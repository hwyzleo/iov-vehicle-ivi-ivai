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
    ROUTE_UNSAFE("IVAI-ROUTE-001");

    companion object {
        fun from(code: String): ErrorCode? = entries.firstOrNull { it.code == code }
    }
}
