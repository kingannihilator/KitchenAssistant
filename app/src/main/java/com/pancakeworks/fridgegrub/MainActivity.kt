package com.pancakeworks.fridgegrub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pancakeworks.fridgegrub.model.Recipe
import com.pancakeworks.fridgegrub.ui.AboutScreen
import com.pancakeworks.fridgegrub.ui.FavoritesScreen
import com.pancakeworks.fridgegrub.ui.IngredientScreen
import com.pancakeworks.fridgegrub.ui.LoadingScreen
import com.pancakeworks.fridgegrub.ui.RecipeDetailScreen
import com.pancakeworks.fridgegrub.ui.RecipeScreen
import com.pancakeworks.fridgegrub.ui.theme.KitchenAssistantTheme
import com.pancakeworks.fridgegrub.viewmodel.IngredientViewModel

sealed class Screen {
    object Loading : Screen()
    object Ingredients : Screen()
    object Favorites : Screen()
    object About : Screen()
    data class Recipes(val fridgeIngredients: List<String>, val prioritizedIngredients: List<String>) : Screen()
    data class RecipeDetail(
        // The full list the user was browsing (search results or favorites) and which entry was
        // opened -- a snapshot at navigation time, not a live StateFlow, so swiping prev/next stays
        // on the same list even if favoriting/re-sorting would reorder the source screen underneath.
        val recipes: List<Recipe>,
        val index: Int,
        val fridgeIngredients: List<String>,
        val prioritizedIngredients: List<String>,
        // Which list to return to on back -- Favorites is a static, fridge-independent bookmark
        // list (see RecipeViewModel.favoriteRecipes), so it carries no fridge/prioritized snapshot
        // of its own to reconstruct like Recipes does.
        val cameFromFavorites: Boolean = false
    ) : Screen() {
        val recipe: Recipe get() = recipes[index]
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KitchenAssistantTheme {
                val ingredientViewModel: IngredientViewModel = viewModel()
                val fridgeIngredients by ingredientViewModel.ingredients.collectAsState()
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Loading) }
                when (val screen = currentScreen) {
                    is Screen.Loading -> LoadingScreen(
                        onFinished = { currentScreen = Screen.Ingredients }
                    )
                    is Screen.Ingredients -> IngredientScreen(
                        onFindRecipes = { fridge, prioritized ->
                            currentScreen = Screen.Recipes(fridge, prioritized)
                        },
                        onViewFavorites = { currentScreen = Screen.Favorites },
                        onOpenAbout = { currentScreen = Screen.About }
                    )
                    is Screen.About -> AboutScreen(
                        onBack = { currentScreen = Screen.Ingredients }
                    )
                    is Screen.Favorites -> FavoritesScreen(
                        onBack = { currentScreen = Screen.Ingredients },
                        onRecipeClick = { recipes, recipe ->
                            currentScreen = Screen.RecipeDetail(
                                recipes,
                                recipes.indexOfFirst { it.id == recipe.id },
                                fridgeIngredients = emptyList(),
                                prioritizedIngredients = emptyList(),
                                cameFromFavorites = true
                            )
                        }
                    )
                    is Screen.Recipes -> RecipeScreen(
                        fridgeIngredients = screen.fridgeIngredients,
                        prioritizedIngredients = screen.prioritizedIngredients,
                        onBack = { currentScreen = Screen.Ingredients },
                        onRecipeClick = { recipes, recipe ->
                            currentScreen = Screen.RecipeDetail(
                                recipes,
                                recipes.indexOfFirst { it.id == recipe.id },
                                screen.fridgeIngredients,
                                screen.prioritizedIngredients
                            )
                        },
                        onViewFavorites = { currentScreen = Screen.Favorites }
                    )
                    is Screen.RecipeDetail -> RecipeDetailScreen(
                        recipe = screen.recipe,
                        fridgeIngredients = fridgeIngredients.filter { !it.isNegative },
                        onBack = {
                            currentScreen = if (screen.cameFromFavorites) {
                                Screen.Favorites
                            } else {
                                Screen.Recipes(screen.fridgeIngredients, screen.prioritizedIngredients)
                            }
                        },
                        hasPrevious = screen.index > 0,
                        hasNext = screen.index < screen.recipes.lastIndex,
                        onNavigate = { delta ->
                            currentScreen = screen.copy(index = screen.index + delta)
                        }
                    )
                }
            }
        }
    }
}
