package com.example.kitchenassistant.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kitchenassistant.ui.theme.LoadingSceneHair
import com.example.kitchenassistant.ui.theme.LoadingSceneOutfit
import com.example.kitchenassistant.ui.theme.LoadingSceneSkin
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

// How long the loading screen stays up before handing off to the ingredient list. Long enough to
// register as a deliberate splash rather than a flicker, short enough not to feel like a stall --
// the app has no real async startup work to wait on (ingredients.db loads fast, see
// IngredientViewModel's init), so this is purely a fixed, deliberate branding beat.
private const val LOADING_DURATION_MS = 1800L

/**
 * App launch splash screen: a woman leaning into an open fridge, pondering what to make, before
 * handing off to the ingredient list via [onFinished]. No bundled image asset -- the whole scene
 * is drawn in a single Canvas as a simplified "2.5D" room corner (flat faces at a fixed skew
 * angle, not true isometric projection), so it scales cleanly and reads correctly in light and
 * dark mode by drawing structural colors (wall/floor/fridge) from the Material theme -- only the
 * woman's skin/hair/outfit are fixed colors, same reasoning as FavoriteHeart in Color.kt.
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

/** Rotates [p] by [degrees] around [pivot] -- used to find where a body part painted inside a
 * `rotate { }` block actually lands on screen, for positioning the (unrotated) thought bubble. */
private fun rotatePoint(p: Offset, pivot: Offset, degrees: Float): Offset {
    val rad = Math.toRadians(degrees.toDouble())
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    val dx = p.x - pivot.x
    val dy = p.y - pivot.y
    return Offset(pivot.x + dx * c - dy * s, pivot.y + dx * s + dy * c)
}

