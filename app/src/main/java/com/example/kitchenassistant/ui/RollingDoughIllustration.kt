package com.example.kitchenassistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.example.kitchenassistant.ui.theme.MascotCounter
import com.example.kitchenassistant.ui.theme.MascotDough

/**
 * The mascot (see ui/Mascot.kt) rolling out dough at a counter, both arms at work
 * ([MascotExpression.WORKING]) -- the recipe detail screen's "hard at work" cameo, sitting where
 * cooking is actually happening rather than a corner peek. Drawn mascot-first, counter-and-props
 * second, so the opaque counter naturally covers her legs (she reads as standing *behind* it) and
 * the dough/rolling pin sit in front of her hands.
 */
@Composable
fun WorkingMascotScene(modifier: Modifier = Modifier) {
    val mascotColors = MascotColors()

    Canvas(modifier = modifier.size(width = 220.dp, height = 190.dp)) {
        val w = size.width
        val h = size.height
        val counterTopY = h * 0.72f

        // headRadius is small relative to this canvas -- her hands land right at counter height
        // (see drawMascot's WORKING branch: handsCenter is ~torsoH*0.05 below the hip), and the
        // hip has to sit low enough for that to reach the counter while still leaving room above
        // for her head and hair to clear the canvas top.
        val headRadius = w * 0.085f
        val torsoH = headRadius * 4.25f
        val hip = Offset(w * 0.5f, counterTopY - torsoH * 0.05f)

        drawMascot(
            hip = hip,
            headRadius = headRadius,
            leanDeg = 0f,
            expression = MascotExpression.WORKING,
            colors = mascotColors
        )

        // Counter surface -- covers her legs (standing behind it) and grounds the scene.
        drawRect(
            color = MascotCounter,
            topLeft = Offset(0f, counterTopY),
            size = Size(w, h - counterTopY)
        )
        drawLine(
            color = MascotCounter.copy(alpha = 0.7f),
            start = Offset(0f, counterTopY),
            end = Offset(w, counterTopY),
            strokeWidth = 2.dp.toPx()
        )

        // Dough, then a rolling pin on top of it at her hands' height.
        val doughCenter = Offset(hip.x + headRadius * 0.3f, counterTopY + headRadius * 0.25f)
        drawOval(
            color = MascotDough,
            topLeft = doughCenter - Offset(headRadius * 1.3f, headRadius * 0.55f),
            size = Size(headRadius * 2.6f, headRadius * 1.1f)
        )
        val pinHalfWidth = headRadius * 1.6f
        drawRoundRect(
            color = MascotCounter,
            topLeft = doughCenter - Offset(pinHalfWidth, headRadius * 0.22f),
            size = Size(pinHalfWidth * 2, headRadius * 0.44f),
            cornerRadius = CornerRadius(headRadius * 0.2f)
        )
        for (dx in listOf(-pinHalfWidth, pinHalfWidth)) {
            drawCircle(
                color = MascotCounter,
                radius = headRadius * 0.16f,
                center = doughCenter + Offset(dx, 0f)
            )
        }
    }
}
