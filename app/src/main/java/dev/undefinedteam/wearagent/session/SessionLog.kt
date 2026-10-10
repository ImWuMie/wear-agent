package dev.undefinedteam.wearagent.session

import android.content.Context
import dev.undefinedteam.wearagent.agent.TranscriptCodec
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
        migrateLegacy()
        return directory.listFiles { file -> file.extension == "jsonl" }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { file ->
                val lines = file.readLines().mapNotNull(::parse)
                SessionInfo(
                    file.nameWithoutExtension,
                    lines.firstOrNull { it.fromUser }?.text ?: file.nameWithoutExtension
                )
            }
    }

    fun create(): String {
        migrateLegacy()
        val id = System.currentTimeMillis().toString()
        File(directory, "$id.jsonl").createNewFile()
        return id
    }

    fun load(id: String): List<ChatMessage> {
        if (id.isBlank()) return emptyList()
        migrateLegacy()
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
                it.copy(text = text, transcript = if (it.fromUser) it.transcript else emptyList())
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

    /** Converts pre-JSONL TSV sessions in place; drops the empty-id artifact of older builds. */
    private fun migrateLegacy() {
        directory.listFiles { file -> file.extension == "txt" }?.forEach { old ->
            if (old.nameWithoutExtension.isNotBlank()) {
                val target = File(directory, "${old.nameWithoutExtension}.jsonl")
                if (!target.exists()) {
                    val messages = old.readLines().mapNotNull(::parseTsv)
                    target.writeText(messages.joinToString("") { encode(it) + "\n" })
                }
            }
            old.delete()
        }
    }

    private fun encode(message: ChatMessage): String {
        val json = JSONObject()
        json.put("id", message.id)
        json.put("role", if (message.fromUser) "user" else "assistant")
        json.put("text", message.text)
        json.put("reasoning", message.reasoning)
        json.put("prompt", message.promptTokens)
        json.put("cached", message.cachedTokens)
        json.put("completion", message.completionTokens)
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
            json.optInt("prompt"),
            json.optInt("cached"),
            json.optInt("completion"),
            json.optLong("ms"),
            TranscriptCodec.decode(json.optJSONArray("transcript")),
        )
    }.getOrNull()

    /** Legacy line format: id \t u|a \t text \t reasoning \t prompt \t cached \t completion \t ms. */
    private fun parseTsv(line: String): ChatMessage? {
        val parts = line.split('\t')
        if (parts.size < 3) return null
        val id = parts[0].toLongOrNull() ?: return null
        fun intAt(i: Int) = parts.getOrNull(i)?.toIntOrNull() ?: 0
        fun longAt(i: Int) = parts.getOrNull(i)?.toLongOrNull() ?: 0L
        return ChatMessage(
            id, parts[1] == "u", unescape(parts[2]),
            if (parts.size >= 4) unescape(parts[3]) else "",
            intAt(4), intAt(5), intAt(6), longAt(7),
        )
    }

    private fun unescape(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length) {
                out.append(if (text[i + 1] == 'n') '\n' else text[i + 1])
                i += 2
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }
}
