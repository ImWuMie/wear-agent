package dev.undefinedteam.wearagent.session

import dev.undefinedteam.wearagent.agent.ToolDefinition
import dev.undefinedteam.wearagent.agent.TranscriptItem

/** What occupies a slice of the context window. */
enum class ContextKind { SYSTEM_PROMPT, SYSTEM_TOOLS, MESSAGES, FREE }

/** One slice of the context window, in tokens. */
data class ContextSegment(val kind: ContextKind, val tokens: Int)

/**
 * The context window [messages] would fill: the [systemPrompt], the tool schemas
 * the model is offered, the conversation itself, and whatever is left. Sizes
 * come from character counts; when the provider reports the prompt total the two
 * used slices are scaled to it so the bar agrees with the provider's own count.
 * [window] is the model's context window, which gives [ContextKind.FREE] its size.
 */
fun contextBreakdown(
    messages: List<ChatMessage>,
    tools: List<ToolDefinition>,
    systemPrompt: String = "",
    promptTokens: Int? = null,
    window: Int = DEFAULT_CONTEXT_WINDOW,
): List<ContextSegment> {
    var systemChars = 0
    tools.forEach {
        systemChars += it.name.length + it.description.length + it.parameterSchema.length
    }
    var messageChars = 0
    expandHistory(messages).forEach { item ->
        when (item) {
            is TranscriptItem.Text -> messageChars += item.text.length + item.images.sumOf { IMAGE_CHARS }

            is TranscriptItem.Assistant -> {
                messageChars += item.text.length
                item.toolCalls.forEach { messageChars += it.name.length + it.arguments.length }
            }

            is TranscriptItem.ToolResult -> messageChars += item.content.length
        }
    }
    val estimated = listOf(
        ContextSegment(ContextKind.SYSTEM_PROMPT, charsToTokens(systemPrompt.length)),
        ContextSegment(ContextKind.SYSTEM_TOOLS, charsToTokens(systemChars)),
        ContextSegment(ContextKind.MESSAGES, charsToTokens(messageChars)),
    )
    // Scale the two used slices to the reported prompt size, then add the rest of
    // the window as free space. A window smaller than the prompt leaves no free
    // space, which is exactly what the bar should show.
    val used = promptTokens?.takeIf { it > 0 }?.let { scaleToTotal(estimated, it) } ?: estimated
    val free = (window - used.sumOf { it.tokens }).coerceAtLeast(0)
    return used + ContextSegment(ContextKind.FREE, free)
}

/** Rough token count for a character count; ~4 characters per token. */
private fun charsToTokens(chars: Int): Int = if (chars <= 0) 0 else (chars + 3) / 4

/**
 * Characters attributed to one attached image. Providers bill an inline image
 * by its dimensions, not its payload, so the base64 length would grossly
 * overstate the cost; this is a deliberately rough, fixed estimate.
 */
private const val IMAGE_CHARS = 1000

/**
 * Scales [segments] so they sum to [total], keeping their proportions. Rounding
 * drift is folded into the largest slice so the sum is exact.
 */
private fun scaleToTotal(segments: List<ContextSegment>, total: Int): List<ContextSegment> {
    val estimated = segments.sumOf { it.tokens }
    if (estimated <= 0 || total <= 0) return segments
    val scaled = segments.map { ContextSegment(it.kind, it.tokens * total / estimated) }
    val drift = total - scaled.sumOf { it.tokens }
    if (drift == 0) return scaled
    val largest = scaled.indices.maxByOrNull { scaled[it].tokens } ?: return scaled
    return scaled.toMutableList().also {
        it[largest] = ContextSegment(scaled[largest].kind, scaled[largest].tokens + drift)
    }
}
