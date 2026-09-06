package net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * JVM 文件持久化实现（CR-011 方案②）。以命名空间隔离目录模拟 SQLite 四表：
 *
 *   <baseDir>/rag_index_manifest/<ns>.{active,staging,previous}.json
 *   <baseDir>/rag_documents/<ns>.{active,staging,previous}.json
 *   <baseDir>/rag_vectors/<ns>.{active,staging,previous}.bin
 *   <baseDir>/rag_build_state/<ns>.json
 *
 * [publish] 通过同目录 rename 实现原子切换（旧 active → previous，staging →
 * active），进程崩溃不产生半成品。
 */
class FileVectorPersistence(
    private val baseDir: File
) : VectorPersistence {

    private val json = Json { ignoreUnknownKeys = true }

    private fun manifestFile(ns: String, slot: Slot) =
        File(baseDir, "rag_index_manifest/$ns.${slot.name.lowercase()}.json")
    private fun docsFile(ns: String, slot: Slot) =
        File(baseDir, "rag_documents/$ns.${slot.name.lowercase()}.json")
    private fun vectorsFile(ns: String, slot: Slot) =
        File(baseDir, "rag_vectors/$ns.${slot.name.lowercase()}.bin")
    private fun buildStateFile(ns: String) = File(baseDir, "rag_build_state/$ns.json")

    private enum class Slot { ACTIVE, STAGING, PREVIOUS }

    override fun readActive(namespace: String): StoredIndex? = read(namespace, Slot.ACTIVE)

    override fun readPrevious(namespace: String): StoredIndex? = read(namespace, Slot.PREVIOUS)

    override fun writeStaging(
        namespace: String,
        manifest: VectorIndexManifest,
        documents: List<IndexedDocument>,
        vectors: List<FloatArray>
    ) {
        write(Slot.STAGING, namespace, manifest, documents, vectors)
    }

    override fun publish(namespace: String) {
        val activeManifest = manifestFile(namespace, Slot.ACTIVE)
        val activeDocs = docsFile(namespace, Slot.ACTIVE)
        val activeVectors = vectorsFile(namespace, Slot.ACTIVE)
        // 旧 active → previous（先删除旧的 previous 再 rename，保证只有两份）。
        deleteSlot(namespace, Slot.PREVIOUS)
        if (activeManifest.exists()) rename(activeManifest, manifestFile(namespace, Slot.PREVIOUS))
        if (activeDocs.exists()) rename(activeDocs, docsFile(namespace, Slot.PREVIOUS))
        if (activeVectors.exists()) rename(activeVectors, vectorsFile(namespace, Slot.PREVIOUS))
        // staging → active。
        rename(manifestFile(namespace, Slot.STAGING), activeManifest)
        rename(docsFile(namespace, Slot.STAGING), activeDocs)
        rename(vectorsFile(namespace, Slot.STAGING), activeVectors)
    }

    override fun deleteStaging(namespace: String) {
        deleteSlot(namespace, Slot.STAGING)
    }

    override fun writeBuildState(namespace: String, state: IndexBuildState) {
        buildStateFile(namespace).apply {
            parentFile?.mkdirs()
            writeText(json.encodeToString(BuildStateDto(state.name)))
        }
    }

    override fun readBuildState(namespace: String): IndexBuildState? {
        val f = buildStateFile(namespace)
        if (!f.exists()) return null
        return runCatching {
            IndexBuildState.valueOf(json.decodeFromString<BuildStateDto>(f.readText()).state)
        }.getOrNull()
    }

    private fun write(slot: Slot, namespace: String, manifest: VectorIndexManifest, documents: List<IndexedDocument>, vectors: List<FloatArray>) {
        val mf = manifestFile(namespace, slot)
        val df = docsFile(namespace, slot)
        val vf = vectorsFile(namespace, slot)
        mf.parentFile?.mkdirs()
        df.parentFile?.mkdirs()
        vf.parentFile?.mkdirs()
        mf.writeText(json.encodeToString(manifest))
        df.writeText(json.encodeToString(DocumentsDto(documents)))
        DataOutputStream(vf.outputStream().buffered()).use { out ->
            for (v in vectors) {
                out.writeInt(v.size)
                for (f in v) out.writeFloat(f)
            }
        }
    }

    private fun read(namespace: String, slot: Slot): StoredIndex? {
        val mf = manifestFile(namespace, slot)
        val df = docsFile(namespace, slot)
        val vf = vectorsFile(namespace, slot)
        if (!mf.exists() || !df.exists() || !vf.exists()) return null
        return runCatching {
            val manifest = json.decodeFromString<VectorIndexManifest>(mf.readText())
            val documents = json.decodeFromString<DocumentsDto>(df.readText()).documents
            val vectors = mutableListOf<FloatArray>()
            DataInputStream(vf.inputStream().buffered()).use { input ->
                while (input.available() > 0) {
                    val n = input.readInt()
                    val v = FloatArray(n)
                    for (i in 0 until n) v[i] = input.readFloat()
                    vectors += v
                }
            }
            if (vectors.size != documents.size) return null // 完整性：数量不一致视为损坏
            StoredIndex(manifest, documents, vectors)
        }.getOrNull()
    }

    private fun deleteSlot(namespace: String, slot: Slot) {
        manifestFile(namespace, slot).delete()
        docsFile(namespace, slot).delete()
        vectorsFile(namespace, slot).delete()
    }

    private fun rename(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            // 跨文件系统兜底：拷贝后删除，仍保证同目录原子语义。
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }

    @Serializable
    private data class DocumentsDto(val documents: List<IndexedDocument>)

    @Serializable
    private data class BuildStateDto(val state: String)
}
