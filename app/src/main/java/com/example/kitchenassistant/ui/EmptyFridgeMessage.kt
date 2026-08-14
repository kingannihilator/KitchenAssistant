package com.example.kitchenassistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shown in place of the fridge list when it's empty -- the same mascot and fridge illustration as
 * the loading screen (see ui/Mascot.kt, ui/FridgeIllustration.kt), but bare shelves, since there's
 * nothing in the fridge yet. A first-run/all-cleared moment is exactly where a little of the
 * app's personality earns its keep instead of just showing blank space.
 */
@Composable
fun EmptyFridgeMessage(modifier: Modifier = Modifier) {
    val outlineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val fridgeColors = FridgeColors(
        top = MaterialTheme.colorScheme.surfaceVariant,
        side = MaterialTheme.colorScheme.outlineVariant,
        interior = MaterialTheme.colorScheme.surface,
        outline = outlineColor,
        shelf = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        handle = MaterialTheme.colorScheme.primary
    )
    val mascotColors = MascotColors()
    val textMeasurer = rememberTextMeasurer()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp)
    ) {
        // No room backdrop here (unlike the loading screen) -- at this compact, inline size a
        // wall rectangle read as a stray floating box rather than a room, and there's no floor
        // beneath it to complete the illusion. Just the fridge and mascot floating on the page,
        // like a typical minimal empty-state illustration.
        Canvas(modifier = Modifier.size(width = 260.dp, height = 220.dp)) {
            val w = size.width
            val h = size.height

            val fridgeW = w * 0.38f
            val fridgeH = h * 0.78f
            drawOpenFridge(
                textMeasurer = textMeasurer,
                anchorBottomLeft = Offset(w * 0.60f, h * 0.96f),
                width = fridgeW,
                height = fridgeH,
                colors = fridgeColors
                // No foodEmoji -- bare shelves is the point.
            )

            // headRadius is deliberately smaller (as a fraction of w) than the loading screen's
            // 0.10f: that value was tuned for a 380x440dp canvas, and reused verbatim here made
            // the whole figure taller than this canvas -- her legs overflowed past the bottom
            // edge and overlapped the caption text below, since Canvas doesn't clip its own
            // drawing to its declared size.
            drawMascot(
                hip = Offset(w * 0.18f, h * 0.71f),
                headRadius = w * 0.077f,
                leanDeg = 8f,
                expression = MascotExpression.THINKING,
                colors = mascotColors
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Your fridge is empty",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Add ingredients above to get started",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
