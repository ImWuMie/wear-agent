package dev.undefinedteam.wearagent.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

/** A full-width transformed button row. */
@Composable
internal fun SettingRow(
    scope: TransformingLazyColumnItemScope,
    spec: TransformationSpec,
    label: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = with(scope) {
            Modifier
                .transformedHeight(this, spec)
                .fillMaxWidth()
        },
        transformation = with(scope) { SurfaceTransformation(spec) },
    ) { Text(label) }
}

/** A full-width transformed radio row. */
@Composable
internal fun SettingToggle(
    scope: TransformingLazyColumnItemScope,
    spec: TransformationSpec,
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    RadioButton(
        selected = selected,
        onSelect = onSelect,
        modifier = with(scope) {
            Modifier
                .transformedHeight(this, spec)
                .fillMaxWidth()
        },
        transformation = with(scope) { SurfaceTransformation(spec) },
        label = { Text(label) },
    )
}

/** A full-width transformed switch row. */
@Composable
internal fun SettingSwitch(
    scope: TransformingLazyColumnItemScope,
    spec: TransformationSpec,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = with(scope) {
            Modifier
                .transformedHeight(this, spec)
                .fillMaxWidth()
        },
        transformation = with(scope) { SurfaceTransformation(spec) },
        label = { Text(label) },
    )
}

/** Transformed plain item wrapper for non-row content. */
@Composable
internal fun Scaled(
    scope: TransformingLazyColumnItemScope,
    spec: TransformationSpec,
    content: @Composable () -> Unit,
) {
    Column(
        with(scope) { Modifier.transformedHeight(this, spec) },
    ) { content() }
}

/** Inline text field with the label shown while empty, saving on each keystroke. */
@Composable
internal fun SettingField(label: String, value: String, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    BasicTextField(
        text,
        {
            text = it
            onSave(it)
        },
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        singleLine = true,
        decorationBox = { inner ->
            if (text.isEmpty()) Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            inner()
        },
    )
}
