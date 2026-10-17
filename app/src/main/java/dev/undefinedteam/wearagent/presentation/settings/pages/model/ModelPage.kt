package dev.undefinedteam.wearagent.presentation.settings.pages.model

import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.presentation.settings.SettingsPage
import dev.undefinedteam.wearagent.presentation.settings.settingsPage

/**
 * Model category: everything that shapes what is sent to the provider — the
 * endpoint and its credentials, the tools offered, and the system prompt.
 */
val ModelPage: SettingsPage = settingsPage("model", titleRes = R.string.model) { list ->
    list.row(R.string.endpoints) { navigate(EndpointsPage) }
    list.row(R.string.tools) { navigate(ToolsPage) }
    list.row(R.string.system_prompt) { navigate(SystemPromptPage) }
}
