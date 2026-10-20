package org.daslearning.opensms.next

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.daslearning.opensms.next.data.ApiClientSummary
import org.daslearning.opensms.next.data.ClientRepository
import org.daslearning.opensms.next.data.AppPreferences
import org.daslearning.opensms.next.domain.IssuedApiKey

@HiltViewModel
class KeyManagementViewModel @Inject constructor(
    private val repository: ClientRepository,
    private val preferences: AppPreferences,
) : ViewModel() {
    val clients = repository.clients.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val trustedLanCidr = preferences.trustedLanCidr
    private val mutableIssuedKey = MutableStateFlow<IssuedApiKey?>(null)
    val issuedKey = mutableIssuedKey.asStateFlow()
    private val mutableErrorCode = MutableStateFlow<String?>(null)
    val errorCode = mutableErrorCode.asStateFlow()

    fun createClient(name: String, scopes: Set<String>) {
        viewModelScope.launch {
            try {
                mutableIssuedKey.value = repository.createClient(name, scopes, System.currentTimeMillis())
                mutableErrorCode.value = null
            } catch (exception: IllegalArgumentException) {
                mutableErrorCode.value = exception.message ?: "KEY_CREATE_FAILED"
            } catch (_: Exception) {
                mutableErrorCode.value = "KEY_CREATE_FAILED"
            }
        }
    }

    fun revoke(client: ApiClientSummary) {
        viewModelScope.launch { repository.revokeClient(client.id, System.currentTimeMillis()) }
    }

    fun rotate(client: ApiClientSummary) {
        viewModelScope.launch {
            mutableIssuedKey.value = repository.rotateClientKey(client.id, System.currentTimeMillis())
        }
    }

    fun dismissIssuedKey() {
        mutableIssuedKey.value = null
    }

    fun saveTrustedLanCidr(value: String): Boolean {
        val cidrs = value.split(',').map(String::trim)
        if (cidrs.isEmpty() || cidrs.any { !isPrivateIpv4Cidr(it) }) return false
        viewModelScope.launch { preferences.setTrustedLanCidr(cidrs.joinToString(",")) }
        return true
    }

    fun clearTrustedLanCidr() {
        viewModelScope.launch { preferences.setTrustedLanCidr(null) }
    }

    private companion object {
        val PRIVATE_CIDR = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}/([0-9]|[12][0-9]|30)$")

        fun isPrivateIpv4Cidr(cidr: String): Boolean {
            if (!PRIVATE_CIDR.matches(cidr)) return false
            val octets = cidr.substringBefore('/').split('.').mapNotNull(String::toIntOrNull)
            if (octets.size != 4 || octets.any { it !in 0..255 }) return false
            val prefix = cidr.substringAfter('/').toIntOrNull() ?: return false
            if (prefix !in 8..30) return false
            return octets[0] == 10 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168)
        }
    }
}
