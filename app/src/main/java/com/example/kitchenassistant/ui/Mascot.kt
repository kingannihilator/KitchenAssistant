package com.example.kitchenassistant.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.example.kitchenassistant.ui.theme.MascotHair
import com.example.kitchenassistant.ui.theme.MascotOutfit
import com.example.kitchenassistant.ui.theme.MascotSkin
import kotlin.math.cos
import kotlin.math.sin

/**
 * The app's recurring illustrated character -- originally built for the loading screen (a woman
 * pondering an open fridge), now reused wherever a small dose of the same personality helps: the
 * empty-fridge state, a "thinking" cameo on the recipe results list, a "smiling" one on Favorites.
 * Kept to one shared drawing function so every appearance is visibly the same person, just a
 * different expression/pose -- see [drawMascot].
 */
enum class MascotExpression { THINKING, SMILING, WORKING }

/** Fixed (not Material-theme-derived) coloring for [drawMascot] -- see [MascotSkin]'s doc for why. */
data class MascotColors(
    val skin: Color = MascotSkin,
    val hair: Color = MascotHair,
    val outfit: Color = MascotOutfit,
    val faceLine: Color = Color.Black.copy(alpha = 0.55f)
)

/**
 * Draws the mascot as a simple flat-design standing figure: legs, torso, arm(s) (bent toward the
 * chin for [MascotExpression.THINKING], relaxed at her side for [MascotExpression.SMILING], or
 * both reaching forward for [MascotExpression.WORKING]), hair, and a small face mark for the
 * expression. Every proportion is a multiple of [headRadius], so the same figure scales cleanly
 * from a full room scene down to a small peeking bust just by changing that one number.
 *
 * [hip] is the pivot for both [leanDeg] (a forward/sideways lean, rotating the whole figure as one
 * rigid body) and for anchoring her feet to whatever "ground" the caller has drawn. Returns where
 * her head actually lands on screen after that rotation, so a caller (e.g. a thought bubble) can
 * position itself relative to her without re-deriving the rotation math.
 */
