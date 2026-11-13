package org.alpha3.launcher.utils

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowWidthSizeClass
import org.alpha3.launcher.ui.theme.OpenMWTheme

/**
 * The window's size now, in dp. Android reads it from the configuration: LocalWindowInfo.containerSize (what
 * currentWindowSize and currentWindowAdaptiveInfo read) stayed at the landscape size after the launcher was turned back
 * to portrait, so it kept the landscape layout (rail, floating Play) in a portrait window.
 */
@Composable
expect fun currentWindowDpSize(): DpSize

/** [currentWindowDpSize] in pixels. */
@Composable
fun currentWindowPxSize(): IntSize = with(LocalDensity.current) {
    val size = currentWindowDpSize()
    IntSize(size.width.roundToPx(), size.height.roundToPx())
}

@Composable
fun getWindowSize(): DpSize = currentWindowDpSize()

@Composable
fun getOrientation(): Int { // in fact it's dumb XD
    val windowSize = getWindowSize()
    val vertical = windowSize.height > windowSize.width
    return if (vertical) 1 else 0
}

@Composable
fun isCompatHorizontal(): Boolean {
    return getLayoutType() == NavigationSuiteType.NavigationRail && getOrientation() == 0 /* horizontal */
}

@Composable
fun getLayoutType(useDrawer: Boolean = false): NavigationSuiteType {
    return NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfoFix(currentWindowAdaptiveInfo(), useDrawer)
}

/**
 * adaptive navigation calculate
 */
@Composable
fun NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfoFix(adaptiveInfo: WindowAdaptiveInfo, useDrawer: Boolean = false): NavigationSuiteType {
    return with(adaptiveInfo) {
        val windowSize = currentWindowDpSize()
        // the width class from the same size (the adaptive info's own comes from the stale size on Android)
        val widthClass = when {
            windowSize.width < 600.dp -> WindowWidthSizeClass.COMPACT
            windowSize.width < 840.dp -> WindowWidthSizeClass.MEDIUM
            else -> WindowWidthSizeClass.EXPANDED
        }
        val vertical = windowSize.height > windowSize.width
        val horizontal = windowSize.height <= windowSize.width
        if ( widthClass == WindowWidthSizeClass.EXPANDED &&
            windowSize.width > 1200.dp) { // 1200dp+ we use Drawer
            if (useDrawer) NavigationSuiteType.NavigationDrawer else NavigationSuiteType.NavigationRail
        } else if (windowPosture.isTabletop) {
            NavigationSuiteType.NavigationBar
        } else if (
            widthClass == WindowWidthSizeClass.EXPANDED ||
            widthClass == WindowWidthSizeClass.MEDIUM ||
            (widthClass == WindowWidthSizeClass.COMPACT
                    && horizontal) // phone + landscape we use rail
        ) {
            NavigationSuiteType.NavigationRail
        } else {
            NavigationSuiteType.NavigationBar
        }
    }
}


/*@Composable
fun GetAdaptiveRowItemCount(adaptiveInfo: WindowAdaptiveInfo, windowSize: DpSize) : Int {
    LazyVerticalGrid() { }
    GridCells.Adaptive

    val widthSize = adaptiveInfo.widthClass
    if (widthSize == WindowWidthSizeClass.EXPANDED)
}*/


fun Density.calculateRowItemCount(availableSize: Int, spacing: Dp, minSize: Dp) : Int {
    val count = maxOf((availableSize + spacing.roundToPx()) / (minSize.roundToPx() + spacing.roundToPx()), 1)
    return count
}

@Composable
fun getListAdaptiveHorizontalPadding(
    normalPadding: Dp = OpenMWTheme.spacing.normal
): PaddingValues {
    val layoutType = getLayoutType()
    val windowSize = getWindowSize()
    val isWideScreen = layoutType == NavigationSuiteType.NavigationRail || layoutType == NavigationSuiteType.NavigationDrawer
    if (isWideScreen)
        return PaddingValues(horizontal = windowSize.width / 9)
    else
        return PaddingValues(0.dp)
}