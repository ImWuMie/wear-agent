package dev.undefinedteam.wearagent.session

import dev.undefinedteam.wearagent.agent.TranscriptItem

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val reasoning: String = "",
    val promptTokens: Int = 0,
    val cachedTokens: Int = 0,
    val completionTokens: Int = 0,
    val elapsedMs: Long = 0,
    val transcript: List<TranscriptItem> = emptyList(),
)

data class SessionInfo(
    val id: String,
    val title: String,
)
