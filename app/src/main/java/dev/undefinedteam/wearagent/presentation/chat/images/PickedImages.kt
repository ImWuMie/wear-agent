package dev.undefinedteam.wearagent.presentation.chat.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import dev.undefinedteam.wearagent.agent.ImageAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Turns picked [Uri]s into sendable attachments, downscaled to a [MAX_EDGE] JPEG so
 * a camera photo does not become a multi-megabyte payload. [ImageDecoder] applies
 * the source's EXIF rotation; `BitmapFactory` would hand back a sideways bitmap.
 */
internal suspend fun encodePickedImages(context: Context, uris: List<Uri>): List<ImageAttachment> =
    withContext(Dispatchers.IO) {
        uris.mapNotNull { uri -> runCatching { encodePickedImage(context, uri) }.getOrNull() }
    }

private fun encodePickedImage(context: Context, uri: Uri): ImageAttachment? {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > MAX_EDGE) {
            val scale = MAX_EDGE.toFloat() / longest
            decoder.setTargetSize(
                (info.size.width * scale).toInt().coerceAtLeast(1),
                (info.size.height * scale).toInt().coerceAtLeast(1),
            )
        }
    }
    val jpeg = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        bitmap.recycle()
        out.toByteArray()
    }
    if (jpeg.isEmpty()) return null
    return ImageAttachment(JPEG, Base64.getEncoder().encodeToString(jpeg))
}

private const val MAX_EDGE = 1024
private const val QUALITY = 80
private const val JPEG = "image/jpeg"
