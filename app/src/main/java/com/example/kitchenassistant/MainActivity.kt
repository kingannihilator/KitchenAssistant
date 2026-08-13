package com.example.kitchenassistant

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
import com.example.kitchenassistant.model.Recipe
import com.example.kitchenassistant.ui.FavoritesScreen
import com.example.kitchenassistant.ui.IngredientScreen
import com.example.kitchenassistant.ui.RecipeDetailScreen
import com.example.kitchenassistant.ui.RecipeScreen
import com.example.kitchenassistant.ui.theme.KitchenAssistantTheme
import com.example.kitchenassistant.viewmodel.IngredientViewModel

sealed class Screen {
    object Ingredients : Screen()
    object Favorites : Screen()
    data class Recipes(val fridgeIngredients: List<String>, val prioritizedIngredients: List<String>) : Screen()
    data class RecipeDetail(
        val recipe: Recipe,
        val fridgeIngredients: List<String>,
        val prioritizedIngredients: List<String>,
        // Which list to return to on back -- Favorites is a static, fridge-independent bookmark
        // list (see RecipeViewModel.favoriteRecipes), so it carries no fridge/prioritized snapshot
        // of its own to reconstruct like Recipes does.
        val cameFromFavorites: Boolean = false
    ) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KitchenAssistantTheme {
                val ingredientViewModel: IngredientViewModel = viewModel()
                val fridgeIngredients by ingredientViewModel.ingredients.collectAsState()
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Ingredients) }
                when (val screen = currentScreen) {
                    is Screen.Ingredients -> IngredientScreen(
                        onFindRecipes = { fridge, prioritized ->
                            currentScreen = Screen.Recipes(fridge, prioritized)
                        },
                        onViewFavorites = { currentScreen = Screen.Favorites }
                    )
                    is Screen.Favorites -> FavoritesScreen(
                        onBack = { currentScreen = Screen.Ingredients },
                        onRecipeClick = { recipe ->
                            currentScreen = Screen.RecipeDetail(
                                recipe,
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
                        onRecipeClick = { recipe ->
                            currentScreen = Screen.RecipeDetail(
                                recipe,
                                screen.fridgeIngredients,
                                screen.prioritizedIngredients
                            )
                        }
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
                        onDeductIngredient = { id -> ingredientViewModel.decrementCount(id) },
                        onSetCount = { id, count -> ingredientViewModel.setCountByIdOrRemove(id, count) }
                    )
                }
            }
        }
    }
}
