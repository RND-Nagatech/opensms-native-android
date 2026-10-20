package org.daslearning.opensms.next

import android.Manifest
import android.app.Service
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import org.daslearning.opensms.next.data.SafeEvent
import org.daslearning.opensms.next.data.SafeEventLogger
import org.daslearning.opensms.next.data.AppPreferences
import org.daslearning.opensms.next.domain.GatewayNetworkMode
import org.daslearning.opensms.next.domain.GatewayRuntimeState
import org.daslearning.opensms.next.domain.GatewayServer
import org.daslearning.opensms.next.domain.GatewayServerStatus
import org.daslearning.opensms.next.domain.TrustedNetworkPolicy
import org.daslearning.opensms.next.domain.QueueCoordinator

@AndroidEntryPoint
class GatewaySessionService : Service() {
    @Inject lateinit var gatewayServer: GatewayServer
    @Inject lateinit var runtimeState: GatewayRuntimeState
    @Inject lateinit var networkPolicy: TrustedNetworkPolicy
    @Inject lateinit var logger: SafeEventLogger
    @Inject lateinit var queueCoordinator: QueueCoordinator
    @Inject lateinit var preferences: AppPreferences

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binder = LocalBinder()
    private val lifecycleMutex = Mutex()
    private var boundHost: String? = null
    private var networkCallbackRegistered = false
    private var queueJob: Job? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var wifiWakeLock: WifiManager.WifiLock? = null
    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) = stopIfBoundAddressChanged()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = stopIfBoundAddressChanged()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
            stopIfBoundAddressChanged()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopGateway()
        } else {
            promoteToForeground()
            val requestedMode = intent?.getStringExtra(EXTRA_NETWORK_MODE)
                ?.let { runCatching { GatewayNetworkMode.valueOf(it) }.getOrNull() }
            if (requestedMode != null) {
                startGateway(requestedMode)
            } else {
                scope.launch {
                    val saved = preferences.gatewaySession.first()
                    val restoredMode = runCatching { GatewayNetworkMode.valueOf(saved.mode) }.getOrNull()
                    if (saved.enabled && restoredMode != null &&
                        restoredMode in setOf(GatewayNetworkMode.LOOPBACK, GatewayNetworkMode.LAN)
                    ) {
                        startGateway(restoredMode, persistSession = false)
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }
                }
            }
        }
        // Preserve the last START intent (including the selected network mode) if an OEM
        // kills the hosting process while removing the app's task from Recents. The null
        // intent recovery path remains for legacy/system restarts without a redelivered one.
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        runBlocking(Dispatchers.IO) {
            lifecycleMutex.withLock {
                try {
                    queueJob?.cancelAndJoin()
                    queueJob = null
                    gatewayServer.stop()
                } finally {
                    releaseLanKeepAwake()
                    unregisterWifiCallback()
                    boundHost = null
                }
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun startGateway(mode: GatewayNetworkMode, persistSession: Boolean = true) {
        if (mode !in setOf(GatewayNetworkMode.LOOPBACK, GatewayNetworkMode.LAN)) return
        scope.launch { lifecycleMutex.withLock {
            val current = runtimeState.snapshot.value.server
            if (current == GatewayServerStatus.RUNNING || current == GatewayServerStatus.STARTING) return@withLock
            if (persistSession) preferences.setGatewaySession(enabled = true, mode = mode.name)
            runtimeState.update(org.daslearning.opensms.next.domain.GatewaySnapshot(GatewayServerStatus.STARTING, mode))
            updateNotification("Menyiapkan gateway ${mode.name}…")
            try {
                requirePermission(Manifest.permission.SEND_SMS)
                requirePermission(Manifest.permission.READ_PHONE_STATE)
                val host = when (mode) {
                    GatewayNetworkMode.LOOPBACK -> "127.0.0.1"
                    GatewayNetworkMode.LAN -> {
                        if (Build.VERSION.SDK_INT >= 37) requirePermission(Manifest.permission.ACCESS_LOCAL_NETWORK)
                        check(networkPolicy.allowLanListener()) { "TRUSTED_LAN_NOT_CONFIGURED" }
                        awaitWifiAddress()
                    }
                    GatewayNetworkMode.UNAVAILABLE -> error("NETWORK_MODE_UNAVAILABLE")
                }
                if (mode == GatewayNetworkMode.LAN) acquireLanKeepAwake()
                gatewayServer.start(host, GATEWAY_PORT)
                boundHost = if (mode == GatewayNetworkMode.LAN) host else null
                if (mode == GatewayNetworkMode.LAN) {
                    connectivityManager.registerNetworkCallback(
                        android.net.NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .build(),
                        wifiCallback,
                    )
                    networkCallbackRegistered = true
                    if (networkPolicy.localWifiAddress() != host || !networkPolicy.allowLanListener()) {
                        gatewayServer.stop()
                        boundHost = null
                        error("NETWORK_CHANGED")
                    }
                }
                runtimeState.update(
                    org.daslearning.opensms.next.domain.GatewaySnapshot(
                        server = GatewayServerStatus.RUNNING,
                        network = mode,
                        address = host,
                        port = GATEWAY_PORT,
                    ),
                )
                val endpointText = if (mode == GatewayNetworkMode.LAN) {
                    "Gateway LAN aktif di $host:$GATEWAY_PORT"
                } else {
                    "Gateway aktif di perangkat ini"
                }
                updateNotification(endpointText)
                logger.record(SafeEvent.GATEWAY_STARTED)
                queueJob = scope.launch {
                    var recovered = false
                    while (isActive) {
                        try {
                            if (!recovered) {
                                queueCoordinator.recoverInFlight()
                                recovered = true
                            } else if (!queueCoordinator.dispatchNext()) {
                                delay(QUEUE_POLL_MILLIS)
                            }
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (_: Exception) {
                            logger.record(SafeEvent.GATEWAY_ERROR, "QUEUE_PROCESSING_FAILED")
                            delay(QUEUE_POLL_MILLIS)
                        }
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                runCatching { gatewayServer.stop() }
                releaseLanKeepAwake()
                unregisterWifiCallback()
                boundHost = null
                preferences.setGatewaySession(enabled = false)
                val code = exception.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,48}")) } ?: "GATEWAY_START_FAILED"
                runtimeState.update(
                    org.daslearning.opensms.next.domain.GatewaySnapshot(
                        server = GatewayServerStatus.ERROR,
                        network = GatewayNetworkMode.UNAVAILABLE,
                        errorCode = code,
                    ),
                )
                logger.record(SafeEvent.GATEWAY_ERROR, code)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } }
    }

    private fun stopGateway() {
        scope.launch { lifecycleMutex.withLock {
            val current = runtimeState.snapshot.value.server
            if (current == GatewayServerStatus.STOPPING) return@withLock
            if (current == GatewayServerStatus.STOPPED) {
                preferences.setGatewaySession(enabled = false)
                releaseLanKeepAwake()
                unregisterWifiCallback()
                boundHost = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@withLock
            }
            preferences.setGatewaySession(enabled = false)
            runtimeState.update(org.daslearning.opensms.next.domain.GatewaySnapshot(GatewayServerStatus.STOPPING))
            updateNotification("Menghentikan gateway…")
            queueJob?.cancelAndJoin()
            queueJob = null
            try {
                gatewayServer.stop()
                releaseLanKeepAwake()
                unregisterWifiCallback()
                boundHost = null
                runtimeState.update(org.daslearning.opensms.next.domain.GatewaySnapshot(GatewayServerStatus.STOPPED))
                logger.record(SafeEvent.GATEWAY_STOPPED)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (_: Exception) {
                runtimeState.update(
                    org.daslearning.opensms.next.domain.GatewaySnapshot(
                        GatewayServerStatus.ERROR,
                        GatewayNetworkMode.UNAVAILABLE,
                        "GATEWAY_STOP_FAILED",
                    ),
                )
                logger.record(SafeEvent.GATEWAY_ERROR, "GATEWAY_STOP_FAILED")
                releaseLanKeepAwake()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } }
    }

    private fun unregisterWifiCallback() {
        if (!networkCallbackRegistered) return
        runCatching { connectivityManager.unregisterNetworkCallback(wifiCallback) }
        networkCallbackRegistered = false
    }

    @Suppress("DEPRECATION") // Only used below API 29; newer WifiLock modes are inactive with screen off.
    private fun acquireLanKeepAwake() {
        if (cpuWakeLock?.isHeld != true) {
            cpuWakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:gateway-lan")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && wifiWakeLock?.isHeld != true) {
            wifiWakeLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:gateway-lan")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    private fun releaseLanKeepAwake() {
        runCatching { if (wifiWakeLock?.isHeld == true) wifiWakeLock?.release() }
        wifiWakeLock = null
        runCatching { if (cpuWakeLock?.isHeld == true) cpuWakeLock?.release() }
        cpuWakeLock = null
    }

    private fun requirePermission(permission: String) {
        check(ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            "PERMISSION_REQUIRED"
        }
    }

    private suspend fun awaitWifiAddress(): String = withTimeoutOrNull(WIFI_WAIT_TIMEOUT_MILLIS) {
        while (true) {
            networkPolicy.localWifiAddress()?.let { return@withTimeoutOrNull it }
            delay(WIFI_RETRY_MILLIS)
        }
        error("WIFI_UNAVAILABLE")
    } ?: error("WIFI_UNAVAILABLE")

    private fun promoteToForeground() {
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Menyiapkan gateway…"), serviceType)
    }

    private fun updateNotification(message: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildNotification(message),
            )
        }
    }

    private fun buildNotification(message: String) = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_opensms)
        .setContentTitle("OpenSMS Next")
        .setContentText(message)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                NOTIFICATION_ID,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            R.drawable.ic_opensms,
            "Hentikan",
            PendingIntent.getService(
                this,
                NOTIFICATION_ID,
                Intent(this, GatewaySessionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Gateway OpenSMS",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Menampilkan status gateway SMS yang berjalan" },
            )
        }
    }

    private fun stopIfBoundAddressChanged() {
        val expected = boundHost ?: return
        if (networkPolicy.localWifiAddress() != expected || !networkPolicy.allowLanListener()) stopGateway()
    }

    inner class LocalBinder : Binder() {
        fun start(mode: GatewayNetworkMode) = startGateway(mode)
        fun stop() = stopGateway()
    }

    companion object {
        const val ACTION_START = "org.daslearning.opensms.next.action.START_GATEWAY"
        const val ACTION_STOP = "org.daslearning.opensms.next.action.STOP_GATEWAY"
        const val ACTION_RESTORE = "org.daslearning.opensms.next.action.RESTORE_GATEWAY"
        private const val EXTRA_NETWORK_MODE = "network_mode"
        private const val NOTIFICATION_CHANNEL_ID = "opensms_gateway"
        private const val NOTIFICATION_ID = 9292
        const val GATEWAY_PORT = 9292
        const val QUEUE_POLL_MILLIS = 1_000L
        private const val WIFI_WAIT_TIMEOUT_MILLIS = 60_000L
        private const val WIFI_RETRY_MILLIS = 1_000L

        fun startIntent(context: android.content.Context, mode: GatewayNetworkMode): Intent =
            Intent(context, GatewaySessionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_NETWORK_MODE, mode.name)

        fun stopIntent(context: android.content.Context): Intent =
            Intent(context, GatewaySessionService::class.java).setAction(ACTION_STOP)

        fun restoreIntent(context: android.content.Context): Intent =
            Intent(context, GatewaySessionService::class.java).setAction(ACTION_RESTORE)
    }
}
