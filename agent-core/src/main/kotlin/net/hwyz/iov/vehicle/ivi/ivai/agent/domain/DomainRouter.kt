package net.hwyz.iov.vehicle.ivi.ivai.agent.domain

import net.hwyz.iov.vehicle.ivi.ivai.agent.router.AgentContext
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.NormalizedInput
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.SemanticFeature

/**
 * 领域预路由输出（IVI-IVAI-DSN-CR-008）。DomainRouter 只产生「受控候选」：
 * 输出 Top-N 业务领域 + 操作类型 + 歧义/否定/多意图标记，**不选择最终 Tool，
 * 也不直接执行**；最终执行权始终位于端侧 Tool Runtime。
 */
data class DomainCandidate(
    val domainId: BusinessDomainId,
    val score: Double,
    val matchedSignals: List<String> = emptyList()
)

/** 领域歧义状态：多领域冲突 / 低置信 / 依赖上下文。 */
enum class DomainAmbiguity {
    NONE,
    MULTI_DOMAIN,
    LOW_CONFIDENCE,
    CONTEXT_DEPENDENT
}

data class DomainRouteDecision(
    val candidates: List<DomainCandidate>,
    val operationType: OperationType,
    val confidence: Double,
    val ambiguity: DomainAmbiguity,
    val semanticFeatures: Set<SemanticFeature>,
    val reasonCode: String,
    /** false 表示未识别到任何可信业务领域（回退旧链路 / 追问）。 */
    val classified: Boolean
) {
    val topDomain: BusinessDomainId? get() = candidates.firstOrNull()?.domainId
}

/** Domain Router 原因码（CR-008）。 */
object DomainReasonCode {
    const val DOMAIN_CONFIDENT = "DOMAIN_CONFIDENT"
    const val DOMAIN_AMBIGUOUS = "DOMAIN_AMBIGUOUS"
    const val DOMAIN_LOW_CONFIDENCE = "DOMAIN_LOW_CONFIDENCE"
    const val DOMAIN_UNKNOWN = "DOMAIN_UNKNOWN"
}

/**
 * Domain Router（IVI-IVAI-DSN-CR-008 首期实现形态：规则/词表，确定式）。
 *
 * 领域词表由两部分合成：
 *  1. 运行时 Tool 治理资产派生（tool.domainId → name/正例/Alias/确定性短语）；
 *  2. [extraVocabulary] 静态词表（需求 BD01~BD10 覆盖范围）。
 *
 * 首期不调用本地 LLM 分类（用户确认方案 A）：结果可复现、离线评测简单；
 * 隐式表达通过语义特征标记交给 L1 消歧/追问。Domain Router 不确定时的降级
 * 策略（保留 Top-N / 追问 / 拒绝）由 TieredIntentRouter 消费本决策实现。
 */
