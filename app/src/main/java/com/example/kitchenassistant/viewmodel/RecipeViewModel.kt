package com.example.kitchenassistant.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.kitchenassistant.data.FavoritesRepository
import com.example.kitchenassistant.data.NewIngredientIndex
import com.example.kitchenassistant.data.NewRecipeDao
import com.example.kitchenassistant.data.NewRecipeDatabase
import com.example.kitchenassistant.model.DetailIngredient
import com.example.kitchenassistant.model.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
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

    val filteredRecipes: StateFlow<List<Recipe>> = combine(sortedRecipes, _filterQuery) { recipes, query ->
        val q = query.trim().lowercase()
        if (q.isEmpty()) recipes
        else recipes.filter { recipe ->
            recipe.title.lowercase().contains(q) ||
            recipe.categories.any { it.lowercase().contains(q) } ||
            recipe.ingredients.any { it.lowercase().contains(q) }
        }
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
     * Backs the "previously favorited" shelf on [com.example.kitchenassistant.ui.FavoritesScreen],
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

    /** Recipes with a blob ingredient row in the new corpus; cached for the session. */
    private var blobRecipeIdsNew: Set<Int>? = null

    private fun newRecipeDao(): NewRecipeDao = NewRecipeDatabase.getInstance(getApplication()).newRecipeDao()

    fun searchRecipes(fridgeIngredients: List<String>, prioritizedIngredients: List<String> = emptyList()) {
        searchRecipesNew(fridgeIngredients, prioritizedIngredients)
    }

    private fun searchRecipesNew(fridgeIngredients: List<String>, prioritizedIngredients: List<String> = emptyList()) {
        // RecipeScreen re-runs this on every navigation back from the detail screen (it's the
        // same ViewModel instance the whole activity lifetime, since there's no back-stack
        // scoping). Skip the multi-second scan when the fridge/prioritized sets haven't changed
        // since last time and we already have a result to show; an empty last result (including
        // from a failed search) still retries, so a transient failure self-heals on next visit.
        if (fridgeIngredients == lastFridgeIngredients &&
            prioritizedIngredients == lastPrioritizedIngredients &&
            _recipes.value.isNotEmpty()
        ) {
            return
        }
        lastFridgeIngredients = fridgeIngredients
        lastPrioritizedIngredients = prioritizedIngredients

        _filterQuery.value = ""
        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dao = newRecipeDao()
                val fridgeSet = fridgeIngredients.filter { it.isNotBlank() }.distinct()
                val prioritizedSet = prioritizedIngredients.filter { it.isNotBlank() }.distinct()

                val results = run {
                    if (fridgeSet.isEmpty()) {
                        _totalMatchCount.value = 0
                        return@run emptyList<Recipe>()
                    }

                    // Resolve the fridge to the set of ingredient_ids it can supply. The
                    // IngredientMatcher head-word rule, plus category expansion for cross-head
                    // cases (fridge "beef" also reaching "ribeye"/"chuck") -- see
                    // NewIngredientIndex's class doc.
                    val index = NewIngredientIndex.get(dao, BLOB_NAME_LENGTH_THRESHOLD_NEW)
                    val matchedIds = index.matching(fridgeSet)
                    if (matchedIds.isEmpty()) {
                        _totalMatchCount.value = 0
                        return@run emptyList<Recipe>()
                    }
                    // Intersected with the matched set on purpose: starring only ever boosts
                    // ingredients you actually have.
                    val prioritizedIds =
                        if (prioritizedSet.isEmpty()) emptySet()
                        else index.matching(prioritizedSet) intersect matchedIds

                    val scored = scoreRecipesNew(dao, matchedIds, prioritizedIds)
                    if (scored.isEmpty()) {
                        _totalMatchCount.value = 0
                        return@run emptyList<Recipe>()
                    }

                    _totalMatchCount.value = scored.size

                    // Two-stage ranking: matchOrder cuts to MAX_RESULTS here, recipeOrder re-sorts
                    // once favorites are known (see recipeOrder/matchOrder's doc comment).
                    val ranked = scored.sortedWith(matchOrder)
                    val favoriteIds = _favoriteIds.value
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
     * Scores every recipe in one pass over `recipe_ingredients`. `SEASONING`-tier rows are
     * excluded from both the `total` and `matched` counts inside [buildScoreQuerySql] -- the
     * "incorporate tiers into scoring" step discussed when this was designed: a recipe needing
     * salt isn't penalized for a fridge without salt, and doesn't get credit for one with it
     * either. `DEFINING`-tier matches are separately counted (not excluded from anything) so
     * [recipeOrder]/[matchOrder] can give them their own ranking boost, the same way starred
     * ingredients already do.
     */
    private suspend fun scoreRecipesNew(
        dao: NewRecipeDao,
        matchedIds: Set<Int>,
        prioritizedIds: Set<Int>
    ): List<RecipeMatch> {
        val junkIds = if (SUPPRESS_BLOB_RECIPES_NEW) {
            blobRecipeIdsNew ?: dao.getBlobRecipeIds(BLOB_NAME_LENGTH_THRESHOLD_NEW).toSet().also {
                blobRecipeIdsNew = it
            }
        } else {
            emptySet()
        }

        // Chunks partition the matched set; `total` is NOT chunk-dependent (it's every
        // non-SEASONING ingredient the recipe calls for, computed the same regardless of which
        // chunk is running), so it's taken once per recipe and never summed across chunks — only
        // matched/prioritized/defining accumulate.
        val accumulated = HashMap<Int, RecipeMatch>()
        for (chunk in chunkIntLiterals(matchedIds)) {
            val prioritizedChunk = chunk.filter { it in prioritizedIds }
            val rows = dao.scoreChunk(SimpleSQLiteQuery(buildScoreQuerySql(chunk, prioritizedChunk)))
            for (row in rows) {
                if (row.recipeId in junkIds) continue
                val existing = accumulated[row.recipeId]
                accumulated[row.recipeId] = if (existing == null) {
                    RecipeMatch(
                        id = row.recipeId,
                        matched = row.matched,
                        total = row.total,
                        prioritized = row.prioritized,
                        defining = row.defining
                    )
                } else {
                    existing.copy(
                        matched = existing.matched + row.matched,
                        prioritized = existing.prioritized + row.prioritized,
                        defining = existing.defining + row.defining
                    )
                }
            }
        }
        return accumulated.values.toList()
    }

    private fun buildScoreQuerySql(matchedChunk: List<Int>, prioritizedChunk: List<Int>): String = buildString {
        append("SELECT recipe_id, ")
        append("COUNT(DISTINCT CASE WHEN tier != 'SEASONING' THEN ingredient_id END) AS total, ")
        append("COUNT(DISTINCT CASE WHEN tier != 'SEASONING' AND ingredient_id IN (")
        append(matchedChunk.joinToString(","))
        append(") THEN ingredient_id END) AS matched, ")
        // Defining-tier ingredients the fridge covers -- a subset of `matched` (DEFINING is never
        // SEASONING, so no tier != 'SEASONING' guard is needed here), used purely for the ranking
        // boost in recipeOrder/matchOrder, not for the total/matched counts themselves.
        append("COUNT(DISTINCT CASE WHEN tier = 'DEFINING' AND ingredient_id IN (")
        append(matchedChunk.joinToString(","))
        append(") THEN ingredient_id END) AS defining, ")
        if (prioritizedChunk.isNotEmpty()) {
            append("COUNT(DISTINCT CASE WHEN tier != 'SEASONING' AND ingredient_id IN (")
            append(prioritizedChunk.joinToString(","))
            append(") THEN ingredient_id END) AS prioritized")
        } else {
            append("0 AS prioritized")
        }
        append(" FROM recipe_ingredients GROUP BY recipe_id HAVING matched > 0")
    }

    /** Loads titles and ingredient lists for the recipes that will actually be shown.
     * `servings`/`categories` are always empty -- this corpus build has no data in those columns
     * for any recipe (confirmed, not assumed; see `porting-reference/NEW_CORPUS_DATA_QUALITY.md`). */
    private suspend fun hydrateNew(dao: NewRecipeDao, topMatches: List<RecipeMatch>): List<Recipe> {
        if (topMatches.isEmpty()) return emptyList()
        val recipeIds = topMatches.map { it.id }

        val titleById = dao.getRecipesByIds(recipeIds).associate { it.recipeId to it.title }

        val ingredientTextById = mutableMapOf<Int, MutableList<String>>()
        for (row in dao.getIngredientTextForRecipes(recipeIds)) {
            ingredientTextById.getOrPut(row.recipeId) { mutableListOf() }.add(row.originalText.lowercase())
        }

        // sortedRecipes re-sorts with the same ordering once favorites are known.
        return topMatches.mapNotNull { match ->
            val title = titleById[match.id] ?: return@mapNotNull null
            Recipe(
                id = match.id,
                title = title,
                servings = null,
                categories = emptyList(),
                ingredients = ingredientTextById[match.id] ?: emptyList(),
                matchedCount = match.matched,
                totalCount = match.total,
                prioritizedCount = match.prioritized,
                definingMatchedCount = match.defining
            )
        }
    }

}
