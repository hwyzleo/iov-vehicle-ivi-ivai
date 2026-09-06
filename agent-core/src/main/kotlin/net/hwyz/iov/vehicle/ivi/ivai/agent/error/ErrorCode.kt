package net.hwyz.iov.vehicle.ivi.ivai.agent.error

/**
 * Global IVAI error codes (IVI-IVAI-DSN-CR-001). Produced at the agent-core
 * orchestration boundary only.
 */
enum class ErrorCode(val code: String) {
    MODEL_UNAVAILABLE("IVAI-MODEL-001"),
    MODEL_RESPONSE_PARSE("IVAI-MODEL-002"),
    OUTPUT_SCHEMA("IVAI-SCHEMA-001"),
    UNKNOWN_TOOL("IVAI-TOOL-001"),
    INVALID_ARGUMENT("IVAI-TOOL-002"),
    POLICY_DENIED("IVAI-POLICY-001"),
    EXECUTION_FAILED("IVAI-EXEC-001"),
    ROUTE_UNSAFE("IVAI-ROUTE-001"),
    METRICS_INVALID("IVAI-METRICS-001"),
    // CR-005: L0 规则冲突或无法唯一确定 Tool
    ROUTE_AMBIGUOUS("IVAI-ROUTE-002"),
    // CR-005: RAG 索引不可用或加载失败
    RAG_INDEX_UNAVAILABLE("IVAI-RAG-001"),
    // CR-005: 索引签名、Hash 或 Manifest 校验失败
    RAG_INDEX_INVALID("IVAI-RAG-002"),
    // CR-005: 索引与 Embedding 模型维度或版本不兼容
    RAG_EMBEDDING_INCOMPATIBLE("IVAI-RAG-003"),
    // CR-005: 检索结果为空或低于阈值
    RAG_RETRIEVAL_EMPTY("IVAI-RAG-004"),
    // CR-005: 索引与当前车型/软件版本不兼容
    RAG_INDEX_INCOMPATIBLE("IVAI-RAG-005"),
    // CR-005: Embedding 推理失败或超时
    RAG_EMBEDDING_FAILED("IVAI-RAG-006"),
    // CR-005: Tool 候选来源非法或不受支持
    CANDIDATE_SOURCE_INVALID("IVAI-TOOL-003"),
    // CR-008: 领域预路由与能力包
    DOMAIN_UNKNOWN("IVAI-DOMAIN-001"),
    DOMAIN_CONFLICT("IVAI-DOMAIN-002"),
    NO_AVAILABLE_CAPABILITY("IVAI-CAP-001"),
    CAPABILITY_INVALID("IVAI-CAP-002"),
    GOVERNANCE_DENIED("IVAI-GOV-001"),
    GOVERNANCE_MISMATCH("IVAI-GOV-002"),
    // CR-008: Workflow
    WORKFLOW_INVALID("IVAI-WORKFLOW-001"),
    WORKFLOW_LIMIT_EXCEEDED("IVAI-WORKFLOW-002"),
    WORKFLOW_FAILED("IVAI-WORKFLOW-003"),
    // CR-008: 执行 Binding
    BINDING_MISSING("IVAI-BINDING-001");

    companion object {
        fun from(code: String): ErrorCode? = entries.firstOrNull { it.code == code }
    }
}

/**
 * CR-010 新增错误码（与 [ErrorCode] 同一定义域，按 CR-010 错误码表新增）：
 * 常量统一委托给 tool-registry 的
 * [net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.Cr010ErrorCodes]（单一来源）。
 */
object Cr010ErrorCode {
    const val CAP_CANONICAL_UNCLOSED =
        net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.Cr010ErrorCodes.CAP_CANONICAL_UNCLOSED
    const val ROUTE_CONFLICT =
        net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.Cr010ErrorCodes.ROUTE_CONFLICT
    const val ROUTE_COVERAGE_MISSING =
        net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.Cr010ErrorCodes.ROUTE_COVERAGE_MISSING
    const val GOV_DRAFT_PROMOTED =
        net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.Cr010ErrorCodes.GOV_DRAFT_PROMOTED
    const val GOV_STUB_EXEMPTION_INVALID =
        net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.Cr010ErrorCodes.GOV_STUB_EXEMPTION_INVALID
    const val ALIAS_CONFLICT =
        net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.Cr010ErrorCodes.ALIAS_CONFLICT
}
