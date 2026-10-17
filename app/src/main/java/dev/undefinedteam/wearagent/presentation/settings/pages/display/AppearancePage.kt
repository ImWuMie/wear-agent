package dev.undefinedteam.wearagent.presentation.settings.pages.display

import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.presentation.settings.SettingsPage
import dev.undefinedteam.wearagent.presentation.settings.settingsPage

/** Appearance: curved list and round-fit bubble layouts. */
val AppearancePage: SettingsPage = settingsPage(
    "appearance",
    parent = DisplayPage,
    titleRes = R.string.appearance,
) { list ->
    list.switch(R.string.curved_list, current.curvedList) { value ->
        launch { store.setCurvedList(value) }
    }
    list.switch(R.string.round_fit, current.roundFit) { value ->
        launch { store.setRoundFit(value) }
    }
    list.switch(R.string.math_fit_width, current.mathFitWidth) { value ->
        launch { store.setMathFitWidth(value) }
    }
}
