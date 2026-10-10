package dev.undefinedteam.wearagent.presentation.settings.pages

import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.presentation.settings.SettingsPage
import dev.undefinedteam.wearagent.presentation.settings.settingsPage

/** Language category: system / Chinese / English, applied via activity recreate. */
val LanguagePage: SettingsPage = settingsPage("language", titleRes = R.string.language) { list ->
    list.toggle(R.string.lang_system, current.language.isBlank()) {
        launch {
            store.setLanguage("")
            recreate()
        }
    }
    list.toggle(R.string.lang_zh, current.language == "zh") {
        launch {
            store.setLanguage("zh")
            recreate()
        }
    }
    list.toggle(R.string.lang_en, current.language == "en") {
        launch {
            store.setLanguage("en")
            recreate()
        }
    }
}
