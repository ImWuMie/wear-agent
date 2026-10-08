package dev.undefinedteam.wearagent.agent

import dev.undefinedteam.wearagent.session.AgentSettings
import dev.undefinedteam.wearagent.session.ApiKind
import dev.undefinedteam.wearagent.session.EndpointProfile
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ChatClientTest {
    private lateinit var server: MockWebServer
    private val definition = ToolDefinition("web_search", "Search and cite sources", """{
        "type":"object","properties":{"query":{"type":"string"}},"required":["query"]
    }""")

    @Before fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After fun stopServer() {
        server.shutdown()
    }

    @Test fun completionsAssemblesIndexedCallsAndReplaysReasoningAndResults() {
        enqueue(wire(
            """{"choices":[{"delta":{"reasoning_content":"Check ","reasoning_details":[{"index":0,"type":"reasoning.text","text":"Look ","signature":"sig-"}],"tool_calls":[{"index":1,"id":"call_","function":{"name":"web_","arguments":"{\"query\":"}}]}}]}""",
            """{"choices":[{"delta":{"content":"Searching","reasoning_content":"sources","reasoning_details":[{"index":0,"text":"up","signature":"end"}],"tool_calls":[{"index":0,"id":"call_a","function":{"name":"web_search","arguments":"{\"query\":\"alpha\"}"}},{"index":1,"id":"b","function":{"name":"search","arguments":"\"beta\"}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":20,"prompt_tokens_details":{"cached_tokens":8},"completion_tokens":4}}""",
            "[DONE]",
        ))
        val client = ChatClient()
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val first = client.stream(settings(ApiKind.COMPLETIONS), listOf(TranscriptItem.Text(true, "Find sources")),
            { text.append(it) }, { reasoning.append(it) }, listOf(definition))
        assertEquals("Searching", text.toString())
        assertEquals("Check Look sourcesup", reasoning.toString())
        assertEquals(listOf("call_a", "call_b"), first.toolCalls.map { it.id })
        assertEquals(listOf("alpha", "beta"), first.toolCalls.map { JSONObject(it.arguments).getString("query") })
        assertEquals(listOf("web_search", "web_search"), first.toolCalls.map { it.name })
        assertEquals(ChatClient.Usage(20, 8, 4), first.usage)
        val initial = requestBody()
        val schema = initial.getJSONArray("tools").getJSONObject(0).getJSONObject("function")
        assertEquals("web_search", schema.getString("name"))
        assertEquals("query", schema.getJSONObject("parameters").getJSONArray("required").getString(0))

        val history = listOf(
            TranscriptItem.Text(true, "Find sources"),
            TranscriptItem.Assistant(text.toString(), first.toolCalls, ApiKind.COMPLETIONS, first.providerContent),
            TranscriptItem.ToolResult("call_a", "Alpha source"),
            TranscriptItem.ToolResult("call_b", "Beta source", true),
        )
        enqueue(finalText(ApiKind.COMPLETIONS, "Answer"))
        val final = client.stream(settings(ApiKind.COMPLETIONS), history, {})
        val messages = requestBody().getJSONArray("messages")
        assertEquals(4, messages.length())
        val assistant = messages.getJSONObject(1)
        assertEquals("Searching", assistant.getString("content"))
        assertEquals("Check sources", assistant.getString("reasoning_content"))
        val details = assistant.getJSONArray("reasoning_details").getJSONObject(0)
        assertEquals("Look up", details.getString("text"))
        assertEquals("sig-end", details.getString("signature"))
        assertEquals("call_a", assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("tool", messages.getJSONObject(2).getString("role"))
        assertEquals("call_a", messages.getJSONObject(2).getString("tool_call_id"))
        assertEquals("Beta source", messages.getJSONObject(3).getString("content"))

        enqueue(finalText(ApiKind.COMPLETIONS, "Next"))
        client.stream(settings(ApiKind.COMPLETIONS), history + TranscriptItem.Assistant(
            "Answer", final.toolCalls, ApiKind.COMPLETIONS, final.providerContent,
        ) + TranscriptItem.Text(true, "Continue"), {})
        val complete = requestBody().getJSONArray("messages")
        assertEquals("Answer", complete.getJSONObject(4).getString("content"))
        assertEquals("Continue", complete.getJSONObject(5).getString("content"))
    }

    @Test fun responsesUsesCallIdAndKeepsEncryptedReasoningInOutputOrder() {
        val reasoningItem = """{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"Plan"}],"encrypted_content":"opaque-reasoning"}"""
        val messageItem = """{"type":"message","id":"msg_1","role":"assistant","content":[],"status":"in_progress"}"""
        val toolItem = """{"type":"function_call","id":"fc_item","call_id":"call_real","name":"web_search","arguments":""}"""
        enqueue(wire(
            """{"type":"response.output_item.added","output_index":0,"item":$reasoningItem}""",
            """{"type":"response.output_item.added","output_index":1,"item":$messageItem}""",
            """{"type":"response.content_part.added","output_index":1,"content_index":0,"part":{"type":"output_text","text":"","annotations":[]}}""",
            """{"type":"response.output_text.delta","output_index":1,"content_index":0,"delta":"Searching"}""",
            """{"type":"response.reasoning_summary_text.delta","output_index":0,"summary_index":0,"delta":" more"}""",
            """{"type":"response.output_item.added","output_index":2,"item":$toolItem}""",
            """{"type":"response.function_call_arguments.delta","item_id":"fc_item","delta":"{\"query\":\""}""",
            """{"type":"response.function_call_arguments.delta","output_index":2,"delta":"wear docs\"}"}""",
            """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":30,"input_tokens_details":{"cached_tokens":12},"output_tokens":7}}}""",
        ))
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val client = ChatClient()
        val first = client.stream(settings(ApiKind.RESPONSES), listOf(TranscriptItem.Text(true, "Search")),
            { text.append(it) }, { reasoning.append(it) }, listOf(definition))
        assertEquals("Searching", text.toString())
        assertEquals(" more", reasoning.toString())
        assertEquals("call_real", first.toolCalls.single().id)
        assertEquals("wear docs", JSONObject(first.toolCalls.single().arguments).getString("query"))
        assertEquals(ChatClient.Usage(30, 12, 7), first.usage)
        val initial = requestBody()
        assertEquals("function", initial.getJSONArray("tools").getJSONObject(0).getString("type"))
        assertEquals("reasoning.encrypted_content", initial.getJSONArray("include").getString(0))

        val history = listOf(
            TranscriptItem.Text(true, "Search"),
            TranscriptItem.Assistant(text.toString(), first.toolCalls, ApiKind.RESPONSES, first.providerContent),
            TranscriptItem.ToolResult("call_real", "Official source"),
        )
        enqueue(finalText(ApiKind.RESPONSES, "Answer"))
        val restored = TranscriptCodec.decode(JSONArray(TranscriptCodec.encode(history).toString()))
        val final = client.stream(settings(ApiKind.RESPONSES), restored, {})
        val replay = requestBody()
        val input = replay.getJSONArray("input")
        assertEquals(5, input.length())
        assertEquals("reasoning", input.getJSONObject(1).getString("type"))
        assertEquals("opaque-reasoning", input.getJSONObject(1).getString("encrypted_content"))
        assertEquals("Plan more", input.getJSONObject(1).getJSONArray("summary").getJSONObject(0).getString("text"))
        assertEquals("Searching", input.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("fc_item", input.getJSONObject(3).getString("id"))
        assertEquals("call_real", input.getJSONObject(3).getString("call_id"))
        assertEquals("function_call_output", input.getJSONObject(4).getString("type"))
        assertEquals("call_real", input.getJSONObject(4).getString("call_id"))
        assertEquals("Official source", input.getJSONObject(4).getString("output"))
        assertEquals("reasoning.encrypted_content", replay.getJSONArray("include").getString(0))

        enqueue(finalText(ApiKind.RESPONSES, "Next"))
        client.stream(settings(ApiKind.RESPONSES), history + TranscriptItem.Assistant(
            "Answer", final.toolCalls, ApiKind.RESPONSES, final.providerContent,
        ) + TranscriptItem.Text(true, "Continue"), {})
        val complete = requestBody().getJSONArray("input")
        assertEquals("Answer", complete.getJSONObject(5).getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("Continue", complete.getJSONObject(6).getString("content"))
    }

    @Test fun responsesFinalSnapshotPreservesNativeReasoningEvenWithoutTools() {
        val output = """[{"type":"reasoning","id":"rs_final","encrypted_content":"encrypted","summary":[]},
            {"type":"message","id":"msg_final","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Done","annotations":[]}]}]"""
        enqueue(wire(
            """{"type":"response.output_text.delta","output_index":1,"delta":"Done"}""",
            """{"type":"response.completed","response":{"status":"completed","output":$output,"usage":{"input_tokens":5,"output_tokens":2}}}""",
        ))
        val client = ChatClient()
        val first = client.stream(settings(ApiKind.RESPONSES), listOf(TranscriptItem.Text(true, "Hello")), {})
        assertTrue(first.toolCalls.isEmpty())
        assertEquals("encrypted", JSONArray(first.providerContent).getJSONObject(0).getString("encrypted_content"))
        assertFalse(requestBody().has("tools"))
        enqueue(finalText(ApiKind.RESPONSES, "Next"))
        client.stream(settings(ApiKind.RESPONSES), listOf(TranscriptItem.Assistant(
            "Done", providerKind = ApiKind.RESPONSES, providerContent = first.providerContent,
        ), TranscriptItem.Text(true, "Next")), {})
        val replay = requestBody().getJSONArray("input")
        assertEquals("rs_final", replay.getJSONObject(0).getString("id"))
        assertEquals("Done", replay.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test fun disabledAnthropicToolsReplayPersistedThinkingAndMatchingResults() {
        enqueue(wire(
            """{"type":"message_start","message":{"content":[],"usage":{"input_tokens":10,"cache_creation_input_tokens":7,"cache_read_input_tokens":5,"output_tokens":1}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Plan"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signed-"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"thinking"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Searching"}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"use_a","name":"web_search","input":{}}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\""}}""",
            """{"type":"content_block_start","index":3,"content_block":{"type":"tool_use","id":"use_b","name":"web_search","input":{}}}""",
            """{"type":"content_block_delta","index":3,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"beta\"}"}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"alpha\"}"}}""",
            """{"type":"content_block_stop","index":3}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"input_tokens":10,"output_tokens":9}}""",
            """{"type":"message_stop"}""",
        ))
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val client = ChatClient()
        val first = client.stream(settings(ApiKind.ANTHROPIC), listOf(TranscriptItem.Text(true, "Search")),
            { text.append(it) }, { reasoning.append(it) }, listOf(definition))
        assertEquals("Searching", text.toString())
        assertEquals("Plan", reasoning.toString())
        assertEquals(listOf("use_a", "use_b"), first.toolCalls.map { it.id })
        assertEquals(listOf("alpha", "beta"), first.toolCalls.map { JSONObject(it.arguments).getString("query") })
        assertEquals(ChatClient.Usage(22, 5, 9), first.usage)
        val initial = requestBody()
        assertEquals("query", initial.getJSONArray("tools").getJSONObject(0)
            .getJSONObject("input_schema").getJSONArray("required").getString(0))
        val history = listOf(
            TranscriptItem.Text(true, "Search"),
            TranscriptItem.Assistant(text.toString(), first.toolCalls, ApiKind.ANTHROPIC, first.providerContent),
            TranscriptItem.ToolResult("use_a", "Error: unavailable", true),
            TranscriptItem.ToolResult("use_b", "Beta source"),
        )
        enqueue(finalText(ApiKind.ANTHROPIC, "Answer"))
        val restored = TranscriptCodec.decode(JSONArray(TranscriptCodec.encode(history).toString()))
        val final = client.stream(settings(ApiKind.ANTHROPIC), restored, {})
        val continuation = requestBody()
        assertEquals("none", continuation.getJSONObject("tool_choice").getString("type"))
        assertEquals("web_search", continuation.getJSONArray("tools").getJSONObject(0).getString("name"))
        val historicalSchema = continuation.getJSONArray("tools").getJSONObject(0).getJSONObject("input_schema")
        assertEquals("query", historicalSchema.getJSONArray("required").getString(0))
        assertEquals("string", historicalSchema.getJSONObject("properties").getJSONObject("query").getString("type"))
        assertEquals(10, historicalSchema.getJSONObject("properties").getJSONObject("limit").getInt("maximum"))
        val messages = continuation.getJSONArray("messages")
        assertEquals(3, messages.length())
        val blocks = messages.getJSONObject(1).getJSONArray("content")
        assertEquals(4, blocks.length())
        assertEquals("thinking", blocks.getJSONObject(0).getString("type"))
        assertEquals("Plan", blocks.getJSONObject(0).getString("thinking"))
        assertEquals("signed-thinking", blocks.getJSONObject(0).getString("signature"))
        assertEquals("Searching", blocks.getJSONObject(1).getString("text"))
        assertEquals("alpha", blocks.getJSONObject(2).getJSONObject("input").getString("query"))
        val results = messages.getJSONObject(2)
        assertEquals("user", results.getString("role"))
        val resultBlocks = results.getJSONArray("content")
        assertEquals(2, resultBlocks.length())
        assertEquals("use_a", resultBlocks.getJSONObject(0).getString("tool_use_id"))
        assertTrue(resultBlocks.getJSONObject(0).getBoolean("is_error"))
        assertEquals("use_b", resultBlocks.getJSONObject(1).getString("tool_use_id"))
        assertFalse(resultBlocks.getJSONObject(1).getBoolean("is_error"))

        enqueue(finalText(ApiKind.ANTHROPIC, "Next"))
        client.stream(settings(ApiKind.ANTHROPIC), history + TranscriptItem.Assistant(
            "Answer", final.toolCalls, ApiKind.ANTHROPIC, final.providerContent,
        ) + TranscriptItem.Text(true, "Continue"), {})
        val complete = requestBody().getJSONArray("messages")
        assertEquals("Answer", complete.getJSONObject(3).getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("Continue", complete.getJSONObject(4).getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test fun protocolSwitchNormalizesCallsInsteadOfReusingForeignNativeBlocks() {
        for (kind in ApiKind.values()) {
            enqueue(finalText(kind, "Done"))
            val foreign = if (kind == ApiKind.COMPLETIONS) ApiKind.ANTHROPIC else ApiKind.COMPLETIONS
            ChatClient().stream(settings(kind), listOf(
                TranscriptItem.Text(true, "Search"),
                TranscriptItem.Assistant("Searching", listOf(ToolCall("pair_id", "web_search", """{"query":"watch"}""")),
                    foreign, "not valid JSON for this protocol"),
                TranscriptItem.ToolResult("pair_id", "Source"),
                TranscriptItem.Assistant("Final answer"),
            ), {})
            val body = requestBody()
            if (kind != ApiKind.ANTHROPIC) assertFalse(body.has("tools"))
            when (kind) {
                ApiKind.COMPLETIONS -> {
                    val messages = body.getJSONArray("messages")
                    assertEquals("pair_id", messages.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
                    assertEquals("pair_id", messages.getJSONObject(2).getString("tool_call_id"))
                    assertEquals("Final answer", messages.getJSONObject(3).getString("content"))
                }
                ApiKind.RESPONSES -> {
                    val input = body.getJSONArray("input")
                    assertEquals("Searching", input.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text"))
                    assertEquals("pair_id", input.getJSONObject(2).getString("call_id"))
                    assertEquals("pair_id", input.getJSONObject(3).getString("call_id"))
                    assertEquals("Final answer", input.getJSONObject(4).getJSONArray("content").getJSONObject(0).getString("text"))
                }
                ApiKind.ANTHROPIC -> {
                    assertEquals("none", body.getJSONObject("tool_choice").getString("type"))
                    assertEquals("web_search", body.getJSONArray("tools").getJSONObject(0).getString("name"))
                    val messages = body.getJSONArray("messages")
                    assertEquals("Searching", messages.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text"))
                    assertEquals("pair_id", messages.getJSONObject(1).getJSONArray("content").getJSONObject(1).getString("id"))
                    assertEquals("pair_id", messages.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("tool_use_id"))
                    assertEquals("Final answer", messages.getJSONObject(3).getJSONArray("content").getJSONObject(0).getString("text"))
                }
            }
        }
    }

    @Test fun textOnlyRequestsDoNotAdvertiseToolsForAnyProtocol() {
        for (kind in ApiKind.values()) {
            enqueue(finalText(kind, "Hello"))
            val text = StringBuilder()
            val outcome = ChatClient().stream(settings(kind), listOf(TranscriptItem.Text(true, "Hi")),
                { text.append(it) })
            assertEquals("Hello", text.toString())
            assertTrue(outcome.toolCalls.isEmpty())
            assertFalse(requestBody().has("tools"))
            if (kind == ApiKind.ANTHROPIC) {
                assertEquals("Hello", JSONArray(outcome.providerContent).getJSONObject(0).getString("text"))
            }
        }
    }

    @Test fun multilineSseAndTransportFragmentsProduceOneLogicalEvent() {
        enqueue(": keepalive\n\nevent: message\ndata: {\"choices\": [\ndata: {\"delta\": {\"content\": \"Hello\"}}],\ndata: \"usage\": {\"prompt_tokens\": 2, \"completion_tokens\": 1}}\n\ndata: [DONE]\n\n")
        val chunks = mutableListOf<String>()
        val result = ChatClient().stream(settings(ApiKind.COMPLETIONS), emptyList(), { chunks.add(it) })
        assertEquals(listOf("Hello"), chunks)
        assertEquals(ChatClient.Usage(2, 0, 1), result.usage)
    }

    @Test fun providerFailuresAreNotReturnedAsSuccessfulPartialAnswers() {
        val failures = listOf(
            ApiKind.COMPLETIONS to """{"error":{"message":"invalid key"}}""",
            ApiKind.RESPONSES to """{"type":"response.failed","response":{"status":"failed","error":{"message":"model failed"}}}""",
            ApiKind.RESPONSES to """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}}""",
            ApiKind.ANTHROPIC to """{"type":"error","error":{"type":"overloaded_error","message":"overloaded"}}""",
        )
        for ((kind, event) in failures) {
            enqueue(wire(event))
            val error = expectIOException { ChatClient().stream(settings(kind), emptyList(), {}) }
            val expected = when {
                event.contains("invalid key") -> "invalid key"
                event.contains("model failed") -> "model failed"
                event.contains("max_output_tokens") -> "max_output_tokens"
                else -> "overloaded"
            }
            assertEquals(expected, error.message)
            requestBody()
        }
        enqueue("event: error\ndata: {\"message\":\"named error\"}\n\n")
        assertEquals("named error", expectIOException {
            ChatClient().stream(settings(ApiKind.COMPLETIONS), emptyList(), {})
        }.message)
        requestBody()
    }

    @Test fun truncatedStreamsAndIncompleteToolArgumentsAreRejected() {
        val cases = listOf(
            wire("""{"choices":[{"delta":{"content":"Partial"}}]}"""),
            wire("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"id","function":{"name":"web_search","arguments":"{\"query\":"}}]}}]}""", "[DONE]"),
            wire("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"web_search","arguments":"{}"}}]}}]}""", "[DONE]"),
            wire("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"id","function":{"name":"web_search","arguments":"{}"}}]},"finish_reason":"length"}]}""", "[DONE]"),
            "data: {not-json}\n\n",
        )
        for (body in cases) {
            enqueue(body)
            expectIOException { ChatClient().stream(settings(ApiKind.COMPLETIONS), emptyList(), {}) }
            requestBody()
        }
        enqueue(wire(
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"item_not_call_id","name":"web_search","arguments":"{}"}}""",
            """{"type":"response.completed","response":{"status":"completed"}}""",
        ))
        assertEquals("Incomplete tool call identity", expectIOException {
            ChatClient().stream(settings(ApiKind.RESPONSES), emptyList(), {})
        }.message)
        requestBody()
    }

    @Test fun cancellationBeforeRequestAndBetweenRoundsIsSticky() {
        val preCanceled = ChatClient()
        preCanceled.cancel()
        assertTrue(expectIOException { preCanceled.stream(settings(ApiKind.COMPLETIONS), emptyList(), {}) } is InterruptedIOException)
        assertEquals(0, server.requestCount)
        enqueue(finalText(ApiKind.COMPLETIONS, "Done"))
        val client = ChatClient()
        client.stream(settings(ApiKind.COMPLETIONS), emptyList(), {})
        requestBody()
        client.cancel()
        repeat(2) {
            assertTrue(expectIOException { client.stream(settings(ApiKind.COMPLETIONS), emptyList(), {}) } is InterruptedIOException)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationDuringStreamDoesNotReturnPartialSuccessOrStartNextRound() {
        enqueue(finalText(ApiKind.COMPLETIONS, "Partial"))
        val client = ChatClient()
        val error = expectIOException {
            client.stream(settings(ApiKind.COMPLETIONS), emptyList(), { client.cancel() })
        }
        assertTrue(error is InterruptedIOException)
        requestBody()
        assertTrue(expectIOException { client.stream(settings(ApiKind.COMPLETIONS), emptyList(), {}) } is InterruptedIOException)
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationInterruptsRegisteredCallBlockedWaitingForResponse() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val executor = Executors.newSingleThreadExecutor()
        val client = ChatClient()
        try {
            val result = executor.submit<ChatClient.StreamOutcome> {
                client.stream(settings(ApiKind.COMPLETIONS), emptyList(), {})
            }
            val request = server.takeRequest(5, TimeUnit.SECONDS)
            assertTrue("request must reach server before cancellation", request != null)
            client.cancel()
            try {
                result.get(5, TimeUnit.SECONDS)
                fail("Canceled request returned normally")
            } catch (e: ExecutionException) {
                assertTrue(e.cause is InterruptedIOException)
            }
            assertTrue(expectIOException { client.stream(settings(ApiKind.COMPLETIONS), emptyList(), {}) } is InterruptedIOException)
            assertEquals(1, server.requestCount)
        } finally {
            client.cancel()
            executor.shutdownNow()
        }
    }

    @Test fun modelDiscoveryRemainsIndependentOfTurnCancellation() {
        val client = ChatClient()
        client.cancel()
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"model_a"},{"id":"model_b"}]}"""))
        val settings = settings(ApiKind.COMPLETIONS).copy(endpoints = listOf(
            EndpointProfile("test", "Test", ApiKind.COMPLETIONS, server.url("/v1/chat/completions").toString(), "test-model", "key"),
        ))
        assertEquals(listOf("model_a", "model_b"), client.models(settings))
        val request = server.takeRequest(5, TimeUnit.SECONDS) ?: throw AssertionError("Missing model request")
        assertEquals("/v1/models", request.path)
        assertEquals("GET", request.method)
    }

    private fun settings(kind: ApiKind) = AgentSettings(
        endpointId = "test",
        endpoints = listOf(EndpointProfile("test", "Test", kind, server.url("/v1").toString(), "test-model", "key")),
    )

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody(body, 7))
    }

    private fun requestBody(): JSONObject {
        val request = server.takeRequest(5, TimeUnit.SECONDS) ?: throw AssertionError("Missing request")
        return JSONObject(request.body.readUtf8())
    }

    private fun wire(vararg events: String): String = events.joinToString("") { event ->
        event.lineSequence().joinToString("\n", postfix = "\n\n") { "data: $it" }
    }

    private fun finalText(kind: ApiKind, text: String): String = when (kind) {
        ApiKind.COMPLETIONS -> wire(JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("delta", JSONObject().put("content", text)).put("finish_reason", "stop"))).toString(), "[DONE]")
        ApiKind.RESPONSES -> {
            val item = JSONObject().put("type", "message").put("id", "msg_final").put("role", "assistant")
                .put("status", "completed").put("content", JSONArray().put(JSONObject()
                    .put("type", "output_text").put("text", text).put("annotations", JSONArray())))
            wire(JSONObject().put("type", "response.output_text.delta").put("output_index", 0).put("delta", text).toString(),
                JSONObject().put("type", "response.completed").put("response", JSONObject()
                    .put("status", "completed").put("output", JSONArray().put(item))).toString())
        }
        ApiKind.ANTHROPIC -> wire(
            """{"type":"message_start","message":{"content":[],"usage":{"input_tokens":1,"output_tokens":0}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            JSONObject().put("type", "content_block_delta").put("index", 0)
                .put("delta", JSONObject().put("type", "text_delta").put("text", text)).toString(),
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
            """{"type":"message_stop"}""",
        )
    }

    private fun expectIOException(action: () -> Unit): IOException {
        try {
            action()
        } catch (e: IOException) {
            return e
        }
        throw AssertionError("Expected IOException")
    }
}
