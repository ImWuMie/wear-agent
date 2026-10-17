package dev.undefinedteam.wearagent.presentation.settings.pages.model

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.Text
import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.presentation.settings.SettingsPage
import dev.undefinedteam.wearagent.presentation.settings.settingsPage

/** Which prompt the editor child page is editing; shared with it. */
private class PromptsState {
    val editingId = mutableStateOf("")
}

/**
 * System prompts: a master switch, then the saved prompts to pick from, with
 * create/edit/delete for the selected one — the same shape as Sessions. The
 * prompt list comes from [SettingsPageScope.current], which re-emits on every
 * change, so items re-render without a local snapshot.
 */
val SystemPromptPage: SettingsPage = settingsPage(
    "system_prompt",
    parent = ModelPage,
    titleRes = R.string.system_prompt,
) { list ->
    val state = shared<PromptsState>("prompts") { PromptsState() }

    list.switch(R.string.system_prompt_enabled, current.systemPromptEnabled) { value ->
        launch { store.setSystemPromptEnabled(value) }
    }
    list.row(R.string.new_system_prompt) {
        launch { store.addSystemPrompt("") }
    }
    current.systemPrompts.forEach { prompt ->
        val selected = prompt.id == current.systemPromptId
        list.toggle(selected, { launch { store.selectSystemPrompt(prompt.id) } }) {
            Text(prompt.name.ifBlank { stringResource(R.string.untitled_prompt) })
        }
        if (selected) {
            list.row(R.string.edit_system_prompt) {
                state.editingId.value = prompt.id
                navigate(SystemPromptDetailPage)
            }
        }
    }
}

/** Editor for one saved prompt: its name and its text. */
val SystemPromptDetailPage: SettingsPage = settingsPage(
    "system_prompt_detail",
    parent = SystemPromptPage,
    titleRes = R.string.system_prompt,
) { list ->
    val state = shared<PromptsState>("prompts") { PromptsState() }
    val prompt = current.systemPrompts.firstOrNull { it.id == state.editingId.value }
        ?: return@settingsPage

    list.subHeader(R.string.prompt_name)
    list.field(R.string.prompt_name, prompt.name) { value ->
        launch { store.updateSystemPrompt(prompt.id) { it.copy(name = value) } }
    }
    list.subHeader(R.string.system_prompt)
    list.area(prompt.id, R.string.system_prompt_hint, prompt.text) { value ->
        launch { store.updateSystemPrompt(prompt.id) { it.copy(text = value) } }
    }
    list.row(R.string.delete_system_prompt) {
        launch {
            store.deleteSystemPrompt(prompt.id)
            back()
        }
    }
}
