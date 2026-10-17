package dev.undefinedteam.wearagent.session

import dev.undefinedteam.wearagent.agent.TranscriptItem

/** Who produced a request entry, for the trajectory row's role chip. */
enum class RequestRole { USER, ASSISTANT, TOOL }

/** How a tool call ended, localized by the UI. */
enum class RequestStatus { COMPLETED, FAILED, PENDING }

/**
 * One row of the request trajectory: a role, an optional tool name, a one-line
 * preview (`args → result` for tools), and the full payload/result revealed on
 * expansion. Kept as plain data so the view only has to render it.
 */
data class RequestEntry(
    val role: RequestRole,
    val name: String = "",
    val preview: String,
    val payload: String = "",
    val result: String = "",
    val status: RequestStatus? = null,
    val elapsedMs: Long = 0,
    /** Attached image count for a user row; 0 when there are none. */
    val images: Int = 0,
    /**
     * Chat message this row jumps back to: the row's own message for a user line,
     * and the user message that started the turn for an assistant or tool line.
     */
    val messageId: Long = 0,
)

/**
 * The provider history a turn sends for [messages]: an assistant line replays its
 * recorded transcript, every other line becomes a plain text item. Shared by the
 * service (to run the turn) and the request view (to show what was sent).
 */
fun expandHistory(messages: List<ChatMessage>): List<TranscriptItem> = buildList {
    for (message in messages) {
        if (!message.userMessage && message.transcript.isNotEmpty()) {
            addAll(message.transcript)
        } else {
            add(TranscriptItem.Text(message.userMessage, message.text, message.images))
        }
    }
}

/**
 * The trajectory of [messages]: every user line, assistant line, and tool call
 * with its paired result, in the order the model saw them. Tool calls are matched
 * to their results by id, so a call and its outcome form one row.
 */
fun describeRequest(messages: List<ChatMessage>): List<RequestEntry> = buildList {
    // Id of the user message that started the turn in progress; assistant and tool
    // rows jump back to it rather than to the assistant message itself.
    var turnUserId = 0L
    messages.forEach { message ->
        if (message.userMessage) turnUserId = message.id
        if (message.transcript.isEmpty()) {
            add(
                RequestEntry(
                    if (message.userMessage) RequestRole.USER else RequestRole.ASSISTANT,
                    preview = oneLine(message.text),
                    payload = message.text,
                    elapsedMs = message.elapsedMs,
                    images = message.images.size,
                    messageId = if (message.userMessage) message.id else turnUserId,
                )
            )
            return@forEach
        }
        val results = message.transcript
            .filterIsInstance<TranscriptItem.ToolResult>()
            .associateBy { it.callId }
        // Each round's assistant text already lives in the transcript, so the
        // message-level aggregate is emitted only for legacy data whose transcript
        // carries no assistant text. Emitting both duplicated a single round.
        val transcriptHasAssistantText = message.transcript.any {
            (it is TranscriptItem.Assistant && it.text.isNotBlank()) ||
                (it is TranscriptItem.Text && !it.userMessage && it.text.isNotBlank())
        }
        if (message.text.isNotBlank() && !transcriptHasAssistantText) {
            add(
                RequestEntry(
                    RequestRole.ASSISTANT,
                    preview = oneLine(message.text),
                    payload = message.text,
                    elapsedMs = message.elapsedMs,
                    messageId = turnUserId,
                )
            )
        }
        message.transcript.forEach { item ->
            when (item) {
                is TranscriptItem.Text -> add(
                    RequestEntry(
                        if (item.userMessage) RequestRole.USER else RequestRole.ASSISTANT,
                        preview = oneLine(item.text),
                        payload = item.text,
                        images = item.images.size,
                        messageId = if (item.userMessage) message.id else turnUserId,
                    )
                )

                is TranscriptItem.Assistant -> {
                    if (item.text.isNotBlank()) {
                        add(
                            RequestEntry(
                                RequestRole.ASSISTANT,
                                preview = oneLine(item.text),
                                payload = item.text,
                                elapsedMs = message.elapsedMs,
                                messageId = turnUserId,
                            )
                        )
                    }
                    item.toolCalls.forEach { call ->
                        val outcome = results[call.id]
                        add(
                            RequestEntry(
                                role = RequestRole.TOOL,
                                name = call.name,
                                preview = toolPreview(call.name, call.arguments, outcome?.content),
                                payload = call.arguments,
                                result = outcome?.content.orEmpty(),
                                status = when {
                                    outcome == null -> RequestStatus.PENDING
                                    outcome.isError -> RequestStatus.FAILED
                                    else -> RequestStatus.COMPLETED
                                },
                                messageId = turnUserId,
                            )
                        )
                    }
                }

                is TranscriptItem.ToolResult -> Unit // shown on its call's row
            }
        }
    }
}

/** `name {"args"} → result`, the one-line trajectory preview. */
private fun toolPreview(name: String, arguments: String, result: String?): String = buildString {
    append(name)
    append(' ')
    append(oneLine(arguments, MAX_PREVIEW))
    append(" → ")
    append(result?.let { oneLine(it, MAX_PREVIEW) } ?: "…")
}

private fun oneLine(text: String, limit: Int = MAX_PREVIEW): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= limit) flat else flat.take(limit - 1) + "…"
}

private const val MAX_PREVIEW = 80
