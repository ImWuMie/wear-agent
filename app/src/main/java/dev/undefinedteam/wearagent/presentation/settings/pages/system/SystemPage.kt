package dev.undefinedteam.wearagent.presentation.settings.pages.system

import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.presentation.settings.SettingsPage
import dev.undefinedteam.wearagent.presentation.settings.settingsPage

/** System category: app-level behavior that is not tied to one screen. */
val SystemPage: SettingsPage = settingsPage("system", titleRes = R.string.system) { list ->
    list.switch(R.string.confirm_exit, current.confirmExit) { value ->
        launch { store.setConfirmExit(value) }
    }
}
