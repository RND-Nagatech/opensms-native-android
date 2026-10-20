package org.daslearning.opensms.next

import android.os.Bundle
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.daslearning.opensms.next.data.AppPreferences
import org.daslearning.opensms.next.data.SafeEvent
import org.daslearning.opensms.next.data.SafeEventLogger
import org.daslearning.opensms.next.domain.GatewayNetworkMode
import org.daslearning.opensms.next.domain.GatewaySnapshot
import org.daslearning.opensms.next.domain.GatewayStatusSource
import org.daslearning.opensms.next.domain.localNetworkEndpoint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import org.daslearning.opensms.next.ui.OpenSmsTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OpenSmsTheme { OpenSmsRoot(appViewModel) } }
    }

}

@HiltViewModel
class AppViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val logger: SafeEventLogger,
    @ApplicationContext private val context: Context,
    statusSource: GatewayStatusSource,
) : ViewModel() {
    val onboardingComplete = preferences.onboardingComplete.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    val gatewaySnapshot = statusSource.snapshot.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GatewaySnapshot(),
    )
    private val mutableServiceBound = kotlinx.coroutines.flow.MutableStateFlow(false)
    val serviceBound = mutableServiceBound
    private var sessionBinder: GatewaySessionService.LocalBinder? = null
    private var bound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            sessionBinder = service as? GatewaySessionService.LocalBinder
            mutableServiceBound.value = sessionBinder != null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sessionBinder = null
            mutableServiceBound.value = false
        }
    }

    init {
        bound = context.bindService(
            Intent(context, GatewaySessionService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            preferences.completeOnboarding()
            logger.record(SafeEvent.ONBOARDING_COMPLETED)
        }
    }

    fun startGateway(mode: GatewayNetworkMode) {
        ContextCompat.startForegroundService(context, GatewaySessionService.startIntent(context, mode))
    }

    fun stopGateway() {
        context.startService(GatewaySessionService.stopIntent(context))
    }

    override fun onCleared() {
        if (bound) context.unbindService(serviceConnection)
    }
}

private data class AppDestination(val route: String, val label: String, val icon: String) {
    companion object {
        val items = listOf(
            AppDestination("dashboard", "Beranda", "⌂"),
            AppDestination("messages", "Pesan", "▤"),
            AppDestination("diagnostics", "Diagnostik", "◉"),
            AppDestination("settings", "Pengaturan", "⚙"),
        )
    }
}

