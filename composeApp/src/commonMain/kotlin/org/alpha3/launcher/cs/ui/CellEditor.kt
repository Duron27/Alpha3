package org.alpha3.launcher.cs.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Dialog
import kotlin.math.sqrt
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.size
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.alpha3.launcher.cs.model.CellRef
import org.alpha3.launcher.cs.model.CellView
import org.alpha3.launcher.cs.model.FLAG_PERSISTENT
import org.alpha3.launcher.cs.model.PLACEABLE
import org.alpha3.launcher.cs.model.RECORD_DEFS
import org.alpha3.launcher.cs.model.RecordRow
import org.alpha3.launcher.cs.model.RecordState
import org.alpha3.launcher.cs.model.RefKey
import org.alpha3.launcher.cs.model.addRef
import org.alpha3.launcher.cs.model.blankSub
import org.alpha3.launcher.cs.model.cellView
import org.alpha3.launcher.cs.model.deleteRef
import org.alpha3.launcher.cs.model.placeable
import org.alpha3.launcher.cs.model.revertRef
import org.alpha3.launcher.cs.model.setCellHeader
import org.alpha3.launcher.cs.model.setRef
import org.alpha3.launcher.cs.model.withPlacement
import org.alpha3.launcher.tools.openmw.Sub
import org.alpha3.launcher.tools.render.FramePresenter
import org.alpha3.launcher.tools.render.MwRenderer
import org.alpha3.launcher.tools.render.RenderLight
import org.alpha3.launcher.tools.render.RenderParams
import org.alpha3.launcher.tools.render.SceneMesh
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.TimeSource

private enum class Tool(val label: String) { View("Look"), Move("Move"), Rotate("Turn"), Raise("Raise") }

/** Subrecords of a reference the inspector shows as fields of their own (the rest are edited as cards). */
private val PLACEMENT_TAGS = setOf("FRMR", "NAME", "DATA", "XSCL", "DELE", "MVRF", "CNDT")
private val EXTRA_REF_TAGS = listOf("UNAM", "ANAM", "BNAM", "XSOL", "CNAM", "INDX", "XCHG", "INTV", "NAM9", "DODT", "DNAM", "FLTV", "KNAM", "TNAM")
private val HEADER_TAGS = listOf("NAME", "DATA", "WHGT", "INTV", "AMBI", "RGNN", "NAM5")

private class Frame(val meshes: List<SceneMesh>, val lights: List<RenderLight>)

