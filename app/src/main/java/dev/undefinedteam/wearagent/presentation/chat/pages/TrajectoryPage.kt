package dev.undefinedteam.wearagent.presentation.chat.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.presentation.settings.TransformedItem
import dev.undefinedteam.wearagent.session.ChatMessage
import dev.undefinedteam.wearagent.session.RequestEntry
import dev.undefinedteam.wearagent.session.RequestRole
import dev.undefinedteam.wearagent.session.describeRequest

/**
 * The request trajectory: one row per user/assistant/tool item, mirroring a
 * harness trace — a role chip, a name, and a one-line `args → result` preview.
 * Expanding a row reveals the full payload and result.
 */
@Composable
internal fun TrajectoryPage(
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
            if (entry.images > 0) Text(
                stringResource(R.string.trajectory_images, entry.images),
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
