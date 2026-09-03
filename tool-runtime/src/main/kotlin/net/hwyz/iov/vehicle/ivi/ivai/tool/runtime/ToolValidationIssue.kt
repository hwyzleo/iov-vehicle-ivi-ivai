package net.hwyz.iov.vehicle.ivi.ivai.tool.runtime

/**
 * Category of a validation failure. agent-core maps these to IVAI-TOOL-* codes.
 */
enum class ValidationIssueKind {
    UNKNOWN_TOOL,
    NOT_OBJECT,
    MISSING_ARGUMENT,
    INVALID_ARGUMENT_TYPE,
    OUT_OF_RANGE,
    NOT_IN_ENUM
}

/**
 * A single validation problem for a tool intent.
 */
data class ToolValidationIssue(
    val kind: ValidationIssueKind,
    val toolId: String?,
    val argument: String?,
    val message: String
)
