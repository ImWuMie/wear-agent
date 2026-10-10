package dev.undefinedteam.wearagent.agent

data class TurnState(
    val running: Boolean = false,
    val text: String = "",
    val reasoning: String = "",
    val error: String? = null,
    val tools: List<ToolActivity> = emptyList(),
)

data class ToolActivity(
    val call: ToolCall,
    val result: String? = null,
    val isError: Boolean = false,
)
