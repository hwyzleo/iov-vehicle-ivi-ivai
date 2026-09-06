package net.hwyz.iov.vehicle.ivi.ivai.adapter.mock

import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionContext
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionStatus
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolExecutionResult
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleToolAdapter

/**
 * 通用治理 Mock 桩（IVI-IVAI-DSN-CR-009）。
 *
 * 支撑 160 个治理目录 Tool 的端到端执行与契约测试：任意 methodId（即 Tool ID）
 * 都返回 SUCCEEDED 并记录最后一次执行，参数原样回显到 stateChanges，便于断言
 * 参数传递正确。真实车辆 Binding 完成后由具体 Adapter 替换。
 */
class MockGovernedToolAdapter : VehicleToolAdapter {

    override val adapterId: String = "mock-governed"

    /** 最近一次执行记录（methodId → arguments）。 */
    @Volatile
    var lastMethodId: String? = null

    @Volatile
    var lastArguments: Map<String, Any?> = emptyMap()

    private val executedMethods = LinkedHashSet<String>()

    /** 已执行过的 methodId 集合（用于幂等/恢复断言）。 */
    @Synchronized
    fun executedMethods(): Set<String> = executedMethods.toSet()

    @Synchronized
    fun reset() {
        lastMethodId = null
        lastArguments = emptyMap()
        executedMethods.clear()
    }

    override suspend fun execute(
        methodId: String,
        arguments: Map<String, Any?>,
        context: ExecutionContext
    ): ToolExecutionResult {
        lastMethodId = methodId
        lastArguments = arguments
        synchronized(this) {
            executedMethods += methodId
        }
        return ToolExecutionResult(
            requestId = context.requestId,
            toolId = methodId,
            status = ExecutionStatus.SUCCEEDED,
            message = "模拟执行：$methodId",
            stateChanges = arguments + mapOf("methodId" to methodId)
        )
    }

    override fun snapshot(): VehicleStateSnapshot = VehicleStateSnapshot(powerOn = true)
}
