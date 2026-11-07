package org.alpha3.launcher.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * What a theme other than Classic draws behind the launcher (Classic keeps the background picked in the settings).
 * The animated ones stand still with Reduce motion; nothing is drawn while the game runs (the launcher is stopped).
 */
@Composable
fun ThemeBackdrop(state: LauncherThemeState, modifier: Modifier = Modifier.fillMaxSize()) {
    val t = state.tokens
    val time = animationClock(state.animate && t.backdrop in animatedBackdrops)
    Canvas(modifier) {
        drawRect(t.background)
        when (t.backdrop) {
            Backdrop.Classic, Backdrop.Flat -> Unit
            Backdrop.Vignette -> vignette(t.primary.copy(alpha = 0.10f), Offset(size.width * 0.5f, size.height * 0.2f))
            Backdrop.Mushroom -> {
                vignette(t.primary.copy(alpha = 0.10f), Offset(size.width * 0.15f, size.height * 0.1f))
                vignette(t.secondary.copy(alpha = 0.12f), Offset(size.width * 0.9f, size.height * 0.85f))
            }
            Backdrop.Scanlines -> scanlines(t.primary, time.value)
            Backdrop.Embers -> embers(t, time.value)
            Backdrop.Holo -> holo(t, time.value)
            Backdrop.Stars -> stars(t, time.value)
            Backdrop.Glitch -> glitch(t, time.value)
        }
    }
}

private val animatedBackdrops = setOf(Backdrop.Scanlines, Backdrop.Embers, Backdrop.Holo, Backdrop.Stars, Backdrop.Glitch)

/** Seconds, 0 to 60 and round again; held at 0 when not animating. */
@Composable
internal fun animationClock(animate: Boolean): State<Float> {
    if (!animate) return remember { mutableFloatStateOf(0f) }
    val transition = rememberInfiniteTransition(label = "theme")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 60f,
        animationSpec = infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Restart),
        label = "clock"
    )
}

private fun DrawScope.vignette(colour: Color, centre: Offset) {
    drawRect(Brush.radialGradient(listOf(colour, Color.Transparent), centre, size.maxDimension * 0.7f))
}

private fun DrawScope.scanlines(colour: Color, time: Float) {
    vignette(colour.copy(alpha = 0.06f), center)
    val step = 4f * density
    var y = 0f
    while (y < size.height) {
        drawLine(Color.Black.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), strokeWidth = density)
        y += step
    }
    // a slow bright band going down
    val band = (time / 8f % 1f) * (size.height + 200f * density) - 100f * density
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, colour.copy(alpha = 0.06f), Color.Transparent), band, band + 100f * density),
        Offset(0f, band), Size(size.width, 100f * density)
    )
}

// fixed pseudo random numbers, so the particles stay where they are between frames
private fun hash(i: Int, salt: Int): Float {
    var x = i * 374761393 + salt * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0xFFFF) / 65535f
}

private fun DrawScope.embers(t: ThemeTokens, time: Float) {
    drawRect(Brush.verticalGradient(listOf(t.background, t.primary.copy(alpha = 0.10f))))
    for (i in 0 until 40) {
        val speed = 0.02f + hash(i, 1) * 0.04f
        val y = 1f - ((hash(i, 2) + time * speed) % 1f)
        val x = hash(i, 3) + sin((time * 0.5f + i) * 0.7f) * 0.02f
        val alpha = (0.15f + 0.5f * hash(i, 4)) * (1f - y * 0.3f)
        drawCircle(t.primary.copy(alpha = alpha), radius = (1f + 2f * hash(i, 5)) * density, center = Offset(x * size.width, y * size.height))
    }
}

private fun DrawScope.holo(t: ThemeTokens, time: Float) {
    vignette(t.primary.copy(alpha = 0.08f), center)
    val step = 32f * density
    var x = 0f
    while (x < size.width) {
        drawLine(t.primary.copy(alpha = 0.05f), Offset(x, 0f), Offset(x, size.height), strokeWidth = density)
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(t.primary.copy(alpha = 0.05f), Offset(0f, y), Offset(size.width, y), strokeWidth = density)
        y += step
    }
    val radius = size.minDimension * 0.42f
    rotate(time * 6f) {
        drawArc(t.primary.copy(alpha = 0.18f), 0f, 250f, false, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2), style = Stroke(1.5f * density))
    }
    rotate(-time * 9f) {
        val r = radius * 0.8f
        drawArc(t.secondary.copy(alpha = 0.18f), 40f, 140f, false, Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = Stroke(3f * density))
    }
    val scan = (time / 5f % 1f) * size.height
    drawLine(t.primary.copy(alpha = 0.12f), Offset(0f, scan), Offset(size.width, scan), strokeWidth = 2f * density)
}

private fun DrawScope.stars(t: ThemeTokens, time: Float) {
    vignette(t.primary.copy(alpha = 0.12f), Offset(size.width * 0.3f, size.height * 0.3f))
    vignette(t.secondary.copy(alpha = 0.08f), Offset(size.width * 0.8f, size.height * 0.7f))
    for (i in 0 until 90) {
        val twinkle = 0.5f + 0.5f * sin(time * (0.5f + hash(i, 7)) * 2f * PI.toFloat() / 6f + i)
        drawCircle(
            Color.White.copy(alpha = 0.15f + 0.6f * hash(i, 8) * twinkle),
            radius = (0.6f + 1.2f * hash(i, 9)) * density,
            center = Offset(hash(i, 10) * size.width, hash(i, 11) * size.height)
        )
    }
}

private fun DrawScope.glitch(t: ThemeTokens, time: Float) {
    vignette(t.secondary.copy(alpha = 0.07f), Offset(size.width, 0f))
    vignette(Color(0xFF05D9E8).copy(alpha = 0.06f), Offset(0f, size.height))
    // now and then a few bars shift sideways
    val tick = (time * 4f).toInt()
    if (hash(tick, 12) > 0.8f) {
        for (b in 0 until 3) {
            val y = hash(tick * 3 + b, 13) * size.height
            val h = (2f + 10f * hash(tick * 3 + b, 14)) * density
            val colour = if (b % 2 == 0) t.secondary else t.primary
            drawRect(colour.copy(alpha = 0.10f), Offset(0f, y), Size(size.width, h))
        }
    }
}

/** The angle of node [index] of [count] on a circle, starting at the top, turning with [turn] (degrees). */
internal fun circlePoint(centre: Offset, radius: Float, index: Int, count: Int, turn: Float = 0f): Offset {
    val angle = (-90f + 360f * index / count + turn) * PI.toFloat() / 180f
    return Offset(centre.x + radius * cos(angle), centre.y + radius * sin(angle))
}
