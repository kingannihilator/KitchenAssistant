package com.pancakeworks.fridgegrub.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

/**
 * A small cameo of the mascot (see ui/Mascot.kt) -- just head and shoulders, cropped by
 * [clipToBounds] rather than a full standing figure, so it reads as "she's looking in on this"
 * without competing for space with real content. Purely decorative: not clickable, no content
 * description needed beyond what the screen around it already says.
 *
 * Two placements so far: a bottom corner of the screen (the favorites list, the recipe results
 * list), and centered under the walkthrough's heading in ui/OnboardingScreen.kt's banner, where
 * the crop line lands on the pager's top edge so she reads as rising up behind the slides.
 *
 * [leanTowardCenter] tilts her head slightly inward (left when she's in the bottom-right corner,
 * right when she's in the bottom-left) so she visibly looks toward the screen's content rather
 * than away from it. A centered caller has no such direction; either value is defensible there,
 * and the flag just picks between the two fixed tilts.
 *
 * The default 92x120dp is not arbitrary and callers resizing her must keep that 92:120 ratio: the
 * hip/headRadius constants below are calibrated against it so the top of her hair clears the box's
 * top edge with a small margin. Scaling both dimensions off that ratio (e.g. 66x86dp) just draws
 * her smaller; changing the ratio alone slices her hair off at the crown, since everything in the
 * drawing is derived from the measured width.
 */
@Composable
fun PeekingMascot(
    expression: MascotExpression,
    leanTowardCenter: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = MascotColors()
    Canvas(
        modifier = modifier
            .size(width = 92.dp, height = 120.dp)
            .clipToBounds()
    ) {
        val w = size.width
        val h = size.height
        // The hip sits well below the box's own bottom edge, so only the head and a hint of
        // shoulder actually land inside the visible (clipped) area -- torso and legs are drawn,
        // they just never get painted since clipToBounds crops them away.
        //
        // headRadius/hip.y are chosen so the top of her hair (drawMascot's hair circle sits
        // headRadius*1.2 above the head's own center) clears y=0 with a small margin -- an
        // earlier version placed the head too close to the top edge and clipToBounds sliced off
        // her hair/forehead.
        drawMascot(
            hip = Offset(w * 0.55f, h * 1.30f),
            headRadius = w * 0.26f,
            leanDeg = if (leanTowardCenter) -10f else 10f,
            expression = expression,
            colors = colors
        )
    }
}
