package dev.undefinedteam.wearagent.presentation.chat.images

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.undefinedteam.wearagent.agent.ImageAttachment

/**
 * The attachments staged for the next send. Two tiles per row; tapping one opens
 * it full-screen, and its corner control removes it. On a round screen a
 * long-press menu would hide under the bezel, so removal is a direct tap.
 */
@Composable
internal fun AttachmentStrip(
    images: List<ImageAttachment>,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty()) return
    val bitmaps = rememberDecodedImages(images)
    var preview by remember { mutableStateOf<ImageAttachment?>(null) }
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        bitmaps.withIndex().chunked(THUMBNAILS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (index, bitmap) ->
                    Box {
                        if (bitmap != null) Image(
                            bitmap,
                            contentDescription = null,
                            Modifier
                                .size(THUMBNAIL)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { preview = images[index] },
                            contentScale = ContentScale.Crop,
                        )
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                                .clickable { onRemove(index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "\u00D7",
                                color = MaterialTheme.colorScheme.onError,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
    preview?.let { ImagePreviewDialog(it) { preview = null } }
}

/** Picker entry point, sized to sit beside the input field. */
@Composable
internal fun AttachButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "+",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
