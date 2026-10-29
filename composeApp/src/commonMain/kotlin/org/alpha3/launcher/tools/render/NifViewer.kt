package org.alpha3.launcher.tools.render

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
import org.alpha3.launcher.io.File
import org.alpha3.launcher.tools.openmw.ToolFs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs
import kotlin.time.TimeSource
import kotlin.math.sqrt

private val Gold = Color(202, 165, 96)
private val Muted = Color.White.copy(alpha = 0.65f)
private val Red = Color(0xFFE57373)

/** One mesh of the model: drawn with its texture, or plain. */
private class Part(val label: String, val texture: String?, val textureFound: Boolean, val triangles: Int, val textured: SceneMesh, val plain: SceneMesh)

/** A model ready to draw: its parts in model space, and the box they fill. */
private class ViewModel(val parts: List<Part>, val center: FloatArray, val radius: Float, val info: String)

private fun loadModel(file: File, fs: ToolFs, assetDirs: List<String>): ViewModel {
    val model = parseNif(file.readBytes(), includeSkinned = true)
    if (model.meshes.isEmpty()) error(model.error ?: "no renderable geometry in ${file.name}")
    val assets = MwAssets(fs, assetDirs)
    val cache = HashMap<String, Bitmap?>()
    val parts = model.meshes.mapIndexed { i, m ->
        val tex = m.texture?.let { name -> cache.getOrPut(name.lowercase()) { assets.readTexture(name) } }
        val diffuse = if (m.diffuse.all { it == 0f }) floatArrayOf(1f, 1f, 1f) else m.diffuse
        val textured = SceneMesh(m.positions, m.normals, m.uvs, m.colors, m.indices, tex, diffuse, m.emissive, m.alphaBlend, m.alphaTest)
        // Without its texture a mesh is drawn in a neutral grey so its shape stays readable
        val plain = SceneMesh(m.positions, m.normals, null, m.colors, m.indices, null, floatArrayOf(0.75f, 0.75f, 0.75f), m.emissive, false, false)
        Part(m.name.ifBlank { "mesh ${i + 1}" }, m.texture, tex != null, m.indices.size / 3, textured, plain)
    }
    val center = FloatArray(3) { (model.min[it] + model.max[it]) / 2f }
    val dx = model.max[0] - model.min[0]; val dy = model.max[1] - model.min[1]; val dz = model.max[2] - model.min[2]
    val radius = (sqrt(dx * dx + dy * dy + dz * dz) / 2f).coerceAtLeast(1f)
    val textures = parts.mapNotNull { it.texture }.distinct()
    val missing = textures.count { t -> parts.first { it.texture == t }.textureFound.not() }
    val info = "${parts.size} meshes, ${parts.sumOf { it.triangles }} triangles, ${textures.size} textures" + (if (missing > 0) " ($missing not found)" else "") + model.error?.let { " - $it" }.orEmpty()
    return ViewModel(parts, center, radius, info)
}

/** Reads a texture in any format Morrowind mods ship. */
private fun loadImage(file: File): Bitmap? {
    val bytes = file.readBytes()
    return when (file.extension.lowercase()) {
        "dds" -> decodeDds(bytes)
        "tga" -> decodeTga(bytes)
        "bmp" -> decodeBmp(bytes)
        else -> decodeEncodedImage(bytes)
    }
}

/**
 * Opens a mesh (.nif) as a 3D model you can turn with the mouse, or a texture as an image. Textures and
 * parts a mesh refers to are looked up in [assetDirs] (mod folders, then the game's own data folders).
 * Beside a model, every mesh and texture file it uses has its own switch, applied live.
 */
@Composable
fun AssetViewerDialog(file: File, fs: ToolFs, assetDirs: List<String>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AssetViewerContent(file, fs, assetDirs, onDismiss, Modifier.fillMaxSize().padding(8.dp))
    }
}

