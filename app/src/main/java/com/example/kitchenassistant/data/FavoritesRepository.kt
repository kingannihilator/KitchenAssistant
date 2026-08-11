package com.example.kitchenassistant.data

import android.content.Context

class FavoritesRepository(context: Context) {
    private val prefs = context.getSharedPreferences("favorites_prefs", Context.MODE_PRIVATE)

    fun getFavoriteIds(): Set<Int> =
        prefs.getStringSet("favorites", emptySet())!!.map { it.toInt() }.toSet()

    fun addFavorite(id: Int) {
        val updated = HashSet(prefs.getStringSet("favorites", emptySet())!!)
        updated.add(id.toString())
        prefs.edit().putStringSet("favorites", updated).apply()
    }

    fun removeFavorite(id: Int) {
        val updated = HashSet(prefs.getStringSet("favorites", emptySet())!!)
        updated.remove(id.toString())
        prefs.edit().putStringSet("favorites", updated).apply()
    }
}
