package dev.undefinedteam.wearagent.presentation.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.agent.TurnState
import dev.undefinedteam.wearagent.session.AgentSettings
import dev.undefinedteam.wearagent.session.InputMode
import dev.undefinedteam.wearagent.session.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
internal fun PullSheet(
    pull: MutableFloatState,
    inputStop: Float,
    configStop: Float,
    settings: SettingsStore,
    turn: MutableStateFlow<TurnState>,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onVoice: () -> Unit,
    onOpenSettings: () -> Unit,
    stage: Int,
    onRelease: () -> Unit,
) {
    val shown = pull.floatValue
    if (shown > 0f) BackHandler { pull.floatValue = 0f }
    val inputShown = shown.coerceAtMost(inputStop) / inputStop
    val settingsShown = ((shown - inputStop) / (configStop - inputStop)).coerceIn(0f, 1f)
    val inputHeight by animateDpAsState(
        40.dp * inputShown,
        spring(dampingRatio = 0.8f, stiffness = 380f),
        label = "input"
    )
    val settingsHeight by animateDpAsState(
        44.dp * settingsShown,
        spring(dampingRatio = 0.8f, stiffness = 380f),
        label = "settings"
    )
    val max = if (stage == 0) inputStop else configStop
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        if (shown > 0f) Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectTapGestures {
                        pull.floatValue = 0f
                        onRelease()
                    }
                },
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(16.dp)
                .pointerInput(max) {
                    detectVerticalDragGestures(
                        onDragEnd = { onRelease() },
                        onDragCancel = { onRelease() },
                    ) { _, drag ->
                        pull.floatValue = (pull.floatValue - drag).coerceIn(0f, max)
                    }
                },
        )
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 9.dp)
                .fillMaxWidth()
                .pointerInput(max) {
                    detectVerticalDragGestures(
                        onDragEnd = { onRelease() },
                        onDragCancel = { onRelease() },
                    ) { _, drag ->
                        pull.floatValue = (pull.floatValue - drag).coerceIn(0f, max)
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Handle(inputShown.coerceAtLeast(settingsShown).coerceAtLeast(0.6f))
            AnimatedVisibility(
                visible = shown > 0f,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(120))
            ) {
                Spacer(Modifier.height(5.dp))
            }
            Box(Modifier.fillMaxWidth(0.86f), contentAlignment = Alignment.Center) {
                SheetStack(
                    inputShown,
                    inputHeight,
                    settingsShown,
                    settingsHeight,
                    settings,
                    turn,
                    onSend,
                    onStop,
                    onVoice,
                    onOpenSettings
                )
            }
        }
    }
}

@Composable
private fun SheetStack(
    inputShown: Float,
    inputHeight: Dp,
    settingsShown: Float,
    settingsHeight: Dp,
    settings: SettingsStore,
    turn: MutableStateFlow<TurnState>,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onVoice: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val current by settings.settings.collectAsStateWithLifecycle(AgentSettings())
    val running = turn.collectAsStateWithLifecycle().value.running
    var draft by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        if (!running && draft.isNotBlank()) {
            keyboard?.hide()
            focusManager.clearFocus(force = true)
            onSend(draft)
            draft = ""
        }
    }
    val dismissKeyboard = {
        keyboard?.hide()
        focusManager.clearFocus(force = true)
    }
    Column(Modifier.fillMaxWidth(0.86f), horizontalAlignment = Alignment.CenterHorizontally) {
        if (inputShown > 0f) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(inputHeight.coerceAtLeast(1.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (current.inputMode == InputMode.VOICE && !running) {
                        SheetRow(stringResource(R.string.hold_to_talk), onClick = onVoice)
                    } else {
                        DraftField(
                            draft,
                            { draft = it },
                            focus,
                            Modifier.clickable { focus.requestFocus() }) {
                            if (current.imeSends) submit() else dismissKeyboard()
                        }
                    }
                }
                ActionButton(running, Modifier.padding(start = 4.dp)) {
                    if (running) onStop() else submit()
                }
            }
        }
        AnimatedVisibility(
            visible = settingsShown > 0f,
            enter = slideInVertically(
                spring(
                    dampingRatio = 0.8f,
                    stiffness = 380f
                )
            ) { it / 2 } + fadeIn(),
            exit = slideOutVertically(tween(150)) { it / 2 } + fadeOut(),
        ) {
            SheetRow(
                stringResource(R.string.settings),
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .height(settingsHeight.coerceAtLeast(1.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(enabled = settingsShown == 1f, onClick = onOpenSettings),
            )
        }
    }
}

@Composable
private fun ActionButton(running: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
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

@Composable
private fun SheetRow(label: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
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
private fun Handle(progression: Float, modifier: Modifier = Modifier) {
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
private fun DraftField(
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

internal fun snapPull(
    pull: MutableFloatState,
    stage: Int,
    inputStop: Float,
    configStop: Float
): Int {
    val value = pull.floatValue
    val next = when {
        stage == 0 && value > 18f -> inputStop
        stage > 0 && value < inputStop -> 0f
        stage > 0 && value > inputStop + 18f -> configStop
        stage > 0 -> inputStop
        else -> 0f
    }
    pull.floatValue = next
    return when (next) {
        configStop -> 2
        inputStop -> 1
        else -> 0
    }
}
