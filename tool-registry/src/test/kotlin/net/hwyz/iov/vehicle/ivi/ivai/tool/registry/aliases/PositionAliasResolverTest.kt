package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.Cr017ErrorCodes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-017 单元测试：位置 Alias 解析（vehicle_position_v2 唯一事实源）。
 *
 * 覆盖设计测试验收 1：
 *  - 每个 canonical zone 的正例、变体、冲突、非法车型和宽泛表达；
 *  - 车型拓扑过滤（两排车禁止 3排/中排区 → IVAI-ALIAS-TOPOLOGY-001）；
 *  - 宽泛表达（右边/中间/后面/right/middle/back）歧义保护（IVAI-ALIAS-AMBIGUOUS-001）；
 *  - 最长优先匹配（第二排左 覆盖 第二排）与重叠区间跳过。
 */
class PositionAliasResolverTest {

    private val resolver = PositionAliasResolver()

    private fun assertZone(query: String, expected: String, vehicle: String? = null) {
        val r = resolver.resolve(query, vehicle)
        assertFalse(r.hasAmbiguity, "$query 不应歧义: ${r.ambiguousWords}")
        assertFalse(r.hasTopologyViolation, "$query 不应拓扑违规: ${r.topologyViolations}")
        assertEquals(expected, r.singleZone, "$query 应唯一解析为 $expected")
    }

    @Test
    fun `all 的批准 Alias 全部命中`() {
        for (w in listOf("全部", "所有", "整车", "全车", "ALL", "ALL_ZONES", "whole_vehicle")) {
            assertZone("把${w}风量调到5档", "all")
        }
    }

    @Test
    fun `driver 与 passenger 的批准 Alias 命中`() {
        for (w in listOf("主驾", "驾驶位", "司机位", "主驾驶")) assertZone("${w}风量调到5档", "driver")
        for (w in listOf("副驾", "副驾驶", "副驾驶位", "乘客位")) assertZone("${w}风量调到5档", "passenger")
    }

    @Test
    fun `front 与 rear 的批准 Alias 命中`() {
        for (w in listOf("前排", "第一排", "1排")) assertZone("${w}风量调到5档", "front")
        assertZone("后排风量调到5档", "rear")
    }

    @Test
    fun `middle_left 与 middle_right 批准 Alias 命中`() {
        for (w in listOf("中左", "中排左", "第二排左", "2排左")) assertZone("${w}风量调到5档", "middle_left")
        for (w in listOf("中右", "中排右", "第二排右", "2排右")) assertZone("${w}风量调到5档", "middle_right")
    }

    @Test
    fun `second_row 与 third_row 批准 Alias 命中`() {
        for (w in listOf("二排", "第二排", "2排")) assertZone("${w}风量调到5档", "second_row")
        for (w in listOf("三排", "第三排", "3排")) assertZone("${w}风量调到5档", "third_row")
    }

    @Test
    fun `模型输出侧英文 Alias 命中`() {
        assertZone("ROW2风量调到5档", "second_row")
        assertZone("ZONE2风量调到5档", "second_row")
        assertZone("3RD风量调到5档", "third_row")
        assertZone("ROW3风量调到5档", "third_row")
    }

    @Test
    fun `最长优先匹配 第二排左 覆盖 第二排`() {
        val r = resolver.resolve("第二排左风量调到5档")
        assertEquals(setOf("middle_left"), r.matchedZones)
        assertFalse(r.hasAmbiguity)
    }

    @Test
    fun `宽泛表达不静默映射且输出歧义错误码`() {
        for (w in listOf("右边", "中间", "后面", "左边", "前面", "right", "middle", "back")) {
            val r = resolver.resolve("${w}风量调到5档")
            assertTrue(r.hasAmbiguity, "$w 应判定歧义")
            assertTrue(r.matchedZones.isEmpty(), "$w 不得静默映射为具体枚举")
            assertEquals(Cr017ErrorCodes.ALIAS_AMBIGUOUS, r.errorCode)
        }
    }

    @Test
    fun `两排车型禁止 3排 与 中排区`() {
        val twoRow = VehicleCabinTopology.twoRow("demo-2row")
        val resolver2 = PositionAliasResolver(topologyResolver = { twoRow })
        for (w in listOf("3排", "三排", "第三排", "中左", "中右")) {
            val r = resolver2.resolve("${w}风量调到5档")
            assertTrue(r.hasTopologyViolation, "$w 在两排车应拓扑违规")
            assertEquals(Cr017ErrorCodes.ALIAS_TOPOLOGY, r.errorCode)
            assertTrue(r.matchedZones.isEmpty(), "$w 不得转换为可执行参数")
        }
    }

    @Test
    fun `两排车型允许 主驾 副驾 前排 后排`() {
        val twoRow = VehicleCabinTopology.twoRow("demo-2row")
        val resolver2 = PositionAliasResolver(topologyResolver = { twoRow })
        for ((w, zone) in listOf("主驾" to "driver", "副驾" to "passenger", "前排" to "front", "后排" to "rear")) {
            val r = resolver2.resolve("${w}风量调到5档")
            assertFalse(r.hasTopologyViolation, "$w 在两排车应合法")
            assertEquals(zone, r.singleZone)
        }
    }

    @Test
    fun `多个位置词同时命中判定歧义`() {
        val r = resolver.resolve("中左和2排都调到5档")
        assertTrue(r.hasAmbiguity)
        assertEquals(Cr017ErrorCodes.ALIAS_AMBIGUOUS, r.errorCode)
    }

    @Test
    fun `无位置词返回空证据`() {
        val r = resolver.resolve("风量档位调到5档")
        assertTrue(r.matchedZones.isEmpty())
        assertTrue(r.matchedEntries.isEmpty())
        assertFalse(r.hasAmbiguity)
        assertEquals(null, r.errorCode)
    }

    @Test
    fun `词表同源 与 L0 中文词表一致`() {
        // vehicle_position_v2 的 canonical 覆盖与 L0 词表一致（防漂移）。
        val l0Words = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.L0AliasVocabularies.vehicle_position_v2
        for ((word, canonical) in l0Words) {
            val r = resolver.resolve(word)
            assertTrue(canonical in r.matchedZones, "$word 应解析到 $canonical")
        }
    }
}
