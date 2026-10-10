package dev.undefinedteam.wearagent.presentation.chat

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.agent.AgentService
import dev.undefinedteam.wearagent.agent.TurnState
import dev.undefinedteam.wearagent.session.AgentSettings
import dev.undefinedteam.wearagent.session.ChatMessage
import dev.undefinedteam.wearagent.session.SessionLog
import dev.undefinedteam.wearagent.session.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
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
        MessageList(log, settings, turn, sent, columnState, selected, { selected = it })
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
        CurvedMessages(messages, turnState, selected, onSelect, roundFit)
        return
    }
    LaunchedEffect(messages.size, turnState.running) {
        val target = messages.lastIndex + if (turnState.live) 1 else 0
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
        if (turnState.live) item { LiveBubble(turnState, roundFit) }
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
