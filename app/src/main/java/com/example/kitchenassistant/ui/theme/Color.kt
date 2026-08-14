package com.example.kitchenassistant.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

val FullMatchContainerLight = Color(0xFFC8E6C9)
val FullMatchContainerDark = Color(0xFF2E7D32)
val FullMatchOnContainerLight = Color(0xFF1B5E20)
val FullMatchOnContainerDark = Color(0xFFA5D6A7)

val PartialMatchContainerLight = Color(0xFFBBDEFB)
val PartialMatchContainerDark = Color(0xFF1565C0)
val PartialMatchOnContainerLight = Color(0xFF0D47A1)
val PartialMatchOnContainerDark = Color(0xFF90CAF9)

val FridgeMatchGreen = Color(0xFF4CAF50)
val FridgeMissingRed = Color(0xFFF44336)

// The classic "liked" heart pink/red seen across social apps -- deliberately a fixed brand-style
// color rather than a theme role, since a red heart reads as "favorited" regardless of light/dark
// mode (same reasoning as FridgeMatchGreen/FridgeMissingRed above).
val FavoriteHeart = Color(0xFFE91E63)

// Permanent, subtle backgrounds for available/missing ingredient rows on the recipe detail
// screen -- light enough to sit calmly behind text at all times, but still a visibly different
// hue from each other and from the page background, and deliberately paler than
// FridgeMissingRed's own "highlight what's missing" flash (up to 0.7 alpha) so that flash still
// reads as a distinct, temporary emphasis layered on top rather than blending into the baseline.
val IngredientAvailableBgLight = Color(0xFFE3F3E6)
val IngredientAvailableBgDark = Color(0xFF1E3323)
val IngredientMissingBgLight = Color(0xFFFCEAEA)
val IngredientMissingBgDark = Color(0xFF3A2223)

// The app's recurring illustrated mascot (see ui/Mascot.kt) -- fixed, not theme-derived (same
// reasoning as FavoriteHeart above): skin/hair/outfit need to read as a person consistently,
// regardless of the device's dynamic Material You palette, which could otherwise tint skin an
// unnatural hue. One shared set of colors so every appearance (loading screen, empty fridge,
// corner cameos) is visibly the same character.
val MascotSkin = Color(0xFFE8B08C)
val MascotHair = Color(0xFF4A3728)
val MascotOutfit = Color(0xFF5C6BC0)

// Props for the "rolling dough" scene (see ui/RollingDoughIllustration.kt) -- also fixed rather
// than theme-derived: a wood counter and pale dough need to read as themselves regardless of the
// device's Material You palette, same reasoning as the mascot's own colors above.
val MascotCounter = Color(0xFF9C7B54)
val MascotDough = Color(0xFFF3E4C8)
// A lighter wood tone than MascotCounter, deliberately -- the pin used to be drawn in the same
// color as the counter beneath it, which read as a hole cut through the dough (revealing the
// counter) rather than a cylinder resting on top of it.
val MascotPin = Color(0xFFC9A66B)