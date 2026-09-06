package net.hwyz.iov.vehicle.ivi.ivai.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity
import net.hwyz.iov.vehicle.ivi.ivai.demo.R
import net.hwyz.iov.vehicle.ivi.ivai.ui.config.ModelConfigActivity
import net.hwyz.iov.vehicle.ivi.ivai.ui.config.asr.AsrConfigActivity
import net.hwyz.iov.vehicle.ivi.ivai.ui.settings.prompt.PromptInfoActivity
import net.hwyz.iov.vehicle.ivi.ivai.ui.settings.rag.RagConfigActivity
import net.hwyz.iov.vehicle.ivi.ivai.ui.settings.vehicle.VehicleInfoActivity

/**
 * Settings hub（IVI-IVAI-DSN-CR-004 + CR-005 + CR-007）：本地模型配置 / 语音识别
 * 配置 / 提示词信息 / 本车功能 / 检索增强RAG设置 全部由按钮进入独立页面，
 * 统一从聊天页的「设置」进入。本页不持有 AgentService，各子页自行绑定。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.modelConfigButton).setOnClickListener {
            startActivity(Intent(this, ModelConfigActivity::class.java))
        }
        findViewById<Button>(R.id.asrConfigButton).setOnClickListener {
            startActivity(Intent(this, AsrConfigActivity::class.java))
        }
        findViewById<Button>(R.id.promptInfoButton).setOnClickListener {
            startActivity(Intent(this, PromptInfoActivity::class.java))
        }
        findViewById<Button>(R.id.vehicleInfoButton).setOnClickListener {
            startActivity(Intent(this, VehicleInfoActivity::class.java))
        }
        findViewById<Button>(R.id.ragConfigButton).setOnClickListener {
            startActivity(Intent(this, RagConfigActivity::class.java))
        }
    }

    override fun onBackPressed() {
        finish()
    }
}
