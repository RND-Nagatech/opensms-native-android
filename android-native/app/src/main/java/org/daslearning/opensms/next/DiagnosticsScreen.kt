package org.daslearning.opensms.next

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun MessageHistoryScreen(viewModel: MessageHistoryViewModel = hiltViewModel()) {
    val messages by viewModel.messages.collectAsState()
    if (messages.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Riwayat pesan", style = MaterialTheme.typography.headlineMedium)
            Text("Pesan yang diterima ke antrean akan muncul di sini. Isi pesan tidak ditampilkan.")
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Riwayat pesan", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 16.dp)) }
        items(messages, key = { it.id }) { message ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(message.destinationMasked, style = MaterialTheme.typography.titleMedium)
                        Text(message.status)
                    }
                    Text("Laporan: ${message.deliveryStatus} · bagian ${message.partsTotal}")
                    if (message.status == "SENT" && message.deliveryStatus in setOf("FAILED", "UNKNOWN")) {
                        Text("SMS sudah terkirim; laporan penerimaan ${message.deliveryStatus.lowercase()} dan tidak membatalkan status terkirim.")
                    }
                    Text(DateFormat.getDateTimeInstance().format(Date(message.createdAt)), style = MaterialTheme.typography.bodySmall)
                    if (message.errorCode != null) Text("Alasan: ${message.errorCode}", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val gateway by viewModel.gateway.collectAsState()
    val queue by viewModel.queueCount.collectAsState()
    val sims by viewModel.activeSubscriptions.collectAsState()
    val simError by viewModel.simError.collectAsState()
    val exportMessage by viewModel.exportMessage.collectAsState()
    val exportLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.exportRedactedAudit(uri, context.contentResolver)
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Diagnostik", style = MaterialTheme.typography.headlineMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Gateway: ${gateway.server}", style = MaterialTheme.typography.titleMedium)
                Text("Jaringan: ${gateway.network}")
                Text("Antrean aktif: $queue")
                if (gateway.errorCode != null) Text("Gangguan: ${gateway.errorCode}", color = MaterialTheme.colorScheme.error)
            }
        }
        Text("SIM aktif", style = MaterialTheme.typography.titleLarge)
        if (simError != null) Text(simError!!)
        if (sims.isEmpty() && simError == null) Text("Tidak ada SIM SMS aktif.")
        sims.forEach { sim -> Text("SIM ${sim.slotIndex + 1}: ${sim.displayName} · ID ${sim.subscriptionId}") }
        Button(onClick = viewModel::refresh) { Text("Perbarui diagnostik") }
        Button(onClick = { exportLog.launch("opensms-audit.csv") }) { Text("Ekspor log teredaksi") }
        if (exportMessage != null) Text(exportMessage!!, style = MaterialTheme.typography.bodySmall)
        Text("Ekspor hanya berisi waktu, kategori aktor, aksi, dan kode hasil. ID entitas, nomor tujuan, metadata bebas, isi SMS, dan token tidak disertakan.")
        Spacer(Modifier.height(8.dp))
        Text("Gateway otomatis berhenti saat aplikasi masuk latar belakang. Pesan DISPATCHING yang hasil radionya tidak pasti menjadi UNKNOWN dan tidak dikirim ulang.")
    }
}
