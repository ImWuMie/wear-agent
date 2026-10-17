package dev.undefinedteam.wearagent.agent

data class ToolCall(val id: String, val name: String, val arguments: String)

/**
 * One image the user attached to a message, carried as base64 so a session
 * replays without the original file. [mediaType] is the IANA type; each protocol
 * renames it to its own wire field.
 */
data class ImageAttachment(val mediaType: String, val base64: String)

/**
 * Media types every supported protocol accepts inline (jpeg/png/gif/webp).
 * Anything else is dropped rather than sent: a rejected image would fail the
 * whole request, while a missing one only costs that attachment.
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
