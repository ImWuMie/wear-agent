package dev.undefinedteam.wearagent.presentation

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.agent.AgentService
import dev.undefinedteam.wearagent.agent.TurnState
import dev.undefinedteam.wearagent.presentation.theme.WearAgentTheme
import dev.undefinedteam.wearagent.session.AgentSettings
import dev.undefinedteam.wearagent.session.ChatMessage
import dev.undefinedteam.wearagent.session.InputMode
import dev.undefinedteam.wearagent.session.SessionLog
import dev.undefinedteam.wearagent.session.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val turn = MutableStateFlow(TurnState())
    private val sent = MutableStateFlow<List<ChatMessage>>(emptyList())
    private var currentSessionId: String = ""
    private var service: AgentService? = null
    private var speech: SpeechRecognizer? = null
    private var onSpeech: (String) -> Unit = {}

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val bound = (binder as AgentService.LocalBinder).service()
            service = bound
            lifecycleScope.launch { bound.state.collectLatest { turn.value = it } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening()
        }

    override fun attachBaseContext(newBase: Context) {
        val lang = kotlinx.coroutines.runBlocking {
            newBase.dataStoreLanguage()
        }
        super.attachBaseContext(LocaleHelper.wrap(newBase, lang))
    }

    private suspend fun Context.dataStoreLanguage(): String =
        runCatching { SettingsStore(this).settings.first().language }.getOrDefault("")

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        bindService(Intent(this, AgentService::class.java), connection, BIND_AUTO_CREATE)
        // React to session switches (new session, delete, switch in Settings) so the
        // chat always shows the active session instead of stale content.
        lifecycleScope.launch {
            SettingsStore(this@MainActivity).settings.map { it.sessionId }.distinctUntilChanged()
                .collect { id ->
                    if (id != currentSessionId) {
                        currentSessionId = id
                        sent.value =
                            if (id.isBlank()) emptyList() else SessionLog(this@MainActivity).load(id)
                    }
                }
        }
        setContent {
            WearAgentTheme {
                ChatScreen(
                    activity = this,
                    log = SessionLog(this),
                    settings = SettingsStore(this),
                    turn = turn,
                    sent = sent,
                    onSend = ::send,
                    onStop = { service?.stopTurn() },
                    onVoice = ::requestVoice,
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                )
            }
        }
    }

    override fun onDestroy() {
        speech?.destroy()
        unbindService(connection)
        super.onDestroy()
    }

    override fun onPause() {
        currentFocus?.let { view ->
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
        super.onPause()
    }

    private fun send(text: String) {
        if (text.isBlank() || service?.state?.value?.running == true) return
        val log = SessionLog(this)
        val settings = SettingsStore(this)
        lifecycleScope.launch {
            val config = settings.settings.first()
            if (config.endpoint.isBlank() || config.model.isBlank() || config.apiKey.isBlank()) {
                Toast.makeText(this@MainActivity, R.string.missing_endpoint, Toast.LENGTH_SHORT)
                    .show()
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                return@launch
            }
            val sessionId =
                config.sessionId.ifBlank { log.create().also { settings.setSession(it) } }
            val id = (log.load(sessionId).maxOfOrNull { it.id } ?: 0L) + 1L
            log.append(sessionId, ChatMessage(id, fromUser = true, text = text.trim()))
            sent.value = log.load(sessionId)
            ContextCompat.startForegroundService(
                this@MainActivity,
                Intent(this@MainActivity, AgentService::class.java)
            )
        }
        currentFocus?.let { view ->
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
    }

    private fun requestVoice() {
        onSpeech = ::send
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        val recognizer = speech ?: SpeechRecognizer.createSpeechRecognizer(this).also { created ->
            created.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }?.let(onSpeech)
                }

                override fun onError(error: Int) = Unit
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            speech = created
        }
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                ),
        )
    }
}

