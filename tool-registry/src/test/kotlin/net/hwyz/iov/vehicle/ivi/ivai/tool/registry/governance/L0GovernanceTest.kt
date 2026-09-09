package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.DeterministicSupport
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.L0ReviewStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-013 验证设计 · 160 个 Tool 的 L0 确定性资格模型与 Catalog 构建。
 *
 *  - 160 项全量资格评审：103 SUPPORTED / 29 NOT_SUPPORTED / 28 NEEDS_REVIEW，
 *    与 IVAI Tool Catalog v1（ivai-l0-rules-v1-draft）一致。
 *  - Catalog 是规则事实源：DeterministicIntentCatalog 构建期编译并校验，
 *    SUPPORTED 有规则，NOT_SUPPORTED/NEEDS_REVIEW 无规则（GOV-006）。
 *  - 构建失败条件：SUPPORTED 缺规则 / 非 SUPPORTED 含规则 / 未知别名词表 /
 *    ruleId 重复 / Tool 不存在（GOV-005）。
 *  - 版本与 Hash 一致性：稳定可复现，随内容变化。
 */
class L0GovernanceTest {

    private val spec: L0GovernanceCatalogSpec = L0CatalogLoader.load()

    @Test
    fun `160 项全量资格评审且唯一`() {
        assertEquals(160, spec.tools.size)
        val ids = spec.tools.map { it.toolId }
        assertEquals(ids.size, ids.toSet().size, "L0 条目 Tool ID 必须唯一")
        val catalogIds = ToolCatalogV1.toolIds
        assertEquals(catalogIds, ids.toSet(), "L0 目录与 ToolCatalogV1 双向闭合")
        assertEquals("ivai-l0-rules-v1-draft", spec.ruleVersion)
    }

    @Test
    fun `资格分布为 103 SUPPORTED 29 NOT_SUPPORTED 28 NEEDS_REVIEW`() {
        val counts = spec.tools.groupingBy { it.support }.eachCount()
        assertEquals(103, counts["SUPPORTED"])
        assertEquals(29, counts["NOT_SUPPORTED"])
        assertEquals(28, counts["NEEDS_REVIEW"])
    }

    @Test
    fun `L0 资格校验零问题通过`() {
        val result = L0QualificationValidator(spec.tools, ToolCatalogV1.ALL, spec.ruleVersion).validate()
        assertTrue(result.passed, "L0 资格校验应通过: ${result.issues.take(5).joinToString("; ") { it.message }}")
        assertEquals(103, result.supportedCount)
        assertEquals(29, result.notSupportedCount)
        assertEquals(28, result.needsReviewCount)
    }

    @Test
    fun `DeterministicIntentCatalog 编译全部 160 个 Profile 且 103 个生产启用`() {
        val catalog = DeterministicIntentCatalog.build(spec, ToolCatalogV1.ALL)
        assertEquals(160, catalog.profiles.size)
        assertEquals("ivai-l0-rules-v1-draft", catalog.ruleVersion)
        assertEquals(103, catalog.productionToolIds.size)
        // SUPPORTED 全部有规则；其余无生产规则。
        for (profile in catalog.profiles.values) {
            when (profile.support) {
                DeterministicSupport.SUPPORTED -> {
                    assertTrue(profile.rules.isNotEmpty(), "${profile.toolId} SUPPORTED 必须有规则")
                    assertTrue(profile.productionEnabled, "${profile.toolId} 生产启用")
                    assertTrue(profile.positiveExamples.isNotEmpty())
                    assertTrue(profile.negativeExamples.isNotEmpty())
                }
                DeterministicSupport.NOT_SUPPORTED, DeterministicSupport.NEEDS_REVIEW -> {
                    assertTrue(profile.rules.isEmpty(), "${profile.toolId} 非 SUPPORTED 不得有生产规则")
                    assertFalse(profile.productionEnabled)
                }
            }
        }
    }

