package org.daslearning.opensms.next.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.daslearning.opensms.next.GatewaySessionService
import org.daslearning.opensms.next.data.AppPreferences

@AndroidEntryPoint
class GatewayBootReceiver : BroadcastReceiver() {
    @Inject lateinit var preferences: AppPreferences

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        receiverScope.launch {
            try {
                if (preferences.gatewaySession.first().enabled) {
                    ContextCompat.startForegroundService(
                        context,
                        GatewaySessionService.restoreIntent(context),
                    )
                }
            } catch (_: Exception) {
                // A user can open the app and start the gateway again if boot restore is unavailable.
            } finally {
                pendingResult.finish()
                receiverScope.cancel()
            }
        }
    }
}
