package net.hwyz.iov.vehicle.ivi.ivai.agenttest.import

import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.ActiveSuiteDescriptor
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.LoadedAgentTestSuite

/**
 * 导入异常（IVI-IVAI-DSN-CR-015 错误码 IVAI-TEST-IMPORT-001~006）。
 *
 * 由 Parser / Validator / Guard / ActiveSuiteStore 抛出，管线捕获后转换为
 * [SuiteImportResult.Failure]，保证任一步失败不修改当前激活 Suite。
 */
class SuiteImportException(
    val errorCode: String,
    message: String
) : RuntimeException(message)

/**
 * Suite 导入结果（IVI-IVAI-DSN-CR-015）。
 */
sealed interface SuiteImportResult {

    /** 导入成功：已原子激活并返回刷新后的加载结果。 */
    data class Success(
        val loaded: LoadedAgentTestSuite,
        val descriptor: ActiveSuiteDescriptor
    ) : SuiteImportResult

    /** 导入失败：不修改当前激活 Suite 与页面列表。 */
    data class Failure(
        val errorCode: String,
        val errorMessage: String
    ) : SuiteImportResult
}
