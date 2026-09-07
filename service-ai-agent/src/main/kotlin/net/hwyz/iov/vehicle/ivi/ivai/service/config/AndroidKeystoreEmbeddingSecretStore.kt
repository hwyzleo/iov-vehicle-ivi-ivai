package net.hwyz.iov.vehicle.ivi.ivai.service.config

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.hwyz.iov.vehicle.ivi.ivai.model.config.KeyStatus
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SecretStore

/**
 * [SecretStore] for the Embedding Provider API key (CR-011 补齐 · 鉴权配置使用
 * Keystore/安全配置引用，不在日志、索引元数据或治理包中保存明文密钥)。
 *
 * 与 LLM/ASR 密钥隔离：独立 master key 别名与密文文件（ivai_embedding_api_key_*），
 * 非导出 AES/GCM 256，每次加密使用随机 12 字节 IV，密文+IV 落入私有文件。
 */
class AndroidKeystoreEmbeddingSecretStore(context: Context) : SecretStore {

    private val cipherFile = File(context.filesDir, CIPHER_FILE_NAME)

    override suspend fun status(): KeyStatus {
        val bytes = cipherFile.readBytesOrNull() ?: return KeyStatus.NOT_SET
        return runCatching { decrypt(bytes) }
            .fold({ KeyStatus.SET }, { KeyStatus.INVALID })
    }

    override suspend fun write(key: String) {
        val bytes = encrypt(key)
        cipherFile.writeAtomic(bytes)
    }

    override suspend fun read(): String? {
        val bytes = cipherFile.readBytesOrNull() ?: return null
        return runCatching { decrypt(bytes) }.getOrNull()
    }

    override suspend fun clear() {
        cipherFile.delete()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        return iv + encrypted
    }

    private fun decrypt(bytes: ByteArray): String {
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
        val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun File.readBytesOrNull(): ByteArray? = if (exists()) readBytes() else null

    /** Write via a temp file + atomic rename so a crash never leaves half-written ciphertext. */
    private fun File.writeAtomic(bytes: ByteArray) {
        val tmp = File(parentFile, "$name.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(this)) {
            tmp.delete()
            throw IOException("无法写入密钥密文文件 $name")
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ivai_embedding_api_key_master"
        const val CIPHER_FILE_NAME = "embedding_api_key_v1.bin"
        const val AES_GCM_NO_PADDING = "AES/GCM/NoPadding"

        /** Android Keystore AES/GCM default IV size is 12 bytes. */
        const val IV_SIZE = 12
    }
}
