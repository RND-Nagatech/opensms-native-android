package org.daslearning.opensms.next

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.daslearning.opensms.next.data.AppDatabase
import org.daslearning.opensms.next.data.MessageRepository
import org.daslearning.opensms.next.data.RedactedAuditCsv
import org.daslearning.opensms.next.domain.GatewaySnapshot
import org.daslearning.opensms.next.domain.GatewayStatusSource
import org.daslearning.opensms.next.domain.SubscriptionInfo
import org.daslearning.opensms.next.domain.SubscriptionProvider

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    statusSource: GatewayStatusSource,
    private val messages: MessageRepository,
    private val subscriptions: SubscriptionProvider,
    private val database: AppDatabase,
) : ViewModel() {
    val gateway = statusSource.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GatewaySnapshot())
    private val mutableQueueCount = MutableStateFlow(0)
    val queueCount = mutableQueueCount.asStateFlow()
    private val mutableSubscriptions = MutableStateFlow<List<SubscriptionInfo>>(emptyList())
    val activeSubscriptions = mutableSubscriptions.asStateFlow()
    private val mutableSimError = MutableStateFlow<String?>(null)
    val simError = mutableSimError.asStateFlow()
    private val mutableExportMessage = MutableStateFlow<String?>(null)
    val exportMessage = mutableExportMessage.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            mutableQueueCount.value = runCatching { messages.queueSize() }.getOrDefault(0)
            try {
                mutableSubscriptions.value = subscriptions.activeSmsSubscriptions()
                mutableSimError.value = null
            } catch (_: SecurityException) {
                mutableSubscriptions.value = emptyList()
                mutableSimError.value = "Izin SIM dibutuhkan saat gateway mulai."
            } catch (_: Exception) {
                mutableSubscriptions.value = emptyList()
                mutableSimError.value = "Status SIM tidak tersedia."
            }
        }
    }

    fun exportRedactedAudit(uri: Uri, resolver: ContentResolver) {
        viewModelScope.launch {
            val exported = withContext(Dispatchers.IO) {
                runCatching {
                    val csv = RedactedAuditCsv.encode(database.gatewayDao().recentAudit(limit = 200))
                    resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(csv) }
                        ?: error("Could not open export destination")
                }.isSuccess
            }
            mutableExportMessage.value = if (exported) "Log teredaksi berhasil diekspor." else "Ekspor log gagal."
        }
    }
}
