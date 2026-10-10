package dev.undefinedteam.wearagent.agent.providers

import dev.undefinedteam.wearagent.agent.ChatRequest
import dev.undefinedteam.wearagent.agent.EndpointKind
import dev.undefinedteam.wearagent.agent.Message
import dev.undefinedteam.wearagent.agent.MessageDelta
import dev.undefinedteam.wearagent.agent.SseChatClient
import dev.undefinedteam.wearagent.agent.Tokens
import dev.undefinedteam.wearagent.agent.ToolCall
import dev.undefinedteam.wearagent.agent.ToolDefinition
import dev.undefinedteam.wearagent.agent.TranscriptItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** OpenAI Responses protocol (/responses, SSE item events). */
class ResponsesProvider : SseChatClient() {
    override fun chatUrl(request: ChatRequest): String {
        val base = request.endpoint.trim().trimEnd('/')
        return if (base.endsWith(SUFFIX)) base else base + SUFFIX
    }

    override fun authHeaders(request: ChatRequest) = listOf("Authorization" to "Bearer ${request.apiKey}")

    override fun requestBody(
        request: ChatRequest,
        history: List<TranscriptItem>,
        tools: List<ToolDefinition>,
    ): String {
        val root = JSONObject()
            .put("model", request.model)
            .put("stream", true)
            .put("input", input(history))
        if (tools.isNotEmpty() || history.any {
                it is TranscriptItem.Assistant && it.endpointKind == EndpointKind.RESPONSES &&
                        it.providerContent.isNotEmpty()
            }) {
            root.put("include", JSONArray().put("reasoning.encrypted_content"))
        }
        if (tools.isNotEmpty()) {
            root.put("tools", JSONArray().also { array ->
                tools.forEach { tool ->
                    array.put(
                        JSONObject().put("name", tool.name)
                            .put("description", tool.description)
                            .put("parameters", JSONObject(tool.parameterSchema))
                            .put("type", "function")
                            .put("strict", false)
                    )
                }
            })
        }
        return root.toString()
    }

    override fun modelUrls(request: ChatRequest): List<String> {
        val base = request.endpoint.trim().trimEnd('/')
        if (base.isBlank()) return emptyList()
        val root = if (base.endsWith(SUFFIX)) base.removeSuffix(SUFFIX) else base
        return if (root.endsWith("/v1")) {
            listOf("$root/models")
        } else {
            listOf("$root/v1/models", "$root/models")
        }
    }

    override fun newState(onDelta: (MessageDelta) -> Unit): StreamState = ResponsesState(onDelta)

    private fun input(history: List<TranscriptItem>): JSONArray {
        val array = JSONArray()
        history.forEach { item ->
            when (item) {
                is TranscriptItem.Text -> array.put(
                    JSONObject()
                        .put("role", if (item.userMessage) "user" else "assistant")
                        .put("content", item.text)
                )

                is TranscriptItem.Assistant -> {
                    val native =
                        item.endpointKind == EndpointKind.RESPONSES && item.providerContent.isNotBlank()
                    if (native) {
                        val output = JSONArray(item.providerContent)
                        for (i in 0 until output.length()) array.put(output.get(i))
                    } else {
                        if (item.text.isNotEmpty() || item.toolCalls.isEmpty()) {
                            array.put(
                                JSONObject().put("type", "message").put("role", "assistant")
                                    .put(
                                        "content", JSONArray().put(
                                            JSONObject().put("type", "output_text")
                                                .put("text", item.text)
                                        )
                                    )
                            )
                        }
                        item.toolCalls.forEach { call ->
                            array.put(
                                JSONObject().put("type", "function_call")
                                    .put("call_id", call.id).put("name", call.name)
                                    .put("arguments", call.arguments)
                            )
                        }
                    }
                }

                is TranscriptItem.ToolResult -> array.put(
                    JSONObject().put("type", "function_call_output")
                        .put("call_id", item.callId).put("output", item.content)
                )
            }
        }
        return array
    }

    private class ResponsesState(private val onDelta: (MessageDelta) -> Unit) : StreamState {
        private val native = JSONObject()
        private val output = sortedMapOf<Int, JSONObject>()
        private var usage: Tokens? = null
        private var finished = false

