package dev.undefinedteam.wearagent.agent

/**
 * Live state of one model turn, streamed to the UI while it runs and returned
 * as the final value when it settles. Derived properties carry the semantics
 * consumers keep re-deriving, so the rules live in exactly one place.
 */
data class TurnState(
    val running: Boolean = false,
    val text: String = "",
    val reasoning: String = "",
    val error: String? = null,
    val tools: List<ToolActivity> = emptyList(),
) {
    /** A live bubble is visible while streaming and while a settled error is still on screen. */
    val live: Boolean get() = running || error != null

    /** Terminal failure: the turn settled and the error text replaces the body. */
    val failed: Boolean get() = !running && error != null

    /** Streaming with no body yet and every requested tool still pending. */
    val thinking: Boolean get() = running && text.isBlank() && tools.none { it.result == null }
}

/** One tool call as shown to the user: request plus its pending/error-marked result. */
data class ToolActivity(
    val call: ToolCall,
    val result: String? = null,
    val isError: Boolean = false,
)
