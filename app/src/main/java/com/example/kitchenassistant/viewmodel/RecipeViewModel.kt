package com.example.kitchenassistant.viewmodel

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.kitchenassistant.data.BundledDatabase
import com.example.kitchenassistant.data.CanonicalIndex
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

        // recipes.db holds 300k+ recipes; a common fridge ingredient (e.g. "egg") can match tens of
        // thousands of them, and nobody scrolls past the first few hundred best matches anyway, so
        // only the top-ranked ones get their full details fetched. Hydrating 500 measured at
        // 0.04s / 0.04s / 0.10s for the three detail queries, so this is cheap to raise further if
        // the starred-ingredient boost ever needs more room.
        private const val MAX_RESULTS = 500

        // Matched canonicals go into the scoring query as inline literals rather than bound
        // parameters — SQLite's host-parameter limit is 999 and a full fridge matches ~9,000 of
        // them. SQLITE_MAX_SQL_LENGTH is 1,000,000 on Android; the prioritized list is a subset of
        // the matched one, so budgeting the matched literal at 350k keeps the whole statement
        // comfortably under half the limit. Fridges large enough to exceed it fall back to
        // scoring in chunks (see chunkByLiteralSize).
        private const val MAX_LITERAL_CHARS = 350_000

        // --- Underparsed-recipe mitigation switch ---
        //
        // A known bug in the current corpus-generation pipeline (outside this repo) sometimes
        // collapses several ingredients into one raw `ingredients` line with no separator the
        // parser recognizes (e.g. "Whole pork tenderloin Onions, sliced Tomatoes, sliced Bacon
        // strips Seasonings" parsed down to just "pork tenderloin onion" — tomato, bacon, and
        // seasoning silently dropped from matching). Those recipes then look trivially complete
        // to anyone with just the one surviving ingredient and rank as false 100% matches.
        //
        // SUPPRESS_UNDERPARSED_RECIPES filters them out via loadUnderparsedRecipeIds below, the
        // same way loadUnparseableRecipeIds already filters `parse_ok = 0` recipes. It's a
        // reversible, app-side mitigation, not a real fix — the lost ingredients aren't
        // recovered, just hidden from ranking. Flip this to false once the upstream corpus is
        // regenerated with the line-collapsing bug fixed; a cleanly parsed corpus won't trip this
        // heuristic anyway, so leaving it on is harmless, but the whole mitigation (this flag,
        // loadUnderparsedRecipeIds, and its call site in scoreRecipes) can be deleted once it's
        // confirmed unnecessary.
        const val SUPPRESS_UNDERPARSED_RECIPES = true

        // --- New-corpus switch ---
        //
        // recipe_database.sqlite (odunola/foodie, 19,566 recipes, ~95MB) is a second, smaller
        // recipe database bundled alongside the original recipes.db (333k recipes, ~620MB) —
        // see porting-reference/ANDROID_HANDOFF.md and INGREDIENT_MATCHING_CONCEPTS.md for the
        // full background. USE_NEW_RECIPE_DATABASE selects which one backs search and detail.
        //
        // The old-corpus code path (scoreRecipes, hydrate, loadUnparseableRecipeIds,
        // loadUnderparsedRecipeIds, openRecipesDatabase, CanonicalIndex, and everything gated by
        // SUPPRESS_UNDERPARSED_RECIPES above) is untouched and fully reachable by flipping this
        // back to false — searchRecipes/loadRecipeDetail just dispatch to *Legacy or *New based
        // on this flag, nothing about the old path was rewritten to make room for the new one.
        const val USE_NEW_RECIPE_DATABASE = true

        // The new corpus's own known data-quality issue (see
        // porting-reference/NEW_CORPUS_DATA_QUALITY.md): ~2.7% of recipe_ingredients rows point
        // at a "blob" ingredient name — unparsed raw text, not a clean ingredient. Different bug,
        // different code than SUPPRESS_UNDERPARSED_RECIPES's target, so it gets its own switch
        // rather than reusing that one. Suppresses affected recipes from ranking; recovering the
        // underlying blob names themselves is deferred (see porting-reference/head_categories.json
        // and the taxonomy-construction docs for where that work picks back up).
        const val SUPPRESS_BLOB_RECIPES_NEW = true

        // Mirrors the Python BLOB_NAME_LENGTH_THRESHOLD in
        // porting-reference/extract_ingredient_heads.py — a normalized_name longer than this is
        // treated as un-stripped raw text rather than a real ingredient name. Kept as the one
        // source of truth here and threaded into NewRecipeDao/NewIngredientIndex calls, rather
        // than hardcoded in multiple places.
        const val BLOB_NAME_LENGTH_THRESHOLD_NEW = 40

        /**
         * Renders [values] as a SQL string literal list. Values come from the database itself, but
         * the quote doubling still matters — canonicals like "confectioner's sugar" exist.
         */
        private fun sqlLiteralList(values: Collection<String>): String =
            values.joinToString(",") { "'" + it.replace("'", "''") + "'" }

        /**
         * Splits [values] into groups whose rendered literal stays under [MAX_LITERAL_CHARS].
         * Returns a single group for any realistic fridge.
         */
        private fun chunkByLiteralSize(values: Collection<String>): List<List<String>> {
            val chunks = mutableListOf<List<String>>()
            var current = mutableListOf<String>()
            var length = 0
            for (value in values) {
                val cost = value.length + 3 // quotes and separator
                if (current.isNotEmpty() && length + cost > MAX_LITERAL_CHARS) {
                    chunks.add(current)
                    current = mutableListOf()
                    length = 0
                }
                current.add(value)
                length += cost
            }
            if (current.isNotEmpty()) chunks.add(current)
            return chunks
        }

        /** [chunkByLiteralSize]'s counterpart for integer ids (no quoting needed) — used by the
         * new-corpus scoring path, where the matched set is ingredient_ids, not canonical names. */
        private fun chunkIntLiterals(values: Collection<Int>): List<List<Int>> {
            val chunks = mutableListOf<List<Int>>()
            var current = mutableListOf<Int>()
            var length = 0
            for (value in values) {
                val cost = value.toString().length + 1 // separator
                if (current.isNotEmpty() && length + cost > MAX_LITERAL_CHARS) {
                    chunks.add(current)
                    current = mutableListOf()
                    length = 0
                }
                current.add(value)
                length += cost
            }
            if (current.isNotEmpty()) chunks.add(current)
            return chunks
        }
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
                if (USE_NEW_RECIPE_DATABASE) loadFavoriteRecipesNew(ids) else loadFavoriteRecipesLegacy(ids)
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

    private fun loadFavoriteRecipesLegacy(ids: Set<Int>): List<Recipe> {
        val database = openRecipesDatabase()
        val currentFavorites = _favoriteIds.value
        val idList = ids.joinToString(",")
        val titleById = mutableMapOf<Int, String>()
        database.rawQuery("SELECT id, title FROM recipes WHERE id IN ($idList)", null).use { cursor ->
            while (cursor.moveToNext()) {
                titleById[cursor.getInt(0)] = cursor.getString(1) ?: ""
            }
        }
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
     * [fridgeIngredients] is only consulted by the new-corpus path, to precompute each line's
     * category-aware match status — see [DetailIngredient.matched]. The legacy path ignores it
     * and keeps computing checkmarks live in [com.example.kitchenassistant.ui.RecipeDetailScreen],
     * exactly as before.
     */
    fun loadRecipeDetail(recipeId: Int, fridgeIngredients: List<String> = emptyList()) {
        if (USE_NEW_RECIPE_DATABASE) {
            loadRecipeDetailNew(recipeId, fridgeIngredients)
        } else {
            loadRecipeDetailLegacy(recipeId)
        }
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

                _detailDirections.value = dao.getSteps(recipeId).mapNotNull { step ->
                    val instruction = step.instruction.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val title = step.stepTitle?.trim()
                    if (title.isNullOrEmpty()) instruction else "$title: $instruction"
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

    private fun loadRecipeDetailLegacy(recipeId: Int) {
        // Same reasoning as searchRecipes: RecipeDetailScreen re-mounts (and re-fires its
        // LaunchedEffect) every time it's navigated to, including re-opening a recipe you already
        // viewed this session. Skip the reload if we already have this recipe's details cached.
        if (recipeId == lastDetailRecipeId &&
            (_detailIngredients.value.isNotEmpty() || _detailDirections.value.isNotEmpty())
        ) {
            return
        }
        lastDetailRecipeId = recipeId

        _isLoadingDetail.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                openRecipesDatabase().use { database ->
                    val args = arrayOf(recipeId.toString())

                    // The normalized name behind each raw ingredient line, so the detail screen's
                    // checkmarks are computed from the same string the search scored against.
                    // Joined in Kotlin rather than SQL on purpose: clean_ingredients has no index
                    // on ingredient_id, and a SQL join on it scans 3.1M rows.
                    val canonicalByIngredientId = mutableMapOf<Int, String>()
                    database.rawQuery(
                        "SELECT ingredient_id, canonical FROM clean_ingredients WHERE recipe_id = ?",
                        args
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val ingredientId = cursor.getInt(0)
                            val canonical = cursor.getString(1) ?: continue
                            canonicalByIngredientId[ingredientId] = canonical
                        }
                    }

                    // Full ingredient lines with quantity and unit
                    val ingredients = mutableListOf<DetailIngredient>()
                    database.rawQuery(
                        "SELECT id, quantity, unit, text FROM ingredients WHERE recipe_id = ? AND is_heading = 0 ORDER BY id",
                        args
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val ingredientId = cursor.getInt(0)
                            val qty = cursor.getString(1)?.trim()
                            val unit = cursor.getString(2)?.trim()
                            val text = cursor.getString(3)?.trim() ?: continue
                            val line = listOfNotNull(qty?.ifEmpty { null }, unit?.ifEmpty { null }, text)
                                .joinToString(" ")
                            ingredients.add(
                                DetailIngredient(line = line, canonical = canonicalByIngredientId[ingredientId])
                            )
                        }
                    }
                    _detailIngredients.value = ingredients

                    // Step-by-step directions
                    val directions = mutableListOf<String>()
                    database.rawQuery(
                        "SELECT text FROM directions WHERE recipe_id = ? ORDER BY step_number",
                        args
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { directions.add(it) }
                        }
                    }
                    _detailDirections.value = directions
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadRecipeDetail failed for recipeId=$recipeId", e)
                _detailIngredients.value = emptyList()
                _detailDirections.value = emptyList()
            } finally {
                _isLoadingDetail.value = false
            }
        }
    }

    private var lastFridgeIngredients: List<String>? = null
    private var lastPrioritizedIngredients: List<String>? = null

    /** Recipes whose ingredient list the corpus failed to parse; cached for the session. */
    private var unparseableRecipeIds: Set<Int>? = null

    /** Recipes flagged by [loadUnderparsedRecipeIds]; cached for the session. */
    private var underparsedRecipeIds: Set<Int>? = null

    /** Recipes with a blob ingredient row in the new corpus; cached for the session. Counterpart
     * to [unparseableRecipeIds]/[underparsedRecipeIds] above, for [SUPPRESS_BLOB_RECIPES_NEW]. */
    private var blobRecipeIdsNew: Set<Int>? = null

    private fun newRecipeDao(): NewRecipeDao = NewRecipeDatabase.getInstance(getApplication()).newRecipeDao()

    fun searchRecipes(fridgeIngredients: List<String>, prioritizedIngredients: List<String> = emptyList()) {
        if (USE_NEW_RECIPE_DATABASE) {
            searchRecipesNew(fridgeIngredients, prioritizedIngredients)
        } else {
            searchRecipesLegacy(fridgeIngredients, prioritizedIngredients)
        }
    }

    private fun searchRecipesNew(fridgeIngredients: List<String>, prioritizedIngredients: List<String> = emptyList()) {
        // Same re-run guard as searchRecipesLegacy -- see its comment for why.
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

                    // Resolve the fridge to the set of ingredient_ids it can supply. Same
                    // IngredientMatcher rule as the old corpus, plus category expansion for
                    // cross-head cases (fridge "beef" also reaching "ribeye"/"chuck") -- see
                    // NewIngredientIndex's class doc.
                    val index = NewIngredientIndex.get(dao, BLOB_NAME_LENGTH_THRESHOLD_NEW)
                    val matchedIds = index.matching(fridgeSet)
                    if (matchedIds.isEmpty()) {
                        _totalMatchCount.value = 0
                        return@run emptyList<Recipe>()
                    }
                    // Intersected with the matched set for the same reason as the old corpus:
                    // starring only ever boosts ingredients you actually have.
                    val prioritizedIds =
                        if (prioritizedSet.isEmpty()) emptySet()
                        else index.matching(prioritizedSet) intersect matchedIds

                    val scored = scoreRecipesNew(dao, matchedIds, prioritizedIds)
                    if (scored.isEmpty()) {
                        _totalMatchCount.value = 0
                        return@run emptyList<Recipe>()
                    }

                    _totalMatchCount.value = scored.size

                    // Same two-stage ranking as the old corpus (matchOrder, then recipeOrder once
                    // favorites are known) -- both are already source-agnostic, no changes needed.
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
     * New-corpus counterpart to [scoreRecipes]. `SEASONING`-tier rows are excluded from both the
     * `total` and `matched` counts inside [buildScoreQuerySql] -- the "incorporate tiers into
     * scoring" step discussed when this was designed: a recipe needing salt isn't penalized for a
     * fridge without salt, and doesn't get credit for one with it either. `DEFINING`-tier matches
     * are separately counted (not excluded from anything) so [recipeOrder]/[matchOrder] can give
     * them their own ranking boost, the same way starred ingredients already do. The resulting
     * [RecipeMatch] values feed the exact same [matchOrder]/[recipeOrder]/[ratioScore]/[matchTier]
     * the old corpus uses -- nothing else downstream needed to change to make tiers count.
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
        // matched/prioritized/defining accumulate. Mirrors queryChunk/scoreRecipes' exact reasoning.
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

    /** New-corpus counterpart to [hydrate]. `servings`/`categories` are always empty -- this
     * corpus build has no data in those columns for any recipe (confirmed, not assumed; see
     * `porting-reference/NEW_CORPUS_DATA_QUALITY.md`). */
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

    private fun searchRecipesLegacy(fridgeIngredients: List<String>, prioritizedIngredients: List<String> = emptyList()) {
        // RecipeScreen re-runs this on every navigation back from the detail screen (it's the same
        // ViewModel instance the whole activity lifetime, since there's no back-stack scoping). Skip
        // the multi-second DB scan when the fridge/prioritized sets haven't changed since last time
        // and we already have a result to show; an empty last result (including from a failed search)
        // still retries, so a transient failure self-heals on the next visit.
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
                val results = openRecipesDatabase().use { database ->
                    val fridgeSet = fridgeIngredients.filter { it.isNotBlank() }.distinct()
                    val prioritizedSet = prioritizedIngredients.filter { it.isNotBlank() }.distinct()

                    if (fridgeSet.isEmpty()) {
                        _totalMatchCount.value = 0
                        return@use emptyList<Recipe>()
                    }

                    // Resolve the fridge to the set of canonical ingredient names it can supply.
                    // IngredientMatcher does the real work here: fridge "chicken" resolves to
                    // "chicken breast" and "chicken thigh" but not "chicken broth".
                    val index = CanonicalIndex.get(database)
                    val matchedCanonicals = index.matching(fridgeSet)
                    if (matchedCanonicals.isEmpty()) {
                        _totalMatchCount.value = 0
                        return@use emptyList<Recipe>()
                    }
                    // Intersected with the matched set on purpose: starring only ever boosts
                    // ingredients you actually have, and the chunked scoring path relies on the
                    // prioritized set being a subset of the matched one to partition correctly.
                    val prioritizedCanonicals =
                        if (prioritizedSet.isEmpty()) emptySet()
                        else index.matching(prioritizedSet) intersect matchedCanonicals

                    val scored = scoreRecipes(database, matchedCanonicals, prioritizedCanonicals)
                    if (scored.isEmpty()) {
                        _totalMatchCount.value = 0
                        return@use emptyList<Recipe>()
                    }

                    _totalMatchCount.value = scored.size

                    val ranked = scored.sortedWith(matchOrder)

                    // Fetch full details only for the top matches, plus any already-favorited recipe
                    // so it doesn't disappear from the favorites-pinned-to-top list just for ranking low.
                    val favoriteIds = _favoriteIds.value
                    val topMatches = (ranked.take(MAX_RESULTS) + ranked.filter { it.id in favoriteIds })
                        .distinctBy { it.id }

                    hydrate(database, topMatches)
                }

                _recipes.value = results
            } catch (e: Exception) {
                Log.e(TAG, "searchRecipes failed", e)
                _recipes.value = emptyList()
                _totalMatchCount.value = 0
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Scores every recipe in one pass over `clean_ingredients`.
     *
     * The whole 3.1M-row table is aggregated inside SQLite and only per-recipe counts cross into
     * the JVM. Counting `DISTINCT canonical` rather than rows matters: 19% of recipes list the same
     * canonical ingredient twice, and since `matched` can only ever be distinct-based, a raw
     * `COUNT(*)` denominator would systematically understate the match ratio.
     *
     * Runs once for a normal fridge. Should the matched set ever be too large to inline, it is
     * split into chunks that partition the set, so per-recipe counts still sum correctly.
     */
    private fun scoreRecipes(
        database: SQLiteDatabase,
        matchedCanonicals: Set<String>,
        prioritizedCanonicals: Set<String>
    ): List<RecipeMatch> {
        var junkIds = unparseableRecipeIds ?: loadUnparseableRecipeIds(database).also {
            unparseableRecipeIds = it
        }
        if (SUPPRESS_UNDERPARSED_RECIPES) {
            junkIds = junkIds + (underparsedRecipeIds ?: loadUnderparsedRecipeIds(database).also {
                underparsedRecipeIds = it
            })
        }

        val chunks = chunkByLiteralSize(matchedCanonicals)

        // The overwhelmingly common case. A hundred thousand recipes can match, so building the
        // merge map the multi-chunk path needs would cost tens of megabytes for nothing.
        if (chunks.size == 1) {
            val scored = ArrayList<RecipeMatch>(4096)
            queryChunk(database, chunks[0], prioritizedCanonicals) { match ->
                if (match.id !in junkIds) scored.add(match)
            }
            return scored
        }

        // Chunks partition the matched set, so a recipe's counts are simply summed across them.
        val accumulated = HashMap<Int, RecipeMatch>()
        for (chunk in chunks) {
            queryChunk(database, chunk, prioritizedCanonicals) { match ->
                if (match.id in junkIds) return@queryChunk
                val existing = accumulated[match.id]
                accumulated[match.id] = existing?.copy(
                    matched = existing.matched + match.matched,
                    prioritized = existing.prioritized + match.prioritized
                ) ?: match
            }
        }
        return accumulated.values.toList()
    }

    /** Runs the scoring aggregate for one chunk of canonicals, emitting a row at a time. */
    private inline fun queryChunk(
        database: SQLiteDatabase,
        chunk: List<String>,
        prioritizedCanonicals: Set<String>,
        emit: (RecipeMatch) -> Unit
    ) {
        // prioritizedCanonicals is a subset of the matched set, so chunking the latter partitions
        // the former too.
        val prioritizedChunk = chunk.filter { it in prioritizedCanonicals }

        val sql = buildString {
            append("SELECT recipe_id, COUNT(DISTINCT canonical) AS total, ")
            append("COUNT(DISTINCT CASE WHEN canonical IN (")
            append(sqlLiteralList(chunk))
            append(") THEN canonical END) AS matched")
            if (prioritizedChunk.isNotEmpty()) {
                append(", COUNT(DISTINCT CASE WHEN canonical IN (")
                append(sqlLiteralList(prioritizedChunk))
                append(") THEN canonical END) AS prioritized")
            }
            append(" FROM clean_ingredients GROUP BY recipe_id HAVING matched > 0")
        }

        database.rawQuery(sql, null).use { cursor ->
            val hasPrioritized = prioritizedChunk.isNotEmpty()
            while (cursor.moveToNext()) {
                emit(
                    RecipeMatch(
                        id = cursor.getInt(0),
                        total = cursor.getInt(1),
                        matched = cursor.getInt(2),
                        prioritized = if (hasPrioritized) cursor.getInt(3) else 0
                    )
                )
            }
        }
    }

    /**
     * Recipes flagged `parse_ok = 0` in the corpus — 4,391 records whose ingredient lists came out
     * garbled. They score well by accident (a one-line "recipe" trivially matches everything you
     * own) and used to fill an eighth of the first page.
     */
    private fun loadUnparseableRecipeIds(database: SQLiteDatabase): Set<Int> {
        val ids = HashSet<Int>(8192)
        try {
            database.rawQuery("SELECT id FROM recipes WHERE parse_ok = 0", null).use { cursor ->
                while (cursor.moveToNext()) ids.add(cursor.getInt(0))
            }
        } catch (e: SQLiteException) {
            // Corpus builds predating the parse_ok column. Losing this filter only lets some
            // garbled recipes back into the ranking; letting it throw would fail the entire
            // search and show the user nothing at all.
            Log.w(TAG, "recipes.parse_ok unavailable — skipping the garbled-recipe filter", e)
        }
        return ids
    }

    /**
     * Recipes where the longest raw ingredient line is suspiciously long (8+ words) but the whole
     * recipe only yielded 2 or fewer distinct canonicals — the signature of [SUPPRESS_UNDERPARSED_RECIPES]'s
     * target bug: a multi-ingredient line the corpus's parser couldn't split, collapsed down to
     * almost nothing usable for matching. Verified against the real corpus (2,248 of 333k recipes,
     * ~0.7%) rather than guessed; sampled results were dominated by genuine parser failures —
     * run-on ingredient lines, recipe-intro prose mistaken for an ingredient, garbled truncation —
     * not legitimate long single-ingredient names, though a few of those are an accepted false
     * positive here (same precision-over-recall tradeoff [loadUnparseableRecipeIds] already makes).
     *
     * Deliberately doesn't try to re-split or re-parse the line — see the conversation this was
     * added from for why that was tried and abandoned (too imprecise on this corpus's mix of
     * brand names, inconsistent Title Case, and stray non-ingredient text). This only decides
     * whether to trust the recipe's existing parse, not what its ingredients actually are.
     */
    private fun loadUnderparsedRecipeIds(database: SQLiteDatabase): Set<Int> {
        val ids = HashSet<Int>(4096)
        try {
            database.rawQuery(
                """
                WITH line_len AS (
                    SELECT recipe_id,
                           MAX(LENGTH(text) - LENGTH(REPLACE(TRIM(text), ' ', '')) + 1) AS max_words
                    FROM ingredients
                    WHERE is_heading = 0 AND text IS NOT NULL AND TRIM(text) != ''
                    GROUP BY recipe_id
                ),
                canon AS (
                    SELECT recipe_id, COUNT(DISTINCT canonical) AS canon_total
                    FROM clean_ingredients
                    GROUP BY recipe_id
                )
                SELECT line_len.recipe_id
                FROM line_len LEFT JOIN canon ON line_len.recipe_id = canon.recipe_id
                WHERE line_len.max_words >= 8 AND COALESCE(canon.canon_total, 0) <= 2
                """.trimIndent(),
                null
            ).use { cursor ->
                while (cursor.moveToNext()) ids.add(cursor.getInt(0))
            }
        } catch (e: SQLiteException) {
            Log.w(TAG, "Could not evaluate underparsed-recipe heuristic — skipping it", e)
        }
        return ids
    }

    /** Loads titles, categories and ingredient lists for the recipes that will actually be shown. */
    private fun hydrate(database: SQLiteDatabase, topMatches: List<RecipeMatch>): List<Recipe> {
        if (topMatches.isEmpty()) return emptyList()
        // Inline integer literals rather than bound parameters: MAX_RESULTS plus the favorites
        // rescue can exceed SQLite's 999-parameter limit.
        val idList = topMatches.joinToString(",") { it.id.toString() }

        // `text`, not `canonical`: this list is only the haystack for the user's free-text filter,
        // where the un-lemmatized wording is what they'd actually type.
        val recipeIngredients = mutableMapOf<Int, MutableList<String>>()
        database.rawQuery(
            "SELECT recipe_id, text FROM clean_ingredients WHERE recipe_id IN ($idList)",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val recipeId = cursor.getInt(0)
                val text = cursor.getString(1)?.lowercase() ?: continue
                recipeIngredients.getOrPut(recipeId) { mutableListOf() }.add(text)
            }
        }

        val recipeMap = mutableMapOf<Int, Pair<String, Int?>>()
        database.rawQuery(
            "SELECT id, title, servings FROM recipes WHERE id IN ($idList)",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getInt(0)
                val title = cursor.getString(1) ?: ""
                val servings = if (cursor.isNull(2)) null else cursor.getInt(2)
                recipeMap[id] = Pair(title, servings)
            }
        }

        val categoryMap = mutableMapOf<Int, MutableList<String>>()
        database.rawQuery(
            "SELECT recipe_id, category FROM categories WHERE recipe_id IN ($idList)",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val recipeId = cursor.getInt(0)
                val category = cursor.getString(1) ?: continue
                categoryMap.getOrPut(recipeId) { mutableListOf() }.add(category)
            }
        }

        // sortedRecipes re-sorts with the same ordering once favorites are known.
        return topMatches.mapNotNull { match ->
            val pair = recipeMap[match.id] ?: return@mapNotNull null
            Recipe(
                id = match.id,
                title = pair.first,
                servings = pair.second,
                categories = categoryMap[match.id] ?: emptyList(),
                ingredients = recipeIngredients[match.id] ?: emptyList(),
                matchedCount = match.matched,
                totalCount = match.total,
                prioritizedCount = match.prioritized
            )
        }
    }

    private fun openRecipesDatabase(): SQLiteDatabase =
        BundledDatabase.openReadOnly(getApplication(), "recipes.db")
}

