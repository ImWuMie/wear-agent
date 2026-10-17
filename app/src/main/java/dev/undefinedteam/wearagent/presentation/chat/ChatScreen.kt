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
import androidx.wear.compose.material3.HorizontalPageIndicator
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.agent.AgentService
import dev.undefinedteam.wearagent.agent.TurnState
import dev.undefinedteam.wearagent.agent.tools.defaultTools
import dev.undefinedteam.wearagent.session.AgentSettings
import dev.undefinedteam.wearagent.session.ChatMessage
import dev.undefinedteam.wearagent.session.SessionLog
import dev.undefinedteam.wearagent.session.SettingsStore
import kotlinx.coroutines.Job
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
    onOpenSettings: () -> Unit,
) {
    val density = LocalDensity.current
    val inputStop = remember(density) { with(density) { 56.dp.toPx() } }
    val configStop = remember(density) { with(density) { 112.dp.toPx() } }
    val pull = remember { mutableFloatStateOf(0f) }
    var stage by remember { mutableStateOf(0) }
    val columnState = rememberLazyListState()
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    var sheetMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var sheetVisible by remember { mutableStateOf(false) }
    var sheetWipe by remember { mutableStateOf<Job?>(null) }
    var detailIndex by remember { mutableStateOf<Int?>(null) }
    var jumpToId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val turnState by turn.collectAsStateWithLifecycle()
    val sessionId = settings.settings.collectAsStateWithLifecycle(AgentSettings()).value.sessionId
    val allMessages by sent.collectAsStateWithLifecycle(initialValue = emptyList())
    val roundFit = settings.settings.collectAsStateWithLifecycle(AgentSettings()).value.roundFit
    val agentSettings = settings.settings.collectAsStateWithLifecycle(AgentSettings()).value
    // The tool schemas the model is offered are part of the context it is sent, so
    // the context page counts them as the system slice.
    val tools = remember(agentSettings) {
        defaultTools().filter { it.enabledBy(agentSettings) }.map { it.definition }
    }
    val contextWindow = agentSettings.contextWindow
    val systemPrompt = agentSettings.effectiveSystemPrompt
    val pager = rememberPagerState(pageCount = { 3 })

    // The service appends the finished assistant message to the log; `sent` only
    // tracks sends, so it goes stale after every turn. Refresh it when a turn
    // settles — otherwise deleting that fresh assistant message writes a list
    // equal to the stale value and nothing recomposes (StateFlow conflates by
    // equality, so the message would linger on screen).
    LaunchedEffect(turnState.running, sessionId) {
        if (!turnState.running) sent.value = log.load(sessionId)
    }

    // A new turn means the user moved on; release the jump so the list follows
    // the tail again.
    LaunchedEffect(turnState.running) {
        if (turnState.running) jumpToId = null
    }

    fun openSheet(message: ChatMessage) {
        sheetWipe?.cancel()
        sheetMessage = message
        sheetVisible = true
    }

    val dismissSheet: () -> Unit = {
        sheetVisible = false
        sheetWipe?.cancel()
        sheetWipe = scope.launch { delay(150); sheetMessage = null }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ChatPager(
            allMessages,
            pager,
            detailIndex,
            { detailIndex = null },
            roundFit,
            tools,
            systemPrompt,
            contextWindow,
            onJumpToMessage = { id -> jumpToId = id },
        ) {
            MessageList(log, settings, turn, sent, columnState, { openSheet(it) }, jumpToId)
        }
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
        }, onStop, {
            pull.floatValue = 0f
            stage = 0
            onOpenSettings()
        }, stage, { stage = snapPull(pull, stage, inputStop, configStop) })
        // Shown only while the pager moves: the dots are orientation, not chrome, so
        // they fade out once a page settles. Placed above the pull sheet and clear of
        // the bezel.
        AnimatedVisibility(
            visible = pager.isScrollInProgress,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(220)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            HorizontalPageIndicator(pager, Modifier.padding(bottom = 14.dp))
        }
        if (sheetMessage != null) {
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
                    sheetMessage!!,
                    onDismiss = dismissSheet,
                    onDelete = { id ->
                        log.deleteMessage(sessionId, id)
                        sent.value = log.load(sessionId)
                        dismissSheet()
                    },
                    onRegenerate = { id ->
                        val isUser = sheetMessage!!.userMessage
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
                    onViewRequest = { message ->
                        detailIndex = allMessages.indexOfFirst { it.id == message.id }
                            .takeIf { it >= 0 }
                        dismissSheet()
                    },
                    onRewind = { message ->
                        val index = allMessages.indexOfFirst { it.id == message.id }
                        if (index >= 0) {
                            log.truncateFrom(sessionId, message.id)
                            sent.value = log.load(sessionId)
                        }
                        dismissSheet()
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
    onSelect: (ChatMessage) -> Unit,
    focusId: Long? = null,
) {
    val persisted by sent.collectAsStateWithLifecycle(initialValue = emptyList())
    val turnState by turn.collectAsStateWithLifecycle()
    val sessionId = settings.settings.collectAsStateWithLifecycle(AgentSettings()).value.sessionId
    val messages = remember(persisted, turnState.running, sessionId) {
        if (turnState.running) persisted else log.load(sessionId).ifEmpty { persisted }
    }
    val curved = settings.settings.collectAsStateWithLifecycle(AgentSettings()).value.curvedList
    val roundFit = settings.settings.collectAsStateWithLifecycle(AgentSettings()).value.roundFit
    val mathFitWidth = settings.settings.collectAsStateWithLifecycle(AgentSettings()).value.mathFitWidth
    if (messages.isEmpty() && !turnState.running) {
        // Empty session: hero title + input hint, like the reference layout
        EmptyChatHero()
        return
    }
    if (curved) {
        CurvedMessages(messages, turnState, onSelect, roundFit, mathFitWidth, focusId)
        return
    }
    // A jump request from the request view wins over the follow-the-tail effect.
    LaunchedEffect(focusId) {
        val index = focusId?.let { id -> messages.indexOfFirst { it.id == id } } ?: -1
        if (index >= 0) columnState.animateScrollToItem(index)
    }
    LaunchedEffect(messages.size, turnState.running, focusId) {
        if (focusId != null) return@LaunchedEffect
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
                Bubble(
                    message,
                    roundFit = roundFit,
                    mathFitWidth = mathFitWidth,
                    onLongPress = { onSelect(message) },
                )
            }
        }
        if (turnState.live) item { LiveBubble(turnState, roundFit, mathFitWidth) }
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
