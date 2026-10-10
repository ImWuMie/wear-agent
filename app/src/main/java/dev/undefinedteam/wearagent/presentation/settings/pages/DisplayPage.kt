package dev.undefinedteam.wearagent.presentation.settings.pages

import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.presentation.settings.SettingsPage
import dev.undefinedteam.wearagent.presentation.settings.settingsPage

/** Display category: curved list and round-fit bubble layouts. */
val DisplayPage: SettingsPage = settingsPage("display", titleRes = R.string.display) { list ->
    list.switch(R.string.curved_list, current.curvedList) { value ->
        launch { store.setCurvedList(value) }
    }
    list.switch(R.string.round_fit, current.roundFit) { value ->
        launch { store.setRoundFit(value) }
    }
}
