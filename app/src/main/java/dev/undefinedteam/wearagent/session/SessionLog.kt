package dev.undefinedteam.wearagent.session

import android.content.Context
import dev.undefinedteam.wearagent.agent.TranscriptCodec
import dev.undefinedteam.wearagent.agent.TokensUsage
import org.json.JSONObject
import java.io.File

/**
 * One JSON object per line (JSONL):
 * `{"id":1,"role":"user","text":"...","reasoning":"","prompt":0,"cached":0,"completion":0,"ms":0}`.
 * Assistant records may also include a protocol `transcript` array for tool/native replay.
 * User records may also include an `images` array of `{name}` references into ImageStore.
 * Append-only; a killed process replays the file, nothing lives only in memory.
 * Legacy TSV `.txt` sessions are migrated to `.jsonl` on first access.
 */
class SessionLog(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "sessions").apply { mkdirs() }
    private val images = ImageStore(File(appContext.filesDir, "images"))
    private val imageCodec = StoredImages(images)

    fun sessions(): List<SessionInfo> {
        return directory.listFiles { file -> file.extension == "jsonl" }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { file ->
                val lines = file.readLines().mapNotNull(::parse)
                SessionInfo(
                    file.nameWithoutExtension,
                    lines.firstOrNull { it.userMessage }?.text ?: file.nameWithoutExtension
                )
            }
    }

    fun create(): String {
        var id = System.currentTimeMillis().toString()
        var file = file(id)
        // Two sessions created in the same millisecond would otherwise share one
        // file, silently merging them; bump until the name is actually free.
        while (!file.createNewFile()) {
            id = (id.toLong() + 1).toString()
            file = file(id)
        }
        return id
    }

    fun load(id: String): List<ChatMessage> {
        if (id.isBlank()) return emptyList()
        val file = file(id)
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull(::parse)
    }

    fun append(id: String, message: ChatMessage) {
        if (id.isBlank()) return
        file(id).appendText(encode(message) + "\n")
    }

    fun deleteSession(id: String) {
        if (id.isBlank()) return
        file(id).delete()
        prune()
    }

    fun deleteMessage(sessionId: String, messageId: Long) {
        val kept = load(sessionId).filterNot { it.id == messageId }
        write(sessionId, kept)
    }

    fun editMessage(sessionId: String, messageId: Long, text: String) {
        val changed = load(sessionId).map {
            if (it.id == messageId) {
                it.copy(text = text, transcript = if (it.userMessage) it.transcript else emptyList())
            } else it
        }
        write(sessionId, changed)
    }

    fun truncateFrom(sessionId: String, messageId: Long) {
        val kept = load(sessionId).takeWhile { it.id != messageId }
        write(sessionId, kept)
    }

    fun truncateAfter(sessionId: String, messageId: Long) {
        val all = load(sessionId)
        val kept = all.takeWhile { it.id != messageId }
            .plus(all.firstOrNull { it.id == messageId } ?: return)
        write(sessionId, kept)
    }

    private fun write(sessionId: String, messages: List<ChatMessage>) {
        if (sessionId.isBlank()) return
        file(sessionId).writeText(messages.joinToString("") { encode(it) + "\n" })
        // A truncated session may have dropped the only reference to an image;
        // prune after the record is durable so a crash cannot lose both.
        prune()
    }

    /** Drops every stored image no session references any more. */
    private fun prune() {
        images.retain(
            directory.listFiles { file -> file.extension == "jsonl" }.orEmpty().map { file ->
                SessionImages(file.nameWithoutExtension, referencedImages(file))
            },
        )
    }

    /** The image names [file] still references, read without decoding whole messages. */
    private fun referencedImages(file: File): List<String> = file.readLines().flatMap { line ->
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return@flatMap emptyList()
        val array = json.optJSONArray("images") ?: return@flatMap emptyList()
        List(array.length()) { array.optJSONObject(it)?.optString("name").orEmpty() }
    }.filter { it.isNotEmpty() }

    private fun file(id: String) = File(directory, "$id.jsonl")

    private fun encode(message: ChatMessage): String {
        val json = JSONObject()
        json.put("id", message.id)
        json.put("role", if (message.userMessage) "user" else "assistant")
        json.put("text", message.text)
        json.put("reasoning", message.reasoning)
        if (message.images.isNotEmpty()) {
            json.put("images", TranscriptCodec.encodeImages(message.images, imageCodec))
        }
        message.tokens?.let {
            json.put("prompt", it.prompt)
            json.put("cached", it.cached)
            json.put("completion", it.completion)
        }
        json.put("ms", message.elapsedMs)
        if (message.transcript.isNotEmpty()) {
            json.put("transcript", TranscriptCodec.encode(message.transcript))
        }
        return json.toString()
    }

    private fun parse(line: String): ChatMessage? = runCatching {
        val json = JSONObject(line)
        ChatMessage(
            json.getLong("id"),
            json.getString("role") == "user",
            json.getString("text"),
            TranscriptCodec.decodeImages(json.optJSONArray("images"), imageCodec),
            json.optString("reasoning"),
            if (json.has("prompt") || json.has("cached") || json.has("completion")) TokensUsage(
                json.optInt("prompt"),
                json.optInt("cached"),
                json.optInt("completion"),
            ) else null,
            json.optLong("ms"),
            TranscriptCodec.decode(json.optJSONArray("transcript")),
        )
    }.getOrNull()
}
