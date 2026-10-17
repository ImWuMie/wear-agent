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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.agent.ToolActivity
import dev.undefinedteam.wearagent.agent.tools.toolSummary
import dev.undefinedteam.wearagent.presentation.markdown.MarkdownText

@Composable
internal fun ToolActivities(
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
internal fun ThinkingBlock(
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
internal fun ThinkingDots() {
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

