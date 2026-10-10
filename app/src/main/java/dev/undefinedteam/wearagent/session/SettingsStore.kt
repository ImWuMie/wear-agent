package dev.undefinedteam.wearagent.session

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.undefinedteam.wearagent.agent.EndpointKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

data class EndpointProfile(
    val id: String,
    val name: String,
    val kind: EndpointKind = EndpointKind.COMPLETIONS,
    val endpoint: String = "",
    val model: String = "",
    val apiKey: String = "",
)

data class AgentSettings(
    val inputMode: InputMode = InputMode.KEYBOARD,
    val imeSends: Boolean = true,
    val curvedList: Boolean = false,
    val roundFit: Boolean = true,
    val language: String = "",
    val sessionId: String = "",
    val endpointId: String = "",
    val endpoints: List<EndpointProfile> = emptyList(),
    val webSearchEnabled: Boolean = false,
) {
    val active: EndpointProfile?
        get() = endpoints.firstOrNull { it.id == endpointId } ?: endpoints.firstOrNull()
    val endpointKind: EndpointKind get() = active?.kind ?: EndpointKind.COMPLETIONS
    val endpoint: String get() = active?.endpoint.orEmpty()
    val model: String get() = active?.model.orEmpty()
    val apiKey: String get() = active?.apiKey.orEmpty()
}

class SettingsStore(private val context: Context) {
    private val inputModeKey = stringPreferencesKey("input_mode")
    private val imeSendKey = booleanPreferencesKey("ime_sends")
    private val curvedListKey = booleanPreferencesKey("curved_list")
    private val roundFitKey = booleanPreferencesKey("round_fit")
    private val webSearchEnabledKey = booleanPreferencesKey("web_search_enabled")
    private val languageKey = stringPreferencesKey("language")
    private val sessionKey = stringPreferencesKey("session_id")
    private val endpointIdKey = stringPreferencesKey("endpoint_id")
    private val endpointsKey = stringPreferencesKey("endpoints")
    private val endpointKindKey = stringPreferencesKey("api_kind")
    private val endpointKey = stringPreferencesKey("endpoint")
    private val modelKey = stringPreferencesKey("model")
    private val apiKeyKey = stringPreferencesKey("api_key")

    val settings: Flow<AgentSettings> = context.settingsDataStore.data.map { prefs ->
        val stored = decode(prefs[endpointsKey].orEmpty())
        val endpoints = stored.ifEmpty {
            val legacy = prefs[endpointKey].orEmpty()
            if (legacy.isBlank() && prefs[modelKey].isNullOrBlank()) {
                emptyList()
            } else {
                listOf(
                    EndpointProfile(
                        "legacy",
                        legacy,
                        EndpointKind.from(prefs[endpointKindKey]),
                        legacy,
                        prefs[modelKey].orEmpty(),
                        prefs[apiKeyKey].orEmpty()
                    )
                )
            }
        }
        AgentSettings(
            inputMode = InputMode.fromStored(prefs[inputModeKey]),
            imeSends = prefs[imeSendKey] ?: true,
            curvedList = prefs[curvedListKey] ?: false,
            roundFit = prefs[roundFitKey] ?: true,
            language = prefs[languageKey].orEmpty(),
            sessionId = prefs[sessionKey].orEmpty(),
            endpointId = prefs[endpointIdKey].orEmpty()
                .ifBlank { endpoints.firstOrNull()?.id.orEmpty() },
            endpoints = endpoints,
            webSearchEnabled = prefs[webSearchEnabledKey] ?: false,
        )
    }

    suspend fun setInputMode(mode: InputMode) = edit { it[inputModeKey] = mode.name }

    suspend fun setImeSends(value: Boolean) = edit { it[imeSendKey] = value }

    suspend fun setCurvedList(value: Boolean) = edit { it[curvedListKey] = value }

    suspend fun setRoundFit(value: Boolean) = edit { it[roundFitKey] = value }

    suspend fun setWebSearchEnabled(value: Boolean) = edit { it[webSearchEnabledKey] = value }

    suspend fun setLanguage(value: String) = edit { it[languageKey] = value }

    suspend fun setSession(id: String) = edit { it[sessionKey] = id }

    suspend fun selectEndpoint(id: String) = edit { it[endpointIdKey] = id }

    suspend fun addEndpoint(name: String): String {
        val id = System.currentTimeMillis().toString()
        update { it + EndpointProfile(id, name.ifBlank { id }) }
        selectEndpoint(id)
        return id
    }

    suspend fun deleteEndpoint(id: String) {
        val current = settings.first()
        update { endpoints -> endpoints.filterNot { it.id == id } }
        if (current.endpointId == id) selectEndpoint(current.endpoints.firstOrNull { it.id != id }?.id.orEmpty())
    }

    suspend fun updateEndpoint(id: String, change: (EndpointProfile) -> EndpointProfile) {
        update { endpoints -> endpoints.map { if (it.id == id) change(it) else it } }
    }

    private suspend fun update(change: (List<EndpointProfile>) -> List<EndpointProfile>) {
        val next = change(settings.first().endpoints)
        edit { it[endpointsKey] = encode(next) }
    }

    private suspend fun edit(change: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit { change(it) }
    }

    private fun encode(endpoints: List<EndpointProfile>): String = JSONArray().apply {
        endpoints.forEach { profile ->
            put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("kind", profile.kind.name)
                    .put("endpoint", profile.endpoint)
                    .put("model", profile.model)
                    .put("apiKey", profile.apiKey),
            )
        }
    }.toString()

    private fun decode(value: String): List<EndpointProfile> {
        if (value.isBlank()) return emptyList()
        val array = try {
            JSONArray(value)
        } catch (_: Exception) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    EndpointProfile(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        kind = EndpointKind.from(item.optString("kind")),
                        endpoint = item.optString("endpoint"),
                        model = item.optString("model"),
                        apiKey = item.optString("apiKey"),
                    ),
                )
            }
        }
    }
}
