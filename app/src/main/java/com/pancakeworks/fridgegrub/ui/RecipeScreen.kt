package com.pancakeworks.fridgegrub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.FilterChip
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
import com.pancakeworks.fridgegrub.ui.theme.FavoriteHeart
import com.pancakeworks.fridgegrub.ui.theme.FullMatchContainerDark
import com.pancakeworks.fridgegrub.ui.theme.FullMatchContainerLight
import com.pancakeworks.fridgegrub.ui.theme.FullMatchOnContainerDark
import com.pancakeworks.fridgegrub.ui.theme.FullMatchOnContainerLight
import com.pancakeworks.fridgegrub.ui.theme.PartialMatchContainerDark
import com.pancakeworks.fridgegrub.ui.theme.PartialMatchContainerLight
import com.pancakeworks.fridgegrub.ui.theme.PartialMatchOnContainerDark
import com.pancakeworks.fridgegrub.ui.theme.PartialMatchOnContainerLight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pancakeworks.fridgegrub.model.Recipe
import com.pancakeworks.fridgegrub.viewmodel.RecipeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 1-4 difficulty scale from the corpus (see `RecipeEntity.difficulty`'s doc for why these
 * particular words) -- shared between the filter chips here and the card/detail metadata line. */
internal val DIFFICULTY_LABELS = linkedMapOf(
    1 to "Easy",
    2 to "Everyday",
    3 to "A Bit of Work",
    4 to "Go For It!"
)

/** "Up to N minutes" filter presets -- a recipe's cook time is only ~24% populated, so a small
 * fixed set of single-select thresholds is simpler than a slider and self-documents what "no
 * value" means (see `matchesFilters`' doc: it always passes, rather than being hidden). */
private val TIME_FILTER_OPTIONS = listOf(
    "Under 30 min" to 30,
    "Under 1 hr" to 60,
    "Under 2 hrs" to 120
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeScreen(
    fridgeIngredients: List<String>,
    prioritizedIngredients: List<String> = emptyList(),
    pantryIngredients: List<String> = emptyList(),
    onBack: () -> Unit,
    onRecipeClick: (List<Recipe>, Recipe) -> Unit = { _, _ -> },
    // Lets the undo snackbar's fallback advice ("check Previously Favorited") actually be
    // actionable from here -- without this, reaching the Favorites screen meant backing all the
    // way out to the fridge screen first.
    onViewFavorites: () -> Unit = {},
    viewModel: RecipeViewModel = viewModel()
) {
    val allRecipes by viewModel.sortedRecipes.collectAsState()
    val recipes by viewModel.filteredRecipes.collectAsState()
    val filterQuery by viewModel.filterQuery.collectAsState()
    val selectedDifficulties by viewModel.selectedDifficulties.collectAsState()
    val maxCookMinutes by viewModel.maxCookMinutes.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val totalMatchCount by viewModel.totalMatchCount.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // The running mascot only earns its place on a search slow enough to actually be seen and
    // read as "working on it" -- most searches finish well under this, and popping in a full
    // illustration only to immediately swap back to results would read as a flicker, not
    // personality. Reset the instant loading ends (not just left true from a stale search), so a
    // second, slower search still gets its own fresh 2s grace period.
    var showMascot by remember { mutableStateOf(false) }
    LaunchedEffect(isLoading) {
        showMascot = if (isLoading) {
            delay(2000)
            true
        } else {
            false
        }
    }

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

    LaunchedEffect(Unit) { viewModel.searchRecipes(fridgeIngredients, prioritizedIngredients, pantryIngredients) }
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DIFFICULTY_LABELS.forEach { (level, label) ->
                        FilterChip(
                            selected = level in selectedDifficulties,
                            onClick = { viewModel.toggleDifficultyFilter(level) },
                            label = { Text(label) }
                        )
                    }
                    TIME_FILTER_OPTIONS.forEach { (label, minutes) ->
                        FilterChip(
                            selected = maxCookMinutes == minutes,
                            onClick = { viewModel.setMaxCookTimeFilter(minutes) },
                            label = { Text(label) }
                        )
                    }
                }
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading -> if (showMascot) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            RunningMascotScene()
                            Spacer(Modifier.height(16.dp))
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    } else {
                        CircularProgressIndicator()
                    }
                    allRecipes.isEmpty() -> RecipeEmptyMessage(
                        title = "No matching recipes",
                        subtitle = "Try adding a few more ingredients to your fridge"
                    )
                    recipes.isEmpty() -> RecipeEmptyMessage(
                        title = "No results",
                        subtitle = "Nothing matches \"$filterQuery\""
                    )
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
                        // Scroll back to the top whenever any filter (text query, difficulty
                        // chips, or cook-time chips) changes while on this screen -- listState is
                        // never disposed just because a filter changed, so a scroll position from
                        // before still points at whatever now-unrelated rows sit at that same
                        // index/offset in the newly filtered list, which reads as "the filter did
                        // nothing" (same underlying issue as the text-search "x" button, now
                        // fixed for the difficulty/time chips too). Compared against a combined
                        // filter-state snapshot from when this screen instance actually started,
                        // not scrolled unconditionally, so restoring scroll position after
                        // returning from a recipe detail -- a legitimate, separate feature via
                        // viewModel.scrollIndex/scrollOffset above -- isn't undone by this.
                        val filterState = Triple(filterQuery, selectedDifficulties, maxCookMinutes)
                        var lastFilterState by remember { mutableStateOf(filterState) }
                        LaunchedEffect(filterState) {
                            if (filterState != lastFilterState) {
                                lastFilterState = filterState
                                listState.scrollToItem(0)
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
                                    onClick = { onRecipeClick(recipes, recipe) },
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
            if (recipe.unmatchedSeasoningCount > 0) {
                // A much lower bar than a missing Supportive/Defining ingredient -- called out
                // separately so "not quite 100%" doesn't read as more work than it is. Already
                // counted in matchedCount/totalCount above; this is purely informational.
                Text(
                    if (recipe.unmatchedSeasoningCount == 1) "missing 1 seasoning"
                    else "missing ${recipe.unmatchedSeasoningCount} seasonings",
                    style = MaterialTheme.typography.bodySmall,
                    color = ingredientTextColor.copy(alpha = 0.7f)
                )
            }
            // Difficulty/time metadata -- absent for the ~25-75% of recipes the corpus doesn't
            // rate (see RecipeEntity's doc), so this line just doesn't render rather than
            // showing an empty "·". Ingredient count itself is already the denominator of the
            // "X/Y ingredients" line above -- no need to repeat it here.
            val metadataParts = listOfNotNull(DIFFICULTY_LABELS[recipe.difficulty], recipe.timeText)
            if (metadataParts.isNotEmpty()) {
                Text(
                    metadataParts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = ingredientTextColor.copy(alpha = 0.7f)
                )
            }
            if (recipe.categories.isNotEmpty()) {
                Text(
                    recipe.categories.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
