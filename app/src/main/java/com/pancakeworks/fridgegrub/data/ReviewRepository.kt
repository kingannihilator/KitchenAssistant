package com.pancakeworks.fridgegrub.data

import android.content.Context

/**
 * Tracks how many cook sessions the user has completed, and whether the one-time in-app review
 * request has already been made. Same SharedPreferences-backed mechanism and shape as
 * [PantryRepository].
 *
 * **The "requested" flag is set as soon as the request is *made*, not when a review card is
 * shown.** That is deliberate, because the Play In-App Review API never reports whether the card
 * appeared -- its completion callback says only that the flow finished, "not whether the user
 * reviewed or not, or even whether the review dialog was shown". If this flag were set only after
 * observing a card, it could never be set at all, and every subsequent cook session would re-request
 * the review, which the API's guidance explicitly warns against. The two-gate design is therefore:
 * this flag (ours, one-shot) and Play's own undisclosed time-bound quota (theirs).
 *
 * Note there is no `synchronized` here, unlike [FavoritesRepository]: the count is only ever
 * incremented from a tap on "Done cooking" on the main thread, whereas FavoritesRepository's lock
 * exists because `toggleFavorite` launches a fresh IO coroutine per tap.
 */
class ReviewRepository(context: Context) {
    private val prefs = context.getSharedPreferences("review_prefs", Context.MODE_PRIVATE)

    fun getCookSessionCount(): Int = prefs.getInt(KEY_COOK_COUNT, 0)

    fun incrementCookSessionCount(): Int =
        (getCookSessionCount() + 1).also { prefs.edit().putInt(KEY_COOK_COUNT, it).apply() }

    fun hasRequestedReview(): Boolean = prefs.getBoolean(KEY_REVIEW_REQUESTED, false)

    fun markReviewRequested() {
        prefs.edit().putBoolean(KEY_REVIEW_REQUESTED, true).apply()
    }

    companion object {
        /** Completed cook sessions needed before the one-time in-app review request fires. */
        const val REVIEW_TRIGGER_COUNT = 3

        private const val KEY_COOK_COUNT = "cook_count"
        private const val KEY_REVIEW_REQUESTED = "review_requested"
    }
}

/**
 * The review trigger rule, split out as a pure function for the same reason `RecipeRanking.kt` was
 * split out of `RecipeViewModel`: it is the actual decision, as opposed to the SharedPreferences
 * read/write around it, and it is the part worth testing. Keeping it here means the threshold and
 * the one-shot gate can be exercised under plain JUnit with no Context and no Robolectric.
 *
 * Both halves matter. Without `alreadyRequested`, a user who cooks three times a week would be
 * asked every week; the counter keeps climbing past the trigger, so the flag is the only thing
 * making this one-shot.
 */
internal fun shouldRequestReview(cookSessionCount: Int, alreadyRequested: Boolean): Boolean =
    cookSessionCount >= ReviewRepository.REVIEW_TRIGGER_COUNT && !alreadyRequested
