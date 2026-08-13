package com.example.kitchenassistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.example.kitchenassistant.model.Recipe
import com.example.kitchenassistant.ui.theme.FavoriteHeart
import com.example.kitchenassistant.viewmodel.RecipeViewModel

/**
 * The favorites shortcut screen, reached from the fridge screen's top-bar heart button.
 *
 * Deliberately separate from [RecipeScreen] rather than a filtered view over its search results:
 * this list is sourced from [RecipeViewModel.favoriteRecipes], which loads recipes directly by id
 * and only changes when a favorite is added or removed -- not on every fridge edit or
 * re-navigation the way a live search would. That also means it works with an empty fridge, where
 * [RecipeViewModel.searchRecipes] has nothing to score against and would show nothing at all.
 *
 * Cards here don't show match-ratio coloring or an ingredient count -- there's no fridge-relative
 * score to show (see [RecipeViewModel.favoriteRecipes]'s doc). Opening a recipe from here still
 * goes to the normal [com.example.kitchenassistant.ui.RecipeDetailScreen], which computes real
 * matched/missing checkmarks against the current fridge as usual.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onRecipeClick: (Recipe) -> Unit = {},
    viewModel: RecipeViewModel = viewModel()
) {
    val favorites by viewModel.favoriteRecipes.collectAsState()
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Favorites (${favorites.size})") }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            if (favorites.isEmpty()) {
                Text(
                    "You haven't favorited any recipes yet — tap the heart on a recipe to save it here.",
                    modifier = Modifier.padding(horizontal = 32.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(favorites, key = { it.id }) { recipe ->
                        FavoriteCard(recipe, onClick = { onRecipeClick(recipe) })
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

@Composable
private fun FavoriteCard(recipe: Recipe, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(recipe.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = "Favorited",
                tint = FavoriteHeart,
                modifier = Modifier.padding(start = 4.dp).size(28.dp)
            )
        }
    }
}
