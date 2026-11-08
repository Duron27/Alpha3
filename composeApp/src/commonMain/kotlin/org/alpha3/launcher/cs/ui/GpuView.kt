package org.alpha3.launcher.cs.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.alpha3.launcher.tools.render.RenderLight
import org.alpha3.launcher.tools.render.SceneMesh

/** One mesh to draw; [highlight] tints it as the selection. */
internal class GpuItem(val mesh: SceneMesh, val highlight: Boolean)

/** Everything one frame of the cell view needs. [water]: height of a water surface, or null for none. */
internal class GpuFrame(
    val items: List<GpuItem>,
    val camera: OrbitCamera,
    val lights: List<RenderLight>,
    val ambient: FloatArray,
    val clear: FloatArray,
    val water: Float?,
    val waterExtent: Pair<FloatArray, Float>,
    val time: Float,
)

/** Whether this platform draws the cell view on the GPU (otherwise the software renderer does). */
internal expect val gpuCellViewAvailable: Boolean

/**
 * The cell view drawn on the GPU. [frame] is called on the render thread for each frame it draws: every display
 * frame when [continuous], otherwise whenever [dirty] changes.
 */
@Composable
internal expect fun GpuCellView(frame: () -> GpuFrame?, continuous: Boolean, dirty: Any, modifier: Modifier)
