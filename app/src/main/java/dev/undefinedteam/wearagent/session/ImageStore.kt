package dev.undefinedteam.wearagent.session

import dev.undefinedteam.wearagent.agent.ImageAttachment
import dev.undefinedteam.wearagent.agent.ImageCodec
import org.json.JSONObject
import java.io.File

/**
 * Stores message images as files under `files/images/` and persists only a
 * `name` reference in the session record, so a JSONL line stays small and a
 * conversation loads without carrying megabytes of base64.
 *
 * Writes are content-addressed by [ImageCodec]'s name, so the same image reused
 * across messages or sessions is stored once. Files are reference-counted
 * against the sessions that name them; a file no session references any more is
 * deleted by [retain].
 *
 * The store assumes it is the only writer. [put] resolves its names in one
 * directory snapshot, and [retain] re-snapshots on every call, so a concurrent
 * prune could delete a file another thread just wrote.
 */
class ImageStore(private val directory: File) {
    private val lock = Any()

    init {
        directory.mkdirs()
    }

    /**
     * Writes [image] and returns its reference. Content-addressed, so writing the
     * same bytes twice is a no-op.
     */
    fun put(image: ImageAttachment): String {
        val name = nameOf(image)
        val file = File(directory, name)
        synchronized(lock) {
            if (!file.exists()) file.writeBytes(image.bytes())
        }
        return name
    }

    /**
     * Reads [name] back; null when it is absent or unreadable.
     *
     * Resolves against the filesystem rather than a cached listing: the app holds
     * several `SessionLog` instances (activity, service, settings), and one
     * created before an image was written must still see it.
     */
    fun load(name: String): ImageAttachment? {
        if (name.isEmpty()) return null
        return runCatching {
            val bytes = File(directory, name).takeIf { it.isFile }?.readBytes() ?: return null
            ImageAttachment(contentType(name), java.util.Base64.getEncoder().encodeToString(bytes))
        }.getOrNull()
    }

    /**
     * Deletes every stored image no longer named by [sessions]. Call after a
     * session is written or removed, so the store tracks the records that survive.
     */
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

    /** The stored file carries the media type in its extension; that is the only copy of it. */
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

/** One session's image references, the input to [ImageStore.retain]. */
data class SessionImages(val id: String, val names: List<String>)

/** Decodes [ImageAttachment.base64] into the bytes the store writes. */
private fun ImageAttachment.bytes(): ByteArray = java.util.Base64.getDecoder().decode(base64)

/**
 * The on-disk codec: encodes an image as a `name` reference and resolves that
 * name through [store]. An image whose file is gone decodes to null and is
 * dropped from the message rather than failing the whole load.
 */
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
