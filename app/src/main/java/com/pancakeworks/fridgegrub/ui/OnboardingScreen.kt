package com.pancakeworks.fridgegrub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * First-run walkthrough: [HelpCarousel] under the app's own name, shown once before the pantry step
 * (see MainActivity's first-run gate).
 *
 * The same slides are reachable later under About -- "How to use" -- so this screen is a one-time
 * introduction, not the only copy of anything.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    // There is nothing behind this screen (the splash has already been replaced), so Back means
    // "skip the rest", exactly like the Skip button. Same plain-lambda form as PantryScreen.
    //
    // Deliberately here rather than inside HelpCarousel: what Back means is the caller's business.
    // Here the carousel *is* the screen, so back leaves it; under About it is a window inside a
    // screen, so back closes the window. One shared handler inside the carousel couldn't be both.
    BackHandler(onBack = onDone)

    HelpCarousel(
        finishLabel = "Get started",
        onFinish = onDone,
        onSkip = onDone
    )
}

/**
 * The walkthrough slides, over [HELP_PAGES]. Shared by the first-run screen above and About's "How
 * to use" window -- one pager, one set of dots, one bottom bar, so the two can't drift.
 *
 * Every page scrolls independently, which is not optional: for a new user this is the very first
 * screen, and it's the one place a raised system font size could push text out of reach with no
 * list behind it to scroll.
 *
 * Above the slides sits a banner: [HELP_TITLE] over a peeking bust of the mascot, so the two read
 * as the walkthrough's purpose statement rather than a bare title. The heading is a constant, not a
 * parameter -- it was one, and the two callers immediately disagreed about it (see that constant's
 * doc). The slides are the same content in both places, so they get the same banner in both places.
 *
 * The bottom bar is the other way round as well as forward: Previous appears from the second slide
 * on (hidden, not disabled, on the first -- see its comment) and sits next to Next, outlined where
 * Next is filled so the pair reads as one control without the two competing. Skip stays alone on
 * the left, and only on the first run. Swiping
 * already worked in both directions; the buttons are there because a swipe is not discoverable, and
 * the slide content itself gives no hint that anything sits *before* the page you are reading.
 *
 * @param finishLabel text on the affirmative button on the last page. "Get started" for a first run,
 *   because it moves the user into the app; "Close" where the carousel is just something to look at.
 * @param onSkip null hides Skip entirely. Only the first run has somewhere to skip *to*; under
 *   About, skipping the walkthrough and closing it are the same gesture, and the host already offers
 *   both a Close button and Back for that.
 */
@Composable
internal fun HelpCarousel(
    finishLabel: String,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    onSkip: (() -> Unit)? = null
) {
    val pagerState = rememberPagerState(pageCount = { HELP_PAGES.size })
    val scope = rememberCoroutineScope()
    val isFirstPage = pagerState.currentPage == 0
    val isLastPage = pagerState.currentPage == HELP_PAGES.lastIndex

    Column(
        // The app draws edge-to-edge, so without this the title sits up inside the status bar band
        // -- visually clear of the clock on a phone, but overlapping the bar itself. The bottom
        // inset is handled inside, on the button row, because the bar's own surface is meant to
        // extend behind the navigation bar rather than stop at it.
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // The banner: the question, with her peeking up beneath it, rather than a bare title. It
        // sits above the pager instead of inside it because she introduces the walkthrough -- she
        // isn't part of any one slide.
        //
        // PeekingMascot is decorative (a Canvas has no semantics node), so a screen reader still
        // hears only the title below -- nothing is lost by having her there.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                HELP_TITLE,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    // The walkthrough's name, as a landmark a screen-reader user can jump back to
                    // after wandering into the page below it.
                    .semantics { heading() }
            )
            Spacer(Modifier.height(6.dp))
            PeekingMascot(
                // THINKING is the "curious" pose everywhere else it appears (the splash, the
                // empty-fridge state, the results list), so the walkthrough opens with the same
                // character the rest of the app does.
                expression = MascotExpression.THINKING,
                // Centered, so there's no side for her to lean toward; true is the gentler of the
                // two tilts, reading as a quizzical look up at the question rather than a slump.
                leanTowardCenter = true,
                // Scaled down from PeekingMascot's own 92x120dp, keeping that exact ratio: the
                // ratio is calibrated so the top of her hair clears the box's top edge with a
                // small margin, and a different one slices her hair off at the crown.
                modifier = Modifier.size(width = 66.dp, height = 86.dp)
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { pageIndex ->
            val page = HELP_PAGES[pageIndex]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Sits the centered block a little above true center, i.e. up toward the
                    // banner. This has to be padding outside verticalScroll rather than a trailing
                    // Spacer inside it: a spacer would also add 36dp of scrollable blank under the
                    // slides that do fill their page.
                    .padding(bottom = 36.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(page.emoji, style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.height(20.dp))
                Text(
                    page.title,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() }
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    page.body,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                if (page.bullets.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Column(
                        modifier = Modifier.widthIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        page.bullets.forEach { bullet ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("•", style = MaterialTheme.typography.bodyMedium)
                                Text(bullet, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                page.swipeHint?.let { hint ->
                    // Wider than the 8dp between bullets, so the flow hint reads as belonging to
                    // the carousel rather than as one more bullet.
                    Spacer(Modifier.height(36.dp))
                    Text(
                        hint,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        PageIndicator(
            pageCount = HELP_PAGES.size,
            currentPage = pagerState.currentPage,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

        Surface(shadowElevation = 8.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                // Skip is the only thing that ever sits on the left, and in the same place on
                // every page. With no Skip -- the About window -- there is a single child, and
                // SpaceBetween would place that one child at the start, so the two cases still
                // have to be chosen between; the paging pair belongs on the right either way.
                horizontalArrangement = if (onSkip == null) Arrangement.End else Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                onSkip?.let { skip ->
                    // Always present, on every page -- the walkthrough is never compulsory.
                    TextButton(onClick = skip) { Text("Skip") }
                }
                // Previous and Next together on the right: the same filled shape and the same
                // corner, but OutlinedButton for Previous so the outline carries the hierarchy --
                // Next is what the walkthrough is nudging toward, and two identical fills would
                // leave nothing to say which one that is.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Hidden rather than disabled on the first slide: there is nothing behind it
                    // to go back to, and the pager itself can't be scrolled that way either, so a
                    // greyed-out button would point at a page that does not exist.
                    if (!isFirstPage) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            }
                        ) {
                            Text("Previous")
                        }
                    }
                    Button(
                        onClick = {
                            if (isLastPage) {
                                onFinish()
                            } else {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                            }
                        }
                    ) {
                        Text(if (isLastPage) finishLabel else "Next")
                    }
                }
            }
        }
    }
}

/** Row of dots showing how many pages there are and which one is showing. */
@Composable
private fun PageIndicator(pageCount: Int, currentPage: Int, modifier: Modifier = Modifier) {
    Row(
        // The dots themselves carry no text, so without this the row is invisible to a screen
        // reader and a user has no way to tell where they are in the walkthrough.
        modifier = modifier.semantics { contentDescription = "Page ${currentPage + 1} of $pageCount" },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(8.dp)
                    .background(
                        color = if (index == currentPage) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape
                    )
            )
        }
    }
}
