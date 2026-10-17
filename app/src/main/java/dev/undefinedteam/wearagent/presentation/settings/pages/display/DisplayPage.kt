package dev.undefinedteam.wearagent.presentation.settings.pages.display

import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.presentation.settings.SettingsPage
import dev.undefinedteam.wearagent.presentation.settings.settingsPage

/** Display category: how the chat is laid out, and the UI language. */
val DisplayPage: SettingsPage = settingsPage("display", titleRes = R.string.display) { list ->
    list.row(R.string.appearance) { navigate(AppearancePage) }
    list.row(R.string.language) { navigate(LanguagePage) }
}
