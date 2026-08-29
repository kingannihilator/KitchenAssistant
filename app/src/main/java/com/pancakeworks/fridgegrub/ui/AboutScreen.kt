package com.pancakeworks.fridgegrub.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recipe Sources") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            item {
                Text(
                    "Fridge Grub's recipes are drawn from these public sources. Some require " +
                        "attribution under their license, shown below.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
            }
            items(SOURCES) { source ->
                Card(modifier = Modifier.padding(bottom = 12.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(source.name, style = MaterialTheme.typography.titleMedium)
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
}
