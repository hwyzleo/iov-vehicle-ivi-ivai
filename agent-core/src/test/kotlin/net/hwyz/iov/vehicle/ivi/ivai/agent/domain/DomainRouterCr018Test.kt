package net.hwyz.iov.vehicle.ivi.ivai.agent.domain

import net.hwyz.iov.vehicle.ivi.ivai.agent.router.AgentContext
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.TextNormalizer
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ClimateToolDefinitions
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-018 验证设计 · Domain Router 座舱气流领域盲区修复：
 *  - 出风/吹风/送风/风口/通风口/气流/自动空调等表达必须进入 CABIN_COMFORT；
 *  - 输出 airflowObjects 语义对象证据（不选 Tool）；
 *  - 气流词未能形成 CABIN_COMFORT 证据时标记 airflowEvidenceFailed（IVAI-DOMAIN-AIRFLOW-001）。
 */
class DomainRouterCr018Test {

    private val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
    private val router = DomainRouter(registry)

    private fun route(text: String): DomainRouteDecision {
        val input = TextNormalizer.normalize(text)
        val context = AgentContext(requestId = "r", sessionId = "s", source = "mock")
        return router.route(input, context)
    }

    @Test
    fun `出风吹风送风风口气流全部进入 CABIN_COMFORT 并形成对象证据`() {
        val cases = mapOf(
            "开出风" to SemanticObject.VENT,
            "开吹风" to SemanticObject.VENT,
            "开送风" to SemanticObject.VENT,
            "打开风口" to SemanticObject.VENT,
            "打开通风口" to SemanticObject.VENT,
            "把气流调大" to SemanticObject.FAN_SPEED,
            "风量调到5档" to SemanticObject.FAN_SPEED,
            "自动空调打开" to SemanticObject.AUTO_HVAC
        )
        for ((text, obj) in cases) {
            val d = route(text)
            assertTrue(d.classified, "应识别领域: $text")
            assertEquals(BusinessDomainId.CABIN_COMFORT, d.topDomain, "领域应为 CABIN_COMFORT: $text")
            assertTrue(obj in d.airflowObjects, "$text 应产生 $obj 证据")
        }
    }

    @Test
    fun `无气流词不产生 airflowObjects 证据`() {
        val d = route("播放音乐")
        assertTrue(d.airflowObjects.isEmpty())
    }

    @Test
    fun `气流词被强媒体信号压过时标记 airflowEvidenceFailed`() {
        // “播放音乐”媒体强信号压过单个“出风”词 → 未形成 CABIN_COMFORT 证据：
        // 输出 IVAI-DOMAIN-AIRFLOW-001（airflowEvidenceFailed + DOMAIN_AIRFLOW_UNRESOLVED）。
        val d = route("把出风关掉的同时播放音乐")
        assertTrue(d.airflowEvidenceFailed, "气流词未形成 CABIN_COMFORT 证据时应标记失败")
        assertEquals(DomainReasonCode.DOMAIN_AIRFLOW_UNRESOLVED, d.reasonCode)
    }

    @Test
    fun `reasonCode 覆盖气流证据成功路径`() {
        // 单信号（出风）命中 → classified + LOW_CONFIDENCE（路由保护兜底仍走 L1）。
        val d = route("开出风")
        assertTrue(d.classified)
        assertEquals(BusinessDomainId.CABIN_COMFORT, d.topDomain)
        assertEquals(DomainReasonCode.DOMAIN_LOW_CONFIDENCE, d.reasonCode)
        assertFalse(d.airflowEvidenceFailed)
        // 多信号（空调 + 出风 + 打开）→ DOMAIN_CONFIDENT。
        val multi = route("打开空调出风")
        assertEquals(DomainReasonCode.DOMAIN_CONFIDENT, multi.reasonCode)
        assertFalse(multi.airflowEvidenceFailed)
    }
}
