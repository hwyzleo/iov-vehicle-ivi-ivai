package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId

/**
 * 第一版领域配额表（IVI-IVAI-DSN-CR-009 领域配额 / REQ-CR-009 数量分配基线）。
 *
 * BD01～BD10 合计 10 Domain、160 Tool、18 Workflow。领域内数量是第一版容量与
 * 治理工作量基线，不是代码常量；跨领域 Workflow 按主要责任领域计数，同时保留
 * 全部 domainIds。
 */
object DomainQuotaCatalog {

    val QUOTAS: List<DomainQuota> = listOf(
        DomainQuota(BusinessDomainId.CABIN_COMFORT, 22, 2, setOf("cabin.climate", "cabin.seat_comfort", "cabin.refrigerator")),
        DomainQuota(BusinessDomainId.BODY_CONTROL, 24, 1, setOf("body.window_roof", "body.door_lock", "body.lighting_wiper")),
        DomainQuota(BusinessDomainId.VEHICLE_DRIVING_CONFIG, 28, 4, setOf("vehicle.driving_config", "vehicle.adas")),
        DomainQuota(BusinessDomainId.ENERGY, 10, 2, setOf("energy.management")),
        DomainQuota(BusinessDomainId.IMAGING_RECORDING, 8, 1, setOf("imaging.recording")),
        DomainQuota(BusinessDomainId.NAVIGATION_TRAVEL, 20, 2, setOf("navigation.route", "navigation.poi_map")),
        DomainQuota(BusinessDomainId.COMMUNICATION, 8, 1, setOf("communication.call_message")),
        DomainQuota(BusinessDomainId.MEDIA_ENTERTAINMENT, 24, 2, setOf("media.audio", "media.video")),
        DomainQuota(BusinessDomainId.APP_SYSTEM, 10, 1, setOf("system.app_desktop")),
        DomainQuota(BusinessDomainId.INFORMATION_SERVICE, 6, 2, setOf("information.weather_calendar", "information.reminder"))
    )

    /** 全部配额 Tool 目标数合计 = 160。 */
    val toolTargetSum: Int = QUOTAS.sumOf { it.toolTarget }

    /** 全部配额 Workflow 目标数合计 = 18。 */
    val workflowTargetSum: Int = QUOTAS.sumOf { it.workflowTarget }

    fun quotaOf(domainId: BusinessDomainId): DomainQuota? =
        QUOTAS.firstOrNull { it.domainId == domainId }

    fun toolTargetOf(domainId: BusinessDomainId): Int =
        quotaOf(domainId)?.toolTarget ?: 0

    fun workflowTargetOf(domainId: BusinessDomainId): Int =
        quotaOf(domainId)?.workflowTarget ?: 0
}
