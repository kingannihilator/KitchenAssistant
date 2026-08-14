// Legacy (recipes.db) recipe-search code path removed from RecipeViewModel.kt.
// Preserved here for reference only -- not compiled, not part of the app module.
// See porting-reference/legacy-recipe-path/README.md for why this was removed.
//
// This is a concatenation of the deleted declarations, in roughly their original order, with
// enough surrounding context (imports, companion-object consts it depended on) to read standalone.
// It will not compile as-is without the rest of RecipeViewModel.kt (RecipeMatch, matchOrder, etc.,
// which were NOT deleted and still live in the real file).

package com.example.kitchenassistant.viewmodel

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import com.example.kitchenassistant.data.BundledDatabase
import com.example.kitchenassistant.data.CanonicalIndex
import com.example.kitchenassistant.model.Recipe

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
// recovered, just hidden from ranking.
const val SUPPRESS_UNDERPARSED_RECIPES = true

// --- New-corpus switch ---
//
// recipe_database.sqlite (odunola/foodie, 19,566 recipes, ~95MB) is a second, smaller
// recipe database bundled alongside the original recipes.db (333k recipes, ~620MB) —
// see porting-reference/ANDROID_HANDOFF.md and INGREDIENT_MATCHING_CONCEPTS.md for the
// full background. USE_NEW_RECIPE_DATABASE selects which one backs search and detail.
const val USE_NEW_RECIPE_DATABASE = true

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
 * recipe only yielded 2 or fewer distinct canonicals — the signature of SUPPRESS_UNDERPARSED_RECIPES's
 * target bug: a multi-ingredient line the corpus's parser couldn't split, collapsed down to
 * almost nothing usable for matching. Verified against the real corpus (2,248 of 333k recipes,
 * ~0.7%) rather than guessed; sampled results were dominated by genuine parser failures —
 * run-on ingredient lines, recipe-intro prose mistaken for an ingredient, garbled truncation —
 * not legitimate long single-ingredient names, though a few of those are an accepted false
 * positive here (same precision-over-recall tradeoff loadUnparseableRecipeIds already makes).
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