@Composable
private fun CurvedMessages(
    messages: List<ChatMessage>,
    turnState: TurnState,
    selected: Long?,
    onSelect: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onRegenerate: (Long) -> Unit,
    onEdit: ((ChatMessage) -> Unit)?,
    roundFit: Boolean = true,
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    LaunchedEffect(messages.size, turnState.running) {
        val target = messages.lastIndex + if (turnState.running || turnState.error != null) 1 else 0
        if (target >= 0) {
            if (turnState.running) state.scrollToItem(target) else state.animateScrollToItem(target)
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        TransformingLazyColumn(
            Modifier.width(if (roundFit) 235.dp else 300.dp),
            state = state,
            contentPadding = PaddingValues(top = 24.dp, bottom = 72.dp),
        ) {
            items(messages.size, key = { messages[it].id }) {
                val message = messages[it]
                val itemTransformation = with(this) { SurfaceTransformation(spec) }
                Column(
                    Modifier
                        .transformedHeight(this, spec)
                        .graphicsLayer { with(itemTransformation) { applyContainerTransformation() } },
                ) {
                    Bubble(message, roundFit = roundFit, onLongPress = { onSelect(message.id) })
                }
            }
            if (turnState.running || turnState.error != null) item {
                Column(Modifier.transformedHeight(this, spec)) { LiveBubble(turnState, roundFit) }
            }
        }
    }
}

/** Empty-session welcome page: centered hero title and subtitle only. */
@Composable
private fun EmptyChatHero() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.hero_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.hero_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


@Composable
private fun ChatScreen(
    activity: ComponentActivity,
    log: SessionLog,
    settings: SettingsStore,
    turn: MutableStateFlow<TurnState>,
    sent: MutableStateFlow<List<ChatMessage>>,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onVoice: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val density = LocalDensity.current
    val inputStop = remember(density) { with(density) { 56.dp.toPx() } }
    val configStop = remember(density) { with(density) { 112.dp.toPx() } }
    val pull = remember { mutableFloatStateOf(0f) }
    var stage by remember { mutableStateOf(0) }
    val columnState = rememberLazyListState()
    var selected by remember { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    val scope = rememberCoroutineScope()
    val sessionId = settings.settings.collectAsStateWithLifecycle(AgentSettings()).value.sessionId
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        MessageList(log, settings, turn, sent, columnState, selected, { selected = it }, { id ->
            log.deleteMessage(sessionId, id)
            sent.value = log.load(sessionId)
            selected = null
        }, { id ->
            log.truncateFrom(sessionId, id)
            sent.value = log.load(sessionId)
            selected = null
            ContextCompat.startForegroundService(
                activity,
                Intent(activity, AgentService::class.java)
            )
        }, { message ->
            editing = message
        })
        if (editing != null) {
            EditMessageDialog(
                editing!!,
                onDismiss = { editing = null },
                onSave = { text ->
                    log.editMessage(sessionId, editing!!.id, text)
                    sent.value = log.load(sessionId)
                    editing = null
                },
            )
        }
        PullSheet(pull, inputStop, configStop, settings, turn, {
            onSend(it)
            pull.floatValue = 0f
            stage = 0
        }, onStop, onVoice, {
            pull.floatValue = 0f
            stage = 0
            onOpenSettings()
        }, stage, { stage = snapPull(pull, stage, inputStop, configStop) })

        val selectedMessage = run {
            val persisted =
                sent.collectAsStateWithLifecycle(initialValue = emptyList<ChatMessage>())
            val turnState = turn.collectAsStateWithLifecycle()
            remember(selected, persisted.value, turnState.value.running) {
                if (turnState.value.running) null
                else log.load(sessionId).firstOrNull { it.id == selected }
                    ?: persisted.value.firstOrNull { it.id == selected }
            }
        }
        var sheetVisible by remember { mutableStateOf(false) }
        LaunchedEffect(selectedMessage) {
            if (selectedMessage != null) sheetVisible = true
        }
        val dismissSheet: () -> Unit = {
            sheetVisible = false
            scope.launch { delay(150); selected = null }
        }
        if (selectedMessage != null) {
            AnimatedVisibility(
                visible = sheetVisible,
                enter = scaleIn(
                    initialScale = 0.85f,
                    animationSpec = spring(dampingRatio = 0.75f, stiffness = 380f)
                ) + fadeIn(tween(150)),
                exit = scaleOut(
                    targetScale = 0.9f,
                    animationSpec = tween(140)
                ) + fadeOut(tween(140)),
            ) {
                MessageActionSheet(
                    selectedMessage,
                    onDismiss = dismissSheet,
                    onDelete = { id ->
                        log.deleteMessage(sessionId, id)
                        sent.value = log.load(sessionId)
                        dismissSheet()
                    },
                    onRegenerate = { id ->
                        val isUser = selectedMessage.fromUser
                        if (isUser) log.truncateAfter(
                            sessionId,
                            id
                        ) else log.truncateFrom(sessionId, id)
                        sent.value = log.load(sessionId)
                        dismissSheet()
                        ContextCompat.startForegroundService(
                            activity,
                            Intent(activity, AgentService::class.java)
                        )
                    },
                    onEdit = { message ->
                        dismissSheet()
                        editing = message
                    },
                )
            }
        }
    }
}

@Composable
private fun MessageList(
    log: SessionLog,
    settings: SettingsStore,
    turn: MutableStateFlow<TurnState>,
    sent: MutableStateFlow<List<ChatMessage>>,
    columnState: LazyListState,
    selected: Long?,
    onSelect: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onRegenerate: (Long) -> Unit,
    onEdit: ((ChatMessage) -> Unit)?,
) {
    val persisted by sent.collectAsStateWithLifecycle(initialValue = emptyList())
    val turnState by turn.collectAsStateWithLifecycle()
    val sessionId = settings.settings.collectAsStateWithLifecycle(AgentSettings()).value.sessionId
    val messages = remember(persisted, turnState.running, sessionId) {
        if (turnState.running) persisted else log.load(sessionId).ifEmpty { persisted }
    }
    val curved = settings.settings.collectAsStateWithLifecycle(AgentSettings()).value.curvedList
    val roundFit = settings.settings.collectAsStateWithLifecycle(AgentSettings()).value.roundFit
    if (messages.isEmpty() && !turnState.running) {
        // Empty session: hero title + input hint, like the reference layout
        EmptyChatHero()
        return
    }
    if (curved) {
        CurvedMessages(
            messages,
            turnState,
            selected,
            onSelect,
            onDelete,
            onRegenerate,
            onEdit,
            roundFit
        )
        return
    }
    LaunchedEffect(messages.size, turnState.running) {
        val target = messages.lastIndex + if (turnState.running || turnState.error != null) 1 else 0
        if (target >= 0) columnState.scrollToItem(target)
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = columnState,
        contentPadding = PaddingValues(top = 36.dp, bottom = 72.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            Column {
                Bubble(message, roundFit = roundFit, onLongPress = { onSelect(message.id) })
            }
        }
        if (turnState.running || turnState.error != null) item { LiveBubble(turnState, roundFit) }
    }
}

@Composable
private fun PullSheet(
    pull: MutableFloatState,
    inputStop: Float,
    configStop: Float,
    settings: SettingsStore,
    turn: MutableStateFlow<TurnState>,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onVoice: () -> Unit,
    onOpenSettings: () -> Unit,
    stage: Int,
    onRelease: () -> Unit,
) {
    val shown = pull.floatValue
    if (shown > 0f) BackHandler { pull.floatValue = 0f }
    val inputShown = shown.coerceAtMost(inputStop) / inputStop
    val settingsShown = ((shown - inputStop) / (configStop - inputStop)).coerceIn(0f, 1f)
    val inputHeight by animateDpAsState(
        40.dp * inputShown,
        spring(dampingRatio = 0.8f, stiffness = 380f),
        label = "input"
    )
    val settingsHeight by animateDpAsState(
        44.dp * settingsShown,
        spring(dampingRatio = 0.8f, stiffness = 380f),
        label = "settings"
    )
    val max = if (stage == 0) inputStop else configStop
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        if (shown > 0f) Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectTapGestures {
                        pull.floatValue = 0f
                        onRelease()
                    }
                },
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(16.dp)
                .pointerInput(max) {
                    detectVerticalDragGestures(
                        onDragEnd = { onRelease() },
                        onDragCancel = { onRelease() },
                    ) { _, drag ->
                        pull.floatValue = (pull.floatValue - drag).coerceIn(0f, max)
                    }
                },
        )
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 9.dp)
                .fillMaxWidth()
                .pointerInput(max) {
                    detectVerticalDragGestures(
                        onDragEnd = { onRelease() },
                        onDragCancel = { onRelease() },
                    ) { _, drag ->
                        pull.floatValue = (pull.floatValue - drag).coerceIn(0f, max)
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Handle(inputShown.coerceAtLeast(settingsShown).coerceAtLeast(0.6f))
            AnimatedVisibility(
                visible = shown > 0f,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(120))
            ) {
                Spacer(Modifier.height(5.dp))
            }
            Box(Modifier.fillMaxWidth(0.86f), contentAlignment = Alignment.Center) {
                SheetStack(
                    inputShown,
                    inputHeight,
                    settingsShown,
                    settingsHeight,
                    settings,
                    turn,
                    onSend,
                    onStop,
                    onVoice,
                    onOpenSettings
                )
            }
        }
    }
}