private data class RecipeMatch(
    val id: Int,
    val matched: Int,
    val total: Int,
    val prioritized: Int,
    /** Defining-tier ingredients matched; new-corpus only, always 0 for the legacy path (which
     * never sets it, relying on this default) -- see [Recipe.definingMatchedCount]. */
    val defining: Int = 0
)

/** Smoothing prior on the match ratio; see [recipeOrder]. */
private const val RATIO_PRIOR = 2

/**
 * A recipe's match ratio, smoothed so recipe size counts for something.
 *
 * A bare `matched / total` puts every trivial one-ingredient recipe at a perfect 1.0, ahead of a
 * genuine 9-of-10 match. Adding a prior to the denominator measured a drop from 142 to 25 trivial
 * recipes in the first 200 results for a three-item fridge, and from 28 to 2 garbled ones.
 *
 * Deliberately NOT what [matchTier] ranks by: the smoothing that fixes trivial-recipe inflation
 * also lets a large recipe missing several ingredients (e.g. 3/4, smoothed 0.50) outscore a small
 * complete one (e.g. 2/2, smoothed 0.50, tied, or worse for very small totals) — which would rank
 * an "you're missing an ingredient" card above a "you have everything" one. [matchTier] is ranked
 * first specifically to rule that out; this smoothed score only breaks ties within a tier.
 */
