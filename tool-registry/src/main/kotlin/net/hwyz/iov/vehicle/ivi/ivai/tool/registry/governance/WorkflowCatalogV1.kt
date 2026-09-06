package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BindingStatus
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus

/**
 * Workflow 治理条目（IVI-IVAI-DSN-CR-009 Workflow Catalog v1）。
 *
 * 每个 Workflow 固化：Workflow ID、Owner Domain、涉及 Domain、触发参数、
 * 步骤 Tool（顺序）、失败/补偿策略、优先级和治理状态（默认 DRAFT）。
 *
 * 开发 AI 不得用自由规划替代已注册 Workflow；Workflow 只表达多步骤、跨能力、
 * 场景化执行和补偿逻辑，单个配置动作继续使用 Tool。
 */
data class WorkflowGovernanceSpec(
    val workflowId: String,
    val name: String,
    val ownerDomainId: BusinessDomainId,
    val domainIds: Set<BusinessDomainId>,
    val triggerParams: String,
    val stepToolIds: List<String>,
    val failurePolicy: String,
    val priority: ImplementationPriority,
    val governanceVersion: String = "ivai-governance-v1-draft",
    val status: GovernanceStatus = GovernanceStatus.DRAFT,
    val bindingStatus: BindingStatus = BindingStatus.NO_BINDING
)

/**
 * IVAI Workflow Catalog v1（IVI-IVAI-DSN-CR-009 规范性附录）。
 *
 * 固化 18 个具体 Workflow。第一版 18 个 Workflow 覆盖：露营、小憩/睡眠、宠物、
 * 洗车、舒适小床、迎宾/离车、影院、长途出行、回家/上班、充电准备、对外放电、
 * 越野/穿越、涉水准备、恶劣天气、儿童乘坐、紧急求助、日出提醒及自定义场景。
 *
 * 步骤 Tool ID 必须能在 [ToolCatalogV1] 中解析（TOOL_REFERENCE_INVALID 校验）。
 * 驾驶模式等单一配置动作不因名称含「模式」自动归入 Workflow。
 */
object WorkflowCatalogV1 {

    private val BD01 = BusinessDomainId.CABIN_COMFORT
    private val BD02 = BusinessDomainId.BODY_CONTROL
    private val BD03 = BusinessDomainId.VEHICLE_DRIVING_CONFIG
    private val BD04 = BusinessDomainId.ENERGY
    private val BD05 = BusinessDomainId.IMAGING_RECORDING
    private val BD06 = BusinessDomainId.NAVIGATION_TRAVEL
    private val BD07 = BusinessDomainId.COMMUNICATION
    private val BD08 = BusinessDomainId.MEDIA_ENTERTAINMENT
    private val BD09 = BusinessDomainId.APP_SYSTEM
    private val BD10 = BusinessDomainId.INFORMATION_SERVICE
    private val P0 = ImplementationPriority.P0
    private val P1 = ImplementationPriority.P1
    private val P2 = ImplementationPriority.P2
    private val P3 = ImplementationPriority.P3

