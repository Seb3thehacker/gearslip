package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.Item
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row as CarRow
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Section
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.car.app.model.signin.InputSignInMethod
import androidx.car.app.model.signin.PinSignInMethod
import androidx.car.app.model.signin.ProviderSignInMethod
import androidx.car.app.model.signin.QRCodeSignInMethod
import androidx.car.app.model.signin.SignInTemplate
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.OnDoneCallback
import androidx.car.app.serialization.Bundleable
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.host.inputChanged
import app.seb3thehacker.gearslip.host.inputSubmitted
import app.seb3thehacker.gearslip.host.submitted
import app.seb3thehacker.gearslip.host.tabSelected
import app.seb3thehacker.gearslip.host.text
import app.seb3thehacker.gearslip.host.textChanged

/**
 * Templates that fill a screen with content rather than sitting over a map.
 *
 * A map-backed template can hold one of these in its side pane, which is why they are drawn by
 * the same function either way - the only difference is how much room they get.
 */
@Composable
fun ContentTemplate(template: Template?, modifier: Modifier = Modifier) {
    when (template) {
        null -> Unit
        is ListTemplate -> ListContent(template, modifier)
        is GridTemplate -> GridContent(template, modifier)
        is PaneTemplate -> PaneContent(template, modifier)
        is MessageTemplate -> MessageContent(template, modifier)
        is LongMessageTemplate -> LongMessageContent(template, modifier)
        is SearchTemplate -> SearchContent(template, modifier)
        is SignInTemplate -> SignInContent(template, modifier)
        is TabTemplate -> TabContent(template, modifier)
        is SectionedItemTemplate -> SectionedContent(template, modifier)
        is MediaPlaybackTemplate -> MediaPlaybackContent(template, modifier)
        else -> UnsupportedChrome(template, modifier)
    }
}

/** True when this template is one [ContentTemplate] knows how to draw. */
fun isContentTemplate(template: Template?): Boolean = when (template) {
    is ListTemplate, is GridTemplate, is PaneTemplate, is MessageTemplate,
    is LongMessageTemplate, is SearchTemplate, is SignInTemplate, is TabTemplate,
    is SectionedItemTemplate, is MediaPlaybackTemplate,
    -> true
    else -> false
}

// --- lists, grids and panes ---------------------------------------------------------------------

@Composable
private fun ListContent(template: ListTemplate, modifier: Modifier) {
    ContentSurface(modifier) {
        HeaderBar(
            headerTitle(template.header, template.title.text()),
            template.header?.startHeaderAction ?: template.headerAction,
            template.header?.endHeaderActions.orEmpty(),
            template.actionStrip,
        )
        if (template.isLoading) Loading()
        else ItemListColumn(
            template.singleList, template.sectionedLists?.map { it.itemList },
            Modifier.weight(1f, fill = false),
        )
        ActionRow(template.actions.orEmpty(), Modifier.padding(12.dp))
    }
}

@Composable
private fun GridContent(template: GridTemplate, modifier: Modifier) {
    ContentSurface(modifier) {
        HeaderBar(
            headerTitle(template.header, template.title.text()),
            template.header?.startHeaderAction ?: template.headerAction,
            template.header?.endHeaderActions.orEmpty(),
            template.actionStrip,
        )
        if (template.isLoading) Loading() else GridItems(template.singleList, Modifier.weight(1f, fill = false))
        ActionRow(template.actions.orEmpty(), Modifier.padding(12.dp))
    }
}

@Composable
private fun PaneContent(template: PaneTemplate, modifier: Modifier) {
    ContentSurface(modifier) {
        HeaderBar(
            headerTitle(template.header, template.title.text()),
            template.header?.startHeaderAction ?: template.headerAction,
            template.header?.endHeaderActions.orEmpty(),
            template.actionStrip,
        )
        if (template.pane?.isLoading == true) Loading() else PaneRows(template.pane)
    }
}

// --- messages -------------------------------------------------------------------------------

