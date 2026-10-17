package dev.undefinedteam.wearagent.presentation.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.PagerState
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.launch
import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.agent.TokensUsage
import dev.undefinedteam.wearagent.agent.ToolDefinition
import dev.undefinedteam.wearagent.session.ChatMessage
import dev.undefinedteam.wearagent.session.ContextKind
import dev.undefinedteam.wearagent.session.DEFAULT_CONTEXT_WINDOW
import dev.undefinedteam.wearagent.session.ContextSegment
import dev.undefinedteam.wearagent.session.contextBreakdown
import dev.undefinedteam.wearagent.session.RequestEntry
import dev.undefinedteam.wearagent.session.RequestRole
import dev.undefinedteam.wearagent.presentation.settings.TransformedItem
import dev.undefinedteam.wearagent.session.describeRequest

private const val CHAT_PAGE = 0
private const val CONTEXT_PAGE = 1
private const val TRAJECTORY_PAGE = 2

/**
 * Chat as a three-page pager: the conversation, the context usage, and — always
 * last — the request trajectory. A long-press action pages to the trajectory.
 * The trajectory is the final page by design, so paging right from the chat walks
 * outward: conversation, then what it cost, then exactly what was sent.
 */
@Composable
internal fun ChatPager(
    messages: List<ChatMessage>,
    pager: PagerState,
    focusIndex: Int?,
    onClearFocus: () -> Unit,
    roundFit: Boolean = true,
    tools: List<ToolDefinition> = emptyList(),
    systemPrompt: String = "",
    contextWindow: Int = DEFAULT_CONTEXT_WINDOW,
    onJumpToMessage: (Long) -> Unit = {},
    chat: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // Long-pressing a trajectory row jumps back to the chat page and asks it to
    // scroll the matching message into view.
    val jumpToChat: (Long) -> Unit = { id ->
        onJumpToMessage(id)
        if (focusIndex != null) onClearFocus()
        scope.launch { pager.animateScrollToPage(CHAT_PAGE) }
    }

    LaunchedEffect(focusIndex) {
        pager.animateScrollToPage(if (focusIndex == null) CHAT_PAGE else TRAJECTORY_PAGE)
    }
    BackHandler(enabled = pager.currentPage != CHAT_PAGE) {
        if (focusIndex != null) onClearFocus()
        else scope.launch { pager.animateScrollToPage(CHAT_PAGE) }
    }
    val density = LocalDensity.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    // Pages are exactly viewport-wide, so at rest the neighbour is fully off-screen
    // and never peeks at the edge.
    val pageSize = remember(density, screenWidth) { with(density) { screenWidth.roundToPx() } }
    // The scaffold adds the Wear transition (the off-centre page scales down and
    // fades) and the page indicator; the pager itself is its content. The page
    // background is opaque and a scrim covers the neighbours, so a partially
    // paged neighbour never shows through behind the active page.
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        HorizontalPagerScaffold(pager, Modifier.fillMaxSize(), pageIndicator = {}) {
            HorizontalPager(
                pager,
                Modifier.fillMaxSize(),
                PaddingValues(0.dp),
                pageSize,
                key = { it },
            ) { page ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    when (page) {
                        // The chat page keeps its own full-bleed layout untouched.
                        CHAT_PAGE -> chat()
                        // The info pages use the chat page's layout recipe — a centred
                        // curved list whose items scale and fade at the bezel — at the
                        // narrower width that keeps full-width rows off the round edge.
                        CONTEXT_PAGE -> ContextPage(messages, tools, systemPrompt, contextWindow, roundFit)
                        else -> TrajectoryPage(messages, focusIndex, roundFit, jumpToChat)
                    }
                }
            }
        }
    }
}

/**
 * Context usage: a single stacked bar showing what fills the window — the tool
 * schemas (the fixed system part), the conversation, and the free space left —
 * with a colour-keyed legend underneath and the numeric totals below that.
 */
