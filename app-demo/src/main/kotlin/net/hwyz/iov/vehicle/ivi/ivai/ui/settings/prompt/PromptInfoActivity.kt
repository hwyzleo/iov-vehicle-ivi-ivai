package net.hwyz.iov.vehicle.ivi.ivai.ui.settings.prompt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import androidx.fragment.app.FragmentActivity
import net.hwyz.iov.vehicle.ivi.ivai.demo.BuildConfig
import net.hwyz.iov.vehicle.ivi.ivai.demo.R
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentService

/**
 * 提示词信息页（IVI-IVAI-DSN-CR-004）：设置页「提示词信息」按钮进入的
 * 全屏只读页。绑定 AgentService，复用 [PromptInfoFragment] 渲染当前生效的
 * 提示词模板快照（debug 全量、release 版本+摘要）。
 */
class PromptInfoActivity : FragmentActivity() {

    private var promptFragment: PromptInfoFragment? = null
    private var agentService: AgentService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as AgentService.LocalBinder).getService()
            agentService = service
            promptFragment?.attach(
                ServicePromptInfoGateway(service),
                showFullContent = BuildConfig.DEBUG
            )
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_prompt_info)
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        if (savedInstanceState == null) {
            promptFragment = PromptInfoFragment.newInstance().also {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.promptInfoContainer, it)
                    .commit()
            }
        } else {
            promptFragment =
                supportFragmentManager.findFragmentById(R.id.promptInfoContainer) as? PromptInfoFragment
        }
        // The service may already be bound (chat screen stays alive behind us).
        agentService?.let { service ->
            promptFragment?.attach(
                ServicePromptInfoGateway(service),
                showFullContent = BuildConfig.DEBUG
            )
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
