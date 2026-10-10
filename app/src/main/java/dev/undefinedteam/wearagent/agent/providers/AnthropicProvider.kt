package dev.undefinedteam.wearagent.agent.providers

import dev.undefinedteam.wearagent.agent.ModelRequest
import dev.undefinedteam.wearagent.agent.EndpointKind
import dev.undefinedteam.wearagent.agent.Message
import dev.undefinedteam.wearagent.agent.MessageDelta
import dev.undefinedteam.wearagent.agent.SseChatClient
import dev.undefinedteam.wearagent.agent.TokensUsage
import dev.undefinedteam.wearagent.agent.ToolCall
import dev.undefinedteam.wearagent.agent.ToolDefinition
import dev.undefinedteam.wearagent.agent.TranscriptItem
import dev.undefinedteam.wearagent.agent.tools.WebSearchTool
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Anthropic Messages protocol (/messages, content-block SSE). */
class AnthropicProvider : SseChatClient() {
    override fun chatUrl(request: ModelRequest): String {
        val base = request.endpoint.trim().trimEnd('/')
        return if (base.endsWith(SUFFIX)) base else base + SUFFIX
    }

    override fun authHeaders(request: ModelRequest) = listOf(
        "x-api-key" to request.apiKey,
        "anthropic-version" to "2023-06-01",
    )

    override fun requestBody(
        request: ModelRequest,
        history: List<TranscriptItem>,
        tools: List<ToolDefinition>,
    ): String {
        val root = JSONObject()
            .put("model", request.model)
            .put("stream", true)
            .put("max_tokens", 1024)
            .put("messages", messages(history))
        if (tools.isNotEmpty()) {
            root.put("tools", JSONArray().also { array ->
                tools.forEach { tool ->
                    array.put(
                        JSONObject().put("name", tool.name)
                            .put("description", tool.description)
                            .put("input_schema", JSONObject(tool.parameterSchema))
                    )
                }
            })
        } else {
            // Anthropic requires definitions for replayed tool blocks even after tools are disabled.
            // tool_choice prevents new requests; unknown historical tools need only replay metadata.
            val names = linkedSetOf<String>()
            history.forEach { item ->
                if (item is TranscriptItem.Assistant) item.toolCalls.forEach { names.add(it.name) }
            }
            if (names.isNotEmpty()) {
                root.put("tools", JSONArray().also { definitions ->
                    names.forEach { name ->
                        val known = WebSearchTool.DEFINITION.takeIf { it.name == name }
                        definitions.put(
                            JSONObject().put("name", name)
                                .put(
                                    "description",
                                    known?.description
                                        ?: "Previously used tool; unavailable in this turn."
                                )
                                .put("input_schema", known?.let { JSONObject(it.parameterSchema) }
                                    ?: JSONObject().put("type", "object")))
                    }
                })
                root.put("tool_choice", JSONObject().put("type", "none"))
            }
        }
        return root.toString()
    }

    override fun modelUrls(request: ModelRequest): List<String> {
        val base = request.endpoint.trim().trimEnd('/')
        if (base.isBlank()) return emptyList()
        val root = if (base.endsWith(SUFFIX)) base.removeSuffix(SUFFIX) else base
        return if (root.endsWith("/v1")) {
            listOf("$root/models")
        } else {
            listOf("$root/v1/models", "$root/models")
        }
    }

    override fun newState(onDelta: (MessageDelta) -> Unit): StreamState = AnthropicState(onDelta)

    private fun messages(history: List<TranscriptItem>): JSONArray {
        val array = JSONArray()
        fun anthropicMessage(role: String, blocks: JSONArray) {
            val last = array.optJSONObject(array.length() - 1)
            if (last?.optString("role") == role) {
                val content = last.getJSONArray("content")
                for (i in 0 until blocks.length()) content.put(blocks.get(i))
            } else array.put(JSONObject().put("role", role).put("content", blocks))
        }
        history.forEach { item ->
            when (item) {
                is TranscriptItem.Text -> {
                    val role = if (item.userMessage) "user" else "assistant"
                    anthropicMessage(
                        role,
                        JSONArray().put(JSONObject().put("type", "text").put("text", item.text))
                    )
                }

                is TranscriptItem.Assistant -> {
                    val content =
                        if (item.endpointKind == EndpointKind.ANTHROPIC && item.providerContent.isNotBlank()) {
                            JSONArray(item.providerContent)
                        } else JSONArray().also { blocks ->
                            if (item.text.isNotEmpty() || item.toolCalls.isEmpty()) {
                                blocks.put(
                                    JSONObject().put("type", "text").put("text", item.text)
                                )
                            }
                            item.toolCalls.forEach { call ->
                                blocks.put(
                                    JSONObject().put("type", "tool_use")
                                        .put("id", call.id).put("name", call.name)
                                        .put("input", JSONObject(call.arguments))
                                )
                            }
                        }
                    anthropicMessage("assistant", content)
                }

                is TranscriptItem.ToolResult -> anthropicMessage(
                    "user", JSONArray().put(
                        JSONObject()
                            .put("type", "tool_result").put("tool_use_id", item.callId)
                            .put("content", item.content).put("is_error", item.isError)
                    )
                )
            }
        }
        return array
    }

