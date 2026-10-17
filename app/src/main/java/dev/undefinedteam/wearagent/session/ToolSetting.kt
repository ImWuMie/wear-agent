package dev.undefinedteam.wearagent.session

import androidx.annotation.StringRes

/** A tool declares this so Settings can render its toggle from the tool list instead of hard-coding one. */
data class ToolSetting(
    val key: String,
    @StringRes val labelRes: Int,
    @StringRes val noteRes: Int? = null,
)
