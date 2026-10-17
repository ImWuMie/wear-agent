package dev.undefinedteam.wearagent.presentation.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.agent.ToolActivity
import dev.undefinedteam.wearagent.agent.tools.toolSummary
import dev.undefinedteam.wearagent.agent.ToolCall
import dev.undefinedteam.wearagent.agent.TranscriptItem
import dev.undefinedteam.wearagent.agent.TokensUsage
import dev.undefinedteam.wearagent.agent.TurnState
import dev.undefinedteam.wearagent.presentation.markdown.MarkdownText
import dev.undefinedteam.wearagent.session.ChatMessage

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

private fun transcriptTools(transcript: List<TranscriptItem>): List<ToolActivity> {
    if (transcript.isEmpty()) return emptyList()
    val results = transcript.filterIsInstance<TranscriptItem.ToolResult>().associateBy { it.callId }
    return buildList {
        transcript.forEach { item ->
            if (item is TranscriptItem.Assistant) {
                item.toolCalls.forEach { call ->
                    val result = results[call.id]
                    add(ToolActivity(call, result?.content, result?.isError ?: false))
                }
            }
        }
    }
}

/**
 * One collapsible block per tool call, stacked above the body like the reasoning
 * block: the header is the owning tool's own summary of the request, expanding
 * reveals the result.
 */
@Composable
private fun ToolActivities(
    tools: List<ToolActivity>,
    running: Boolean,
    onLongPress: () -> Unit = {},
    mathFitWidth: Boolean = true,
) {
    tools.forEach { activity ->
        key(activity.call.id) {
            var expanded by remember { mutableStateOf(false) }
            val summary = remember(activity.call.arguments) { toolSummary(activity.call) }
            val result = activity.result
            val status = stringResource(
                when {
                    activity.isError -> R.string.tool_failed
                    result != null -> R.string.tool_completed
                    running -> R.string.tool_running
                    else -> R.string.tool_incomplete
                }
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .combinedClickable(
                            enabled = result != null,
                            onClickLabel = stringResource(
                                if (expanded) R.string.tool_hide_result else R.string.tool_show_result
                            ),
                            onLongClick = onLongPress,
                        ) {
                            expanded = !expanded
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            summary,
                            color = if (activity.isError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${activity.call.name} · $status",
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (result != null) Text(
                        if (expanded) "\u25B2" else "\u25BC",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                AnimatedVisibility(visible = expanded && result != null) {
                    SelectionContainer {
                        MarkdownText(
                            result.orEmpty(),
                            Modifier.padding(top = 4.dp),
                            mathFitWidth = mathFitWidth,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Collapsible reasoning block: auto-expands while thinking, auto-collapses once the body starts. */
@Composable
private fun ThinkingBlock(
    reasoning: String,
    hasBody: Boolean,
    roundFit: Boolean = true,
    active: Boolean = false,
    onLongPress: () -> Unit = {},
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
                .combinedClickable(
                    onClick = {
                        userToggled = true
                        expanded = !expanded
                    },
                    onLongClick = onLongPress,
                )
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
internal fun Bubble(
    message: ChatMessage,
    mathFitWidth: Boolean = true,
    modifier: Modifier = Modifier,
    transformation: SurfaceTransformation? = null,
    roundFit: Boolean = true,
    activeThinking: Boolean = false,
    onLongPress: () -> Unit = {},
    toolActivities: List<ToolActivity>? = null,
    toolsRunning: Boolean = false,
) {
    val mine = message.userMessage
    val userMax = if (roundFit) 190.dp else 250.dp
    val aiMax = if (roundFit) 235.dp else 290.dp
    val storedTools = remember(message.transcript) { transcriptTools(message.transcript) }
    val tools = toolActivities ?: storedTools
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
                    if (tools.isNotEmpty()) ToolActivities(tools, toolsRunning, onLongPress, mathFitWidth)
                    if (message.reasoning.isNotBlank()) {
                        ThinkingBlock(
                            message.reasoning,
                            hasBody = message.text.isNotBlank(),
                            roundFit = roundFit,
                            active = activeThinking,
                            onLongPress = onLongPress,
                        )
                    }
                    if (message.text.isNotBlank()) MarkdownText(message.text, mathFitWidth = mathFitWidth)
                    if (activeThinking && message.reasoning.isBlank()) ThinkingDots()
                    if (!message.userMessage) message.tokens?.let { UsageLine(it, message.elapsedMs) }
                }
            }
        }
    }
}

@Composable
private fun UsageLine(tokens: TokensUsage, elapsedMs: Long) {
    val tps =
        if (elapsedMs > 0) tokens.completion * 1000f / elapsedMs else 0f

    fun short(n: Int): String = when {
        n >= 1000 -> "${"%.1f".format(n / 1000f)}k"
        else -> n.toString()
    }

    val cached = if (tokens.cached > 0) " (${short(tokens.cached)} ch)" else ""
    Text(
        "↓ ${short(tokens.completion)}t, ↑ ${short(tokens.prompt)}t$cached · ${
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