    @Test
    fun `槽位编译为运行时 SlotPattern 并展开受控别名词表`() {
        val catalog = DeterministicIntentCatalog.build(spec, ToolCatalogV1.ALL)
        val powerSet = catalog.profileFor("climate.power.set")!!
        val zoneSlot = powerSet.rules.first { it.ruleId == "L0.climate.power.set.on" }
            .slotPatterns.first { it.name == "zone" }
        assertEquals("driver", zoneSlot.aliases["主驾"])
        assertEquals("passenger", zoneSlot.aliases["副驾"])
        val windowSet = catalog.profileFor("body.window.set")!!
        val positions = windowSet.rules.first().slotPatterns.first { it.name == "positions" }
        assertEquals("left_front", positions.aliases["左前"])
    }

    @Test
    fun `版本与内容 Hash 稳定可复现且随内容变化`() {
        val catalog = DeterministicIntentCatalog.build(spec, ToolCatalogV1.ALL)
        val rebuilt = DeterministicIntentCatalog.build(spec, ToolCatalogV1.ALL)
        assertEquals(catalog.contentHash, rebuilt.contentHash, "同内容 Hash 必须稳定")
        assertTrue(catalog.contentHash.length >= 16)
        assertNotEquals("", catalog.contentHash)

        // 内容变化 → Hash 变化（模拟一条规则变更）。
        val mutated = spec.copy(tools = spec.tools.map {
            if (it.toolId == "climate.power.set") it.copy(positiveExamples = it.positiveExamples + "额外正例") else it
        })
        val mutatedCatalog = DeterministicIntentCatalog.build(mutated, ToolCatalogV1.ALL)
        assertNotEquals(catalog.contentHash, mutatedCatalog.contentHash)
    }

    @Test
    fun `SUPPORTED 缺少规则时构建失败（GOV-005）`() {
        val broken = spec.copy(tools = spec.tools.map {
            if (it.toolId == "climate.power.set") it.copy(rules = emptyList()) else it
        })
        val ex = assertThrows(L0CatalogBuildException::class.java) {
            DeterministicIntentCatalog.build(broken, ToolCatalogV1.ALL)
        }
        assertTrue(ex.issues.any { it.toolId == "climate.power.set" })
    }

    @Test
    fun `非 SUPPORTED 含生产规则时构建失败（GOV-006）`() {
        val broken = spec.copy(tools = spec.tools.map {
            if (it.toolId == "media.playback.play") {
                it.copy(rules = listOf(L0RuleSpec(ruleId = "L0.bad", exactPhrases = listOf("播放媒体"))))
            } else it
        })
        val ex = assertThrows(L0CatalogBuildException::class.java) {
            DeterministicIntentCatalog.build(broken, ToolCatalogV1.ALL)
        }
        assertTrue(ex.issues.any { it.toolId == "media.playback.play" && it.message.contains("GOV-006") })
    }

    @Test
    fun `未知别名词表引用构建失败（GOV-005）`() {
        val broken = spec.copy(tools = spec.tools.map {
            if (it.toolId == "climate.power.set") {
                it.copy(rules = it.rules.map { rule ->
                    rule.copy(slotPatterns = rule.slotPatterns.map { slot ->
                        slot.copy(aliases = "unknown_vocab_v9")
                    })
                })
            } else it
        })
        val ex = assertThrows(L0CatalogBuildException::class.java) {
            DeterministicIntentCatalog.build(broken, ToolCatalogV1.ALL)
        }
        assertTrue(ex.issues.any { it.message.contains("未知别名词表") })
    }

    @Test
    fun `L0 规则版本与评审状态语义正确`() {
        val catalog = DeterministicIntentCatalog.build(spec, ToolCatalogV1.ALL)
        // 词表未闭合项标记为 NEEDS_REVIEW。
        val driveMode = catalog.profileFor("vehicle.drive_mode.set")
        assertNotNull(driveMode)
        assertEquals(DeterministicSupport.NEEDS_REVIEW, driveMode!!.support)
        assertEquals(L0ReviewStatus.NEEDS_REVIEW, driveMode.reviewStatus)
        // 开放检索项标记 NOT_SUPPORTED。
        assertEquals(DeterministicSupport.NOT_SUPPORTED, catalog.profileFor("media.playback.play")?.support)
        // 明确开关/数值设置 SUPPORTED。
        assertEquals(DeterministicSupport.SUPPORTED, catalog.profileFor("climate.power.set")?.support)
    }
}
