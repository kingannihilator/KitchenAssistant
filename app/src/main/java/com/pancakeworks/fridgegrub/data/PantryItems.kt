package com.pancakeworks.fridgegrub.data

/**
 * Common pantry/seasoning staples offered by the onboarding and pantry-edit checklists (see
 * [PantryRepository], `ui/PantryScreen.kt`). A curated static list, not derived from the recipe
 * corpus -- same spirit as `IngredientScreen.kt`'s `UNIT_OPTIONS`/Quick-Add shortcuts, since a
 * checklist needs a small, predictable, human-picked set rather than whatever the corpus happens
 * to use most.
 *
 * Every item here is offered on the checklist, but only [DEFAULT_CHECKED_PANTRY_ITEMS] starts
 * checked -- the rest (mostly specific spices/condiments a lot of, but not most, households keep)
 * are still one tap away, just not assumed. See [PantryRepository.getCheckedItems].
 */
val ALL_PANTRY_ITEMS: List<String> = listOf(
    "water",
    "milk",
    "salt",
    "black pepper",
    "garlic",
    "onion",
    "olive oil",
    "vegetable oil",
    "butter",
    "sugar",
    "brown sugar",
    "all-purpose flour",
    "vinegar",
    "soy sauce",
    "ketchup",
    "mustard",
    "mayonnaise",
    "honey",
    "baking powder",
    "baking soda",
    "cinnamon",
    "paprika",
    "cumin",
    "oregano",
    "chili powder",
    "garlic powder",
    "onion powder",
    "vanilla extract"
)

/**
 * Staples near-universal enough across households to assume by default -- basics almost every
 * kitchen has, plus the near-universal baking/condiment set. Deliberately excludes more
 * cuisine- or baking-specific items (cumin, oregano, chili powder, garlic/onion powder, vanilla
 * extract, honey, brown sugar, soy sauce) that plenty of households simply don't keep -- those
 * stay on the list (see [ALL_PANTRY_ITEMS]) but start unchecked.
 */
val DEFAULT_CHECKED_PANTRY_ITEMS: Set<String> = setOf(
    "water",
    "milk",
    "salt",
    "black pepper",
    "garlic",
    "onion",
    "olive oil",
    "vegetable oil",
    "butter",
    "sugar",
    "all-purpose flour",
    "vinegar",
    "ketchup",
    "mustard",
    "mayonnaise",
    "baking powder",
    "baking soda"
)
