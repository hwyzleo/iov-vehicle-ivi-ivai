package net.hwyz.iov.vehicle.ivi.ivai.ui.settings.rag

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import androidx.fragment.app.FragmentActivity
import net.hwyz.iov.vehicle.ivi.ivai.demo.R
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentService

/**
 * 检索增强 RAG 设置页（IVI-IVAI-DSN-CR-005）：设置页「检索增强RAG设置」按钮
 * 进入的全屏页。绑定 AgentService，复用 [RagConfigFragment] 渲染 RAG 总开关、
 * Tool/Knowledge 子开关与运行状态。
 */
class RagConfigActivity : FragmentActivity() {

    private var ragFragment: RagConfigFragment? = null
    private var agentService: AgentService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as AgentService.LocalBinder).getService()
            agentService = service
            ragFragment?.attach(ServiceRagConfigGateway(service))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rag_config)
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        if (savedInstanceState == null) {
            ragFragment = RagConfigFragment.newInstance().also {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.ragConfigContainer, it)
                    .commit()
            }
        } else {
            ragFragment =
                supportFragmentManager.findFragmentById(R.id.ragConfigContainer) as? RagConfigFragment
        }
        // The service may already be bound (chat screen stays alive behind us).
        agentService?.let { service ->
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
