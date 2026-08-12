package com.example.kitchenassistant.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database over the new recipe corpus (`assets/database/recipe_database.sqlite`).
 *
 * Unlike [BundledDatabase]'s per-call open/close pattern for the old corpus, this is held open
 * for the app's lifetime via the singleton below -- the normal Room idiom, and cheaper here since
 * there's no need to re-check/re-copy the asset on every call the way `openReadOnly` does.
 *
 * `exportSchema = false`: this is a read-only prepackaged asset with no migrations to test against
 * exported schema JSON, so there's nothing to export.
 */
@Database(
    entities = [RecipeEntity::class, NewIngredientEntity::class, RecipeStepEntity::class, CategoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class NewRecipeDatabase : RoomDatabase() {

    abstract fun newRecipeDao(): NewRecipeDao

    companion object {
        @Volatile
        private var instance: NewRecipeDatabase? = null

        fun getInstance(context: Context): NewRecipeDatabase {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NewRecipeDatabase::class.java,
                    "recipe_database.sqlite"
                )
                    .createFromAsset("database/recipe_database.sqlite")
                    .build()
                    .also { instance = it }
            }
        }
    }
}
