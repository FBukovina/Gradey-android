package com.bukovinafilip.gradey.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Height of the floating tab bar at the current text scale. */
@Composable
fun floatingNavigationBarHeight(): Dp = with(LocalDensity.current) {
    // Android's non-linear font scaling must be evaluated at the label's actual line size.
    if (fontScale > 1.15f) 44.dp + 13.sp.toDp() * 3 else maxOf(64.dp, 36.dp + 13.sp.toDp() * 2)
}

/** Gap between the tab bar and the bottom system inset. */
val FloatingNavigationBottomMargin = 12.dp

/** Reserve the floating tab bar, system navigation inset, and breathing room at any text scale. */
@Composable
fun floatingNavigationContentPadding(): Dp {
    val navigationInset = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    return floatingNavigationBarHeight() + FloatingNavigationBottomMargin + navigationInset + 20.dp
}
