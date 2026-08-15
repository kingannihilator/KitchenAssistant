package com.example.kitchenassistant.data

import android.content.Context
import com.example.kitchenassistant.model.AppMode

/**
 * Persists the user's chosen [AppMode] (SharedPreferences-backed, same mechanism as
 * [FavoritesRepository]/[FridgeRepository]). Defaults to [AppMode.QUANTITY] so existing installs
 * see no behavior change on upgrade.
 */
class AppModeRepository(context: Context) {
    private val prefs = context.getSharedPreferences("app_mode_prefs", Context.MODE_PRIVATE)

    fun load(): AppMode {
        val name = prefs.getString("mode", null) ?: return AppMode.QUANTITY
        return try {
            AppMode.valueOf(name)
        } catch (e: IllegalArgumentException) {
            AppMode.QUANTITY
        }
    }

    fun save(mode: AppMode) {
        prefs.edit().putString("mode", mode.name).apply()
    }
}
