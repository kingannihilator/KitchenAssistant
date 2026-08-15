package com.example.kitchenassistant.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.kitchenassistant.model.Ingredient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import com.example.kitchenassistant.data.AppModeRepository
import com.example.kitchenassistant.data.BundledDatabase
import com.example.kitchenassistant.data.FridgeRepository
import com.example.kitchenassistant.data.IngredientPopularityIndex
import com.example.kitchenassistant.data.NewIngredientIndex
import com.example.kitchenassistant.data.NewRecipeDao
import com.example.kitchenassistant.data.NewRecipeDatabase
import com.example.kitchenassistant.model.AppMode
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * The ingredient list is persisted to this device via [FridgeRepository] (SharedPreferences-backed,
 * same mechanism as favorites) -- survives app close and process death, cleared only by
 * uninstalling the app. No account-level sync yet; see [FridgeRepository]'s doc.
 */
class IngredientViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /**
         * Ranks [candidates] for the autocomplete dropdown against [trimmedQuery]: prefix matches
         * before mid-word matches, and within each group, higher [frequencyOf] first, then fewer
         * extra words, then alphabetical as the final tiebreak.
         *
         * [frequencyOf] is expected to be real recipe-corpus popularity (see
         * [IngredientPopularityIndex]) — the bundled `ingredients.db` itself has no such signal
         * (schema is just `id, name_en`; see `porting-reference/INGREDIENT_MATCHING_CONCEPTS.md`).
         * It defaults to "everyone's equally (un)popular" so this still produces a sane, fully
         * deterministic order before that index has finished loading (see
         * [IngredientViewModel.onSearchQueryChange]): word count is the next-best proxy for how
         * general a name is — "chicken breast" and "chicken neck" are both 2-word entries and land
         * together regardless of their differing character lengths — with alphabetical order
         * making the remaining ties predictable to type toward.
         *
         * A free function on the companion object (not a method) specifically so it needs no
         * Application/Context and can be unit tested under plain JUnit, same reasoning as
         * [com.example.kitchenassistant.data.IngredientMatcher].
         */
        internal fun rankSuggestions(
            candidates: List<String>,
            trimmedQuery: String,
            frequencyOf: (String) -> Int = { 0 }
        ): List<String> {
            if (trimmedQuery.isEmpty()) return emptyList()
            val (prefixMatches, midWordMatches) = candidates
                .filter { it.contains(trimmedQuery, ignoreCase = true) }
                .partition { it.startsWith(trimmedQuery, ignoreCase = true) }
            val ranking = compareByDescending<String> { frequencyOf(it) }
                .thenBy { it.trim().split(WHITESPACE).size }
                .thenBy { it.lowercase() }
            return prefixMatches.sortedWith(ranking) + midWordMatches.sortedWith(ranking)
        }

        private val WHITESPACE = Regex("\\s+")

        // rankSuggestions scans the full ~8k-row ingredient list and sorts twice; cheap in
        // isolation, but a fast typist can fire it once per keystroke. Debouncing lets a run of
        // keystrokes collapse into a single recompute instead of one per character.
        private const val SEARCH_DEBOUNCE_MS = 200L
    }

    // --- State: ingredient list ---

    private val fridgeRepository = FridgeRepository(application)

    // Backing mutable flow; only this class can write to it. Seeded synchronously from the saved
    // fridge (a small SharedPreferences read, not worth a loading state) so the very first
    // composition already shows real data instead of a flash of "fridge is empty".
    private val _ingredients = MutableStateFlow(fridgeRepository.load())

    // Public read-only view exposed to the UI.
    val ingredients: StateFlow<List<Ingredient>> = _ingredients.asStateFlow()

    init {
        // Persist on every change rather than sprinkling a save call through every mutator
        // (addIngredient, removeIngredient, setCount, togglePrioritized, ...) -- one place that
        // can't be forgotten when a new mutation is added later. drop(1) skips the replay of the
        // value _ingredients already starts with (the load above), which hasn't actually changed.
        viewModelScope.launch(Dispatchers.IO) {
            ingredients.drop(1).collect { list -> fridgeRepository.save(list) }
        }
    }

    // --- State: app mode (Basic = presence-only, Full = quantities + cook mode) ---

    private val appModeRepository = AppModeRepository(application)

    private val _appMode = MutableStateFlow(appModeRepository.load())
    val appMode: StateFlow<AppMode> = _appMode.asStateFlow()

    /** Switches the app between Basic (presence-only) and Full (quantities) mode. */
    fun setAppMode(mode: AppMode) {
        _appMode.value = mode
        appModeRepository.save(mode)
    }

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

    // True when the current query is an exact ingredients.db match (Add is enabled) that matches
    // nothing at all in the recipe corpus -- addable, but won't help find any recipe. The UI shows
    // this in the dropdown's spot once the dropdown itself has nothing left to show; see
    // AddIngredientCard. False (not just unset) while unknown, so nothing renders prematurely.
    private val _hasNoRecipeMatch = MutableStateFlow(false)
    val hasNoRecipeMatch: StateFlow<Boolean> = _hasNoRecipeMatch.asStateFlow()

    // Full list of ingredient names loaded once from assets/ingredients.db, off the main thread
    // (see init below). Kept in memory for instant, no-network filtering once loaded. @Volatile:
    // written from the IO dispatcher, read from the main thread on every keystroke/rename check.
    // Empty until the load finishes -- autocomplete and rename validation just see no matches yet,
    // self-correcting the moment it's populated; LoadingScreen's own startup delay means a real
    // user is very unlikely to type anything before this small file finishes loading anyway.
    @Volatile
    private var allIngredients: List<String> = emptyList()

    init {
        // BundledDatabase copies assets/ingredients.db into the app's internal databases directory
        // (SQLiteDatabase needs a real file path) and re-copies it whenever the bundled asset
        // changes, so a regenerated taxonomy actually reaches an existing install. Run off the
        // main thread: this is real I/O (and, on first run, a SHA-256 hash of the asset), not
        // something a ViewModel constructor should block on.
        //
        // If the file is missing or the query fails for any reason, we fall back to an empty
        // list so the app still works — autocomplete just won't offer suggestions.
        viewModelScope.launch(Dispatchers.IO) {
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
    }

    // Real-world popularity signal from the bundled recipe corpus, loaded lazily on the first
    // search (see ensurePopularityIndexLoaded) rather than at startup alongside allIngredients,
    // since building it means opening and querying a second, much larger database. @Volatile:
    // written from the IO dispatcher in ensurePopularityIndexLoaded, read from the main thread on
    // every keystroke.
    @Volatile
    private var popularityIndex: IngredientPopularityIndex? = null

    private fun newRecipeDao(): NewRecipeDao = NewRecipeDatabase.getInstance(getApplication()).newRecipeDao()

    // The in-flight debounced recompute from the most recent [onSearchQueryChange] call, if any --
    // cancelled and replaced on every keystroke so only the last one in a fast run actually runs.
    private var searchJob: Job? = null

    /**
     * Called on every keystroke in the ingredient name field.
     *
     * The query itself updates immediately, so the text field stays responsive and in sync with
     * what the user typed. The suggestion recompute -- a scan over all of [allIngredients] plus
     * two sorts in [rankSuggestions] -- is debounced by [SEARCH_DEBOUNCE_MS] and run off the main
     * thread, so a fast typist collapses a run of keystrokes into one recompute instead of paying
     * for every intermediate one. Suggestions are only shown when the query is at least 2
     * characters long to avoid flooding the dropdown on a single-character prefix.
     */
    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)

            // Matching uses the trimmed query, not the raw one: many IME word-suggestion strips
            // insert a trailing space when a word is tapped (to prime the next word), so
            // selecting "chicken" from that strip can leave the field holding "chicken " (with a
            // trailing space). Left untrimmed, that space makes the plain "chicken" entry fail
            // both an exact match ("chicken " != "chicken") and a substring match against itself,
            // while multi-word entries like "chicken fat" still match, since the space just falls
            // before "fat" — exactly backwards from what the user picked. The field itself keeps
            // the raw text so the user can keep typing past that space normally.
            val trimmed = query.trim()
            val (isValid, newSuggestions) = withContext(Dispatchers.Default) {
                val valid = allIngredients.any { it.equals(trimmed, ignoreCase = true) }
                val suggestions = if (trimmed.length >= 2) rankWithCurrentFrequencies(trimmed) else emptyList()
                valid to suggestions
            }

            _isQueryValid.value = isValid
            _suggestions.value = newSuggestions
            ensurePopularityIndexLoaded()

            if (isValid) {
                checkRecipeMatch(trimmed)
            } else {
                // Nothing addable yet, so nothing to hint about -- and this also clears a hint
                // left over from a previous exact match now that the user has typed past it.
                _hasNoRecipeMatch.value = false
            }
        }
    }

    /**
     * Called when the user taps a suggestion in the autocomplete dropdown. A suggestion is always
     * an exact [allIngredients] match by construction, so -- unlike [onSearchQueryChange] --
     * [isQueryValid] is set immediately rather than going through the debounce: the caller also
     * clears the dropdown right after selecting, and that clear used to race the just-launched
     * debounced job in [onSearchQueryChange], cancelling it via [searchJob] before its delay
     * elapsed. [isQueryValid] and [hasNoRecipeMatch] were left stale as a result -- picking a
     * suggestion that has zero recipe matches silently skipped the "not used in any of our
     * recipes" hint, since the check that would have set it never got to run.
     */
    fun selectSuggestion(name: String) {
        searchJob?.cancel()
        _searchQuery.value = name
        _suggestions.value = emptyList()
        _isQueryValid.value = true
        checkRecipeMatch(name.trim())
        ensurePopularityIndexLoaded()
    }

    /**
     * Checks whether [trimmed] (already confirmed to be an exact `ingredients.db` match) matches
     * anything at all in the recipe corpus, via [NewIngredientIndex.matching] -- the identical
     * logic real search runs, so this can never disagree with what search actually finds.
     * Updates [hasNoRecipeMatch] if [trimmed] is still the live query when the (first-call-only
     * expensive; see [NewIngredientIndex.get]) lookup finishes.
     */
    private fun checkRecipeMatch(trimmed: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val matched = try {
                val index = NewIngredientIndex.get(newRecipeDao(), RecipeViewModel.BLOB_NAME_LENGTH_THRESHOLD_NEW)
                index.matching(listOf(trimmed)).isNotEmpty()
            } catch (e: Exception) {
                true // Unknown on failure -- silently hiding the hint beats wrongly claiming "no matches".
            }
            if (_searchQuery.value.trim().equals(trimmed, ignoreCase = true)) {
                _hasNoRecipeMatch.value = !matched
            }
        }
    }

    private fun rankWithCurrentFrequencies(trimmed: String): List<String> {
        val index = popularityIndex
        return if (index != null) {
            rankSuggestions(allIngredients, trimmed) { index.frequencyFor(it) }
        } else {
            rankSuggestions(allIngredients, trimmed)
        }
    }

    /**
     * Builds [popularityIndex] in the background on first use, then re-ranks whatever query is
     * showing at that moment so the dropdown upgrades to popularity-ranked order in place, rather
     * than the very first search waiting on it. A no-op once [popularityIndex] is loaded.
     *
     * Safe to call on every keystroke before that: [IngredientPopularityIndex.get] has its own
     * double-checked singleton cache, so a few redundant launches racing to build it before the
     * first one finishes just join the same work rather than repeating it.
     */
    private fun ensurePopularityIndexLoaded() {
        if (popularityIndex != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val built = try {
                IngredientPopularityIndex.get(newRecipeDao(), RecipeViewModel.BLOB_NAME_LENGTH_THRESHOLD_NEW)
            } catch (e: Exception) {
                null
            } ?: return@launch
            popularityIndex = built
            val current = _searchQuery.value.trim()
            if (current.length >= 2) _suggestions.value = rankWithCurrentFrequencies(current)
        }
    }

    /** Clears the autocomplete dropdown, e.g. after the user selects a suggestion or taps away. */
    fun clearSuggestions() {
        searchJob?.cancel()
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
        searchJob?.cancel()
        _searchQuery.value = ""
        _suggestions.value = emptyList()
        _isQueryValid.value = false
        _hasNoRecipeMatch.value = false
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
     * Re-inserts [ingredient] as-is (same id, star/count/unit/expiration) at [atIndex], for the
     * delete-with-undo row in [com.example.kitchenassistant.ui.IngredientScreen] -- unlike
     * [addIngredient], which always mints a fresh id/defaults and appends, this restores exactly
     * what was removed, back where it was, rather than at the end of the list.
     */
    fun restoreIngredient(ingredient: Ingredient, atIndex: Int) {
        _ingredients.update { list ->
            list.toMutableList().apply { add(atIndex.coerceIn(0, size), ingredient) }
        }
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

    /** True if [name] (trimmed) exactly matches an entry in the bundled ingredient database, case-insensitive. */
    fun isKnownIngredientName(name: String): Boolean =
        allIngredients.any { it.equals(name.trim(), ignoreCase = true) }

    /**
     * Renames the ingredient with the given [id] to [newName], reusing the same
     * database-membership validation as the add-ingredient field. A no-op (name left unchanged)
     * if [newName] isn't a recognized ingredient -- callers don't need to check first, but see
     * [isKnownIngredientName] if the UI wants to reflect validity live while the user types.
     *
     * Renaming to a name that collides with another fridge row is allowed and does not merge the
     * two -- unlike [quickAddIngredient], which merges deliberately for its one-tap-repeat use
     * case. A rename is a deliberate single edit, not a repeated shortcut, so silently combining
     * it with an unrelated existing row would be more surprising than helpful.
     */
    fun renameIngredient(id: String, newName: String) {
        val trimmed = newName.trim()
        if (!isKnownIngredientName(trimmed)) return
        _ingredients.update { list ->
            list.map { if (it.id == id) it.copy(name = trimmed) else it }
        }
    }

    /** Updates the unit of measurement of the ingredient with the given [id]. */
    fun setUnit(id: String, unit: String) {
        _ingredients.update { list ->
            list.map { if (it.id == id) it.copy(unit = unit) else it }
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
     * Toggles the prioritized state of the ingredient with the given [id].
     * Multiple ingredients can be prioritized at the same time.
     */
    fun togglePrioritized(id: String) {
        _ingredients.update { list ->
            list.map { if (it.id == id) it.copy(isPrioritized = !it.isPrioritized) else it }
        }
    }
}