/** The viewer itself, without the dialog window around it. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AssetViewerContent(file: File, fs: ToolFs, assetDirs: List<String>, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val isMesh = file.extension.lowercase() in setOf("nif")
    var model by remember(file) { mutableStateOf<ViewModel?>(null) }
    var image by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    var imageInfo by remember(file) { mutableStateOf("") }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var yaw by remember { mutableFloatStateOf(0.6f) }
    var pitch by remember { mutableFloatStateOf(0.35f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var spin by remember { mutableStateOf(true) }
    var brightness by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var panZ by remember { mutableFloatStateOf(0f) }
    var info by remember { mutableStateOf("") }
    var texturedAll by remember { mutableStateOf(true) }
    // Per mesh: shown?  Per texture file: used?  Both start on, and take effect on the next frame.
    val shown = remember(file) { mutableStateListOf<Boolean>() }
    val textureOff = remember(file) { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            try {
                if (isMesh) {
                    val m = loadModel(file, fs, assetDirs)
                    shown.clear(); shown.addAll(List(m.parts.size) { true })
                    model = m
                    info = m.info
                } else {
                    val img = loadImage(file) ?: error("cannot read ${file.name}")
                    image = argbToImageBitmap(img.width, img.height, img.levels[0].third.let { px -> IntArray(px.size) { px[it] } })
                    imageInfo = "${img.width} x ${img.height}"
                }
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            }
        }
    }

    LaunchedEffect(model, size) {
        val m = model ?: return@LaunchedEffect
        if (size.width < 16 || size.height < 16) return@LaunchedEffect
        val scale = minOf(1f, 640f / maxOf(size.width, size.height))
        val w = (size.width * scale).toInt().coerceAtLeast(64)
        val h = (size.height * scale).toInt().coerceAtLeast(64)
        val renderer = MwRenderer(w, h)
        val presenter = FramePresenter(w, h)
        val r = m.radius
        var t = 0f
        var last = TimeSource.Monotonic.markNow()
        while (true) {
            val now = TimeSource.Monotonic.markNow()
            val dt = ((now - last).inWholeMicroseconds / 1e6f).coerceAtMost(0.1f)
            last = now
            t += dt
            if (spin) yaw += dt * 0.5f
            val y = yaw; val p = pitch; val z = zoom; val b = brightness
            val target = floatArrayOf(m.center[0] + panX, m.center[1] + panY, m.center[2] + panZ)
            // Lights ride just behind the camera, so the model is lit from the side you are looking at and
            // the lights themselves (which the renderer draws as glare) stay out of the picture.
            val sy = sin(y); val cy = cos(y); val sp = sin(p); val cp = cos(p)
            val d = r * 2.3f / z
            val camX = target[0] + d * cp * sy; val camY = target[1] - d * cp * cy; val camZ = target[2] + d * sp
            val ox = cp * sy; val oy = -cp * cy; val oz = sp          // from the model toward the camera
            val rx = cy; val ry = sy                                   // screen right
            val ux = -sy * sp; val uy = cy * sp; val uz = cp           // screen up
            val back = r * 0.4f
            val lit = listOf(
                RenderLight(camX + ox * back - rx * r * 0.7f + ux * r * 0.6f, camY + oy * back - ry * r * 0.7f + uy * r * 0.6f, camZ + oz * back + uz * r * 0.6f, 1.25f * b, 1.15f * b, 1.0f * b, r * 14f),
                RenderLight(camX + ox * back + rx * r * 0.9f - ux * r * 0.2f, camY + oy * back + ry * r * 0.9f - uy * r * 0.2f, camZ + oz * back - uz * r * 0.2f, 0.35f * b, 0.4f * b, 0.55f * b, r * 14f),
            )
            // the parts to draw right now, with or without their textures
            val globalTex = texturedAll
            val meshes = m.parts.indices.filter { shown.getOrElse(it) { true } }.map { i ->
                val part = m.parts[i]
                val useTexture = globalTex && part.texture?.let { name -> textureOff[name.lowercase()] != true } != false
                if (useTexture) part.textured else part.plain
            }
            frame = withContext(Dispatchers.Default) {
                val params = RenderParams(
                    t, y, p, r * 2.3f / z, target, lit, floatArrayOf(0.45f * b, 0.45f * b, 0.5f * b),
                    floatArrayOf(0.05f, 0.05f, 0.06f), 0f, false, false
                )
                renderer.render(meshes, params)
                presenter.present(renderer.pixels)
            }
            // paced by the display instead of a fixed wait on top of the render time
            withFrameNanos { }
        }
    }

    run {
        Card(
            modifier = modifier.border(2.dp, Gold, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF12151A))
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(file.name, color = Gold, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(if (isMesh) info.ifEmpty { "Loading model..." } else imageInfo, color = Muted, fontSize = 11.sp)
                    }
                    if (isMesh) {
                        SmallButton(if (spin) "Stop spinning" else "Spin") { spin = !spin }
                        SmallButton("Reset view") { yaw = 0.6f; pitch = 0.35f; zoom = 1f; panX = 0f; panY = 0f; panZ = 0f; brightness = 1f }
                    }
                    Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A8A), contentColor = Color.White), shape = RoundedCornerShape(6.dp)) {
                        Text("Close", fontSize = 12.sp)
                    }
                }
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                    val compact = maxWidth < 720.dp
                    val viewport: @Composable (Modifier) -> Unit = { vm ->
                        Box(
                            modifier = vm.background(Color(0xFF0C0E12), RoundedCornerShape(8.dp))
                                .onSizeChanged { size = it }
                                .then(
                                    if (isMesh) Modifier
                                        .pointerInput(Unit) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    val ev = awaitPointerEvent()
                                                    if (ev.type == PointerEventType.Scroll) {
                                                        // mouse wheel: toward you zooms out, away zooms in
                                                        val c = ev.changes.firstOrNull() ?: continue
                                                        zoom = (zoom * (1f - c.scrollDelta.y * 0.12f)).coerceIn(0.2f, 12f)
                                                        c.consume()
                                                        continue
                                                    }
                                                    if (ev.type != PointerEventType.Move) continue
                                                    val down = ev.changes.filter { it.pressed }
                                                    if (down.isEmpty()) continue
                                                    spin = false
                                                    var pdx = 0f; var pdy = 0f
                                                    if (down.size >= 2) {
                                                        // two fingers: pinch to zoom, drag to move the view point
                                                        val a = down[0]; val b = down[1]
                                                        val now = (a.position - b.position).getDistance()
                                                        val before = (a.previousPosition - b.previousPosition).getDistance()
                                                        if (before > 8f && now > 8f) zoom = (zoom * now / before).coerceIn(0.2f, 12f)
                                                        pdx = ((a.position.x - a.previousPosition.x) + (b.position.x - b.previousPosition.x)) / 2f
                                                        pdy = ((a.position.y - a.previousPosition.y) + (b.position.y - b.previousPosition.y)) / 2f
                                                    } else if (ev.buttons.isSecondaryPressed || ev.buttons.isTertiaryPressed) {
                                                        pdx = down[0].position.x - down[0].previousPosition.x
                                                        pdy = down[0].position.y - down[0].previousPosition.y
                                                    } else {
                                                        yaw += (down[0].position.x - down[0].previousPosition.x) * 0.008f
                                                        pitch = (pitch + (down[0].position.y - down[0].previousPosition.y) * 0.006f).coerceIn(-1.4f, 1.4f)
                                                    }
                                                    if (pdx != 0f || pdy != 0f) {
                                                        // move the view point in the plane of the screen, by the distance the fingers moved
                                                        val sy = sin(yaw); val cy = cos(yaw); val sp = sin(pitch); val cp = cos(pitch)
                                                        val scale = (model?.radius ?: 100f) * 2.3f / zoom * 1.1547f / size.height.coerceAtLeast(1)
                                                        panX += (-cy * pdx + -sy * sp * pdy) * scale
                                                        panY += (-sy * pdx + cy * sp * pdy) * scale
                                                        panZ += (cp * pdy) * scale
                                                    }
                                                    down.forEach { it.consume() }
                                                }
                                            }
                                        }
                                    else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            val e = error
                            val f = frame
                            val img = image
                            when {
                                e != null -> Text("Cannot show this file: $e", color = Red, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                                isMesh && f != null -> Image(f, file.name, contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Medium, modifier = Modifier.fillMaxSize())
                                !isMesh && img != null -> Image(img, file.name, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize().padding(8.dp))
                                else -> Text("Loading...", color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                    }
                    val side: @Composable (Modifier) -> Unit = { pm ->
                        val m = model
                        if (isMesh && m != null) {
                            // Switches for the files involved
                            Column(modifier = pm.background(Color(0xFF1B1E24), RoundedCornerShape(8.dp)).padding(10.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Show", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Switch("Textures on", texturedAll) { texturedAll = it }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SmallButton("All on") { for (i in shown.indices) shown[i] = true; textureOff.clear(); texturedAll = true }
                                    SmallButton("All off") { for (i in shown.indices) shown[i] = false }
                                }
                                Text("Meshes in ${file.name}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                                m.parts.forEachIndexed { i, part ->
                                    Switch("${part.label} (${part.triangles} tris)", shown.getOrElse(i) { true }) { if (i < shown.size) shown[i] = it }
                                }
                                val textures = m.parts.mapNotNull { p -> p.texture?.let { it to p.textureFound } }.distinctBy { it.first.lowercase() }
                                if (textures.isNotEmpty()) {
                                    Text("Texture files", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                                    for ((name, found) in textures) {
                                        Switch(name.substringAfterLast('\\').substringAfterLast('/') + if (found) "" else "  (not found)", textureOff[name.lowercase()] != true, enabled = found, error = !found) { textureOff[name.lowercase()] = !it }
                                    }
                                }
                            }
                        }
                
                    }
                    if (compact) {
                        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            viewport(Modifier.weight(1f).fillMaxWidth())
                            side(Modifier.fillMaxWidth().height(150.dp))
                        }
                    } else {
                        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            viewport(Modifier.weight(1f).fillMaxHeight())
                            side(Modifier.width(260.dp).fillMaxHeight())
                        }
                    }
                }
                if (isMesh) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Text("Brightness", color = Color.White, fontSize = 12.sp)
                        Slider(
                            value = brightness, onValueChange = { brightness = it }, valueRange = 0.2f..8f,
                            colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
                            modifier = Modifier.weight(1f)
                        )
                        Text("${(brightness * 10).toInt() / 10f}x", color = Gold, fontSize = 12.sp, modifier = Modifier.width(44.dp))
                        SmallButton("-") { zoom = (zoom / 1.25f).coerceAtLeast(0.2f) }
                        Text("zoom", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                        SmallButton("+") { zoom = (zoom * 1.25f).coerceAtMost(12f) }
                    }
                    Text("Drag to turn, pinch or scroll to zoom, two fingers (or right drag) to move. Textures come from the mod folders and the game's data folders.", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick, shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A), contentColor = Gold),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    ) { Text(label, fontSize = 12.sp) }
}

@Composable
private fun Switch(label: String, checked: Boolean, enabled: Boolean = true, error: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = Gold, uncheckedColor = Muted, checkmarkColor = Color.Black))
        Text(label, color = if (error) Red else Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
