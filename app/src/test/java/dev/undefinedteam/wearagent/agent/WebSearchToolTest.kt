package dev.undefinedteam.wearagent.agent

import dev.undefinedteam.wearagent.agent.tools.WebSearchTool
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.InterruptedIOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WebSearchToolTest {
    private lateinit var server: MockWebServer
    private lateinit var tool: WebSearchTool
    private lateinit var endpoint: String

    @Before fun setUp() {
        server = MockWebServer()
        server.start(java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        // The fixture listens on IPv4, not localhost's unrelated IPv6 route.
        endpoint = server.url("/mcp").newBuilder().host("127.0.0.1").build().toString()
        tool = WebSearchTool(endpoint = endpoint)
    }

    @After fun tearDown() {
        tool.cancel()
        server.shutdown()
    }

    @Test fun publicMcpRequestPreservesOperatorsAndReturnsStructuredSources() {
        enqueueResult(JSONObject().put("structuredContent", payload(2)))
        val query = "site:developer.android.com \"Wear OS\" -obsolete OR watch"
        val result = tool.execute(call(JSONObject().put("query", query).put("limit", 2)))

        assertFalse(result.isError)
        assertEquals("call-1", result.callId)
        assertEquals(
            "[1] Official source 1\nURL: https://developer.android.com/topic/1\nPublished: 2026-10-01\nSnippet: Authentic excerpt 1\n\n" +
                "[2] Official source 2\nURL: https://developer.android.com/topic/2\nPublished: 2026-10-01\nSnippet: Authentic excerpt 2",
            result.content,
        )
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/mcp", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("application/json, text/event-stream", request.getHeader("Accept"))
        assertEquals("WearAgent", request.getHeader("User-Agent"))
        assertEquals(null, request.getHeader("Authorization"))
        assertEquals(null, request.getHeader("x-api-key"))
        val rpc = JSONObject(request.body.readUtf8())
        assertEquals("2.0", rpc.getString("jsonrpc"))
        assertEquals("call-1", rpc.getString("id"))
        assertEquals("tools/call", rpc.getString("method"))
        val params = rpc.getJSONObject("params")
        assertEquals("web_search", params.getString("name"))
        val arguments = params.getJSONObject("arguments")
        assertEquals(query, arguments.getString("objective"))
        assertEquals(query, arguments.getJSONArray("search_queries").getString(0))
        assertEquals(2, arguments.length())
    }

    @Test fun allRecencyOptionsAppendUtcDateAndDoNotAlterObjective() {
        val days = mapOf("day" to 1L, "week" to 7L, "month" to 30L, "year" to 365L)
        for ((recency, distance) in days) {
            enqueueResult(JSONObject().put("structuredContent", payload(0)))
            val before = LocalDate.now(ZoneOffset.UTC).minusDays(distance)
            val result = tool.execute(call(JSONObject().put("query", "site:example.com news").put("recency", recency)))
            val after = LocalDate.now(ZoneOffset.UTC).minusDays(distance)
            assertFalse(result.isError)
            val arguments = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
                .getJSONObject("params").getJSONObject("arguments")
            assertEquals("site:example.com news", arguments.getString("objective"))
            val query = arguments.getJSONArray("search_queries").getString(0)
            assertTrue(query == "site:example.com news after:$before" || query == "site:example.com news after:$after")
        }
    }

    @Test fun explicitAfterOperatorWinsOverRecency() {
        enqueueResult(JSONObject().put("structuredContent", payload(0)))
        val query = "news after:2025-01-01 before:2026-01-01"
        assertFalse(tool.execute(call(JSONObject().put("query", query).put("recency", "day"))).isError)
        val arguments = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            .getJSONObject("params").getJSONObject("arguments")
        assertEquals(query, arguments.getJSONArray("search_queries").getString(0))
    }

    @Test fun textPayloadParsesAfterNonJsonContentAndUsesUrlForMissingTitle() {
        val sources = JSONArray().put(JSONObject().put("title", "No URL"))
            .put(JSONObject().put("url", "https://example.com/authentic")
                .put("excerpts", JSONArray().put(" first excerpt ").put(42).put("second excerpt")))
        val content = JSONArray().put(JSONObject().put("type", "image").put("text", "not text"))
            .put(JSONObject().put("type", "text").put("text", "Search completed"))
            .put(JSONObject().put("type", "text").put("text", JSONObject().put("results", sources).toString()))
        enqueueResult(JSONObject().put("content", content))
        val result = tool.execute(call())
        assertFalse(result.isError)
        assertEquals(
            "[1] https://example.com/authentic\nURL: https://example.com/authentic\nSnippet: first excerpt\n\nsecond excerpt",
            result.content,
        )
    }

    @Test fun structuredPayloadTakesPrecedenceOverTextPayload() {
        enqueueResult(JSONObject().put("structuredContent", payload(1))
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", payload(2).toString()))))
        val result = tool.execute(call())
        assertFalse(result.isError)
        assertTrue(result.content.contains("Authentic excerpt 1"))
        assertFalse(result.content.contains("Authentic excerpt 2"))
    }

    @Test fun sseCombinesMultilineDataSkipsNotificationsAndReturnsBeforeConnectionCloses() {
        val resultEvent = "data: {\"jsonrpc\":\"2.0\",\"id\":\"call-1\",\n" +
            "data: \"result\":${JSONObject().put("structuredContent", payload(1))}}\n\n"
        val events = "\uFEFF: keepalive\r\n\r\n" +
            "event: message\r\nid: 1\r\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\r\n\r\n" +
            "data: ${rpc(JSONObject().put("structuredContent", payload(0)), "other-call")}\n\n" + resultEvent
        // Deliberately promise more body bytes than are sent: waiting for EOF would time out.
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(events).setHeader("Content-Length", events.toByteArray(Charsets.UTF_8).size + 100))
        val client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
        val search = WebSearchTool(client, endpoint)
        val result = search.execute(call())
        assertFalse(result.isError)
        assertTrue(result.content.contains("https://developer.android.com/topic/1"))
    }

    @Test fun sseParsesFinalEventWithoutBlankLineAndJsonBatchSelectsMatchingId() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: ${rpc(JSONObject().put("structuredContent", payload(1)))}"))
        assertFalse(tool.execute(call()).isError)
        val batch = JSONArray().put(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/progress"))
            .put(rpc(JSONObject().put("structuredContent", payload(0)), "other"))
            .put(rpc(JSONObject().put("structuredContent", payload(1))))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(batch.toString()))
        assertFalse(tool.execute(call()).isError)
    }

    @Test fun defaultAndExplicitResultLimitsBoundNumberedOutputAndSnippets() {
        val sources = payload(12)
        sources.getJSONArray("results").getJSONObject(0).put("title", "t".repeat(300))
            .put("excerpts", JSONArray().put("x".repeat(700)).put("must not be included"))
        for (limit in listOf(null, 1, 10)) {
            enqueueResult(JSONObject().put("structuredContent", sources))
            val args = JSONObject().put("query", "query")
            if (limit != null) args.put("limit", limit)
            val result = tool.execute(call(args))
            assertFalse(result.isError)
            val expected = limit ?: 5
            assertEquals(expected, Regex("(?m)^\\[\\d+] ").findAll(result.content).count())
            assertTrue(result.content.startsWith("[1] ${"t".repeat(200)}\n"))
            assertTrue(result.content.contains("Snippet: ${"x".repeat(500)}"))
            assertFalse(result.content.contains("x".repeat(501)))
            assertFalse(result.content.contains("must not be included"))
            assertFalse(result.content.contains("[${expected + 1}]"))
        }
    }

    @Test fun absentExcerptIsHonestAndEmptyResultsAreNotErrors() {
        val noExcerpt = JSONObject().put("results", JSONArray().put(JSONObject().put("url", "https://example.com")))
        enqueueResult(JSONObject().put("structuredContent", noExcerpt))
        val source = tool.execute(call())
        assertFalse(source.isError)
        assertTrue(source.content.endsWith("Snippet: (No excerpt provided.)"))
        for (empty in listOf(
            JSONObject().put("structuredContent", payload(0)),
            JSONObject().put("content", JSONArray()),
            JSONObject().put("structuredContent", JSONObject().put("results", JSONArray().put(JSONObject().put("title", "No URL")))),
        )) {
            enqueueResult(empty)
            val result = tool.execute(call())
            assertFalse(result.isError)
            assertEquals("No web search results found.", result.content)
        }
    }

    @Test fun invalidArgumentsAndUnknownToolDoNotMakeNetworkRequests() {
        val invalid = listOf(
            "not json", "[]", "{}", "{\"query\":null}", "{\"query\":42}", "{\"query\":\"  \\n\\t\"}",
            "{\"query\":\"ok\",\"limit\":0}", "{\"query\":\"ok\",\"limit\":11}",
            "{\"query\":\"ok\",\"limit\":1.5}", "{\"query\":\"ok\",\"limit\":\"5\"}",
            "{\"query\":\"ok\",\"limit\":null}", "{\"query\":\"ok\",\"recency\":\"hour\"}",
            "{\"query\":\"ok\",\"recency\":null}", "{\"query\":\"ok\",\"recency\":1}",
            JSONObject().put("query", "x".repeat(4097)).toString(),
            " ".repeat(16385), "{\"query\":\"ok\"} trailing garbage",
        )
        for (arguments in invalid) assertFailure(tool.execute(ToolCall("invalid-id", "web_search", arguments)), "invalid-id")
        assertFailure(tool.execute(ToolCall("unknown-id", "other_tool", "{}")), "unknown-id")
        assertEquals(0, server.requestCount)
    }

    @Test fun maximumQueryAndArgumentSizesAreAccepted() {
        val query = "q".repeat(4096)
        val arguments = JSONObject().put("query", query).toString().padEnd(16384, ' ')
        enqueueResult(JSONObject().put("structuredContent", payload(1)))
        val result = tool.execute(ToolCall("call-1", "web_search", arguments))
        assertFalse(result.isError)
        val sent = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            .getJSONObject("params").getJSONObject("arguments")
        assertEquals(query, sent.getString("objective"))
    }

    @Test fun httpJsonRpcAndToolErrorsAreHonestModelFailuresWithoutRetries() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limit"))
        assertTrue(tool.execute(call()).content.contains("HTTP 429"))
        server.enqueue(MockResponse().setBody(JSONObject().put("jsonrpc", "2.0").put("id", "call-1")
            .put("error", JSONObject().put("code", -32603).put("message", "Search unavailable")).toString()))
        val rpcError = tool.execute(call())
        assertFailure(rpcError)
        assertTrue(rpcError.content.contains("-32603"))
        assertTrue(rpcError.content.contains("Search unavailable"))
        enqueueResult(JSONObject().put("isError", true).put("structuredContent", payload(1))
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Public search is busy"))))
        val toolError = tool.execute(call())
        assertFailure(toolError)
        assertTrue(toolError.content.contains("Public search is busy"))
        enqueueResult(JSONObject().put("isError", true))
        assertFailure(tool.execute(call()))
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
        assertFailure(tool.execute(call()))
        assertEquals(5, server.requestCount)
    }

    @Test fun malformedOrUnmatchedResponsesNeverMasqueradeAsNoResults() {
        val malformed = listOf(
            "not JSON", "{}", "[]", "{\"jsonrpc\":\"2.0\",\"id\":\"call-1\"}",
            rpc(JSONObject().put("structuredContent", payload(1)), "wrong-id").toString(),
            rpc(JSONObject().put("structuredContent", payload(1))).put("jsonrpc", "1.0").toString(),
            rpc(JSONObject().put("structuredContent", payload(1))).put("error", JSONObject()).toString(),
            JSONObject().put("jsonrpc", "2.0").put("id", "call-1").put("error", JSONObject().put("message", "no code")).toString(),
            rpc(JSONObject().put("structuredContent", "not object")).toString(),
            rpc(JSONObject().put("structuredContent", JSONObject())).toString(),
            rpc(JSONObject().put("structuredContent", JSONObject().put("results", "not an array"))).toString(),
            rpc(JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "plain prose")))).toString(),
            rpc(JSONObject()).toString(),
            "{\"jsonrpc\":\"2.0\",\"id\":\"call-1\",\"result\":null}",
            rpc(JSONObject().put("structuredContent", payload(0))).toString() + " trailing",
        )
        for (body in malformed) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
            val result = tool.execute(call())
            assertFailure(result)
            assertFalse(result.content.contains("No web search results found."))
        }
        for (events in listOf("data: not-json\n\n", "data: [DONE]\n\n", ": only a heartbeat\n\n",
            "data: ${rpc(JSONObject().put("structuredContent", payload(1)), "wrong")}\n\n")) {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events))
            assertFailure(tool.execute(call()))
        }
    }

    @Test fun responseSizeCapAcceptsExactBoundaryAndRejectsDeclaredAndChunkedOversize() {
        val base = rpc(JSONObject().put("structuredContent", payload(1))).toString()
        val maximum = 512 * 1024
        server.enqueue(MockResponse().setBody(base.padEnd(maximum, ' ')))
        assertFalse(tool.execute(call()).isError)
        server.enqueue(MockResponse().setBody(base.padEnd(maximum + 1, ' ')))
        val declared = tool.execute(call())
        assertFailure(declared)
        assertTrue(declared.content.contains("512 KiB"))
        server.enqueue(MockResponse().setChunkedBody(base.padEnd(maximum + 1, ' '), 4096))
        val chunked = tool.execute(call())
        assertFailure(chunked)
        assertTrue(chunked.content.contains("512 KiB"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setChunkedBody(": " + "x".repeat(maximum) + "\n\n", 4096))
        assertFailure(tool.execute(call()))
    }

    @Test fun oversizedSourceUrlFailsRatherThanInventingTruncatedCitation() {
        val sources = JSONObject().put("results", JSONArray().put(JSONObject()
            .put("url", "https://example.com/" + "x".repeat(2048)).put("excerpts", JSONArray().put("excerpt"))))
        enqueueResult(JSONObject().put("structuredContent", sources))
        assertFailure(tool.execute(call()))
    }

    @Test fun finiteTimeoutReturnsFailureAndDoesNotMakeCancellationSticky() {
        val http = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
        val search = WebSearchTool(http, endpoint)
        val executor = Executors.newSingleThreadExecutor()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = executor.submit<TranscriptItem.ToolResult> { search.execute(call()) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val timeout = pending.get(5, TimeUnit.SECONDS)
            assertFailure(timeout)
            assertTrue(timeout.content.contains("timed out"))
            enqueueResult(JSONObject().put("structuredContent", payload(1)))
            val recovered = search.execute(call())
            assertFalse(recovered.content, recovered.isError)
            assertEquals(2, server.requestCount)
        } finally {
            search.cancel()
            executor.shutdownNow()
        }
    }

    @Test fun cancellationBeforeExecutionAndBetweenCallsIsSticky() {
        tool.cancel()
        assertCanceled { tool.execute(call()) }
        assertCanceled { tool.execute(ToolCall("bad", "other", "not JSON")) }
        assertEquals(0, server.requestCount)

        val fresh = WebSearchTool(endpoint = endpoint)
        enqueueResult(JSONObject().put("structuredContent", payload(1)))
        assertFalse(fresh.execute(call()).isError)
        fresh.cancel()
        assertCanceled { fresh.execute(call()) }
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationDuringBlockedCallThrowsAndPreventsNextRequest() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<TranscriptItem.ToolResult> { tool.execute(call()) }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            tool.cancel()
            try {
                pending.get(2, TimeUnit.SECONDS)
                fail("Cancellation must throw, not return a tool error")
            } catch (error: ExecutionException) {
                assertTrue(error.cause is InterruptedIOException)
            }
            assertCanceled { tool.execute(call()) }
            assertEquals(1, server.requestCount)
        } finally {
            tool.cancel()
            executor.shutdownNow()
        }
    }

    @Test fun interruptedThreadIsNotConvertedIntoOrdinaryFailure() {
        Thread.currentThread().interrupt()
        try {
            assertCanceled { tool.execute(call()) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun injectedClientInterruptionIsNotSwallowedAsTimeout() {
        val http = OkHttpClient.Builder().addInterceptor {
            throw InterruptedIOException("Canceled by caller")
        }.build()
        val search = WebSearchTool(http, endpoint)
        assertCanceled { search.execute(call()) }
        assertEquals(0, server.requestCount)
    }

    private fun call(arguments: JSONObject = JSONObject().put("query", "Android Wear documentation")) =
        ToolCall("call-1", "web_search", arguments.toString())

    private fun payload(count: Int) = JSONObject().put("results", JSONArray().apply {
        for (index in 1..count) put(JSONObject()
            .put("title", "Official source $index")
            .put("url", "https://developer.android.com/topic/$index")
            .put("excerpts", JSONArray().put("Authentic excerpt $index"))
            .put("publish_date", "2026-10-01"))
    })

    private fun rpc(result: JSONObject, id: String = "call-1") = JSONObject()
        .put("jsonrpc", "2.0").put("id", id).put("result", result)

    private fun enqueueResult(result: JSONObject) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(rpc(result).toString()))
    }

    private fun assertFailure(result: TranscriptItem.ToolResult, id: String = "call-1") {
        assertEquals(id, result.callId)
        assertTrue(result.isError)
        assertTrue(result.content.startsWith("Error: "))
    }

    private fun assertCanceled(action: () -> Unit) {
        try {
            action()
            fail("Expected InterruptedIOException")
        } catch (_: InterruptedIOException) {
            // Expected: never a model-facing ToolResult.
        }
    }
}
