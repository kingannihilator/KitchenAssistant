package com.pancakeworks.fridgegrub

import com.pancakeworks.fridgegrub.data.ReviewRepository
import com.pancakeworks.fridgegrub.data.shouldRequestReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the in-app review trigger rule (data/ReviewRepository.kt). Only the decision is tested --
 * the SharedPreferences reads/writes around it need a Context, and there is no Robolectric in this
 * project, which is exactly why the rule was split out as a pure function.
 */
class ReviewTriggerTest {

    @Test
    fun `first two cook sessions do not trigger`() {
        assertFalse(shouldRequestReview(cookSessionCount = 1, alreadyRequested = false))
        assertFalse(shouldRequestReview(cookSessionCount = 2, alreadyRequested = false))
    }

    @Test
    fun `third cook session triggers`() {
        assertTrue(
            shouldRequestReview(
                cookSessionCount = ReviewRepository.REVIEW_TRIGGER_COUNT,
                alreadyRequested = false
            )
        )
    }

    @Test
    fun `later cook sessions do not trigger again`() {
        // The counter keeps climbing past the trigger -- nothing resets it -- so `alreadyRequested`
        // is the only thing making this one-shot. Without it, every cook from the third onward
        // would request a review.
        assertFalse(shouldRequestReview(cookSessionCount = 4, alreadyRequested = true))
        assertFalse(shouldRequestReview(cookSessionCount = 99, alreadyRequested = true))
    }

    @Test
    fun `a threshold reached after an earlier request stays quiet`() {
        // Belt-and-braces against the counting logic changing: if a count somehow arrived at or
        // past the threshold with the flag already set, the flag still wins.
        assertFalse(
            shouldRequestReview(
                cookSessionCount = ReviewRepository.REVIEW_TRIGGER_COUNT,
                alreadyRequested = true
            )
        )
    }

    @Test
    fun `across a full cook sequence the review is requested exactly once`() {
        // Mirrors what the ViewModel does per "Done cooking" tap, including the fact that a
        // successful trigger sets the flag. Walks well past the threshold to prove it never
        // re-arms.
        var requested = false
        var triggers = 0
        for (session in 1..10) {
            if (shouldRequestReview(cookSessionCount = session, alreadyRequested = requested)) {
                triggers++
                requested = true
            }
        }
        assertEquals(1, triggers)
        assertTrue(requested)
    }
}
