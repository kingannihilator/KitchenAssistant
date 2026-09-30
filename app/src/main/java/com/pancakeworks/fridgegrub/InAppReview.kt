package com.pancakeworks.fridgegrub

import android.app.Activity
import com.google.android.gms.tasks.Task
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The Play In-App Review flow, wrapped so callers don't have to juggle Play Core's `Task` API.
 *
 * **A `true` return means "the flow ran", never "the user saw or left a review".** The API is
 * explicit that its callbacks carry no such information, and it enforces its own undisclosed
 * time-bound quota -- [request] can complete successfully and show nothing at all. That is
 * required behaviour, not a failure: never build UI that depends on a card appearing, never
 * re-request because nothing seemed to happen, and never log a missing card as an error. The
 * permanent "Rate this app" entry on About ([ui.openPlayStoreListing]) is the guaranteed path;
 * this one is the bonus.
 *
 * There is no manifest entry or permission for this (unlike the TTS `<queries>` block) -- Play
 * Core talks to the Play Store app directly via an already-visible service.
 *
 * Failure is swallowed into `false` throughout, deliberately: this runs off a tap in the middle of
 * the cook flow, and nothing the user did should appear to break because a review card couldn't be
 * fetched. The caller only needs to know whether to bother again later.
 */
internal object InAppReview {

    /**
     * Requests and launches the review flow on [activity]. Returns true only if Play handed back a
     * review flow that was then successfully launched.
     */
    suspend fun request(activity: Activity): Boolean = runCatching {
        val manager = ReviewManagerFactory.create(activity)
        val reviewInfo = manager.requestReviewFlow().awaitOrNull() ?: return@runCatching false
        // launchReviewFlow's Task completes when the card is dismissed (or when there was no card
        // to show) -- there is nothing to inspect in the result either way.
        manager.launchReviewFlow(activity, reviewInfo).awaitOrNull()
        true
    }.getOrDefault(false)

    /**
     * Suspends until [this] Task completes, yielding its result or null if it failed.
     *
     * Hand-rolled rather than pulled from `play-review-ktx`: this is the only Play Core call in the
     * app, and the -ktx artifact exists to supply exactly this one extension. Takes no executor, so
     * Play's listeners fire on the main thread, which is where [request] is already being called
     * from anyway.
     *
     * The Task type here is `com.google.android.gms.tasks.Task`, not a `play.core.tasks` one: since
     * Play Core 2.0 the library returns GMS Tasks, delivered transitively via `play-services-tasks`.
     */
    private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            // isActive, not isCancelled-check-then-resume: the activity can be torn down between
            // the request going out and Play coming back, which cancels the coroutine above.
            if (cont.isActive) cont.resume(if (task.isSuccessful) task.result else null)
        }
    }
}
