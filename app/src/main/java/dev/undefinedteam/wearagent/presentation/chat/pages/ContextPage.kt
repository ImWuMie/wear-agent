package dev.undefinedteam.wearagent.presentation.chat.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.agent.ToolDefinition
import dev.undefinedteam.wearagent.presentation.settings.TransformedItem
import dev.undefinedteam.wearagent.session.ChatMessage
import dev.undefinedteam.wearagent.session.ContextKind
import dev.undefinedteam.wearagent.session.ContextSegment
import dev.undefinedteam.wearagent.session.contextBreakdown

/**
 * Context usage: a single stacked bar showing what fills the window — the tool
 * schemas (the fixed system part), the conversation, and the free space left —
 * with a colour-keyed legend underneath and the numeric totals below that.
 */
@Composable
internal fun ContextPage(
    messages: List<ChatMessage>,
    tools: List<ToolDefinition>,
    systemPrompt: String,
    contextWindow: Int,
    roundFit: Boolean,
) {
    val turns = remember(messages) { messages.count { it.userMessage } }
    val last = remember(messages) { messages.lastOrNull { it.tokens != null } }
    val prompt = last?.tokens?.prompt
    val segments = remember(messages, tools, systemPrompt, prompt, contextWindow) {
        contextBreakdown(messages, tools, systemPrompt, prompt, contextWindow)
    }
    val total = segments.sumOf { it.tokens }
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    // Same shape as the chat page: a centred curved list whose items scale and fade
    // as they cross the bezel. ScreenScaffold adds the standard centre-end scroll
    // indicator, so a long page shows where it sits.
    ScreenScaffold(scrollState = state) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            TransformingLazyColumn(
                Modifier.width(if (roundFit) 200.dp else 240.dp),
                state = state,
                contentPadding = PaddingValues(top = 24.dp, bottom = 72.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                item {
                    TransformedItem(this, spec) {
                        ListHeader { Text(stringResource(R.string.context_usage)) }
                    }
                }
                item {
                    TransformedItem(this, spec) {
                        ContextBar(segments, total)
                    }
                }
                items(segments.size) { index ->
                    val segment = segments[index]
                    if (segment.tokens > 0) {
                        TransformedItem(this, spec) {
                            LegendRow(segment, total)
                        }
                    }
                }
                item {
                    TransformedItem(this, spec) {
                        UsageRow(stringResource(R.string.context_turns), turns.toString())
                    }
                }
                if (last?.elapsedMs?.let { it > 0 } == true) {
                    item {
                        TransformedItem(this, spec) {
                            UsageRow(stringResource(R.string.context_elapsed), "${last.elapsedMs} ms")
                        }
                    }
                }
                if (last?.tokens?.completion != null) {
                    item {
                        TransformedItem(this, spec) {
                            UsageRow(
                                stringResource(R.string.context_completion),
                                last.tokens.completion.toString(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Smallest share of the bar a non-empty slice is allowed to occupy. */
private const val MIN_SLICE_FRACTION = 0.015f

/** The stacked context bar: one coloured slice per non-empty [ContextSegment]. */
@Composable
private fun ContextBar(segments: List<ContextSegment>, total: Int) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(scheme.surfaceContainerHigh),
    ) {
        if (total > 0) {
            // A tiny slice still gets a visible sliver, so a small but present
            // slice (say 0.3% of the window) is not silently dropped from the bar.
            val floor = total * MIN_SLICE_FRACTION
            segments.forEach { segment ->
                if (segment.tokens > 0) {
                    Box(
                        Modifier
                            .weight(segment.tokens.toFloat().coerceAtLeast(floor))
                            .fillMaxHeight()
                            .background(contextColor(segment.kind, scheme)),
                    )
                }
            }
        }
    }
}

/** One legend line: colour swatch, name, and the slice's size and share. */
@Composable
private fun LegendRow(segment: ContextSegment, total: Int) {
    val scheme = MaterialTheme.colorScheme
    val percent = if (total > 0) segment.tokens * 100f / total else 0f
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(contextColor(segment.kind, scheme)),
        )
        Text(
            stringResource(contextLabel(segment.kind)),
            Modifier.weight(1f),
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "~${formatTokens(segment.tokens)} (${formatPercent(percent)})",
            color = scheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** `128k` / `1.3M` style token counts for the legend. */
private fun formatTokens(tokens: Int): String = when {
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000f)
    tokens >= 1_000 -> "%.1fk".format(tokens / 1_000f)
    else -> tokens.toString()
}

/** One-decimal percentage for the legend. */
private fun formatPercent(percent: Float): String = "%.1f%%".format(percent)

/** One colour per context slice, matching the legend swatches. */
private fun contextColor(kind: ContextKind, scheme: ColorScheme): Color = when (kind) {
    ContextKind.SYSTEM_PROMPT -> scheme.outline
    ContextKind.SYSTEM_TOOLS -> scheme.tertiary
    ContextKind.MESSAGES -> scheme.primary
    ContextKind.FREE -> scheme.surfaceContainerHigh
}

private fun contextLabel(kind: ContextKind): Int = when (kind) {
    ContextKind.SYSTEM_PROMPT -> R.string.context_system_prompt
    ContextKind.SYSTEM_TOOLS -> R.string.context_system_tools
    ContextKind.MESSAGES -> R.string.context_messages
    ContextKind.FREE -> R.string.context_free
}

/** One label/value line inside a [TransformedItem]; the row scales with the item. */
@Composable
private fun UsageRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

