package net.hwyz.iov.vehicle.ivi.ivai.agent.domain

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.ClimateAirflowObjectWords
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-018 验证设计 · 座舱气流语义词典：
 *  - P0 词汇输出 Domain/Pack + 语义对象证据，不选择 Tool；
 *  - 与治理对象词表（ClimateAirflowObjectWords）单一事实源一致；
 *  - 出风/吹风/送风/风口/风量/风速/气流/自动空调/AUTO 模式全部可路由到 CABIN_COMFORT。
 */
class CabinAirflowSemanticLexiconTest {

    @Test
    fun `P0 词汇全部进入 CABIN_COMFORT 与 cabin_climate 包证据`() {
        val p0 = listOf(
            "空调", "空调系统", "空调电源", "出风", "吹风", "送风", "风口",
            "通风口", "出风口", "风量", "风速", "气流", "自动空调", "AUTO模式"
        )
        for (phrase in p0) {
            val objects = CabinAirflowSemanticLexicon.evidenceFor(phrase)
            assertTrue(objects.isNotEmpty(), "$phrase 应形成对象证据")
            val entries = CabinAirflowSemanticLexicon.entriesFor(phrase)
            assertTrue(entries.all { it.domainId == BusinessDomainId.CABIN_COMFORT })
            assertTrue(entries.all { it.capabilityPackId == CabinAirflowSemanticLexicon.CABIN_CLIMATE_PACK })
        }
    }

    @Test
    fun `对象映射正确`() {
        assertEquals(
            setOf(SemanticObject.HVAC_SYSTEM),
            CabinAirflowSemanticLexicon.evidenceFor("打开空调")
        )
        assertEquals(
            setOf(SemanticObject.VENT),
            CabinAirflowSemanticLexicon.evidenceFor("打开通风口")
        )
        assertEquals(
            setOf(SemanticObject.VENT),
            CabinAirflowSemanticLexicon.evidenceFor("开出风")
        )
        assertEquals(
            setOf(SemanticObject.FAN_SPEED),
            CabinAirflowSemanticLexicon.evidenceFor("风量调到5档")
        )
        assertEquals(
            setOf(SemanticObject.AIRFLOW_DIRECTION),
            CabinAirflowSemanticLexicon.evidenceFor("吹脸")
        )
        assertEquals(
            setOf(SemanticObject.AUTO_HVAC, SemanticObject.HVAC_SYSTEM),
            CabinAirflowSemanticLexicon.evidenceFor("开启自动空调")
        )
    }

    @Test
    fun `自动空调同时形成 AUTO 与系统对象证据但以更具体词优先`() {
        val objects = CabinAirflowSemanticLexicon.evidenceFor("自动空调")
        assertTrue(SemanticObject.AUTO_HVAC in objects)
    }

    @Test
    fun `无气流词不产生证据`() {
        assertFalse(CabinAirflowSemanticLexicon.hasAirflowEvidence("播放音乐"))
        assertEquals(emptySet<SemanticObject>(), CabinAirflowSemanticLexicon.evidenceFor("今天天气怎么样"))
    }

    @Test
    fun `与治理对象词表单一事实源一致`() {
        // 词典条目必须由 ClimateAirflowObjectWords 生成（禁止各自维护词表）。
        val expectedPhrases = ClimateAirflowObjectWords.OBJECT_WORDS.values.flatten().toSet()
        val lexiconPhrases = CabinAirflowSemanticLexicon.ENTRIES.map { it.phrase }.toSet()
        assertEquals(expectedPhrases, lexiconPhrases)
    }
}
