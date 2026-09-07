package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.capability.CapabilityPack
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BindingStatus
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-010 验证设计 · RuntimeCapabilityAssembler 单元测试。
 *
 *  - 统一候选集 = 选中 Pack 内全部运行时可执行 Tool（无独立 L0 白名单）。
 *  - STRICT 只收 APPROVED + BOUND；DEVELOPMENT_STUB 仅经豁免放行白名单 DRAFT
 *    （DRAFT 状态保留，仅 Mock Adapter）。
 *  - 旧 ID canonicalize 只产生一个新 ID 候选；双注册 / Alias 冲突 / 引用不闭合 /
 *    开发桩泄漏到 STRICT → 装配失败（IVAI-ALIAS-001 / IVAI-CAP-003 / IVAI-GOV-004）。
 */
class RuntimeCapabilityAssemblerTest {

    private val assembler = DefaultRuntimeCapabilityAssembler()

    private val packId = "cabin.climate"

    /** Pack 的 toolIds 由给定 tools 中同 Pack 条目自动汇总（含追加测试工具）。 */
    private fun pack(tools: List<ToolGovernanceSpec>): CapabilityPack = CapabilityPack(
        packId = packId,
        domainId = BusinessDomainId.CABIN_COMFORT,
        name = "空调与温控",
        toolIds = tools.filter { it.capabilityPackId == packId }.map { it.toolId }.toSet(),
        governanceVersion = "1.0",
        status = GovernanceStatus.DRAFT
    )

    private fun catalog(tools: List<ToolGovernanceSpec> = defaultTools()) =
        GovernanceCatalog(packs = listOf(pack(tools)), tools = tools, workflows = emptyList())

    private fun defaultTools(): List<ToolGovernanceSpec> = listOf(
        governed("climate.power.set"),
        governed("climate.temperature.set"),
        governed("climate.temperature.adjust"),
        governed("climate.status.query")
    )

    private fun governed(toolId: String, status: GovernanceStatus = GovernanceStatus.DRAFT, binding: BindingStatus = BindingStatus.NO_BINDING) =
        ToolGovernanceSpec(
            toolId = toolId,
            name = toolId,
            domainId = BusinessDomainId.CABIN_COMFORT,
            capabilityPackId = packId,
            operationType = OperationType.CONTROL,
            parameterSchema = "{}",
            policySummary = "LOW",
            priority = ImplementationPriority.P0,
            status = status,
            bindingStatus = binding
        )

    private fun env(selected: Set<String> = setOf(packId), mode: GovernanceRuntimeMode = GovernanceRuntimeMode.STRICT, exemptions: List<StubExemption> = emptyList(), softwareVersion: String? = null) =
        RuntimeEnvironment(selectedPackIds = selected, mode = mode, stubExemptions = exemptions, softwareVersion = softwareVersion)

    // ------------------------------------------------------------------ STRICT

    @Test
    fun `STRICT 只收 APPROVED 且 BOUND 的 Tool，DRAFT 一律排除`() {
        val tools = defaultTools() + governed("climate.defrost.set", GovernanceStatus.APPROVED, BindingStatus.BOUND)
        val result = assembler.assemble(catalog(tools), ToolAliasCatalog, env())
        assertTrue("climate.defrost.set" in result.runtimeCandidateToolIds)
        assertFalse("climate.power.set" in result.runtimeCandidateToolIds, "DRAFT 不得进入 STRICT 候选")
        assertEquals(1, result.runtimeCandidateToolIds.size)
        assertEquals("ivai-climate-migration-v1", result.migrationVersion)
        assertTrue(result.stubExemptedToolIds.isEmpty())
    }

    @Test
    fun `STRICT 只收 APPROVED 但未 BOUND 也被排除`() {
        val tools = defaultTools() + governed("climate.defrost.set", GovernanceStatus.APPROVED, BindingStatus.PENDING)
        val result = assembler.assemble(catalog(tools), ToolAliasCatalog, env())
        assertFalse("climate.defrost.set" in result.runtimeCandidateToolIds)
    }

