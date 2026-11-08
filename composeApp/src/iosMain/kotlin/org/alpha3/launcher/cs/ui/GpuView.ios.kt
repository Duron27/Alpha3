package org.alpha3.launcher.cs.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal actual val gpuCellViewAvailable: Boolean = false

@Composable
internal actual fun GpuCellView(frame: () -> GpuFrame?, continuous: Boolean, dirty: Any, modifier: Modifier) {}
