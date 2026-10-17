package dev.undefinedteam.wearagent.agent.tools

import dev.undefinedteam.wearagent.agent.ToolCall
import dev.undefinedteam.wearagent.agent.TranscriptItem
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Base for HTTP-backed tools. Owns the shared machinery: sticky cancellation
 * that aborts in-flight calls, the failure rule (a thrown error becomes a
 * model-facing error result; cancellation stays an [InterruptedIOException]),
 * and the timeout bounds an injected client must respect.
 */
abstract class HttpTool : Tool {
    private val lock = Any()
    private val activeCalls = mutableSetOf<Call>()

    @Volatile
    private var canceled = false

    @Volatile
    private var lastCallCanceled = false

    override fun cancel() {
        synchronized(lock) {
            canceled = true
            activeCalls.forEach { it.cancel() }
        }
    }

    override fun execute(call: ToolCall): TranscriptItem.ToolResult {
        checkCancellation()
        lastCallCanceled = false
        try {
            require(call.name == definition.name) { "Unknown tool: ${call.name.take(100)}" }
            return TranscriptItem.ToolResult(call.id, invoke(call))
        } catch (error: Throwable) {
            checkCancellation()
            val timedOut = error is SocketTimeoutException ||
                    (error is InterruptedIOException && error.message == "timeout")
            if (!timedOut && (error is InterruptedIOException || lastCallCanceled)) {
                if (error is InterruptedIOException) throw error
                throw InterruptedIOException("${definition.name} canceled").also {
                    it.initCause(error)
                }
            }
            return TranscriptItem.ToolResult(
                call.id,
                "Error: " + if (timedOut) {
                    "${definition.name} request timed out"
                } else {
                    error.message?.take(500) ?: "${definition.name} failed"
                },
                isError = true,
            )
        }
    }

    /** Produces the result text for one call, or throws to report a failure. */
    protected abstract fun invoke(call: ToolCall): String

    /** Throws [InterruptedIOException] once this tool has been canceled. */
    protected fun checkCancellation() {
        if (canceled || Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("${definition.name} canceled")
        }
    }

    /**
     * Executes one HTTP call, registered so [cancel] aborts it mid-flight and
     * unregistered only after [block] consumes the response.
     */
    protected fun <T> executeHttp(
        client: OkHttpClient,
        request: Request,
        block: (Response) -> T
    ): T {
        val networkCall = client.newCall(request)
        synchronized(lock) {
            checkCancellation()
            activeCalls.add(networkCall)
        }
        try {
            checkCancellation()
            return networkCall.execute().use(block)
        } finally {
            lastCallCanceled = networkCall.isCanceled()
            synchronized(lock) { activeCalls.remove(networkCall) }
        }
    }

    /** Caps every timeout an injected client may set, so none stays unbounded. */
    protected fun boundedBuilder(
        base: OkHttpClient,
        connectTimeoutMs: Long,
        readTimeoutMs: Long,
        callTimeoutMs: Long,
    ): OkHttpClient.Builder = base.newBuilder()
        .connectTimeout(bound(base.connectTimeoutMillis, connectTimeoutMs), TimeUnit.MILLISECONDS)
        .readTimeout(bound(base.readTimeoutMillis, readTimeoutMs), TimeUnit.MILLISECONDS)
        .callTimeout(bound(base.callTimeoutMillis, callTimeoutMs), TimeUnit.MILLISECONDS)

    private fun bound(current: Int, maximum: Long): Long =
        if (current == 0) maximum else minOf(current.toLong(), maximum)

    /** Reads a response body as text, refusing to buffer more than [limit] bytes. */
    protected fun readBounded(body: ResponseBody, limit: Long, label: String): String {
        if (body.contentLength() > limit) throw IOException("$label exceeds ${limit / 1024} KiB")
        return boundedStream(body, limit, label).bufferedReader(Charsets.UTF_8).readText()
    }

    /** The body stream capped at [limit] bytes, for callers that parse incrementally. */
    protected fun boundedStream(body: ResponseBody, limit: Long, label: String): InputStream =
        BoundedInputStream(body.byteStream(), limit, label)
}

/**
 * Counts decompressed bytes too, so an unknown or chunked Content-Length cannot
 * bypass the cap; exceeding it throws instead of buffering unbounded data.
 */
private class BoundedInputStream(
    input: InputStream,
    private val limit: Long,
    private val label: String,
) : FilterInputStream(input) {
    private var bytesRead = 0L

    override fun read(): Int {
        val value = `in`.read()
        if (value != -1) count(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = `in`.read(buffer, offset, minOf(length.toLong(), limit - bytesRead + 1).toInt())
        if (read > 0) count(read)
        return read
    }

    private fun count(count: Int) {
        bytesRead += count
        if (bytesRead > limit) throw IOException("$label exceeds ${limit / 1024} KiB")
    }
}
