package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

/**
 * 空调相似 Tool 边界条目（IVI-IVAI-DSN-CR-018，对应设计 ClimateToolBoundaryDocument）。
 *
 * 每个空调 Tool 固化：职责、正向对象（SemanticObject 名称）、正向动作、必填槽位、
 * 分区正例、负例边界与冲突 Tool 集。只作为 RAG 文档 / Candidate Context / 混合
 * 排序的**证据来源**，不产生 L0 规则；“吹风/出风”不得单独成为任一 Tool 的
 * 确定性正例（歧义表达进入 L1 消歧或追问）。
 */
data class ClimateToolBoundary(
    val canonicalId: String,
    val responsibility: String,
    val positiveObjects: Set<String>,
    val positiveActions: Set<String>,
    val requiredSlots: Set<String>,
    val positiveExamples: List<String>,
    val negativeExamples: List<String>,
    val conflictToolIds: Set<String>,
    val governanceVersion: String,
    val contentHash: String
)

/**
 * 空调相似 Tool 边界目录（IVI-IVAI-DSN-CR-018，治理单一事实源）。
 *
 * 排序证据（设计 12.11）：
 *  - power.set:      HVAC_SYSTEM + open/close/start/power-on
 *  - vent.set:       VENT + open/close
 *  - fan.speed.set:  FAN_SPEED + 绝对数值档位
 *  - fan.speed.adjust: FAN_SPEED + 相对增减
 *  - airflow.mode.set: AIRFLOW_DIRECTION + face/feet/defrost/mixed
 *  - auto.set:       AUTO_HVAC + auto/AUTO
 */
object ClimateToolBoundaryCatalog {

    const val GOVERNANCE_VERSION = "ivai-climate-boundary-v1"

    val ALL: Map<String, ClimateToolBoundary> = listOf(
        climate(
            canonicalId = "climate.power.set",
            responsibility = "开启或关闭空调系统电源",
            positiveObjects = setOf("HVAC_SYSTEM"),
            positiveActions = setOf("打开", "开启", "关闭", "关掉", "启动", "接通", "启用", "power-on", "power-off"),
            requiredSlots = setOf("enabled"),
            positiveExamples = listOf(
                "打开空调", "开启空调", "把空调打开", "开空调", "空调打开",
                "关闭空调", "关空调", "把空调关掉", "关掉空调", "空调关闭",
                "启动空调", "启动空调系统", "接通空调电源", "启用空调系统",
                "让空调系统开始运行", "关闭空调系统", "断开空调电源",
                "打开主驾空调电源", "关闭主驾空调电源", "把空调电源打开", "把空调电源关掉"
            ),
            negativeExamples = listOf(
                "打开通风口", "打开主驾通风口", "风量调到5档", "风量调高一点",
                "出风模式吹脸", "开启自动空调", "打开AUTO模式"
            ),
            conflictToolIds = setOf(
                "climate.auto.set", "climate.vent.set", "climate.fan.speed.set",
                "climate.fan.speed.adjust", "climate.airflow.mode.set"
            )
        ),
        climate(
            canonicalId = "climate.vent.set",
            responsibility = "打开或关闭通风口/风口（zone 必填）",
            positiveObjects = setOf("VENT"),
            positiveActions = setOf("打开", "开启", "关闭", "关掉", "open", "close"),
            requiredSlots = setOf("zone", "enabled"),
            positiveExamples = listOf(
                "打开主驾通风口", "打开副驾通风口", "开启驾驶位通风口", "开启副驾驶通风口",
                "关闭主驾通风口", "关闭副驾通风口", "关掉驾驶位通风口", "关掉副驾驶通风口",
                "打开后排通风口", "关闭后排通风口", "打开全车通风口", "关闭全车通风口",
                "打开通风口", "开启通风口", "打开前排风口", "打开全车风口",
                "关掉全车通风口", "打开主驾出风口", "开启后排通风口"
            ),
            negativeExamples = listOf(
                "打开空调", "风量调到5档", "风量调高一点", "出风模式吹脸", "开启自动空调"
            ),
            conflictToolIds = setOf(
                "climate.power.set", "climate.fan.speed.set", "climate.fan.speed.adjust",
                "climate.airflow.mode.set", "climate.auto.set"
            )
        ),
        climate(
            canonicalId = "climate.fan.speed.set",
            responsibility = "设置绝对风量/风速档位（level 必填，zone 缺省 all）",
            positiveObjects = setOf("FAN_SPEED"),
            positiveActions = setOf("调到", "设为", "设置", "设成", "absolute-level"),
            requiredSlots = setOf("level"),
            positiveExamples = listOf(
                "风量档位调到2档", "设置风量档位2档", "中左风量档位调到5档",
                "中右设置风量档位5档", "2排风量档位设为5档", "3排风量档位调到5档",
                "风量调到5档", "风速设成3档", "风量设为2档", "风速调到7档",
                "中左风量调到5档", "2排风速设成3档"
            ),
            negativeExamples = listOf(
                "风量调大一点", "风量调高", "风量调低", "打开通风口", "打开空调"
            ),
            conflictToolIds = setOf(
                "climate.fan.speed.adjust", "climate.power.set", "climate.vent.set",
                "climate.airflow.mode.set", "climate.auto.set"
            )
        ),
        climate(
            canonicalId = "climate.fan.speed.adjust",
            responsibility = "在当前风量基础上相对增减（direction + step）",
            positiveObjects = setOf("FAN_SPEED"),
            positiveActions = setOf("调高", "调低", "调大", "调小", "增加", "减少", "大一点", "小一点", "relative"),
            requiredSlots = setOf("direction", "step"),
            positiveExamples = listOf(
                "风量调高", "调高风量", "增加风量", "风量调低", "调低风量", "降低风量",
                "风量调大一点", "风量调小2档", "风速调低一点"
            ),
            negativeExamples = listOf(
                "风量调到5档", "风速设成3档", "打开通风口", "打开空调"
            ),
            conflictToolIds = setOf(
                "climate.fan.speed.set", "climate.power.set", "climate.vent.set",
                "climate.airflow.mode.set", "climate.auto.set"
            )
        ),
        climate(
            canonicalId = "climate.airflow.mode.set",
            responsibility = "设置出风方向模式（吹脸/吹脚/除霜/混合）",
            positiveObjects = setOf("AIRFLOW_DIRECTION"),
            positiveActions = setOf("吹脸", "吹脚", "吹腿", "除霜", "混合", "face", "feet", "defrost", "mixed"),
            requiredSlots = setOf("mode"),
            positiveExamples = listOf(
                "出风模式吹脸", "设置出风模式为吹脸", "出风模式吹脚", "设置出风模式为吹脚",
                "出风模式除霜", "设置出风模式为除霜", "出风模式混合", "设置出风模式为混合",
                "吹脸", "吹脚", "除霜模式", "混合出风", "改成吹脚", "出风模式吹腿"
            ),
            negativeExamples = listOf(
                "打开空调", "风量调到5档", "打开通风口", "开启自动空调"
            ),
            conflictToolIds = setOf(
                "climate.power.set", "climate.vent.set", "climate.fan.speed.set",
                "climate.fan.speed.adjust", "climate.auto.set"
            )
        ),
        climate(
            canonicalId = "climate.auto.set",
            responsibility = "开启/关闭自动空调（AUTO）模式",
            positiveObjects = setOf("AUTO_HVAC"),
            positiveActions = setOf("打开", "开启", "关闭", "关掉", "auto", "AUTO"),
            requiredSlots = setOf("enabled"),
            positiveExamples = listOf(
                "打开自动空调", "开启自动空调", "关闭自动空调", "关掉自动空调",
                "打开AUTO模式", "开启自动模式", "把自动空调打开", "把自动空调关掉",
                "自动空调开启", "自动空调关闭"
            ),
            negativeExamples = listOf(
                "打开空调", "风量调到5档", "打开通风口", "出风模式吹脸"
            ),
            conflictToolIds = setOf(
                "climate.power.set", "climate.vent.set", "climate.fan.speed.set",
                "climate.fan.speed.adjust", "climate.airflow.mode.set"
            )
        )
    ).associateBy { it.canonicalId }

