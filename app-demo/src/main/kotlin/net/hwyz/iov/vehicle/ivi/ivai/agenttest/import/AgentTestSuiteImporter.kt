package net.hwyz.iov.vehicle.ivi.ivai.agenttest.import

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode

/**
 * Suite 导入器（IVI-IVAI-DSN-CR-015）。通过系统文件选择器返回的 content:// URI
 * 读取单个 JSON 文件；读取后统一进入字节管线（解析 → 校验 → 指纹 → 原子激活）。
 */
interface AgentTestSuiteImporter {
    /** 从 content:// URI 读取并导入 Suite JSON。 */
    suspend fun import(uri: Uri): SuiteImportResult

    /**
     * 直接以字节导入（单元测试与复用管线入口；生产路径由 [import] 读取后进入同一管线）。
     */
    suspend fun importBytes(bytes: ByteArray): SuiteImportResult
}

/**
 * ContentResolver 实现（IVI-IVAI-DSN-CR-015）。
 *
 *  - 使用 ContentResolver.openInputStream 读取 content:// URI，不把 URI 当作普通
 *    文件路径，不申请 MANAGE_EXTERNAL_STORAGE 或全盘读写权限。
 *  - 受限读取（ImportSizeGuard），避免超大文件载入内存。
 *  - 用户取消选择由 Activity 处理（不进入本实现）。
 */
class ContentResolverSuiteImporter(
    private val contentResolver: ContentResolver,
    private val pipeline: SuiteImportPipeline
) : AgentTestSuiteImporter {

    override suspend fun import(uri: Uri): SuiteImportResult = withContext(Dispatchers.IO) {
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { stream ->
                pipeline.guard.readLimited(stream)
            }
        } catch (e: SuiteImportException) {
            return@withContext SuiteImportResult.Failure(e.errorCode, e.message ?: "读取失败")
        } catch (e: Exception) {
            return@withContext SuiteImportResult.Failure(
                TestErrorCode.IMPORT_URI_UNREADABLE,
                "文件 URI 无法打开或读取失败：${e.message}"
            )
        }
        if (bytes == null) {
            return@withContext SuiteImportResult.Failure(
                TestErrorCode.IMPORT_URI_UNREADABLE,
                "文件 URI 无法打开或读取失败"
            )
        }
        pipeline.import(bytes)
    }

    override suspend fun importBytes(bytes: ByteArray): SuiteImportResult =
        pipeline.import(bytes)
}