@Composable
private fun SheetStack(
    inputShown: Float,
    inputHeight: androidx.compose.ui.unit.Dp,
    settingsShown: Float,
    settingsHeight: androidx.compose.ui.unit.Dp,
    settings: SettingsStore,
    turn: MutableStateFlow<TurnState>,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onVoice: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val current by settings.settings.collectAsStateWithLifecycle(AgentSettings())
    val running = turn.collectAsStateWithLifecycle().value.running
    var draft by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        if (!running && draft.isNotBlank()) {
            keyboard?.hide()
            focusManager.clearFocus(force = true)
            onSend(draft)
            draft = ""
        }
    }
    val dismissKeyboard = {
        keyboard?.hide()
        focusManager.clearFocus(force = true)
    }
    Column(Modifier.fillMaxWidth(0.86f), horizontalAlignment = Alignment.CenterHorizontally) {
        if (inputShown > 0f) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(inputHeight.coerceAtLeast(1.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (current.inputMode == InputMode.VOICE && !running) {
                        SheetRow(stringResource(R.string.hold_to_talk), onClick = onVoice)
                    } else {
                        DraftField(
                            draft,
                            { draft = it },
                            focus,
                            Modifier.clickable { focus.requestFocus() }) {
                            if (current.imeSends) submit() else dismissKeyboard()
                        }
                    }
                }
                ActionButton(running, Modifier.padding(start = 4.dp)) {
                    if (running) onStop() else submit()
                }
            }
        }
        AnimatedVisibility(
            visible = settingsShown > 0f,
            enter = slideInVertically(
                spring(
                    dampingRatio = 0.8f,
                    stiffness = 380f
                )
            ) { it / 2 } + fadeIn(),
            exit = slideOutVertically(tween(150)) { it / 2 } + fadeOut(),
        ) {
            SheetRow(
                stringResource(R.string.settings),
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .height(settingsHeight.coerceAtLeast(1.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(enabled = settingsShown == 1f, onClick = onOpenSettings),
            )
        }
    }
}

