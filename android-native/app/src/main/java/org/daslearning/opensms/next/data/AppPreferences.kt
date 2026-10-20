package org.daslearning.opensms.next.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.SupervisorJob

private val Context.preferencesDataStore by preferencesDataStore(name = "app_preferences")

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val onboardingComplete: Flow<Boolean> = context.preferencesDataStore.data
        .map { preferences -> preferences[ONBOARDING_COMPLETE] ?: false }

    val trustedLanCidr = context.preferencesDataStore.data
        .map { preferences -> preferences[TRUSTED_LAN_CIDR] }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly, null)

    val gatewaySession = context.preferencesDataStore.data
        .map { preferences ->
            GatewaySessionPreference(
                enabled = preferences[GATEWAY_SESSION_ENABLED] ?: false,
                mode = preferences[GATEWAY_SESSION_MODE] ?: "",
            )
        }

    suspend fun completeOnboarding() {
        context.preferencesDataStore.edit { preferences ->
            preferences[ONBOARDING_COMPLETE] = true
        }
    }

    suspend fun setTrustedLanCidr(cidr: String?) {
        context.preferencesDataStore.edit { preferences ->
            if (cidr == null) preferences.remove(TRUSTED_LAN_CIDR)
            else preferences[TRUSTED_LAN_CIDR] = cidr
        }
    }

    suspend fun setGatewaySession(enabled: Boolean, mode: String? = null) {
        context.preferencesDataStore.edit { preferences ->
            preferences[GATEWAY_SESSION_ENABLED] = enabled
            if (enabled && mode != null) preferences[GATEWAY_SESSION_MODE] = mode
            if (!enabled) preferences.remove(GATEWAY_SESSION_MODE)
        }
    }

    private companion object {
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val TRUSTED_LAN_CIDR = stringPreferencesKey("trusted_lan_cidr")
        val GATEWAY_SESSION_ENABLED = booleanPreferencesKey("gateway_session_enabled")
        val GATEWAY_SESSION_MODE = stringPreferencesKey("gateway_session_mode")
    }
}

data class GatewaySessionPreference(val enabled: Boolean, val mode: String)
