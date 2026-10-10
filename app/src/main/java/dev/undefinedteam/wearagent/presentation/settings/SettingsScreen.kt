package dev.undefinedteam.wearagent.presentation.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.remote.interactions.RemoteActivityHelper
import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.session.AgentSettings
import dev.undefinedteam.wearagent.session.SessionLog
import dev.undefinedteam.wearagent.session.SettingsStore
import kotlinx.coroutines.launch

/**
 * Settings navigation shell. Page content lives in the page files; this only
 * hosts the animated page switcher, the back behavior, and the
 * [SettingsPageScope] implementation shared by all pages.
 */
@Composable
internal fun SettingsScreen(store: SettingsStore, sessions: SessionLog) {
    // Touching the manifest triggers each page file's class init, which
    // self-registers its category (and child pages declared in that file).
    SettingsPageFiles

    val current by store.settings.collectAsStateWithLifecycle(AgentSettings())
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    var direction by remember { mutableStateOf(1) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val shared = remember { mutableStateMapOf<String, Any?>() }

    fun navigate(target: SettingsPage) {
        direction = if (target.order > (page?.order ?: -1)) 1 else -1
        page = target
    }

    fun back() {
        direction = -1
        page = page?.parent
    }

    val pageScope = object : SettingsPageScope {
        override val store = store
        override val sessions = sessions
        override val current get() = current

        override fun navigate(page: SettingsPage) = navigate(page)
        override fun back() = back()

        override fun openUrl(url: String) {
            val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            val executor = ContextCompat.getMainExecutor(context)
            RemoteActivityHelper(context, executor)
                .startRemoteActivity(intent, null)
                .addListener(
                    {
                        // The paired phone is unreachable (not paired, offline, or an emulator
                        // without a companion). A watch has no browser and ACTION_VIEW is
                        // rejected by the system broker, so say so instead of retrying locally.
                        Toast.makeText(context, R.string.about_link_failed, Toast.LENGTH_SHORT)
                            .show()
                    },
                    executor,
                )
        }

        override fun recreate() {
            (context as? android.app.Activity)?.recreate()
        }

        override fun launch(block: suspend () -> Unit) {
            scope.launch { block() }
        }

        override fun <T> shared(key: String, factory: () -> T): T {
            @Suppress("UNCHECKED_CAST")
            return shared.getOrPut(key) { factory() } as T
        }
    }

    if (page != null) BackHandler(onBack = ::back)

    ScreenScaffold(scrollState = state) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward =
                    slideInVertically(spring(stiffness = 380f)) { it / 3 } + fadeIn(tween(200)) togetherWith
                            slideOutVertically(tween(150)) { -it / 3 } + fadeOut(tween(150))
                val backward =
                    slideInVertically(tween(150)) { -it / 3 } + fadeIn(tween(150)) togetherWith
                            slideOutVertically(tween(150)) { it / 3 } + fadeOut(tween(150))
                if (direction >= 0) forward else backward
            },
            label = "settings-page",
        ) { target ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                TransformingLazyColumn(
                    Modifier.width(200.dp),
                    state = state,
                    contentPadding = PaddingValues(top = 24.dp, bottom = 56.dp)
                ) {
                    val headerRes = target?.titleRes ?: R.string.settings
                    item { Scaled(this, spec) { ListHeader { Text(stringResource(headerRes)) } } }
                    if (target == null) {
                        SettingsPages.home.forEach { category ->
                            item {
                                SettingRow(this, spec, stringResource(category.titleRes)) {
                                    navigate(category)
                                }
                            }
                        }
                    } else {
                        with(target) {
                            pageScope.content(PageList(this@TransformingLazyColumn, spec))
                        }
                    }
                }
            }
        }
    }
}
