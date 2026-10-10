package dev.undefinedteam.wearagent.agent

import okhttp3.Call
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

/**
 * Template for SSE-based chat providers: owns HTTP clients, cancellation, the
 * SSE line protocol, and the models-endpoint retry loop. A subclass supplies
 * the protocol pieces: endpoint URL, auth headers, request body, stream-event
 * reducer, and final message assembly.
 */
abstract class SseChatClient : ChatProvider {
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

    @Volatile
    private var canceled = false
    private var call: Call? = null

    /** Cancellation belongs to this turn, including gaps between tool rounds. */
    override fun cancel() {
        synchronized(callLock) {
            canceled = true
            call?.cancel()
        }
    }

    /** Protocol-specific SSE event reducer; call [fail] on terminal errors. */
    protected interface StreamState {
        /** True when this protocol considers the stream finished. */
        fun isTerminal(): Boolean

        /** True if the payload is this protocol's end marker (e.g. [DONE]). */
        fun endMarker(payload: String): Boolean

        /** Folds one SSE event. */
        fun accept(event: JSONObject)

        /** Assembles the final message; must throw if the stream is incomplete. */
        fun outcome(elapsedMs: Long): Message
    }

    /** Chat endpoint URL (with protocol suffix appended if missing). */
    protected abstract fun chatUrl(request: ModelRequest): String

    /** Auth/protocol headers for the chat request. */
    protected abstract fun authHeaders(request: ModelRequest): List<Pair<String, String>>

    /** Request body for one round. */
    protected abstract fun requestBody(
        request: ModelRequest,
        history: List<TranscriptItem>,
        tools: List<ToolDefinition>,
    ): String

    /** A fresh reducer for this stream. */
    protected abstract fun newState(onDelta: (MessageDelta) -> Unit): StreamState

    /** Candidate /models URLs to try in order. */
    protected abstract fun modelUrls(request: ModelRequest): List<String>

    override fun stream(
        request: ModelRequest,
        history: List<TranscriptItem>,
        tools: List<ToolDefinition>,
        onDelta: (MessageDelta) -> Unit,
    ): Message {
        checkCanceled()
        val current = http.newCall(chatRequest(request, history, tools))
        synchronized(callLock) {
            checkCanceled()
            call = current
        }
        val start = System.currentTimeMillis()
        val state = newState(onDelta)
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
                    if (state.endMarker(payload)) {
                        done = true
                        return
                    }
                    val event = try {
                        JSONObject(payload)
                    } catch (e: Exception) {
                        throw IOException("Invalid stream event", e)
                    }
                    if (!event.has("type") && eventName.isNotEmpty()) event.put("type", eventName)
                    failOnError(event)
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

    private fun chatRequest(
        request: ModelRequest,
        history: List<TranscriptItem>,
        tools: List<ToolDefinition>,
    ): Request {
        val builder = Request.Builder()
            .url(chatUrl(request))
            .header("Accept", "text/event-stream")
            .post(requestBody(request, history, tools).toRequestBody(json))
        authHeaders(request).forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    override fun models(request: ModelRequest): List<String> {
        var lastError: Exception? = null
        for (url in modelUrls(request)) {
            try {
                return fetchModels(url, request)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("models endpoint not found")
    }

    private fun fetchModels(url: String, request: ModelRequest): List<String> {
        val builder = Request.Builder().url(url).get()
        authHeaders(request).forEach { (name, value) -> builder.header(name, value) }
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

    private fun checkCanceled() {
        if (canceled) throw InterruptedIOException("canceled")
    }

    /** Shared error contract: any provider may surface errors as events. */
    private fun failOnError(event: JSONObject) {
        val type = event.optString("type")
        val response = event.optJSONObject("response")
        val status = response?.optString("status")
        if (!event.isNull("error") || type == "error" || type == "response.failed" ||
            type == "response.incomplete" || status == "failed" || status == "incomplete"
        ) {
            val error = event.optJSONObject("error") ?: response?.optJSONObject("error")
            val message = error?.optString("message")?.takeIf { it.isNotBlank() }
                ?: event.optString("message").takeIf { it.isNotBlank() }
                ?: response?.optJSONObject("incomplete_details")?.optString("reason")
                ?: "Provider stream failed"
            throw IOException(message)
        }
    }
}
