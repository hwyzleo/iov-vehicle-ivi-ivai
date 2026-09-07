package net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository

import android.content.Context

/**
 * Android debug assets 加载器（IVI-IVAI-DSN-CR-012）。
 * 测试资产放在 debug source set（src/debug/assets），Release 不打包。
 */
class AssetsAgentTestCaseLoader(
    context: Context,
    private val assetPath: String = DEFAULT_ASSET_PATH
) : AgentTestCaseLoader {

    private val assets = context.assets

    override fun load(): String? = runCatching {
        assets.open(assetPath).bufferedReader().use { it.readText() }
    }.getOrNull()

    companion object {
        const val DEFAULT_ASSET_PATH = "agent-tests/v1/agent-regression.json"
    }
}