    fun forTool(toolId: String): ClimateToolBoundary? = ALL[toolId]

    /** 稳定内容 Hash（Catalog / 边界变化必须触发 contentHash、Embedding 刷新与索引切换）。 */
    fun contentHashOf(boundary: ClimateToolBoundary): String = GovernanceManifestBuilder.sha256(
        buildString {
            append(boundary.canonicalId).append('|')
            append(boundary.responsibility).append('|')
            append(boundary.positiveObjects.sorted().joinToString(",")).append('|')
            append(boundary.positiveActions.sorted().joinToString(",")).append('|')
            append(boundary.requiredSlots.sorted().joinToString(",")).append('|')
            append(boundary.positiveExamples.joinToString(";")).append('|')
            append(boundary.negativeExamples.joinToString(";")).append('|')
            append(boundary.conflictToolIds.sorted().joinToString(",")).append('|')
            append(boundary.governanceVersion)
        }
    )

    private fun climate(
        canonicalId: String,
        responsibility: String,
        positiveObjects: Set<String>,
        positiveActions: Set<String>,
        requiredSlots: Set<String>,
        positiveExamples: List<String>,
        negativeExamples: List<String>,
        conflictToolIds: Set<String>
    ): ClimateToolBoundary {
        val boundary = ClimateToolBoundary(
            canonicalId = canonicalId,
            responsibility = responsibility,
            positiveObjects = positiveObjects,
            positiveActions = positiveActions,
            requiredSlots = requiredSlots,
            positiveExamples = positiveExamples,
            negativeExamples = negativeExamples,
            conflictToolIds = conflictToolIds,
            governanceVersion = GOVERNANCE_VERSION,
            contentHash = ""
        )
        return boundary.copy(contentHash = contentHashOf(boundary))
    }
}
