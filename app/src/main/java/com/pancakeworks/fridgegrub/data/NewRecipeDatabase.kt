package com.pancakeworks.fridgegrub.data

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
 *
 * `version` MUST be bumped every time the bundled asset's schema changes (a new `RecipeEntity`
 * column, for instance) -- confirmed via a real on-device crash, not theorized:
 * `createFromAsset` only copies the asset into the app's internal storage on first install, so an
 * existing install that already has an old copy there will open THAT file and Room will throw
 * `IllegalStateException: Room cannot verify the data integrity` the moment the declared entities'
 * schema hash no longer matches what's on disk. `fallbackToDestructiveMigration` is the right
 * response here (not a real `Migration`): this is a read-only bundled corpus with no user data to
 * preserve, so "wipe and recopy the new asset" is exactly what should happen on a version bump.
 */
@Database(
    entities = [RecipeEntity::class, NewIngredientEntity::class, RecipeStepEntity::class, CategoryEntity::class],
    version = 2,
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
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
