package dev.undefinedteam.wearagent.agent

import dev.undefinedteam.wearagent.session.AgentSettings
import dev.undefinedteam.wearagent.session.ApiKind
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

class ChatClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private val quickHttp = http.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = "application/json".toMediaType()
    private val callLock = Any()
    @Volatile private var canceled = false
    private var call: okhttp3.Call? = null

    /** Cancellation belongs to this turn, including gaps between tool rounds. */
    fun cancel() {
        synchronized(callLock) {
            canceled = true
            call?.cancel()
        }
    }

    fun stream(
        settings: AgentSettings,
        history: List<TranscriptItem>,
        onText: (String) -> Unit,
        onReasoning: (String) -> Unit = {},
        tools: List<ToolDefinition> = emptyList(),
    ): StreamOutcome {
        checkCanceled()
        val current = http.newCall(request(settings, history, tools))
        synchronized(callLock) {
            checkCanceled()
            call = current
        }
        val start = System.currentTimeMillis()
        val state = StreamState(settings.apiKind, onText, onReasoning)
        try {
            checkCanceled()
            current.execute().use { response ->
                checkCanceled()
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val source = response.body?.source() ?: throw IOException("empty body")
                val data = StringBuilder()
                var eventName = ""
                var done = false
                fun dispatch() {
                    if (data.isEmpty()) return
                    checkCanceled()
                    val payload = data.toString()
                    data.setLength(0)
                    if (payload.trim() == "[DONE]") {
                        done = true
                        state.endMarker()
                        return
                    }
                    val event = try {
                        JSONObject(payload)
                    } catch (e: Exception) {
                        throw IOException("Invalid stream event", e)
                    }
                    if (!event.has("type") && eventName.isNotEmpty()) event.put("type", eventName)
                    try {
                        state.accept(event)
                        if (state.isTerminal()) done = true
                    } catch (e: JSONException) {
                        throw IOException("Invalid provider stream event", e)
                    }
                }
                while (!done && !source.exhausted()) {
                    checkCanceled()
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.isEmpty() -> {
                            dispatch()
                            eventName = ""
                        }
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.substring(5).removePrefix(" "))
                        }
                        line.startsWith("event:") -> eventName = line.substring(6).trim()
                    }
                }
                if (!done) dispatch()
                checkCanceled()
            }
            checkCanceled()
            return state.outcome((System.currentTimeMillis() - start).coerceAtLeast(1))
        } catch (e: IOException) {
            if (canceled || current.isCanceled()) {
                throw InterruptedIOException("canceled").also { it.initCause(e) }
            }
            throw e
        } finally {
            synchronized(callLock) {
                if (call === current) call = null
            }
        }
    }

    private fun checkCanceled() {
        if (canceled) throw InterruptedIOException("canceled")
    }

    data class Usage(val prompt: Int, val cached: Int, val completion: Int)

    data class StreamOutcome(
        val usage: Usage?,
        val elapsedMs: Long,
        val toolCalls: List<ToolCall> = emptyList(),
        val providerContent: String = "",
    )

    /** Fetches available model ids from the provider's models endpoint. */
    fun models(settings: AgentSettings): List<String> {
        val base = settings.endpoint.trim().trimEnd('/')
        if (base.isBlank()) return emptyList()
        val chatSuffix = when (settings.apiKind) {
            ApiKind.COMPLETIONS -> "/chat/completions"
            ApiKind.RESPONSES -> "/responses"
            ApiKind.ANTHROPIC -> "/messages"
        }
        val root =
            if (chatSuffix.isNotEmpty() && base.endsWith(chatSuffix)) base.removeSuffix(chatSuffix) else base
        val candidates = if (root.endsWith("/v1")) {
            listOf("$root/models")
        } else {
            listOf("$root/v1/models", "$root/models")
        }

        var lastError: Exception? = null
        for (url in candidates) {
            try {
                return fetchModels(url, settings)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("models endpoint not found")
    }

    private fun fetchModels(url: String, settings: AgentSettings): List<String> {
        val builder = Request.Builder().url(url).get()
        if (settings.apiKind == ApiKind.ANTHROPIC) {
            builder.header("x-api-key", settings.apiKey).header("anthropic-version", "2023-06-01")
        } else {
            builder.header("Authorization", "Bearer ${settings.apiKey}")
        }
        quickHttp.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val text = response.body?.string() ?: throw IOException("empty body")
            val root = JSONObject(text)
            val data = root.optJSONArray("data") ?: JSONArray()
            return buildList {
                for (index in 0 until data.length()) {
                    val id = data.optJSONObject(index)?.optString("id")
                    if (!id.isNullOrBlank()) add(id)
                }
            }
        }
    }

    private fun request(
        settings: AgentSettings,
        history: List<TranscriptItem>,
        tools: List<ToolDefinition>,
    ): Request {
        val builder = Request.Builder()
            .url(url(settings))
            .header("Accept", "text/event-stream")
            .post(body(settings, history, tools).toRequestBody(json))
        if (settings.apiKind == ApiKind.ANTHROPIC) {
            builder.header("x-api-key", settings.apiKey).header("anthropic-version", "2023-06-01")
        } else {
            builder.header("Authorization", "Bearer ${settings.apiKey}")
        }
        return builder.build()
    }

    private fun url(settings: AgentSettings): String {
        val base = settings.endpoint.trim().trimEnd('/')
        val suffix = when (settings.apiKind) {
            ApiKind.COMPLETIONS -> "/chat/completions"
            ApiKind.RESPONSES -> "/responses"
            ApiKind.ANTHROPIC -> "/messages"
        }
        return if (base.endsWith(suffix)) base else base + suffix
    }

    private fun body(
        settings: AgentSettings,
        history: List<TranscriptItem>,
        tools: List<ToolDefinition>,
    ): String {
        val root = JSONObject().put("model", settings.model).put("stream", true)
        when (settings.apiKind) {
            ApiKind.COMPLETIONS -> root
                .put("messages", messages(history, settings.apiKind))
                .put("stream_options", JSONObject().put("include_usage", true))
            ApiKind.RESPONSES -> {
                root.put("input", messages(history, settings.apiKind))
                if (tools.isNotEmpty() || history.any {
                        it is TranscriptItem.Assistant && it.providerKind == ApiKind.RESPONSES &&
                            it.providerContent.isNotEmpty()
                    }) {
                    root.put("include", JSONArray().put("reasoning.encrypted_content"))
                }
            }
            ApiKind.ANTHROPIC -> root
                .put("max_tokens", 1024)
                .put("messages", messages(history, settings.apiKind))
        }
        if (tools.isNotEmpty()) {
            root.put("tools", JSONArray().also { array ->
                tools.forEach { tool ->
                    val definition = JSONObject().put("name", tool.name).put("description", tool.description)
                    val schema = JSONObject(tool.parameterSchema)
                    when (settings.apiKind) {
                        ApiKind.COMPLETIONS -> array.put(JSONObject().put("type", "function")
                            .put("function", definition.put("parameters", schema)))
                        ApiKind.RESPONSES -> array.put(definition.put("type", "function")
                            .put("parameters", schema).put("strict", false))
                        ApiKind.ANTHROPIC -> array.put(definition.put("input_schema", schema))
                    }
                }
            })
        } else if (settings.apiKind == ApiKind.ANTHROPIC) {
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
                        definitions.put(JSONObject().put("name", name)
                            .put("description", known?.description ?: "Previously used tool; unavailable in this turn.")
                            .put("input_schema", known?.let { JSONObject(it.parameterSchema) }
                                ?: JSONObject().put("type", "object")))
                    }
                })
                root.put("tool_choice", JSONObject().put("type", "none"))
            }
        }
        return root.toString()
    }

    private fun messages(history: List<TranscriptItem>, kind: ApiKind): JSONArray {
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
                    val role = if (item.fromUser) "user" else "assistant"
                    if (kind == ApiKind.ANTHROPIC) {
                        anthropicMessage(role, JSONArray().put(JSONObject().put("type", "text").put("text", item.text)))
                    } else array.put(JSONObject().put("role", role).put("content", item.text))
                }
                is TranscriptItem.Assistant -> {
                    val native = item.providerKind == kind && item.providerContent.isNotBlank()
                    when (kind) {
                        ApiKind.COMPLETIONS -> {
                            val message = if (native) JSONObject(item.providerContent) else JSONObject()
                            message.put("role", "assistant").put("content", item.text)
                            if (item.toolCalls.isNotEmpty()) message.put("tool_calls", JSONArray().also { calls ->
                                item.toolCalls.forEach { call -> calls.put(JSONObject().put("id", call.id)
                                    .put("type", "function").put("function", JSONObject()
                                        .put("name", call.name).put("arguments", call.arguments))) }
                            })
                            array.put(message)
                        }
                        ApiKind.RESPONSES -> {
                            if (native) {
                                val output = JSONArray(item.providerContent)
                                for (i in 0 until output.length()) array.put(output.get(i))
                            } else {
                                if (item.text.isNotEmpty() || item.toolCalls.isEmpty()) {
                                    array.put(JSONObject().put("type", "message").put("role", "assistant")
                                        .put("content", JSONArray().put(JSONObject().put("type", "output_text")
                                            .put("text", item.text))))
                                }
                                item.toolCalls.forEach { call -> array.put(JSONObject().put("type", "function_call")
                                    .put("call_id", call.id).put("name", call.name).put("arguments", call.arguments)) }
                            }
                        }
                        ApiKind.ANTHROPIC -> {
                            val content = if (native) JSONArray(item.providerContent) else JSONArray().also { blocks ->
                                if (item.text.isNotEmpty() || item.toolCalls.isEmpty()) {
                                    blocks.put(JSONObject().put("type", "text").put("text", item.text))
                                }
                                item.toolCalls.forEach { call -> blocks.put(JSONObject().put("type", "tool_use")
                                    .put("id", call.id).put("name", call.name).put("input", JSONObject(call.arguments))) }
                            }
                            anthropicMessage("assistant", content)
                        }
                    }
                }
                is TranscriptItem.ToolResult -> when (kind) {
                    ApiKind.COMPLETIONS -> array.put(JSONObject().put("role", "tool")
                        .put("tool_call_id", item.callId).put("content", item.content))
                    ApiKind.RESPONSES -> array.put(JSONObject().put("type", "function_call_output")
                        .put("call_id", item.callId).put("output", item.content))
                    ApiKind.ANTHROPIC -> anthropicMessage("user", JSONArray().put(JSONObject()
                        .put("type", "tool_result").put("tool_use_id", item.callId)
                        .put("content", item.content).put("is_error", item.isError)))
                }
            }
        }
        return array
    }

    private class StreamState(
        private val kind: ApiKind,
        private val onText: (String) -> Unit,
        private val onReasoning: (String) -> Unit,
    ) {
        private val calls = sortedMapOf<Int, JSONObject>()
        private val native = JSONObject()
        private val output = sortedMapOf<Int, JSONObject>()
        private val argumentFragments = mutableMapOf<Int, StringBuilder>()
        private var usage: Usage? = null
        private var finished = false
        private var incompleteTools = false
        private var anthropicInput = 0
        private var anthropicCacheCreation = 0
        private var anthropicCacheRead = 0
        private var anthropicOutput = 0

        fun isTerminal(): Boolean = kind != ApiKind.COMPLETIONS && finished

        fun endMarker() {
            if (kind == ApiKind.COMPLETIONS) finished = true
        }

        fun accept(event: JSONObject) {
            val type = event.optString("type")
            val response = event.optJSONObject("response")
            val status = response?.optString("status")
            if (!event.isNull("error") || type == "error" || type == "response.failed" ||
                type == "response.incomplete" || status == "failed" || status == "incomplete") {
                val error = event.optJSONObject("error") ?: response?.optJSONObject("error")
                val message = error?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: event.optString("message").takeIf { it.isNotBlank() }
                    ?: response?.optJSONObject("incomplete_details")?.optString("reason")
                    ?: "Provider stream failed"
                throw IOException(message)
            }
            when (kind) {
                ApiKind.COMPLETIONS -> completions(event)
                ApiKind.RESPONSES -> responses(event)
                ApiKind.ANTHROPIC -> anthropic(event)
            }
        }

        private fun completions(event: JSONObject) {
            event.optJSONObject("usage")?.let { value ->
                usage = Usage(value.optInt("prompt_tokens"),
                    value.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens")
                        ?: value.optInt("cached_tokens"), value.optInt("completion_tokens"))
            }
            val choice = event.optJSONArray("choices")?.optJSONObject(0) ?: return
            val finish = choice.optString("finish_reason", "")
            if (finish == "length" || finish == "content_filter") incompleteTools = true
            if (finish.isNotEmpty()) finished = true
            val delta = choice.optJSONObject("delta") ?: return
            if (!delta.isNull("content")) onText(delta.optString("content"))
            completionReasoning(delta, "reasoning_content")
            completionReasoning(delta, "reasoning")
            completionReasoning(delta, "thinking")
            delta.optJSONArray("reasoning_details")?.let { details ->
                val saved = native.optJSONArray("reasoning_details") ?: JSONArray().also { native.put("reasoning_details", it) }
                for (i in 0 until details.length()) {
                    val part = details.optJSONObject(i) ?: continue
                    val index = part.optInt("index", i)
                    val target = saved.optJSONObject(index) ?: JSONObject().also { saved.put(index, it) }
                    mergeReasoningFragments(target, part)
                    part.optString("text").takeIf { it.isNotEmpty() }?.let(onReasoning)
                }
            }
            delta.optJSONArray("tool_calls")?.let { fragments ->
                for (i in 0 until fragments.length()) {
                    val part = fragments.getJSONObject(i)
                    val index = part.optInt("index", i)
                    val target = calls.getOrPut(index) { JSONObject().put("id", "").put("name", "").put("arguments", "") }
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
                onReasoning(value)
            } else native.put(key, value)
        }

        private fun responses(event: JSONObject) {
            val type = event.optString("type")
            val index = event.optInt("output_index", -1).takeIf { it >= 0 }
                ?: output.entries.firstOrNull { it.value.optString("id") == event.optString("item_id") }?.key
            when (type) {
                "response.output_item.added", "response.output_item.done" -> {
                    val item = event.optJSONObject("item") ?: throw IOException("Missing response output item")
                    output[index ?: throw IOException("Missing response output index")] = item
                }
                "response.function_call_arguments.delta" -> {
                    val key = index ?: throw IOException("Missing function call output index")
                    val fragment = event.optString("delta")
                    val item = output[key] ?: throw IOException("Missing function call item")
                    append(item, "arguments", fragment)
                }
                "response.function_call_arguments.done" -> {
                    val key = index ?: throw IOException("Missing function call output index")
                    val item = output[key] ?: throw IOException("Missing function call item")
                    item.put("arguments", event.getString("arguments"))
                }
                "response.content_part.added", "response.content_part.done" -> {
                    val item = index?.let(output::get) ?: throw IOException("Missing response message item")
                    val content = item.optJSONArray("content") ?: JSONArray().also { item.put("content", it) }
                    content.put(event.getInt("content_index"), event.getJSONObject("part"))
                }
                "response.output_text.delta" -> {
                    val text = event.optString("delta")
                    onText(text)
                    val item = index?.let(output::get)
                    if (item != null) {
                        val content = item.optJSONArray("content") ?: JSONArray().also { item.put("content", it) }
                        val position = event.optInt("content_index")
                        val part = content.optJSONObject(position) ?: JSONObject().put("type", "output_text")
                            .put("text", "").also { content.put(position, it) }
                        append(part, "text", text)
                    }
                }
                "response.reasoning_text.delta", "response.reasoning_summary_text.delta" -> {
                    val text = event.optString("delta")
                    onReasoning(text)
                    val item = index?.let(output::get)
                    if (item != null) {
                        val field = if (type == "response.reasoning_summary_text.delta") "summary" else "content"
                        val parts = item.optJSONArray(field) ?: JSONArray().also { item.put(field, it) }
                        val position = event.optInt(if (field == "summary") "summary_index" else "content_index")
                        val part = parts.optJSONObject(position) ?: JSONObject()
                            .put("type", if (field == "summary") "summary_text" else "reasoning_text")
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
                    usage = Usage(value.optInt("input_tokens"),
                        value.optJSONObject("input_tokens_details")?.optInt("cached_tokens") ?: 0,
                        value.optInt("output_tokens"))
                }
            }
        }

        private fun anthropic(event: JSONObject) {
            when (event.optString("type")) {
                "message_start" -> event.optJSONObject("message")?.let { message ->
                    message.optJSONObject("usage")?.let(::anthropicUsage)
                    message.optJSONArray("content")?.let { blocks ->
                        for (i in 0 until blocks.length()) output[i] = blocks.getJSONObject(i)
                    }
                }
                "content_block_start" -> output[event.getInt("index")] = event.getJSONObject("content_block")
                "content_block_delta" -> {
                    val index = event.getInt("index")
                    val block = output[index] ?: throw IOException("Missing Anthropic content block")
                    val delta = event.getJSONObject("delta")
                    when (delta.optString("type")) {
                        "text_delta" -> delta.optString("text").let { append(block, "text", it); onText(it) }
                        "thinking_delta" -> delta.optString("thinking").let { append(block, "thinking", it); onReasoning(it) }
                        "signature_delta" -> append(block, "signature", delta.optString("signature"))
                        "input_json_delta" -> argumentFragments.getOrPut(index) { StringBuilder() }
                            .append(delta.optString("partial_json"))
                    }
                }
                "content_block_stop" -> finishAnthropicBlock(event.getInt("index"))
                "message_delta" -> {
                    event.optJSONObject("usage")?.let(::anthropicUsage)
                    val reason = event.optJSONObject("delta")?.optString("stop_reason")
                    if (reason == "max_tokens") incompleteTools = true
                }
                "message_stop" -> finished = true
            }
        }

        private fun anthropicUsage(value: JSONObject) {
            if (value.has("input_tokens")) anthropicInput = value.optInt("input_tokens")
            if (value.has("cache_creation_input_tokens")) anthropicCacheCreation = value.optInt("cache_creation_input_tokens")
            if (value.has("cache_read_input_tokens")) anthropicCacheRead = value.optInt("cache_read_input_tokens")
            if (value.has("output_tokens")) anthropicOutput = value.optInt("output_tokens")
            usage = Usage(anthropicInput + anthropicCacheCreation + anthropicCacheRead, anthropicCacheRead, anthropicOutput)
        }

        private fun finishAnthropicBlock(index: Int) {
            val fragments = argumentFragments[index] ?: return
            val block = output[index] ?: throw IOException("Missing Anthropic tool block")
            block.put("input", parseArguments(fragments.toString()))
        }

        fun outcome(elapsed: Long): StreamOutcome {
            // A broken connection must not turn partial arguments into an executable call.
            if (!finished) throw IOException("Provider stream ended before completion")
            val tools = buildList {
                when (kind) {
                    ApiKind.COMPLETIONS -> calls.values.forEach {
                        add(completeCall(it.optString("id"), it.optString("name"), it.optString("arguments")))
                    }
                    ApiKind.RESPONSES -> output.values.forEach {
                        if (it.optString("type") == "function_call") {
                            add(completeCall(it.optString("call_id"), it.optString("name"), it.optString("arguments")))
                        }
                    }
                    ApiKind.ANTHROPIC -> {
                        argumentFragments.keys.forEach(::finishAnthropicBlock)
                        output.values.forEach {
                            if (it.optString("type") == "tool_use") {
                                add(completeCall(it.optString("id"), it.optString("name"),
                                    it.optJSONObject("input")?.toString().orEmpty()))
                            }
                        }
                    }
                }
            }
            if (tools.isNotEmpty() && incompleteTools) throw IOException("Provider stopped before tool calls completed")
            val content = if (kind == ApiKind.COMPLETIONS) native.toString() else
                JSONArray().also { array -> output.values.forEach { array.put(it) } }.toString()
            return StreamOutcome(usage, elapsed, tools, content)
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
                val fragment = key == "text" || key == "summary" || key == "data" || key == "signature"
                if (fragment && part.opt(key) is String) append(target, key, part.getString(key))
                else target.put(key, part.get(key))
            }
        }
    }
}