fun DrawScope.drawMascot(
    hip: Offset,
    headRadius: Float,
    leanDeg: Float,
    expression: MascotExpression,
    colors: MascotColors = MascotColors()
): Offset {
    val torsoW = headRadius * 2.5f
    val torsoH = headRadius * 4.25f
    val legW = headRadius * 0.58f
    val legH = headRadius * 2.7f
    val torsoTopLeft = Offset(hip.x - torsoW / 2f, hip.y - torsoH)
    val headCenter = Offset(hip.x + torsoW * 0.12f, torsoTopLeft.y - headRadius * 0.75f)

    rotate(degrees = leanDeg, pivot = hip) {
        // Legs.
        drawRoundRect(
            color = colors.outfit,
            topLeft = Offset(hip.x - legW * 1.4f, hip.y),
            size = Size(legW, legH),
            cornerRadius = CornerRadius(legW * 0.3f)
        )
        drawRoundRect(
            color = colors.outfit,
            topLeft = Offset(hip.x + legW * 0.4f, hip.y),
            size = Size(legW, legH),
            cornerRadius = CornerRadius(legW * 0.3f)
        )
        // Torso.
        drawRoundRect(
            color = colors.outfit,
            topLeft = torsoTopLeft,
            size = Size(torsoW, torsoH),
            cornerRadius = CornerRadius(headRadius * 0.4f)
        )
        // Arm(s): bent up toward the chin when thinking, a short relaxed stub at her side when
        // smiling, or both arms reaching forward together when working.
        val rightShoulder = Offset(torsoTopLeft.x + torsoW * 0.88f, torsoTopLeft.y + torsoH * 0.18f)
        when (expression) {
            MascotExpression.THINKING -> {
                val elbow = Offset(rightShoulder.x + headRadius * 0.75f, rightShoulder.y + headRadius * 0.58f)
                val hand = Offset(rightShoulder.x + headRadius * 0.25f, rightShoulder.y - headRadius * 0.75f)
                drawLine(colors.skin, rightShoulder, elbow, strokeWidth = headRadius * 0.22f, cap = StrokeCap.Round)
                drawLine(colors.skin, elbow, hand, strokeWidth = headRadius * 0.22f, cap = StrokeCap.Round)
            }
            MascotExpression.SMILING -> {
                val hand = Offset(rightShoulder.x + headRadius * 0.15f, rightShoulder.y + headRadius * 1.4f)
                drawLine(colors.skin, rightShoulder, hand, strokeWidth = headRadius * 0.22f, cap = StrokeCap.Round)
            }
            MascotExpression.WORKING -> {
                // Both arms reach down and forward to a shared point in front of her -- roughly
                // where a rolling pin sits. The caller draws the pin/dough itself (see
                // ui/RollingDoughIllustration.kt); this just gets her hands to the right spot.
                val leftShoulder = Offset(torsoTopLeft.x + torsoW * 0.12f, torsoTopLeft.y + torsoH * 0.18f)
                val handsCenter = Offset(hip.x + torsoW * 0.12f, torsoTopLeft.y + torsoH * 1.05f)
                val leftHand = handsCenter - Offset(headRadius * 0.55f, 0f)
                val rightHand = handsCenter + Offset(headRadius * 0.55f, 0f)
                drawLine(colors.skin, leftShoulder, leftHand, strokeWidth = headRadius * 0.22f, cap = StrokeCap.Round)
                drawLine(colors.skin, rightShoulder, rightHand, strokeWidth = headRadius * 0.22f, cap = StrokeCap.Round)
            }
        }
        // Hair (behind the head), then the head itself, then a short hair wisp at the crown.
        drawCircle(
            color = colors.hair,
            radius = headRadius * 1.08f,
            center = headCenter - Offset(0f, headRadius * 0.12f)
        )
        drawCircle(color = colors.skin, radius = headRadius, center = headCenter)
        drawArc(
            color = colors.hair,
            startAngle = 190f,
            sweepAngle = 120f,
            useCenter = false,
            style = Stroke(width = headRadius * 0.5f, cap = StrokeCap.Round),
            topLeft = headCenter - Offset(headRadius, headRadius),
            size = Size(headRadius * 2, headRadius * 2)
        )
        when (expression) {
            MascotExpression.THINKING -> {
                // Furrowed eyebrow + a small frown mark -- "pondering", facing down-right.
                val browStart = headCenter + Offset(0f, -headRadius * 0.15f)
                drawLine(
                    color = colors.faceLine,
                    start = browStart,
                    end = browStart + Offset(headRadius * 0.55f, -headRadius * 0.12f),
                    strokeWidth = headRadius * 0.10f,
                    cap = StrokeCap.Round
                )
                drawArc(
                    color = colors.faceLine,
                    startAngle = 195f,
                    sweepAngle = 55f,
                    useCenter = false,
                    style = Stroke(width = headRadius * 0.10f, cap = StrokeCap.Round),
                    topLeft = headCenter + Offset(0f, headRadius * 0.30f),
                    size = Size(headRadius * 0.7f, headRadius * 0.5f)
                )
            }
            MascotExpression.SMILING -> {
                // Two small dot eyes + an upward smile mark -- relaxed, pleased.
                drawCircle(
                    color = colors.faceLine,
                    radius = headRadius * 0.07f,
                    center = headCenter + Offset(-headRadius * 0.20f, -headRadius * 0.05f)
                )
                drawCircle(
                    color = colors.faceLine,
                    radius = headRadius * 0.07f,
                    center = headCenter + Offset(headRadius * 0.28f, -headRadius * 0.05f)
                )
                drawArc(
                    color = colors.faceLine,
                    startAngle = 25f,
                    sweepAngle = 130f,
                    useCenter = false,
                    style = Stroke(width = headRadius * 0.10f, cap = StrokeCap.Round),
                    topLeft = headCenter + Offset(-headRadius * 0.32f, headRadius * 0.02f),
                    size = Size(headRadius * 0.64f, headRadius * 0.5f)
                )
            }
            MascotExpression.WORKING -> {
                // Two small dot eyes looking down at her work + a small flat, focused mouth --
                // content and concentrating, not smiling or frowning.
                drawCircle(
                    color = colors.faceLine,
                    radius = headRadius * 0.07f,
                    center = headCenter + Offset(-headRadius * 0.20f, headRadius * 0.02f)
                )
                drawCircle(
                    color = colors.faceLine,
                    radius = headRadius * 0.07f,
                    center = headCenter + Offset(headRadius * 0.28f, headRadius * 0.02f)
                )
                drawLine(
                    color = colors.faceLine,
                    start = headCenter + Offset(-headRadius * 0.16f, headRadius * 0.35f),
                    end = headCenter + Offset(headRadius * 0.20f, headRadius * 0.35f),
                    strokeWidth = headRadius * 0.09f,
                    cap = StrokeCap.Round
                )
            }
        }
    }
    return rotateAroundPivot(headCenter, hip, leanDeg)
}

/** Rotates [p] by [degrees] around [pivot] -- used to find where a body part painted inside
 * [drawMascot]'s `rotate { }` block actually lands on screen, for positioning things relative to
 * her (a thought bubble, an anchor point) from outside that rotated coordinate space. */
fun rotateAroundPivot(p: Offset, pivot: Offset, degrees: Float): Offset {
    val rad = Math.toRadians(degrees.toDouble())
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    val dx = p.x - pivot.x
    val dy = p.y - pivot.y
    return Offset(pivot.x + dx * c - dy * s, pivot.y + dx * s + dy * c)
}
