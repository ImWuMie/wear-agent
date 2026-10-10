package dev.undefinedteam.wearagent.presentation.settings.pages

import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.presentation.settings.SettingsPage
import dev.undefinedteam.wearagent.presentation.settings.settingsPage
import dev.undefinedteam.wearagent.session.InputMode

/** Input category: keyboard vs voice, and IME send behavior. */
val InputPage: SettingsPage = settingsPage("input", titleRes = R.string.input_mode) { list ->
    list.toggle(R.string.mode_keyboard, current.inputMode == InputMode.KEYBOARD) {
        launch { store.setInputMode(InputMode.KEYBOARD) }
    }
    list.toggle(R.string.mode_voice, current.inputMode == InputMode.VOICE) {
        launch { store.setInputMode(InputMode.VOICE) }
    }
    list.subHeader(R.string.ime_options)
    list.switch(R.string.ime_send_now, current.imeSends) { value ->
        launch { store.setImeSends(value) }
    }
}