    val ALL: List<WorkflowGovernanceSpec> = listOf(
        wf(
            "workflow.cabin.sleep", "小憩/睡眠模式", BD01,
            setOf(BD01, BD09), "duration,temperature,sound",
            listOf("seat.comfort.status.query", "climate.temperature.set", "system.display.set", "media.playback.play"),
            "任一步骤失败停止；可恢复屏幕和媒体", P0
        ),
        wf(
            "workflow.cabin.precondition", "座舱预调节", BD01,
            setOf(BD01), "departureTime,temperature",
            listOf("climate.power.set", "climate.temperature.set", "seat.heating.set", "seat.ventilation.set"),
            "非关键步骤失败可跳过；恢复原设置", P0
        ),
        wf(
            "workflow.vehicle.welcome", "迎宾/离车", BD03,
            setOf(BD01, BD02, BD09), "action,profile",
            listOf("body.lock.set", "seat.heating.set", "system.display.set"),
            "按 action 补偿恢复", P1
        ),
        wf(
            "workflow.body.wash", "洗车模式", BD02,
            setOf(BD02, BD03), "enabled",
            listOf("body.window.set", "body.sunroof.set", "body.mirror.fold.set", "vehicle.setting.query"),
            "失败停止并提示未完成项", P1
        ),
        wf(
            "workflow.vehicle.child", "儿童乘坐模式", BD03,
            setOf(BD01, BD02, BD09), "positions",
            listOf("body.child_lock.set", "climate.temperature.set", "media.audio.play_by_filter"),
            "取消时恢复临时设置", P2
        ),
        wf(
            "workflow.vehicle.offroad", "越野/穿越准备", BD03,
            setOf(BD02, BD03, BD05), "mode",
            listOf("vehicle.offroad.mode.set", "imaging.avm.open", "body.mirror.fold.set"),
            "高风险；失败回滚模式", P3
        ),
        wf(
            "workflow.vehicle.wading", "涉水准备", BD03,
            setOf(BD02, BD03, BD05), "enabled",
            listOf("body.window.set", "vehicle.offroad.mode.set", "imaging.camera.view.set"),
            "强确认；失败回滚", P3
        ),
        wf(
            "workflow.energy.charge_prepare", "充电准备", BD04,
            setOf(BD01, BD04), "limit,schedule",
            listOf("energy.charge.limit.set", "energy.charge.schedule.set", "energy.charge.start"),
            "开始充电失败保留配置", P3
        ),
        wf(
            "workflow.energy.external_power", "对外放电", BD04,
            setOf(BD04), "type,powerLimit",
            listOf("energy.battery.status.query", "energy.discharge.start"),
            "强确认；低电量自动停止", P3
        ),
        wf(
            "workflow.imaging.narrow_road", "窄路影像辅助", BD05,
            setOf(BD05), "view",
            listOf("imaging.avm.open", "imaging.camera.view.set", "imaging.transparent_chassis.set"),
            "退出时关闭临时视图", P3
        ),
        wf(
            "workflow.navigation.home", "回家/上班", BD06,
            setOf(BD06, BD08), "destination,playMedia",
            listOf("navigation.home.start", "navigation.work.start", "media.playback.play"),
            "媒体失败不影响导航", P2
        ),
        wf(
            "workflow.navigation.long_trip", "长途出行", BD06,
            setOf(BD01, BD06, BD08), "destination,waypoints,preference",
            listOf("navigation.destination.set", "navigation.waypoint.add", "navigation.route.start", "media.playback.play"),
            "导航失败停止后续", P2
        ),
        wf(
            "workflow.media.theater", "全车影院", BD08,
            setOf(BD01, BD02, BD08, BD09), "contentId,target",
            listOf("body.window.set", "system.display.set", "media.video.cast.set", "media.video.play"),
            "驾驶状态禁止；退出恢复", P2
        ),
        wf(
            "workflow.media.party", "车内派对", BD08,
            setOf(BD01, BD08), "playlist,lighting",
            listOf("body.ambient_light.set", "media.audio.play_by_filter", "media.volume.set"),
            "音频失败恢复灯光", P2
        ),
        wf(
            "workflow.communication.emergency", "紧急求助", BD07,
            setOf(BD05, BD06, BD07), "contact,shareLocation",
            listOf("navigation.location.query", "communication.message.send", "communication.call.start"),
            "强确认豁免按安全策略；逐项审计", P3
        ),
        wf(
            "workflow.system.app_scene", "应用工作台", BD09,
            setOf(BD09), "apps,layout",
            listOf("system.app.open", "system.display.set"),
            "应用失败跳过并汇总", P1
        ),
        wf(
            "workflow.information.bad_weather", "恶劣天气出行", BD10,
            setOf(BD01, BD02, BD06, BD10), "destination",
            listOf("information.weather.query", "navigation.route.option.set", "climate.defrost.set", "body.wiper.set"),
            "天气证据不足时不自动车控", P3
        ),
        wf(
            "workflow.information.sunrise", "日出提醒与导航", BD10,
            setOf(BD06, BD10), "date,location",
            listOf("information.weather.query", "information.reminder.create", "navigation.destination.set"),
            "无天气/地点时追问", P3
        )
    )

    private fun wf(
        workflowId: String,
        name: String,
        ownerDomainId: BusinessDomainId,
        domainIds: Set<BusinessDomainId>,
        triggerParams: String,
        stepToolIds: List<String>,
        failurePolicy: String,
        priority: ImplementationPriority
    ) = WorkflowGovernanceSpec(
        workflowId = workflowId,
        name = name,
        ownerDomainId = ownerDomainId,
        domainIds = domainIds,
        triggerParams = triggerParams,
        stepToolIds = stepToolIds,
        failurePolicy = failurePolicy,
        priority = priority
    )

    fun get(workflowId: String): WorkflowGovernanceSpec? =
        ALL.firstOrNull { it.workflowId == workflowId }

    val workflowIds: Set<String> = ALL.map { it.workflowId }.toSet()

    /** Owner Domain 分组计数（跨领域 Workflow 按 ownerDomainId 计数）。 */
    fun countByOwnerDomain(): Map<BusinessDomainId, Int> =
        ALL.groupingBy { it.ownerDomainId }.eachCount()
}
