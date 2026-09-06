package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain

/**
 * P0 气候工具的治理资产（IVI-IVAI-DSN-CR-008 / REQ-CR-008）。
 *
 * 包含两层：
 *  1. 现有 6 个运行时 Tool 的 GovernedCapability（APPROVED + BOUND）——P0 验证集。
 *  2. 参数化 Tool 建议 `climate.temperature.adjust`：把「主驾升温、副驾升温、
 *     主驾降温、副驾降温」按 IVI-IVAI-REQ-071 归并为单一参数化 Tool
 *     （position/direction/step），原始四项作为 OriginalFeatureRecord 保留。
 *     因 Mock 适配器尚未提供 adjust Binding，[BindingStatus.PENDING] →
 *     被 IVAI-BINDING-001 阻止进入运行时索引（下一版 CR 注册并绑定）。
 */
object ClimateGovernedCapabilities {

    /** 现有 6 个空调 Tool 的治理条目（APPROVED + BOUND）。 */
    val RUNTIME: List<GovernedCapability> = listOf(
        governed(
            "cap.climate.power_on", "climate.power_on", "cabin.climate",
            OperationType.CONTROL, setOf(SemanticFeature.EXPLICIT_COMMAND),
            "打开空调电源", "AC_Control_1", BindingStatus.BOUND
        ),
        governed(
            "cap.climate.power_off", "climate.power_off", "cabin.climate",
            OperationType.CONTROL, setOf(SemanticFeature.EXPLICIT_COMMAND),
            "关闭空调电源", "AC_Control_2", BindingStatus.BOUND
        ),
        governed(
            "cap.climate.temperature_increase", "climate.temperature_increase", "cabin.climate",
            OperationType.CONTROL, setOf(SemanticFeature.EXPLICIT_COMMAND, SemanticFeature.IMPLICIT_EXPRESSION),
            "升温（含隐式冷表达）", "AC_Temperature_2", BindingStatus.BOUND
        ),
        governed(
            "cap.climate.temperature_decrease", "climate.temperature_decrease", "cabin.climate",
            OperationType.CONTROL, setOf(SemanticFeature.EXPLICIT_COMMAND, SemanticFeature.IMPLICIT_EXPRESSION),
            "降温（含隐式热表达）", "AC_Temperature_3", BindingStatus.BOUND
        ),
        governed(
            "cap.climate.temperature_set", "climate.temperature_set", "cabin.climate",
            OperationType.CONTROL, setOf(SemanticFeature.EXPLICIT_COMMAND),
            "设定绝对温度", "AC_Temperature_1", BindingStatus.BOUND
        ),
        governed(
            "cap.climate.status_query", "climate.status_query", "cabin.climate",
            OperationType.QUERY, setOf(SemanticFeature.EXPLICIT_COMMAND),
            "查询空调状态（QUERY 关联被查询对象）", null, BindingStatus.BOUND
        )
    )

    /**
     * 参数化 Tool 建议（IVI-IVAI-REQ-071 / EARS #3）：语义同构、仅参数不同的
     * 主驾/副驾升温降温归并为 climate.temperature.adjust。
     */
    val PARAMETERIZED_SUGGESTION: GovernedCapability = GovernedCapability(
        capabilityId = "cap.climate.temperature.adjust",
        domainId = BusinessDomainId.CABIN_COMFORT,
        operationType = OperationType.CONTROL,
        semanticFeatures = setOf(SemanticFeature.EXPLICIT_COMMAND),
        toolId = "climate.temperature.adjust",
        workflowId = null,
        capabilityPackId = "cabin.climate",
        status = GovernanceStatus.APPROVED,
        governanceVersion = "1.0",
        governanceReason = "归并主驾/副驾升温降温为参数化 Tool（position/direction/step），待适配器绑定",
        sources = listOf(
            feature(OriginalDomain.AIR_CONDITIONING, "主驾升温", "AC_Temperature_2", "主驾升温"),
            feature(OriginalDomain.AIR_CONDITIONING, "副驾升温", "AC_Temperature_2", "副驾升温"),
            feature(OriginalDomain.AIR_CONDITIONING, "主驾降温", "AC_Temperature_3", "主驾降温"),
            feature(OriginalDomain.AIR_CONDITIONING, "副驾降温", "AC_Temperature_3", "副驾降温")
        ),
        functionIds = listOf("AC_Temperature_2", "AC_Temperature_3"),
        bindingStatus = BindingStatus.PENDING
    )

    private fun governed(
        capabilityId: String,
        toolId: String,
        packId: String,
        operationType: OperationType,
        semanticFeatures: Set<SemanticFeature>,
        reason: String,
        functionId: String?,
        binding: BindingStatus
    ) = GovernedCapability(
        capabilityId = capabilityId,
        domainId = BusinessDomainId.CABIN_COMFORT,
        operationType = operationType,
        semanticFeatures = semanticFeatures,
        toolId = toolId,
        workflowId = null,
        capabilityPackId = packId,
        status = GovernanceStatus.APPROVED,
        governanceVersion = "1.0",
        governanceReason = reason,
        sources = listOf(feature(OriginalDomain.AIR_CONDITIONING, toolId, functionId, toolId)),
        functionIds = listOfNotNull(functionId),
        bindingStatus = binding
    )

    private fun feature(
        originalDomain: OriginalDomain,
        subFunction: String,
        functionId: String?,
        utterance: String
    ) = OriginalFeatureRecord(
        originalDomain = originalDomain,
        originalSubFunction = subFunction,
        functionId = functionId,
        sourceSheet = "语音AI标准功能目录",
        originalRecordKey = "ivai.fn.$subFunction",
        representativeUtterance = utterance
    )

    /** 全部治理条目（运行时 + 参数化建议）。 */
    val ALL: List<GovernedCapability> = RUNTIME + PARAMETERIZED_SUGGESTION

    /**
     * 运行时允许进入索引的 Tool/Workflow：APPROVED 且 BOUND。
     * 未批准 / 待确认 / 无 Binding（IVAI-GOV-001 / IVAI-BINDING-001）一律排除。
     */
    fun runtimeIndexable(): List<GovernedCapability> =
        ALL.filter { it.status == GovernanceStatus.APPROVED && it.bindingStatus == BindingStatus.BOUND }
}
