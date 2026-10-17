package dev.undefinedteam.wearagent.presentation.settings.pages.chat

import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.presentation.settings.SettingsPage
import dev.undefinedteam.wearagent.presentation.settings.settingsPage

/** Chat category: which session is active, and how the keyboard sends. */
val ChatPage: SettingsPage = settingsPage("chat", titleRes = R.string.chat) { list ->
    list.row(R.string.sessions) { navigate(SessionsPage) }
    list.row(R.string.ime_options) { navigate(InputPage) }
}
