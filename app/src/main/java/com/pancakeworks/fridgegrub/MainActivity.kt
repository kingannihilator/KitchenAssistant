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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pancakeworks.fridgegrub.model.Recipe
import com.pancakeworks.fridgegrub.ui.AboutScreen
import com.pancakeworks.fridgegrub.ui.FavoritesScreen
import com.pancakeworks.fridgegrub.ui.IngredientScreen
import com.pancakeworks.fridgegrub.ui.LoadingScreen
import com.pancakeworks.fridgegrub.ui.OnboardingScreen
import com.pancakeworks.fridgegrub.ui.PantryScreen
import com.pancakeworks.fridgegrub.ui.RecipeDetailScreen
import com.pancakeworks.fridgegrub.ui.RecipeScreen
import com.pancakeworks.fridgegrub.ui.theme.KitchenAssistantTheme
import com.pancakeworks.fridgegrub.viewmodel.IngredientViewModel
import kotlinx.coroutines.launch

sealed class Screen {
    object Loading : Screen()
    object Ingredients : Screen()
    // returnTo: which screen to go back to -- Favorites is reachable from both Ingredients and
    // Recipes (each has its own "view favorites" heart icon), so a hardcoded destination sent
    // every visitor back to Ingredients regardless of where they actually came from.
    data class Favorites(val returnTo: Screen = Ingredients) : Screen()
    object About : Screen()
    // The first-run walkthrough. A bare object with no returnTo, unlike Favorites/Pantry: it is
    // reachable only from Loading and its only exit is forward, so there is nowhere to go back to.
    object Onboarding : Screen()
    // returnTo: same reasoning as Favorites above -- Pantry is now reachable from Ingredients,
    // Recipes, and RecipeDetail, each with its own pantry-icon entry point, so a hardcoded
    // destination would strand a visitor from Recipes/RecipeDetail back on Ingredients instead.
    data class Pantry(val isOnboarding: Boolean, val returnTo: Screen = Ingredients) : Screen()
    data class Recipes(
        val fridgeIngredients: List<String>,
        val prioritizedIngredients: List<String>,
        // Kept separate from fridgeIngredients (not pre-merged) so RecipeViewModel can tell a
        // real-fridge match from a pantry-only one -- see RecipeMatch.usesRealFridgeItem's doc.
        val pantryIngredients: List<String> = emptyList()
    ) : Screen()
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
        val cameFromFavorites: Boolean = false,
        // Only used to reconstruct Screen.Recipes on back navigation, same as fridgeIngredients/
        // prioritizedIngredients above -- RecipeDetailScreen itself reads pantry items live from
        // IngredientViewModel, not from here.
        val pantryIngredients: List<String> = emptyList(),
        // Only meaningful when cameFromFavorites -- carries Favorites' own returnTo forward, so
        // going Recipes -> Favorites -> a recipe -> back -> Favorites -> back still lands on
        // Recipes, not Ingredients.
        val favoritesReturnTo: Screen = Ingredients
    ) : Screen() {
        val recipe: Recipe get() = recipes[index]
    }
}

/**
 * Single source of truth for the first-run sequence: the intro walkthrough, then the pantry
 * confirmation, then the app itself. Pure -- two booleans in, one Screen out -- so the ordering is
 * testable under plain JUnit without Android, the same reason `RecipeRanking.kt` was split out of
 * `RecipeViewModel`.
 *
 * The `hasSeenPantry` half of the first condition is a migration guard, not redundancy: `introSeen`
 * is a key that did not exist before the walkthrough shipped, so every install that predates it
 * reads back `false` and would otherwise be shown a first-run carousel for an app it has been using
 * for months. Anyone who has already confirmed their pantry has demonstrably used the app, so they
 * count as past the intro. For genuinely new installs both flags start `false` and the ordering is
 * the obvious one.
 */
internal fun firstRunDestination(introSeen: Boolean, hasSeenPantry: Boolean): Screen = when {
    !introSeen && !hasSeenPantry -> Screen.Onboarding
    !hasSeenPantry -> Screen.Pantry(isOnboarding = true)
    else -> Screen.Ingredients
}

/**
 * Testing switch: show the first-run carousel on every launch, ignoring the stored flags.
 *
 * Keyed to `BuildConfig.DEBUG` rather than a hand-flipped boolean deliberately. "Remember to turn
 * it off before release" is exactly the kind of instruction that gets forgotten, and forgetting
 * this one would push a first-run carousel at every existing user on update. Tied to the build
 * type there is nothing to remember and nothing to revert: release builds -- which is what reaches
 * Play Console, internal-testing and closed tracks included -- always take the real path.
 */
internal val FORCE_ONBOARDING_FOR_TESTING: Boolean = BuildConfig.DEBUG

