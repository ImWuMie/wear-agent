package dev.undefinedteam.wearagent.session

import dev.undefinedteam.wearagent.agent.ImageAttachment
import dev.undefinedteam.wearagent.agent.ToolCall
import dev.undefinedteam.wearagent.agent.ToolDefinition
import dev.undefinedteam.wearagent.agent.TranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBreakdownTest {
    private fun tool(name: String, description: String = "", schema: String = "") =
        ToolDefinition(name, description, schema)

    private fun user(id: Long, text: String) = ChatMessage(id, true, text)

    private fun turn(id: Long, text: String, transcript: List<TranscriptItem> = emptyList()) =
        ChatMessage(id, false, text, transcript = transcript)

    private fun tokensOf(segments: List<ContextSegment>, kind: ContextKind) =
        segments.first { it.kind == kind }.tokens

    @Test fun everyKindIsCounted() {
        val segments = contextBreakdown(
            listOf(
                user(1, "a".repeat(40)),
                turn(
                    2, "b".repeat(80),
                    listOf(
                        TranscriptItem.Assistant(
                            "b".repeat(80),
                            listOf(ToolCall("c1", "web_search", "{\"query\":\"x\"}")),
                        ),
                        TranscriptItem.ToolResult("c1", "r".repeat(120)),
                    ),
                ),
            ),
            listOf(tool("fetch", "d".repeat(40), "s".repeat(40))),
            systemPrompt = "p".repeat(40),
        )
        assertEquals(
            listOf(
                ContextKind.SYSTEM_PROMPT,
                ContextKind.SYSTEM_TOOLS,
                ContextKind.MESSAGES,
                ContextKind.FREE,
            ),
            segments.map { it.kind },
        )
        assertTrue(tokensOf(segments, ContextKind.SYSTEM_PROMPT) > 0)
        assertTrue(tokensOf(segments, ContextKind.SYSTEM_TOOLS) > 0)
        assertTrue(tokensOf(segments, ContextKind.MESSAGES) > 0)
        assertTrue(tokensOf(segments, ContextKind.FREE) > 0)
    }

    @Test fun usedSlicesScaleToTheReportedPromptAndTheRestIsFree() {
        val segments = contextBreakdown(
            listOf(user(1, "a".repeat(400)), turn(2, "b".repeat(400))),
            emptyList(),
            systemPrompt = "s".repeat(40),
            promptTokens = 1000,
            window = 8000,
        )
        // The two used slices sum to the reported prompt; free space is the rest.
        assertEquals(1000, segments.filter { it.kind != ContextKind.FREE }.sumOf { it.tokens })
        assertEquals(7000, tokensOf(segments, ContextKind.FREE))
    }

    @Test fun slicesAlwaysSumToTheWindow() {
        val segments = contextBreakdown(
            listOf(user(1, "hello")),
            listOf(tool("fetch", "d".repeat(400))),
            systemPrompt = "s".repeat(40),
            promptTokens = 500,
            window = 128_000,
        )
        assertEquals(128_000, segments.sumOf { it.tokens })
    }

    @Test fun aPromptFillingTheWindowLeavesNoFreeSpace() {
        val segments = contextBreakdown(
            listOf(user(1, "a".repeat(400))),
            emptyList(),
            promptTokens = 10_000,
            window = 8_000,
        )
        assertEquals(0, tokensOf(segments, ContextKind.FREE))
    }

    @Test fun attachedImagesCountTowardTheMessageSlice() {
        val withImage = contextBreakdown(
            listOf(ChatMessage(1, true, "hi", images = listOf(ImageAttachment("image/png", "AAAA")))),
            emptyList(),
            window = 32_000,
        )
        val withoutImage = contextBreakdown(listOf(user(1, "hi")), emptyList(), window = 32_000)
        assertTrue(
            tokensOf(withImage, ContextKind.MESSAGES) > tokensOf(withoutImage, ContextKind.MESSAGES),
        )
        assertEquals(32_000, withImage.sumOf { it.tokens })
    }

    @Test fun emptyInputIsAllFreeSpace() {
        val segments = contextBreakdown(emptyList(), emptyList(), window = 32_000)
        assertEquals(32_000, tokensOf(segments, ContextKind.FREE))
        assertEquals(0, tokensOf(segments, ContextKind.MESSAGES))
        assertEquals(0, tokensOf(segments, ContextKind.SYSTEM_TOOLS))
        assertEquals(0, tokensOf(segments, ContextKind.SYSTEM_PROMPT))
    }
}
