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
import androidx.compose.material.icons.filled.FavoriteBorder
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import com.example.kitchenassistant.ui.theme.FavoriteHeart
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kitchenassistant.model.Recipe
import com.example.kitchenassistant.viewmodel.RecipeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeScreen(
    fridgeIngredients: List<String>,
    prioritizedIngredients: List<String> = emptyList(),
    onBack: () -> Unit,
    onRecipeClick: (Recipe) -> Unit = {},
    // Lets the undo snackbar's fallback advice ("check Previously Favorited") actually be
    // actionable from here -- without this, reaching the Favorites screen meant backing all the
    // way out to the fridge screen first.
    onViewFavorites: () -> Unit = {},
    viewModel: RecipeViewModel = viewModel()
) {
    val allRecipes by viewModel.sortedRecipes.collectAsState()
    val recipes by viewModel.filteredRecipes.collectAsState()
    val filterQuery by viewModel.filterQuery.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val totalMatchCount by viewModel.totalMatchCount.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Undo-on-remove safety net, kept only on this screen (not FavoritesScreen/RecipeDetailScreen
    // -- there, re-tapping the same heart is just as fast as an "Undo" button, so the snackbar
    // added nothing). Here it earns its keep: un-favoriting triggers a live re-sort via
    // recipeOrder, which can move or hide the very card you just tapped, so "just tap it again"
    // isn't always trivial. SnackbarDuration.Long (not the default Short) gives more time to
    // notice and react in a scrolling list; the message also names the permanent fallback
    // (Previously Favorited on the Favorites screen) for whenever the snackbar's already gone.
    fun toggleFavoriteWithUndo(recipe: Recipe) {
        val wasFavorite = recipe.isFavorite
        viewModel.toggleFavorite(recipe.id)
        if (wasFavorite) {
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = "Removed \"${recipe.title}\" from favorites. Can't find it? " +
                        "Check Previously Favorited on the Favorites screen.",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.toggleFavorite(recipe.id)
                }
            }
        }
    }

    LaunchedEffect(Unit) { viewModel.searchRecipes(fridgeIngredients, prioritizedIngredients) }
    BackHandler(onBack = onBack)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                },
                actions = {
                    IconButton(onClick = onViewFavorites) {
                        FavoritesShortcutIcon()
                    }
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
                                RecipeCard(
                                    recipe,
                                    onClick = { onRecipeClick(recipe) },
                                    onToggleFavorite = { toggleFavoriteWithUndo(recipe) }
                                )
                            }
                        }
                        LazyListScrollbar(
                            state = listState,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(6.dp)
                        )
                        // A small cameo of the loading screen's mascot, peeking in on the results
                        // -- only while there's an actual list to look at, not during loading or
                        // the "no results" messages, which already speak for themselves. Drawn on
                        // top of the list (declared last): if she covers a card at the current
                        // scroll position, scrolling past her is a trivial nudge.
                        PeekingMascot(
                            expression = MascotExpression.THINKING,
                            leanTowardCenter = true,
                            modifier = Modifier.align(Alignment.BottomStart)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeCard(recipe: Recipe, onClick: () -> Unit, onToggleFavorite: () -> Unit) {
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
        Column(modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)) {
            // Top-aligned, not centered: a long title wraps to 2-3 lines and centering would
            // float the heart in the middle of that block instead of next to the first line.
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    recipe.title,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(top = 10.dp)
                )
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (recipe.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (recipe.isFavorite) "Remove from favorites" else "Add to favorites",
                        tint = if (recipe.isFavorite) FavoriteHeart else ingredientTextColor.copy(alpha = 0.5f),
                        modifier = Modifier.size(26.dp)
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
