package dev.undefinedteam.wearagent.presentation.chat.images

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

internal fun thumbnail(context: Context, uri: android.net.Uri): ImageBitmap? = runCatching {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > THUMB_EDGE) {
            val scale = THUMB_EDGE.toFloat() / longest
            decoder.setTargetSize(
                (info.size.width * scale).toInt().coerceAtLeast(1),
                (info.size.height * scale).toInt().coerceAtLeast(1),
            )
        }
    }
    bitmap.asImageBitmap()
}.getOrNull()

internal fun Context.hasImagePermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED

internal const val COLUMNS = 3
internal val GRID_WIDTH = 208.dp
internal val TILE = 58.dp
internal val TILE_GAP = 6.dp
internal val BUTTON_SIZE = 34.dp

internal const val MAX_SELECTION = 10
internal const val THUMB_EDGE = 256
internal const val CANCEL_GLYPH = "\u00D7"
internal const val DONE_GLYPH = "\u2713"
internal const val BUSY_GLYPH = "\u2026"

@Composable
internal fun RoundActionButton(
    glyph: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(BUTTON_SIZE)
            .clip(CircleShape)
            .background(
                when {
                    !enabled -> scheme.surfaceContainerHigh
                    destructive -> scheme.errorContainer
                    else -> scheme.primary
                }
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = when {
                !enabled -> scheme.onSurfaceVariant
                destructive -> scheme.onErrorContainer
                else -> scheme.onPrimary
            },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
