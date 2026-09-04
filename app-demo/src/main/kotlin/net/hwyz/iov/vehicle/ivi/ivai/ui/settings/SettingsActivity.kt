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
import net.hwyz.iov.vehicle.ivi.ivai.ui.settings.prompt.PromptInfoFragment
import net.hwyz.iov.vehicle.ivi.ivai.ui.settings.prompt.ServicePromptInfoGateway

/**
 * Settings host (IVI-IVAI-DSN-CR-004). Binds the agent service and attaches the
 * read-only PromptInfo page once connected.
 */
class SettingsActivity : FragmentActivity() {

    private var fragment: PromptInfoFragment? = null
    private var agentService: AgentService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as AgentService.LocalBinder).getService()
            agentService = service
            fragment?.attach(
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
        setContentView(R.layout.activity_settings)
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        fragment = if (savedInstanceState == null) {
            PromptInfoFragment.newInstance().also {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.settingsContainer, it)
                    .commit()
            }
        } else {
            supportFragmentManager.findFragmentById(R.id.settingsContainer) as? PromptInfoFragment
        }
        // The service may already be bound (chat screen stays alive behind us).
        agentService?.let { service ->
            fragment?.attach(ServicePromptInfoGateway(service), showFullContent = BuildConfig.DEBUG)
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
