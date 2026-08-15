package com.pancakeworks.fridgegrub.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pancakeworks.fridgegrub.ui.theme.FavoriteHeart

/**
 * Top-bar shortcut to the Favorites screen -- a small pair of hearts, not the single solid heart
 * used everywhere else in the app for the per-recipe favorite toggle (RecipeScreen's card,
 * RecipeDetailScreen's app bar, FavoritesScreen's own rows). Those are all "is this one recipe
 * favorited"; this is "go look at the favorites list", a different action that happened to share
 * the exact same glyph -- worth a visually distinct icon so the two aren't confused at a glance.
 */
@Composable
fun FavoritesShortcutIcon(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .semantics { contentDescription = "View favorite recipes" }
    ) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = null,
            tint = FavoriteHeart.copy(alpha = 0.55f),
            modifier = Modifier
                .size(15.dp)
                .align(Alignment.TopStart)
        )
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = null,
            tint = FavoriteHeart,
            modifier = Modifier
                .size(17.dp)
                .align(Alignment.BottomEnd)
        )
    }
}