        override fun isTerminal() = finished

        override fun endMarker(payload: String) = payload.trim() == "[DONE]"

        override fun accept(event: JSONObject) {
            val type = event.optString("type")
            val index = event.optInt("output_index", -1).takeIf { it >= 0 }
                ?: output.entries.firstOrNull { it.value.optString("id") == event.optString("item_id") }?.key
            when (type) {
                "response.output_item.added", "response.output_item.done" -> {
                    val item = event.optJSONObject("item")
                        ?: throw IOException("Missing response output item")
                    output[index ?: throw IOException("Missing response output index")] = item
                }

                "response.function_call_arguments.delta" -> {
                    val key = index ?: throw IOException("Missing function call output index")
                    val item = output[key] ?: throw IOException("Missing function call item")
                    append(item, "arguments", event.optString("delta"))
                }

                "response.function_call_arguments.done" -> {
                    val key = index ?: throw IOException("Missing function call output index")
                    val item = output[key] ?: throw IOException("Missing function call item")
                    item.put("arguments", event.getString("arguments"))
                }

                "response.content_part.added", "response.content_part.done" -> {
                    val item = index?.let(output::get)
                        ?: throw IOException("Missing response message item")
                    val content =
                        item.optJSONArray("content") ?: JSONArray().also { item.put("content", it) }
                    content.put(event.getInt("content_index"), event.getJSONObject("part"))
                }

                "response.output_text.delta" -> {
                    val text = event.optString("delta")
                    onDelta(MessageDelta(text = text))
                    val item = index?.let(output::get)
                    if (item != null) {
                        val content = item.optJSONArray("content") ?: JSONArray().also {
                            item.put("content", it)
                        }
                        val position = event.optInt("content_index")
                        val part = content.optJSONObject(position)
                            ?: JSONObject().put("type", "output_text")
                                .put("text", "").also { content.put(position, it) }
                        append(part, "text", text)
                    }
                }

                "response.reasoning_text.delta", "response.reasoning_summary_text.delta" -> {
                    val text = event.optString("delta")
                    onDelta(MessageDelta(reasoning = text))
                    val item = index?.let(output::get)
                    if (item != null) {
                        val field =
                            if (type == "response.reasoning_summary_text.delta") "summary" else "content"
                        val parts =
                            item.optJSONArray(field) ?: JSONArray().also { item.put(field, it) }
                        val position =
                            event.optInt(if (field == "summary") "summary_index" else "content_index")
                        val part = parts.optJSONObject(position) ?: JSONObject()
                            .put(
                                "type",
                                if (field == "summary") "summary_text" else "reasoning_text"
                            )
                            .put("text", "").also { parts.put(position, it) }
                        append(part, "text", text)
                    }
                }

                "response.completed" -> finished = true
            }
            event.optJSONObject("response")?.let { response ->
                response.optJSONArray("output")?.let { items ->
                    if (type == "response.completed") output.clear()
                    for (i in 0 until items.length()) output[i] = items.getJSONObject(i)
                }
                response.optJSONObject("usage")?.let { value ->
                    usage = Tokens(
                        value.optInt("input_tokens"),
                        value.optJSONObject("input_tokens_details")?.optInt("cached_tokens") ?: 0,
                        value.optInt("output_tokens")
                    )
                }
            }
        }

        override fun outcome(elapsedMs: Long): Message {
            // A broken connection must not turn partial arguments into an executable call.
            if (!finished) throw IOException("Provider stream ended before completion")
            val tools = buildList {
                output.values.forEach {
                    if (it.optString("type") == "function_call") {
                        add(
                            completeCall(
                                it.optString("call_id"),
                                it.optString("name"),
                                it.optString("arguments")
                            )
                        )
                    }
                }
            }
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
            try {
                JSONObject(arguments)
            } catch (e: Exception) {
                throw IOException("Incomplete or invalid tool arguments", e)
            }
            return ToolCall(id, name, arguments)
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
        private const val SUFFIX = "/responses"
    }
}
