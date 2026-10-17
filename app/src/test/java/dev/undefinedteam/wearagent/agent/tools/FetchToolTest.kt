package dev.undefinedteam.wearagent.agent.tools

import dev.undefinedteam.wearagent.agent.ToolCall
import dev.undefinedteam.wearagent.agent.tools.web.FetchTool
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class FetchToolTest {
    private lateinit var server: MockWebServer
    private lateinit var tool: FetchTool
    private lateinit var endpoint: String

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        endpoint = server.url("/resource").toString()
        tool = FetchTool()
    }

    @After fun tearDown() {
        tool.cancel()
        server.shutdown()
    }

    @Test fun sendsCustomMethodHeadersAndBodyAndReturnsStatusAndContent() {
        server.enqueue(
            MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true}""")
        )
        val result = tool.execute(
            call(
                JSONObject()
                    .put("url", endpoint)
                    .put("method", "POST")
                    .put("headers", JSONObject().put("X-Token", "abc").put("Accept", "application/json"))
                    .put("body", """{"query":"wear"}""")
                    .put("content_type", "application/json")
            )
        )

        assertFalse(result.isError)
        assertTrue(result.content.startsWith("HTTP 201"))
        assertTrue(result.content.contains("Content-Type: application/json"))
        assertTrue(result.content.contains("""{"ok":true}"""))

        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/resource", request.path)
        assertEquals("abc", request.getHeader("X-Token"))
        assertEquals("application/json", request.getHeader("Accept"))
        assertEquals("""{"query":"wear"}""", request.body.readUtf8())
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test fun getIgnoresBodyAndDefaultsToGet() {
        server.enqueue(MockResponse().setBody("hello"))
        val result = tool.execute(call(JSONObject().put("url", endpoint).put("body", "ignored")))
        assertFalse(result.isError)
        assertTrue(result.content.contains("hello"))
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("", request.body.readUtf8())
    }

    @Test fun errorStatusIsReportedNotTreatedAsFailure() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
        val result = tool.execute(call(JSONObject().put("url", endpoint)))
        assertFalse(result.isError)
        assertTrue(result.content.startsWith("HTTP 404"))
        assertTrue(result.content.contains("missing"))
    }

    @Test fun invalidArgumentsFailWithoutNetworkRequests() {
        val invalid = listOf(
            "not json", "[]", "{}", """{"url":""}""", """{"url":"ftp://x"}""",
            """{"url":"https://x","method":"TRACE"}""",
            """{"url":"https://x","headers":"nope"}""",
            """{"url":"https://x","headers":{"Host":"evil"}}""",
            """{"url":"https://x","headers":{"X":"${"v".repeat(4097)}"}}""",
            """{"url":"https://x","body":42}""",
            """{"url":"https://x"} trailing""",
        )
        for (arguments in invalid) {
            val result = tool.execute(ToolCall("call-1", "fetch", arguments))
            assertTrue(arguments, result.isError)
            assertTrue(result.content.startsWith("Error: "))
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun responseSizeCapRejectsOversizeInsteadOfBuffering() {
        server.enqueue(MockResponse().setBody("x".repeat(256 * 1024 + 1)))
        val result = tool.execute(call(JSONObject().put("url", endpoint)))
        assertTrue(result.isError)
        assertTrue(result.content.contains("256 KiB"))
    }

    @Test fun redirectIsNotFollowedAndIsVisibleToTheModel() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", endpoint))
        val result = tool.execute(call(JSONObject().put("url", endpoint)))
        assertFalse(result.isError)
        assertTrue(result.content.startsWith("HTTP 302"))
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationBeforeExecutionIsStickyAndMakesNoRequest() {
        tool.cancel()
        try {
            tool.execute(call(JSONObject().put("url", endpoint)))
            org.junit.Assert.fail("Expected InterruptedIOException")
        } catch (_: java.io.InterruptedIOException) {
            // Expected: cancellation never becomes a model-facing result.
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun injectedClientTimeoutIsBoundedAndFailureIsReported() {
        val slow = FetchTool(OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build())
        try {
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val result = slow.execute(call(JSONObject().put("url", endpoint)))
            assertTrue(result.isError)
        } finally {
            slow.cancel()
        }
    }

    private fun call(arguments: JSONObject) = ToolCall("call-1", "fetch", arguments.toString())
}
