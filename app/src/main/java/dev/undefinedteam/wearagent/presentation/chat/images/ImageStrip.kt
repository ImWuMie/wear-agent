package dev.undefinedteam.wearagent.presentation.chat.images

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
 * One message's attachments: tappable thumbnails in a single scrolling row, so many
 * images stay one row tall. Tapping one opens it full-screen.
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
    // Scrolling rather than wrapping: otherwise the bubble grows without bound and
    // pushes the text off the round screen.
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        bitmaps.withIndex().forEach { (index, bitmap) ->
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
    preview?.let { ImagePreviewDialog(it) { preview = null } }
}
