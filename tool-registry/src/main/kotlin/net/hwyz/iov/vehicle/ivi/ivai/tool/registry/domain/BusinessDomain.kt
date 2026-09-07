package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain

import kotlinx.serialization.Serializable

/**
 * 建议业务领域（IVI-IVAI-REQ-CR-008 运行时分类模型 BD01~BD10）。
 *
 * CR-008 把「业务对象」「操作类型」「语义能力」「执行适配」拆分为正交维度：
 * 本枚举只描述「用户要操作的对象/业务领域」，与操作类型（[OperationType]）、
 * 语义能力（[SemanticFeature]）、执行绑定（ToolExecutionBinding）互不混用。
 *
 * [code] 为需求给定的 BDxx 编码，[label] 为中文名称；Tool ID、治理资产和
 * 可观测日志统一使用该编码作为稳定标识。
 *
 * CR-012：标记 @Serializable，供测试快照 / 测试用例资产按枚举名（如
 * CABIN_COMFORT）反序列化。
 */
@Serializable
enum class BusinessDomainId(
    val code: String,
    val label: String
) {
    CABIN_COMFORT("BD01", "座舱舒适"),
    BODY_CONTROL("BD02", "车身控制"),
    VEHICLE_DRIVING_CONFIG("BD03", "车辆设置与驾驶"),
    ENERGY("BD04", "能源与补能"),
    IMAGING_RECORDING("BD05", "影像与记录"),
    NAVIGATION_TRAVEL("BD06", "导航与出行"),
    COMMUNICATION("BD07", "通讯"),
    MEDIA_ENTERTAINMENT("BD08", "媒体娱乐"),
    APP_SYSTEM("BD09", "应用与系统"),
    INFORMATION_SERVICE("BD10", "信息服务");

    companion object {
        fun fromCode(code: String): BusinessDomainId? =
            entries.firstOrNull { it.code == code }

        fun fromLabel(label: String): BusinessDomainId? =
            entries.firstOrNull { it.label == label }
    }
}

/**
 * 操作类型（IVI-IVAI-REQ-CR-008 运行时分类模型）。描述用户要对业务对象执行的
 * 动作，与业务领域正交：
 *  - CONTROL / CONFIGURE：改变对象状态或配置。
 *  - QUERY：读取状态/数值（「查看空调状态」→ 座舱舒适 + QUERY）。
 *  - NAVIGATE_UI：页面打开/定位/返回（「打开充电设置」→ 能源与补能 + NAVIGATE_UI）。
 *  - SEARCH / PLAYBACK：内容检索 / 媒体播放。
 *  - WORKFLOW：跨能力、多步骤或场景化编排（「露营模式」）。
 *  - UNKNOWN：无法判定。
 */
enum class OperationType {
    CONTROL,
    QUERY,
    CONFIGURE,
    NAVIGATE_UI,
    SEARCH,
    PLAYBACK,
    WORKFLOW,
    UNKNOWN
}

/**
 * 横切语义能力（IVI-IVAI-REQ-CR-008）。自然表达、隐式意图、否定、指代消解、
 * 连续指令、上下文规则、动态热词和多意图拆分属于横切语义能力，**不作为一级
 * 业务领域参与最终 Tool 路由**，而是驱动规范化、补槽和评测。
 */
enum class SemanticFeature {
    EXPLICIT_COMMAND,
    IMPLICIT_EXPRESSION,
    NEGATION,
    REFERENCE_RESOLUTION,
    CONTINUOUS_COMMAND,
    CONTEXT_RULE,
    DYNAMIC_HOTWORD,
    MULTI_INTENT
}
