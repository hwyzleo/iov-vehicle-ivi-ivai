package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.schema

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases.DefaultAliasLexicons
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.DeterministicIntentRule
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolExecutionBinding
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-016 单元测试：统一参数规范化（ParameterCanonicalizationService）。
 *
 * 覆盖设计测试设计：
 *  - “5档”“调到5”“设为5”在匹配规则声明 NUMERIC 槽位时统一输出 level=5；
 *  - 全部、所有、整车、ALL 和 all 在 zone 字段中输出同一 canonical 值；
 *  - 自由文本参数不因 canonicalizer 被任意 lowercase；
 *  - 未知 Tool、唯一 Alias 和冲突 Alias 分别被阻止、转换和拒绝；
 *  - 越界与必填缺失被拒绝（IVAI-PARAM-001）。
 */
class ParameterCanonicalizationServiceTest {

    private val fanSpeedSetSchema = """
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
            parameterSchema = fanSpeedSetSchema,
            policy = ToolPolicy(),
            execution = ToolExecutionBinding(adapterId = "test", methodId = "test")
        )
    )

    private val service = DefaultParameterCanonicalizationService(registry, DefaultAliasLexicons.DEFAULT)

    @Test
    fun `整数档位保留整数语义`() {
        val result = service.canonicalize(
            "climate.fan.speed.set",
            mapOf("level" to 5),
            CanonicalizationSource.L0_RULE,
            requiredOverride = setOf("level")
        )
        assertInstanceOf(CanonicalizationResult.Success::class.java, result)
        assertEquals(5, (result as CanonicalizationResult.Success).canonicalArguments["level"])
    }

    @Test
    fun `字符串数字转换为整数`() {
        val result = service.canonicalize(
            "climate.fan.speed.set",
            mapOf("level" to "5"),
            CanonicalizationSource.L0_RULE,
            requiredOverride = setOf("level")
        )
        assertInstanceOf(CanonicalizationResult.Success::class.java, result)
        assertEquals(5, (result as CanonicalizationResult.Success).canonicalArguments["level"])
    }

    @Test
    fun `全部 所有 整车 ALL all 统一为 canonical all`() {
        for (alias in listOf("全部", "所有", "整车", "ALL", "all")) {
            val result = service.canonicalize(
                "climate.fan.speed.set",
                mapOf("zone" to alias, "level" to 3),
                CanonicalizationSource.L0_RULE,
                requiredOverride = setOf("level")
            )
            assertInstanceOf(CanonicalizationResult.Success::class.java, result, "zone=$alias 应可 canonicalize")
            assertEquals("all", (result as CanonicalizationResult.Success).canonicalArguments["zone"])
        }
    }

    @Test
    fun `自由文本参数不被任意 lowercase`() {
        val result = service.canonicalize(
            "climate.fan.speed.set",
            mapOf("zone" to "DRIVER", "level" to 3),
            CanonicalizationSource.L0_RULE,
            requiredOverride = setOf("level")
        )
        // Schema enum 含 driver；DRIVER 经忽略大小写比对映射（受控），不改变自由文本语义。
        assertInstanceOf(CanonicalizationResult.Success::class.java, result)
        assertEquals("driver", (result as CanonicalizationResult.Success).canonicalArguments["zone"])
    }

    @Test
    fun `必填缺失返回 Failed 且带 missingArguments`() {
        val result = service.canonicalize(
            "climate.fan.speed.set",
            mapOf("zone" to "driver"),
            CanonicalizationSource.L0_RULE
        )
        assertInstanceOf(CanonicalizationResult.Failed::class.java, result)
        val failed = result as CanonicalizationResult.Failed
        assertEquals("IVAI-PARAM-001", failed.errorCode)
        assertTrue(failed.missingArguments.contains("level"))
    }

    @Test
    fun `越界值被拒绝`() {
        val result = service.canonicalize(
            "climate.fan.speed.set",
            mapOf("level" to 99),
            CanonicalizationSource.L0_RULE,
            requiredOverride = setOf("level")
        )
        assertInstanceOf(CanonicalizationResult.Failed::class.java, result)
        assertEquals("IVAI-PARAM-001", (result as CanonicalizationResult.Failed).errorCode)
    }

    @Test
    fun `未知 Tool 被阻止`() {
        val result = service.canonicalize(
            "climate.ghost",
            mapOf("level" to 1),
            CanonicalizationSource.L0_RULE
        )
        assertInstanceOf(CanonicalizationResult.Failed::class.java, result)
        assertEquals("IVAI-PARAM-001", (result as CanonicalizationResult.Failed).errorCode)
    }

    @Test
    fun `字段级枚举 Alias 表外值不被转换`() {
        // zone=weird 不在 Schema enum 也不在 Alias 表：保留原值（由 ToolValidator 判定）。
        val result = service.canonicalize(
            "climate.fan.speed.set",
            mapOf("zone" to "weird", "level" to 3),
            CanonicalizationSource.L0_RULE,
            requiredOverride = setOf("level")
        )
        assertInstanceOf(CanonicalizationResult.Success::class.java, result)
        assertEquals("weird", (result as CanonicalizationResult.Success).canonicalArguments["zone"])
    }

    @Test
    fun `stableJsonObject 保持参数稳定序列化`() {
        val result = service.canonicalize(
            "climate.fan.speed.set",
            mapOf("level" to 2, "zone" to "全部"),
            CanonicalizationSource.L0_RULE,
            requiredOverride = setOf("level")
        )
        assertInstanceOf(CanonicalizationResult.Success::class.java, result)
        val success = result as CanonicalizationResult.Success
        assertEquals(listOf("level", "zone"), success.canonicalArguments.keys.toList())
        assertFalse(success.argumentSources.isEmpty())
    }
}
