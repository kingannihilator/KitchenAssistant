package com.pancakeworks.fridgegrub.ui

/**
 * One "how to use" page, shared by the first-run walkthrough and the "How to use" window under
 * About. Both render it through [HelpCarousel], so there is deliberately only ever one copy of this
 * copy -- and, now, only one thing that can render it.
 *
 * [swipeHint] is the carousel's own flow text, kept out of [body] so that a page's prose is always
 * about the app rather than about the UI it happens to be sitting in. That is also what would let
 * this list be rendered as flat text again -- in an app tour, a help article, a store listing --
 * with no edits to the copy itself.
 *
 * [emoji] stands in for art, matching how the rest of the app illustrates things: the quick-add
 * tiles (see QUICK_ADD_ITEMS in IngredientScreen) and the splash scene's fridge contents
 * (LoadingScreen) are both emoji strings rather than image assets, so there's nothing to bundle
 * and nothing to scale.
 */
internal data class HelpPage(
    val emoji: String,
    val title: String,
    val body: String,
    val bullets: List<String> = emptyList(),
    /** Carousel-only flow hint, shown once at the top of the walkthrough. */
    val swipeHint: String? = null
)

/**
 * The walkthrough's own heading, shown above the slides wherever the carousel appears -- the
 * first-run screen and About's "How to use" window alike. Phrased as the question the app exists
 * to answer rather than as a name, and set over the mascot in [HelpCarousel]'s banner, so the pair
 * reads as the walkthrough's purpose statement instead of a technical title.
 *
 * A constant rather than a parameter of [HelpCarousel], which is what it was first: a parameter
 * meant the two callers drifted on the first day, with the title passed on the first run and
 * omitted under About, so the same slides were headed in one place and bare in the other. It is
 * not the host screen's title in either case -- About's top bar says "How to use", which names the
 * window rather than the walkthrough -- so it belongs to the carousel, and to one spelling.
 */
internal const val HELP_TITLE = "What's for dinner?"

internal val HELP_PAGES: List<HelpPage> = listOf(
    HelpPage(
        // Tomato, not the carrot this page used to lead with: the same glyph IngredientScreen's
        // QUICK_ADD_ITEMS shows for Tomato, so the first slide opens on something the user is
        // about to see on the very next screen.
        emoji = "🍅",
        title = "Start with what's in your fridge",
        body = "Add what you've got. Fridge Grub knows the names as you type, so you don't have " +
            "to spell them exactly right, and quantities and dates are both optional.",
        bullets = listOf(
            "Type a few letters and pick from the suggestions",
            "Or tap a quick-add tile for common items",
            "Use + and − to set how much you have"
        ),
        // On the first page, where it tells the user something they don't know yet. It used to sit
        // on the last page, which is the one page where a "swipe for more" hint isn't true.
        //
        // Worded to cover both directions: the pager always scrolled backwards too, and a hint
        // promising only "the next tip" made the walkthrough read as one-way until Previous was
        // added -- which is itself the better teacher, this hint being the nudge before the
        // buttons are noticed.
        swipeHint = "Swipe either way to see the other tips"
    ),
    HelpPage(
        emoji = "⭐",
        title = "Star the things you want to use up",
        body = "Starring an ingredient moves recipes that use it higher up your results. It's a " +
            "boost, not a filter: recipes that don't use it still show up.",
        bullets = listOf(
            "Tap the star on any fridge row",
            "Starred ingredients sort first in your recipe results"
        )
    ),
    HelpPage(
        emoji = "🍳",
        title = "Find recipes that match",
        body = "Tap Find Recipes and Fridge Grub scores every recipe in its collection against " +
            "what you have. The whole collection is built into the app, so it works with no " +
            "connection and nothing you type ever leaves your phone.",
        bullets = listOf(
            "A green card is a complete match; amber means you have at least three-quarters",
            "Tap a card for the full ingredient list and directions"
        )
    ),
    HelpPage(
        emoji = "💗",
        title = "Heart the recipes you like",
        body = "Tapping the heart on a recipe saves it. Saved recipes stay pinned to the top of " +
            "your results, and the list lives on this phone along with your fridge.",
        bullets = listOf(
            "Tap the heart on a recipe card, or on the recipe itself",
            "The heart on the fridge screen opens your saved list",
            "It still works with an empty fridge",
            "Removed favorites wait on a \"Previously Favorited\" shelf, one tap to add back"
        )
    ),
    HelpPage(
        emoji = "🧂",
        title = "Check your pantry",
        body = "Staples like salt, pepper and oil are assumed to be on hand, so a missing pinch of " +
            "salt never spoils an otherwise perfect match. The shaker icon opens the pantry list.",
        bullets = listOf(
            "The basics most kitchens keep are already checked",
            "Uncheck anything you don't actually keep",
            "Pantry items alone never make a recipe show up"
        )
    ),
    HelpPage(
        emoji = "🍽️",
        title = "Cook it, and mark off what you use",
        body = "Open a recipe and tap Cook this recipe. Fridge Grub shows what to pull out of the " +
            "fridge and lets you knock each item down as you go, so your list stays accurate " +
            "without any extra bookkeeping.",
        bullets = listOf(
            "See at a glance what you have and what's missing",
            "Tap Read to me to have the steps read aloud — straight through, or one at a time",
            "Tap Done cooking when you're finished",
            "Cooking is available in Quantity mode, switchable from the scales icon"
        )
    )
)
