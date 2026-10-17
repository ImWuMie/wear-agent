package dev.undefinedteam.wearagent.presentation.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import dev.undefinedteam.wearagent.agent.TurnState
import dev.undefinedteam.wearagent.session.AgentSettings
import dev.undefinedteam.wearagent.session.ChatMessage
import dev.undefinedteam.wearagent.session.SessionLog
import dev.undefinedteam.wearagent.session.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
internal fun CurvedMessages(
    messages: List<ChatMessage>,
    turnState: TurnState,
    onSelect: (ChatMessage) -> Unit,
    roundFit: Boolean = true,
    mathFitWidth: Boolean = true,
    focusId: Long? = null,
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    // A jump request from the request view scrolls the target into view. While it
    // is set the list stays where the user put it — the follow-the-tail effect is
    // suppressed, otherwise the two effects fight and the list drifts to the tail.
    // The target is cleared by the caller when the user sends a new message.
    LaunchedEffect(focusId) {
        val index = focusId?.let { id -> messages.indexOfFirst { it.id == id } } ?: -1
        if (index >= 0) state.animateScrollToItem(index)
    }
    LaunchedEffect(messages.size, turnState.running, focusId) {
        if (focusId != null) return@LaunchedEffect
        val target = messages.lastIndex + if (turnState.live) 1 else 0
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
                    Bubble(
                        message,
                        roundFit = roundFit,
                        mathFitWidth = mathFitWidth,
                        onLongPress = { onSelect(message) },
                    )
                }
            }
            if (turnState.live) item {
                Column(Modifier.transformedHeight(this, spec)) {
                    LiveBubble(turnState, roundFit, mathFitWidth)
                }
            }
        }
    }
}

@Composable
internal fun LiveBubble(
    state: TurnState,
    roundFit: Boolean = true,
    mathFitWidth: Boolean = true,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        val body = if (state.failed) state.error.orEmpty() else state.text
        Bubble(
            ChatMessage(
                -1,
                userMessage = false,
                text = body,
                reasoning = if (state.failed) "" else state.reasoning
            ),
            roundFit = roundFit,
            mathFitWidth = mathFitWidth,
            activeThinking = state.thinking,
            toolActivities = if (state.failed) emptyList() else state.tools,
            toolsRunning = state.running,
        )
    }
}



@Composable
internal fun MessageList(
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