@Composable
private fun ActionButton(running: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (running) scheme.error else scheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (running) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(scheme.onError)
            )
        } else {
            Text(
                "\u2191",
                color = scheme.onPrimary,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun SheetRow(label: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Text(
            label,
            Modifier
                .fillMaxWidth()
                .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
                .padding(horizontal = 14.dp, vertical = 9.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Handle(progression: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(28.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(
                MaterialTheme.colorScheme.onSurface.copy(
                    alpha = 0.4f * progression.coerceIn(
                        0f,
                        1f
                    )
                )
            ),
    )
}

@Composable
private fun LiveBubble(state: TurnState, roundFit: Boolean = true) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        val body = state.text.ifBlank { state.error.orEmpty() }
        if (body.isBlank()) {
            Bubble(
                ChatMessage(-1, fromUser = false, text = "", reasoning = state.reasoning),
                roundFit = roundFit,
                activeThinking = true
            )
        } else {
            Bubble(
                ChatMessage(-1, fromUser = false, text = body, reasoning = state.reasoning),
                roundFit = roundFit
            )
        }
    }
}

/** Collapsible reasoning block: auto-expands while thinking, auto-collapses once the body starts. */
@Composable
private fun ThinkingBlock(
    reasoning: String,
    hasBody: Boolean,
    roundFit: Boolean = true,
    active: Boolean = false
) {
    var userToggled by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(!hasBody) }
    LaunchedEffect(hasBody) {
        if (!userToggled) expanded = !hasBody
    }
    val maxWidth = if (roundFit) 235.dp else 290.dp
    Column(
        Modifier
            .widthIn(max = maxWidth)
            .padding(bottom = 4.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable {
                    userToggled = true
                    expanded = !expanded
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(if (active) R.string.thinking_active else R.string.thinking),
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (expanded) "▲" else "▼",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        AnimatedVisibility(visible = expanded) {
            val scroll = rememberScrollState()
            LaunchedEffect(reasoning) {
                if (!userToggled && scroll.maxValue > 0) scroll.animateScrollTo(scroll.maxValue)
            }
            Text(
                reasoning,
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .heightIn(max = 160.dp)
                    .verticalScroll(scroll),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "dots")
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotAlpha",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            Box(
                Modifier
                    .size(4.dp)
                    .graphicsLayer {
                        this.alpha = if (index == 0) alpha else alpha * (1f - index * 0.25f)
                    }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}

@Composable
private fun Bubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    transformation: SurfaceTransformation? = null,
    roundFit: Boolean = true,
    activeThinking: Boolean = false,
    onLongPress: () -> Unit = {},
) {
    val mine = message.fromUser
    val userMax = if (roundFit) 190.dp else 250.dp
    val aiMax = if (roundFit) 235.dp else 290.dp
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        if (mine) {
            BubbleSurface(
                modifier = modifier.widthIn(max = userMax),
                transformation = transformation,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                onLongPress = onLongPress,
            ) {
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        } else {
            BubbleSurface(
                modifier = modifier.widthIn(max = aiMax),
                transformation = transformation,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                onLongPress = onLongPress,
            ) {
                Column {
                    if (message.reasoning.isNotBlank()) {
                        ThinkingBlock(
                            message.reasoning,
                            hasBody = message.text.isNotBlank(),
                            roundFit = roundFit,
                            active = activeThinking
                        )
                    }
                    if (message.text.isNotBlank()) MarkdownText(message.text)
                    if (activeThinking && message.reasoning.isBlank()) ThinkingDots()
                    if (!message.fromUser && message.completionTokens > 0) UsageLine(message)
                }
            }
        }
    }
}

@Composable
private fun UsageLine(message: ChatMessage) {
    val tps =
        if (message.elapsedMs > 0) message.completionTokens * 1000f / message.elapsedMs else 0f

    fun short(n: Int): String = when {
        n >= 1000 -> "${"%.1f".format(n / 1000f)}k"
        else -> n.toString()
    }

    val cached = if (message.cachedTokens > 0) " (${short(message.cachedTokens)} ch)" else ""
    Text(
        "↓ ${short(message.completionTokens)}t, ↑ ${short(message.promptTokens)}t$cached · ${
            "%.1f".format(
                tps
            )
        } t/s",
        Modifier.padding(top = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun BubbleSurface(
    modifier: Modifier = Modifier,
    transformation: SurfaceTransformation? = null,
    containerColor: Color,
    contentColor: Color = Color.Unspecified,
    onLongPress: () -> Unit,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier
                .graphicsLayer { if (transformation != null) with(transformation) { applyContainerTransformation() } }
                .clip(RoundedCornerShape(16.dp))
                .background(containerColor)
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .graphicsLayer { if (transformation != null) with(transformation) { applyContentTransformation() } }
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) { content() }
    }
}

@Composable
private fun MessageActionSheet(
    message: ChatMessage,
    onDismiss: () -> Unit,
    onDelete: (Long) -> Unit,
    onRegenerate: (Long) -> Unit,
    onEdit: (ChatMessage) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var selecting by remember { mutableStateOf(false) }
    BackHandler(onBack = onDismiss)
    if (selecting) {
        SelectTextDialog(message.text) { selecting = false }
        return
    }
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ScreenScaffold(scrollState = state) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                TransformingLazyColumn(
                    Modifier
                        .width(200.dp),
                    state = state,
                    contentPadding = PaddingValues(top = 36.dp, bottom = 56.dp),
                ) {
                    item {
                        ActionPageButton(this, spec, stringResource(R.string.copy_message)) {
                            clipboard.setText(AnnotatedString(message.text))
                            Toast.makeText(
                                context,
                                context.getString(R.string.copied),
                                Toast.LENGTH_SHORT
                            ).show()
                            onDismiss()
                        }
                    }
                    item {
                        ActionPageButton(
                            this,
                            spec,
                            stringResource(R.string.select_text)
                        ) { selecting = true }
                    }
                    item {
                        ActionPageButton(this, spec, stringResource(R.string.regenerate)) {
                            onRegenerate(message.id)
                            onDismiss()
                        }
                    }
                    item {
                        ActionPageButton(this, spec, stringResource(R.string.edit_message)) {
                            onEdit(message)
                            onDismiss()
                        }
                    }
                    item {
                        ActionPageButton(
                            this, spec, stringResource(R.string.delete_message),
                            destructive = true,
                        ) {
                            onDelete(message.id)
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionPageButton(
    scope: TransformingLazyColumnItemScope,
    spec: TransformationSpec,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Column(Modifier.transformedHeight(scope, spec)) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            transformation = with(scope) { SurfaceTransformation(spec) },
            colors = if (destructive) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
        ) { Text(label) }
    }
}

@Composable
private fun SheetAction(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (destructive) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceContainer,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        color = if (destructive) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun SelectTextDialog(text: String, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ScreenScaffold(scrollState = state) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                TransformingLazyColumn(
                    Modifier.width(200.dp),
                    state = state,
                    contentPadding = PaddingValues(top = 36.dp, bottom = 56.dp),
                ) {
                    item {
                        Column(Modifier.transformedHeight(this, spec)) {
                            Text(
                                stringResource(R.string.select_text),
                                Modifier.padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item {
                        Column(Modifier.transformedHeight(this, spec)) {
                            SelectionContainer {
                                Text(
                                    text,
                                    Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun EditMessageDialog(
    message: ChatMessage,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(message.id) { mutableStateOf(message.text) }
    BackHandler(onBack = onDismiss)
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ScreenScaffold(scrollState = state) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                TransformingLazyColumn(
                    Modifier.width(200.dp),
                    state = state,
                    contentPadding = PaddingValues(top = 36.dp, bottom = 56.dp),
                ) {
                    item {
                        Column(Modifier.transformedHeight(this, spec)) {
                            Text(
                                stringResource(R.string.edit_message),
                                Modifier.padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item {
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 240.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(10.dp)
                                .verticalScroll(rememberScrollState()),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        )
                    }
                    item {
                        ActionPageButton(this, spec, stringResource(R.string.save)) {
                            onSave(text)
                        }
                    }
                    item {
                        ActionPageButton(
                            this,
                            spec,
                            stringResource(R.string.cancel)
                        ) { onDismiss() }
                    }
                }
            }
        }
    }
}

@Composable
private fun DraftField(
    value: String,
    onValue: (String) -> Unit,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
    onSend: () -> Unit
) {
    BasicTextField(
        value, onValue,
        modifier
            .fillMaxWidth()
            .focusRequester(focus),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSend() }),
        singleLine = true,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(
                    stringResource(R.string.input_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                inner()
            }
        },
    )
}

private fun snapPull(
    pull: MutableFloatState,
    stage: Int,
    inputStop: Float,
    configStop: Float
): Int {
    val value = pull.floatValue
    val next = when {
        stage == 0 && value > 18f -> inputStop
        stage > 0 && value < inputStop -> 0f
        stage > 0 && value > inputStop + 18f -> configStop
        stage > 0 -> inputStop
        else -> 0f
    }
    pull.floatValue = next
    return when (next) {
        configStop -> 2
        inputStop -> 1
        else -> 0
    }
}
