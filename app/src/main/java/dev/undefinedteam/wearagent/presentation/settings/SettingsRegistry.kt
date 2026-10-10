package dev.undefinedteam.wearagent.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import dev.undefinedteam.wearagent.presentation.settings.pages.AboutPage
import dev.undefinedteam.wearagent.presentation.settings.pages.DisplayPage
import dev.undefinedteam.wearagent.presentation.settings.pages.EndpointsPage
import dev.undefinedteam.wearagent.presentation.settings.pages.InputPage
import dev.undefinedteam.wearagent.presentation.settings.pages.LanguagePage
import dev.undefinedteam.wearagent.presentation.settings.pages.SessionsPage
import dev.undefinedteam.wearagent.presentation.settings.pages.ToolsPage
import dev.undefinedteam.wearagent.session.AgentSettings
import dev.undefinedteam.wearagent.session.SessionLog
import dev.undefinedteam.wearagent.session.SettingsStore

/**
 * A self-contained settings page. Category pages (parent == null) appear on the
 * settings home; child pages (parent != null) are reachable only through their
 * category and go back to it.
 *
 * A page registers itself at file-class initialization via [settingsPage]; the
 * manifest at the bottom of this file touches one symbol per page file to
 * trigger that initialization. Adding a child page to an existing category file
 * therefore needs no change anywhere else — only a new category file needs one
 * extra manifest line.
 */
class SettingsPage internal constructor(
    val route: String,
    /** Parent category; null for top-level pages shown on the settings home. */
    val parent: SettingsPage?,
    /** Title shown in the page header. */
    val titleRes: Int,
    /** Global registration order; drives navigation animation direction. */
    internal val order: Int,
    /**
     * Page body. NOT composable: it runs inside the lazy column's content block
     * and only registers items via [PageList]; all composable work happens
     * inside each item. Re-registers on every recomposition, so state reads
     * (scope values, editor state) are tracked normally.
     */
    val content: SettingsPageScope.(PageList) -> Unit,
)

/** Shared dependencies and actions handed to every page. */
interface SettingsPageScope {
    val store: SettingsStore
    val sessions: SessionLog
    val current: AgentSettings

    /** Navigate to [page] with a direction-aware animation. */
    fun navigate(page: SettingsPage)

    /** Go back to the current page's parent (categories return to home). */
    fun back()

    /** Opens a URL on the paired phone; Wear OS has no local browser. */
    fun openUrl(url: String)

    /** Recreates the host activity, e.g. to apply a language change. */
    fun recreate()

    /** Launch work on the settings screen's scope (survives page navigation). */
    fun launch(block: suspend () -> Unit)

    /**
     * Screen-lifetime state shared across pages of one screen visit, e.g. the
     * endpoint editor state shared between endpoint and models pages.
     */
    fun <T> shared(key: String, factory: () -> T): T
}

object SettingsPages {
    private val pages = LinkedHashMap<String, SettingsPage>()

    /** Top-level categories in registration order = settings home order. */
    val home: List<SettingsPage> get() = pages.values.filter { it.parent == null }

    internal fun register(page: SettingsPage) {
        require(pages.put(page.route, page) == null) {
            "Duplicate settings page route '${page.route}'"
        }
    }

    internal fun nextOrder(): Int = pages.size
}

/**
 * Declares and registers a page. Call from a top-level `val` in the page file:
 * `val MyPage: SettingsPage = settingsPage("my", titleRes = R.string.my) { ... }`.
 * The explicit type breaks the inference cycle when sibling pages in one file
 * reference each other. Children must be declared after their parent's val.
 */
fun settingsPage(
    route: String,
    parent: SettingsPage? = null,
    titleRes: Int,
    content: SettingsPageScope.(PageList) -> Unit,
): SettingsPage {
    val page = SettingsPage(route, parent, titleRes, SettingsPages.nextOrder(), content)
    SettingsPages.register(page)
    return page
}

/**
 * Manifest of page files. Referencing each category page forces its file's
 * class initialization, which registers the category and (within that file)
 * its child pages. One line per category file; child pages cost nothing here.
 */
internal val SettingsPageFiles = listOf(
    SessionsPage,
    EndpointsPage,
    InputPage,
    ToolsPage,
    DisplayPage,
    LanguagePage,
    AboutPage,
)

/** Title of a page as a localized string. */
@Composable
fun SettingsPage.title(): String = stringResource(titleRes)

/**
 * Item registration API handed to page content. Each helper emits one list
 * item with the standard curved-list transformation; labels given as resource
 * ids are resolved inside the item, where composition allows it.
 */
class PageList internal constructor(
    private val column: TransformingLazyColumnScope,
    private val spec: TransformationSpec,
) {
    fun row(labelRes: Int, onClick: () -> Unit) = column.item {
        SettingRow(this, spec, stringResource(labelRes), onClick)
    }

    fun row(label: String, onClick: () -> Unit) = column.item {
        SettingRow(this, spec, label, onClick)
    }

    fun toggle(labelRes: Int, selected: Boolean, onSelect: () -> Unit) = column.item {
        SettingToggle(this, spec, stringResource(labelRes), selected, onSelect)
    }

    fun toggle(label: String, selected: Boolean, onSelect: () -> Unit) = column.item {
        SettingToggle(this, spec, label, selected, onSelect)
    }

    fun switch(labelRes: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) =
        column.item {
            SettingSwitch(this, spec, stringResource(labelRes), checked, onCheckedChange)
        }

    fun field(labelRes: Int, value: String, onSave: (String) -> Unit) = column.item {
        Scaled(this, spec) { SettingField(stringResource(labelRes), value, onSave) }
    }

    fun subHeader(labelRes: Int) = column.item {
        Scaled(this, spec) { ListSubHeader { Text(stringResource(labelRes)) } }
    }

    /**
     * One transformed item wrapping a free-form [Column]. The content runs
     * inside the item, so it IS a composable context; the receiver is the
     * lazy-column item scope for nested transformed rows.
     */
    fun custom(
        modifier: Modifier = Modifier,
        horizontalAlignment: Alignment.Horizontal = Alignment.Start,
        verticalArrangement: Arrangement.Vertical = Arrangement.Top,
        content: @Composable TransformingLazyColumnItemScope.() -> Unit,
    ) = column.item {
        val transformation = SurfaceTransformation(spec)
        Column(
            Modifier
                .transformedHeight(this, spec)
                .graphicsLayer { with(transformation) { applyContainerTransformation() } }
                .fillMaxWidth()
                .then(modifier),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = verticalArrangement,
        ) { this@item.content() }
    }
}
