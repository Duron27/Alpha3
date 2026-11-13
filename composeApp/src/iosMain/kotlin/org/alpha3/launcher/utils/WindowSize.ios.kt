package org.alpha3.launcher.utils

import androidx.compose.material3.adaptive.currentWindowSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.toSize

@Composable
actual fun currentWindowDpSize(): DpSize = with(LocalDensity.current) { currentWindowSize().toSize().toDpSize() }
