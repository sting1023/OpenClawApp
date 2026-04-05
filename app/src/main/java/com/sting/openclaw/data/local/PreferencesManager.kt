package com.sting.openclaw.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "openclaw_prefs")

data class GatewayConfig(
    val id: String,
    val name: String,
    val url: String,
    val port: Int,
    val token: String
)

data class AppPreferences(
    val currentGatewayId: String?,
    val gateways: List<GatewayConfig>,
    val isDarkTheme: String?,
    val selectedModel: String?
)

@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore
    
    companion object {
        val CURRENT_GATEWAY_ID = stringPreferencesKey("current_gateway_id")
        val GATEWAYS = stringPreferencesKey("gateways")
        val IS_DARK_THEME = stringPreferencesKey("is_dark_theme")
        val SELECTED_MODEL = stringPreferencesKey("selected_model")
    }
    
    val preferences: Flow<AppPreferences> = dataStore.data.map { prefs ->
        AppPreferences(
            currentGatewayId = prefs[CURRENT_GATEWAY_ID],
            gateways = parseGateways(prefs[GATEWAYS]),
            isDarkTheme = prefs[IS_DARK_THEME],
            selectedModel = prefs[SELECTED_MODEL]
        )
    }
    
    val currentGateway: Flow<GatewayConfig?> = dataStore.data.map { prefs ->
        val id = prefs[CURRENT_GATEWAY_ID]
        parseGateways(prefs[GATEWAYS]).find { it.id == id }
    }
    
    suspend fun saveGateway(gateway: GatewayConfig) {
        dataStore.edit { prefs ->
            val current = parseGateways(prefs[GATEWAYS]).toMutableList()
            val existing = current.indexOfFirst { it.id == gateway.id }
            if (existing >= 0) {
                current[existing] = gateway
            } else {
                current.add(gateway)
            }
            prefs[GATEWAYS] = serializeGateways(current)
            if (prefs[CURRENT_GATEWAY_ID] == null) {
                prefs[CURRENT_GATEWAY_ID] = gateway.id
            }
        }
    }
    
    suspend fun deleteGateway(gatewayId: String) {
        dataStore.edit { prefs ->
            val current = parseGateways(prefs[GATEWAYS]).toMutableList()
            current.removeAll { it.id == gatewayId }
            prefs[GATEWAYS] = serializeGateways(current)
            if (prefs[CURRENT_GATEWAY_ID] == gatewayId) {
                val nextGateway = current.firstOrNull()
                if (nextGateway != null) {
                    prefs[CURRENT_GATEWAY_ID] = nextGateway.id
                } else {
                    prefs.remove(CURRENT_GATEWAY_ID)
                }
            }
        }
    }
    
    suspend fun setCurrentGateway(gatewayId: String) {
        dataStore.edit { prefs ->
            prefs[CURRENT_GATEWAY_ID] = gatewayId
        }
    }
    
    suspend fun setDarkTheme(mode: String) {
        dataStore.edit { prefs ->
            prefs[IS_DARK_THEME] = mode
        }
    }
    
    suspend fun setSelectedModel(model: String?) {
        dataStore.edit { prefs ->
            if (model != null) {
                prefs[SELECTED_MODEL] = model
            } else {
                prefs.remove(SELECTED_MODEL)
            }
        }
    }
    
    private fun parseGateways(json: String?): List<GatewayConfig> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val list = mutableListOf<GatewayConfig>()
            val regex = """\{"id":"([^"]*)","name":"([^"]*)","url":"([^"]*)","port":(\d+),"token":"([^"]*)"\}""".toRegex()
            regex.findAll(json).forEach { match ->
                val (id, name, url, port, token) = match.destructured
                list.add(GatewayConfig(id, name, url, port.toInt(), token))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun serializeGateways(gateways: List<GatewayConfig>): String {
        return gateways.joinToString(",", "[", "]") { gw ->
            """{"id":"${gw.id}","name":"${gw.name}","url":"${gw.url}","port":${gw.port},"token":"${gw.token}"}"""
        }
    }
}
