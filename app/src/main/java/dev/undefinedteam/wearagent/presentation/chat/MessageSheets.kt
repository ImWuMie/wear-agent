package dev.undefinedteam.wearagent.presentation.chat

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.session.ChatMessage

@Composable
internal fun MessageActionSheet(
    message: ChatMessage,
    onDismiss: () -> Unit,
    onDelete: (Long) -> Unit,
    onRegenerate: (Long) -> Unit,
    onEdit: (ChatMessage) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var selecting by remember { mutableStateOf(false) }
    BackHandler(onBack = onDismiss)
    if (selecting) {
        SelectTextDialog(message.text) { selecting = false }
        return
    }
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ScreenScaffold(scrollState = state) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                TransformingLazyColumn(
                    Modifier
                        .width(200.dp),
                    state = state,
                    contentPadding = PaddingValues(top = 36.dp, bottom = 56.dp),
                ) {
                    item {
                        ActionPageButton(this, spec, stringResource(R.string.copy_message)) {
                            clipboard.setText(AnnotatedString(message.text))
                            Toast.makeText(
                                context,
                                context.getString(R.string.copied),
                                Toast.LENGTH_SHORT
                            ).show()
                            onDismiss()
                        }
                    }
                    item {
                        ActionPageButton(
                            this,
                            spec,
                            stringResource(R.string.select_text)
                        ) { selecting = true }
                    }
                    item {
                        ActionPageButton(this, spec, stringResource(R.string.regenerate)) {
                            onRegenerate(message.id)
                            onDismiss()
                        }
                    }
                    item {
                        ActionPageButton(this, spec, stringResource(R.string.edit_message)) {
                            onEdit(message)
                            onDismiss()
                        }
                    }
                    item {
                        ActionPageButton(
                            this, spec, stringResource(R.string.delete_message),
                            destructive = true,
                        ) {
                            onDelete(message.id)
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ActionPageButton(
    scope: TransformingLazyColumnItemScope,
    spec: TransformationSpec,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Column(Modifier.transformedHeight(scope, spec)) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            transformation = with(scope) { SurfaceTransformation(spec) },
            colors = if (destructive) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
        ) { Text(label) }
    }
}

@Composable
private fun SelectTextDialog(text: String, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ScreenScaffold(scrollState = state) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                TransformingLazyColumn(
                    Modifier.width(200.dp),
                    state = state,
                    contentPadding = PaddingValues(top = 36.dp, bottom = 56.dp),
                ) {
                    item {
                        Column(Modifier.transformedHeight(this, spec)) {
                            Text(
                                stringResource(R.string.select_text),
                                Modifier.padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item {
                        Column(Modifier.transformedHeight(this, spec)) {
                            SelectionContainer {
                                Text(
                                    text,
                                    Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun EditMessageDialog(
    message: ChatMessage,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(message.id) { mutableStateOf(message.text) }
    BackHandler(onBack = onDismiss)
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ScreenScaffold(scrollState = state) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                TransformingLazyColumn(
                    Modifier.width(200.dp),
                    state = state,
                    contentPadding = PaddingValues(top = 36.dp, bottom = 56.dp),
                ) {
                    item {
                        Column(Modifier.transformedHeight(this, spec)) {
                            Text(
                                stringResource(R.string.edit_message),
                                Modifier.padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item {
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 240.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(10.dp)
                                .verticalScroll(rememberScrollState()),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        )
                    }
                    item {
                        ActionPageButton(this, spec, stringResource(R.string.save)) {
                            onSave(text)
                        }
                    }
                    item {
                        ActionPageButton(
                            this,
                            spec,
                            stringResource(R.string.cancel)
                        ) { onDismiss() }
                    }
                }
            }
        }
    }
}
