package dev.undefinedteam.wearagent.presentation.chat.images

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.undefinedteam.wearagent.agent.ImageAttachment

/**
 * The attachments of one message, as tappable thumbnails that wrap two per row.
 * Tapping one opens it full-screen.
 */
@Composable
internal fun ImageStrip(
    images: List<ImageAttachment>,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val bitmaps = rememberDecodedImages(images)
    if (bitmaps.isEmpty()) return
    var preview by remember { mutableStateOf<ImageAttachment?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        bitmaps.withIndex().chunked(THUMBNAILS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (index, bitmap) ->
                    if (bitmap != null) Image(
                        bitmap,
                        contentDescription,
                        Modifier
                            .size(THUMBNAIL)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { preview = images[index] },
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }
    preview?.let { ImagePreviewDialog(it) { preview = null } }
}
