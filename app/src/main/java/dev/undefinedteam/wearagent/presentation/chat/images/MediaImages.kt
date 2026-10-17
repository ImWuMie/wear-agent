package dev.undefinedteam.wearagent.presentation.chat.images

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MediaImage(val id: Long, val uri: Uri, val name: String)

/**
 * Lists the device's images, newest first. Requires `READ_MEDIA_IMAGES`: on API 33+
 * a query without it returns no rows instead of throwing, so an empty result means
 * either "no images" or "not allowed" and the caller must check the grant.
 */
internal suspend fun queryMediaImages(context: Context): List<MediaImage> =
    withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
        )
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val rows = runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                order,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val found = ArrayList<MediaImage>()
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    found.add(
                        MediaImage(
                            id = id,
                            uri = Uri.withAppendedPath(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                id.toString(),
                            ),
                            name = cursor.getString(nameColumn).orEmpty(),
                        )
                    )
                }
                found
            }
        }.getOrNull()
        rows ?: emptyList()
    }