@Composable
private fun OpenSmsRoot(viewModel: AppViewModel) {
    val onboardingComplete by viewModel.onboardingComplete.collectAsState()
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val showNavigation = onboardingComplete && currentEntry?.destination?.route != "onboarding"

    Scaffold(
        bottomBar = {
            if (showNavigation) {
                NavigationBar {
                    AppDestination.items.forEach { destination ->
                        val selected = currentEntry?.destination?.hierarchy?.any {
                            it.route == destination.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigate(destination.route) {
                                popUpTo("dashboard") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            } },
                            icon = { Text(destination.icon) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (onboardingComplete) "dashboard" else "onboarding",
            modifier = Modifier.padding(padding),
        ) {
            composable("onboarding") {
                OnboardingScreen(onContinue = {
                    viewModel.completeOnboarding()
                    navController.navigate("dashboard") { popUpTo("onboarding") { inclusive = true } }
                })
            }
            composable("dashboard") {
                val snapshot by viewModel.gatewaySnapshot.collectAsState()
                val serviceBound by viewModel.serviceBound.collectAsState()
                DashboardScreen(
                    snapshot = snapshot,
                    serviceBound = serviceBound,
                    onStart = viewModel::startGateway,
                    onStop = viewModel::stopGateway,
                )
            }
            composable("messages") { MessageHistoryScreen() }
            composable("diagnostics") { DiagnosticsScreen() }
            composable("settings") { KeyManagementScreen() }
        }
    }
}

@Composable
private fun OnboardingScreen(onContinue: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("OpenSMS Next", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(16.dp))
            Text("Gateway SMS lokal yang hanya aktif saat Anda menyalakannya.")
            Spacer(Modifier.height(12.dp))
            Text("Pesan dapat dikenai biaya operator. Gunakan hanya pada jaringan tepercaya. Status SENT tidak menjamin pesan diterima.")
            Spacer(Modifier.height(24.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text("Saya mengerti")
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    snapshot: GatewaySnapshot,
    serviceBound: Boolean,
    onStart: (GatewayNetworkMode) -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    var selectedMode by remember { mutableStateOf(GatewayNetworkMode.LOOPBACK) }
    var permissionError by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) onStart(selectedMode) else permissionError = true
    }
    val gatewayActive = snapshot.server == org.daslearning.opensms.next.domain.GatewayServerStatus.RUNNING ||
        snapshot.server == org.daslearning.opensms.next.domain.GatewayServerStatus.STARTING
    val displayedMode = if (gatewayActive) snapshot.network else selectedMode
    val requestStart: () -> Unit = {
        val permissions = buildList {
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (selectedMode == GatewayNetworkMode.LAN && Build.VERSION.SDK_INT >= 37) {
                add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        }.distinct()
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        permissionError = false
        if (missing.isEmpty()) onStart(selectedMode)
        else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("OpenSMS Next", style = MaterialTheme.typography.headlineMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Gateway")
                    Text(snapshot.server.name, color = if (snapshot.server == org.daslearning.opensms.next.domain.GatewayServerStatus.RUNNING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                Text(if (snapshot.network == GatewayNetworkMode.LOOPBACK) "Akses lokal perangkat ini" else if (snapshot.network == GatewayNetworkMode.LAN) "LAN tepercaya" else "Listener mati")
                val endpoint = snapshot.localNetworkEndpoint()
                if (endpoint != null) {
                    Text("Alamat untuk perangkat di jaringan lokal", style = MaterialTheme.typography.titleSmall)
                    Text(endpoint, style = MaterialTheme.typography.bodyLarge)
                    Text("API v1: $endpoint/v1", style = MaterialTheme.typography.bodyMedium)
                } else if (snapshot.server == org.daslearning.opensms.next.domain.GatewayServerStatus.RUNNING && snapshot.network == GatewayNetworkMode.LOOPBACK) {
                    Text("Gateway hanya dapat diakses dari perangkat ini. Pilih LAN sebelum mulai untuk akses dari perangkat lain.")
                } else if (snapshot.server == org.daslearning.opensms.next.domain.GatewayServerStatus.RUNNING && snapshot.network == GatewayNetworkMode.LAN) {
                    Text("Alamat LAN tidak tersedia.", color = MaterialTheme.colorScheme.error)
                }
                if (snapshot.errorCode != null) Text("Alasan: ${snapshot.errorCode}", color = MaterialTheme.colorScheme.error)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChoiceButton(
                label = "PERANGKAT",
                selected = displayedMode == GatewayNetworkMode.LOOPBACK,
                modifier = Modifier.weight(1f),
                onClick = { selectedMode = GatewayNetworkMode.LOOPBACK },
            )
            ModeChoiceButton(
                label = "LAN",
                selected = displayedMode == GatewayNetworkMode.LAN,
                modifier = Modifier.weight(1f),
                onClick = { selectedMode = GatewayNetworkMode.LAN },
            )
        }
        if (snapshot.server == org.daslearning.opensms.next.domain.GatewayServerStatus.STOPPING) {
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Menghentikan gateway…")
                }
            }
        } else if (snapshot.server == org.daslearning.opensms.next.domain.GatewayServerStatus.RUNNING ||
            snapshot.server == org.daslearning.opensms.next.domain.GatewayServerStatus.STARTING
        ) {
            Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Hentikan gateway") }
        } else {
            Button(onClick = requestStart, enabled = serviceBound, modifier = Modifier.fillMaxWidth()) {
                Text(if (serviceBound) "Mulai gateway" else "Menyiapkan…")
            }
        }
        if (permissionError) Text("Izin ditolak. Gateway tidak dimulai.", color = MaterialTheme.colorScheme.error)
        Text(
            if (gatewayActive && displayedMode == GatewayNetworkMode.LAN) {
                "Mode LAN menjaga CPU tetap aktif selama diizinkan sistem. Pada Infinix/XOS, izinkan aktivitas latar belakang dan autostart OpenSMS serta atur baterai ke Tanpa batas agar gateway tidak dibekukan saat layar mati. Doze atau kebijakan perangkat masih dapat memutus jaringan; Hentikan gateway untuk melepas kunci CPU."
            } else {
                "Gateway berjalan dengan notifikasi persisten di latar belakang. Android atau penghemat baterai perangkat tetap dapat menghentikannya."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ModeChoiceButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text("✓  $label")
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(label)
        }
    }
}