private fun ratioScore(matched: Int, total: Int): Float =
    if (total <= 0) 0f else matched.toFloat() / (total + RATIO_PRIOR)

/**
 * The match-tier bucket a recipe falls into, on the same *unsmoothed* ratio the recipe card uses
 * to pick its color in [com.example.kitchenassistant.ui.RecipeScreen] (`RecipeCard`) — so a card
 * can never be colored green while a blue one sits above it in the list.
 */
private fun matchTier(matched: Int, total: Int): Int {
    if (total <= 0) return 0
    val ratio = matched.toFloat() / total
    return when {
        ratio >= 1f -> 2   // 100% match — green
        ratio >= 0.75f -> 1 // partial match — blue
        else -> 0
    }
}

/**
 * Display ordering, and the counterpart to [matchOrder] — the two must agree, since the cut to
 * MAX_RESULTS happens before favorites are known and the final sort happens after.
 *
 * Starred ingredients boost rather than filter: a recipe using two of them outranks one using a
 * single starred ingredient, but recipes using none are still shown.
 *
 * `definingMatchedCount` boosts the same way, right after starred ingredients: a recipe where the
 * fridge covers the dish's namesake ingredient (e.g. garlic in "Garlic Chicken") outranks one at
 * the same match tier/ratio where it doesn't — new-corpus only, always 0 (a no-op tie) for the old
 * corpus. Ranked above `matchTier` on purpose, matching how `prioritizedCount` already outranks it:
 * both are deliberate boosts, not filters, so they get first say over the raw ratio.
 */
private val recipeOrder = compareByDescending<Recipe> { it.isFavorite }
    .thenByDescending { it.prioritizedCount }
    .thenByDescending { it.definingMatchedCount }
    .thenByDescending { matchTier(it.matchedCount, it.totalCount) }
    .thenByDescending { ratioScore(it.matchedCount, it.totalCount) }
    .thenByDescending { it.matchedCount }
    .thenBy { it.title }

/** [recipeOrder] applied to raw scores, for the cut to MAX_RESULTS. */
private val matchOrder = compareByDescending<RecipeMatch> { it.prioritized }
    .thenByDescending { it.defining }
    .thenByDescending { matchTier(it.matched, it.total) }
    .thenByDescending { ratioScore(it.matched, it.total) }
    .thenByDescending { it.matched }
