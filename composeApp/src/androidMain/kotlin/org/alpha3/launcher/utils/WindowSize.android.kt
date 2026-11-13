package org.alpha3.launcher.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

// The configuration follows every rotation (MainActivity handles orientation itself); LocalWindowInfo.containerSize
// stayed at the landscape size after turning back to portrait, so the launcher kept its landscape layout.
@Composable
actual fun currentWindowDpSize(): DpSize {
    val configuration = LocalConfiguration.current
    return DpSize(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp)
}
