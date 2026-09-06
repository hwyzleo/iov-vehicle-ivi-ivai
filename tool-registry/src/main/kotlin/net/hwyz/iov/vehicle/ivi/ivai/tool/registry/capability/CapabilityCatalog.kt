package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.capability

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus

/**
 * 建议能力包目录（IVI-IVAI-DSN-CR-008「建议能力包示例」）。
 *
 * 首期（P0）只启用 [CABIN_CLIMATE]：现有 6 个空调 Tool 作为 P0 验证集。
 * 其余 Pack 作为治理定义保留（DRAFT / NEEDS_REVIEW），不得进入运行时——
 * 对应设计「首期只对 P0 范围启用新路由，其余领域继续使用旧链路或不可执行
 * 治理状态」。下一版 CR 治理导航/媒体/应用领域时再逐个提升治理状态。
 */
object CapabilityCatalog {

    /** P0：座舱舒适 · 空调，已批准并启用，包含现有 6 个空调 Tool。 */
    val CABIN_CLIMATE = CapabilityPack(
        packId = "cabin.climate",
        domainId = BusinessDomainId.CABIN_COMFORT,
        name = "空调与温控",
        toolIds = setOf(
            "climate.power_on",
            "climate.power_off",
            "climate.temperature_increase",
            "climate.temperature_decrease",
            "climate.temperature_set",
            "climate.status_query"
        ),
        workflowIds = setOf("cabin.camping_mode"),
        applicableSoftware = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.VersionConstraint(min = "0.1.0"),
        governanceVersion = "1.0",
        enabled = true,
        status = GovernanceStatus.APPROVED
    )

    /** 治理定义：座舱舒适 · 座椅。P1 启用。 */
    val CABIN_SEAT_COMFORT = CapabilityPack(
        packId = "cabin.seat_comfort",
        domainId = BusinessDomainId.CABIN_COMFORT,
        name = "座椅舒适",
        toolIds = setOf("seat.heat", "seat.ventilation"),
        governanceVersion = "0.1",
        enabled = false,
        status = GovernanceStatus.DRAFT
    )

    /** 治理定义：车身控制 · 车窗车门。P1 启用。 */
    val BODY_WINDOW_DOOR = CapabilityPack(
        packId = "body.window_door",
        domainId = BusinessDomainId.BODY_CONTROL,
        name = "车窗车门",
        toolIds = setOf("window.adjust", "door.lock"),
        governanceVersion = "0.1",
        enabled = false,
        status = GovernanceStatus.DRAFT
    )

    /** 治理定义：车身控制 · 灯光雨刮。P1 启用。 */
    val BODY_LIGHTING_WIPER = CapabilityPack(
        packId = "body.lighting_wiper",
        domainId = BusinessDomainId.BODY_CONTROL,
        name = "灯光雨刮",
        toolIds = setOf("light.adjust", "wiper.control"),
        governanceVersion = "0.1",
        enabled = false,
        status = GovernanceStatus.DRAFT
    )

    /** 治理定义：车辆设置与驾驶 · 驾驶模式。P1 启用。 */
    val VEHICLE_DRIVING_MODE = CapabilityPack(
        packId = "vehicle.driving_mode",
        domainId = BusinessDomainId.VEHICLE_DRIVING_CONFIG,
        name = "驾驶模式",
        toolIds = setOf("driving_mode.set"),
        governanceVersion = "0.1",
        enabled = false,
        status = GovernanceStatus.DRAFT
    )

    /** 治理定义：车辆设置与驾驶 · ADAS 配置。P1 启用。 */
    val VEHICLE_ADAS_CONFIG = CapabilityPack(
        packId = "vehicle.adas_config",
        domainId = BusinessDomainId.VEHICLE_DRIVING_CONFIG,
        name = "ADAS 配置",
        toolIds = setOf("adas.config"),
        governanceVersion = "0.1",
        enabled = false,
        status = GovernanceStatus.DRAFT
    )

    /** 治理定义：能源与补能 · 充电。P3 启用。 */
    val ENERGY_CHARGING = CapabilityPack(
        packId = "energy.charging",
        domainId = BusinessDomainId.ENERGY,
        name = "充电管理",
        toolIds = setOf("charging.status_query", "charging.navigate_ui"),
        governanceVersion = "0.1",
        enabled = false,
        status = GovernanceStatus.DRAFT
    )

    /** 治理定义：导航与出行 · 路线。P2 启用。 */
    val NAVIGATION_ROUTE = CapabilityPack(
        packId = "navigation.route",
        domainId = BusinessDomainId.NAVIGATION_TRAVEL,
        name = "路线导航",
        toolIds = setOf("navigation.route_start", "navigation.route_cancel"),
        governanceVersion = "0.1",
        enabled = false,
        status = GovernanceStatus.DRAFT
    )

    /** 治理定义：导航与出行 · POI。P2 启用。 */
    val NAVIGATION_POI = CapabilityPack(
        packId = "navigation.poi",
        domainId = BusinessDomainId.NAVIGATION_TRAVEL,
        name = "POI 检索",
        toolIds = setOf("navigation.poi_search"),
        governanceVersion = "0.1",
        enabled = false,
        status = GovernanceStatus.DRAFT
    )

    /** 治理定义：媒体娱乐 · 音频。P2 启用。 */
    val MEDIA_AUDIO = CapabilityPack(
        packId = "media.audio",
        domainId = BusinessDomainId.MEDIA_ENTERTAINMENT,
        name = "音频播放",
        toolIds = setOf("media.audio_playback"),
        governanceVersion = "0.1",
        enabled = false,
        status = GovernanceStatus.DRAFT
    )

    /** 治理定义：媒体娱乐 · 视频。P2 启用。 */
    val MEDIA_VIDEO = CapabilityPack(
        packId = "media.video",
        domainId = BusinessDomainId.MEDIA_ENTERTAINMENT,
        name = "视频播放",
        toolIds = setOf("media.video_playback"),
        governanceVersion = "0.1",
        enabled = false,
        status = GovernanceStatus.DRAFT
    )

    /** 治理定义：应用与系统 · 桌面。P1 启用。 */
    val SYSTEM_APP_DESKTOP = CapabilityPack(
        packId = "system.app_desktop",
        domainId = BusinessDomainId.APP_SYSTEM,
        name = "应用桌面",
        toolIds = setOf("app.open", "app.close"),
        governanceVersion = "0.1",
        enabled = false,
        status = GovernanceStatus.DRAFT
    )

    /** 全部能力包（含未启用治理定义）。 */
    val ALL: List<CapabilityPack> = listOf(
        CABIN_CLIMATE,
        CABIN_SEAT_COMFORT,
        BODY_WINDOW_DOOR,
        BODY_LIGHTING_WIPER,
        VEHICLE_DRIVING_MODE,
        VEHICLE_ADAS_CONFIG,
        ENERGY_CHARGING,
        NAVIGATION_ROUTE,
        NAVIGATION_POI,
        MEDIA_AUDIO,
        MEDIA_VIDEO,
        SYSTEM_APP_DESKTOP
    )

    /** 当前运行环境实际可用的 Pack（P0 首期：仅空调）。 */
    val AVAILABLE: List<CapabilityPack> = ALL.filter { it.available }

    fun get(packId: String): CapabilityPack? = ALL.firstOrNull { it.packId == packId }
}
