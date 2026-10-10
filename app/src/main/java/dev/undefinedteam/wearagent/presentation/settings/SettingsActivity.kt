package dev.undefinedteam.wearagent.presentation.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.material3.AppScaffold
import dev.undefinedteam.wearagent.presentation.LocaleHelper
import dev.undefinedteam.wearagent.presentation.theme.WearAgentTheme
import dev.undefinedteam.wearagent.session.SessionLog
import dev.undefinedteam.wearagent.session.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class SettingsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        val lang = runBlocking {
            runCatching { SettingsStore(newBase).settings.first().language }.getOrDefault("")
        }
        super.attachBaseContext(LocaleHelper.wrap(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WearAgentTheme {
                AppScaffold {
                    SettingsScreen(
                        SettingsStore(this@SettingsActivity),
                        SessionLog(this@SettingsActivity)
                    )
                }
            }
        }
    }
}
