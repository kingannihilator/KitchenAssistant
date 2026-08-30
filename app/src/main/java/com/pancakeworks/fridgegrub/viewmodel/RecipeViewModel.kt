package com.pancakeworks.fridgegrub.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.sqlite.db.SimpleSQLiteQuery
import com.pancakeworks.fridgegrub.data.FavoritesRepository
import com.pancakeworks.fridgegrub.data.IngredientMatcher
import com.pancakeworks.fridgegrub.data.NewIngredientIndex
import com.pancakeworks.fridgegrub.data.NewRecipeDao
import com.pancakeworks.fridgegrub.data.NewRecipeDatabase
import com.pancakeworks.fridgegrub.model.DetailIngredient
import com.pancakeworks.fridgegrub.model.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecipeViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "RecipeViewModel"

        // A common fridge ingredient (e.g. "egg") can match thousands of recipes, and nobody
        // scrolls past the first few hundred best matches anyway, so only the top-ranked ones get
        // their full details fetched. Hydrating 500 measured at 0.04s / 0.04s / 0.10s for the
        // three detail queries, so this is cheap to raise further if the starred-ingredient boost
        // ever needs more room.
        private const val MAX_RESULTS = 500

        // A known data-quality issue (see porting-reference/NEW_CORPUS_DATA_QUALITY.md): ~2.7% of
        // recipe_ingredients rows point at a "blob" ingredient name — unparsed raw text, not a
        // clean ingredient. A reversible, app-side mitigation, not a real fix: suppresses affected
        // recipes from ranking; recovering the underlying blob names themselves is deferred (see
        // porting-reference/head_categories.json and the taxonomy-construction docs for where that
        // work picks back up).
        const val SUPPRESS_BLOB_RECIPES_NEW = true

        // Mirrors the Python BLOB_NAME_LENGTH_THRESHOLD in
        // porting-reference/extract_ingredient_heads.py — a normalized_name longer than this is
        // treated as un-stripped raw text rather than a real ingredient name. Kept as the one
        // source of truth here and threaded into NewRecipeDao/NewIngredientIndex calls, rather
        // than hardcoded in multiple places.
        const val BLOB_NAME_LENGTH_THRESHOLD_NEW = 40
    }

    private val favoritesRepository = FavoritesRepository(getApplication())

    private val _favoriteIds = MutableStateFlow<Set<Int>>(emptySet())
    val favoriteIds: StateFlow<Set<Int>> = _favoriteIds.asStateFlow()

    // Every id ever favorited, including ones since removed -- see FavoritesRepository's doc.
    private val _favoriteHistoryIds = MutableStateFlow<Set<Int>>(emptySet())

    init {
        _favoriteIds.value = favoritesRepository.getFavoriteIds()
        _favoriteHistoryIds.value = favoritesRepository.getFavoriteHistoryIds()
    }

    private val _recipes = MutableStateFlow<List<Recipe>>(emptyList())
    val recipes: StateFlow<List<Recipe>> = _recipes.asStateFlow()

    /**
     * How many recipes matched in total, before the [MAX_RESULTS] cut. Lets the UI admit that the
     * list is a top-N slice instead of implying it is everything.
     */
    private val _totalMatchCount = MutableStateFlow(0)
    val totalMatchCount: StateFlow<Int> = _totalMatchCount.asStateFlow()

    private val _filterQuery = MutableStateFlow("")
    val filterQuery: StateFlow<String> = _filterQuery.asStateFlow()

    fun setFilterQuery(query: String) { _filterQuery.value = query }

    // --- State: difficulty / cook-time / exact-match filters ---

    // Empty = no difficulty filter active (everything passes) -- see matchesFilters' doc for why
    // a recipe with no difficulty value always passes an active filter too, rather than being
    // hidden for lack of data. Stores DIFFICULTY_BUCKETS labels, not raw 1-4 values, since a
    // bucket (e.g. "Everyday") can cover more than one raw value.
    private val _selectedDifficultyLabels = MutableStateFlow<Set<String>>(emptySet())
    val selectedDifficultyLabels: StateFlow<Set<String>> = _selectedDifficultyLabels.asStateFlow()

    fun toggleDifficultyFilter(label: String) {
        _selectedDifficultyLabels.update { current ->
            if (label in current) current - label else current + label
        }
    }

    // Null = no cook-time filter active.
    private val _maxCookMinutes = MutableStateFlow<Int?>(null)
    val maxCookMinutes: StateFlow<Int?> = _maxCookMinutes.asStateFlow()

    /** Pass the same value again to clear the filter -- RecipeScreen's time dropdown re-selecting
     * "Any time" (null) is how a user backs out of it. */
    fun setMaxCookTimeFilter(minutes: Int?) {
        _maxCookMinutes.value = if (_maxCookMinutes.value == minutes) null else minutes
    }

    // Off by default -- see matchesFilters' doc: unlike difficulty/time, this is a real filter,
    // not a soft exemption, so it stays opt-in rather than silently hiding category-expansion
    // matches (e.g. "chicken pieces" recipes for a "chicken breast" fridge item) by default.
    private val _exactMatchOnly = MutableStateFlow(false)
    val exactMatchOnly: StateFlow<Boolean> = _exactMatchOnly.asStateFlow()

    fun toggleExactMatchOnly() { _exactMatchOnly.update { !it } }

    // Plain vars, not StateFlow: only read once when RecipeScreen's LazyColumn is (re)created and
    // written once when it's torn down (navigating to detail/back), so no observers are needed.
    var scrollIndex: Int = 0
    var scrollOffset: Int = 0

    val sortedRecipes: StateFlow<List<Recipe>> = combine(_recipes, _favoriteIds) { recipes, favorites ->
        recipes
            .map { it.copy(isFavorite = it.id in favorites) }
            .sortedWith(recipeOrder)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val filteredRecipes: StateFlow<List<Recipe>> = combine(
        sortedRecipes, _filterQuery, _selectedDifficultyLabels, _maxCookMinutes, _exactMatchOnly
    ) { recipes, query, difficulties, maxMinutes, exactMatchOnly ->
        val q = query.trim().lowercase()
        recipes
            .filter { recipe ->
                q.isEmpty() ||
                recipe.title.lowercase().contains(q) ||
                recipe.categories.any { it.lowercase().contains(q) } ||
                recipe.ingredients.any { it.lowercase().contains(q) }
            }
            .filter { recipe -> matchesFilters(recipe, difficulties, maxMinutes, exactMatchOnly) }
            // Stable sort on top of the already-recipeOrder-sorted list: a recipe genuinely
            // rated within an active filter ranks above one that only passed via the unrated
            // exemption (see matchesKnownDifficulty/matchesKnownCookTime's doc) -- an unrated
            // recipe otherwise still floats to the top purely on match quality, which reads as
            // "the filter did nothing" once you've selected one. Recipes tied on both keys keep
            // their existing recipeOrder-derived order (stable sort), so this is a pure tiebreak,
            // not a new ranking axis.
            .sortedWith(
                compareByDescending<Recipe> { matchesKnownDifficulty(it, difficulties) }
                    .thenByDescending { matchesKnownCookTime(it, maxMinutes) }
            )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun toggleFavorite(recipeId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _favoriteIds.value
            if (recipeId in current) {
                favoritesRepository.removeFavorite(recipeId)
                _favoriteIds.value = current - recipeId
            } else {
                favoritesRepository.addFavorite(recipeId)
                _favoriteIds.value = current + recipeId
                _favoriteHistoryIds.value = _favoriteHistoryIds.value + recipeId
            }
        }
    }

    /**
     * The user's favorited recipes, loaded directly by id -- unlike [sortedRecipes], this never
     * runs fridge-relative match scoring at all, so it re-fetches only when [favoriteIds] itself
     * changes (StateFlow already only emits on an actual value change, so [mapLatest] here fires
     * exactly on add/remove, never on an unrelated recomposition or fridge search). A bookmark
     * list, not a filtered search result -- it shows the same favorites regardless of what's in
     * the fridge right now, including when the fridge is empty (where [searchRecipes] has nothing
     * to score against and returns nothing at all). matchedCount/totalCount are always 0 here:
     * real per-ingredient matching still happens in [loadRecipeDetail] once a favorite is opened.
     */
    val favoriteRecipes: StateFlow<List<Recipe>> = _favoriteIds
        .mapLatest { ids -> loadFavoriteRecipesById(ids) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * Recipes favorited at some point but not currently -- the same static, by-id loading as
     * [favoriteRecipes], just over the [_favoriteHistoryIds] minus [_favoriteIds] set instead.
     * Backs the "previously favorited" shelf on [com.pancakeworks.fridgegrub.ui.FavoritesScreen],
     * so removing a favorite (by mistake or otherwise) doesn't mean losing track of it entirely.
     */
    val favoriteHistoryRecipes: StateFlow<List<Recipe>> =
        combine(_favoriteIds, _favoriteHistoryIds) { current, history -> history - current }
            .mapLatest { ids -> loadFavoriteRecipesById(ids) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    private suspend fun loadFavoriteRecipesById(ids: Set<Int>): List<Recipe> {
        if (ids.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                loadFavoriteRecipesNew(ids)
            } catch (e: Exception) {
                Log.w(TAG, "Could not load favorite recipes", e)
                emptyList()
            }
        }
    }

    private suspend fun loadFavoriteRecipesNew(ids: Set<Int>): List<Recipe> {
        val dao = newRecipeDao()
        val currentFavorites = _favoriteIds.value
        val titleById = dao.getRecipesByIds(ids.toList()).associate { it.recipeId to it.title }
        return ids.mapNotNull { id ->
            val title = titleById[id] ?: return@mapNotNull null
            Recipe(
                id = id,
                title = title,
                servings = null,
                categories = emptyList(),
                ingredients = emptyList(),
                matchedCount = 0,
                totalCount = 0,
                isFavorite = id in currentFavorites
            )
        }.sortedBy { it.title }
    }

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Detail screen state
    private val _detailIngredients = MutableStateFlow<List<DetailIngredient>>(emptyList())
    val detailIngredients: StateFlow<List<DetailIngredient>> = _detailIngredients.asStateFlow()

    private val _detailDirections = MutableStateFlow<List<String>>(emptyList())
    val detailDirections: StateFlow<List<String>> = _detailDirections.asStateFlow()

    private val _isLoadingDetail = MutableStateFlow(false)
    val isLoadingDetail: StateFlow<Boolean> = _isLoadingDetail.asStateFlow()

    private var lastDetailRecipeId: Int? = null
    private var lastDetailFridgeIngredients: List<String>? = null

    /**
     * [fridgeIngredients] precomputes each line's category-aware match status — see
     * [DetailIngredient.matched].
     */
    fun loadRecipeDetail(recipeId: Int, fridgeIngredients: List<String> = emptyList()) {
        loadRecipeDetailNew(recipeId, fridgeIngredients)
    }

    private fun loadRecipeDetailNew(recipeId: Int, fridgeIngredients: List<String>) {
        val fridgeSet = fridgeIngredients.filter { it.isNotBlank() }.distinct()
        if (recipeId == lastDetailRecipeId && fridgeSet == lastDetailFridgeIngredients &&
            (_detailIngredients.value.isNotEmpty() || _detailDirections.value.isNotEmpty())
        ) {
            return
        }
        lastDetailRecipeId = recipeId
        lastDetailFridgeIngredients = fridgeSet

        _isLoadingDetail.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dao = newRecipeDao()

                // Same category-expanded matched set scoreRecipesNew used for this recipe's card
                // ratio, so a line credited there (even a category-only match with no shared
                // words, e.g. "ribeye" via fridge "beef") shows the same checkmark here.
                val matchedIds = if (fridgeSet.isEmpty()) {
                    emptySet()
                } else {
                    NewIngredientIndex.get(dao, BLOB_NAME_LENGTH_THRESHOLD_NEW).matching(fridgeSet)
                }

                _detailIngredients.value = dao.getIngredientLines(recipeId).map { row ->
                    DetailIngredient(
                        line = row.originalText,
                        canonical = row.normalizedName,
                        matched = row.ingredientId in matchedIds
                    )
                }

                _detailDirections.value = dao.getSteps(recipeId).flatMap { step ->
                    val instruction = step.instruction.trim().takeIf { it.isNotBlank() } ?: return@flatMap emptyList()
                    // recipe_steps is essentially unsplit in this corpus build -- 99.98% of
                    // recipes that have steps at all have exactly one row, with every real step
                    // concatenated into its instruction text separated by runs of blank lines
                    // (confirmed against the shipped db, not assumed). Splitting on those here
                    // recovers per-step granularity for the read-aloud step navigation without
                    // needing a corpus rebuild -- a single blank line within a genuine paragraph
                    // is rare enough in this data that requiring 2+ to split on is the safer cut.
                    instruction.split(Regex("\n{2,}"))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadRecipeDetailNew failed for recipeId=$recipeId", e)
                _detailIngredients.value = emptyList()
                _detailDirections.value = emptyList()
            } finally {
                _isLoadingDetail.value = false
            }
        }
    }

    private var lastFridgeIngredients: List<String>? = null
    private var lastPrioritizedIngredients: List<String>? = null
    private var lastPantryIngredients: List<String>? = null

    /** Recipes with a blob ingredient row in the new corpus; cached for the session. */
    private var blobRecipeIdsNew: Set<Int>? = null

    private fun newRecipeDao(): NewRecipeDao = NewRecipeDatabase.getInstance(getApplication()).newRecipeDao()

    /**
     * [pantryIngredients] (see [com.pancakeworks.fridgegrub.data.PantryRepository]) are merged
     * into the same matched-ingredient set as [fridgeIngredients] for scoring, but kept as a
     * separate list rather than pre-merged by the caller specifically so [scoreRecipesNew] can
     * tell which matches trace back to a *real* fridge item -- see [RecipeMatch.usesRealFridgeItem]
     * for why that distinction has to survive into ranking, not just matching.
     */
    fun searchRecipes(
        fridgeIngredients: List<String>,
        prioritizedIngredients: List<String> = emptyList(),
        pantryIngredients: List<String> = emptyList()
    ) {
        searchRecipesNew(fridgeIngredients, prioritizedIngredients, pantryIngredients)
    }

    private fun searchRecipesNew(
        fridgeIngredients: List<String>,
        prioritizedIngredients: List<String> = emptyList(),
        pantryIngredients: List<String> = emptyList()
    ) {
        // RecipeScreen re-runs this on every navigation back from the detail screen (it's the
        // same ViewModel instance the whole activity lifetime, since there's no back-stack
        // scoping). Skip the multi-second scan when the fridge/prioritized/pantry sets haven't
        // changed since last time and we already have a result to show; an empty last result
        // (including from a failed search) still retries, so a transient failure self-heals on
        // next visit.
        if (fridgeIngredients == lastFridgeIngredients &&
            prioritizedIngredients == lastPrioritizedIngredients &&
            pantryIngredients == lastPantryIngredients &&
            _recipes.value.isNotEmpty()
        ) {
            return
        }
        lastFridgeIngredients = fridgeIngredients
        lastPrioritizedIngredients = prioritizedIngredients
        lastPantryIngredients = pantryIngredients

        _filterQuery.value = ""
        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dao = newRecipeDao()
                val fridgeSet = fridgeIngredients.filter { it.isNotBlank() }.distinct()
                val prioritizedSet = prioritizedIngredients.filter { it.isNotBlank() }.distinct()
                val pantrySet = pantryIngredients.filter { it.isNotBlank() }.distinct()

                val results = run {
                    if (fridgeSet.isEmpty()) {
                        _totalMatchCount.value = 0
                        return@run emptyList<Recipe>()
                    }

                    // Resolve the fridge+pantry to the set of ingredient_ids they can supply. The
                    // IngredientMatcher head-word rule, plus category expansion for cross-head
                    // cases (fridge "beef" also reaching "ribeye"/"chuck") -- see
                    // NewIngredientIndex's class doc. Passed separately, not concatenated: pantry
                    // entries must not seed category expansion (see that doc's "Pantry entries"
                    // section) -- checking pantry "onion" should not silently also credit
                    // "scallions"/"leeks" the way a real fridge "onion" legitimately would.
                    val index = NewIngredientIndex.get(dao, BLOB_NAME_LENGTH_THRESHOLD_NEW)
                    val matchOrigins = index.matchOrigins(fridgeSet, pantrySet)
                    val matchedIds = matchOrigins.keys

                    // The origin keys (see NewIngredientIndex.matchOrigins' doc) that trace back to
                    // a real fridge term specifically, not a pantry one -- used below to tell
                    // whether a recipe's match is backed by anything actually in the fridge, or
                    // satisfied purely by pantry staples the user has checked off. A key present in
                    // both sets (e.g. "garlic" typed into the fridge AND checked as pantry) counts
                    // as real, which is correct: the user does have it, regardless of source.
                    val realFridgeOriginKeys = fridgeSet.mapNotNullTo(HashSet()) { name ->
                        IngredientMatcher.parseFridge(name).words.takeIf { it.isNotEmpty() }
                            ?.sorted()?.joinToString(" ")
                    }
                    if (matchedIds.isEmpty()) {
                        _totalMatchCount.value = 0
                        return@run emptyList<Recipe>()
                    }
                    // Intersected with the matched set on purpose: starring only ever boosts
                    // ingredients you actually have.
                    val prioritizedIds =
                        if (prioritizedSet.isEmpty()) emptySet()
                        else index.matching(prioritizedSet) intersect matchedIds

                    val scored = scoreRecipesNew(dao, matchedIds, prioritizedIds, matchOrigins, realFridgeOriginKeys)
                    if (scored.isEmpty()) {
                        _totalMatchCount.value = 0
                        return@run emptyList<Recipe>()
                    }

                    val favoriteIds = _favoriteIds.value
                    // Drop matches satisfied purely by checked pantry items, not anything really
                    // in the fridge -- a handful of common pantry staples (garlic, onion, butter,
                    // olive oil) are common enough as Supportive/Defining ingredients that leaving
                    // this in would qualify ~80% of the whole corpus regardless of what's actually
                    // in the fridge (measured directly against the corpus, not estimated). A
                    // favorited recipe is exempt, same as the MAX_RESULTS cut below -- a saved
                    // favorite should never silently vanish from search just because the fridge
                    // changed.
                    val relevant = scored.filter { it.usesRealFridgeItem || it.id in favoriteIds }
                    if (relevant.isEmpty()) {
                        _totalMatchCount.value = 0
                        return@run emptyList<Recipe>()
                    }

                    _totalMatchCount.value = relevant.size

                    // Two-stage ranking: matchOrder cuts to MAX_RESULTS here, recipeOrder re-sorts
                    // once favorites are known (see recipeOrder/matchOrder's doc comment).
                    val ranked = relevant.sortedWith(matchOrder)
                    val topMatches = (ranked.take(MAX_RESULTS) + ranked.filter { it.id in favoriteIds })
                        .distinctBy { it.id }

                    hydrateNew(dao, topMatches)
                }

                _recipes.value = results
            } catch (e: Exception) {
                Log.e(TAG, "searchRecipesNew failed", e)
                _recipes.value = emptyList()
                _totalMatchCount.value = 0
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Scores every recipe in one pass over `recipe_ingredients`. `SEASONING`-tier rows used to be
     * excluded from both the `total` and `matched` counts -- "a recipe needing salt isn't
     * penalized for a fridge without salt, and doesn't get credit for one with it either" -- back
     * when there was no way to know whether a fridge had salt at all short of literally typing it
     * in. That's no longer true once pantry items exist (see
     * `data/PantryRepository.kt`): seasoning availability is now a real, user-confirmed signal,
     * not noise, so `total`/`matched` count every tier the same way. `DEFINING`-tier matches (and
     * totals -- `definingTotal`) are separately counted on top of that so [recipeOrder]/
     * [matchOrder] can give them their own ranking boost as a *proportion* covered, not a raw
     * count (see `RecipeRanking.kt`'s doc for why the proportion matters), the same way starred
     * ingredients already do. `SEASONING`-tier totals are
     * *also* tracked separately (`seasoningTotal`/`seasoningMatchedIds`) purely to power
     * [Recipe.unmatchedSeasoningCount]'s small card indicator -- calling out that the only gap is
     * a seasoning is still useful even though it now counts against the ratio like anything else.
     *
     * [matchOrigins] maps each matched ingredient_id to the fridge entry that satisfied it (see
     * [com.pancakeworks.fridgegrub.data.NewIngredientIndex.matchOrigins]) -- the raw matched-id
     * set is collapsed by that origin before counting, so a recipe calling for several kinds of
     * cheese only gets credit for as many as the fridge can actually distinguish (one, for a
     * generic "cheese" entry; more, if the fridge lists them by specific name).
     *
     * [realFridgeOriginKeys] is the subset of those origins that trace back to a real fridge item
     * rather than a checked pantry one -- used to compute [RecipeMatch.usesRealFridgeItem], which
     * keeps a recipe satisfied purely by pantry staples (e.g. "Garlic Salt" when the fridge has
     * only "egg") from ranking alongside or above one that actually uses what's in the fridge.
     *
     * [MatchOrigin.direct] (also carried in [matchOrigins]) similarly feeds
     * [RecipeMatch.usesDirectMatch]: whether at least one matched ingredient traces back to a
     * *real fridge item* (not a pantry one -- same [realFridgeOriginKeys] gate as
     * [RecipeMatch.usesRealFridgeItem]) that was reached by direct word-matching rather than only
     * via category expansion -- e.g. fridge "chicken breast" directly matching recipe "chicken
     * breast" vs. only reaching recipe "chicken drumsticks"/"chicken wings" through the shared
     * Meat/Chicken category. The [realFridgeOriginKeys] gate matters here specifically: without
     * it, a recipe whose *only* fridge-relevant ingredient is category-matched (e.g. "chicken
     * breast" -> "chicken wings") still counted as a direct match whenever some unrelated pantry
     * staple (garlic, salt, oil) it also calls for happened to match directly -- passing "Exact
     * match only" on the strength of a pantry item having nothing to do with what the filter is
     * meant to check. Both directness and reality are valid, real matches on their own; this just
     * distinguishes "the exact thing you have" from "something the taxonomy says is a reasonable
     * substitute", for
     * `RecipeRanking.kt`'s tiebreak and `RecipeScreen`'s "Exact match only" filter.
     */
    private suspend fun scoreRecipesNew(
        dao: NewRecipeDao,
        matchedIds: Set<Int>,
        prioritizedIds: Set<Int>,
        matchOrigins: Map<Int, NewIngredientIndex.MatchOrigin>,
        realFridgeOriginKeys: Set<String>
    ): List<RecipeMatch> {
        val junkIds = if (SUPPRESS_BLOB_RECIPES_NEW) {
            blobRecipeIdsNew ?: dao.getBlobRecipeIds(BLOB_NAME_LENGTH_THRESHOLD_NEW).toSet().also {
                blobRecipeIdsNew = it
            }
        } else {
            emptySet()
        }

        // Chunks partition the matched set; `total`/`seasoningTotal` are NOT chunk-dependent
        // (every ingredient the recipe calls for, computed the same regardless of which chunk is
        // running), so they're taken once per recipe and never summed across chunks. The
        // matched/defining/seasoning-matched ingredient_ids themselves accumulate as sets (a
        // chunk only ever sees its own slice), and the deduped counts are derived from those sets
        // once all chunks are in; prioritized stays a simple per-chunk sum.
        data class Accumulator(
            var total: Int = 0,
            val matchedIds: MutableSet<Int> = mutableSetOf(),
            val definingIds: MutableSet<Int> = mutableSetOf(),
            var definingTotal: Int = 0,
            var seasoningTotal: Int = 0,
            val seasoningMatchedIds: MutableSet<Int> = mutableSetOf(),
            var prioritized: Int = 0
        )
        fun originsOf(ids: Set<Int>) = ids.mapTo(HashSet()) { matchOrigins[it]?.fridgeKey ?: it.toString() }

        val accumulated = HashMap<Int, Accumulator>()
        for (chunk in chunkIntLiterals(matchedIds)) {
            val prioritizedChunk = chunk.filter { it in prioritizedIds }
            val rows = dao.scoreChunk(SimpleSQLiteQuery(buildScoreQuerySql(chunk, prioritizedChunk)))
            for (row in rows) {
                if (row.recipeId in junkIds) continue
                val acc = accumulated.getOrPut(row.recipeId) { Accumulator() }
                acc.total = row.total
                acc.definingTotal = row.definingTotal
                acc.seasoningTotal = row.seasoningTotal
                row.matchedIds?.splitToSequence(',')?.forEach { acc.matchedIds.add(it.toInt()) }
                row.definingIds?.splitToSequence(',')?.forEach { acc.definingIds.add(it.toInt()) }
                row.seasoningMatchedIds?.splitToSequence(',')?.forEach { acc.seasoningMatchedIds.add(it.toInt()) }
                acc.prioritized += row.prioritized
            }
        }
        return accumulated.map { (recipeId, acc) ->
            RecipeMatch(
                id = recipeId,
                matched = originsOf(acc.matchedIds).size,
                total = acc.total,
                prioritized = acc.prioritized,
                defining = originsOf(acc.definingIds).size,
                definingTotal = acc.definingTotal,
                usesRealFridgeItem = acc.matchedIds.any { matchOrigins[it]?.fridgeKey in realFridgeOriginKeys },
                usesDirectMatch = acc.matchedIds.any {
                    matchOrigins[it]?.direct == true && matchOrigins[it]?.fridgeKey in realFridgeOriginKeys
                },
                unmatchedSeasoningCount = (acc.seasoningTotal - originsOf(acc.seasoningMatchedIds).size).coerceAtLeast(0)
            )
        }
    }

    private fun buildScoreQuerySql(matchedChunk: List<Int>, prioritizedChunk: List<Int>): String = buildString {
        append("SELECT recipe_id, ")
        // Every tier counts toward total/matched now -- see scoreRecipesNew's doc for why the
        // old SEASONING exclusion no longer applies now that pantry gives real seasoning-
        // availability signal instead of the noise it used to be.
        append("COUNT(DISTINCT ingredient_id) AS total, ")
        // The actual matched ingredient_ids, not just a count -- scoreRecipesNew collapses ids
        // that trace back to the same fridge entry (see NewIngredientIndex.matchOrigins) before
        // counting, so it needs the list, not an aggregate.
        append("GROUP_CONCAT(DISTINCT CASE WHEN ingredient_id IN (")
        append(matchedChunk.joinToString(","))
        append(") THEN ingredient_id END) AS matched_ids, ")
        // Defining-tier ingredients the fridge covers -- a subset of `matched`, used purely for
        // the ranking boost in recipeOrder/matchOrder, not for the total/matched counts
        // themselves. Also returned as ids, not a count, so it gets the same fridge-origin dedup
        // as matched_ids. defining_total (not chunk-dependent, same rationale as total) is the
        // denominator that boost is a *proportion* of -- see RecipeRanking.kt's doc for why a raw
        // count would unfairly favor a recipe that tags several ingredients DEFINING.
        append("GROUP_CONCAT(DISTINCT CASE WHEN tier = 'DEFINING' AND ingredient_id IN (")
        append(matchedChunk.joinToString(","))
        append(") THEN ingredient_id END) AS defining_ids, ")
        append("COUNT(DISTINCT CASE WHEN tier = 'DEFINING' THEN ingredient_id END) AS defining_total, ")
        // How many SEASONING-tier ingredients this recipe calls for, and which of those this
        // chunk covers -- feeds Recipe.unmatchedSeasoningCount (see NewRecipeMatchRow's doc), a
        // small card indicator distinct from total/matched now that seasoning is folded in there.
        append("COUNT(DISTINCT CASE WHEN tier = 'SEASONING' THEN ingredient_id END) AS seasoning_total, ")
        append("GROUP_CONCAT(DISTINCT CASE WHEN tier = 'SEASONING' AND ingredient_id IN (")
        append(matchedChunk.joinToString(","))
        append(") THEN ingredient_id END) AS seasoning_matched_ids, ")
        if (prioritizedChunk.isNotEmpty()) {
            append("COUNT(DISTINCT CASE WHEN ingredient_id IN (")
            append(prioritizedChunk.joinToString(","))
            append(") THEN ingredient_id END) AS prioritized")
        } else {
            append("0 AS prioritized")
        }
        append(" FROM recipe_ingredients GROUP BY recipe_id HAVING matched_ids IS NOT NULL")
    }

    /** Loads recipe rows and ingredient lists for the recipes that will actually be shown.
     * `categories` is always empty -- this corpus build has no data in `category`/`cuisine`
     * (confirmed, not assumed; see `porting-reference/NEW_CORPUS_DATA_QUALITY.md`), unlike
     * `servings`/`difficulty`/`total_minutes_min/max`, which this corpus does populate (partially
     * -- see `RecipeEntity`'s doc for exact rates) and which do get carried through here. */
    private suspend fun hydrateNew(dao: NewRecipeDao, topMatches: List<RecipeMatch>): List<Recipe> {
        if (topMatches.isEmpty()) return emptyList()
        val recipeIds = topMatches.map { it.id }

        val recipeById = dao.getRecipesByIds(recipeIds).associateBy { it.recipeId }

        val ingredientTextById = mutableMapOf<Int, MutableList<String>>()
        for (row in dao.getIngredientTextForRecipes(recipeIds)) {
            ingredientTextById.getOrPut(row.recipeId) { mutableListOf() }.add(row.originalText.lowercase())
        }

        // sortedRecipes re-sorts with the same ordering once favorites are known.
        return topMatches.mapNotNull { match ->
            val entity = recipeById[match.id] ?: return@mapNotNull null
            Recipe(
                id = match.id,
                title = entity.title,
                servings = parseServings(entity.servings),
                categories = emptyList(),
                ingredients = ingredientTextById[match.id] ?: emptyList(),
                matchedCount = match.matched,
                totalCount = match.total,
                prioritizedCount = match.prioritized,
                definingMatchedCount = match.defining,
                definingTotalCount = match.definingTotal,
                usesRealFridgeItem = match.usesRealFridgeItem,
                usesDirectMatch = match.usesDirectMatch,
                unmatchedSeasoningCount = match.unmatchedSeasoningCount,
                difficulty = entity.difficulty,
                timeText = entity.timeText,
                cookMinutesMin = entity.totalMinutesMin,
                cookMinutesMax = entity.totalMinutesMax
            )
        }
    }

}