/**
 * The screen to open with. Separate from [firstRunDestination] rather than folded into it so the
 * real rule stays testable in isolation and stays correct on its own after the testing switch is
 * gone; this is only the seam the switch plugs into.
 *
 * Note the switch applies *here* and not to the carousel's own exit. That exit calls
 * [firstRunDestination] directly, so finishing or skipping the carousel still moves forward into
 * the app -- routing it through this same function would bounce the user straight back into the
 * carousel they just dismissed, forever.
 */
internal fun initialDestination(
    showOnboardingForTesting: Boolean,
    introSeen: Boolean,
    hasSeenPantry: Boolean
): Screen = if (showOnboardingForTesting) {
    Screen.Onboarding
} else {
    firstRunDestination(introSeen, hasSeenPantry)
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
                        onFinished = {
                            currentScreen = initialDestination(
                                showOnboardingForTesting = FORCE_ONBOARDING_FOR_TESTING,
                                introSeen = ingredientViewModel.hasSeenIntro(),
                                hasSeenPantry = ingredientViewModel.hasSeenPantryOnboarding()
                            )
                        }
                    )
                    is Screen.Onboarding -> OnboardingScreen(
                        onDone = {
                            ingredientViewModel.markIntroSeen()
                            // introSeen = true literally rather than re-read from prefs: it was
                            // just written above, and this removes any question about when
                            // SharedPreferences.apply() lands.
                            currentScreen = firstRunDestination(
                                introSeen = true,
                                hasSeenPantry = ingredientViewModel.hasSeenPantryOnboarding()
                            )
                        }
                    )
                    is Screen.Ingredients -> IngredientScreen(
                        onFindRecipes = { fridge, prioritized, pantry ->
                            currentScreen = Screen.Recipes(fridge, prioritized, pantry)
                        },
                        onViewFavorites = { currentScreen = Screen.Favorites(returnTo = screen) },
                        onOpenAbout = { currentScreen = Screen.About },
                        onOpenPantry = { currentScreen = Screen.Pantry(isOnboarding = false) }
                    )
                    is Screen.About -> AboutScreen(
                        onBack = { currentScreen = Screen.Ingredients }
                    )
                    is Screen.Pantry -> PantryScreen(
                        isOnboarding = screen.isOnboarding,
                        onDone = { currentScreen = screen.returnTo }
                    )
                    is Screen.Favorites -> FavoritesScreen(
                        onBack = { currentScreen = screen.returnTo },
                        onRecipeClick = { recipes, recipe ->
                            currentScreen = Screen.RecipeDetail(
                                recipes,
                                recipes.indexOfFirst { it.id == recipe.id },
                                fridgeIngredients = emptyList(),
                                prioritizedIngredients = emptyList(),
                                cameFromFavorites = true,
                                favoritesReturnTo = screen.returnTo
                            )
                        }
                    )
                    is Screen.Recipes -> RecipeScreen(
                        fridgeIngredients = screen.fridgeIngredients,
                        prioritizedIngredients = screen.prioritizedIngredients,
                        pantryIngredients = screen.pantryIngredients,
                        onBack = { currentScreen = Screen.Ingredients },
                        onRecipeClick = { recipes, recipe ->
                            currentScreen = Screen.RecipeDetail(
                                recipes,
                                recipes.indexOfFirst { it.id == recipe.id },
                                screen.fridgeIngredients,
                                screen.prioritizedIngredients,
                                pantryIngredients = screen.pantryIngredients
                            )
                        },
                        onViewFavorites = { currentScreen = Screen.Favorites(returnTo = screen) },
                        onOpenPantry = { currentScreen = Screen.Pantry(isOnboarding = false, returnTo = screen) }
                    )
                    is Screen.RecipeDetail -> RecipeDetailScreen(
                        recipe = screen.recipe,
                        fridgeIngredients = fridgeIngredients.filter { !it.isNegative },
                        onBack = {
                            currentScreen = if (screen.cameFromFavorites) {
                                Screen.Favorites(returnTo = screen.favoritesReturnTo)
                            } else {
                                Screen.Recipes(screen.fridgeIngredients, screen.prioritizedIngredients, screen.pantryIngredients)
                            }
                        },
                        hasPrevious = screen.index > 0,
                        hasNext = screen.index < screen.recipes.lastIndex,
                        onNavigate = { delta ->
                            currentScreen = screen.copy(index = screen.index + delta)
                        },
                        onOpenPantry = { currentScreen = Screen.Pantry(isOnboarding = false, returnTo = screen) },
                        onCookSessionCompleted = {
                            // recordCookSession() is the whole decision -- it owns the counter and
                            // the one-shot flag -- so this only has to handle the "yes, now" case.
                            if (ingredientViewModel.recordCookSession()) {
                                // lifecycleScope, not a composable's rememberCoroutineScope: the
                                // review flow outlives this click callback, and it should die with
                                // the Activity (which is also the Activity Play needs) rather than
                                // with whatever composable happened to be on screen.
                                lifecycleScope.launch { InAppReview.request(this@MainActivity) }
                            }
                        }
                    )
                }
            }
        }
    }
}
