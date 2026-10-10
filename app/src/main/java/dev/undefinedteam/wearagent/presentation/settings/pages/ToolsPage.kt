package dev.undefinedteam.wearagent.presentation.settings.pages

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.presentation.settings.SettingsPage
import dev.undefinedteam.wearagent.presentation.settings.settingsPage

/** Tools category: agent tool toggles with privacy notes. */
val ToolsPage: SettingsPage = settingsPage("tools", titleRes = R.string.tools) { list ->
    list.switch(R.string.web_search, current.webSearchEnabled) { value ->
        launch { store.setWebSearchEnabled(value) }
    }
    list.custom {
        Text(
            stringResource(R.string.web_search_privacy),
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
