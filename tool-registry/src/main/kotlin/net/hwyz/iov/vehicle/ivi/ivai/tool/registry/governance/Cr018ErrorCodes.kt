package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

/**
 * CR-018 新增错误码（空调相似 Tool 边界 / RAG 治理）。
 *
 * 与 CR-017 的 IVAI-RAG-DOC-001 / IVAI-RAG-INDEX-001 并存；汇总码语义不变
 * （Trace / 导出辅助字段记录细分码）。
 */
object Cr018ErrorCodes {

    /** Tool 检索文档缺少相似 Tool 边界（正对象/动作/负边界/冲突集）。 */
    const val RAG_BOUNDARY = "IVAI-RAG-BOUNDARY-001"

    /** 检索排序未记录对象、动作或槽位证据（混合加权 Trace 缺失）。 */
    const val RAG_BOUNDARY_EVIDENCE = "IVAI-RAG-BOUNDARY-002"
}
