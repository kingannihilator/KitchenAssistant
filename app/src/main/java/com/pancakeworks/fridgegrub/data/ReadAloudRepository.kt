package com.pancakeworks.fridgegrub.data

import android.content.Context
import com.pancakeworks.fridgegrub.model.ReadAloudPlaybackMode

/**
 * Persists the user's read-aloud speed and playback mode (SharedPreferences-backed, same
 * mechanism as [AppModeRepository]) so a preference set on one recipe carries over to the next,
 * and across app restarts.
 */
class ReadAloudRepository(context: Context) {
    private val prefs = context.getSharedPreferences("read_aloud_prefs", Context.MODE_PRIVATE)

    fun loadSpeed(): Float = prefs.getFloat(KEY_SPEED, DEFAULT_SPEED)

    fun saveSpeed(speed: Float) {
        prefs.edit().putFloat(KEY_SPEED, speed).apply()
    }

    fun loadMode(): ReadAloudPlaybackMode {
        val name = prefs.getString(KEY_MODE, null) ?: return ReadAloudPlaybackMode.THROUGH
        return try {
            ReadAloudPlaybackMode.valueOf(name)
        } catch (e: IllegalArgumentException) {
            ReadAloudPlaybackMode.THROUGH
        }
    }

    fun saveMode(mode: ReadAloudPlaybackMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    companion object {
        private const val KEY_SPEED = "speed"
        private const val KEY_MODE = "mode"

        // A bit slower than the platform default (1.0x) -- directions read more naturally at a
        // pace someone can cook along to, per user feedback that the default rate reads too fast.
        const val DEFAULT_SPEED = 0.85f

        /** Steps the speed control cycles through on tap, slowest to fastest. */
        val SPEED_STEPS = listOf(0.7f, DEFAULT_SPEED, 1.0f, 1.25f)
    }
}
