package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions

/**
 * Full tool definition (IVI-IVAI-DSN-CR-001).
 *
 * @param functionId compatible legacy Function-ID, or null when not mapped yet
 * @param parameterSchema JSON Schema text describing accepted arguments
 * @param policy risk / confirmation / precondition policy
 * @param execution adapter + method binding
 */
data class ToolDefinition(
    val toolId: String,
    val functionId: String?,
    val name: String,
    val description: String,
    val positiveExamples: List<String>,
    val negativeExamples: List<String>,
    val selectionPriority: Int,
    val parameterSchema: String,
    val policy: ToolPolicy,
    val execution: ToolExecutionBinding,
    /** L0 deterministic routing rules (IVI-IVAI-DSN-CR-005); empty = never L0. */
    val deterministicRules: List<DeterministicIntentRule> = emptyList(),
    /** Deployment / vehicle / version availability (IVI-IVAI-DSN-CR-005). */
    val availability: ToolAvailability = ToolAvailability()
)
