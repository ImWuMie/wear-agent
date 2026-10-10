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
 * Append-only; a killed process replays the file, nothing lives only in memory.
 * Legacy TSV `.txt` sessions are migrated to `.jsonl` on first access.
 */
class SessionLog(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "sessions").apply { mkdirs() }

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
        val id = System.currentTimeMillis().toString()
        File(directory, "$id.jsonl").createNewFile()
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
    }

    private fun file(id: String) = File(directory, "$id.jsonl")

    private fun encode(message: ChatMessage): String {
        val json = JSONObject()
        json.put("id", message.id)
        json.put("role", if (message.userMessage) "user" else "assistant")
        json.put("text", message.text)
        json.put("reasoning", message.reasoning)
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
