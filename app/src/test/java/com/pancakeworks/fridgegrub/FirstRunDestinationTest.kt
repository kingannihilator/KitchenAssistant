package com.pancakeworks.fridgegrub

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the first-run ordering in MainActivity.kt. Plain JUnit for the same reason
 * RecipeRankingTest is: `firstRunDestination` is deliberately pure -- two booleans in, one Screen
 * out -- so the sequence can be pinned down without a device, an Activity, or SharedPreferences.
 *
 * The migration-guard case is the one that actually needed a test. It is the only input where the
 * "obvious" reading of the flags (`!introSeen` -> show the intro) is wrong, and getting it wrong
 * would push a first-run carousel at every existing install on update.
 */
class FirstRunDestinationTest {

    @Test
    fun `new install goes to the intro walkthrough first`() {
        assertEquals(
            Screen.Onboarding,
            firstRunDestination(introSeen = false, hasSeenPantry = false)
        )
    }

    @Test
    fun `intro already seen but pantry not confirmed goes to pantry onboarding`() {
        assertEquals(
            Screen.Pantry(isOnboarding = true),
            firstRunDestination(introSeen = true, hasSeenPantry = false)
        )
    }

    @Test
    fun `both done goes straight to the fridge`() {
        assertEquals(
            Screen.Ingredients,
            firstRunDestination(introSeen = true, hasSeenPantry = true)
        )
    }

    @Test
    fun `existing install upgrading to the walkthrough is not shown it`() {
        // introSeen is false only because the key did not exist before this feature shipped -- a
        // user who has already confirmed their pantry has demonstrably used the app, so the
        // pantry flag alone must be enough to skip the intro. This is the migration guard.
        assertEquals(
            Screen.Ingredients,
            firstRunDestination(introSeen = false, hasSeenPantry = true)
        )
    }

    @Test
    fun `the testing switch shows the carousel regardless of the stored flags`() {
        assertEquals(
            Screen.Onboarding,
            initialDestination(
                showOnboardingForTesting = true,
                introSeen = true,
                hasSeenPantry = true
            )
        )
    }

    @Test
    fun `leaving the carousel moves forward instead of looping back into it`() {
        // The real risk the switch introduces: OnboardingScreen exits via markIntroSeen() followed
        // by firstRunDestination(introSeen = true, ...). If that exit ever routed through
        // initialDestination instead, this switch would send the user back to the carousel they
        // just dismissed, forever. Pin both halves of that: the exit is not the switch, and the
        // exit lands somewhere real.
        assertEquals(
            Screen.Ingredients,
            firstRunDestination(introSeen = true, hasSeenPantry = true)
        )
        assertEquals(
            Screen.Pantry(isOnboarding = true),
            firstRunDestination(introSeen = true, hasSeenPantry = false)
        )
    }

    @Test
    fun `with the switch off the real rule is unchanged`() {
        assertEquals(
            Screen.Onboarding,
            initialDestination(
                showOnboardingForTesting = false,
                introSeen = false,
                hasSeenPantry = false
            )
        )
        assertEquals(
            Screen.Ingredients,
            initialDestination(
                showOnboardingForTesting = false,
                introSeen = false,
                hasSeenPantry = true
            )
        )
    }
}
