package com.pancakeworks.fridgegrub.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// How long the loading screen stays up before handing off to the ingredient list. Long enough to
// register as a deliberate splash rather than a flicker, short enough not to feel like a stall --
// the app has no real async startup work to wait on (ingredients.db loads fast, see
// IngredientViewModel's init), so this is purely a fixed, deliberate branding beat.
private const val LOADING_DURATION_MS = 1800L

/**
 * App launch splash screen: the mascot (see ui/Mascot.kt) leaning into an open fridge, pondering
 * what to make, before handing off to the ingredient list via [onFinished]. No bundled image asset
 * -- the whole scene is drawn in a single Canvas as a simplified "2.5D" room corner (flat faces at
 * a fixed skew angle, not true isometric projection), so it scales cleanly and reads correctly in
 * light and dark mode by drawing structural colors (wall/floor/fridge) from the Material theme.
 */
@Composable
fun LoadingScreen(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(LOADING_DURATION_MS)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            KitchenScene(modifier = Modifier.size(width = 380.dp, height = 440.dp))
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun KitchenScene(modifier: Modifier = Modifier) {
    val wallColor = MaterialTheme.colorScheme.surfaceContainerLow
    val floorColor = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val fridgeColors = FridgeColors(
        top = MaterialTheme.colorScheme.surfaceVariant,
        side = MaterialTheme.colorScheme.outlineVariant,
        interior = MaterialTheme.colorScheme.surface,
        outline = outlineColor,
        shelf = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        handle = MaterialTheme.colorScheme.primary
    )
    val bubbleColor = MaterialTheme.colorScheme.primaryContainer
    val bubbleTextColor = MaterialTheme.colorScheme.onPrimaryContainer
    val mascotColors = MascotColors()
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // --- Room corner: back wall + receding floor, a simplified "2.5D" plane rather than a
        // true isometric projection -- enough to read as a room without needing real 3D math. ---
        val wallBottom = h * 0.48f
        drawRect(color = wallColor, size = Size(w, wallBottom))
        // A symmetric trapezoid -- narrower back edge (at the wall seam) than front edge (at the
        // canvas bottom) -- reads as a floor plane converging toward the back, the standard cheap
        // "floor with perspective" cue.
        val floorBackLeft = Offset(w * 0.15f, wallBottom)
        val floorBackRight = Offset(w * 0.85f, wallBottom)
        val floorPath = Path().apply {
            moveTo(floorBackLeft.x, floorBackLeft.y)
            lineTo(floorBackRight.x, floorBackRight.y)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(floorPath, color = floorColor)
        drawLine(
            color = outlineColor.copy(alpha = 0.35f),
            start = floorBackLeft,
            end = floorBackRight,
            strokeWidth = 1.dp.toPx()
        )

        // Fridge, open door, food on the shelves -- the same emoji the app's own Quick Add row
        // uses (see IngredientScreen's QUICK_ADD_ITEMS) -- ties the illustration to what the app
        // is actually for.
        val fridgeW = w * 0.26f
        val fridgeH = h * 0.44f
        val fridgeAnchor = Offset(w * 0.60f, h * 0.78f)
        drawOpenFridge(
            textMeasurer = textMeasurer,
            anchorBottomLeft = fridgeAnchor,
            width = fridgeW,
            height = fridgeH,
            colors = fridgeColors,
            foodEmoji = listOf("🍗", "🧀", "🥕")
        )

        // Mascot, leaning forward toward the open door.
        val hip = Offset(w * 0.16f, h * 0.76f)
        val leanDeg = 9f
        val headRadius = w * 0.060f
        val headScreenCenter = drawMascot(
            hip = hip,
            headRadius = headRadius,
            leanDeg = leanDeg,
            expression = MascotExpression.THINKING,
            colors = mascotColors
        )

        // --- Thought bubble, drawn in unrotated space above where her (leaned) head actually
        // lands on screen -- a puffy rounded box with small trailing circles, not a speech
        // bubble's pointed tail, to read as "thinking" rather than "speaking aloud". ---
        // Bigger and higher than a normal thought bubble would sit -- this line is the app's
        // whole pitch (turning what's already in the fridge into a meal), not idle chatter, so it
        // should read first, like a tagline sitting above the scene rather than a small aside.
        val bubbleStyle = TextStyle(
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = bubbleTextColor,
            textAlign = TextAlign.Center
        )
        val layout = textMeasurer.measure(
            text = "What's for dinner today?",
            style = bubbleStyle,
            constraints = Constraints(maxWidth = (w * 0.62f).toInt())
        )
        val pad = 12.dp.toPx()
        val bubbleSize = Size(layout.size.width + pad * 2, layout.size.height + pad * 2)
        val bubbleTopLeft = Offset(
            x = (headScreenCenter.x - bubbleSize.width * 0.35f).coerceIn(4.dp.toPx(), w - bubbleSize.width - 4.dp.toPx()),
            y = (headScreenCenter.y - headRadius - bubbleSize.height - h * 0.20f).coerceAtLeast(4.dp.toPx())
        )
        drawRoundRect(
            color = bubbleColor,
            topLeft = bubbleTopLeft,
            size = bubbleSize,
            cornerRadius = CornerRadius(14.dp.toPx())
        )
        drawText(layout, topLeft = Offset(bubbleTopLeft.x + pad, bubbleTopLeft.y + pad))

        // Trailing circles, decreasing in size, from the bubble down toward her head.
        val trailStart = Offset(bubbleTopLeft.x + bubbleSize.width * 0.3f, bubbleTopLeft.y + bubbleSize.height)
        val trailEnd = Offset(headScreenCenter.x - headRadius * 0.2f, headScreenCenter.y - headRadius * 1.3f)
        val steps = listOf(0.35f to 7.dp.toPx(), 0.62f to 5.dp.toPx(), 0.85f to 3.dp.toPx())
        for ((t, r) in steps) {
            drawCircle(
                color = bubbleColor,
                radius = r,
                center = Offset(
                    trailStart.x + (trailEnd.x - trailStart.x) * t,
                    trailStart.y + (trailEnd.y - trailStart.y) * t
                )
            )
        }
    }
}