class DomainRouter(
    private val registry: ToolRegistry,
    private val extraVocabulary: Map<BusinessDomainId, List<String>> = DEFAULT_DOMAIN_VOCAB,
    private val maxCandidates: Int = DEFAULT_MAX_CANDIDATES
) {

    private val domainVocab: Map<BusinessDomainId, List<String>> = run {
        val map = LinkedHashMap<BusinessDomainId, MutableList<String>>()
        for (tool in registry.all()) {
            val signals = buildList {
                add(tool.name)
                addAll(tool.positiveExamples)
                addAll(tool.aliases.map { it.sourceValue })
                for (rule in tool.deterministicRules) addAll(rule.exactPhrases)
            }
            map.getOrPut(tool.domainId) { mutableListOf() }.addAll(signals)
        }
        for ((domain, words) in extraVocabulary) {
            map.getOrPut(domain) { mutableListOf() }.addAll(words)
        }
        // 输入经 TextNormalizer 已转小写；词表统一小写后匹配，避免 ADAS/POI/wifi 等
        // 英文词大小写不一致导致领域无法识别（CR-009 160 工具含大量英文词）。
        map.mapValues { (_, signals) ->
            signals.map { it.lowercase() }.filter { it.isNotBlank() }.distinct()
        }
    }

    fun route(input: NormalizedInput, context: AgentContext): DomainRouteDecision {
        val text = input.normalized
        val scored = domainVocab.mapNotNull { (domain, signals) ->
            val hits = signals.filter { text.contains(it) }
            if (hits.isEmpty()) null else DomainCandidate(domain, hits.size.toDouble(), hits.take(3))
        }.sortedByDescending { it.score }

        val operationType = OperationClassifier.classify(text)
        val semantic = semanticFeatures(input)

        if (scored.isEmpty()) {
            return DomainRouteDecision(
                candidates = emptyList(),
                operationType = operationType,
                confidence = 0.0,
                ambiguity = DomainAmbiguity.LOW_CONFIDENCE,
                semanticFeatures = semantic,
                reasonCode = DomainReasonCode.DOMAIN_UNKNOWN,
                classified = false
            )
        }

        val topN = scored.take(maxCandidates)
        val best = topN.first()
        val second = topN.getOrNull(1)
        val confidence = if (second == null) 1.0 else best.score / (best.score + second.score)
        val ambiguity = when {
            second != null && second.score >= best.score * AMBIGUITY_RATIO -> DomainAmbiguity.MULTI_DOMAIN
            best.score <= MIN_CONFIDENT_SCORE -> DomainAmbiguity.LOW_CONFIDENCE
            else -> DomainAmbiguity.NONE
        }
        val classified = best.score >= MIN_CONFIDENT_SCORE
        return DomainRouteDecision(
            candidates = topN,
            operationType = operationType,
            confidence = confidence,
            ambiguity = ambiguity,
            semanticFeatures = semantic,
            reasonCode = when (ambiguity) {
                DomainAmbiguity.MULTI_DOMAIN -> DomainReasonCode.DOMAIN_AMBIGUOUS
                DomainAmbiguity.LOW_CONFIDENCE -> DomainReasonCode.DOMAIN_LOW_CONFIDENCE
                else -> DomainReasonCode.DOMAIN_CONFIDENT
            },
            classified = classified
        )
    }

    private fun semanticFeatures(input: NormalizedInput): Set<SemanticFeature> = buildSet {
        if (input.hasNegation) add(SemanticFeature.NEGATION)
        if (input.hasMultiIntent) add(SemanticFeature.MULTI_INTENT)
        if (IMPLICIT_PATTERNS.any { input.normalized.contains(it) }) {
            add(SemanticFeature.IMPLICIT_EXPRESSION)
        }
    }

    /**
     * 操作类型分类（IVI-IVAI-REQ-CR-008）：对象与动作分离。
     * 页面导航（打开XX设置）→ NAVIGATE_UI；查询 → QUERY；模式/场景 → WORKFLOW；
     * 播放 → PLAYBACK；搜索 → SEARCH；配置 → CONFIGURE；其余显式动作 → CONTROL。
     */
    object OperationClassifier {

        fun classify(normalized: String): OperationType = when {
            isNavigateUi(normalized) -> OperationType.NAVIGATE_UI
            containsAny(normalized, PLAYBACK_VERBS) -> OperationType.PLAYBACK
            containsAny(normalized, SEARCH_VERBS) -> OperationType.SEARCH
            containsAny(normalized, QUERY_VERBS) -> OperationType.QUERY
            containsAny(normalized, WORKFLOW_VERBS) -> OperationType.WORKFLOW
            containsAny(normalized, CONFIGURE_VERBS) -> OperationType.CONFIGURE
            containsAny(normalized, CONTROL_VERBS) -> OperationType.CONTROL
            else -> OperationType.UNKNOWN
        }

        /** 「打开/进入 + 设置/页面/界面/面板/配置」→ 页面定位。 */
        private fun isNavigateUi(normalized: String): Boolean {
            val openVerb = containsAny(normalized, listOf("打开", "进入", "跳转", "定位到", "导航到"))
            val pageMarker = containsAny(normalized, listOf("设置", "页面", "界面", "面板", "配置页"))
            return openVerb && pageMarker
        }

        private fun containsAny(text: String, verbs: List<String>): Boolean =
            verbs.any { text.contains(it) }

        private val CONTROL_VERBS = listOf(
            "打开", "开启", "关闭", "关掉", "调高", "调低", "升高", "降低", "升温", "降温",
            "切换", "调节", "增大", "减小", "开", "关"
        )
        private val QUERY_VERBS = listOf(
            "查询", "查看", "看", "多少", "有没有", "是否", "开了吗", "关了吗",
            "状态", "剩余", "怎么样", "现在", "多少度"
        )
        private val CONFIGURE_VERBS = listOf("设置", "配置", "修改", "调整", "设为", "设成", "更改")
        private val NAVIGATE_UI_VERBS = listOf("打开", "进入", "跳转", "定位到", "导航到")
        private val SEARCH_VERBS = listOf("搜索", "找一下", "搜", "查一下", "附近")
        private val PLAYBACK_VERBS = listOf(
            "播放", "暂停", "停止", "下一首", "上一首", "选集", "音量", "放音乐", "听歌"
        )
        private val WORKFLOW_VERBS = listOf("模式", "一键", "场景", "露营", "洗车", "通勤")
    }

    companion object {
        const val DEFAULT_MAX_CANDIDATES = 3
        /** Top-2 得分比达到该比例视为多领域歧义。 */
        const val AMBIGUITY_RATIO = 0.8
        /** 最少命中信号数才视为可信领域。 */
        const val MIN_CONFIDENT_SCORE = 1.0

        val IMPLICIT_PATTERNS = listOf("有点冷", "太冷", "有点热", "太热", "凉一点", "热一点", "有点闷", "有点凉")

        /**
         * BD01~BD10 静态词表（来源：IVI-IVAI-REQ-CR-008 运行时分类模型覆盖范围）。
         * 与 Tool 治理资产派生词表合并后用于领域打分。
         */
        val DEFAULT_DOMAIN_VOCAB: Map<BusinessDomainId, List<String>> = mapOf(
            BusinessDomainId.CABIN_COMFORT to listOf(
                "空调", "温度", "暖风", "冷风", "风量", "风速", "吹风", "除霜", "除雾",
                "座椅", "通风", "循环", "制热", "制冷", "有点冷", "太热", "露营",
                "升高", "调高", "调低", "升温", "降温"
            ),
            BusinessDomainId.BODY_CONTROL to listOf(
                "车门", "门锁", "车窗", "天窗", "灯光", "灯", "雨刮", "后视镜", "尾门", "门"
            ),
            BusinessDomainId.VEHICLE_DRIVING_CONFIG to listOf(
                "驾驶模式", "动能回收", "转向手感", "辅助驾驶", "ADAS", "个性化", "车辆设置", "限速"
            ),
            BusinessDomainId.ENERGY to listOf(
                "充电", "电量", "续航", "能耗", "放电", "电池", "补能", "快充"
            ),
            BusinessDomainId.IMAGING_RECORDING to listOf(
                "摄像头", "环视", "行车记录", "影像", "录像", "拍照", "全景"
            ),
            BusinessDomainId.NAVIGATION_TRAVEL to listOf(
                "导航", "地图", "路线", "目的地", "POI", "地址", "去", "路况"
            ),
            BusinessDomainId.COMMUNICATION to listOf(
                "电话", "拨号", "通讯录", "联系人", "短信", "消息", "通话", "呼叫"
            ),
            BusinessDomainId.MEDIA_ENTERTAINMENT to listOf(
                "音乐", "电台", "广播", "视频", "电影", "播放", "歌", "频道", "有声"
            ),
            BusinessDomainId.APP_SYSTEM to listOf(
                "应用", "桌面", "系统设置", "蓝牙", "wifi", "网络", "商店", "app"
            ),
            BusinessDomainId.INFORMATION_SERVICE to listOf(
                "天气", "日历", "提醒", "新闻", "股票", "时间", "汇率"
            )
        )
    }
}
