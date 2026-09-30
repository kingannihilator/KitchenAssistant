package com.pancakeworks.fridgegrub.data

import android.content.Context

/**
 * Tracks whether the first-run walkthrough ([com.pancakeworks.fridgegrub.ui.OnboardingScreen]) has
 * been seen. Same SharedPreferences-backed mechanism and shape as [PantryRepository], one flag
 * instead of a set.
 *
 * Deliberately a separate file and key from that class's `onboarding_seen`
 * ([PantryRepository.hasSeenOnboarding]), which means specifically "the pantry checklist has been
 * confirmed". The two are consecutive steps of first run, and overloading one flag for both would
 * make the ordering in `MainActivity`'s first-run gate impossible to express -- and impossible to
 * tell apart in stored data later.
 */
class OnboardingRepository(context: Context) {
    private val prefs = context.getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)

    fun hasSeenIntro(): Boolean = prefs.getBoolean(KEY_INTRO_SEEN, false)

    fun markIntroSeen() {
        prefs.edit().putBoolean(KEY_INTRO_SEEN, true).apply()
    }

    companion object {
        private const val KEY_INTRO_SEEN = "intro_seen"
    }
}
