package dev.undefinedteam.wearagent.presentation.settings.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.undefinedteam.wearagent.BuildConfig
import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.presentation.settings.SettingsPage
import dev.undefinedteam.wearagent.presentation.settings.settingsPage

private const val REPO_URL = "https://github.com/ImWuMie/wear-agent"

/** About category: app identity, version, repo and related links. */
val AboutPage: SettingsPage = settingsPage("about", titleRes = R.string.about) { list ->
    list.custom(
        Modifier.padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painterResource(R.drawable.ic_app_icon),
            contentDescription = null,
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Fit,
        )
        Text(
            stringResource(R.string.app_name),
            Modifier.padding(top = 5.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "v" + BuildConfig.VERSION_NAME,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.about_tagline),
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, start = 10.dp, end = 10.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
    list.custom(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { openUrl(REPO_URL) }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_github),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                REPO_URL.removePrefix("https://"),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    list.custom(
        Modifier.padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinkRow(stringResource(R.string.about_issues), "issues") { openUrl("$REPO_URL/issues") }
        LinkRow(stringResource(R.string.about_license), "blob/main/LICENSE") {
            openUrl("$REPO_URL/blob/main/LICENSE")
        }
    }
}

/** One About link: a muted label followed by its blue path, tappable as a whole. */
@Composable
private fun LinkRow(label: String, path: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            path,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
