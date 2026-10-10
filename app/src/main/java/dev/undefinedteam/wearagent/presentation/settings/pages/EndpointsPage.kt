package dev.undefinedteam.wearagent.presentation.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.agent.ChatProvider
import dev.undefinedteam.wearagent.agent.ChatRequest
import dev.undefinedteam.wearagent.presentation.settings.SettingRow
import dev.undefinedteam.wearagent.presentation.settings.SettingsPage
import dev.undefinedteam.wearagent.presentation.settings.SettingsPageScope
import dev.undefinedteam.wearagent.presentation.settings.settingsPage
import dev.undefinedteam.wearagent.agent.EndpointKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Mutable editor state shared between the endpoints page and its child pages. */
internal class EndpointEditorState {
    var editingId by mutableStateOf("")
    var models by mutableStateOf<List<String>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf(false)
    var errorText by mutableStateOf("")
}

/**
 * Endpoints category. List/select endpoints; the detail and model-list child
 * pages share one [EndpointEditorState] via [dev.undefinedteam.wearagent.presentation.settings.SettingsPageScope.shared].
 */
val EndpointsPage: SettingsPage = settingsPage("endpoints", titleRes = R.string.endpoints) { list ->
    val editor = shared<EndpointEditorState>("endpoints") { EndpointEditorState() }

    list.row(R.string.new_endpoint) {
        launch {
            editor.editingId = store.addEndpoint("")
            navigate(EndpointDetailPage)
        }
    }
    current.endpoints.forEach { profile ->
        list.toggle(profile.name, profile.id == current.endpointId) {
            launch { store.selectEndpoint(profile.id) }
        }
        if (profile.id == current.endpointId) {
            list.row(R.string.edit_endpoint) {
                editor.editingId = profile.id
                navigate(EndpointDetailPage)
            }
        }
    }
}

/** Endpoint detail: name, API kind, URL, model, API key, delete. */
val EndpointDetailPage: SettingsPage = settingsPage(
    "endpoint",
    parent = EndpointsPage,
    titleRes = R.string.endpoints,
) { list ->
    val editor = shared<EndpointEditorState>("endpoints") { EndpointEditorState() }
    val profile = current.endpoints.firstOrNull { it.id == editor.editingId } ?: return@settingsPage

    list.subHeader(R.string.endpoint_name)
    list.field(R.string.endpoint_name, profile.name) { value ->
        launch { store.updateEndpoint(profile.id) { it.copy(name = value) } }
    }
    list.subHeader(R.string.api_type)
    EndpointKind.entries.forEach { kind ->
        list.toggle(apiLabelRes(kind), profile.kind == kind) {
            launch { store.updateEndpoint(profile.id) { it.copy(kind = kind) } }
        }
    }
    list.subHeader(R.string.endpoint)
    list.field(R.string.endpoint, profile.endpoint) { value ->
        launch { store.updateEndpoint(profile.id) { it.copy(endpoint = value) } }
    }
    list.subHeader(R.string.model)
    list.row(R.string.fetch_models) {
        editor.models = emptyList()
        editor.error = false
        navigate(ModelsPage)
        fetchModels(editor)
    }
    if (profile.model.isNotBlank()) {
        list.toggle(profile.model, true) { navigate(ModelsPage) }
    }
    list.subHeader(R.string.api_key)
    list.field(R.string.api_key, profile.apiKey) { value ->
        launch { store.updateEndpoint(profile.id) { it.copy(apiKey = value) } }
    }
    list.row(R.string.delete_endpoint) {
        launch { store.deleteEndpoint(profile.id) }
    }
}

/** Model picker for the endpoint being edited; fetches the provider list. */
val ModelsPage: SettingsPage = settingsPage(
    "models",
    parent = EndpointsPage,
    titleRes = R.string.model,
) { list ->
    val editor = shared<EndpointEditorState>("endpoints") { EndpointEditorState() }
    val profile = current.endpoints.firstOrNull { it.id == editor.editingId } ?: return@settingsPage

    when {
        editor.loading -> list.custom(horizontalAlignment = Alignment.Start) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.loading_models))
            }
        }

        editor.error -> list.custom {
            Text(
                editor.errorText,
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
            )
            SettingRow(
                this,
                rememberTransformationSpec(),
                stringResource(R.string.fetch_models_retry),
            ) { fetchModels(editor) }
        }

        else -> {
            editor.models.forEach { model ->
                list.toggle(model, profile.model == model) {
                    launch { store.updateEndpoint(profile.id) { it.copy(model = model) } }
                }
            }
            if (editor.models.isEmpty()) {
                list.custom {
                    Text(
                        stringResource(R.string.fetch_models_hint),
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun SettingsPageScope.fetchModels(editor: EndpointEditorState) {
    launch {
        editor.loading = true
        editor.error = false
        try {
            val target = current.endpoints.firstOrNull { it.id == editor.editingId }
                ?: error("no endpoint")
            val models = withContext(Dispatchers.IO) {
                ChatProvider(target.kind).models(
                    ChatRequest(target.endpoint, target.model, target.apiKey)
                )
            }
            editor.models = models
            if (models.isEmpty()) {
                editor.errorText = "empty list"
                editor.error = true
            }
        } catch (e: Exception) {
            editor.errorText = e.message ?: e.javaClass.simpleName
            editor.models = emptyList()
            editor.error = true
        } finally {
            editor.loading = false
        }
    }
}

private fun apiLabelRes(kind: EndpointKind): Int = when (kind) {
    EndpointKind.COMPLETIONS -> R.string.api_completions
    EndpointKind.RESPONSES -> R.string.api_responses
    EndpointKind.ANTHROPIC -> R.string.api_anthropic
}
