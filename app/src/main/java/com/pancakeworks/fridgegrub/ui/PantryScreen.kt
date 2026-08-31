package com.pancakeworks.fridgegrub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pancakeworks.fridgegrub.data.ALL_PANTRY_ITEMS
import com.pancakeworks.fridgegrub.viewmodel.IngredientViewModel

/**
 * The pantry/seasoning checklist -- no quantity, just "do you generally have this on hand." Every
 * checked item is merged into the fridge's ingredient names wherever a recipe search or detail
 * match is computed (see [IngredientScreen]'s "Find Recipes" call and [RecipeDetailScreen]'s
 * `loadRecipeDetail` call), so a user doesn't have to add "salt" and "black pepper" to the fridge
 * by hand for every recipe that calls for them.
 *
 * Reached two ways, distinguished by [isOnboarding]: once automatically on first launch (with a
 * "Done" button that also marks onboarding seen, framed as "confirm what you have" since the list
 * starts fully checked), and afterward any time via the pantry icon on [IngredientScreen]'s top
 * bar (framed as a plain editable list with a back button).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantryScreen(
    isOnboarding: Boolean,
    onDone: () -> Unit,
    viewModel: IngredientViewModel = viewModel()
) {
    val checkedItems by viewModel.pantryItems.collectAsState()
    if (!isOnboarding) {
        BackHandler(onBack = onDone)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pantry & Seasonings") },
                navigationIcon = {
                    if (!isOnboarding) {
                        IconButton(onClick = onDone) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (isOnboarding) {
                Surface(shadowElevation = 8.dp) {
                    Button(
                        onClick = {
                            viewModel.markPantryOnboardingSeen()
                            onDone()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text("Done")
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isOnboarding) {
                Text(
                    "Check off anything else you keep on hand and uncheck anything you don't. " +
                        "We've already checked off the basic things that most kitchens have.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.padding(16.dp)
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(ALL_PANTRY_ITEMS, key = { it }) { item ->
                    val checked = item in checkedItems
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.togglePantryItem(item) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { viewModel.togglePantryItem(item) })
                        Text(
                            item.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }
}
