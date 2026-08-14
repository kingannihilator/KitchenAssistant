package com.example.kitchenassistant.data

import android.content.Context
import com.example.kitchenassistant.model.Ingredient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the fridge's ingredient list to SharedPreferences as a JSON array, so it survives app
 * close/process death (previously in-memory only, the app's single biggest data-loss surface --
 * see [com.example.kitchenassistant.viewmodel.IngredientViewModel]). Same mechanism as
 * [FavoritesRepository], just JSON instead of a bare string set, since an `Ingredient` has several
 * fields to round-trip rather than one id. `org.json` (not a third-party library) is enough --
 * this repo has no JSON dependency otherwise, and the fridge is a handful of rows, not a corpus.
 *
 * Whole-list save-and-overwrite, not incremental: cheap at this size, and avoids any
 * add/remove/edit sync bugs a partial-update scheme could introduce.
 */
class FridgeRepository(context: Context) {
    private val prefs = context.getSharedPreferences("fridge_prefs", Context.MODE_PRIVATE)

    /** Returns the saved fridge contents, or an empty list if nothing was saved yet or the saved
     * JSON can't be parsed (e.g. a future app version changes the shape). */
    fun load(): List<Ingredient> {
        val json = prefs.getString(KEY_INGREDIENTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.toIngredientOrNull() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun JSONObject.toIngredientOrNull(): Ingredient? {
        val id = optString("id", "").ifEmpty { return null }
        val name = optString("name", "").ifEmpty { return null }
        return Ingredient(
            id = id,
            name = name,
            expirationDate = if (has("expirationDate") && !isNull("expirationDate")) getLong("expirationDate") else null,
            count = optInt("count", 1),
            isNegative = optBoolean("isNegative", false),
            isPrioritized = optBoolean("isPrioritized", false),
            unit = optString("unit", "units")
        )
    }

    /** Overwrites the saved fridge contents with [ingredients]. */
    fun save(ingredients: List<Ingredient>) {
        val array = JSONArray()
        for (ingredient in ingredients) {
            array.put(JSONObject().apply {
                put("id", ingredient.id)
                put("name", ingredient.name)
                put("expirationDate", ingredient.expirationDate ?: JSONObject.NULL)
                put("count", ingredient.count)
                put("isNegative", ingredient.isNegative)
                put("isPrioritized", ingredient.isPrioritized)
                put("unit", ingredient.unit)
            })
        }
        prefs.edit().putString(KEY_INGREDIENTS, array.toString()).apply()
    }

    private companion object {
        const val KEY_INGREDIENTS = "ingredients_json"
    }
}
