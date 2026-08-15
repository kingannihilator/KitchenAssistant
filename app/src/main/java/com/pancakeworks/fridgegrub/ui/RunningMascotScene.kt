package com.pancakeworks.fridgegrub.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/**
 * The mascot (see ui/Mascot.kt) mid-sprint, [MascotExpression.RUNNING] -- RecipeScreen's search
 * loading state, replacing a bare CircularProgressIndicator with the same illustrated-character
 * treatment the loading screen, empty fridge, and results/favorites cameos already use. A full
 * standing figure, not a cropped cameo (contrast [PeekingMascot]/[WorkingMascotScene]): search has
 * an otherwise-empty screen to fill while it runs, so there's room for the whole pose (the leg
 * stride is the whole point) rather than needing to crop down to fit a corner.
 *
 * A ground shadow anchors her feet, and a few horizontal motion lines trail behind her (opposite
 * her running direction) -- a still image of a mid-stride pose reads as motion well enough on its
 * own, but the lines make "hurrying" unambiguous at a glance, the same cheap-but-legible cue as
 * the loading screen's thought-bubble trail.
 */
@Composable
fun RunningMascotScene(modifier: Modifier = Modifier) {
    val mascotColors = MascotColors()
    val motionLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val shadowColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)

    Canvas(modifier = modifier.size(width = 220.dp, height = 340.dp)) {
        // headRadius derived from *height*, not width -- Canvas doesn't clip its own drawing to
        // its declared size, so a width-derived headRadius here (an earlier version used
        // w * 0.16f) left the ground shadow below, the lowest thing this scene draws, well past
        // the declared canvas height, overlapping whatever the caller places underneath (the
        // Spacer + CircularProgressIndicator on RecipeScreen's loading state). The shadow (at
        // hip.y + headRadius*3.9, see below) sits lower than the running legs' own trailing foot,
        // so it -- not the standard legH used by every other pose -- is what has to fit: solving
        // "hair-top-clearance (headRadius*6.2) + shadow-bottom-below-hip (headRadius*3.9) <=
        // height" for headRadius, with a little margin, gives height / 10.5.
        val headRadius = size.height / 10.5f
        val hip = Offset(size.width * 0.5f, headRadius * 6.6f)

        // Ground shadow, drawn first (beneath everything): a flattened oval right under her
        // trailing foot, not the hip itself -- with one leg kicked back and one driven forward,
        // centering it on the hip would leave it floating under empty space between her feet.
        drawOval(
            color = shadowColor,
            topLeft = Offset(hip.x - headRadius * 1.1f, hip.y + headRadius * 3.35f),
            size = Size(headRadius * 2.2f, headRadius * 0.55f)
        )

        // Motion lines: three short horizontal dashes trailing off her back side, shortest (and
        // so implicitly "fastest") closest to her, fading out as they trail further behind.
        val lineStartX = hip.x - headRadius * 2.6f
        val lineYs = listOf(hip.y - headRadius * 3.2f, hip.y - headRadius * 2.2f, hip.y - headRadius * 1.2f)
        val lineLengths = listOf(headRadius * 0.9f, headRadius * 1.3f, headRadius * 1.0f)
        for (i in lineYs.indices) {
            drawLine(
                color = motionLineColor,
                start = Offset(lineStartX, lineYs[i]),
                end = Offset(lineStartX - lineLengths[i], lineYs[i]),
                strokeWidth = headRadius * 0.14f,
                cap = StrokeCap.Round
            )
        }

        drawMascot(
            hip = hip,
            headRadius = headRadius,
            leanDeg = 6f,
            expression = MascotExpression.RUNNING,
            colors = mascotColors
        )
    }
}
