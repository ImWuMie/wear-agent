package dev.undefinedteam.wearagent.agent

import dev.undefinedteam.wearagent.session.AgentSettings
import dev.undefinedteam.wearagent.session.ApiKind
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
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
    @Volatile
    private var call: okhttp3.Call? = null

    fun cancel() {
        call?.cancel()
    }

    /** Streams text and returns the function calls accumulated from the provider's tool events. */
    fun stream(
        settings: AgentSettings,
        history: List<TranscriptItem>,
        onText: (String) -> Unit,
        onReasoning: (String) -> Unit = {},
    ): StreamOutcome {
        val current = http.newCall(request(settings, history))
        call = current
        var usage: Usage? = null
        val start = System.currentTimeMillis()
        current.execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val source = response.body?.source() ?: throw IOException("empty body")
            while (!current.isCanceled() && !source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val event = try {
                    JSONObject(data)
                } catch (_: Exception) {
                    continue
                }
                read(settings.apiKind, event, onText, onReasoning)
                val eventUsage = event.optJSONObject("usage")
                if (eventUsage != null) usage = parseUsage(eventUsage)
            }
            if (current.isCanceled()) throw InterruptedIOException("canceled")
        }
        val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1)
        return StreamOutcome(usage, elapsed)
    }

    data class Usage(val prompt: Int, val cached: Int, val completion: Int)

    data class StreamOutcome(val usage: Usage?, val elapsedMs: Long)

    private fun parseUsage(usage: JSONObject): Usage {
        var cached = usage.optInt("cached_tokens", 0)
        if (cached == 0) cached = usage.optInt("cache_read_input_tokens", 0)
        if (cached == 0) {
            cached = usage.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens", 0) ?: 0
        }
        return Usage(usage.optInt("prompt_tokens", 0), cached, usage.optInt("completion_tokens", 0))
    }

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

    private fun request(settings: AgentSettings, history: List<TranscriptItem>): Request {
        val builder = Request.Builder()
            .url(url(settings))
            .header("Accept", "text/event-stream")
            .post(body(settings, history).toRequestBody(json))
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
        return if (suffix.isEmpty() || base.endsWith(suffix)) base else base + suffix
    }

    private fun body(settings: AgentSettings, history: List<TranscriptItem>): String {
        val root = JSONObject().put("model", settings.model).put("stream", true)
        when (settings.apiKind) {
            ApiKind.COMPLETIONS -> root
                .put("messages", messages(history, settings.apiKind))
                .put("stream_options", JSONObject().put("include_usage", true))

            ApiKind.RESPONSES -> root
                .put("input", messages(history, settings.apiKind))

            ApiKind.ANTHROPIC -> root
                .put("max_tokens", 1024)
                .put("messages", messages(history, settings.apiKind))
        }
        return root.toString()
    }

    private fun messages(history: List<TranscriptItem>, kind: ApiKind): JSONArray {
        val array = JSONArray()
        history.forEach { item ->
            when (item) {
                is TranscriptItem.Text -> array.put(textItem(item, kind))
            }
        }
        return array
    }

    private fun textItem(item: TranscriptItem.Text, kind: ApiKind): JSONObject {
        val role = if (item.fromUser) "user" else "assistant"
        return JSONObject().put("role", role).put("content", item.text)
    }

    private fun read(
        kind: ApiKind,
        event: JSONObject,
        onText: (String) -> Unit,
        onReasoning: (String) -> Unit,
    ) {
        when (kind) {
            ApiKind.ANTHROPIC -> readAnthropic(event, onText, onReasoning)
            ApiKind.RESPONSES -> readResponses(event, onText, onReasoning)
            else -> readCompletions(event, onText, onReasoning)
        }
    }

    private fun readCompletions(
        event: JSONObject,
        onText: (String) -> Unit,
        onReasoning: (String) -> Unit
    ) {
        val delta =
            event.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return
        if (!delta.isNull("content")) onText(delta.optString("content"))
        reasoningOf(delta)?.let(onReasoning)
    }

    private fun readResponses(
        event: JSONObject,
        onText: (String) -> Unit,
        onReasoning: (String) -> Unit
    ) {
        when (event.optString("type")) {
            "response.output_text.delta" -> onText(event.optString("delta"))
            "response.reasoning_text.delta", "response.reasoning_summary_text.delta" -> onReasoning(
                event.optString("delta")
            )
        }
    }

    private fun readAnthropic(
        event: JSONObject,
        onText: (String) -> Unit,
        onReasoning: (String) -> Unit
    ) {
        when (event.optString("type")) {
            "content_block_delta" -> {
                val delta = event.optJSONObject("delta") ?: return
                when (delta.optString("type")) {
                    "text_delta" -> onText(delta.optString("text"))
                    "thinking_delta" -> onReasoning(delta.optString("thinking"))
                }
            }
        }
    }

    private fun reasoningOf(delta: JSONObject): String? {
        val keys = listOf("reasoning_content", "reasoning", "thinking")
        keys.forEach { key -> if (delta.has(key) && !delta.isNull(key)) return delta.optString(key) }
        val details = delta.optJSONArray("reasoning_details") ?: return null
        val out = StringBuilder()
        for (index in 0 until details.length()) {
            val item = details.optJSONObject(index) ?: continue
            val text = item.optString("text")
            if (text.isNotEmpty()) out.append(text)
        }
        return out.toString().ifEmpty { null }
    }

    // TODO(tools): tool definitions, streaming tool-call parsing
    // and provider-specific call/result serializers were removed together with Harness.
    // Re-add here when watch-side tools come back.
}
