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

/** OpenAI-compatible chat/completions protocol. */
class CompletionsProvider : SseChatClient() {
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
            .put("messages", messages(history))
            .put("stream_options", JSONObject().put("include_usage", true))
        if (tools.isNotEmpty()) {
            root.put("tools", JSONArray().also { array ->
                tools.forEach { tool ->
                    array.put(
                        JSONObject().put("type", "function")
                            .put(
                                "function",
                                JSONObject().put("name", tool.name)
                                    .put("description", tool.description)
                                    .put("parameters", JSONObject(tool.parameterSchema))
                            )
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

    override fun newState(onDelta: (MessageDelta) -> Unit): StreamState = CompletionsState(onDelta)

    private fun messages(history: List<TranscriptItem>): JSONArray {
        val array = JSONArray()
        history.forEach { item ->
            when (item) {
                is TranscriptItem.Text -> array.put(
                    JSONObject().put(
                        "role",
                        if (item.userMessage) "user" else "assistant"
                    ).put("content", item.text)
                )

                is TranscriptItem.Assistant -> {
                    val native =
                        if (item.endpointKind == KIND && item.providerContent.isNotBlank()) {
                            JSONObject(item.providerContent)
                        } else JSONObject()
                    native.put("role", "assistant").put("content", item.text)
                    if (item.toolCalls.isNotEmpty()) native.put(
                        "tool_calls",
                        JSONArray().also { calls ->
                            item.toolCalls.forEach { call ->
                                calls.put(
                                    JSONObject().put("id", call.id)
                                        .put("type", "function").put(
                                            "function", JSONObject()
                                                .put("name", call.name)
                                                .put("arguments", call.arguments)
                                        )
                                )
                            }
                        })
                    array.put(native)
                }

                is TranscriptItem.ToolResult -> array.put(
                    JSONObject().put("role", "tool")
                        .put("tool_call_id", item.callId).put("content", item.content)
                )
            }
        }
        return array
    }

    private class CompletionsState(private val onDelta: (MessageDelta) -> Unit) : StreamState {
        private val calls = sortedMapOf<Int, JSONObject>()
        private val native = JSONObject()
        private var usage: Tokens? = null
        private var finished = false
        private var incompleteTools = false

        override fun isTerminal() = false // Completions terminates only on [DONE], not finish_reason

        override fun endMarker(payload: String): Boolean {
            val marker = payload.trim() == "[DONE]"
            if (marker) finished = true
            return marker
        }

        override fun accept(event: JSONObject) {
            event.optJSONObject("usage")?.let { value ->
                usage = Tokens(
                    value.optInt("prompt_tokens"),
                    value.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens")
                        ?: value.optInt("cached_tokens"),
                    value.optInt("completion_tokens")
                )
            }
            val choice = event.optJSONArray("choices")?.optJSONObject(0) ?: return
            val finish = choice.optString("finish_reason", "")
            if (finish == "length" || finish == "content_filter") incompleteTools = true
            if (finish.isNotEmpty()) finished = true
            val delta = choice.optJSONObject("delta") ?: return
            if (!delta.isNull("content")) onDelta(MessageDelta(text = delta.optString("content")))
            completionReasoning(delta, "reasoning_content")
            completionReasoning(delta, "reasoning")
            completionReasoning(delta, "thinking")
            delta.optJSONArray("reasoning_details")?.let { details ->
                val saved = native.optJSONArray("reasoning_details")
                    ?: JSONArray().also { native.put("reasoning_details", it) }
                for (i in 0 until details.length()) {
                    val part = details.optJSONObject(i) ?: continue
                    val index = part.optInt("index", i)
                    val target =
                        saved.optJSONObject(index) ?: JSONObject().also { saved.put(index, it) }
                    mergeReasoningFragments(target, part)
                    part.optString("text").takeIf { it.isNotEmpty() }
                        ?.let { onDelta(MessageDelta(reasoning = it)) }
                }
            }
            delta.optJSONArray("tool_calls")?.let { fragments ->
                for (i in 0 until fragments.length()) {
                    val part = fragments.getJSONObject(i)
                    val index = part.optInt("index", i)
                    val target = calls.getOrPut(index) {
                        JSONObject().put("id", "").put("name", "").put("arguments", "")
                    }
                    append(target, "id", part.optString("id"))
                    part.optJSONObject("function")?.let { function ->
                        append(target, "name", function.optString("name"))
                        append(target, "arguments", function.optString("arguments"))
                    }
                }
            }
        }

        private fun completionReasoning(delta: JSONObject, key: String) {
            if (delta.isNull(key)) return
            val value = delta.get(key)
            if (value is String) {
                append(native, key, value)
                onDelta(MessageDelta(reasoning = value))
            } else native.put(key, value)
        }

        override fun outcome(elapsedMs: Long): Message {
            // A broken connection must not turn partial arguments into an executable call.
            if (!finished) throw IOException("Provider stream ended before completion")
            val tools = buildList {
                calls.values.forEach {
                    add(
                        completeCall(
                            it.optString("id"),
                            it.optString("name"),
                            it.optString("arguments")
                        )
                    )
                }
            }
            if (tools.isNotEmpty() && incompleteTools) throw IOException("Provider stopped before tool calls completed")
            return Message(
                text = "",
                tokens = usage,
                elapsedMs = elapsedMs,
                toolCalls = tools,
                providerContent = native.toString(),
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

        private fun mergeReasoningFragments(target: JSONObject, part: JSONObject) {
            for (key in part.keys()) {
                val fragment =
                    key == "text" || key == "summary" || key == "data" || key == "signature"
                if (fragment && part.opt(key) is String) append(target, key, part.getString(key))
                else target.put(key, part.get(key))
            }
        }
    }

    companion object {
        private val KIND = EndpointKind.COMPLETIONS
        private const val SUFFIX = "/chat/completions"
    }
}
