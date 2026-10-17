package dev.undefinedteam.wearagent.session

import dev.undefinedteam.wearagent.agent.ImageAttachment
import dev.undefinedteam.wearagent.agent.ImageCodec
import org.json.JSONObject
import java.io.File

/**
 * Message images live as content-addressed files under `files/images/`, with the
 * session record holding only a `name` reference: the same bytes are stored once
 * across messages and sessions, and [retain] drops files no session names any more.
 *
 * Assumes a single writer: a concurrent [retain] could delete a file [put] just wrote.
 */
class ImageStore(private val directory: File) {
    private val lock = Any()

    init {
        directory.mkdirs()
    }

    /** Content-addressed: writing the same bytes twice is a no-op. */
    fun put(image: ImageAttachment): String {
        val name = nameOf(image)
        val file = File(directory, name)
        synchronized(lock) {
            if (!file.exists()) file.writeBytes(image.bytes())
        }
        return name
    }

    /**
     * Null when absent or unreadable. Reads the filesystem rather than a cached
     * listing: the app holds several `SessionLog` instances, and one created
     * before an image was written must still see it.
     */
    fun load(name: String): ImageAttachment? {
        if (name.isEmpty()) return null
        return runCatching {
            val bytes = File(directory, name).takeIf { it.isFile }?.readBytes() ?: return null
            ImageAttachment(contentType(name), java.util.Base64.getEncoder().encodeToString(bytes))
        }.getOrNull()
    }

    /** Drops files no session in [sessions] names; call after a session is written or removed. */
    fun retain(sessions: List<SessionImages>) {
        val live = HashSet<String>()
        sessions.forEach { session -> session.names.forEach { live.add(it) } }
        synchronized(lock) {
            snapshot().filterNot { it in live }.forEach { File(directory, it).delete() }
        }
    }

    private fun snapshot(): Set<String> =
        directory.listFiles()?.filter { it.isFile }?.map { it.name }?.toSet().orEmpty()

    private fun nameOf(image: ImageAttachment): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(image.bytes())
        return digest.joinToString("") { "%02x".format(it) } + extensionOf(image.mediaType)
    }

    /** The extension is the only record of the media type. */
    private fun extensionOf(mediaType: String): String = when (mediaType.lowercase()) {
        "image/png" -> ".png"
        "image/gif" -> ".gif"
        "image/webp" -> ".webp"
        else -> ".jpg"
    }

    private fun contentType(name: String): String = when {
        name.endsWith(".png") -> "image/png"
        name.endsWith(".gif") -> "image/gif"
        name.endsWith(".webp") -> "image/webp"
        else -> "image/jpeg"
    }
}

/** One session's image references; input to [ImageStore.retain]. */
data class SessionImages(val id: String, val names: List<String>)

private fun ImageAttachment.bytes(): ByteArray = java.util.Base64.getDecoder().decode(base64)

/** Encodes an image as a `name` reference; an image whose file is gone decodes to null and is dropped. */
class StoredImages(private val store: ImageStore) : ImageCodec {
    override fun encode(image: ImageAttachment): JSONObject = JSONObject()
        .put("name", store.put(image))

    override fun decode(json: JSONObject): ImageAttachment? {
        val name = json.optString("name")
        if (name.isNotEmpty()) return store.load(name)
        // A record written before the store existed still carries inline bytes.
        val base64 = json.optString("base64")
        if (base64.isEmpty()) return null
        return ImageAttachment(json.optString("mediaType"), base64)
    }
}
