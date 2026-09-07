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
)

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
    val expectedArguments: JsonObject = buildJsonObject {}
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