@Composable
private fun ContextPage(
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
            "${formatTokens(segment.tokens)} (${formatPercent(percent)})",
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

/**
 * The request trajectory: one row per user/assistant/tool item, mirroring a
 * harness trace — a role chip, a name, and a one-line `args → result` preview.
 * Expanding a row reveals the full payload and result.
 */
@Composable
private fun TrajectoryPage(
    messages: List<ChatMessage>,
    focusIndex: Int?,
    roundFit: Boolean,
    onJumpToMessage: (Long) -> Unit,
) {
    val entries = remember(messages, focusIndex) {
        describeRequest(focusIndex?.let { messages.take(it + 1) } ?: messages)
    }
    // Nothing has been sent yet: a hero page reads better than a bare header.
    if (entries.isEmpty()) {
        RequestEmptyHero()
        return
    }
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    // Same shape as the chat page: a centred curved list whose rows scale and fade
    // as they cross the bezel. ScreenScaffold adds the standard centre-end scroll
    // indicator, so a long page shows where it sits.
    ScreenScaffold(scrollState = state) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            TransformingLazyColumn(
                Modifier.width(if (roundFit) 200.dp else 240.dp),
                state = state,
                contentPadding = PaddingValues(top = 24.dp, bottom = 72.dp),
                // Short pages centre vertically so their full-width rows stay inside the
                // widest part of the round bezel instead of clipping at the top arc.
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                item {
                    TransformedItem(this, spec) {
                        ListHeader { Text(stringResource(R.string.request_info)) }
                    }
                }
                items(entries.size, key = { it }) { index ->
                    TransformedItem(this, spec) {
                        TrajectoryRow(entries[index], onJumpToMessage)
                    }
                }
            }
        }
    }
}

/** Empty request page: centred hero title and subtitle, like the chat page. */
@Composable
private fun RequestEmptyHero() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.request_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.request_empty_subtitle),
            Modifier.fillMaxWidth(0.8f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** One trajectory row: role chip + one-line preview, expanding to full detail. */
@Composable
private fun TrajectoryRow(entry: RequestEntry, onJumpToMessage: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(bottom = 3.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .combinedClickable(
                    onClickLabel = stringResource(
                        if (expanded) R.string.trajectory_collapse
                        else R.string.trajectory_expand
                    ),
                    onLongClickLabel = stringResource(R.string.trajectory_jump),
                    onLongClick = { if (entry.messageId != 0L) onJumpToMessage(entry.messageId) },
                ) { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoleChip(entry)
            Text(
                entry.preview,
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (expanded) "\u25B2" else "\u25BC",
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 4.dp, top = 4.dp)) {
                if (entry.payload.isNotBlank()) {
                    TrajectoryField(stringResource(R.string.trajectory_payload), entry.payload)
                }
                if (entry.result.isNotBlank()) {
                    TrajectoryField(stringResource(R.string.trajectory_result), entry.result)
                }
            }
        }
    }
}

@Composable
private fun RoleChip(entry: RequestEntry) {
    val scheme = MaterialTheme.colorScheme
    val label = when (entry.role) {
        RequestRole.USER -> stringResource(R.string.role_user)
        RequestRole.ASSISTANT -> stringResource(R.string.role_assistant)
        RequestRole.TOOL -> stringResource(R.string.role_tool)
    }
    // One colour per role so the trajectory reads at a glance.
    val container = when (entry.role) {
        RequestRole.USER -> scheme.primaryContainer
        RequestRole.ASSISTANT -> scheme.secondaryContainer
        RequestRole.TOOL -> scheme.tertiaryContainer
    }
    val onContainer = when (entry.role) {
        RequestRole.USER -> scheme.onPrimaryContainer
        RequestRole.ASSISTANT -> scheme.onSecondaryContainer
        RequestRole.TOOL -> scheme.onTertiaryContainer
    }
    Text(
        label,
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        color = onContainer,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TrajectoryField(label: String, body: String) {
    Text(
        label,
        Modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )
    SelectionContainer {
        Text(
            body,
            Modifier.fillMaxWidth().padding(top = 1.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}