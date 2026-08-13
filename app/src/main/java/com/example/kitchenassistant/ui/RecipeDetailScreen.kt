package com.example.kitchenassistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.foundation.layout.size
import com.example.kitchenassistant.ui.theme.FridgeMatchGreen
import com.example.kitchenassistant.ui.theme.FridgeMissingRed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kitchenassistant.data.IngredientMatcher
import com.example.kitchenassistant.model.Ingredient
import com.example.kitchenassistant.model.Recipe
import com.example.kitchenassistant.viewmodel.RecipeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    recipe: Recipe,
    fridgeIngredients: List<Ingredient> = emptyList(),
    onBack: () -> Unit,
    // Keyed by Ingredient.id, not name: two fridge entries can share a name prefix, and the old
    // name-based lookup resolved "eggplant" to "egg" and decremented the wrong one.
    onDeductIngredient: (String) -> Unit = {},
    onSetCount: (String, Int) -> Unit = { _, _ -> },
    viewModel: RecipeViewModel = viewModel()
) {
    val ingredients by viewModel.detailIngredients.collectAsState()
    val directions by viewModel.detailDirections.collectAsState()
    val isLoading by viewModel.isLoadingDetail.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()
    val isFavorite = favoriteIds.contains(recipe.id)
    var showCookSection by remember { mutableStateOf(false) }

    // Which fridge ingredient covers each recipe line, parallel to [ingredients]. Computed once
    // and used for both the checkmarks and the cook-mode rows, through the same IngredientMatcher
    // the search scored with — so this screen can no longer disagree with the card's "X/Y".
    val coveredBy: List<Ingredient?> = remember(ingredients, fridgeIngredients) {
        val fridgeTerms = fridgeIngredients.map { it to IngredientMatcher.parseFridge(it.name) }
        ingredients.map { detail ->
            val canonical = detail.canonical ?: return@map null
            val recipeTerm = IngredientMatcher.parseRecipe(canonical)
            fridgeTerms.firstOrNull { (_, fridgeTerm) ->
                IngredientMatcher.matches(fridgeTerm, recipeTerm)
            }?.first
        }
    }
    val cookIngredients = remember(coveredBy) { coveredBy.filterNotNull().distinctBy { it.id } }

    LaunchedEffect(recipe.id, fridgeIngredients) {
        viewModel.loadRecipeDetail(recipe.id, fridgeIngredients.map { it.name })
    }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(recipe.title) },
                actions = {
                    IconButton(onClick = { viewModel.toggleFavorite(recipe.id) }) {
                        Icon(
                            // A heart, not a star -- the star is already used for prioritized
                            // fridge ingredients (see IngredientScreen), a different concept
                            // (boosts ranking dynamically) from favoriting a specific recipe
                            // (a static pin). Same icon for both read as "the same feature."
                            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Metadata row
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (recipe.servings != null) {
                            Text("Serves ${recipe.servings}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (recipe.categories.isNotEmpty()) {
                            Text(
                                recipe.categories.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Ingredients section
                if (ingredients.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text("Ingredients", style = MaterialTheme.typography.titleMedium)
                        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                    }
                    itemsIndexed(ingredients) { index, detail ->
                        // detail.matched is the new corpus's category-aware verdict (see its doc);
                        // the legacy corpus leaves it null and falls back to the direct-match-only
                        // coveredBy computed above, exactly as before.
                        val inFridge = detail.matched ?: (coveredBy.getOrNull(index) != null)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (detail.canonical == null) {
                                // A section heading or a line the corpus couldn't parse. It can't
                                // be matched either way, so claiming it's missing would be a lie.
                                Spacer(Modifier.size(16.dp))
                            } else {
                                Icon(
                                    imageVector = if (inFridge) Icons.Filled.Check else Icons.Filled.Close,
                                    contentDescription = null,
                                    tint = if (inFridge) FridgeMatchGreen else FridgeMissingRed,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(detail.line, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                // Directions section
                if (directions.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text("Directions", style = MaterialTheme.typography.titleMedium)
                        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                    }
                    itemsIndexed(directions) { _, step ->
                        Text(step, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // Cook section toggle
                if (cookIngredients.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = { showCookSection = !showCookSection },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (showCookSection) "Hide cook mode" else "Cook this recipe")
                        }
                    }
                }

                // Cook section — ingredient rows with editable count and minus button
                if (showCookSection && cookIngredients.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text("Use from fridge", style = MaterialTheme.typography.titleMedium)
                        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                    }
                    itemsIndexed(cookIngredients, key = { _, ing -> ing.id }) { _, ingredient ->
                        CookIngredientRow(
                            ingredient = ingredient,
                            onDeduct = { onDeductIngredient(ingredient.id) },
                            onSetCount = { onSetCount(ingredient.id, it) }
                        )
                    }
                }

                if (ingredients.isEmpty() && directions.isEmpty()) {
                    item {
                        Text(
                            "No details available for this recipe.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            LazyListScrollbar(
                state = listState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(6.dp)
            )
            } // end Box
        }
    }
}

@Composable
private fun CookIngredientRow(
    ingredient: Ingredient,
    onDeduct: () -> Unit,
    onSetCount: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${ingredient.name} (${ingredient.unit})",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        CountStepper(
            count = ingredient.count,
            onDecrement = onDeduct,
            onIncrement = { onSetCount(ingredient.count + 1) },
            onSetCount = onSetCount
        )
    }
}