    private class AnthropicState(private val onDelta: (MessageDelta) -> Unit) : StreamState {
        private val native = JSONObject()
        private val output = sortedMapOf<Int, JSONObject>()
        private val argumentFragments = mutableMapOf<Int, StringBuilder>()
        private var usage: TokensUsage? = null
        private var finished = false
        private var incompleteTools = false
        private var input = 0
        private var cacheCreation = 0
        private var cacheRead = 0
        private var outputTokens = 0

        override fun isTerminal() = finished

        override fun endMarker(payload: String) = payload.trim() == "[DONE]"

        override fun accept(event: JSONObject) {
            when (event.optString("type")) {
                "message_start" -> event.optJSONObject("message")?.let { message ->
                    message.optJSONObject("usage")?.let(::anthropicUsage)
                    message.optJSONArray("content")?.let { blocks ->
                        for (i in 0 until blocks.length()) output[i] = blocks.getJSONObject(i)
                    }
                }

                "content_block_start" -> output[event.getInt("index")] =
                    event.getJSONObject("content_block")

                "content_block_delta" -> {
                    val index = event.getInt("index")
                    val block =
                        output[index] ?: throw IOException("Missing Anthropic content block")
                    val delta = event.getJSONObject("delta")
                    when (delta.optString("type")) {
                        "text_delta" -> delta.optString("text")
                            .let { append(block, "text", it); onDelta(MessageDelta(text = it)) }

                        "thinking_delta" -> delta.optString("thinking")
                            .let { append(block, "thinking", it); onDelta(MessageDelta(reasoning = it)) }

                        "signature_delta" -> append(
                            block,
                            "signature",
                            delta.optString("signature")
                        )

                        "input_json_delta" -> argumentFragments.getOrPut(index) { StringBuilder() }
                            .append(delta.optString("partial_json"))
                    }
                }

                "content_block_stop" -> finishBlock(event.getInt("index"))
                "message_delta" -> {
                    event.optJSONObject("usage")?.let(::anthropicUsage)
                    val reason = event.optJSONObject("delta")?.optString("stop_reason")
                    if (reason == "max_tokens") incompleteTools = true
                }

                "message_stop" -> finished = true
            }
        }

        private fun anthropicUsage(value: JSONObject) {
            if (value.has("input_tokens")) input = value.optInt("input_tokens")
            if (value.has("cache_creation_input_tokens")) cacheCreation =
                value.optInt("cache_creation_input_tokens")
            if (value.has("cache_read_input_tokens")) cacheRead = value.optInt("cache_read_input_tokens")
            if (value.has("output_tokens")) outputTokens = value.optInt("output_tokens")
            usage = TokensUsage(input + cacheCreation + cacheRead, cacheRead, outputTokens)
        }

        private fun finishBlock(index: Int) {
            val fragments = argumentFragments[index] ?: return
            val block = output[index] ?: throw IOException("Missing Anthropic tool block")
            block.put("input", parseArguments(fragments.toString()))
        }

        override fun outcome(elapsedMs: Long): Message {
            // A broken connection must not turn partial arguments into an executable call.
            if (!finished) throw IOException("Provider stream ended before completion")
            argumentFragments.keys.forEach(::finishBlock)
            val tools = buildList {
                output.values.forEach {
                    if (it.optString("type") == "tool_use") {
                        add(
                            completeCall(
                                it.optString("id"), it.optString("name"),
                                it.optJSONObject("input")?.toString().orEmpty()
                            )
                        )
                    }
                }
            }
            if (tools.isNotEmpty() && incompleteTools) throw IOException("Provider stopped before tool calls completed")
            val content = JSONArray().also { array -> output.values.forEach { array.put(it) } }
                .toString()
            return Message(
                text = "",
                tokens = usage,
                elapsedMs = elapsedMs,
                toolCalls = tools,
                providerContent = content,
            )
        }

        private fun completeCall(id: String, name: String, arguments: String): ToolCall {
            if (id.isBlank() || name.isBlank()) throw IOException("Incomplete tool call identity")
            parseArguments(arguments)
            return ToolCall(id, name, arguments)
        }

        private fun parseArguments(arguments: String): JSONObject = try {
            JSONObject(arguments)
        } catch (e: Exception) {
            throw IOException("Incomplete or invalid tool arguments", e)
        }

        private fun append(target: JSONObject, key: String, fragment: String) {
            val previous = target.opt(key)
            val buffer = previous as? StringBuilder ?: StringBuilder(target.optString(key)).also {
                target.put(key, it)
            }
            buffer.append(fragment)
        }
    }

    companion object {
        private const val SUFFIX = "/messages"
    }
}
