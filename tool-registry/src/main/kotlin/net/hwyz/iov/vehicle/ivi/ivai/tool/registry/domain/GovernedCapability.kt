package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain

/**
 * 语音 AI 标准功能目录的 18 个原始一级领域（保留层，IVI-IVAI-REQ-CR-008）。
 * 原始分类、来源 Sheet、Function-ID 与代表说法一律保留，作为 source taxonomy，
 * 由 [DomainMigration] 映射到建议业务领域 / 操作类型 / 能力包。
 */
enum class OriginalDomain(val label: String) {
    AIR_CONDITIONING("空调与舒适"),
    CABIN_SYSTEM("座舱系统"),
    DIRECT_VEHICLE_CONTROL("直接车控"),
    VEHICLE_CONFIG("车辆配置"),
    PAGE_NAVIGATION("页面导航"),
    STATUS_QUERY("状态查询"),
    APP_DESKTOP("应用与桌面"),
    IMAGING_RECORDING("影像与记录"),
    ENERGY_BATTERY("能源与电池"),
    SCENE_MODE("场景模式"),
    NATURAL_EXPRESSION("自然表达"),
    NAVIGATION_MAP("导航地图"),
    COMMUNICATION("通讯"),
    MUSIC_AUDIO("音乐音频"),
    ONLINE_VIDEO("在线视频"),
    INFORMATION_SERVICE("信息服务"),
    SEMANTIC_SUPPORT("语义支撑"),
    EXECUTION_ADAPTER("执行适配")
}

/**
 * 保留层：语音 AI 标准功能目录中的一条原始功能记录，只读追溯。
 * 原始领域、原始二级功能、Function-ID、来源 Sheet、原始记录键与代表说法
 * 必须保留，不得被治理结果覆盖。
 */
data class OriginalFeatureRecord(
    val originalDomain: OriginalDomain,
    val originalSubFunction: String,
    val functionId: String? = null,
    val sourceSheet: String? = null,
    val originalRecordKey: String,
    val representativeUtterance: String
)

/**
 * 治理层：由原始条目映射/归并后的受控能力（IVI-IVAI-DSN-CR-008 治理数据模型）。
 *
 * OriginalFeatureRecord → GovernedCapability 的映射保留：
 *   BusinessDomain、OperationType、SemanticFeatures、Tool/Workflow、
 *   ToolAlias/Function-ID、ExecutionBinding、GovernanceStatus/Version/Reason。
 *
 * [bindingStatus] 为 [BindingStatus.BOUND] 且 [status] 为 APPROVED 时才允许进入
 * 运行时索引；否则被 IVAI-GOV-001 / IVAI-BINDING-001 阻止。
 */
data class GovernedCapability(
    val capabilityId: String,
    val domainId: BusinessDomainId,
    val operationType: OperationType,
    val semanticFeatures: Set<SemanticFeature>,
    val toolId: String? = null,
    val workflowId: String? = null,
    val capabilityPackId: String? = null,
    val status: GovernanceStatus,
    val governanceVersion: String,
    val governanceReason: String,
    val sources: List<OriginalFeatureRecord> = emptyList(),
    val functionIds: List<String> = emptyList(),
    val bindingStatus: BindingStatus = BindingStatus.NO_BINDING
)

/**
 * 现有一级领域 → 建议治理方式（IVI-IVAI-REQ-CR-008「现有一级领域治理映射表」）。
 *
 * [suggestedDomain] 为空表示该原始领域不作为业务领域（页面导航 → NAVIGATE_UI、
 * 状态查询 → QUERY、自然表达/语义支撑 → 横切语义能力、执行适配 → Binding 元数据、
 * 场景模式 → Workflow）。
 */
data class DomainMigration(
    val originalDomain: OriginalDomain,
    val suggestedDomain: BusinessDomainId?,
    val operationType: OperationType? = null,
    val capabilityPacks: List<String> = emptyList(),
    val governance: String
)

