package org.alpha3.launcher.tools.render

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.alpha3.launcher.tools.openmw.ToolFs
import kotlin.math.sin

/** What the light settings do to the two light kinds in the preview, as lightconfig.toml has them. */
internal class LightPreviewSettings(
    val standardHue: Float, val standardSat: Float, val standardVal: Float, val standardRadius: Float,
    val coloredHue: Float, val coloredSat: Float, val coloredVal: Float, val coloredRadius: Float,
    val durationMult: Float, val disablePulse: Boolean, val disableFlicker: Boolean,
    val rain: Boolean, val puddles: Boolean, val rainAmount: Float,
)

private const val LIGHT_GAIN = 2.4f

/**
 * Previews the light settings on real Morrowind objects (read from the game's BSA archives and data folders),
 * lit the way the game lights them: radius-limited point lights with a configurable colour.
 * Drag to orbit the camera, pinch or scroll to zoom.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LightPreview(settings: LightPreviewSettings, fs: ToolFs, dataDirs: List<String>, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(settings)
    var sceneKind by remember { mutableStateOf(PreviewScene.NIGHT_WATCH) }
    var loaded by remember { mutableStateOf<LoadedScene?>(null) }
    var status by remember { mutableStateOf("Loading Morrowind objects...") }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var yawDelta by remember { mutableFloatStateOf(0f) }
    var pitchDelta by remember { mutableFloatStateOf(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val assets = remember(dataDirs) { arrayOfNulls<MwAssets>(1) }

    LaunchedEffect(dataDirs, sceneKind) {
        loaded = null
        yawDelta = 0f; pitchDelta = 0f; zoom = 1f
        if (dataDirs.isEmpty()) {
            status = "Morrowind data files (Morrowind.bsa) were not found.\nSet the Morrowind folder in the launcher to see the preview."
            return@LaunchedEffect
        }
        status = "Loading Morrowind objects..."
        loaded = withContext(Dispatchers.Default) {
            val a = assets[0] ?: MwAssets(fs, dataDirs).also { assets[0] = it }
            runCatching { loadPreviewScene(a, sceneKind) }.getOrNull()
        }
        if (loaded == null) status = "Could not load the preview scene."
    }

    LaunchedEffect(loaded, size) {
        val scene = loaded ?: return@LaunchedEffect
        if (size.width < 8 || size.height < 8) return@LaunchedEffect
        val scale = minOf(1f, 560f / size.width)
        val w = (size.width * scale).toInt().coerceAtLeast(64)
        val h = (size.height * scale).toInt().coerceAtLeast(64)
        val renderer = MwRenderer(w, h)
        val presenter = FramePresenter(w, h)
        val startTime = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val s = current
            val seconds = (now - startTime) / 1_000_000_000f
            val t = seconds * s.durationMult
            val yd = yawDelta; val pd = pitchDelta; val zm = zoom
            frame = withContext(Dispatchers.Default) {
                scene.actor?.update(seconds)
                renderer.render(scene.meshes, buildParams(scene, s, t, yd, pd, zm, seconds))
                presenter.present(renderer.pixels)
            }
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, z, _ ->
                    yawDelta += pan.x * 0.006f
                    pitchDelta = (pitchDelta + pan.y * 0.005f).coerceIn(-0.6f, 0.9f)
                    zoom = (zoom * z).coerceIn(0.5f, 2.5f)
                }
            }
    ) {
        val img = frame
        if (loaded != null && img != null) {
            Image(
                bitmap = img, contentDescription = "Light preview", contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.Medium, modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(status, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center).padding(16.dp))
        }

        FlowRow(
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (kind in PreviewScene.entries) {
                val selected = kind == sceneKind
                Text(
                    text = kind.label,
                    color = if (selected) Color.Black else Color(202, 165, 96),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(if (selected) Color(202, 165, 96) else Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .clickable { sceneKind = kind }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        val missing = loaded?.missing.orEmpty()
        if (missing.isNotEmpty()) {
            Text(
                "Missing: ${missing.joinToString { it.substringAfterLast('/') }}",
                color = Color(0xFFE57373), fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            )
        }
    }
}

private fun buildParams(scene: LoadedScene, s: LightPreviewSettings, t: Float, yawDelta: Float, pitchDelta: Float, zoom: Float, seconds: Float): RenderParams {
    val lights = scene.lights.mapIndexed { i, l ->
        if (l.fire) {
            // a real fire: fixed warm color and its own flicker, whatever the HSV settings say
            val k = 1f + 0.12f * sin(seconds * 9.7f + i) + 0.08f * sin(seconds * 17.3f + i * 2f) + 0.05f * sin(seconds * 3.9f)
            return@mapIndexed RenderLight(l.x, l.y, l.z, 1.0f * 2.3f * k, 0.52f * 2.3f * k, 0.18f * 2.3f * k, l.baseRadius, true, l.flameSize)
        }
        val c = if (l.colored) Color.hsv((s.coloredHue * 360f) % 360f, s.coloredSat.coerceIn(0f, 1f), s.coloredVal.coerceIn(0f, 1f))
        else Color.hsv((s.standardHue * 360f) % 360f, s.standardSat.coerceIn(0f, 1f), s.standardVal.coerceIn(0f, 1f))
        val radius = l.baseRadius * (if (l.colored) s.coloredRadius else s.standardRadius)
        val phase = i * 1.7f
        var k = 1f
        if (!s.disableFlicker) k *= 1f + 0.10f * sin(t * 13.1f + phase) + 0.07f * sin(t * 23.7f + phase * 2f) + 0.05f * sin(t * 5.3f)
        if (!s.disablePulse) k *= 1f + 0.22f * sin(t * 2.1f + phase)
        RenderLight(l.x, l.y, l.z, c.red * LIGHT_GAIN * k, c.green * LIGHT_GAIN * k, c.blue * LIGHT_GAIN * k, radius)
    }
    val allLights = scene.actor?.let { g ->
        // the guard's torch is an ordinary fire: fixed warm color, always flickering a little
        val k = 1f + 0.10f * sin(seconds * 11.3f) + 0.07f * sin(seconds * 19.1f + 1f) + 0.05f * sin(seconds * 4.7f)
        lights + RenderLight(g.torchX, g.torchY, g.torchZ, 1.0f * 2.1f * k, 0.52f * 2.1f * k, 0.18f * 2.1f * k, 340f, true, 1f)
    } ?: lights
    return RenderParams(
        t, scene.yaw + yawDelta, (scene.pitch + pitchDelta).coerceIn(-0.2f, 1.2f), scene.dist / zoom,
        scene.target, allLights, scene.ambient, scene.fog, scene.fogDensity, s.rain, s.puddles, s.rainAmount,
        scene.actor?.let { listOf(ShadowCaster(it.footX, it.footY, 135f, 22f)) } ?: emptyList()
    )
}