@Composable
private fun KitchenScene(modifier: Modifier = Modifier) {
    val wallColor = MaterialTheme.colorScheme.surfaceContainerLow
    val floorColor = MaterialTheme.colorScheme.surfaceVariant
    val fridgeTopColor = MaterialTheme.colorScheme.surfaceVariant
    val fridgeSideColor = MaterialTheme.colorScheme.outlineVariant
    val fridgeInteriorColor = MaterialTheme.colorScheme.surface
    val outlineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val shelfColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val handleColor = MaterialTheme.colorScheme.primary
    val bubbleColor = MaterialTheme.colorScheme.primaryContainer
    val bubbleTextColor = MaterialTheme.colorScheme.onPrimaryContainer
    val faceLineColor = Color.Black.copy(alpha = 0.55f)
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // --- Room corner: back wall + receding floor, a simplified "2.5D" plane rather than a
        // true isometric projection -- enough to read as a room without needing real 3D math. ---
        val wallBottom = h * 0.48f
        drawRect(color = wallColor, size = Size(w, wallBottom))
        // A symmetric trapezoid -- narrower back edge (at the wall seam) than front edge (at the
        // canvas bottom) -- rather than the earlier lopsided quadrilateral, which had its two
        // slanted edges leaning in unrelated directions and read as a stray rug, not a floor.
        // Converging toward the back like this is the classic cheap "floor with perspective" cue.
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

        // --- Fridge, door open: a flat-shaded box (top face + right-side face) with the front
        // left open to show shelves, plus a door swung out to the left on a hinge at FTL/FBL. ---
        val fridgeW = w * 0.26f
        val fridgeH = h * 0.44f
        val depthDx = w * 0.10f
        val depthDy = h * 0.065f

        val fbl = Offset(w * 0.60f, h * 0.78f)
        val fbr = Offset(fbl.x + fridgeW, fbl.y - h * 0.015f)
        val ftl = Offset(fbl.x, fbl.y - fridgeH)
        val ftr = Offset(fbr.x, fbr.y - fridgeH)
        val btl = Offset(ftl.x + depthDx, ftl.y - depthDy)
        val btr = Offset(ftr.x + depthDx, ftr.y - depthDy)
        val bbr = Offset(fbr.x + depthDx, fbr.y - depthDy)

        val topFace = Path().apply {
            moveTo(ftl.x, ftl.y); lineTo(ftr.x, ftr.y); lineTo(btr.x, btr.y); lineTo(btl.x, btl.y); close()
        }
        val sideFace = Path().apply {
            moveTo(fbr.x, fbr.y); lineTo(ftr.x, ftr.y); lineTo(btr.x, btr.y); lineTo(bbr.x, bbr.y); close()
        }
        drawPath(topFace, color = fridgeTopColor)
        drawPath(topFace, color = outlineColor, style = Stroke(width = 1.5.dp.toPx()))
        drawPath(sideFace, color = fridgeSideColor)
        drawPath(sideFace, color = outlineColor, style = Stroke(width = 1.5.dp.toPx()))

        // Open interior: an inset, recessed rectangle with shelves and a few food blobs.
        val il = ftl.x + fridgeW * 0.10f
        val ir = ftr.x - fridgeW * 0.06f
        val itop = ftl.y + fridgeH * 0.08f
        val ibottom = fbl.y - fridgeH * 0.05f
        drawRoundRect(
            color = fridgeInteriorColor,
            topLeft = Offset(il, itop),
            size = Size(ir - il, ibottom - itop),
            cornerRadius = CornerRadius(4.dp.toPx())
        )
        val shelfY1 = itop + (ibottom - itop) * 0.42f
        val shelfY2 = itop + (ibottom - itop) * 0.74f
        for (shelfY in listOf(shelfY1, shelfY2)) {
            drawLine(
                color = shelfColor,
                start = Offset(il + 2.dp.toPx(), shelfY),
                end = Offset(ir - 2.dp.toPx(), shelfY),
                strokeWidth = 2.dp.toPx()
            )
        }
        // Food on the shelves, drawn as the same emoji the app's own Quick Add row uses (see
        // IngredientScreen's QUICK_ADD_ITEMS) -- ties the illustration to what the app is
        // actually for, rather than abstract colored blocks standing in for "some food".
        val foodStyle = TextStyle(fontSize = 15.sp)
        val foodSpots = listOf(
            "🍗" to Offset(il + (ir - il) * 0.26f, shelfY1),
            "🧀" to Offset(il + (ir - il) * 0.62f, shelfY1),
            "🥕" to Offset(il + (ir - il) * 0.42f, shelfY2)
        )
        for ((emoji, anchor) in foodSpots) {
            val emojiLayout = textMeasurer.measure(emoji, style = foodStyle)
            drawText(
                emojiLayout,
                topLeft = Offset(anchor.x - emojiLayout.size.width / 2f, anchor.y - emojiLayout.size.height - 1.dp.toPx())
            )
        }
        drawRoundRect(
            color = outlineColor,
            topLeft = Offset(il, itop),
            size = Size(ir - il, ibottom - itop),
            cornerRadius = CornerRadius(4.dp.toPx()),
            style = Stroke(width = 1.dp.toPx())
        )

        // Door, hinged at the fridge's front-left edge (ftl-fbl), swung open to the left.
        val doorW = fridgeW * 0.60f
        val doorLean = h * 0.02f
        val dft = Offset(ftl.x - doorW, ftl.y - doorLean)
        val dfb = Offset(fbl.x - doorW, fbl.y - doorLean)
        val doorPath = Path().apply {
            moveTo(ftl.x, ftl.y); lineTo(dft.x, dft.y); lineTo(dfb.x, dfb.y); lineTo(fbl.x, fbl.y); close()
        }
        drawPath(doorPath, color = fridgeSideColor)
        drawPath(doorPath, color = outlineColor, style = Stroke(width = 1.5.dp.toPx()))
        drawLine(
            color = handleColor,
            start = Offset(dft.x + 5.dp.toPx(), dft.y + (dfb.y - dft.y) * 0.28f),
            end = Offset(dft.x + 5.dp.toPx(), dft.y + (dfb.y - dft.y) * 0.72f),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )

        // --- Woman, leaning forward toward the open door. Legs/torso/head are computed here (in
        // the canvas's normal, unrotated coordinates) and then painted inside a single `rotate`
        // block so they lean together as one rigid body, pivoting at the hip. ---
        val hip = Offset(w * 0.16f, h * 0.76f)
        val leanDeg = 9f
        val torsoW = w * 0.15f
        val torsoH = h * 0.22f
        val torsoTopLeft = Offset(hip.x - torsoW / 2f, hip.y - torsoH)
        val headRadius = w * 0.060f
        val headCenter = Offset(hip.x + torsoW * 0.12f, torsoTopLeft.y - headRadius * 0.75f)

        rotate(degrees = leanDeg, pivot = hip) {
            // Legs.
            drawRoundRect(
                color = LoadingSceneOutfit,
                topLeft = Offset(hip.x - w * 0.05f, hip.y),
                size = Size(w * 0.035f, h * 0.14f),
                cornerRadius = CornerRadius(3.dp.toPx())
            )
            drawRoundRect(
                color = LoadingSceneOutfit,
                topLeft = Offset(hip.x + w * 0.015f, hip.y),
                size = Size(w * 0.035f, h * 0.14f),
                cornerRadius = CornerRadius(3.dp.toPx())
            )
            // Torso.
            drawRoundRect(
                color = LoadingSceneOutfit,
                topLeft = torsoTopLeft,
                size = Size(torsoW, torsoH),
                cornerRadius = CornerRadius(9.dp.toPx())
            )
            // Arm bent up toward the chin -- the classic "thinking" pose.
            val shoulder = Offset(torsoTopLeft.x + torsoW * 0.88f, torsoTopLeft.y + torsoH * 0.18f)
            val elbow = Offset(shoulder.x + w * 0.045f, shoulder.y + h * 0.035f)
            val hand = Offset(shoulder.x + w * 0.015f, shoulder.y - h * 0.045f)
            drawLine(LoadingSceneSkin, shoulder, elbow, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
            drawLine(LoadingSceneSkin, elbow, hand, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
            // Hair (behind the head), then the head itself, then a short hair wisp at the crown.
            drawCircle(
                color = LoadingSceneHair,
                radius = headRadius * 1.08f,
                center = headCenter - Offset(0f, headRadius * 0.12f)
            )
            drawCircle(color = LoadingSceneSkin, radius = headRadius, center = headCenter)
            drawArc(
                color = LoadingSceneHair,
                startAngle = 190f,
                sweepAngle = 120f,
                useCenter = false,
                style = Stroke(width = headRadius * 0.5f, cap = StrokeCap.Round),
                topLeft = headCenter - Offset(headRadius, headRadius),
                size = Size(headRadius * 2, headRadius * 2)
            )
            // Furrowed eyebrow + a small frown -- "pondering", facing down-right toward the fridge.
            val browStart = headCenter + Offset(headRadius * 0.0f, -headRadius * 0.15f)
            drawLine(
                color = faceLineColor,
                start = browStart,
                end = browStart + Offset(headRadius * 0.55f, -headRadius * 0.12f),
                strokeWidth = 2.2.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawArc(
                color = faceLineColor,
                startAngle = 195f,
                sweepAngle = 55f,
                useCenter = false,
                style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round),
                topLeft = headCenter + Offset(headRadius * 0.0f, headRadius * 0.30f),
                size = Size(headRadius * 0.7f, headRadius * 0.5f)
            )
        }

        // --- Thought bubble, drawn in unrotated space above where her (leaned) head actually
        // lands on screen -- a puffy rounded box with small trailing circles, not a speech
        // bubble's pointed tail, to read as "thinking" rather than "speaking aloud". ---
        val headScreenCenter = rotatePoint(headCenter, hip, leanDeg)
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
