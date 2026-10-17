package dev.undefinedteam.wearagent.agent.tools

import dev.undefinedteam.wearagent.agent.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolSummaryTest {
    private fun call(name: String, arguments: String) = ToolCall("id", name, arguments)

    @Test fun fetchSummaryIsMethodAndUrl() {
        assertEquals(
            "POST https://api.example.com/v1/items",
            toolSummary(call("fetch", """{"url":"https://api.example.com/v1/items","method":"POST"}""")),
        )
        assertEquals(
            "GET https://example.com/a",
            toolSummary(call("fetch", """{"url":"https://example.com/a"}""")),
        )
    }

    @Test fun searchSummaryIsTheQuery() {
        assertEquals("wear os docs", toolSummary(call("web_search", """{"query":"wear os docs","limit":3}""")))
    }

    @Test fun malformedArgumentsFallBackToRawArgumentsThenName() {
        // A fetch call whose arguments do not parse shows the raw arguments.
        assertEquals("not json", toolSummary(call("fetch", "not json")))
        // A call whose arguments parse but lack a url also falls back.
        assertEquals("""{"method":"POST"}""", toolSummary(call("fetch", """{"method":"POST"}""")))
        // An unknown tool has no owner, so the raw arguments show; blank shows the name.
        assertEquals("not json", toolSummary(call("mystery", "not json")))
        assertEquals("mystery", toolSummary(call("mystery", "")))
    }
}
