package dev.undefinedteam.wearagent.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import dev.undefinedteam.wearagent.agent.ToolActivity
import dev.undefinedteam.wearagent.agent.TokensUsage
import dev.undefinedteam.wearagent.agent.TranscriptItem
import dev.undefinedteam.wearagent.presentation.chat.images.ImageStrip
import dev.undefinedteam.wearagent.presentation.markdown.MarkdownText
import dev.undefinedteam.wearagent.session.ChatMessage

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
                Column {
                    if (message.images.isNotEmpty()) {
                        // Capped so a two-up strip fits the round bezel: the bubble
                        // is as wide as the screen's midpoint chord, and wider
                        // thumbnails would be clipped at the curve.
                        ImageStrip(
                            message.images,
                            Modifier
                                .widthIn(max = 136.dp)
                                .padding(bottom = 4.dp),
                        )
                    }
                    if (message.text.isNotBlank()) Text(
                        message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
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

