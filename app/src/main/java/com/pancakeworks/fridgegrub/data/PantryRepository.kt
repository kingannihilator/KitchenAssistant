package com.pancakeworks.fridgegrub.data

import android.content.Context

/**
 * Persists the user's pantry/seasoning checklist -- which of [ALL_PANTRY_ITEMS] (or any other
 * name, though the UI only ever offers that list) they've confirmed having on hand, plus whether
 * the first-run onboarding checklist has been shown. Same SharedPreferences-backed mechanism as
 * [FavoritesRepository], just one set instead of two, since there's no history concept here --
 * unchecking an item means "I don't have this," full stop.
 *
 * Deliberately no quantity, expiration, or per-item state beyond checked/unchecked: this is a
 * coarse "do you generally have this" signal for recipe matching, not fridge inventory.
 */
class PantryRepository(context: Context) {
    private val prefs = context.getSharedPreferences("pantry_prefs", Context.MODE_PRIVATE)

    fun getCheckedItems(): Set<String> =
        prefs.getStringSet(KEY_ITEMS, null) ?: DEFAULT_CHECKED_PANTRY_ITEMS

    fun saveCheckedItems(items: Set<String>) {
        prefs.edit().putStringSet(KEY_ITEMS, items).apply()
    }

    fun hasSeenOnboarding(): Boolean = prefs.getBoolean(KEY_ONBOARDING_SEEN, false)

    fun markOnboardingSeen() {
        prefs.edit().putBoolean(KEY_ONBOARDING_SEEN, true).apply()
    }

    companion object {
        private const val KEY_ITEMS = "checked_items"
        private const val KEY_ONBOARDING_SEEN = "onboarding_seen"
    }
}
