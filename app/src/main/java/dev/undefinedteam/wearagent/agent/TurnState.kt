package dev.undefinedteam.wearagent.agent

data class TurnState(
    val running: Boolean = false,
    val text: String = "",
    val reasoning: String = "",
    val error: String? = null,
)
