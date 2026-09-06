package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-009 验证设计 · Governance Manifest：
 *  - target 与第一版基线一致（10/18/160/18）。
 *  - actual 由构建流程计算（v1 目录全 DRAFT → approved/bound 为 0，needsReview=620）。
 *  - 五段 Hash 生成且可复现。
 *  - Manifest 可序列化为 JSON。
 */
class GovernanceManifestTest {

    @Test
    fun `Manifest 目标与第一版基线一致`() {
        val manifest = GovernanceWorkspace.manifest
        assertEquals("ivai-governance-v1", manifest.baselineVersion)
        assertEquals(10, manifest.target.businessDomainCount)
        assertEquals(18, manifest.target.capabilityPackCount)
        assertEquals(160, manifest.target.toolCount)
        assertEquals(18, manifest.target.workflowCount)
    }

    @Test
    fun `Manifest 来源目录统计正确`() {
        val manifest = GovernanceWorkspace.manifest
        assertEquals(18, manifest.sourceCatalog.domainCount)
        assertEquals(1730, manifest.sourceCatalog.standardFeatureCount)
        assertEquals(3444, manifest.sourceCatalog.instructionCount)
        assertEquals(620, manifest.sourceCatalog.needsReviewFeatureCount)
    }

    @Test
    fun `actual 由目录计算而非手工维护`() {
        val manifest = GovernanceWorkspace.manifest
        // v1 目录全部 DRAFT + NO_BINDING → approved/bound 为 0。
        assertEquals(0, manifest.actual.approvedToolCount)
        assertEquals(0, manifest.actual.boundToolCount)
        assertEquals(0, manifest.actual.approvedWorkflowCount)
        assertEquals(0, manifest.actual.boundWorkflowCount)
        assertEquals(620, manifest.actual.needsReviewCount)
        // actual 与目录实际数量可核对。
        assertEquals(ToolCatalogV1.ALL.size, 160)
        assertEquals(WorkflowCatalogV1.ALL.size, 18)
    }

    @Test
    fun `五段 Hash 非空且可复现`() {
        val manifest = GovernanceWorkspace.manifest
        assertFalse(manifest.hashes.toolManifest.isBlank())
        assertFalse(manifest.hashes.domainManifest.isBlank())
        assertFalse(manifest.hashes.capabilityManifest.isBlank())
        assertFalse(manifest.hashes.workflowManifest.isBlank())
        assertFalse(manifest.hashes.aliasManifest.isBlank())
        // 同输入再次构建 → Hash 一致。
        val rebuilt = GovernanceManifestBuilder.build(
            GovernanceWorkspace.baseline,
            GovernanceWorkspace.packs,
            GovernanceWorkspace.tools,
            GovernanceWorkspace.workflows,
            GovernanceWorkspace.sourceCatalog
        )
        assertEquals(manifest.hashes.toolManifest, rebuilt.hashes.toolManifest)
    }

    @Test
    fun `Manifest 可序列化为 JSON 并反序列化`() {
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(GovernanceManifest.serializer(), GovernanceWorkspace.manifest)
        assertTrue(encoded.contains("\"baselineVersion\""))
        assertTrue(encoded.contains("ivai-governance-v1"))
        val decoded = json.decodeFromString(GovernanceManifest.serializer(), encoded)
        assertEquals(GovernanceWorkspace.manifest.actual.boundToolCount, decoded.actual.boundToolCount)
        assertEquals(GovernanceWorkspace.manifest.target.toolCount, decoded.target.toolCount)
    }
}
