package com.pancakeworks.fridgegrub.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.pancakeworks.fridgegrub.ui.theme.MascotCounter
import com.pancakeworks.fridgegrub.ui.theme.MascotDough
import com.pancakeworks.fridgegrub.ui.theme.MascotPin

/**
 * The mascot (see ui/Mascot.kt) rolling out dough at a small board, both arms at work
 * ([MascotExpression.WORKING]) -- the recipe detail screen's "hard at work" cameo, sitting where
 * cooking is actually happening rather than a corner peek. A close, cropped view -- just head,
 * shoulders and arms, no full half body -- rather than a full standing figure: [clipToBounds]
 * crops her hip/legs (and most of her torso), the same way a photo crops close for a
 * "working at the counter" shot.
 */
@Composable
fun WorkingMascotScene(modifier: Modifier = Modifier) {
    val mascotColors = MascotColors()

    Canvas(
        modifier = modifier
            .size(width = 210.dp, height = 150.dp)
            .clipToBounds()
    ) {
        val w = size.width
        val headRadius = w * 0.12f
        val torsoH = headRadius * 4.25f
        // hip.y is derived from a head-clearance constraint, not a fixed fraction of canvas
        // height: the top of her hair (drawMascot's hair circle sits headRadius*1.2 above head
        // center, which itself sits torsoH + headRadius*0.75 above the hip) needs to clear y=0 by
        // a small margin, or clipToBounds slices it off -- see PeekingMascot's history for this
        // exact bug. Solving that inequality gives hip.y >= headRadius*6.2 + margin, independent
        // of how tall this canvas is.
        val hip = Offset(w * 0.5f, headRadius * 6.2f + 8.dp.toPx())

        drawMascot(
            hip = hip,
            headRadius = headRadius,
            leanDeg = 0f,
            expression = MascotExpression.WORKING,
            colors = mascotColors
        )

        // The board is sized and positioned to match where WORKING's bent arms naturally land
        // (mirroring drawMascot's own handsCenter formula) and always extends to the canvas
        // bottom, so everything below her hands -- lower torso, hip, legs -- reads as hidden
        // behind it rather than needing separate cropping logic.
        val torsoTopLeftY = hip.y - torsoH
        val handsCenter = Offset(
            hip.x + headRadius * 2.5f * 0.12f,
            torsoTopLeftY + torsoH * 0.58f
        )

        val boardWidth = w * 0.46f
        val boardTop = handsCenter.y - headRadius * 0.15f
        drawRoundRect(
            color = MascotCounter,
            topLeft = Offset(hip.x - boardWidth / 2f, boardTop),
            size = Size(boardWidth, size.height - boardTop),
            cornerRadius = CornerRadius(6.dp.toPx())
        )
        drawLine(
            color = MascotCounter.copy(alpha = 0.7f),
            start = Offset(hip.x - boardWidth / 2f, boardTop),
            end = Offset(hip.x + boardWidth / 2f, boardTop),
            strokeWidth = 2.dp.toPx()
        )

        // Dough, then a rolling pin on top of it -- doughCenter is placed a full half-height below
        // boardTop (plus a hair of margin) specifically so the whole oval sits on the board, not
        // straddling its top edge the way an earlier version did.
        val doughHalfHeight = headRadius * 0.45f
        val doughCenter = Offset(handsCenter.x, boardTop + doughHalfHeight + 2.dp.toPx())
        drawOval(
            color = MascotDough,
            topLeft = doughCenter - Offset(headRadius * 1.0f, doughHalfHeight),
            size = Size(headRadius * 2.0f, doughHalfHeight * 2)
        )
        // Narrower than the dough oval (half-width < the oval's own headRadius*1.0) and thin, so
        // it reads as a slim cylinder resting on top of a continuous piece of dough rather than a
        // wide bar bisecting it into two lumps -- its ends also land just past where the hands
        // grip it (handsCenter +/- headRadius*0.4, see drawWorkingArms), like a real pin's handles
        // poking out past the fists wrapped around its barrel. Centered on handsCenter.y, not
        // doughCenter.y -- the hands terminate at the near edge of the dough oval, well above its
        // vertical middle, so anchoring to doughCenter left the pin sitting below/behind where the
        // hands actually are, visually disconnected from them. A distinct MascotPin color (lighter
        // than MascotCounter) matters too: drawn in the counter's own color, the pin read as a
        // hole punched through the dough rather than an object resting on top of it.
        // Flat ends, not rounded/capped -- an earlier version rounded the rect's ends and then
        // added a slightly-larger circle at each end for a "handle" bump, but the circle's radius
        // exceeded the rect's own corner radius and poked out past it, reading as a small stray
        // knob rather than a clean pin end.
        val pinHalfWidth = headRadius * 0.6f
        val pinHalfHeight = headRadius * 0.15f
        val pinCenter = Offset(doughCenter.x, handsCenter.y)
        drawRect(
            color = MascotPin,
            topLeft = pinCenter - Offset(pinHalfWidth, pinHalfHeight),
            size = Size(pinHalfWidth * 2, pinHalfHeight * 2)
        )

        // Her arms are drawn last, on top of the board/dough/pin -- see drawMascot's WORKING
        // branch and drawWorkingArms's doc: this is what makes her hands read as gripping the
        // dough instead of the board/dough appearing to sit in front of (and bury) her hands.
        drawWorkingArms(hip = hip, headRadius = headRadius, leanDeg = 0f, colors = mascotColors)
    }
}
