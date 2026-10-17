package dev.undefinedteam.wearagent.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
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
    SettingToggle(scope, spec, { Text(label) }, selected, onSelect)
}

/** A full-width transformed radio row whose label is composed at render time. */
@Composable
internal fun SettingToggle(
    scope: TransformingLazyColumnItemScope,
    spec: TransformationSpec,
    label: @Composable RowScope.() -> Unit,
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
        label = label,
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

/**
 * Transformed container for free-form content: reserves the transformed height
 * and applies the container scale/fade, exactly like `PageList.custom`. Use this
 * (not [Scaled]) when the item has no Material surface of its own and must still
 * scale and fade as it crosses the bezel.
 */
@Composable
internal fun TransformedItem(
    scope: TransformingLazyColumnItemScope,
    spec: TransformationSpec,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable TransformingLazyColumnItemScope.() -> Unit,
) {
    val transformation = with(scope) { SurfaceTransformation(spec) }
    Column(
        with(scope) {
            Modifier
                .transformedHeight(this, spec)
                .graphicsLayer { with(transformation) { applyContainerTransformation() } }
                .fillMaxWidth()
        }.then(modifier),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = verticalArrangement,
    ) { scope.content() }
}

/**
 * Large multi-line editor for free-form text, like the chat's message editor:
 * a rounded surface that grows to [minHeight] and scrolls once it exceeds
 * [maxHeight]. Local state is keyed on [identity] (the edited record's id), so
 * a store emission after each keystroke does not reset the caret.
 */
@Composable
internal fun SettingTextArea(
    identity: Any,
    label: String,
    value: String,
    onSave: (String) -> Unit,
    minHeight: Dp = 120.dp,
    maxHeight: Dp = 240.dp,
) {
    var text by remember(identity) { mutableStateOf(value) }
    BasicTextField(
        text,
        {
            text = it
            onSave(it)
        },
        Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight, max = maxHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        decorationBox = { inner ->
            if (text.isEmpty()) Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            inner()
        },
    )
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
