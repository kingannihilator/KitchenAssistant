package com.example.kitchenassistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import com.example.kitchenassistant.ui.theme.FullMatchContainerDark
import com.example.kitchenassistant.ui.theme.FullMatchContainerLight
import com.example.kitchenassistant.ui.theme.FullMatchOnContainerDark
import com.example.kitchenassistant.ui.theme.FullMatchOnContainerLight
import com.example.kitchenassistant.ui.theme.PartialMatchContainerDark
import com.example.kitchenassistant.ui.theme.PartialMatchContainerLight
import com.example.kitchenassistant.ui.theme.PartialMatchOnContainerDark
import com.example.kitchenassistant.ui.theme.PartialMatchOnContainerLight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kitchenassistant.model.Recipe
import com.example.kitchenassistant.viewmodel.RecipeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeScreen(
    fridgeIngredients: List<String>,
    prioritizedIngredients: List<String> = emptyList(),
    onBack: () -> Unit,
    onRecipeClick: (Recipe) -> Unit = {},
    viewModel: RecipeViewModel = viewModel()
) {
    val allRecipes by viewModel.sortedRecipes.collectAsState()
    val recipes by viewModel.filteredRecipes.collectAsState()
    val filterQuery by viewModel.filterQuery.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val totalMatchCount by viewModel.totalMatchCount.collectAsState()

    LaunchedEffect(Unit) { viewModel.searchRecipes(fridgeIngredients, prioritizedIngredients) }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Text(
                        when {
                            isLoading -> "Searching…"
                            filterQuery.isNotEmpty() -> "${recipes.size} of ${allRecipes.size} recipes"
                            // Only the best-ranked slice is loaded, so say so rather than letting
                            // "600 found" imply that's all there was.
                            totalMatchCount > allRecipes.size -> "Top ${allRecipes.size} of $totalMatchCount"
                            else -> "Recipes (${allRecipes.size} found)"
                        }
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!isLoading && allRecipes.isNotEmpty()) {
                OutlinedTextField(
                    value = filterQuery,
                    onValueChange = { viewModel.setFilterQuery(it) },
                    placeholder = { Text("Search by name, ingredient, category…") },
                    singleLine = true,
                    trailingIcon = {
                        if (filterQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setFilterQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading -> CircularProgressIndicator()
                    allRecipes.isEmpty() -> Text("No matching recipes")
                    recipes.isEmpty() -> Text("No results for \"$filterQuery\"")
                    else -> {
                        val listState = rememberLazyListState(
                            initialFirstVisibleItemIndex = viewModel.scrollIndex,
                            initialFirstVisibleItemScrollOffset = viewModel.scrollOffset
                        )
                        DisposableEffect(Unit) {
                            onDispose {
                                viewModel.scrollIndex = listState.firstVisibleItemIndex
                                viewModel.scrollOffset = listState.firstVisibleItemScrollOffset
                            }
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(recipes, key = { it.id }) { recipe ->
                                RecipeCard(recipe, onClick = { onRecipeClick(recipe) })
                            }
                        }
                        LazyListScrollbar(
                            state = listState,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeCard(recipe: Recipe, onClick: () -> Unit) {
    val matchRatio = if (recipe.totalCount > 0) recipe.matchedCount.toFloat() / recipe.totalCount else 0f
    val isComplete = matchRatio == 1f
    val isPartial = matchRatio >= 0.75f && !isComplete
    val isDark = isSystemInDarkTheme()
    val cardContainerColor = when {
        isComplete -> if (isDark) FullMatchContainerDark else FullMatchContainerLight
        isPartial  -> if (isDark) PartialMatchContainerDark else PartialMatchContainerLight
        else       -> MaterialTheme.colorScheme.surfaceVariant
    }
    val ingredientTextColor = when {
        isComplete -> if (isDark) FullMatchOnContainerDark else FullMatchOnContainerLight
        isPartial  -> if (isDark) PartialMatchOnContainerDark else PartialMatchOnContainerLight
        else       -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = cardContainerColor),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(recipe.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (recipe.isFavorite) {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = "Favorited",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
            Text(
                "${recipe.matchedCount}/${recipe.totalCount} ingredients",
                style = MaterialTheme.typography.bodySmall,
                color = ingredientTextColor
            )
            if (recipe.categories.isNotEmpty()) {
                Text(
                    recipe.categories.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
