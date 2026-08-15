package com.pancakeworks.fridgegrub.model

import java.util.UUID

/**
 * Represents a single ingredient in the user's fridge.
 *
 * Instances are immutable data classes — any change (e.g. toggling excluded state)
 * produces a new copy via [copy], which triggers a StateFlow emission in the ViewModel.
 *
 * @property id            Unique identifier generated at creation; used as a stable key in
 *                         Compose lazy lists to preserve item animations and avoid recomposition.
 * @property name          Display name of the ingredient as entered or selected by the user.
 * @property expirationDate Expiration date stored as milliseconds since the Unix epoch, or null
 *                         if no date was set. Formatted for display in the UI via SimpleDateFormat.
 * @property count         How many units of this ingredient the user has. Defaults to 1.
 *                         Decrementing to 0 removes the ingredient entirely.
 * @property isNegative     When true, the ingredient is excluded from recipe searches and shown
 *                          with a strikethrough in a red card.
 * @property isPrioritized  When true, this ingredient is flagged as a priority for recipe search.
 *                          Multiple ingredients can be prioritized simultaneously.
 * @property unit           The unit of measurement for the quantity (e.g. "units", "cups").
 */
data class Ingredient(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val expirationDate: Long? = null,
    val count: Int = 1,
    val isNegative: Boolean = false,
    val isPrioritized: Boolean = false,
    val unit: String = "units"
)
