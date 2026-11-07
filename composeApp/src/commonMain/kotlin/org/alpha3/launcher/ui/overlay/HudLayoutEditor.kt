package org.alpha3.launcher.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.alpha3.launcher.engine.Engine
import kotlin.math.roundToInt

/** A HUD element as the game reports it (support/src/hudlayout.cpp), in fractions of the screen. */
private data class HudElement(
    val name: String,
    val visible: Boolean,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val scale: Float,
    val scales: Boolean,
)

/** The element under the finger, read and written by the gesture handlers without waiting for a recomposition. */
private class Latest(var value: HudElement) {
    var dragging = 0
}

private val hudLabels = mapOf(
    "HealthFrame" to "Health",
    "MagickaFrame" to "Magicka",
    "FatigueFrame" to "Fatigue",
    "EnemyHealth" to "Enemy health",
    "WeapBox" to "Weapon",
    "SpellBox" to "Spell",
    "SneakBox" to "Sneak",
    "EffectBox" to "Effects",
    "MiniMapBox" to "Map",
    "CellName" to "Cell name",
    "WeaponSpellName" to "Item name",
)

private fun parseHud(text: String): List<HudElement> = text.lineSequence().mapNotNull { line ->
    val f = line.trim().split(' ')
    if (f.size < 8) return@mapNotNull null
    runCatching {
        HudElement(
            name = f[0], visible = f[1] == "1",
            left = f[2].toFloat(), top = f[3].toFloat(), width = f[4].toFloat(), height = f[5].toFloat(),
            scale = f[6].toFloat(), scales = f[7] == "1",
        )
    }.getOrNull()
}.toList()

/**
 * The game's HUD in the overlay's edit mode: a box over each element (health bars, weapon and spell, map, ...).
 * Drag a box to move the element, drag its corner to resize it; the game keeps the layout (Alpha3/config/hudlayout.cfg).
 * Elements the game hides right now (enemy health, the cell name) are drawn grey: they can be placed all the same.
 */
@Composable
fun HudLayoutEditor(containerWidth: Float, containerHeight: Float) {
    if (containerWidth <= 0f || containerHeight <= 0f) return
    val elements = remember { mutableStateMapOf<String, HudElement>() }
    var dragging by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            Engine.hudLayout()?.let { text ->
                for (element in parseHud(text)) {
                    if (element.name != dragging) elements[element.name] = element
                }
            }
            delay(250)
        }
    }

    Box(Modifier.fillMaxSize()) {
        elements.values.sortedByDescending { it.width * it.height }.forEach { element ->
            key(element.name) {
                HudBox(
                    element = element,
                    containerWidth = containerWidth,
                    containerHeight = containerHeight,
                    onDragging = { dragging = if (it) element.name else null },
                    onChange = { changed, keep ->
                        elements[changed.name] = changed
                        Engine.hudSet(changed.name, changed.left, changed.top, changed.scale, keep)
                    },
                )
            }
        }
        if (elements.isNotEmpty()) {
            Button(
                onClick = { Engine.hudReset() },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.7f), contentColor = Color(0xFFCAA560)),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
            ) { Text("Reset HUD") }
        }
    }
}

@Composable
private fun HudBox(
    element: HudElement,
    containerWidth: Float,
    containerHeight: Float,
    onDragging: (Boolean) -> Unit,
    onChange: (HudElement, keep: Boolean) -> Unit,
) {
    val density = LocalDensity.current.density
    // the latest value inside the gesture handlers, which outlive a recomposition
    val latest = remember { Latest(element) }
    if (latest.dragging == 0) latest.value = element
    val colour = if (element.visible) Color(0xFFCAA560) else Color.Gray
    val widthPx = (element.width * containerWidth).coerceAtLeast(24f * density)
    val heightPx = (element.height * containerHeight).coerceAtLeast(24f * density)

    Box(
        modifier = Modifier
            .offset { IntOffset((element.left * containerWidth).roundToInt(), (element.top * containerHeight).roundToInt()) }
            .size((widthPx / density).dp, (heightPx / density).dp)
            .background(colour.copy(alpha = 0.18f), RoundedCornerShape(3.dp))
            .border(2.dp, colour, RoundedCornerShape(3.dp))
            .pointerInput(element.name) {
                detectDragGestures(
                    onDragStart = { latest.dragging++; onDragging(true) },
                    onDrag = { change, amount ->
                        change.consume()
                        val e = latest.value
                        latest.value = e.copy(left = e.left + amount.x / containerWidth, top = e.top + amount.y / containerHeight)
                        onChange(latest.value, false)
                    },
                    onDragEnd = {
                        latest.dragging--
                        onDragging(false)
                        onChange(latest.value, true)
                    },
                    onDragCancel = { latest.dragging--; onDragging(false) },
                )
            },
    ) {
        Text(
            text = hudLabels[element.name] ?: element.name,
            color = Color.White,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .align(Alignment.TopStart)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 2.dp),
        )
        if (element.scales) {
            // the corner: dragging it resizes the element, keeping its proportions
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 10.dp, y = 10.dp)
                    .size(24.dp)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                    .border(1.dp, colour, RoundedCornerShape(12.dp))
                    .pointerInput(element.name) {
                        detectDragGestures(
                            onDragStart = { latest.dragging++; onDragging(true) },
                            onDrag = { change, amount ->
                                change.consume()
                                val e = latest.value
                                val w = e.width * containerWidth
                                val h = e.height * containerHeight
                                if (w <= 0f || h <= 0f) return@detectDragGestures
                                // the larger of the two movements, as a share of the element's size
                                val grow = maxOf((w + amount.x) / w, (h + amount.y) / h)
                                val scale = (e.scale * grow).coerceIn(0.25f, 6f)
                                val ratio = scale / e.scale
                                latest.value = e.copy(scale = scale, width = e.width * ratio, height = e.height * ratio)
                                onChange(latest.value, false)
                            },
                            onDragEnd = {
                                latest.dragging--
                                onDragging(false)
                                onChange(latest.value, true)
                            },
                            onDragCancel = { latest.dragging--; onDragging(false) },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.OpenInFull, contentDescription = "Resize", tint = colour, modifier = Modifier.size(14.dp))
            }
        }
    }
}
