package com.pancakeworks.fridgegrub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * One recipe source, as required by [SOURCES]' license. See `new_db_workable/HANDOVER.md`'s
 * "LICENSING" section: the Wikibooks Cookbook source is CC BY-SA 4.0, which requires attribution
 * -- this screen is that attribution, not a nice-to-have. Text below is copied verbatim from the
 * `sources`/`licenses` tables in `new_db_workable/recipes_open_v1_4.sqlite` (queried directly, not
 * paraphrased) rather than shipping those tables into the app: they never change at runtime, so a
 * static screen is simpler than adding two more Room entities to read them live.
 */
private data class RecipeSource(
    val name: String,
    val url: String,
    val attribution: String,
    val license: String
)

private val SOURCES = listOf(
    RecipeSource(
        name = "Wikibooks Cookbook",
        url = "https://en.wikibooks.org/wiki/Cookbook",
        attribution = "Wikibooks contributors",
        license = "Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)"
    ),
    RecipeSource(
        name = "Pennsylvania Dutch Cooking",
        url = "https://www.gutenberg.org/ebooks/26558",
        attribution = "Pennsylvania Dutch Cooking (Project Gutenberg eBook #26558)",
        license = "Public domain in the USA (Project Gutenberg)"
    ),
    RecipeSource(
        name = "Practical Vegetarian Cookery",
        url = "https://www.gutenberg.org/ebooks/69812",
        attribution = "Practical Vegetarian Cookery (Project Gutenberg eBook #69812)",
        license = "Public domain in the USA (Project Gutenberg)"
    ),
    RecipeSource(
        name = "La Cuisine Creole",
        url = "https://www.gutenberg.org/ebooks/75027",
        attribution = "La Cuisine Creole (Project Gutenberg eBook #75027)",
        license = "Public domain in the USA (Project Gutenberg)"
    ),
    RecipeSource(
        name = "Chinese Recipes",
        url = "https://www.gutenberg.org/ebooks/76573",
        attribution = "Nellie C. Wong, Chinese Recipes (1927); Project Gutenberg eBook #76573",
        license = "Public domain in the USA (Project Gutenberg)"
    ),
    RecipeSource(
        name = "The Khaki Kook Book",
        url = "https://www.gutenberg.org/ebooks/25914",
        attribution = "Mary Kennedy Core, The Khaki Kook Book; Project Gutenberg eBook #25914",
        license = "Public domain in the USA (Project Gutenberg)"
    ),
    RecipeSource(
        name = "Dry Beans, Peas, Lentils: Modern Cookery",
        url = "https://archive.org/details/drybeanspeaslent326swic",
        attribution = "Mary T. Swickard, Bureau of Human Nutrition and Home Economics, Agricultural Research Administration, U.S. Department of Agriculture, September 1952 (USDA Leaflet No. 326)",
        license = "U.S. Government work -- public domain (17 U.S.C. § 105)"
    )
)

/**
 * The About screen's three entries, and the whole of its structure.
 *
 * An enum rather than three booleans or a nullable Int: [title] is both the menu row's label and
 * the detail window's title bar, so there is exactly one place a section's name is spelled, and
 * adding a fourth section is one entry plus one branch in the body's `when`.
 *
 * Declaration order is display order -- the how-to comes first because it is the only one a new
 * user needs; rating and attribution are both things you go looking for on purpose.
 */
private enum class AboutSection(
    val title: String,
    val subtitle: String,
    val icon: ImageVector
) {
    HOW_TO_USE(
        title = "How to use",
        // Describes what you get, not the topics: this opens the walkthrough itself, so saying so is
        // more use than listing the slides.
        subtitle = "The walkthrough again, any time you want it",
        icon = Icons.Filled.MenuBook
    ),
    RATE(
        title = "Rate this app",
        subtitle = "Tell other people what you think of Fridge Grub",
        icon = Icons.Filled.Star
    ),
    SOURCES(
        title = "Recipe sources & licenses",
        subtitle = "Where the recipes come from, and who to credit",
        icon = Icons.AutoMirrored.Filled.LibraryBooks
    )
}

