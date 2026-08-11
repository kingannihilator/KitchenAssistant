package com.example.kitchenassistant.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.kitchenassistant.model.Ingredient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.kitchenassistant.data.BundledDatabase
import kotlinx.coroutines.flow.update

/**
 * ViewModel for the ingredient list screen.
 *
 * Extends AndroidViewModel (instead of plain ViewModel) so it can hold a reference to the
 * Application context, which is needed to read files from the assets folder.
 *
 * All state is exposed as read-only [StateFlow]s. The UI collects these flows and recomposes
 * automatically whenever a value changes. Mutations go through the private MutableStateFlow
 * backing properties to prevent the UI from writing state directly.
 *
 * The ingredient list is currently held in memory only — it is lost when the app process ends.
 */
class IngredientViewModel(application: Application) : AndroidViewModel(application) {

    // --- State: ingredient list ---

    // Backing mutable flow; only this class can write to it.
    private val _ingredients = MutableStateFlow<List<Ingredient>>(emptyList())

    // Public read-only view exposed to the UI.
    val ingredients: StateFlow<List<Ingredient>> = _ingredients.asStateFlow()

    // --- State: search / autocomplete ---

    // The current text the user has typed into the ingredient name field.
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // The filtered list of autocomplete suggestions shown in the dropdown.
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    // True when the current search query exactly matches an entry in the database (case-insensitive).
    // Controls whether the Add button is enabled.
    private val _isQueryValid = MutableStateFlow(false)
    val isQueryValid: StateFlow<Boolean> = _isQueryValid.asStateFlow()

    // Full list of ingredient names loaded once from assets/ingredients.db at startup.
    // Kept in memory for instant, no-network filtering.
    private val allIngredients: List<String>

    init {
        // BundledDatabase copies assets/ingredients.db into the app's internal databases directory
        // (SQLiteDatabase needs a real file path) and re-copies it whenever the bundled asset
        // changes, so a regenerated taxonomy actually reaches an existing install.
        //
        // If the file is missing or the query fails for any reason, we fall back to an empty
        // list so the app still works — autocomplete just won't offer suggestions.
        allIngredients = try {
            BundledDatabase.openReadOnly(application, "ingredients.db").use { database ->
                // Pull every non-blank name from the ingredients table.
                database.rawQuery("SELECT name_en FROM ingredients", null).use { cursor ->
                    val names = mutableListOf<String>()
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { names.add(it) }
                    }
                    names
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Called on every keystroke in the ingredient name field.
     *
     * Updates the search query and immediately recomputes suggestions by filtering
     * [allIngredients] for entries that contain the query (case-insensitive).
     * Suggestions are only shown when the query is at least 2 characters long to avoid
     * flooding the dropdown on a single-character prefix.
     * Results are capped at 5 to keep the dropdown concise.
     */
    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        _isQueryValid.value = allIngredients.any { it.equals(query, ignoreCase = true) }
        _suggestions.value = if (query.length >= 2) {
            // Partition into prefix matches and mid-word matches, then concatenate so
            // prefix matches always appear first. Both groups retain their original order.
            val (prefixMatches, midWordMatches) = allIngredients
                .filter { it.contains(query, ignoreCase = true) }
                .partition { it.startsWith(query, ignoreCase = true) }
            // Within each group, shorter names appear first.
            prefixMatches.sortedBy { it.length } + midWordMatches.sortedBy { it.length }
        } else {
            emptyList()
        }
    }

    /** Clears the autocomplete dropdown, e.g. after the user selects a suggestion or taps away. */
    fun clearSuggestions() {
        _suggestions.value = emptyList()
    }

    /**
     * Adds a new ingredient to the list.
     *
     * Blank names are rejected. The search query and suggestions are cleared after adding
     * so the text field resets for the next entry. The [count] defaults to 1 if not specified.
     */
    fun addIngredient(name: String, expirationDate: Long?, count: Int = 1, unit: String = "units") {
        if (name.isBlank()) return
        _ingredients.update {
            it + Ingredient(name = name.trim(), expirationDate = expirationDate, count = count, unit = unit)
        }
        _searchQuery.value = ""
        _suggestions.value = emptyList()
        _isQueryValid.value = false
    }

    /**
     * Adds one unit of [name] to the fridge, used by the quick-add thumbnails.
     *
     * If an ingredient with this name (case-insensitive) is already in the fridge, its count is
     * incremented instead of creating a duplicate row — repeated taps on the same thumbnail build
     * up quantity rather than spawning a new card each time. Otherwise behaves like [addIngredient]
     * with no expiration date and a count of 1.
     */
    fun quickAddIngredient(name: String) {
        val existing = _ingredients.value.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (existing != null) {
            incrementCount(existing.id)
        } else {
            addIngredient(name, expirationDate = null, count = 1)
        }
    }

    /** Removes the ingredient with the given [id] from the list. */
    fun removeIngredient(id: String) {
        _ingredients.update { it.filter { ing -> ing.id != id } }
    }

    /**
     * Sets the count of the ingredient with the given [id] to an explicit value.
     * Values below 1 are ignored — use [decrementCount] to remove.
     */
    fun setCount(id: String, count: Int) {
        if (count < 1) return
        _ingredients.update { list ->
            list.map { if (it.id == id) it.copy(count = count) else it }
        }
    }

    /** Increases the count of the ingredient with the given [id] by 1. */
    fun incrementCount(id: String) {
        _ingredients.update { list ->
            list.map { if (it.id == id) it.copy(count = it.count + 1) else it }
        }
    }

    /**
     * Decreases the count of the ingredient with the given [id] by 1.
     * If the count would reach 0, the ingredient is removed from the list entirely.
     */
    fun decrementCount(id: String) {
        _ingredients.update { list ->
            list.mapNotNull {
                if (it.id == id) {
                    if (it.count <= 1) null else it.copy(count = it.count - 1)
                } else it
            }
        }
    }

    /** Updates the expiration date of the ingredient with the given [id]. Pass null to clear it. */
    fun setExpirationDate(id: String, date: Long?) {
        _ingredients.update { list ->
            list.map { if (it.id == id) it.copy(expirationDate = date) else it }
        }
    }

    /** Removes all ingredients from the list. */
    fun clearAll() {
        _ingredients.value = emptyList()
    }

//    /**
//     * Toggles the excluded (negative) state of the ingredient with the given [id].
//     *
//     * When an ingredient is excluded it is shown with a strikethrough and red background.
//     * If the ingredient was prioritized before being excluded, that status is cleared.
//     */
//    fun toggleExclude(id: String) {
//        _ingredients.update { list ->
//            list.map { ing ->
//                if (ing.id == id) ing.copy(
//                    isNegative = !ing.isNegative,
//                    // Clear prioritized status when moving from included → excluded.
//                    isPrioritized = if (!ing.isNegative) false else ing.isPrioritized
//                )
//                else ing
//            }
//        }
//    }

    /**
     * Sets the count of the ingredient with the given [id], removing it once the count reaches 0.
     *
     * Used by cook mode, where typing 0 into the stepper means "I've used it all up" rather than
     * being ignored the way [setCount] would.
     */
    fun setCountByIdOrRemove(id: String, count: Int) {
        if (count <= 0) removeIngredient(id) else setCount(id, count)
    }

    /**
     * Toggles the prioritized state of the ingredient with the given [id].
     * Multiple ingredients can be prioritized at the same time.
     */
    fun togglePrioritized(id: String) {
        _ingredients.update { list ->
            list.map { if (it.id == id) it.copy(isPrioritized = !it.isPrioritized) else it }
        }
    }
}
