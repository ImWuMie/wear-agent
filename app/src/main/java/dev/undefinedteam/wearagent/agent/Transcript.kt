package dev.undefinedteam.wearagent.agent

data class ToolCall(val id: String, val name: String, val arguments: String)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameterSchema: String,
)

/** Provider history, including assistant tool requests and their matching results. */
sealed class TranscriptItem {
    data class Text(val userMessage: Boolean, val text: String) : TranscriptItem()

    data class Assistant(
        val text: String,
        val toolCalls: List<ToolCall> = emptyList(),
        val endpointKind: EndpointKind = EndpointKind.COMPLETIONS,
        val providerContent: String = "",
    ) : TranscriptItem()

    data class ToolResult(
        val callId: String,
        val content: String,
        val isError: Boolean = false,
    ) : TranscriptItem()
}