/**
 * About: a three-item menu, each opening its own detail window. Reached from the info icon on
 * [IngredientScreen]'s top bar.
 *
 * Grew out of what used to be a recipe-sources-only screen. It was briefly one long scrolling page
 * of all three sections; the menu-and-detail split exists because the attribution alone is seven
 * cards, so everything below it was a long scroll away from the thing a visitor actually came for.
 * [SOURCES] itself is unchanged through all of that -- that text is license-required attribution
 * rather than copy to be edited in passing.
 *
 * "How to use" is the shared [HelpCarousel] rather than its own rendering of [HELP_PAGES], so the
 * slides a user swiped through on day one are literally the same slides they find here later --
 * with no Skip, and a Close instead of "Get started", which are the only two things that differ.
 *
 * The open section is held here rather than as a `Screen` in `MainActivity`'s sealed class on
 * purpose: these are three views of one leaf screen, not three destinations, so pushing them onto
 * the app's navigation stack would put the app's back state somewhere the rest of the app never
 * needs to know about.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    var openSection by rememberSaveable { mutableStateOf<AboutSection?>(null) }

    // One handler for both levels. System back closes an open detail first; from the menu it leaves
    // the screen. The second half fixes a real bug rather than anticipating one: with no handler at
    // all, back on the menu fell through to the Activity -- and since MainActivity is this app's
    // only Activity, that exited the app instead of returning to the fridge, even though the top
    // bar's own back arrow went to the fridge correctly.
    BackHandler {
        if (openSection != null) openSection = null else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(openSection?.title ?: "About") },
                navigationIcon = {
                    // Both states use the same top-left slot, so it never moves under the user's
                    // thumb; the icon changing from an arrow to an X is what says "this one closes
                    // a window" rather than "this one leaves the screen".
                    if (openSection == null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    } else {
                        IconButton(onClick = { openSection = null }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close")
                        }
                    }
                }
            )
        }
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp)

        // Exhaustive over the enum plus null: adding an AboutSection without a branch here is a
        // compile error, which is the point of the enum.
        when (openSection) {
            null -> AboutMenu(
                modifier = contentModifier,
                onOpen = { openSection = it }
            )
            AboutSection.HOW_TO_USE -> HelpCarousel(
                // "Get started" would be a lie here -- this window isn't moving anyone into the
                // app, so the last slide's button closes it, same as the top bar's X.
                finishLabel = "Close",
                onFinish = { openSection = null },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // The carousel handles its own navigation-bar inset, because the standalone
                    // first-run screen is a bare Column with no Scaffold to do it. The Scaffold
                    // here has already inset the content, so without consuming it the bottom bar
                    // gets the inset twice and sits noticeably high.
                    .consumeWindowInsets(padding)
            )
            AboutSection.RATE -> RateDetail(contentModifier)
            AboutSection.SOURCES -> SourcesDetail(contentModifier)
        }
    }
}

@Composable
private fun AboutMenu(modifier: Modifier, onOpen: (AboutSection) -> Unit) {
    LazyColumn(modifier = modifier) {
        items(AboutSection.entries.toList()) { section ->
            Card(
                onClick = { onOpen(section) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = section.icon,
                        // The label right beside it already says what this is; a description here
                        // would just be read out twice inside the same row.
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(section.title, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            section.subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RateDetail(modifier: Modifier) {
    val context = LocalContext.current
    LazyColumn(modifier = modifier) {
        item {
            Card(modifier = Modifier.padding(vertical = 6.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "If Fridge Grub has been useful, a rating helps other people find it.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    // Opens the Play Store listing directly rather than calling the in-app review
                    // API: Google explicitly warns against a button-triggered review flow, because
                    // a user who has hit their review quota would tap it and see nothing happen.
                    // The API is used instead from an automatic, unrequested moment (after a few
                    // cooked recipes), where a silent no-op is invisible.
                    OutlinedButton(onClick = { openPlayStoreListing(context) }) {
                        Text("Rate Fridge Grub on Google Play")
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcesDetail(modifier: Modifier) {
    LazyColumn(modifier = modifier) {
        item {
            Text(
                "Fridge Grub's recipes are drawn from these public sources. Some require " +
                    "attribution under their license, shown below.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp)
            )
            Spacer(Modifier.height(16.dp))
        }
        items(SOURCES) { source ->
            Card(modifier = Modifier.padding(vertical = 6.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Seven of these stack up when a user scrolls the detail; as headings they're
                    // separately navigable instead of one long read.
                    Text(
                        source.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(source.attribution, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(source.url, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        source.license,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
