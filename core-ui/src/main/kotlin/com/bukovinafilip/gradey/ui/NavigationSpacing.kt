package com.bukovinafilip.gradey.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Reserve the floating tab bar, system navigation inset, and breathing room at any text scale. */
@Composable
fun floatingNavigationContentPadding(): Dp {
    val density = LocalDensity.current
    val barHeight = if (density.fontScale > 1.15f) with(density) { 48.dp + 14.sp.toDp() * 3 } else maxOf(62.dp, with(density) { 34.dp + 14.sp.toDp() * 2 })
    val navigationInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    return maxOf(126.dp, barHeight + navigationInset + 16.dp)
}
