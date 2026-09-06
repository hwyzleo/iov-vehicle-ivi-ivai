package net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore

/**
 * 向量索引持久化（CR-011 本地索引与持久化）。
 *
 * 参考 SQLite schema（LOCAL_EXACT 建议结构）：
 *
 *   SQLite
 *   ├─ rag_index_manifest    # namespace: tool-intent / knowledge
 *   ├─ rag_documents         # document_type + namespace
 *   ├─ rag_vectors           # namespace + document_id
 *   └─ rag_build_state
 *
 * 首期以文件持久化实现相同语义（命名空间隔离 + staging/active/previous + 原子
 * 发布）；SQLite 四表保留为参考 schema，规模/平台需要时可替换实现而不改变
 * VectorStore 上层契约。
 */
interface VectorPersistence {

    /** 原子发布后的 active 索引；无则 null。 */
    fun readActive(namespace: String): StoredIndex?

    /** 上一有效索引（回滚用）；无则 null。 */
    fun readPrevious(namespace: String): StoredIndex?

    /** 写入 staging（未发布，进程终止只删除 staging，不影响 active）。 */
    fun writeStaging(namespace: String, manifest: VectorIndexManifest, documents: List<IndexedDocument>, vectors: List<FloatArray>)

    /** 原子切换 staging → active；旧 active 保留为 previous。 */
    fun publish(namespace: String)

    /** 删除 staging（构建取消 / 失败）。 */
    fun deleteStaging(namespace: String)

    /** 记录构建状态（进行中 / 成功 / 失败 / 取消）。 */
    fun writeBuildState(namespace: String, state: IndexBuildState)

    fun readBuildState(namespace: String): IndexBuildState?
}

/** 已发布/暂存的索引内容。 */
data class StoredIndex(
    val manifest: VectorIndexManifest,
    val documents: List<IndexedDocument>,
    val vectors: List<FloatArray>
)

/** 构建状态（CR-011 构建取消、进程终止或服务失败只删除 staging，不影响 active）。 */
enum class IndexBuildState {
    BUILDING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
