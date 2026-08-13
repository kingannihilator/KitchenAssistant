package com.example.kitchenassistant.data

import android.content.Context

/**
 * Two persisted sets: [getFavoriteIds] (currently favorited) and [getFavoriteHistoryIds] (every
 * id that has *ever* been favorited, including ones since removed). History only grows -- it's
 * how the Favorites screen can show a "previously favorited" shelf so removing one by mistake (or
 * on purpose, then changing your mind) doesn't mean re-finding it via a fresh search.
 */
class FavoritesRepository(context: Context) {
    private val prefs = context.getSharedPreferences("favorites_prefs", Context.MODE_PRIVATE)

    fun getFavoriteIds(): Set<Int> =
        prefs.getStringSet("favorites", emptySet())!!.map { it.toInt() }.toSet()

    fun getFavoriteHistoryIds(): Set<Int> =
        prefs.getStringSet("favorite_history", emptySet())!!.map { it.toInt() }.toSet()

    fun addFavorite(id: Int) {
        val updatedFavorites = HashSet(prefs.getStringSet("favorites", emptySet())!!)
        updatedFavorites.add(id.toString())
        val updatedHistory = HashSet(prefs.getStringSet("favorite_history", emptySet())!!)
        updatedHistory.add(id.toString())
        prefs.edit()
            .putStringSet("favorites", updatedFavorites)
            .putStringSet("favorite_history", updatedHistory)
            .apply()
    }

    /** Removes [id] from the active favorites only -- [getFavoriteHistoryIds] still remembers it. */
    fun removeFavorite(id: Int) {
        val updated = HashSet(prefs.getStringSet("favorites", emptySet())!!)
        updated.remove(id.toString())
        prefs.edit().putStringSet("favorites", updated).apply()
    }
}
