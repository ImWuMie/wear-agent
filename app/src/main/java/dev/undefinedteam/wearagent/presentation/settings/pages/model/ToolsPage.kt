package dev.undefinedteam.wearagent.presentation.settings.pages.model

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.agent.tools.defaultTools
import dev.undefinedteam.wearagent.presentation.settings.SettingsPage
import dev.undefinedteam.wearagent.presentation.settings.settingsPage

/**
 * Tools: one toggle per tool that declares a settings entry, followed by its
 * privacy note. Adding a tool with a setting needs no change here.
 */
val ToolsPage: SettingsPage = settingsPage(
    "tools",
    parent = ModelPage,
    titleRes = R.string.tools,
) { list ->
    defaultTools().mapNotNull { it.setting }.forEach { setting ->
        list.switch(setting.labelRes, current.toolEnabled(setting.key)) { value ->
            launch { store.setToolFlag(setting.key, value) }
        }
        setting.noteRes?.let { note ->
            list.custom {
                Text(
                    stringResource(note),
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
