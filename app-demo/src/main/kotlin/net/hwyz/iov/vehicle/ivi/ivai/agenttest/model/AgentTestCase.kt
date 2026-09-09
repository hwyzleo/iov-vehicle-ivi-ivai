package net.hwyz.iov.vehicle.ivi.ivai.agenttest.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.TargetType
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId

/**
 * 版本化本地回归测试套件（IVI-IVAI-DSN-CR-012）。以 JSON 资产随项目发布，
 * 用例顺序以声明顺序为准（稳定排序）。
 */
@Serializable
data class AgentTestSuite(
    val suiteId: String,
    val schemaVersion: Int = 1,
    val governanceVersion: String? = null,
    val cases: List<AgentTestCase> = emptyList()
) {
    companion object {
        /** 当前支持的 Suite Schema 版本（解析器 / 校验器共享）。
         * CR-019：v2 为业务 Outcome 评分契约（EXECUTE/NEED_DIALOGUE/REJECTED +
         * reasonCode）；v1 保留为历史样本（不再作为发布准确率基线）。 */
        const val SUPPORTED_SCHEMA_VERSION = 2
    }
}

/**
 * CR-019：业务 Outcome（测试评分契约 V2，与处理 Tier 拆分）。
 *
 *  - EXECUTE：产生了可执行目标并执行成功（必须有 Target 与参数断言）；
 *  - NEED_DIALOGUE：缺少必填参数 / 歧义追问（必须无可执行 Target，校验
 *    runtimeOutcome 与 reasonCode）；
 *  - REJECTED：安全/越界/未知目标拒绝（必须无可执行 Target，校验 reasonCode）。
 */
@Serializable
enum class ExpectedOutcome {
    EXECUTE,
    NEED_DIALOGUE,
    REJECTED
}

/**
 * 单条本地回归测试用例（IVI-IVAI-DSN-CR-012 / IVAI-REQ-111）。
 *
 * [expectedTarget] 为 null 表示「预期不产生可执行目标」——仅当实际目标也为 null
 * 时该维度匹配（用于隐式表达 / 追问 / 纯回复类回归用例；REQ 的必填目标字段在
 * 需要断言具体 Tool/Workflow 时使用，扩展语义不改变既有必填场景）。
 */
@Serializable
data class AgentTestCase(
    val caseId: String,
    val description: String? = null,
    val tags: Set<String> = emptySet(),
    val enabled: Boolean = true,
    val input: String,
    val expectedTier: IntentTier,
    val expectedDomain: BusinessDomainId,
    val expectedCapabilityPack: String,
    val expectedTarget: ExpectedTarget? = null,
    /** 空对象表示预期无业务参数：仅当实际业务参数也为空时才匹配。 */
    val expectedArguments: JsonObject = buildJsonObject {},
    /** CR-019：V2 业务 Outcome（schemaVersion=2 时必填；v1 兼容扩展保持 null）。 */
    val expectedOutcome: ExpectedOutcome? = null,
    /** CR-019：V2 期望 reasonCode（NEED_DIALOGUE/REJECTED 时按此断言完整率）。 */
    val expectedReasonCode: String? = null
)

/**
 * CR-019：V2 意图期望契约（设计 IntentExpectationV2，与 AgentTestCase 扩展字段同源）。
 * 用于 V2 用例的显式视图；评分器以 [AgentTestCase] 字段为准。
 */
data class IntentExpectationV2(
    val expectedTier: IntentTier,
    val expectedOutcome: ExpectedOutcome,
    val expectedDomain: BusinessDomainId,
    val expectedCapabilityPack: String,
    val expectedTarget: ExpectedTarget? = null,
    val expectedArguments: JsonObject = buildJsonObject {},
    val expectedReasonCode: String? = null
)

/**
 * 预期目标：类型（TOOL / WORKFLOW）+ canonical ID。Tool 与同名 Workflow 不得
 * 互相匹配（评分规则 4）。
 */
@Serializable
data class ExpectedTarget(
    val type: TargetType,
    val id: String
)