/** 18 个原始领域的全量治理映射（来源：IVI-IVAI-REQ-CR-008 映射表）。 */
object DomainMigrationCatalog {

    val MIGRATIONS: List<DomainMigration> = listOf(
        DomainMigration(OriginalDomain.AIR_CONDITIONING, BusinessDomainId.CABIN_COMFORT, null, listOf("cabin.climate", "cabin.seat_comfort"), "映射至 BD01 座舱舒适"),
        DomainMigration(OriginalDomain.CABIN_SYSTEM, BusinessDomainId.APP_SYSTEM, null, listOf("system.app_desktop"), "按功能拆分至 BD09；车辆个性化项可映射至 BD03"),
        DomainMigration(OriginalDomain.DIRECT_VEHICLE_CONTROL, null, null, listOf("cabin.climate", "body.window_door", "body.lighting_wiper", "vehicle.driving_mode"), "按对象拆分至 BD01、BD02 或 BD03"),
        DomainMigration(OriginalDomain.VEHICLE_CONFIG, BusinessDomainId.VEHICLE_DRIVING_CONFIG, OperationType.CONFIGURE, listOf("vehicle.driving_mode", "vehicle.adas_config"), "映射至 BD03；查询类另标记 QUERY"),
        DomainMigration(OriginalDomain.PAGE_NAVIGATION, null, OperationType.NAVIGATE_UI, emptyList(), "不作为业务领域，改为 NAVIGATE_UI 操作类型，并关联实际业务领域"),
        DomainMigration(OriginalDomain.STATUS_QUERY, null, OperationType.QUERY, emptyList(), "不作为业务领域，改为 QUERY 操作类型，并关联被查询对象领域"),
        DomainMigration(OriginalDomain.APP_DESKTOP, BusinessDomainId.APP_SYSTEM, null, listOf("system.app_desktop"), "映射至 BD09；具体媒体、导航功能映射至对应业务领域"),
        DomainMigration(OriginalDomain.IMAGING_RECORDING, BusinessDomainId.IMAGING_RECORDING, null, emptyList(), "映射至 BD05"),
        DomainMigration(OriginalDomain.ENERGY_BATTERY, BusinessDomainId.ENERGY, null, listOf("energy.charging"), "映射至 BD04"),
        DomainMigration(OriginalDomain.SCENE_MODE, null, OperationType.WORKFLOW, emptyList(), "改为 Workflow；其步骤分别关联实际业务领域和 Tool"),
        DomainMigration(OriginalDomain.NATURAL_EXPRESSION, null, null, emptyList(), "改为横切语义能力和评测样例"),
        DomainMigration(OriginalDomain.NAVIGATION_MAP, BusinessDomainId.NAVIGATION_TRAVEL, null, listOf("navigation.route", "navigation.poi"), "映射至 BD06"),
        DomainMigration(OriginalDomain.COMMUNICATION, BusinessDomainId.COMMUNICATION, null, emptyList(), "映射至 BD07"),
        DomainMigration(OriginalDomain.MUSIC_AUDIO, BusinessDomainId.MEDIA_ENTERTAINMENT, null, listOf("media.audio"), "映射至 BD08 的音频能力包"),
        DomainMigration(OriginalDomain.ONLINE_VIDEO, BusinessDomainId.MEDIA_ENTERTAINMENT, null, listOf("media.video"), "映射至 BD08 的视频能力包"),
        DomainMigration(OriginalDomain.INFORMATION_SERVICE, BusinessDomainId.INFORMATION_SERVICE, null, emptyList(), "映射至 BD10"),
        DomainMigration(OriginalDomain.SEMANTIC_SUPPORT, null, null, emptyList(), "改为横切语义能力、上下文策略或输入增强配置"),
        DomainMigration(OriginalDomain.EXECUTION_ADAPTER, null, null, emptyList(), "改为 Execution Binding 元数据")
    )
}
