package dev.undefinedteam.wearagent.agent

data class ToolCall(val id: String, val name: String, val arguments: String)

/**
 * An image attached to a message, carried as base64 so a session replays without the
 * original file. Each protocol renames [mediaType] to its own wire field.
 */
data class ImageAttachment(val mediaType: String, val base64: String)

/**
 * Media types every protocol accepts inline. Anything else is dropped rather than
 * sent: a rejected image fails the whole request, a missing one costs nothing.
 */
fun List<ImageAttachment>.supportedImages(): List<ImageAttachment> =
    filter { it.mediaType.lowercase() in SUPPORTED_IMAGE_TYPES }

private val SUPPORTED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameterSchema: String,
)

/** Provider history, including assistant tool requests and their matching results. */
sealed class TranscriptItem {
    data class Text(
        val userMessage: Boolean,
        val text: String,
        val images: List<ImageAttachment> = emptyList(),
    ) : TranscriptItem()

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
