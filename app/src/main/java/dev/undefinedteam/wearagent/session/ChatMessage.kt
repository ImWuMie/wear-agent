package dev.undefinedteam.wearagent.session

import dev.undefinedteam.wearagent.agent.TokensUsage
import dev.undefinedteam.wearagent.agent.TranscriptItem

/**
 * One persisted chat line. Assistant lines additionally carry the reasoning,
 * aggregated [tokens] of the whole turn, and the provider replay [transcript];
 * user lines leave them at defaults.
 */
data class ChatMessage(
    val id: Long,
    val userMessage: Boolean,
    val text: String,
    val reasoning: String = "",
    val tokens: TokensUsage? = null,
    val elapsedMs: Long = 0,
    val transcript: List<TranscriptItem> = emptyList(),
)

data class SessionInfo(
    val id: String,
    val title: String,
)
