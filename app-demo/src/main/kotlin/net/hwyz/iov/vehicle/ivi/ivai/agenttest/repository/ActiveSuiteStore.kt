package net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.SuiteFingerprint
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.SuiteImportException
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.ActiveSuiteDescriptor

/**
 * 激活 Suite 快照：激活文件原始字节 + 描述符（Repository 加载前做一致性校验）。
 */
data class ActiveSuiteSnapshot(
    val descriptor: ActiveSuiteDescriptor,
    val bytes: ByteArray
)

/**
 * 激活存储读取结果（IVI-IVAI-DSN-CR-015）：区分缺失 / 损坏 / 有效快照，
 * 供 Repository 记录准确的回退原因（IVAI-TEST-IMPORT-008）。
 */
sealed interface ActiveStoreRead {
    /** 无激活文件（未导入过）。 */
    data object Absent : ActiveStoreRead

    /** 文件存在但描述符或内容不可读（损坏 / 部分写入）。 */
    data class Corrupt(val reason: String) : ActiveStoreRead

    /** 有效快照（内容 + 描述符）。 */
    data class Snapshot(val snapshot: ActiveSuiteSnapshot) : ActiveStoreRead
}

/**
 * 激活 Suite 存储（IVI-IVAI-DSN-CR-015）。
 *
 * 外部导入文件复制到应用私有目录：
 *   filesDir/agent-tests/active/agent-regression.json + active-suite.json
 *
 * 原子写入步骤：
 *  写 agent-regression.json.tmp → flush / fsync → 重读校验 SHA-256 →
 *  原子 rename 为正式文件 → 原子更新 active-suite.json。
 * 写入、校验或 rename 失败时删除临时文件并保留上一份有效激活 Suite。
 * 本实现不覆盖 APK assets（纯 JVM，便于单元测试注入临时目录）。
 */
class ActiveSuiteStore(
    private val activeDir: File,
    private val fileName: String = DEFAULT_FILE_NAME,
    private val descriptorFileName: String = DEFAULT_DESCRIPTOR_FILE_NAME,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    private val activeFile: File get() = File(activeDir, fileName)
    private val descriptorFile: File get() = File(activeDir, descriptorFileName)

    /**
     * 原子激活。写入、Hash 校验或替换失败时抛 [SuiteImportException]
     * （IVAI-TEST-IMPORT-006）并删除临时文件，不破坏上一份激活 Suite。
     */
    fun activate(bytes: ByteArray, descriptor: ActiveSuiteDescriptor) {
        try {
            activeDir.mkdirs()
            // 1) 写临时内容文件并 fsync。
            val contentTmp = File(activeDir, "$fileName.tmp")
            FileOutputStream(contentTmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            // 2) 从临时文件重新读取并校验 SHA-256。
            val written = contentTmp.readBytes()
            if (!SuiteFingerprint.matches(written, descriptor.sha256)) {
                throw SuiteImportException(
                    TestErrorCode.IMPORT_ACTIVATION_FAILED,
                    "写入后 Hash 校验不一致（期望 ${descriptor.sha256}）"
                )
            }
            // 3) 原子替换正式内容文件。
            atomicReplace(contentTmp, activeFile)
            // 4) 原子更新描述符文件。
            val descriptorTmp = File(activeDir, "$descriptorFileName.tmp")
            descriptorTmp.writeText(
                json.encodeToString(ActiveSuiteDescriptor.serializer(), descriptor)
            )
            atomicReplace(descriptorTmp, descriptorFile)
        } catch (e: SuiteImportException) {
            cleanupTmp()
            throw e
        } catch (e: Exception) {
            cleanupTmp()
            throw SuiteImportException(
                TestErrorCode.IMPORT_ACTIVATION_FAILED,
                "激活文件写入失败：${e.message}"
            )
        }
    }

    /**
     * 当前激活存储读取结果。激活文件或描述符缺失返回 [ActiveStoreRead.Absent]；
     * 描述符 / 内容损坏返回 [ActiveStoreRead.Corrupt]（视为无有效导入 Suite）。
     */
    fun current(): ActiveStoreRead {
        if (!activeFile.exists() || !descriptorFile.exists()) return ActiveStoreRead.Absent
        val descriptor = try {
            json.decodeFromString(
                ActiveSuiteDescriptor.serializer(),
                descriptorFile.readText()
            )
        } catch (e: Exception) {
            return ActiveStoreRead.Corrupt("激活描述符无法解析或损坏")
        }
        val bytes = try {
            activeFile.readBytes()
        } catch (e: Exception) {
            return ActiveStoreRead.Corrupt("激活文件无法读取")
        }
        return ActiveStoreRead.Snapshot(ActiveSuiteSnapshot(descriptor, bytes))
    }

    /** 原子移动：优先 ATOMIC_MOVE，不支持时退化为删除目标后 renameTo。 */
    private fun atomicReplace(tmp: File, target: File) {
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: AtomicMoveNotSupportedException) {
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }
    }

    private fun cleanupTmp() {
        File(activeDir, "$fileName.tmp").delete()
        File(activeDir, "$descriptorFileName.tmp").delete()
    }

    companion object {
        const val DEFAULT_FILE_NAME = "agent-regression.json"
        const val DEFAULT_DESCRIPTOR_FILE_NAME = "active-suite.json"

        /** 激活目录（相对 filesDir）。 */
        const val DEFAULT_DIR = "agent-tests/active"
    }
}
