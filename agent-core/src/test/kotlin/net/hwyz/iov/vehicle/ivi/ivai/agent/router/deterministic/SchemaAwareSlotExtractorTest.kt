package net.hwyz.iov.vehicle.ivi.ivai.agent.router.deterministic

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases.DefaultAliasLexicons
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.DeterministicIntentRule
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.SlotPattern
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.SlotType
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolExecutionBinding
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-016 单元测试：Schema 感知槽位提取（SchemaAwareSlotExtractor）。
 *
 * 覆盖设计测试设计：
 *  - “5档”“调到5”“设为5”在匹配规则声明 NUMERIC 槽位时统一输出 level=5；
 *  - 中文数字档位（“调到五档”→ 5）；
 *  - 位置词经版本化 Alias Lexicon（主驾/副驾/前排/后排/全车/2排）；
 *  - 数值档位支持“档/级”受控单位后缀。
 */
class SchemaAwareSlotExtractorTest {

    private val fanSchema = """
        {
          "type": "object",
          "properties": {
            "zone": { "type": "string", "enum": ["driver", "passenger", "front", "rear", "all"] },
            "level": { "type": "integer", "minimum": 0, "maximum": 10 }
          },
          "required": ["level"]
        }
    """.trimIndent()

    private val registry = ToolRegistry().register(
        ToolDefinition(
            toolId = "climate.fan.speed.set",
            functionId = null,
            name = "设置风量档位",
            description = "test",
            positiveExamples = listOf(),
            negativeExamples = listOf(),
            selectionPriority = 1,
            parameterSchema = fanSchema,
            policy = ToolPolicy(),
            execution = ToolExecutionBinding(adapterId = "test", methodId = "test")
        )
    )

    private val extractor = SchemaAwareSlotExtractor(registry, DefaultAliasLexicons.DEFAULT)

    private val numericRule = DeterministicIntentRule(
        ruleId = "L0.fan.set",
        toolId = "climate.fan.speed.set",
        exactPhrases = listOf("风量档位调到"),
        slotPatterns = listOf(
            SlotPattern(name = "zone", type = SlotType.POSITION, required = false),
            SlotPattern(name = "level", type = SlotType.NUMERIC, required = true)
        )
    )

    @Test
    fun `5档 输出 level=5 用户显式来源`() {
        val result = extractor.extract(numericRule, "climate.fan.speed.set", "风量档位调到5档")
        val level = result.arguments.first { it.name == "level" }
        assertEquals(5, level.rawValue)
        assertEquals(ArgumentSource.USER_EXPLICIT, level.source)
    }

    @Test
    fun `调到5 输出 level=5`() {
        val result = extractor.extract(numericRule, "climate.fan.speed.set", "风量档位调到5")
        assertEquals(5, result.arguments.first { it.name == "level" }.rawValue)
    }

    @Test
    fun `设为5 输出 level=5`() {
        val result = extractor.extract(numericRule, "climate.fan.speed.set", "风量档位设为5")
        assertEquals(5, result.arguments.first { it.name == "level" }.rawValue)
    }

    @Test
    fun `中文数字档位 输出 level=5`() {
        val result = extractor.extract(numericRule, "climate.fan.speed.set", "风量档位调到五档")
        assertEquals(5, result.arguments.first { it.name == "level" }.rawValue)
    }

    @Test
    fun `主驾位置经 Alias Lexicon 映射为 driver`() {
        val result = extractor.extract(numericRule, "climate.fan.speed.set", "主驾风量档位调到5")
        val zone = result.arguments.first { it.name == "zone" }
        assertEquals("driver", zone.rawValue)
        assertEquals(ArgumentSource.ALIAS_MAPPING, zone.source)
    }

    @Test
    fun `全车位置经 Alias Lexicon 映射为 all`() {
        val result = extractor.extract(numericRule, "climate.fan.speed.set", "全车风量档位调到5")
        assertEquals("all", result.arguments.first { it.name == "zone" }.rawValue)
    }

    @Test
    fun `必填 level 缺失时记录 missing`() {
        val result = extractor.extract(numericRule, "climate.fan.speed.set", "风量档位调到")
        assertTrue(result.missing.contains("level"))
    }

    @Test
    fun `无位置词时 zone 不提取且不误报必填`() {
        val result = extractor.extract(numericRule, "climate.fan.speed.set", "风量档位调到5")
        assertNull(result.arguments.firstOrNull { it.name == "zone" })
        assertTrue(!result.missing.contains("zone"))
    }

    @Test
    fun `数值越界仍提取原始值（范围校验由 canonicalizer 完成）`() {
        val result = extractor.extract(numericRule, "climate.fan.speed.set", "风量档位调到99")
        assertEquals(99, result.arguments.first { it.name == "level" }.rawValue)
    }
}
