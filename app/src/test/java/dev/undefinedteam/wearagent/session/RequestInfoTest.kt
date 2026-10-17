package dev.undefinedteam.wearagent.session

import dev.undefinedteam.wearagent.agent.EndpointKind
import dev.undefinedteam.wearagent.agent.ToolCall
import dev.undefinedteam.wearagent.agent.TranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestInfoTest {
    private fun user(id: Long, text: String) = ChatMessage(id, true, text)

    private fun turn(id: Long, text: String, transcript: List<TranscriptItem>, ms: Long = 0) =
        ChatMessage(id, false, text, elapsedMs = ms, transcript = transcript)

    private fun call(id: String, name: String, arguments: String) = ToolCall(id, name, arguments)

    @Test fun assistantLinesReplayTheirTranscriptAndOthersBecomeText() {
        val history = expandHistory(
            listOf(
                user(1, "hi"),
                turn(
                    2, "answer",
                    listOf(
                        TranscriptItem.Assistant("", listOf(call("c1", "web_search", """{"query":"x"}"""))),
                        TranscriptItem.ToolResult("c1", "source"),
                    ),
                ),
            )
        )
        assertEquals(3, history.size)
        assertEquals(TranscriptItem.Text(true, "hi"), history[0])
        assertTrue(history[1] is TranscriptItem.Assistant)
        assertEquals("source", (history[2] as TranscriptItem.ToolResult).content)
    }

    @Test fun legacyAssistantWithoutTranscriptBecomesPlainText() {
        val history = expandHistory(listOf(user(1, "hi"), ChatMessage(2, false, "answer")))
        assertEquals(
            listOf(TranscriptItem.Text(true, "hi"), TranscriptItem.Text(false, "answer")),
            history,
        )
    }

    @Test fun trajectoryPairsCallWithItsResultInOneRow() {
        val entries = describeRequest(
            listOf(
                user(1, "搜一下"),
                turn(
                    2, "结果如下",
                    listOf(
                        TranscriptItem.Assistant(
                            "", listOf(call("c1", "web_search", """{"query":"wear os"}"""))
                        ),
                        TranscriptItem.ToolResult("c1", "[1] Wear OS docs"),
                    ),
                ),
            )
        )
        assertEquals(listOf(RequestRole.USER, RequestRole.ASSISTANT, RequestRole.TOOL), entries.map { it.role })
        val tool = entries.last()
        assertEquals("web_search", tool.name)
        assertEquals(RequestStatus.COMPLETED, tool.status)
        assertTrue(tool.preview.contains("wear os"))
        assertTrue(tool.preview.contains("→"))
        assertTrue(tool.preview.contains("Wear OS docs"))
        assertEquals("""{"query":"wear os"}""", tool.payload)
        assertEquals("[1] Wear OS docs", tool.result)
    }

    @Test fun failedAndPendingCallsAreDistinguished() {
        val entries = describeRequest(
            listOf(
                turn(
                    1, "",
                    listOf(
                        TranscriptItem.Assistant(
                            "",
                            listOf(
                                call("c1", "fetch", """{"url":"https://a"}"""),
                                call("c2", "fetch", """{"url":"https://b"}"""),
                            ),
                        ),
                        TranscriptItem.ToolResult("c1", "Error: boom", isError = true),
                    ),
                ),
            )
        )
        assertEquals(RequestStatus.FAILED, entries[0].status)
        assertEquals(RequestStatus.PENDING, entries[1].status)
        assertTrue(entries[1].preview.endsWith("→ …"))
        assertEquals("", entries[1].result)
    }

    @Test fun aggregateAssistantTextIsNotDuplicated() {
        // A turn with no tool calls stores its text both as the message aggregate
        // and as the transcript's assistant item; only one row may be emitted.
        val entries = describeRequest(
            listOf(
                user(1, "写个矩阵"),
                turn(2, "可以", listOf(TranscriptItem.Assistant("可以"))),
            )
        )
        assertEquals(
            listOf(RequestRole.USER, RequestRole.ASSISTANT),
            entries.map { it.role },
        )
        assertEquals("可以", entries[1].preview)
    }

    @Test fun assistantAndToolRowsJumpToTheirTurnUserMessage() {
        val entries = describeRequest(
            listOf(
                user(7, "搜一下"),
                turn(
                    8, "结果如下",
                    listOf(
                        TranscriptItem.Assistant(
                            "先查",
                            listOf(call("c1", "web_search", "{\"query\":\"x\"}")),
                        ),
                        TranscriptItem.ToolResult("c1", "ok"),
                    ),
                ),
            )
        )
        assertEquals(listOf(RequestRole.USER, RequestRole.ASSISTANT, RequestRole.TOOL), entries.map { it.role })
        assertEquals(7L, entries[0].messageId)
        assertEquals(7L, entries[1].messageId)
        assertEquals(7L, entries[2].messageId)
    }

    @Test fun previewIsASingleTruncatedLine() {
        val long = "x".repeat(200)
        val entries = describeRequest(listOf(user(1, "a\nb\tc $long")))
        assertEquals(1, entries.size)
        assertFalse(entries[0].preview.contains('\n'))
        assertTrue(entries[0].preview.length <= 80)
        assertTrue(entries[0].preview.endsWith("…"))
        // The full text stays available for the expanded payload.
        assertTrue(entries[0].payload.contains(long))
    }

    @Test fun rewindDropsTheMessageAndEverythingAfter() {
        val all = listOf(user(1, "a"), ChatMessage(2, false, "b"), user(3, "c"))
        val kept = all.takeWhile { it.id != 3L }
        assertEquals(listOf(1L, 2L), kept.map { it.id })
    }
}