/**
 * A cell: its objects drawn in 3D (tap one to select it; Move, Turn and Raise drag the selection), the list of its
 * references, and the selected reference's position, rotation, scale and other data. Edits go into the plugin as
 * overrides of the masters' references; the masters themselves never change.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CellEditor(s: CsSession, row: RecordRow) {
    val doc = s.doc
    val result = remember(s.version, row.id) { runCatching { doc.cellView(row) } }
    val view = result.getOrNull()
    if (view == null) {
        Text("Cannot read this cell: ${result.exceptionOrNull()?.message}", color = Color(0xFFE57373), modifier = Modifier.padding(16.dp))
        return
    }
    var selected by remember(row.id) { s.cellSelection.getOrPut(row.id) { mutableStateOf(null) } }
    var full by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf(Tool.View) }
    var headerOpen by remember { mutableStateOf(false) }
    var placing by remember { mutableStateOf(false) }
    // the camera, set to frame the cell once its objects are placed
    var yaw by remember(row.id) { mutableFloatStateOf(0.6f) }
    var pitch by remember(row.id) { mutableFloatStateOf(0.7f) }
    var dist by remember(row.id) { mutableFloatStateOf(1500f) }
    var target by remember(row.id) { mutableStateOf(floatArrayOf(0f, 0f, 0f)) }
    var framed by remember(row.id) { mutableStateOf(false) }
    var editorLight by remember { mutableStateOf(true) }
    // live: drawn every frame, lights flicker and pulse as in the game, water moves
    var live by remember { mutableStateOf(false) }
    // a reference being dragged: drawn at this position until the finger lifts, then saved
    var drag by remember { mutableStateOf<Pair<RefKey, FloatArray>?>(null) }

    val cache = s.scene
    val placed = remember(row.id) { HashMap<RefKey, PlacedRef>() }
    var scene by remember(row.id) { mutableStateOf<List<PlacedRef>>(emptyList()) }
    var loading by remember(row.id) { mutableStateOf(true) }

    LaunchedEffect(view) {
        loading = true
        scene = withContext(Dispatchers.Default) {
            val live = view.refs.filter { it.state != RecordState.Deleted }
            val next = live.map { r -> placeRef(cache, doc, r, r.position, r.scale, placed[r.key]) }
            placed.clear(); next.forEach { placed[it.key] = it }
            next
        }
        loading = false
        if (!framed) {
            val (c, r) = sceneBounds(scene)
            target = c; dist = r * 1.7f; pitch = if (view.interior) 0.75f else 0.45f
            framed = true
        }
    }

    val selectedRef = view.refs.firstOrNull { it.key == selected }
    fun commitPlacement(ref: CellRef, pos: FloatArray, scale: Float, key: String? = null) {
        doc.setRef(row, ref, withPlacement(ref.subs, pos, scale), mergeKey = key)
        s.changed()
    }
    fun focus(ref: CellRef) {
        val p = placed[ref.key]
        target = if (p != null) floatArrayOf((p.min[0] + p.max[0]) / 2, (p.min[1] + p.max[1]) / 2, (p.min[2] + p.max[2]) / 2) else ref.position.copyOf(3)
        val r = p?.let { maxOf(it.max[0] - it.min[0], it.max[1] - it.min[1], it.max[2] - it.min[2]) } ?: 128f
        dist = (r * 2.2f).coerceIn(150f, 4000f)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 760.dp
        val viewportFull: @Composable (Modifier) -> Unit = { m ->
            CellViewport(
                view = view, scene = scene, loading = loading, selected = selected, drag = drag, tool = tool, editorLight = editorLight, live = live,
                yaw = yaw, pitch = pitch, dist = dist, target = target,
                onCamera = { y, p, d, t -> yaw = y; pitch = p; dist = d; target = t },
                onPick = { selected = it },
                onDrag = { drag = it },
                onDragEnd = { key, pos ->
                    drag = null
                    view.refs.firstOrNull { it.key == key }?.let { commitPlacement(it, pos, it.scale) }
                },
                modifier = m,
            )
        }
        val viewport: @Composable (Modifier) -> Unit = { m ->
            if (full) Box(m.background(Color(0xFF0C0E12), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Text("Shown full screen", color = Color.Gray, fontSize = 12.sp)
            } else viewportFull(m)
        }
        if (full) Dialog(onDismissRequest = { full = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                viewportFull(Modifier.fillMaxSize())
                FlowRow(
                    Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    SmallButton("Exit full screen") { full = false }
                    for (t in Tool.entries) FilterChip(tool == t, { tool = t }, label = { Text(t.label) })
                    FilterChip(editorLight, { editorLight = !editorLight }, label = { Text("Editor light") })
                    FilterChip(live, { live = !live }, label = { Text("Live") })
                    SmallButton("Frame cell") { val (c, r) = sceneBounds(scene); target = c; dist = r * 1.7f }
                }
                selectedRef?.let { r ->
                    Text(r.objectId + if (r.state == RecordState.Deleted) " (deleted)" else "", color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
        val toolbar: @Composable () -> Unit = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.widthIn(max = 360.dp)) {
                    Text(view.header.firstOrNull { it.tag == "NAME" }?.let { org.alpha3.launcher.cs.model.zstringOf(it.data) }?.ifEmpty { null } ?: row.displayId,
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (view.interior) "Interior" else "Exterior ${view.grid.first}, ${view.grid.second}") +
                            " · ${view.refs.count { it.state != RecordState.Deleted }} objects · " + when (row.state) {
                            RecordState.Base -> "not changed by this plugin"
                            RecordState.Modified -> "changed in this plugin"
                            RecordState.New -> "new in this plugin"
                            RecordState.Deleted -> "deleted by this plugin"
                        },
                        fontSize = 12.sp, color = stateColour.getValue(row.state),
                    )
                }
                for (t in Tool.entries) FilterChip(tool == t, { tool = t }, label = { Text(t.label) })
                FilterChip(editorLight, { editorLight = !editorLight }, label = { Text("Editor light") })
                FilterChip(live, { live = !live }, label = { Text("Live") })
                SmallButton("Frame cell") { val (c, r) = sceneBounds(scene); target = c; dist = r * 1.7f }
                SmallButton("Full screen") { full = true }
                SmallButton("Place object") { placing = true }
                SmallButton("Cell settings") { headerOpen = true }
            }
        }
        val inspector: @Composable (Modifier) -> Unit = { m ->
            Box(m) {
                if (selectedRef != null) RefInspector(s, row, selectedRef, onFocus = { focus(selectedRef) }, onCommit = ::commitPlacement, onGone = { selected = null })
                else Text("Tap an object in the view or the list.\nLook: drag to turn the view, two fingers to move and zoom. Move, Turn and Raise drag the selected object.",
                    color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
            }
        }
        val list: @Composable (Modifier) -> Unit = { m -> RefList(view, selected, s.onlyPlugin, m) { r -> selected = r.key; if (tool == Tool.View) focus(r) } }

        if (wide) {
            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                toolbar()
                viewport(Modifier.fillMaxWidth().weight(1.15f))
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    list(Modifier.width(300.dp).fillMaxHeight())
                    inspector(Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else {
            var tab by remember { mutableStateOf(0) }
            Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                toolbar()
                viewport(Modifier.fillMaxWidth().weight(1f))
                TabRow(tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("Selected") })
                    Tab(tab == 1, { tab = 1 }, text = { Text("Objects") })
                }
                if (tab == 0) inspector(Modifier.fillMaxWidth().weight(1f)) else list(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }

    if (placing) {
        val ids = remember { doc.ids(PLACEABLE.toSet()) }
        PickerDialog("Place object", ids, onDismiss = { placing = false }) { id ->
            placing = false
            val persistent = runCatching { doc.placeable(id) }.getOrNull()?.let { it.flags and FLAG_PERSISTENT != 0L } ?: false
            val key = doc.addRef(row, id, floatArrayOf(target[0], target[1], target[2], 0f, 0f, 0f), persistent)
            s.changed()
            selected = key
        }
    }
    if (headerOpen) CellHeaderDialog(s, row, view) { headerOpen = false }
}

@Composable
private fun CellViewport(
    view: CellView,
    scene: List<PlacedRef>,
    loading: Boolean,
    selected: RefKey?,
    drag: Pair<RefKey, FloatArray>?,
    tool: Tool,
    editorLight: Boolean,
    live: Boolean,
    yaw: Float, pitch: Float, dist: Float, target: FloatArray,
    onCamera: (Float, Float, Float, FloatArray) -> Unit,
    onPick: (RefKey?) -> Unit,
    onDrag: (Pair<RefKey, FloatArray>?) -> Unit,
    onDragEnd: (RefKey, FloatArray) -> Unit,
    modifier: Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var renderSize by remember { mutableStateOf(IntSize(1, 1)) }
    val gpu = gpuCellViewAvailable
    // everything the gesture handler and the render thread read, current on every event
    val cur = rememberUpdatedState(Triple(view, scene, selected))
    val cam = rememberUpdatedState(OrbitCamera(yaw, pitch, dist, target))
    val toolNow = rememberUpdatedState(tool)
    val dragNow = rememberUpdatedState(drag)
    val camCb = rememberUpdatedState(onCamera)
    val lightNow = rememberUpdatedState(editorLight)
    val liveNow = rememberUpdatedState(live)

    val ambient = remember(view) {
        // the cell's ambient light (AMBI), or a daylight level outdoors
        val a = view.header.firstOrNull { it.tag == "AMBI" && it.data.size >= 16 }?.data
        if (view.interior && a != null) floatArrayOf((a[0].toInt() and 255) / 255f, (a[1].toInt() and 255) / 255f, (a[2].toInt() and 255) / 255f)
        else floatArrayOf(0.55f, 0.55f, 0.6f)
    }
    val water = remember(view) { waterHeight(view) }
    val extent = remember(scene) { sceneBounds(scene) }
    val clock = remember { TimeSource.Monotonic.markNow() }
    val dragCache = remember { DragCache() }
    var lastTap = remember { 0L to Offset.Zero }

    /** The frame as it stands: selection tinted, a dragged object where the finger is, lights animated when live. */
    fun buildFrame(): GpuFrame {
        val (v, sc, sel) = cur.value
        val c = cam.value
        val d = dragNow.value
        val live = liveNow.value
        val t = if (live) clock.elapsedNow().inWholeMilliseconds / 1000f else 0f
        val items = ArrayList<GpuItem>()
        for (p in sc) {
            val shown = dragCache.shown(p, v, d)
            val hl = p.key == sel
            for (m in shown.meshes) items += GpuItem(m, hl)
        }
        val lights = ArrayList<RenderLight>()
        sc.mapNotNull { p -> if (live) animatedLight(p, t) else p.light }.sortedBy { l ->
            val dx = l.x - c.target[0]; val dy = l.y - c.target[1]; val dz = l.z - c.target[2]; dx * dx + dy * dy + dz * dz
        }.take(if (gpu) 11 else 8).forEach { lights += it }
        if (lightNow.value) {
            // a soft light just over the camera, so dark interiors can be worked in
            val e = c.eye
            lights += RenderLight(e[0] + c.up[0] * 64f, e[1] + c.up[1] * 64f, e[2] + c.up[2] * 64f, 0.8f, 0.78f, 0.72f, c.dist * 4f)
        }
        return GpuFrame(items, c, lights, ambient, floatArrayOf(0.05f, 0.055f, 0.07f), water, extent, t)
    }

    LaunchedEffect(size, gpu) {
        if (size.width < 16 || size.height < 16) return@LaunchedEffect
        if (gpu) { renderSize = size; return@LaunchedEffect }
        // the software renderer: a smaller frame, scaled up
        val k = minOf(1f, 720f / maxOf(size.width, size.height))
        val w = (size.width * k).toInt().coerceAtLeast(64)
        val h = (size.height * k).toInt().coerceAtLeast(64)
        renderSize = IntSize(w, h)
        val renderer = MwRenderer(w, h).also { it.cullBackFaces = true }
        val presenter = FramePresenter(w, h)
        snapshotFlow { listOf(cur.value, cam.value, dragNow.value, lightNow.value, liveNow.value) }.collectLatest {
            frame = withContext(Dispatchers.Default) {
                val f = buildFrame()
                val (v, sc, sel) = cur.value
                val meshes = ArrayList<SceneMesh>()
                for (p in sc) {
                    val shown = dragCache.shown(p, v, dragNow.value)
                    meshes += if (p.key == sel) shown.highlighted else shown.meshes
                }
                val c = f.camera
                renderer.render(meshes, RenderParams(0f, c.yaw, c.pitch, c.dist, c.target, f.lights, ambient, floatArrayOf(0.06f, 0.07f, 0.09f), 0f, false, false))
                presenter.present(renderer.pixels)
            }
        }
    }

    Box(
        modifier.background(Color(0xFF0C0E12), RoundedCornerShape(8.dp))
            .onSizeChanged { size = it },
        contentAlignment = Alignment.Center,
    ) {
        if (gpu) {
            GpuCellView(
                frame = ::buildFrame, continuous = live,
                dirty = listOf(view, scene, selected, drag, yaw, pitch, dist, target.toList(), editorLight),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            frame?.let { Image(it, "Cell view", contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Medium, modifier = Modifier.fillMaxSize()) }
        }
        // touches go to a layer over the view (the GPU view is an Android view of its own)
        Box(Modifier.matchParentSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = 0f
                    var multi = false
                    var dragged: Pair<RefKey, FloatArray>? = null
                    var planeStart: FloatArray? = null
                    var startPos: FloatArray? = null
                    val f = renderSize.width.toFloat() / size.width.coerceAtLeast(1)
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.type == PointerEventType.Scroll) {
                            val c = cam.value
                            ev.changes.firstOrNull()?.let { ch -> camCb.value(c.yaw, c.pitch, (c.dist * (1f + ch.scrollDelta.y * 0.12f)).coerceIn(50f, 60000f), c.target); ch.consume() }
                            continue
                        }
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        val c = cam.value
                        if (pressed.size >= 2) {
                            multi = true
                            // pinch zooms, two fingers move the view point across the screen
                            val a = pressed[0]; val b = pressed[1]
                            val now = (a.position - b.position).getDistance()
                            val before = (a.previousPosition - b.previousPosition).getDistance()
                            var d = c.dist
                            if (before > 8f && now > 8f) d = (d * before / now).coerceIn(50f, 60000f)
                            val pdx = ((a.position.x - a.previousPosition.x) + (b.position.x - b.previousPosition.x)) / 2f
                            val pdy = ((a.position.y - a.previousPosition.y) + (b.position.y - b.previousPosition.y)) / 2f
                            val scale = d * 1.1547f / size.height.coerceAtLeast(1)
                            val t = FloatArray(3) { c.target[it] - c.right[it] * pdx * scale + c.up[it] * pdy * scale }
                            camCb.value(c.yaw, c.pitch, d, t)
                            pressed.forEach { it.consume() }
                            continue
                        }
                        if (multi) continue
                        val ch = pressed[0]
                        val dx = ch.position.x - ch.previousPosition.x
                        val dy = ch.position.y - ch.previousPosition.y
                        moved += abs(dx) + abs(dy)
                        if (moved < 14f) continue
                        ch.consume()
                        val (v, _, sel) = cur.value
                        val ref = sel?.let { k -> v.refs.firstOrNull { it.key == k } }
                        val t = toolNow.value
                        if (t == Tool.View || ref == null || ref.state == RecordState.Deleted) {
                            camCb.value(c.yaw + dx * 0.008f, (c.pitch + dy * 0.006f).coerceIn(-1.45f, 1.45f), c.dist, c.target)
                            continue
                        }
                        val base = startPos ?: (dragNow.value?.takeIf { it.first == ref.key }?.second ?: ref.position).copyOf().also { startPos = it }
                        val pos = (dragged?.second ?: base).copyOf()
                        when (t) {
                            Tool.Move -> {
                                // the point under the finger slides over the floor at the object's height
                                val o = c.eye
                                val hit = rayPlaneZ(o, c.ray(ch.position.x * f, ch.position.y * f, renderSize.width, renderSize.height), base[2])
                                if (hit != null) {
                                    val ps = planeStart ?: rayPlaneZ(o, c.ray(down.position.x * f, down.position.y * f, renderSize.width, renderSize.height), base[2]) ?: hit
                                    planeStart = ps
                                    pos[0] = base[0] + hit[0] - ps[0]; pos[1] = base[1] + hit[1] - ps[1]
                                }
                            }
                            Tool.Rotate -> {
                                pos[5] = ((pos[5] + dx * 0.01f) % (2 * PI.toFloat()))
                            }
                            Tool.Raise -> {
                                pos[2] -= dy * c.dist * 1.1547f / size.height.coerceAtLeast(1)
                            }
                            Tool.View -> {}
                        }
                        dragged = ref.key to pos
                        onDrag(dragged)
                    }
                    val done = dragged
                    when {
                        done != null -> onDragEnd(done.first, done.second)
                        !multi && moved < 14f -> {
                            // a tap: the nearest object under the finger; a second tap soon after flies there
                            val c = cam.value
                            val f = renderSize.width.toFloat() / size.width.coerceAtLeast(1)
                            val ray = c.ray(down.position.x * f, down.position.y * f, renderSize.width, renderSize.height)
                            val hit = pick(cur.value.second, c.eye, ray)
                            val now = clock.elapsedNow().inWholeMilliseconds
                            val double = now - lastTap.first < 350 && (down.position - lastTap.second).getDistance() < 60f
                            lastTap = (if (double) 0L else now) to down.position
                            if (double) {
                                val point = hit?.let { (_, t) -> FloatArray(3) { c.eye[it] + ray[it] * t } }
                                    ?: rayPlaneZ(c.eye, ray, c.target[2])
                                if (point != null) camCb.value(c.yaw, c.pitch, (c.dist * 0.6f).coerceAtLeast(150f), point)
                            } else onPick(hit?.first)
                        }
                    }
                }
            })
        NavPad(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { fwd, side, rise, dt ->
            // fly the view point: along the ground the way the camera faces, sideways, up and down
            val c = cam.value
            val hl = sqrt(c.fwd[0] * c.fwd[0] + c.fwd[1] * c.fwd[1]).coerceAtLeast(1e-4f)
            val step = (c.dist * 0.8f).coerceIn(150f, 20000f) * dt
            val t = floatArrayOf(
                c.target[0] + (c.fwd[0] / hl * fwd + c.right[0] * side) * step,
                c.target[1] + (c.fwd[1] / hl * fwd + c.right[1] * side) * step,
                c.target[2] + rise * step,
            )
            camCb.value(c.yaw, c.pitch, c.dist, t)
        }
        if (loading) Text("Loading models...", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
        if (!view.interior) Text("Exteriors are shown without their landscape for now", color = Color.Gray, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
    }
}

/** The drag preview of one object, made once per finger position and reused by every frame drawn at it. */
private class DragCache {
    @kotlin.concurrent.Volatile private var state: Triple<RefKey, FloatArray, PlacedRef>? = null

    fun shown(p: PlacedRef, v: CellView, d: Pair<RefKey, FloatArray>?): PlacedRef {
        if (d == null || d.first != p.key) return p
        state?.let { (k, pos, placed) -> if (k == p.key && pos === d.second) return placed }
        val ref = v.refs.firstOrNull { it.key == p.key } ?: return p
        val placed = movePlaced(p, ref.position, ref.scale, d.second, ref.scale)
        state = Triple(p.key, d.second, placed)
        return placed
    }
}

/** The height of the cell's water surface, or null when it has none (exteriors are at sea level). */
private fun waterHeight(view: CellView): Float? {
    if (!view.interior) return 0f
    val flags = view.headerData?.let { (it[0].toInt() and 255) } ?: 0
    if (flags and 0x2 == 0) return null
    view.header.firstOrNull { it.tag == "WHGT" && it.data.size == 4 }?.data?.let { d ->
        val f = Float.fromBits((d[0].toInt() and 255) or ((d[1].toInt() and 255) shl 8) or ((d[2].toInt() and 255) shl 16) or (d[3].toInt() shl 24))
        if (f.isFinite()) return f
    }
    view.header.firstOrNull { it.tag == "INTV" && it.data.size == 4 }?.data?.let { d ->
        return ((d[0].toInt() and 255) or ((d[1].toInt() and 255) shl 8) or ((d[2].toInt() and 255) shl 16) or (d[3].toInt() shl 24)).toFloat()
    }
    return 0f
}

/** [p] moved from [from] to [to] (and rescaled), by undoing its old placement and applying the new one. */
private fun movePlaced(p: PlacedRef, from: FloatArray, fromScale: Float, to: FloatArray, toScale: Float): PlacedRef {
    val r0 = rotationMatrix(from[3], from[4], from[5])
    val r1 = rotationMatrix(to[3], to[4], to[5])
    // world = R0 * local * s0 + t0  =>  local = R0^T (world - t0) / s0
    val min = FloatArray(3) { Float.MAX_VALUE }
    val max = FloatArray(3) { -Float.MAX_VALUE }
    val cache = HashMap<FloatArray, FloatArray>()
    fun move(src: FloatArray, normals: Boolean): FloatArray = cache.getOrPut(src) {
        val out = FloatArray(src.size)
        var i = 0
        while (i < src.size) {
            var x = src[i]; var y = src[i + 1]; var z = src[i + 2]
            if (!normals) { x = (x - from[0]) / fromScale; y = (y - from[1]) / fromScale; z = (z - from[2]) / fromScale }
            val lx = r0[0] * x + r0[3] * y + r0[6] * z
            val ly = r0[1] * x + r0[4] * y + r0[7] * z
            val lz = r0[2] * x + r0[5] * y + r0[8] * z
            var wx = r1[0] * lx + r1[1] * ly + r1[2] * lz
            var wy = r1[3] * lx + r1[4] * ly + r1[5] * lz
            var wz = r1[6] * lx + r1[7] * ly + r1[8] * lz
            if (!normals) {
                wx = wx * toScale + to[0]; wy = wy * toScale + to[1]; wz = wz * toScale + to[2]
                if (wx < min[0]) min[0] = wx; if (wy < min[1]) min[1] = wy; if (wz < min[2]) min[2] = wz
                if (wx > max[0]) max[0] = wx; if (wy > max[1]) max[1] = wy; if (wz > max[2]) max[2] = wz
            }
            out[i] = wx; out[i + 1] = wy; out[i + 2] = wz
            i += 3
        }
        out
    }
    fun mesh(m: SceneMesh) = SceneMesh(move(m.positions, false), move(m.normals, true), m.uvs, m.colors, m.indices, m.texture, m.diffuse, m.emissive, m.alphaBlend, m.alphaTest)
    val meshes = p.meshes.map(::mesh)
    val lit = p.highlighted.map(::mesh)
    val light = p.light?.let { RenderLight(to[0], to[1], to[2], it.r, it.g, it.b, it.radius) }
    return PlacedRef(p.key, "", meshes, lit, min, max, light)
}

@Composable
private fun RefList(view: CellView, selected: RefKey?, onlyPlugin: Boolean, modifier: Modifier, onSelect: (CellRef) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(view, query, onlyPlugin) {
        view.refs.filter { (!onlyPlugin || it.state != RecordState.Base) && (query.isBlank() || it.objectId.contains(query, ignoreCase = true)) }
            .sortedWith(compareBy({ it.state == RecordState.Base }, { it.objectId.lowercase() }))
    }
    val state = rememberLazyListState()
    Column(modifier.background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(8.dp)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(query, { query = it }, placeholder = { Text(if (onlyPlugin) "Search ${shown.size} changed objects" else "Search ${view.refs.size} objects") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyColumn(state = state, modifier = Modifier.weight(1f)) {
            items(shown, key = { "${it.key.file}:${it.key.index}" }) { r ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (r.key == selected) Color.White.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable { onSelect(r) }.padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("●  ", color = stateColour.getValue(r.state), fontSize = 10.sp)
                    Text(r.objectId, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), fontSize = 14.sp)
                    if (r.persistent) Text("P", color = Color.Gray, fontSize = 11.sp)
                }
            }
        }
    }
}

/** A number field that keeps what is being typed and reports only values that parse. */
@Composable
private fun DecimalField(key: Any, label: String, value: Float, digits: Int, modifier: Modifier = Modifier, onSet: (Float) -> Unit) {
    var m = 1f
    repeat(digits) { m *= 10f }
    fun fmt(v: Float): String = if (digits == 0) v.roundToInt().toString() else ((v * m).roundToInt() / m).toString()
    var text by remember(key) { mutableStateOf(fmt(value)) }
    // a change from elsewhere (a drag, undo) shows; what is being typed is left alone while it means the same value
    LaunchedEffect(value) {
        val typed = text.replace(',', '.').toFloatOrNull()
        if (typed == null || abs(typed - value) > 0.5f / m) text = fmt(value)
    }
    OutlinedTextField(
        text, { t -> text = t; t.replace(',', '.').toFloatOrNull()?.let(onSet) },
        label = { Text(label) }, singleLine = true, modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RefInspector(s: CsSession, row: RecordRow, ref: CellRef, onFocus: () -> Unit, onCommit: (CellRef, FloatArray, Float, String?) -> Unit, onGone: () -> Unit) {
    val doc = s.doc
    val cellDef = RECORD_DEFS.getValue("CELL")
    var picking by remember { mutableStateOf(false) }
    val pos = ref.position
    val deg = 180f / PI.toFloat()
    Column(
        Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(8.dp)).verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(ref.objectId, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (ref.state) {
                        RecordState.Base -> "from ${doc.masters.getOrNull(ref.source)?.name ?: "this plugin"}"
                        RecordState.Modified -> "changed in this plugin"
                        RecordState.New -> "new in this plugin"
                        RecordState.Deleted -> "deleted by this plugin"
                    } + if (ref.persistent) " · persistent" else "",
                    color = stateColour.getValue(ref.state), fontSize = 12.sp,
                )
            }
            SmallButton("Focus") { onFocus() }
            if (ref.state == RecordState.Modified || ref.state == RecordState.Deleted) SmallButton("Revert") { doc.revertRef(row, ref); s.changed() }
            if (ref.state != RecordState.Deleted) SmallButton("Delete") {
                doc.deleteRef(row, ref); s.changed()
                if (ref.state == RecordState.New) onGone()
            }
        }
        if (ref.state == RecordState.Deleted) {
            Text("This object is deleted by the plugin. Revert brings it back.", color = Color.Gray, fontSize = 13.sp)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(ref.objectId, {}, readOnly = true, label = { Text("Object") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { picking = true }) { Text("Change") }
            TextButton(onClick = {
                val tag = doc.placeable(ref.objectId)?.tag
                if (tag != null) { s.type = tag; s.selected = tag to ref.objectId.lowercase() }
            }) { Text("Open") }
        }
        Text("Position", fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0..2) DecimalField("${ref.key}p$i", "XYZ"[i].toString(), pos[i], 1, Modifier.widthIn(min = 110.dp, max = 160.dp)) { v ->
                onCommit(ref, pos.copyOf().also { it[i] = v }, ref.scale, "pos$i")
            }
        }
        Text("Rotation (degrees)", fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0..2) DecimalField("${ref.key}r$i", "XYZ"[i].toString(), pos[3 + i] * deg, 1, Modifier.widthIn(min = 110.dp, max = 160.dp)) { v ->
                onCommit(ref, pos.copyOf().also { it[3 + i] = v / deg }, ref.scale, "rot$i")
            }
            DecimalField("${ref.key}s", "Scale", ref.scale, 2, Modifier.widthIn(min = 110.dp, max = 160.dp)) { v ->
                if (v in 0.5f..2f) onCommit(ref, pos, v, "scale")
            }
        }
        // ownership, locks, doors, counts...: the reference's other subrecords
        ref.subs.forEachIndexed { index, sub ->
            if (sub.tag in PLACEMENT_TAGS) return@forEachIndexed
            SubCard(s, cellDef.sub(sub.tag), sub, index, removable = true, onRemove = {
                doc.setRef(row, ref, ref.subs.filterIndexed { i, _ -> i != index }); s.changed()
            }) { newSub, field ->
                doc.setRef(row, ref, ref.subs.mapIndexed { i, o -> if (i == index) newSub else o }, mergeKey = "$index:$field"); s.changed()
            }
        }
        AddSubButton(EXTRA_REF_TAGS.filter { t -> ref.subs.none { it.tag == t } }.mapNotNull { cellDef.sub(it) }) { d ->
            // before the position, which the game writes last
            val at = ref.subs.indexOfFirst { it.tag == "DATA" }.let { if (it < 0) ref.subs.size else it }
            doc.setRef(row, ref, ref.subs.toMutableList().also { it.add(at, blankSub(d)) }); s.changed()
        }
    }
    if (picking) {
        val ids = remember { doc.ids(PLACEABLE.toSet()) }
        PickerDialog("Object", ids, onDismiss = { picking = false }) { id ->
            picking = false
            val name = Sub("NAME", doc.codec.encode(id) + byteArrayOf(0))
            doc.setRef(row, ref, ref.subs.map { if (it.tag == "NAME") name else it }); s.changed()
        }
    }
}

