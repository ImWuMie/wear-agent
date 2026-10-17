package dev.undefinedteam.wearagent.presentation.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.PagerState
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.MaterialTheme
import dev.undefinedteam.wearagent.agent.ToolDefinition
import dev.undefinedteam.wearagent.presentation.chat.pages.ContextPage
import dev.undefinedteam.wearagent.presentation.chat.pages.TrajectoryPage
import dev.undefinedteam.wearagent.session.ChatMessage
import dev.undefinedteam.wearagent.session.DEFAULT_CONTEXT_WINDOW
import kotlinx.coroutines.launch

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
                        CONTEXT_PAGE -> ContextPage(
                            messages,
                            tools,
                            systemPrompt,
                            contextWindow,
                            roundFit
                        )

                        else -> TrajectoryPage(messages, focusIndex, roundFit, jumpToChat)
                    }
                }
            }
        }
    }
}

