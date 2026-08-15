package com.pancakeworks.fridgegrub.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shown in place of the recipe list when a search comes back empty -- no matches at all, or a
 * free-text filter that matches nothing -- the same mascot (see ui/Mascot.kt) already used
 * elsewhere for empty states (ui/EmptyFridgeMessage.kt) and as the results list's own corner
 * cameo, just [MascotExpression.THINKING] on her own rather than paired with a fridge: this is
 * about a search coming up short, not an empty fridge (that already has its own screen/message).
 */
@Composable
fun RecipeEmptyMessage(title: String, subtitle: String, modifier: Modifier = Modifier) {
    val mascotColors = MascotColors()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp)
    ) {
        Canvas(modifier = Modifier.size(width = 140.dp, height = 190.dp)) {
            // headRadius derived from *height*, not width -- this is a full standing figure (head
            // to feet), not a cropped cameo, so it's the vertical extent that has to fit inside
            // this Canvas's declared bounds (Canvas doesn't clip its own drawing to its declared
            // size, so an earlier width-derived headRadius here left her legs overflowing well
            // past the bottom edge and overlapping the caption text below). Solving
            // "hair-top-clearance (headRadius*6.2) + legH (headRadius*2.7) <= height" for
            // headRadius, with a little margin, gives height / 9.3.
            val headRadius = size.height / 9.3f
            val hip = Offset(size.width * 0.5f, headRadius * 6.6f)
            drawMascot(
                hip = hip,
                headRadius = headRadius,
                leanDeg = 8f,
                expression = MascotExpression.THINKING,
                colors = mascotColors
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
