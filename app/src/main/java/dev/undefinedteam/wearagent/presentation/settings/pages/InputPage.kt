package dev.undefinedteam.wearagent.presentation.settings.pages

import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.presentation.settings.SettingsPage
import dev.undefinedteam.wearagent.presentation.settings.settingsPage

/** Input category: how the on-screen keyboard sends. */
val InputPage: SettingsPage = settingsPage("input", titleRes = R.string.ime_options) { list ->
    list.switch(R.string.ime_send_now, current.imeSends) { value ->
        launch { store.setImeSends(value) }
    }
}
