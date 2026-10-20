package org.daslearning.opensms.next

import android.content.ClipData
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
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
fun KeyManagementScreen(viewModel: KeyManagementViewModel = hiltViewModel()) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val clients by viewModel.clients.collectAsState()
    val savedCidr by viewModel.trustedLanCidr.collectAsState()
    val issued by viewModel.issuedKey.collectAsState()
    val error by viewModel.errorCode.collectAsState()
    var clientName by remember { mutableStateOf("") }
    var writeScope by remember { mutableStateOf(true) }
    var readScope by remember { mutableStateOf(true) }
    var systemScope by remember { mutableStateOf(true) }
    var trustedCidr by remember(savedCidr) { mutableStateOf(savedCidr.orEmpty()) }
    var networkError by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Kunci API", style = MaterialTheme.typography.headlineMedium)
        Text("Kunci dibuat acak dan ditampilkan satu kali. Hanya verifier-nya yang disimpan.")
        OutlinedTextField(
            value = clientName,
            onValueChange = { clientName = it.take(60) },
            label = { Text("Nama client") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        ScopeCheckbox("Kirim pesan", writeScope) { writeScope = it }
        ScopeCheckbox("Baca pesan milik client", readScope) { readScope = it }
        ScopeCheckbox("Baca status dan SIM", systemScope) { systemScope = it }
        Button(
            onClick = {
                val scopes = buildSet {
                    if (writeScope) add("messages:write")
                    if (readScope) add("messages:read")
                    if (systemScope) add("system:read")
                }
                viewModel.createClient(clientName, scopes)
            },
            enabled = clientName.isNotBlank() && (writeScope || readScope || systemScope) && issued == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Buat kunci baru") }
        if (error != null) {
            Text("Tidak dapat membuat kunci ($error).", color = MaterialTheme.colorScheme.error)
        }
        issued?.let { key ->
            var copied by remember(key.token) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Salin sekarang — kunci tidak akan ditampilkan lagi.", style = MaterialTheme.typography.titleMedium)
                    Text(key.token, style = MaterialTheme.typography.bodySmall)
                    Text("Client: ${key.clientName} · scope: ${key.scopes.sorted().joinToString()}")
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("OpenSMS API key", key.token)))
                                copied = true
                            }
                        },
                    ) { Text(if (copied) "Kunci tersalin" else "Salin kunci") }
                    Button(onClick = { viewModel.dismissIssuedKey() }) { Text("Selesai") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Client API", style = MaterialTheme.typography.titleLarge)
        if (clients.isEmpty()) Text("Belum ada client.")
        clients.forEach { client ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(client.name, style = MaterialTheme.typography.titleMedium)
                    Text("Scope: ${client.scopes.sorted().joinToString()}")
                    Text(if (client.revoked) "Dicabut" else "Aktif")
                    if (!client.revoked) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.rotate(client) }, enabled = issued == null) { Text("Rotasi kunci") }
                            Button(onClick = { viewModel.revoke(client) }) { Text("Cabut kunci") }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Akses LAN tepercaya", style = MaterialTheme.typography.titleLarge)
        Text("Masukkan satu atau beberapa subnet IPv4 privat, pisahkan dengan koma (contoh 192.168.16.0/20, 192.168.32.0/20). Gateway hanya bind ke alamat Wi-Fi aktif dan hanya menerima koneksi dari subnet yang diizinkan.")
        OutlinedTextField(
            value = trustedCidr,
            onValueChange = { trustedCidr = it; networkError = false },
            label = { Text("Subnet tepercaya (pisahkan dengan koma)") },
            placeholder = { Text("192.168.16.0/20, 192.168.32.0/20") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(onClick = { networkError = !viewModel.saveTrustedLanCidr(trustedCidr) }) { Text("Simpan allowlist") }
        OutlinedButton(onClick = { trustedCidr = ""; viewModel.clearTrustedLanCidr() }) { Text("Hapus allowlist") }
        if (networkError) Text("Setiap subnet harus berupa CIDR IPv4 privat yang valid (/8 sampai /30), dipisahkan dengan koma.", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ScopeCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, modifier = Modifier.padding(top = 12.dp))
    }
}
