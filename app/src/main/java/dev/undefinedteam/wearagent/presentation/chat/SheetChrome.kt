package dev.undefinedteam.wearagent.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.undefinedteam.wearagent.R


@Composable
internal fun ActionButton(running: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (running) scheme.error else scheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (running) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(scheme.onError)
            )
        } else {
            Text(
                "\u2191",
                color = scheme.onPrimary,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** Voice capture row: press-and-hold to record; live partial transcript while held. */
@Composable
internal fun SheetRow(label: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Text(
            label,
            Modifier
                .fillMaxWidth()
                .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
                .padding(horizontal = 14.dp, vertical = 9.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun Handle(progression: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(28.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(
                MaterialTheme.colorScheme.onSurface.copy(
                    alpha = 0.4f * progression.coerceIn(
                        0f,
                        1f
                    )
                )
            ),
    )
}

@Composable
internal fun DraftField(
    value: String,
    onValueChange: (String) -> Unit,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
    onSend: () -> Unit
) {
    BasicTextField(
        value, onValueChange,
        modifier
            .fillMaxWidth()
            .focusRequester(focus),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSend() }),
        singleLine = true,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(
                    stringResource(R.string.input_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                inner()
            }
        },
    )
}
