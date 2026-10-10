package dev.undefinedteam.wearagent.agent

import dev.undefinedteam.wearagent.agent.tools.WebSearchTool
import dev.undefinedteam.wearagent.session.AgentSettings
import dev.undefinedteam.wearagent.session.EndpointProfile
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ToolLoopTest {
    @Test
    fun parallelCallsArePairedBeforeContinuationAndUsageIsAggregated() {
        MockWebServer().use { model ->
            MockWebServer().use { search ->
                model.start()
                search.start()
                model.enqueue(stream(toolDelta("one", "wear os"), toolDelta("two", "android", 1),
                    JSONObject().put("usage", JSONObject().put("prompt_tokens", 10)
                        .put("completion_tokens", 3).put("prompt_tokens_details", JSONObject().put("cached_tokens", 2)))))
                model.enqueue(stream(textDelta("Finished."),
                    JSONObject().put("usage", JSONObject().put("prompt_tokens", 20)
                        .put("completion_tokens", 5).put("prompt_tokens_details", JSONObject().put("cached_tokens", 4)))))
                search.enqueue(searchResult("https://developer.android.com/training/wearables"))
                search.enqueue(searchResult("https://developer.android.com", "two"))
                val states = ArrayList<TurnState>()
                val loop = ToolLoop(search = WebSearchTool(
                    endpoint = search.url("/mcp").toString()
                )
                )
                val result = loop.run(settings(model), listOf(TranscriptItem.Text(true, "Compare docs")), states::add)

                assertFalse(result.canceled)
                assertFalse(result.state.running)
                assertEquals(TokensUsage(30, 6, 8), result.tokens)
                assertEquals(2, search.requestCount)
                assertEquals(2, model.requestCount)
                val toolResults = result.transcript.filterIsInstance<TranscriptItem.ToolResult>()
                assertEquals(listOf("one", "two"), toolResults.map { it.callId })
                assertTrue(toolResults.all { !it.isError })
                assertTrue(states.any { it.text.isEmpty() && it.tools.any { activity -> activity.result == null } })
                assertTrue(result.state.tools.all { it.result != null && !it.isError })
                model.takeRequest(5, TimeUnit.SECONDS)!!
                val continuation = JSONObject(model.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                    .getJSONArray("messages")
                assertEquals("assistant", continuation.getJSONObject(1).getString("role"))
                assertEquals(2, continuation.getJSONObject(1).getJSONArray("tool_calls").length())
                assertEquals("one", continuation.getJSONObject(2).getString("tool_call_id"))
                assertEquals("two", continuation.getJSONObject(3).getString("tool_call_id"))
            }
        }
    }

    @Test
    fun cancelDuringSearchPairsRemainingCallsAndDoesNotResumeModel() {
        MockWebServer().use { model ->
            MockWebServer().use { search ->
                model.start()
                search.start()
                model.enqueue(stream(textDelta("Looking up docs."), toolDelta("one", "wear os"), toolDelta("two", "android", 1)))
                val enteredSearch = CountDownLatch(1)
                search.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        enteredSearch.countDown()
                        return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    }
                }
                val loop = ToolLoop(search = WebSearchTool(
                    endpoint = search.url("/mcp").toString()
                )
                )
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val future = executor.submit<ToolLoop.Outcome> {
                        loop.run(settings(model), listOf(TranscriptItem.Text(true, "Search docs")))
                    }
                    assertTrue(enteredSearch.await(5, TimeUnit.SECONDS))
                    loop.cancel()
                    val result = future.get(5, TimeUnit.SECONDS)
                    assertTrue(result.canceled)
                    assertEquals(null, result.state.error)
                    assertTrue(result.state.text.contains("Looking up docs."))
                    assertEquals(1, model.requestCount)
                    assertEquals(1, search.requestCount)
                    val results = result.transcript.filterIsInstance<TranscriptItem.ToolResult>()
                    assertEquals(listOf("one", "two"), results.map { it.callId })
                    assertTrue(results.all { it.isError })
                    assertTrue(result.state.tools.all { it.result != null && it.isError })
                } finally {
                    loop.cancel()
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test
    fun exhaustedBudgetPairsRejectedCallAndPreventsAnotherSearch() {
        MockWebServer().use { model ->
            MockWebServer().use { search ->
                model.start()
                search.start()
                model.enqueue(stream(toolDelta("one", "wear os")))
                model.enqueue(stream(toolDelta("two", "android")))
                search.enqueue(searchResult("https://developer.android.com"))
                val loop = ToolLoop(
                    search = WebSearchTool(endpoint = search.url("/mcp").toString()),
                    maxToolRounds = 1,
                )
                val result = loop.run(settings(model), listOf(TranscriptItem.Text(true, "Search docs")))
                assertTrue(result.state.error != null)
                assertEquals(1, search.requestCount)
                assertEquals(2, model.requestCount)
                assertEquals("two", (result.transcript.last() as TranscriptItem.ToolResult).callId)
                assertTrue((result.transcript.last() as TranscriptItem.ToolResult).isError)
                model.takeRequest(5, TimeUnit.SECONDS)!!
                val lastRequest = JSONObject(model.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                assertEquals(0, lastRequest.optJSONArray("tools")?.length() ?: 0)
            }
        }
    }

    @Test
    fun emptyCompletionDoesNotAppendAnInvalidAssistantMessageToHistory() {
        MockWebServer().use { model ->
            model.start()
            model.enqueue(stream(JSONObject().put("choices", JSONArray().put(
                JSONObject().put("delta", JSONObject())))))
            val result = ToolLoop().run(settings(model), listOf(TranscriptItem.Text(true, "Hello")))
            assertTrue(result.transcript.isEmpty())
            assertFalse(result.state.running)
            assertEquals(null, result.state.error)
        }
    }

    private fun settings(model: MockWebServer) = AgentSettings(
        endpoints = listOf(EndpointProfile("test", "test", endpoint = model.url("/v1").toString(), model = "test", apiKey = "test")),
        endpointId = "test",
        webSearchEnabled = true,
    )

    private fun stream(vararg events: JSONObject): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    private fun textDelta(text: String) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("delta", JSONObject().put("content", text))))

    private fun toolDelta(id: String, query: String, index: Int = 0) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("delta", JSONObject().put("tool_calls", JSONArray().put(
            JSONObject().put("index", index).put("id", id).put("type", "function")
                .put("function", JSONObject().put("name", "web_search")
                    .put("arguments", JSONObject().put("query", query).put("limit", 1).toString())))))))

    private fun searchResult(url: String, id: String = "one"): MockResponse {
        val payload = JSONObject().put("results", JSONArray().put(
            JSONObject().put("title", "Documentation").put("url", url).put("excerpts", JSONArray().put("Official docs"))))
        return MockResponse().setHeader("Content-Type", "application/json").setBody(
            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", JSONObject().put("content", JSONArray().put(
                JSONObject().put("type", "text").put("text", payload.toString())))).toString())
    }
}
