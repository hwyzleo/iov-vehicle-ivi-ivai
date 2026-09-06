package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus

/**
 * Capability Pack 治理条目（IVI-IVAI-DSN-CR-009 Capability Pack Catalog v1）。
 *
 * 与 CR-008 运行时 [net.hwyz.iov.vehicle.ivi.ivai.tool.registry.capability.CapabilityPack]
 * 区分：这里是治理目录条目（默认 DRAFT，不得进入运行时）；[toolTarget] /
 * [workflowTarget] 为该 Pack 的规划配额。
 */
data class CapabilityPackGovernanceSpec(
    val packId: String,
    val domainId: BusinessDomainId,
    val name: String,
    val responsibility: String,
    val toolTarget: Int,
    val workflowTarget: Int,
    val priority: ImplementationPriority,
    val governanceVersion: String = "ivai-governance-v1-draft",
    val status: GovernanceStatus = GovernanceStatus.DRAFT
)

/**
 * IVAI Capability Pack Catalog v1（IVI-IVAI-DSN-CR-009 规范性附录）。
 *
 * 固化 18 个 Pack 的 packId、Domain、职责边界、Tool/Workflow 目标数、优先级和
 * 治理版本。Tool 与 Workflow 通过 packId 归属到 Pack；Manifest 按此表校验
 * 目标配额与引用完整性。
 */
object CapabilityPackCatalogV1 {

    val ALL: List<CapabilityPackGovernanceSpec> = listOf(
        spec("cabin.climate", BusinessDomainId.CABIN_COMFORT, "空调与温控",
            "空调电源、温度、风量、风向、循环、除霜及空气质量", 13, 1, ImplementationPriority.P0),
        spec("cabin.seat_comfort", BusinessDomainId.CABIN_COMFORT, "座椅与舒适",
            "座椅加热、通风、按摩及方向盘加热", 6, 1, ImplementationPriority.P0),
        spec("cabin.refrigerator", BusinessDomainId.CABIN_COMFORT, "车载冰箱",
            "冰箱模式、温度与状态", 3, 0, ImplementationPriority.P1),
        spec("body.window_roof", BusinessDomainId.BODY_CONTROL, "车窗与天窗",
            "车窗、天窗及遮阳帘控制", 7, 0, ImplementationPriority.P0),
        spec("body.door_lock", BusinessDomainId.BODY_CONTROL, "车门与门锁",
            "车门、尾门、门锁、儿童锁", 7, 1, ImplementationPriority.P0),
        spec("body.lighting_wiper", BusinessDomainId.BODY_CONTROL, "灯光与雨刮",
            "外灯、氛围灯、阅读灯、雨刮和后视镜", 10, 0, ImplementationPriority.P0),
        spec("vehicle.driving_config", BusinessDomainId.VEHICLE_DRIVING_CONFIG, "驾驶与车辆配置",
            "驾驶、能源、转向、悬架、制动和车辆个性化设置", 18, 2, ImplementationPriority.P1),
        spec("vehicle.adas", BusinessDomainId.VEHICLE_DRIVING_CONFIG, "ADAS 设置",
            "ADAS 开关、灵敏度、模式及状态", 10, 2, ImplementationPriority.P1),
        spec("energy.management", BusinessDomainId.ENERGY, "能源与补能",
            "充放电、电池、能耗和补能设置", 10, 2, ImplementationPriority.P3),
        spec("imaging.recording", BusinessDomainId.IMAGING_RECORDING, "影像与记录",
            "环视、摄像头、透明底盘与行车记录", 8, 1, ImplementationPriority.P3),
        spec("navigation.route", BusinessDomainId.NAVIGATION_TRAVEL, "导航与路线",
            "目的地、路线、途经点和导航控制", 12, 1, ImplementationPriority.P2),
        spec("navigation.poi_map", BusinessDomainId.NAVIGATION_TRAVEL, "POI 与地图",
            "POI 搜索、地图视图、路况和收藏", 8, 1, ImplementationPriority.P2),
        spec("communication.call_message", BusinessDomainId.COMMUNICATION, "通讯",
            "联系人、拨号、通话和消息", 8, 1, ImplementationPriority.P2),
        spec("media.audio", BusinessDomainId.MEDIA_ENTERTAINMENT, "音乐与音频",
            "音乐、广播、有声内容与播放控制", 15, 1, ImplementationPriority.P2),
        spec("media.video", BusinessDomainId.MEDIA_ENTERTAINMENT, "在线视频",
            "视频搜索、播放、选集与投屏", 9, 1, ImplementationPriority.P2),
        spec("system.app_desktop", BusinessDomainId.APP_SYSTEM, "应用与桌面",
            "应用、桌面、屏幕、网络和语音系统设置", 10, 1, ImplementationPriority.P1),
        spec("information.weather_calendar", BusinessDomainId.INFORMATION_SERVICE, "天气与日历",
            "天气、日历和事件查询", 3, 1, ImplementationPriority.P3),
        spec("information.reminder", BusinessDomainId.INFORMATION_SERVICE, "提醒服务",
            "提醒创建、管理和查询", 3, 1, ImplementationPriority.P3)
    )

    private fun spec(
        packId: String,
        domainId: BusinessDomainId,
        name: String,
        responsibility: String,
        toolTarget: Int,
        workflowTarget: Int,
        priority: ImplementationPriority
    ) = CapabilityPackGovernanceSpec(
        packId = packId,
        domainId = domainId,
        name = name,
        responsibility = responsibility,
        toolTarget = toolTarget,
        workflowTarget = workflowTarget,
        priority = priority
    )

    fun get(packId: String): CapabilityPackGovernanceSpec? =
        ALL.firstOrNull { it.packId == packId }

    val toolTargetSum: Int = ALL.sumOf { it.toolTarget }

    val workflowTargetSum: Int = ALL.sumOf { it.workflowTarget }
}
