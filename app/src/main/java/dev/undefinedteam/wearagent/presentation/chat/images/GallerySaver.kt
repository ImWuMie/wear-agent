package dev.undefinedteam.wearagent.presentation.chat.images

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import dev.undefinedteam.wearagent.agent.ImageAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

/**
 * Writes [image] into the shared gallery through MediaStore, which needs no
 * storage permission on API 29+. Returns false when the bytes cannot be decoded
 * or the provider rejects the insert.
 */
internal suspend fun saveToGallery(context: Context, image: ImageAttachment): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val bytes = Base64.getDecoder().decode(image.base64)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "wearagent-${System.currentTimeMillis()}")
                put(MediaStore.Images.Media.MIME_TYPE, image.mediaType)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/WearAgent")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@runCatching false
            // Clearing IS_PENDING publishes the row; leaving it set makes the image
            // invisible to the gallery.
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.getOrDefault(false)
    }
