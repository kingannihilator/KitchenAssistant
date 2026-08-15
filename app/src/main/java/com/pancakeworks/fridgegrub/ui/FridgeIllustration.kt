package com.pancakeworks.fridgegrub.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Fixed structural colors for [drawOpenFridge] -- callers pull these from the Material theme so
 * the illustration follows light/dark mode, same as the loading screen's original inline version. */
data class FridgeColors(
    val top: Color,
    val side: Color,
    val interior: Color,
    val outline: Color,
    val shelf: Color,
    val handle: Color
)

/**
 * Draws an open-door fridge (top face + right-side face, flat-shaded, plus an open interior with
 * two shelves) -- extracted from the loading screen's original inline version so the empty-fridge
 * state can reuse the same illustration. [anchorBottomLeft] is the fridge's front-bottom-left
 * corner, where the door hinges; [width]/[height] size the whole box. Up to three [foodEmoji] are
 * placed on the shelves (two on top, one on the bottom); pass an empty list to show bare shelves.
 */
fun DrawScope.drawOpenFridge(
    textMeasurer: TextMeasurer,
    anchorBottomLeft: Offset,
    width: Float,
    height: Float,
    colors: FridgeColors,
    foodEmoji: List<String> = emptyList(),
    emojiFontSize: TextUnit = 15.sp
) {
    val depthDx = width * 0.385f
    val depthDy = height * 0.148f

    val fbl = anchorBottomLeft
    val fbr = Offset(fbl.x + width, fbl.y - height * 0.034f)
    val ftl = Offset(fbl.x, fbl.y - height)
    val ftr = Offset(fbr.x, fbr.y - height)
    val btl = Offset(ftl.x + depthDx, ftl.y - depthDy)
    val btr = Offset(ftr.x + depthDx, ftr.y - depthDy)
    val bbr = Offset(fbr.x + depthDx, fbr.y - depthDy)

    val topFace = Path().apply {
        moveTo(ftl.x, ftl.y); lineTo(ftr.x, ftr.y); lineTo(btr.x, btr.y); lineTo(btl.x, btl.y); close()
    }
    val sideFace = Path().apply {
        moveTo(fbr.x, fbr.y); lineTo(ftr.x, ftr.y); lineTo(btr.x, btr.y); lineTo(bbr.x, bbr.y); close()
    }
    drawPath(topFace, color = colors.top)
    drawPath(topFace, color = colors.outline, style = Stroke(width = 1.5.dp.toPx()))
    drawPath(sideFace, color = colors.side)
    drawPath(sideFace, color = colors.outline, style = Stroke(width = 1.5.dp.toPx()))

    // Open interior: an inset, recessed rectangle with shelves and (optionally) food.
    val il = ftl.x + width * 0.10f
    val ir = ftr.x - width * 0.06f
    val itop = ftl.y + height * 0.08f
    val ibottom = fbl.y - height * 0.05f
    drawRoundRect(
        color = colors.interior,
        topLeft = Offset(il, itop),
        size = Size(ir - il, ibottom - itop),
        cornerRadius = CornerRadius(4.dp.toPx())
    )
    val shelfY1 = itop + (ibottom - itop) * 0.42f
    val shelfY2 = itop + (ibottom - itop) * 0.74f
    for (shelfY in listOf(shelfY1, shelfY2)) {
        drawLine(
            color = colors.shelf,
            start = Offset(il + 2.dp.toPx(), shelfY),
            end = Offset(ir - 2.dp.toPx(), shelfY),
            strokeWidth = 2.dp.toPx()
        )
    }
    val foodSpots = listOf(
        Offset(il + (ir - il) * 0.26f, shelfY1),
        Offset(il + (ir - il) * 0.62f, shelfY1),
        Offset(il + (ir - il) * 0.42f, shelfY2)
    )
    val foodStyle = TextStyle(fontSize = emojiFontSize)
    foodEmoji.take(3).forEachIndexed { index, emoji ->
        val emojiLayout = textMeasurer.measure(emoji, style = foodStyle)
        val anchor = foodSpots[index]
        drawText(
            emojiLayout,
            topLeft = Offset(anchor.x - emojiLayout.size.width / 2f, anchor.y - emojiLayout.size.height - 1.dp.toPx())
        )
    }
    drawRoundRect(
        color = colors.outline,
        topLeft = Offset(il, itop),
        size = Size(ir - il, ibottom - itop),
        cornerRadius = CornerRadius(4.dp.toPx()),
        style = Stroke(width = 1.dp.toPx())
    )

    // Door, hinged at the fridge's front-left edge (ftl-fbl), swung open to the left.
    val doorW = width * 0.60f
    val doorLean = height * 0.046f
    val dft = Offset(ftl.x - doorW, ftl.y - doorLean)
    val dfb = Offset(fbl.x - doorW, fbl.y - doorLean)
    val doorPath = Path().apply {
        moveTo(ftl.x, ftl.y); lineTo(dft.x, dft.y); lineTo(dfb.x, dfb.y); lineTo(fbl.x, fbl.y); close()
    }
    drawPath(doorPath, color = colors.side)
    drawPath(doorPath, color = colors.outline, style = Stroke(width = 1.5.dp.toPx()))
    drawLine(
        color = colors.handle,
        start = Offset(dft.x + 5.dp.toPx(), dft.y + (dfb.y - dft.y) * 0.28f),
        end = Offset(dft.x + 5.dp.toPx(), dft.y + (dfb.y - dft.y) * 0.72f),
        strokeWidth = 3.dp.toPx(),
        cap = StrokeCap.Round
    )
}
