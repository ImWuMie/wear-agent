package dev.undefinedteam.wearagent.agent

import dev.undefinedteam.wearagent.agent.providers.AnthropicProvider
import dev.undefinedteam.wearagent.agent.providers.CompletionsProvider
import dev.undefinedteam.wearagent.agent.providers.ResponsesProvider

/** Minimal per-request config; a provider only ever needs these three. */
data class ModelRequest(val endpoint: String, val model: String, val apiKey: String)

/** One streamed increment; a single event may carry both text and reasoning. */
data class MessageDelta(val text: String = "", val reasoning: String = "")

/** Token accounting for one model round. */
data class TokensUsage(val prompt: Int, val cached: Int, val completion: Int)

/** One completed model round: content, reasoning, usage, and any tool calls. */
data class Message(
    val text: String = "",
    val reasoning: String = "",
    val tokens: TokensUsage? = null,
    val elapsedMs: Long = 0,
    val toolCalls: List<ToolCall> = emptyList(),
    /** Provider-native replay payload (opaque, protocol-specific). */
    val providerContent: String = "",
)


enum class EndpointKind {
    COMPLETIONS,
    RESPONSES,
    ANTHROPIC;

    companion object {
        fun from(value: String?): EndpointKind =
            entries.firstOrNull { it.name == value } ?: COMPLETIONS
    }
}

/**
 * A chat protocol. Switching endpoint kinds switches implementations — nothing
 * else in the app touches protocol specifics.
 */
interface ChatProvider {
    /**
     * Runs one streaming round. Calls [onDelta] incrementally and returns the
     * completed [Message] when the stream terminates cleanly. Throws
     * [java.io.IOException] on transport/protocol failure or cancellation.
     */
    fun stream(
        request: ModelRequest,
        history: List<TranscriptItem>,
        tools: List<ToolDefinition> = emptyList(),
        onDelta: (MessageDelta) -> Unit = {},
    ): Message

    /** Lists model ids available at the endpoint. */
    fun models(request: ModelRequest): List<String>

    /** Cancels the in-flight stream; subsequent/ongoing calls abort. */
    fun cancel()
}

/** Switching endpoint kind = switching implementation. */
fun ChatProvider(kind: EndpointKind): ChatProvider = when (kind) {
    EndpointKind.COMPLETIONS -> CompletionsProvider()
    EndpointKind.RESPONSES -> ResponsesProvider()
    EndpointKind.ANTHROPIC -> AnthropicProvider()
}