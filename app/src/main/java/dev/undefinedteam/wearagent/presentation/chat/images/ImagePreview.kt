package dev.undefinedteam.wearagent.presentation.chat.images

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.agent.ImageAttachment
import dev.undefinedteam.wearagent.presentation.chat.ActionPageButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64

/** Thumbnail edge; the strips scroll, so only one tile has to fit at a time. */
internal val THUMBNAIL = 64.dp

/** Null when the bytes will not decode; a broken image must not break the list. */
internal fun decodeImage(base64: String): ImageBitmap? = runCatching {
    val bytes = Base64.getDecoder().decode(base64)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

/**
 * Decodes [images] off the main thread, preserving order with a null slot for
 * anything undecodable. A session replays its images on every load.
 */
@Composable
internal fun rememberDecodedImages(images: List<ImageAttachment>): List<ImageBitmap?> {
    val decoded by produceState(initialValue = emptyList(), images) {
        value = withContext(Dispatchers.Default) { images.map { decodeImage(it.base64) } }
    }
    return decoded
}

/**
 * Full-screen preview: double-tap animates between fit and zoom, pinch scales,
 * drag pans while zoomed, long-press opens the menu, back closes. No tap-to-dismiss
 * — a stray tap while inspecting an image should not throw it away.
 */
@Composable
@SuppressLint("LocalContextGetResourceValueCall")
internal fun ImagePreviewDialog(image: ImageAttachment, onDismiss: () -> Unit) {
    val bitmap = rememberDecodedImages(listOf(image)).firstOrNull()
    // Animatable, so a double-tap glides instead of snapping while a pinch can still
    // set the value directly and cancel the animation.
    val scale = remember { Animatable(MIN_SCALE) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    BackHandler {
        if (menu) menu = false else onDismiss()
    }
    // Resetting the pan rides the same animation, so the image does not jump
    // off-centre when the zoom ends.
    fun toggleZoom() {
        val zoomedOut = scale.value <= MIN_SCALE
        scope.launch {
            if (zoomedOut) {
                launch { scale.animateTo(DOUBLE_TAP_SCALE, ZOOM_SPEC) }
            } else {
                launch { offsetX.animateTo(0f, ZOOM_SPEC) }
                launch { offsetY.animateTo(0f, ZOOM_SPEC) }
                launch { scale.animateTo(MIN_SCALE, ZOOM_SPEC) }
            }
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(PREVIEW_BACKDROP)
                // Transform before taps: a pan must not be read as a tap.
                .pointerInput(bitmap) {
                    if (bitmap == null) return@pointerInput
                    detectTransformGestures { _, pan, zoom, _ ->
                        // snapTo, not animateTo: the image tracks the gesture frame by
                        // frame, and a running double-tap animation is cancelled.
                        val next = (scale.value * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        scope.launch { scale.snapTo(next) }
                        // Only while zoomed in: at fit scale panning would drift the
                        // image off-centre for no reason.
                        if (next > MIN_SCALE) {
                            val limit = (next - MIN_SCALE) * size.width / 2f
                            scope.launch {
                                offsetX.snapTo((offsetX.value + pan.x).coerceIn(-limit, limit))
                                offsetY.snapTo((offsetY.value + pan.y).coerceIn(-limit, limit))
                            }
                        }
                    }
                }
                .pointerInput(bitmap) {
                    if (bitmap == null) return@pointerInput
                    detectTapGestures(
                        onDoubleTap = { toggleZoom() },
                        onLongPress = { menu = true },
                    )
                }
                .padding(PREVIEW_INSET),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap,
                    contentDescription = stringResource(R.string.image_preview),
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                            translationX = offsetX.value
                            translationY = offsetY.value
                        },
                    contentScale = ContentScale.Fit,
                )
            }
        }
        if (menu) {
            PreviewMenu(
                onSave = {
                    menu = false
                    scope.launch {
                        val ok = saveToGallery(context, image)
                        Toast.makeText(
                            context,
                            context.getString(
                                if (ok) R.string.saved_to_gallery else R.string.save_failed
                            ),
                            Toast.LENGTH_SHORT,
                        ).show()
                        if (ok) onDismiss()
                    }
                },
                onClose = {
                    menu = false
                    onDismiss()
                },
            )
        }
    }
}

/**
 * Long-press menu, built from the same pieces as the message action page — a
 * curved `TransformingLazyColumn` of `ActionPageButton`s — so the two read as one
 * surface. Tapping outside dismisses it without touching the image.
 */
@Composable
private fun PreviewMenu(onSave: () -> Unit, onClose: () -> Unit) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable { onClose() },
    ) {
        ScreenScaffold(scrollState = state) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                TransformingLazyColumn(
                    Modifier.width(200.dp),
                    state = state,
                    contentPadding = PaddingValues(top = 36.dp, bottom = 56.dp),
                    // Centred: two rows at the top would sit where the bezel is
                    // narrowest, clipping their ends.
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    item {
                        ActionPageButton(this, spec, stringResource(R.string.save_to_gallery)) {
                            onSave()
                        }
                    }
                    item {
                        ActionPageButton(this, spec, stringResource(R.string.close_preview)) {
                            onClose()
                        }
                    }
                }
            }
        }
    }
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 2.5f

/** Soft in, soft out: a spring would overshoot and make a large zoom wobble. */
private val ZOOM_SPEC = tween<Float>(durationMillis = 260, easing = FastOutSlowInEasing)

/** Keeps the fitted image inside the round bezel instead of clipping its corners. */
private val PREVIEW_INSET = 18.dp

/**
 * Near-opaque black behind the preview: the image should read as the only thing
 * on screen, and the chat behind it must not compete.
 */
private val PREVIEW_BACKDROP = Color(0xF2000000)