@Composable
private fun CellHeaderDialog(s: CsSession, row: RecordRow, view: CellView, onDismiss: () -> Unit) {
    val doc = s.doc
    val def = RECORD_DEFS.getValue("CELL")
    val header = view.header.filter { it.tag != "NAM0" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cell settings") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Lighting, water, region and flags. Changing the name of a cell from a master makes a new cell instead.", color = Color.Gray, fontSize = 12.sp)
                header.forEachIndexed { index, sub ->
                    SubCard(s, def.sub(sub.tag), sub, index, removable = sub.tag != "NAME" && sub.tag != "DATA", onRemove = {
                        doc.setCellHeader(row, header.filterIndexed { i, _ -> i != index }); s.changed()
                    }) { newSub, field ->
                        doc.setCellHeader(row, header.mapIndexed { i, o -> if (i == index) newSub else o }, mergeKey = "$index:$field"); s.changed()
                    }
                }
                AddSubButton(HEADER_TAGS.filter { t -> header.none { it.tag == t } }.mapNotNull { def.sub(it) }) { d ->
                    doc.setCellHeader(row, header + blankSub(d)); s.changed()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}


/** On-screen flight controls: hold an arrow to move the view point (forward/back/strafe, up/down). */
@Composable
private fun NavPad(modifier: Modifier, onMove: (fwd: Float, side: Float, rise: Float, dt: Float) -> Unit) {
    val move = rememberUpdatedState(onMove)
    @Composable
    fun Key(label: String, fwd: Float, side: Float, rise: Float) {
        var held by remember { mutableStateOf(false) }
        LaunchedEffect(held) {
            var last = 0L
            while (held) {
                withFrameNanos { t ->
                    if (last != 0L) move.value(fwd, side, rise, ((t - last) / 1e9f).coerceAtMost(0.1f))
                    last = t
                }
            }
        }
        Box(
            Modifier.size(46.dp).background(Color.Black.copy(alpha = if (held) 0.75f else 0.45f), RoundedCornerShape(10.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown().consume()
                        held = true
                        waitForUpOrCancellation()
                        held = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Text(label, color = Color.White, fontSize = 18.sp) }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Key("⤒", 0f, 0f, 1f)
            Key("⤓", 0f, 0f, -1f)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Key("▲", 1f, 0f, 0f)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Key("◀", 0f, -1f, 0f)
                Key("▼", -1f, 0f, 0f)
                Key("▶", 0f, 1f, 0f)
            }
        }
    }
}
