package com.pancakeworks.fridgegrub.model

/**
 * Whether the fridge tracks exact quantities/units ([QUANTITY], today's default behavior, and the
 * only mode where cook mode's fridge-deduction UI applies) or just presence ([CHECKLIST] -- no
 * quantity/unit UI anywhere). Recipe search/matching only ever reads ingredient names
 * ([com.pancakeworks.fridgegrub.data.IngredientMatcher]), never count/unit, so this toggle has no
 * effect on search results either way.
 */
enum class AppMode {
    CHECKLIST,
    QUANTITY
}
