package dev.undefinedteam.wearagent.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.agent.ChatClient
import dev.undefinedteam.wearagent.presentation.theme.WearAgentTheme
import dev.undefinedteam.wearagent.session.AgentSettings
import dev.undefinedteam.wearagent.session.ApiKind
import dev.undefinedteam.wearagent.session.EndpointProfile
import dev.undefinedteam.wearagent.session.InputMode
import dev.undefinedteam.wearagent.session.SessionLog
import dev.undefinedteam.wearagent.session.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class Page { HOME, SESSIONS, ENDPOINTS, ENDPOINT, MODELS, INPUT, DISPLAY, LANGUAGE }

class SettingsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        val lang = kotlinx.coroutines.runBlocking {
            runCatching { SettingsStore(newBase).settings.first().language }.getOrDefault("")
        }
        super.attachBaseContext(LocaleHelper.wrap(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WearAgentTheme {
                AppScaffold {
                    SettingsScreen(
                        SettingsStore(this@SettingsActivity),
                        SessionLog(this@SettingsActivity)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(store: SettingsStore, sessions: SessionLog) {
    val current by store.settings.collectAsStateWithLifecycle(AgentSettings())
    var listed by remember { mutableStateOf(sessions.sessions()) }
    var page by remember { mutableStateOf(Page.HOME) }
    var direction by remember { mutableStateOf(1) }
    var editing by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf(false) }
    var modelsErrorText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val client = remember { ChatClient() }
    val navigate: (Page) -> Unit = { target ->
        direction = target.ordinal - page.ordinal
        page = target
    }
    val back: () -> Unit = {
        direction = -1
        page = when (page) {
            Page.ENDPOINT -> Page.ENDPOINTS
            Page.MODELS -> Page.ENDPOINT
            Page.DISPLAY, Page.LANGUAGE, Page.INPUT -> Page.HOME
            else -> Page.HOME
        }
    }
    if (page != Page.HOME) BackHandler(onBack = back)
    val settingsFor: (EndpointProfile) -> AgentSettings = { profile ->
        current.copy(endpoints = listOf(profile), endpointId = profile.id)
    }
    val fetchModels: () -> Unit = {
        scope.launch(Dispatchers.IO) {
            loadingModels = true
            modelsError = false
            try {
                val target =
                    current.endpoints.firstOrNull { it.id == editing } ?: error("no endpoint")
                models = client.models(settingsFor(target))
                if (models.isEmpty()) {
                    modelsErrorText = "empty list"
                    modelsError = true
                }
            } catch (e: Exception) {
                modelsErrorText = e.message ?: e.javaClass.simpleName
                models = emptyList()
                modelsError = true
            } finally {
                loadingModels = false
            }
        }
    }
    ScreenScaffold(scrollState = state) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward =
                    slideInVertically(spring(stiffness = 380f)) { it / 3 } + fadeIn(tween(200)) togetherWith
                            slideOutVertically(tween(150)) { -it / 3 } + fadeOut(tween(150))
                val backward =
                    slideInVertically(tween(150)) { -it / 3 } + fadeIn(tween(150)) togetherWith
                            slideOutVertically(tween(150)) { it / 3 } + fadeOut(tween(150))
                if (direction >= 0) forward else backward
            },
            label = "settings-page",
        ) { target ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                TransformingLazyColumn(
                    Modifier.width(200.dp),
                    state = state,
                    contentPadding = PaddingValues(top = 24.dp, bottom = 56.dp)
                ) {
                    item { Scaled(this, spec) { ListHeader { Text(title(target)) } } }
                    when (target) {
                        Page.HOME -> {
                            item {
                                SettingRow(
                                    this,
                                    spec,
                                    stringResource(R.string.sessions)
                                ) { navigate(Page.SESSIONS) }
                            }
                            item {
                                SettingRow(
                                    this,
                                    spec,
                                    stringResource(R.string.endpoints)
                                ) { navigate(Page.ENDPOINTS) }
                            }
                            item {
                                SettingRow(
                                    this,
                                    spec,
                                    stringResource(R.string.input_mode)
                                ) { navigate(Page.INPUT) }
                            }
                            item {
                                SettingRow(
                                    this,
                                    spec,
                                    stringResource(R.string.display)
                                ) { navigate(Page.DISPLAY) }
                            }
                            item {
                                SettingRow(
                                    this,
                                    spec,
                                    stringResource(R.string.language)
                                ) { navigate(Page.LANGUAGE) }
                            }
                        }

                        Page.SESSIONS -> {
                            item {
                                SettingRow(this, spec, stringResource(R.string.new_session)) {
                                    scope.launch {
                                        store.setSession(sessions.create())
                                        listed = sessions.sessions()
                                    }
                                }
                            }
                            listed.forEach { session ->
                                item {
                                    SettingToggle(
                                        this,
                                        spec,
                                        session.title,
                                        session.id == current.sessionId
                                    ) {
                                        scope.launch { store.setSession(session.id) }
                                    }
                                }
                                if (session.id == current.sessionId) {
                                    item {
                                        SettingRow(
                                            this,
                                            spec,
                                            stringResource(R.string.delete_session)
                                        ) {
                                            scope.launch {
                                                sessions.deleteSession(session.id)
                                                store.setSession(
                                                    sessions.sessions().firstOrNull()?.id.orEmpty()
                                                )
                                                listed = sessions.sessions()
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Page.ENDPOINTS -> {
                            item {
                                SettingRow(this, spec, stringResource(R.string.new_endpoint)) {
                                    scope.launch {
                                        editing = store.addEndpoint("")
                                        navigate(Page.ENDPOINT)
                                    }
                                }
                            }
                            current.endpoints.forEach { profile ->
                                item {
                                    SettingToggle(
                                        this,
                                        spec,
                                        profile.name,
                                        profile.id == current.endpointId
                                    ) {
                                        scope.launch { store.selectEndpoint(profile.id) }
                                    }
                                }
                                if (profile.id == current.endpointId) {
                                    item {
                                        SettingRow(
                                            this,
                                            spec,
                                            stringResource(R.string.edit_endpoint)
                                        ) {
                                            editing = profile.id
                                            navigate(Page.ENDPOINT)
                                        }
                                    }
                                }
                            }
                        }

                        Page.ENDPOINT -> {
                            val profile = current.endpoints.firstOrNull { it.id == editing }
                            if (profile != null) {
                                item {
                                    Scaled(
                                        this,
                                        spec
                                    ) { ListSubHeader { Text(stringResource(R.string.endpoint_name)) } }
                                }
                                item {
                                    Scaled(this, spec) {
                                        SettingField(
                                            stringResource(R.string.endpoint_name),
                                            profile.name
                                        ) { value ->
                                            scope.launch {
                                                store.updateEndpoint(profile.id) {
                                                    it.copy(
                                                        name = value
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                item {
                                    Scaled(
                                        this,
                                        spec
                                    ) { ListSubHeader { Text(stringResource(R.string.api_type)) } }
                                }
                                ApiKind.entries.forEach { kind ->
                                    item {
                                        SettingToggle(
                                            this,
                                            spec,
                                            apiLabel(kind),
                                            profile.kind == kind
                                        ) {
                                            scope.launch {
                                                store.updateEndpoint(profile.id) {
                                                    it.copy(
                                                        kind = kind
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                item {
                                    Scaled(
                                        this,
                                        spec
                                    ) { ListSubHeader { Text(stringResource(R.string.endpoint)) } }
                                }
                                item {
                                    Scaled(this, spec) {
                                        SettingField(
                                            stringResource(R.string.endpoint),
                                            profile.endpoint
                                        ) { value ->
                                            scope.launch {
                                                store.updateEndpoint(profile.id) {
                                                    it.copy(
                                                        endpoint = value
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                item {
                                    Scaled(
                                        this,
                                        spec
                                    ) { ListSubHeader { Text(stringResource(R.string.model)) } }
                                }
                                item {
                                    SettingRow(this, spec, stringResource(R.string.fetch_models)) {
                                        models = emptyList()
                                        modelsError = false
                                        navigate(Page.MODELS)
                                        fetchModels()
                                    }
                                }
                                if (profile.model.isNotBlank()) item {
                                    SettingToggle(
                                        this,
                                        spec,
                                        profile.model,
                                        true
                                    ) { navigate(Page.MODELS) }
                                }
                                item {
                                    Scaled(this, spec) {
                                        SettingField(
                                            stringResource(R.string.model),
                                            profile.model
                                        ) { value ->
                                            scope.launch {
                                                store.updateEndpoint(profile.id) {
                                                    it.copy(
                                                        model = value
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                item {
                                    Scaled(
                                        this,
                                        spec
                                    ) { ListSubHeader { Text(stringResource(R.string.api_key)) } }
                                }
                                item {
                                    Scaled(this, spec) {
                                        SettingField(
                                            stringResource(R.string.api_key),
                                            profile.apiKey
                                        ) { value ->
                                            scope.launch {
                                                store.updateEndpoint(profile.id) {
                                                    it.copy(
                                                        apiKey = value
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                item {
                                    SettingRow(
                                        this,
                                        spec,
                                        stringResource(R.string.delete_endpoint)
                                    ) {
                                        scope.launch { store.deleteEndpoint(profile.id) }
                                    }
                                }
                            }
                        }

                        Page.INPUT -> {
                            item {
                                SettingToggle(
                                    this,
                                    spec,
                                    stringResource(R.string.mode_keyboard),
                                    current.inputMode == InputMode.KEYBOARD
                                ) {
                                    scope.launch { store.setInputMode(InputMode.KEYBOARD) }
                                }
                            }
                            item {
                                SettingToggle(
                                    this,
                                    spec,
                                    stringResource(R.string.mode_voice),
                                    current.inputMode == InputMode.VOICE
                                ) {
                                    scope.launch { store.setInputMode(InputMode.VOICE) }
                                }
                            }
                            item {
                                Scaled(
                                    this,
                                    spec
                                ) { ListSubHeader { Text(stringResource(R.string.ime_options)) } }
                            }
                            item {
                                SettingSwitch(
                                    this,
                                    spec,
                                    stringResource(R.string.ime_send_now),
                                    current.imeSends
                                ) { value ->
                                    scope.launch { store.setImeSends(value) }
                                }
                            }
                        }

                        Page.DISPLAY -> {
                            item {
                                Scaled(
                                    this,
                                    spec
                                ) { ListSubHeader { Text(stringResource(R.string.display)) } }
                            }
                            item {
                                SettingSwitch(
                                    this,
                                    spec,
                                    stringResource(R.string.curved_list),
                                    current.curvedList
                                ) { value ->
                                    scope.launch { store.setCurvedList(value) }
                                }
                            }
                            item {
                                SettingSwitch(
                                    this,
                                    spec,
                                    stringResource(R.string.round_fit),
                                    current.roundFit
                                ) { value ->
                                    scope.launch { store.setRoundFit(value) }
                                }
                            }
                        }

                        Page.LANGUAGE -> {
                            val activity = context
                            item {
                                SettingToggle(
                                    this,
                                    spec,
                                    stringResource(R.string.lang_system),
                                    current.language.isBlank()
                                ) {
                                    scope.launch {
                                        store.setLanguage("")
                                        (activity as? android.app.Activity)?.recreate()
                                    }
                                }
                            }
                            item {
                                SettingToggle(
                                    this,
                                    spec,
                                    stringResource(R.string.lang_zh),
                                    current.language == "zh"
                                ) {
                                    scope.launch {
                                        store.setLanguage("zh")
                                        (activity as? android.app.Activity)?.recreate()
                                    }
                                }
                            }
                            item {
                                SettingToggle(
                                    this,
                                    spec,
                                    stringResource(R.string.lang_en),
                                    current.language == "en"
                                ) {
                                    scope.launch {
                                        store.setLanguage("en")
                                        (activity as? android.app.Activity)?.recreate()
                                    }
                                }
                            }
                        }

                        Page.MODELS -> {
                            val profile = current.endpoints.firstOrNull { it.id == editing }
                            if (profile != null) {
                                item {
                                    val itemScope = this
                                    when {
                                        loadingModels -> Scaled(this, spec) {
                                            Row(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                CircularProgressIndicator(
                                                    Modifier.size(16.dp),
                                                    strokeWidth = 2.dp
                                                )
                                                Text(stringResource(R.string.loading_models))
                                            }
                                        }

                                        modelsError -> Column(
                                            Modifier.transformedHeight(
                                                itemScope,
                                                spec
                                            )
                                        ) {
                                            Text(
                                                modelsErrorText,
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 14.dp, vertical = 2.dp),
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            SettingRow(
                                                itemScope,
                                                spec,
                                                stringResource(R.string.fetch_models_retry)
                                            ) {
                                                fetchModels()
                                            }
                                        }
                                    }
                                }
                                models.forEach { model ->
                                    item {
                                        SettingToggle(this, spec, model, profile.model == model) {
                                            scope.launch {
                                                store.updateEndpoint(profile.id) {
                                                    it.copy(
                                                        model = model
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                if (models.isEmpty() && !loadingModels && !modelsError) item {
                                    Scaled(this, spec) {
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
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    scope: TransformingLazyColumnItemScope,
    spec: TransformationSpec,
    label: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = with(scope) { Modifier
            .transformedHeight(this, spec)
            .fillMaxWidth() },
        transformation = with(scope) { SurfaceTransformation(spec) },
    ) { Text(label) }
}

@Composable
private fun SettingToggle(
    scope: TransformingLazyColumnItemScope,
    spec: TransformationSpec,
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    RadioButton(
        selected = selected,
        onSelect = onSelect,
        modifier = with(scope) { Modifier
            .transformedHeight(this, spec)
            .fillMaxWidth() },
        transformation = with(scope) { SurfaceTransformation(spec) },
        label = { Text(label) },
    )
}

@Composable
private fun SettingSwitch(
    scope: TransformingLazyColumnItemScope,
    spec: TransformationSpec,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = with(scope) { Modifier
            .transformedHeight(this, spec)
            .fillMaxWidth() },
        transformation = with(scope) { SurfaceTransformation(spec) },
        label = { Text(label) },
    )
}

@Composable
private fun Scaled(
    scope: TransformingLazyColumnItemScope,
    spec: TransformationSpec,
    content: @Composable () -> Unit
) {
    Column(
        with(scope) { Modifier.transformedHeight(this, spec) },
    ) { content() }
}

@Composable
private fun SettingField(label: String, value: String, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    BasicTextField(
        text,
        {
            text = it
            onSave(it)
        },
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        singleLine = true,
        decorationBox = { inner ->
            if (text.isEmpty()) Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            inner()
        },
    )
}

@Composable
private fun title(page: Page): String = stringResource(
    when (page) {
        Page.HOME -> R.string.settings
        Page.SESSIONS -> R.string.sessions
        Page.ENDPOINTS, Page.ENDPOINT -> R.string.endpoints
        Page.MODELS -> R.string.model
        Page.INPUT -> R.string.input_mode
        Page.DISPLAY -> R.string.display
        Page.LANGUAGE -> R.string.language
    },
)

@Composable
private fun apiLabel(kind: ApiKind): String = stringResource(
    when (kind) {
        ApiKind.COMPLETIONS -> R.string.api_completions
        ApiKind.RESPONSES -> R.string.api_responses
        ApiKind.ANTHROPIC -> R.string.api_anthropic
    },
)
