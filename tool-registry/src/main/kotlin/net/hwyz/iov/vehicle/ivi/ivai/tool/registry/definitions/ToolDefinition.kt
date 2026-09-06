package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.ToolAlias

/**
 * Full tool definition (IVI-IVAI-DSN-CR-001 + CR-008).
 *
 * @param functionId compatible legacy Function-ID, or null when not mapped yet
 * @param parameterSchema JSON Schema text describing accepted arguments
 * @param policy risk / confirmation / precondition policy
 * @param execution adapter + method binding
 * @param domainId suggested business domain (IVI-IVAI-DSN-CR-008) — defaults to
 *   CABIN_COMFORT so legacy definitions without a domain still compile
 * @param capabilityPackId owning capability pack (CR-008); used by the domain /
 *   pack scoped L0 / retrieval
 * @param supportedOperations operation types this tool can express (CR-008)
 * @param aliases legacy Tool ID / Function-ID / expression mappings for traceability
 * @param governanceVersion governance version of this definition (CR-008)
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
    val availability: ToolAvailability = ToolAvailability(),
    /** Suggested business domain (IVI-IVAI-DSN-CR-008). */
    val domainId: BusinessDomainId = BusinessDomainId.CABIN_COMFORT,
    /** Owning capability pack (IVI-IVAI-DSN-CR-008). */
    val capabilityPackId: String = "cabin.climate",
    /** Operation types this tool can express (IVI-IVAI-DSN-CR-008). */
    val supportedOperations: Set<OperationType> = setOf(OperationType.CONTROL),
    /** Legacy / expression aliases for traceability (IVI-IVAI-DSN-CR-008). */
    val aliases: List<ToolAlias> = emptyList(),
    /** Governance version of this definition (IVI-IVAI-DSN-CR-008). */
    val governanceVersion: String = "1.0"
)
