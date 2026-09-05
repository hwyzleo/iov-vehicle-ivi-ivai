package net.hwyz.iov.vehicle.ivi.ivai.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.fragment.app.FragmentActivity
import android.widget.Button
import net.hwyz.iov.vehicle.ivi.ivai.demo.BuildConfig
import net.hwyz.iov.vehicle.ivi.ivai.demo.R
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentService
import net.hwyz.iov.vehicle.ivi.ivai.ui.config.ModelConfigActivity
import net.hwyz.iov.vehicle.ivi.ivai.ui.config.asr.AsrConfigActivity
import net.hwyz.iov.vehicle.ivi.ivai.ui.settings.prompt.PromptInfoFragment
import net.hwyz.iov.vehicle.ivi.ivai.ui.settings.prompt.ServicePromptInfoGateway
import net.hwyz.iov.vehicle.ivi.ivai.ui.settings.rag.RagConfigFragment
import net.hwyz.iov.vehicle.ivi.ivai.ui.settings.rag.ServiceRagConfigGateway

/**
 * Settings hub (IVI-IVAI-DSN-CR-004 + CR-005 + CR-007): 模型配置 / 语音识别配置
 * entries plus the read-only PromptInfo page and the 检索增强 (RAG) config group
 * under 调试信息, all reachable from one 设置 button on the chat screen.
 */
class SettingsActivity : FragmentActivity() {

    private var promptFragment: PromptInfoFragment? = null
    private var ragFragment: RagConfigFragment? = null
    private var agentService: AgentService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as AgentService.LocalBinder).getService()
            agentService = service
            promptFragment?.attach(
                ServicePromptInfoGateway(service),
                showFullContent = BuildConfig.DEBUG
            )
            ragFragment?.attach(ServiceRagConfigGateway(service))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
        }
    }

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
        if (savedInstanceState == null) {
            promptFragment = PromptInfoFragment.newInstance().also {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.settingsPromptContainer, it)
                    .commit()
            }
            ragFragment = RagConfigFragment.newInstance().also {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.settingsRagContainer, it)
                    .commit()
            }
        } else {
            promptFragment = supportFragmentManager.findFragmentById(R.id.settingsPromptContainer) as? PromptInfoFragment
            ragFragment = supportFragmentManager.findFragmentById(R.id.settingsRagContainer) as? RagConfigFragment
        }
        // The service may already be bound (chat screen stays alive behind us).
        agentService?.let { service ->
            promptFragment?.attach(ServicePromptInfoGateway(service), showFullContent = BuildConfig.DEBUG)
            ragFragment?.attach(ServiceRagConfigGateway(service))
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, AgentService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        unbindService(serviceConnection)
        agentService = null
        super.onStop()
    }

    override fun onBackPressed() {
        finish()
    }
}