@Composable
private fun MessageContent(template: MessageTemplate, modifier: Modifier) {
    ContentSurface(modifier) {
        HeaderBar(
            headerTitle(template.header, template.title.text()),
            template.header?.startHeaderAction ?: template.headerAction,
            template.header?.endHeaderActions.orEmpty(),
            template.actionStrip,
        )
        if (template.isLoading) {
            Loading()
            return@ContentSurface
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            template.icon?.let {
                CarGlyph(it, Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(template.message.text(), style = MaterialTheme.typography.bodyLarge)
        }
        ActionRow(template.actions.orEmpty(), Modifier.padding(16.dp))
    }
}

/**
 * The long form is the same idea with a scroll: it's what apps use for terms and conditions,
 * which is also why it is only ever shown parked.
 */
@Composable
private fun LongMessageContent(template: LongMessageTemplate, modifier: Modifier) {
    ContentSurface(modifier) {
        HeaderBar(
            template.title.text(),
            template.headerAction,
            actionStrip = template.actionStrip,
        )
        Text(
            template.message.text(),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        )
        ActionRow(template.actions.orEmpty(), Modifier.padding(16.dp))
    }
}

// --- search ---------------------------------------------------------------------------------

/**
 * Search: the host owns the text and the keyboard, and the app owns the results.
 *
 * Every keystroke goes to the app through [SearchTemplate.getSearchCallbackDelegate], which is
 * what lets results update as you type. The text lives here rather than in the template
 * because the app never sends it back - it only ever sends a new list of results.
 */
@Composable
private fun SearchContent(template: SearchTemplate, modifier: Modifier) {
    // Seeded once. The app refreshes this template on every keystroke, so re-seeding from
    // initialSearchText would fight whatever is being typed.
    var query by remember { mutableStateOf(template.initialSearchText.orEmpty()) }
    val delegate = template.searchCallbackDelegate

    ContentSurface(modifier) {
        // Back button and field share a row: on a 480px screen the keyboard and the results
        // are already competing for every pixel, so the header can't have one to itself.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            template.headerAction?.let { ActionButton(it) }
            SearchField(query, template.searchHint.orEmpty(), Modifier.weight(1f))
            ActionRow(template.actionStrip?.actions.orEmpty())
        }

        Box(Modifier.weight(1f)) {
            if (template.isLoading) Loading()
            else ItemListColumn(template.itemList, modifier = Modifier.fillMaxSize())
        }

        CarKeyboard(
            text = query,
            onTextChange = {
                query = it
                delegate.textChanged(it)
            },
            onSubmit = { delegate.submitted(query) },
        )
    }
}

@Composable
private fun SearchField(query: String, hint: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            query.ifEmpty { hint.ifEmpty { "Search" } },
            style = MaterialTheme.typography.titleMedium,
            color = if (query.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

// --- sign-in --------------------------------------------------------------------------------

/**
 * Sign-in comes in four shapes and the host has to draw whichever the app picked. Only the
 * typed one needs the keyboard; a PIN or QR code is something the driver reads off the screen
 * and completes on their phone.
 */
@Composable
private fun SignInContent(template: SignInTemplate, modifier: Modifier) {
    ContentSurface(modifier) {
        HeaderBar(
            template.title.text(),
            template.headerAction,
            actionStrip = template.actionStrip,
        )
        if (template.isLoading) {
            Loading()
            return@ContentSurface
        }

        val instructions = template.instructions.text()
        if (instructions.isNotEmpty()) {
            Text(
                instructions,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        when (val method = template.signInMethod) {
            is InputSignInMethod -> InputSignIn(method, Modifier.weight(1f))
            is PinSignInMethod -> Callout(method.pinCode.toString(), Modifier.weight(1f))
            is QRCodeSignInMethod -> Callout(method.uri.toString(), Modifier.weight(1f))
            is ProviderSignInMethod -> Box(Modifier.weight(1f), Alignment.Center) {
                ActionRow(listOf(method.action))
            }
            else -> Spacer(Modifier.weight(1f))
        }

        val additional = template.additionalText.text()
        if (additional.isNotEmpty()) {
            Text(
                additional,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        ActionRow(template.actions.orEmpty(), Modifier.padding(12.dp))
    }
}

@Composable
private fun InputSignIn(method: InputSignInMethod, modifier: Modifier) {
    var value by remember { mutableStateOf(method.defaultValue.text()) }
    val delegate = method.inputCallbackDelegate
    val masked = method.inputType == InputSignInMethod.INPUT_TYPE_PASSWORD

    Column(modifier) {
        SearchField(if (masked) "•".repeat(value.length) else value, method.hint.text())
        val error = method.errorMessage.text()
        if (error.isNotEmpty()) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        CarKeyboard(
            text = value,
            onTextChange = {
                value = it
                delegate.inputChanged(it)
            },
            onSubmit = { delegate.inputSubmitted(value) },
            submitIcon = false,
        )
    }
}

/** A code the driver reads off the screen. Big, centred, nothing else competing with it. */
@Composable
private fun Callout(value: String, modifier: Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// --- tabs -----------------------------------------------------------------------------------

@Composable
private fun TabContent(template: TabTemplate, modifier: Modifier) {
    val active = template.activeTabContentId
    ContentSurface(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .verticalScrollNone(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            template.headerAction?.let { ActionButton(it) }
            template.tabs.forEach { tab ->
                val selected = tab.contentId == active
                Surface(
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.clickable {
                        template.tabCallbackDelegate.tabSelected(tab.contentId)
                    },
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tab.icon?.let {
                            CarGlyph(it, Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(tab.title.text(), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }
        }
        if (template.isLoading) Loading()
        else ContentTemplate(template.tabContents?.template, Modifier.weight(1f))
    }
}

/** Tabs share a row; this keeps the modifier chain readable where no scroll is wanted. */
private fun Modifier.verticalScrollNone(): Modifier = this

// --- sectioned items --------------------------------------------------------------------------

/**
 * Sections fetch their items across the binder on demand rather than carrying them, so each one
 * is asked for its full range once and the answer is held while the section is on screen.
 */
@Composable
private fun SectionedContent(template: SectionedItemTemplate, modifier: Modifier) {
    ContentSurface(modifier) {
        HeaderBar(
            headerTitle(template.header, ""),
            template.header?.startHeaderAction,
            template.header?.endHeaderActions.orEmpty(),
        )
        if (template.isLoading) {
            Loading()
            return@ContentSurface
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            template.sections.forEach { section ->
                PaneTitle(section.title.text())
                SectionRows(section)
            }
        }
        ActionRow(template.actions.orEmpty(), Modifier.padding(12.dp))
    }
}

@Composable
private fun SectionRows(section: Section<*>) {
    var items by remember(section) { mutableStateOf<List<Item>>(emptyList()) }

    LaunchedEffect(section) {
        val delegate = section.itemsDelegate
        val size = runCatching { delegate.size }.getOrDefault(0)
        if (size <= 0) return@LaunchedEffect
        runCatching {
            delegate.requestItemRange(0, size - 1, object : OnDoneCallback {
                override fun onSuccess(response: Bundleable?) {
                    val value = response?.let { runCatching { it.get() }.getOrNull() }
                    @Suppress("UNCHECKED_CAST")
                    items = (value as? List<Item>).orEmpty()
                }

                override fun onFailure(response: Bundleable) =
                    GearslipLog.w("host: a section refused to hand over its items")
            })
        }.onFailure { GearslipLog.w("host: could not fetch section items: ${it.message}") }
    }

    if (items.isEmpty()) {
        val message = section.noItemsMessage.text()
        if (message.isNotEmpty()) Text(message, Modifier.padding(14.dp))
        return
    }
    items.filterIsInstance<CarRow>().forEach { RowItem(it) }
}

// --- media ----------------------------------------------------------------------------------

/**
 * Media playback is drawn from the app's MediaSession rather than from the template, which
 * carries only a header. Gearslip has no media channel yet, so this says so plainly instead of
 * showing an empty player.
 */
@Composable
private fun MediaPlaybackContent(template: MediaPlaybackTemplate, modifier: Modifier) {
    ContentSurface(modifier) {
        HeaderBar(
            headerTitle(template.header, ""),
            template.header?.startHeaderAction,
            template.header?.endHeaderActions.orEmpty(),
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "Playback controls come from the app's media session,\nwhich Gearslip doesn't carry yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// --- shared ---------------------------------------------------------------------------------

@Composable
private fun ContentSurface(modifier: Modifier, content: @Composable ColumnScopeAlias.() -> Unit) {
    ChromeSurface(modifier, shape = RoundedCornerShape(0.dp)) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

private typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
        Text("Loading…", style = MaterialTheme.typography.bodyLarge)
    }
}
