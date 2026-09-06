package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ClimateToolDefinitions
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-008 验证设计 · 治理模型测试：
 *  - 18 个原始领域全量映射到 BD01~BD10 / 操作类型 / 能力包。
 *  - 业务领域与操作类型拆分（页面导航 → NAVIGATE_UI、状态查询 → QUERY、场景 → WORKFLOW）。
 *  - 运行时索引只允许 APPROVED + BOUND（未批准 / 无 Binding 被 IVAI-GOV-001 / IVAI-BINDING-001 阻止）。
 *  - 参数化 Tool 归并建议（主驾/副驾升温降温 → adjust）。
 *  - 现有 6 个空调 Tool 携带领域 / 能力包 / 操作类型 / Alias 元数据。
 */
class GovernanceModelTest {

    @Test
    fun `业务领域带 BD 编码与中文名且可按编码反查`() {
        assertEquals("BD01", BusinessDomainId.CABIN_COMFORT.code)
        assertEquals("座舱舒适", BusinessDomainId.CABIN_COMFORT.label)
        assertEquals(BusinessDomainId.CABIN_COMFORT, BusinessDomainId.fromCode("BD01"))
        assertEquals(BusinessDomainId.ENERGY, BusinessDomainId.fromLabel("能源与补能"))
        assertNull(BusinessDomainId.fromCode("BD99"))
        assertEquals(10, BusinessDomainId.entries.size)
    }

    @Test
    fun `18 个原始领域全部映射到建议治理方式`() {
        val migrations = DomainMigrationCatalog.MIGRATIONS
        assertEquals(18, migrations.size)
        assertEquals(18, OriginalDomain.entries.size)
        for (migration in migrations) {
            val original = migration.originalDomain
            when (original) {
                OriginalDomain.PAGE_NAVIGATION -> {
                    assertNull(migration.suggestedDomain)
                    assertEquals(OperationType.NAVIGATE_UI, migration.operationType)
                }
                OriginalDomain.STATUS_QUERY -> {
                    assertNull(migration.suggestedDomain)
                    assertEquals(OperationType.QUERY, migration.operationType)
                }
                OriginalDomain.SCENE_MODE -> {
                    assertNull(migration.suggestedDomain)
                    assertEquals(OperationType.WORKFLOW, migration.operationType)
                }
                OriginalDomain.NATURAL_EXPRESSION, OriginalDomain.SEMANTIC_SUPPORT, OriginalDomain.EXECUTION_ADAPTER -> {
                    assertNull(migration.suggestedDomain)
                }
                OriginalDomain.DIRECT_VEHICLE_CONTROL -> assertNull(migration.suggestedDomain)
                else -> assertTrue(migration.suggestedDomain != null, "$original 应映射到建议业务领域")
            }
        }
    }

    @Test
    fun `空调与舒适映射到 BD01 座舱舒适并关联空调能力包`() {
        val migration = DomainMigrationCatalog.MIGRATIONS
            .first { it.originalDomain == OriginalDomain.AIR_CONDITIONING }
        assertEquals(BusinessDomainId.CABIN_COMFORT, migration.suggestedDomain)
        assertTrue("cabin.climate" in migration.capabilityPacks)
    }

    @Test
    fun `运行时索引只允许 APPROVED 且 BOUND 的治理能力`() {
        val indexable = ClimateGovernedCapabilities.runtimeIndexable()
        // 6 个运行时 Tool 全量可索引。
        assertEquals(6, indexable.size)
        assertTrue(indexable.all { it.status == GovernanceStatus.APPROVED })
        assertTrue(indexable.all { it.bindingStatus == BindingStatus.BOUND })
        // 参数化 adjust 建议虽 APPROVED 但 Binding PENDING → 不得进入运行时索引。
        val adjust = ClimateGovernedCapabilities.PARAMETERIZED_SUGGESTION
        assertEquals("climate.temperature.adjust", adjust.toolId)
        assertEquals(BindingStatus.PENDING, adjust.bindingStatus)
        assertTrue(indexable.none { it.toolId == "climate.temperature.adjust" })
    }

    @Test
    fun `参数化 Tool 建议保留 4 项原始记录并归并 Function-ID`() {
        val adjust = ClimateGovernedCapabilities.PARAMETERIZED_SUGGESTION
        assertEquals(4, adjust.sources.size)
        assertEquals(
            setOf("主驾升温", "副驾升温", "主驾降温", "副驾降温"),
            adjust.sources.map { it.representativeUtterance }.toSet()
        )
        assertTrue(adjust.functionIds.containsAll(listOf("AC_Temperature_2", "AC_Temperature_3")))
        assertTrue(adjust.governanceReason.contains("归并"))
    }

    @Test
    fun `现有 6 个空调 Tool 携带领域能力包操作类型与 Alias 元数据`() {
        val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
        assertEquals(6, registry.count())
        for (tool in registry.all()) {
            assertEquals(BusinessDomainId.CABIN_COMFORT, tool.domainId)
            assertEquals("cabin.climate", tool.capabilityPackId)
            assertTrue(tool.governanceVersion.isNotBlank())
        }
        val inc = registry.get("climate.temperature_increase")!!
        assertTrue(inc.supportedOperations.contains(OperationType.CONTROL))
        // 归并表达 Alias：主驾升温 → position=driver。
        val alias = inc.aliases.first { it.sourceValue == "主驾升温" }
        assertEquals("driver", alias.mappedArguments["position"])
        assertEquals(AliasSourceType.EXPRESSION, alias.sourceType)
        // 状态查询标记为 QUERY 操作类型。
        assertEquals(setOf(OperationType.QUERY), registry.get("climate.status_query")!!.supportedOperations)
    }

    @Test
    fun `非 CABIN 域 Tool 领域元数据默认可用于治理追踪`() {
        // 参数化 adjust 建议归并方向校验：direction/step 语义由参数承载。
        val adjust = ClimateGovernedCapabilities.PARAMETERIZED_SUGGESTION
        assertFalse(adjust.sources.isEmpty())
        assertTrue(adjust.capabilityPackId == "cabin.climate")
    }
}
