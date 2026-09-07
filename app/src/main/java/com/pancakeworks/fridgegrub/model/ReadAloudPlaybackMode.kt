package com.pancakeworks.fridgegrub.model

/**
 * How the read-aloud feature (`ui/RecipeDetailScreen.kt`) advances between directions steps once
 * one finishes.
 */
enum class ReadAloudPlaybackMode {
    /** Keeps going automatically until the last step, same as before this mode existed. */
    THROUGH,

    /** Stops after each step and waits for the user to tap play again before reading the next
     * one. */
    STEP_BY_STEP
}