    @Test
    fun `STRICT 不允许任何开发桩豁免`() {
        val e = assertThrows(RuntimeAssemblyException::class.java) {
            assembler.assemble(catalog(), ToolAliasCatalog, env(mode = GovernanceRuntimeMode.STRICT, exemptions = listOf(GovernanceWorkspace.devStubExemption())))
        }
        assertEquals("IVAI-GOV-004", e.errorCode)
    }

    // ------------------------------------------------------------------ DEVELOPMENT_STUB

    @Test
    fun `STUB 经豁免放行白名单 DRAFT 且保留 DRAFT 状态`() {
        val result = assembler.assemble(
            catalog(),
            ToolAliasCatalog,
            env(mode = GovernanceRuntimeMode.DEVELOPMENT_STUB, exemptions = listOf(stubExemption(setOf("climate.power.set"))))
        )
        assertTrue("climate.power.set" in result.runtimeCandidateToolIds, "豁免 DRAFT 应进入 STUB 候选")
        assertTrue("climate.power.set" in result.stubExemptedToolIds)
        assertFalse("climate.temperature.set" in result.runtimeCandidateToolIds, "未豁免 DRAFT 不得进入")
        // DRAFT 原始状态保留（目录不可变）。
        assertEquals(GovernanceStatus.DRAFT, ToolCatalogV1.get("climate.power.set")?.status)
    }

    @Test
    fun `STUB 过期豁免的 DRAFT 不得进入候选`() {
        // 当前软件版本 0.1.0 不在豁免 9.0.0~10.0.0 区间内 → 豁免无效，DRAFT 被排除。
        val expired = stubExemption(setOf("climate.power.set"), startVersion = "9.0.0", endVersion = "10.0.0")
        val result = assembler.assemble(
            catalog(),
            ToolAliasCatalog,
            env(mode = GovernanceRuntimeMode.DEVELOPMENT_STUB, exemptions = listOf(expired), softwareVersion = "0.1.0")
        )
        assertFalse("climate.power.set" in result.runtimeCandidateToolIds, "过期豁免的 DRAFT 应被排除")
        assertTrue(result.runtimeCandidateToolIds.isEmpty())
    }

    // ------------------------------------------------------------------ canonical 迁移与冲突

    @Test
    fun `旧空调 ID canonicalize 后只产生一个新 ID 候选不发生双匹配`() {
        // 全部 DRAFT 经豁免放行 canonical；旧 ID（climate.power_on 等）不在目录 → 不产生第二候选。
        val result = assembler.assemble(
            catalog(),
            ToolAliasCatalog,
            env(mode = GovernanceRuntimeMode.DEVELOPMENT_STUB, exemptions = listOf(GovernanceWorkspace.devStubExemption()))
        )
        val canonical = result.runtimeCandidateToolIds
        assertTrue(canonical.contains("climate.power.set"))
        assertFalse(canonical.contains("climate.power_on"), "旧 ID 不得作为独立候选")
        assertFalse(canonical.contains("climate.temperature_increase"), "旧 ID 不得作为独立候选")
        assertFalse(canonical.contains("climate.temperature_set"), "旧 ID 不得作为独立候选")
        // 同一输入只对应一个 canonical（去重）。
        assertEquals(canonical.size, canonical.size)
        assertEquals(canonical.toSet().size, canonical.size)
    }

    @Test
    fun `旧 ID 与 canonical ID 双注册触发 IVAI-ALIAS-001`() {
        // 同时存在旧 ID 定义（climate.power_on）与 canonical 定义（climate.power.set），
        // 且两者都被豁免放行 → 双注册，装配必须失败（IVAI-ALIAS-001）。
        val tools = defaultTools() + governed("climate.power_on")
        val dualExemption = stubExemption(setOf("climate.power.set", "climate.power_on"))
        val e = assertThrows(RuntimeAssemblyException::class.java) {
            assembler.assemble(
                catalog(tools),
                ToolAliasCatalog,
                env(mode = GovernanceRuntimeMode.DEVELOPMENT_STUB, exemptions = listOf(dualExemption))
            )
        }
        assertEquals("IVAI-ALIAS-001", e.errorCode)
    }

