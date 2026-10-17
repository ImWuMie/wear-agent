package dev.undefinedteam.wearagent.session

import androidx.annotation.StringRes

/**
 * A tool's entry in Settings: the stored key, its label, and an optional privacy
 * note. A tool declares this so the settings page can be generated from the tool
 * list instead of hard-coding each toggle.
 */
data class ToolSetting(
    val key: String,
    @StringRes val labelRes: Int,
    @StringRes val noteRes: Int? = null,
)
