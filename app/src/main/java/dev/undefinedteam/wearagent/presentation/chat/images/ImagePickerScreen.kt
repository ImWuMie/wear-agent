package dev.undefinedteam.wearagent.presentation.chat.images

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.presentation.settings.TransformedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app's own image picker: a black screen, a title, and a curved grid of circular
 * thumbnails that scale and fade at the bezel — the system photo picker's square grid
 * gets clipped by the round bezel.
 */
@Composable
internal fun ImagePickerScreen(
    onConfirm: (List<android.net.Uri>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var images by remember { mutableStateOf<List<MediaImage>?>(null) }
    var selected by remember { mutableStateOf(emptyList<Long>()) }
    // Guards against a second tap while the caller is finishing the pick.
    var busy by remember { mutableStateOf(false) }
    // Without the grant the query is silently empty, so the screen tracks the
    // permission itself to tell "no images" from "not allowed".
    var granted by remember { mutableStateOf(context.hasImagePermission()) }
    var asked by remember { mutableStateOf(false) }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        asked = true
    }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(granted) {
        images = if (granted) queryMediaImages(context) else null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            !granted -> PermissionPrompt(
                denied = asked,
                onGrant = { requestPermission.launch(Manifest.permission.READ_MEDIA_IMAGES) },
                onDismiss = onDismiss,
            )

            images == null -> Centered(stringResource(R.string.loading_images))
            images!!.isEmpty() -> Centered(stringResource(R.string.no_images))
            else -> PickerGrid(
                images = images!!,
                selected = selected,
                busy = busy,
                onToggle = { image ->
                    val order = selected.indexOf(image.id)
                    selected = when {
                        order >= 0 -> selected - image.id
                        selected.size < MAX_SELECTION -> selected + image.id
                        else -> selected
                    }
                },
                onConfirm = {
                    val picked = images!!.filter { it.id in selected }
                    if (picked.isEmpty()) onDismiss() else onConfirm(picked.map { it.uri })
                },
                onCancel = onDismiss,
            )
        }
    }
}

@Composable
private fun PickerGrid(
    images: List<MediaImage>,
    selected: List<Long>,
    busy: Boolean,
    onToggle: (MediaImage) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = state) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            TransformingLazyColumn(
                Modifier.width(GRID_WIDTH),
                state = state,
                contentPadding = PaddingValues(top = 12.dp, bottom = 64.dp),
            ) {
                item {
                    TransformedItem(this, spec) {
                        Text(
                            stringResource(R.string.images),
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                val rows = images.chunked(COLUMNS)
                items(rows.size) { rowIndex ->
                    TransformedItem(this, spec) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(
                                TILE_GAP,
                                Alignment.CenterHorizontally
                            ),
                        ) {
                            rows[rowIndex].forEach { image ->
                                val order = selected.indexOf(image.id)
                                CircleTile(image, order >= 0, order + 1) { onToggle(image) }
                            }
                        }
                    }
                }
            }
        }
    }
    Row(
        Modifier
            .fillMaxSize()
            .padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Bottom,
    ) {
        RoundActionButton(CANCEL_GLYPH, enabled = !busy, destructive = true, onClick = onCancel)
        RoundActionButton(
            if (busy) BUSY_GLYPH else DONE_GLYPH,
            enabled = !busy,
            onClick = onConfirm
        )
    }
}

@Composable
private fun CircleTile(
    image: MediaImage,
    selected: Boolean,
    order: Int,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, image.uri) {
        value = withContext(Dispatchers.IO) { thumbnail(context, image.uri) }
    }
    Box(
        Modifier
            .size(TILE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                it,
                contentDescription = image.name,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        if (selected) {
            Box(Modifier
                .fillMaxSize()
                .background(Color(0x66000000)))
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    order.toString(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun PermissionPrompt(denied: Boolean, onGrant: () -> Unit, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(if (denied) R.string.images_denied else R.string.images_permission),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            if (denied) {
                RoundActionButton(
                    CANCEL_GLYPH,
                    enabled = true,
                    destructive = true,
                    onClick = onDismiss
                )
            } else {
                RoundActionButton(DONE_GLYPH, enabled = true, onClick = onGrant)
            }
        }
    }
}

@Composable
private fun Centered(message: String) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}