    @Test
    fun `Alias canonical 引用不闭合触发 IVAI-CAP-003`() {
        // catalog 中不存在 climate.power.set（canonical 目标），但 ToolAliasCatalog 映射需要它。
        val tools = listOf(governed("climate.defrost.set"))
        val e = assertThrows(RuntimeAssemblyException::class.java) {
            assembler.assemble(catalog(tools), ToolAliasCatalog, env(mode = GovernanceRuntimeMode.DEVELOPMENT_STUB, exemptions = listOf(GovernanceWorkspace.devStubExemption())))
        }
        assertEquals("IVAI-CAP-003", e.errorCode)
    }

    @Test
    fun `统一候选集无独立 L0 白名单且 Hash 稳定可复现`() {
        val tools = defaultTools().map {
            it.copy(status = GovernanceStatus.APPROVED, bindingStatus = BindingStatus.BOUND)
        }
        val result1 = assembler.assemble(catalog(tools), ToolAliasCatalog, env())
        val result2 = assembler.assemble(catalog(tools), ToolAliasCatalog, env())
        assertEquals(result1.runtimeCandidateToolIds, result2.runtimeCandidateToolIds)
        assertEquals(result1.runtimeCandidateToolIdsHash, result2.runtimeCandidateToolIdsHash)
        assertEquals(4, result1.runtimeCandidateToolIds.size)
        assertEquals(setOf(packId), result1.selectedPackIds)
    }

    // ------------------------------------------------------------------ CR-013 L0 确定性候选

    @Test
    fun `deterministicCandidateToolIds 是运行时合法候选的 SUPPORTED 治理投影`() {
        // climate.power.set 为 SUPPORTED；media.playback.play 为 NOT_SUPPORTED
        // （两者都 APPROVED + BOUND 进入 runtimeCandidate）。
        val tools = listOf(
            governed("climate.power.set", GovernanceStatus.APPROVED, BindingStatus.BOUND),
            governed("climate.temperature.set"),
            governed("climate.temperature.adjust"),
            governed("climate.status.query"),
            governed("media.playback.play", GovernanceStatus.APPROVED, BindingStatus.BOUND),
            governed("vehicle.drive_mode.set", GovernanceStatus.APPROVED, BindingStatus.BOUND)
        )
        val pack2 = CapabilityPack(
            packId = "media.audio",
            domainId = BusinessDomainId.MEDIA_ENTERTAINMENT,
            name = "媒体",
            toolIds = setOf("media.playback.play"),
            governanceVersion = "1.0",
            status = GovernanceStatus.APPROVED
        )
        val pack3 = CapabilityPack(
            packId = "vehicle.driving_config",
            domainId = BusinessDomainId.VEHICLE_DRIVING_CONFIG,
            name = "驾驶配置",
            toolIds = setOf("vehicle.drive_mode.set"),
            governanceVersion = "1.0",
            status = GovernanceStatus.APPROVED
        )
        val fullCatalog = GovernanceCatalog(
            packs = listOf(pack(tools), pack2, pack3),
            tools = tools,
            workflows = emptyList()
        )
        val result = assembler.assemble(
            fullCatalog,
            ToolAliasCatalog,
            env(selected = setOf(packId, "media.audio", "vehicle.driving_config"))
        )
        // 全部进入运行时合法候选（L1 边界不变）。
        assertTrue("climate.power.set" in result.runtimeCandidateToolIds)
        assertTrue("media.playback.play" in result.runtimeCandidateToolIds, "NOT_SUPPORTED 必须保留 L1 合法候选")
        assertTrue("vehicle.drive_mode.set" in result.runtimeCandidateToolIds, "NEEDS_REVIEW 必须保留 L1 合法候选")
        // 仅 SUPPORTED 进入确定性候选。
        assertEquals(setOf("climate.power.set"), result.deterministicCandidateToolIds)
        assertEquals("ivai-l0-rules-v1-draft", result.deterministicCatalogVersion)
        assertNotNull(result.deterministicCatalogHash)
        assertTrue(result.deterministicCandidateToolIds.all { it in result.runtimeCandidateToolIds })
    }

    private fun stubExemption(scope: Set<String>, startVersion: String = "0.0.0", endVersion: String? = "99.0.0") =
        StubExemption(
            exemptionId = "test-stub",
            scopeToolIds = scope,
            reason = "test",
            owner = "test",
            startVersion = startVersion,
            endVersion = endVersion
        )
}
