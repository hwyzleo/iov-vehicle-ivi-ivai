package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BindingStatus
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType

/**
 * Tool 治理条目（IVI-IVAI-DSN-CR-009 Tool Catalog v1）。
 *
 * 每个 Tool 定义：稳定 Tool ID、名称、Domain、Capability Pack、OperationType、
 * 参数 Schema（目录文本，非运行时强制 JSON Schema）、Policy/确认、Binding 契约、
 * Alias 规则和 P0～P3 实施优先级、治理状态（默认 DRAFT）。
 *
 * DRAFT 条目可以作为接口、Schema、Registry、Mock Adapter 和测试骨架的开发输入；
 * 在完成来源 Alias、真实 Binding 与安全评审前**不得**进入运行时可执行索引。
 */
data class ToolGovernanceSpec(
    val toolId: String,
    val name: String,
    val domainId: BusinessDomainId,
    val capabilityPackId: String,
    val operationType: OperationType,
    val parameterSchema: String,
    val policySummary: String,
    val bindingContract: String = "按车型/版本解析 Adapter.method",
    val aliasRule: String = "来源标准功能/Function-ID 映射",
    val priority: ImplementationPriority,
    val governanceVersion: String = "ivai-governance-v1-draft",
    val status: GovernanceStatus = GovernanceStatus.DRAFT,
    val bindingStatus: BindingStatus = BindingStatus.NO_BINDING
)
