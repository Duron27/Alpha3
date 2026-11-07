package org.alpha3.launcher.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

/** A section of the home screen (a mod list, the downloader, a settings page, search, help, ...). */
class NavEntry(
    val icon: ImageVector,
    val label: String,
    val selected: Boolean,
    /** search, help and the like: small, after the sections */
    val utility: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * The home screen's sections, as the theme shows them: none of these scroll sideways (the Classic tab row does).
 * [trailing] is what the bar has at its end (the landscape menu). [vertical]: as a rail down the left side.
 * [core] is the big button of the radial and orbit menus (Play).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemedNav(
    state: LauncherThemeState,
    entries: List<NavEntry>,
    vertical: Boolean,
    core: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val t = state.tokens
    when (t.nav) {
        NavStyle.Classic -> Unit
        NavStyle.Rail -> RailNav(t, entries, trailing)
        NavStyle.Radial, NavStyle.Orbit -> CircleNavBar(state, entries, core, trailing)
        NavStyle.Underline -> FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF212121))
                .padding(horizontal = 4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            entries.forEach { entry -> UnderlineTab(t, entry) }
            trailing()
        }
        else -> FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(t.background.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            entries.forEachIndexed { index, entry ->
                when (t.nav) {
                    NavStyle.Tiles -> if (entry.utility) SmallIcon(t, entry, RoundedCornerShape(t.corner.dp)) else Tile(t, entry)
                    NavStyle.Segmented -> Segment(t, entry)
                    NavStyle.Text -> TextTab(t, entry)
                    NavStyle.Chips -> Chip(t, entry)
                    NavStyle.Slots -> Slot(t, entry, index + 1)
                    else -> Unit
                }
            }
            trailing()
        }
    }
}

@Composable
private fun Tile(t: ThemeTokens, entry: NavEntry) {
    val shape = RoundedCornerShape(t.corner.dp)
    Column(
        modifier = Modifier
            .widthIn(min = 92.dp)
            .clip(shape)
            .background(if (entry.selected) t.primary.copy(alpha = 0.22f) else t.surface.copy(alpha = 0.85f), shape)
            .border(1.dp, if (entry.selected) t.primary else t.primary.copy(alpha = 0.25f), shape)
            .clickable(onClick = entry.onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(entry.icon, contentDescription = null, tint = if (entry.selected) t.primary else t.dim, modifier = Modifier.size(20.dp))
        Text(entry.label, color = if (entry.selected) t.onBackground else t.dim, fontSize = 12.sp, fontFamily = t.display, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Material's tab: the label in capitals, the selected one white over a line in the accent. */
@Composable
private fun UnderlineTab(t: ThemeTokens, entry: NavEntry) {
    Column(
        modifier = Modifier.width(IntrinsicSize.Max).clickable(onClick = entry.onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            if (entry.utility) Icon(entry.icon, contentDescription = entry.label, tint = if (entry.selected) t.onBackground else t.dim, modifier = Modifier.size(20.dp))
            else Text(
                entry.label.uppercase(), color = if (entry.selected) t.onBackground else t.dim,
                fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp, maxLines = 1,
            )
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (entry.selected) t.primary else Color.Transparent))
    }
}

@Composable
private fun SmallIcon(t: ThemeTokens, entry: NavEntry, shape: Shape) {
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (entry.selected) t.primary.copy(alpha = 0.22f) else Color.Transparent, shape)
            .border(1.dp, if (entry.selected) t.primary else t.dim.copy(alpha = 0.3f), shape)
            .clickable(onClick = entry.onClick)
            .padding(8.dp),
    ) {
        Icon(entry.icon, contentDescription = entry.label, tint = if (entry.selected) t.primary else t.dim, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Segment(t: ThemeTokens, entry: NavEntry) {
    val shape = RoundedCornerShape(t.corner.dp)
    if (entry.utility) return SmallIcon(t, entry, shape)
    Text(
        entry.label,
        modifier = Modifier
            .clip(shape)
            .background(if (entry.selected) t.primary else t.surface, shape)
            .clickable(onClick = entry.onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        color = if (entry.selected) Color.White else t.onBackground,
        fontSize = 13.sp,
        fontWeight = if (entry.selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
    )
}

@Composable
private fun TextTab(t: ThemeTokens, entry: NavEntry) {
    Text(
        if (entry.selected) "[${entry.label}]" else " ${entry.label} ",
        modifier = Modifier
            .background(if (entry.selected) t.primary else Color.Transparent)
            .clickable(onClick = entry.onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        color = if (entry.selected) t.background else t.primary,
        fontFamily = t.body,
        fontSize = 13.sp,
        maxLines = 1,
    )
}

@Composable
private fun Chip(t: ThemeTokens, entry: NavEntry) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (entry.selected) t.primary else t.surface.copy(alpha = 0.9f), shape)
            .border(1.dp, if (entry.selected) t.primary else t.primary.copy(alpha = 0.3f), shape)
            .clickable(onClick = entry.onClick)
            .padding(horizontal = if (entry.utility) 9.dp else 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val tint = if (entry.selected) t.background else t.onBackground
        Icon(entry.icon, contentDescription = entry.label, tint = tint, modifier = Modifier.size(16.dp))
        if (!entry.utility) Text(entry.label, color = tint, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun Slot(t: ThemeTokens, entry: NavEntry, number: Int) {
    val shape = CutCornerShape(topEnd = 8.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (entry.selected) t.primary else t.surface, shape)
            .border(1.dp, if (entry.selected) t.primary else t.secondary.copy(alpha = 0.5f), shape)
            .clickable(onClick = entry.onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val tint = if (entry.selected) t.background else t.onBackground
        Text(number.toString().padStart(2, '0'), color = if (entry.selected) t.background else t.secondary, fontFamily = t.display, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Icon(entry.icon, contentDescription = entry.label, tint = tint, modifier = Modifier.size(16.dp))
        if (!entry.utility) Text(entry.label.uppercase(), color = tint, fontFamily = t.display, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun RailNav(t: ThemeTokens, entries: List<NavEntry>, trailing: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .width(80.dp)
            .fillMaxHeight()
            .background(t.surface.copy(alpha = 0.85f))
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        entries.forEach { entry ->
            val shape = RoundedCornerShape(t.corner.dp)
            Column(
                modifier = Modifier
                    .width(72.dp)
                    .clip(shape)
                    .background(if (entry.selected) t.primary.copy(alpha = 0.2f) else Color.Transparent, shape)
                    .clickable(onClick = entry.onClick)
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 48.dp, height = 28.dp)
                        .background(if (entry.selected) t.secondary.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(entry.icon, contentDescription = entry.label, tint = if (entry.selected) t.primary else t.dim, modifier = Modifier.size(20.dp))
                }
                if (!entry.utility) Text(
                    entry.label, color = if (entry.selected) t.onBackground else t.dim, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
        }
        trailing()
    }
}

/** The radial and orbit themes: a slim bar naming the section, and a button that opens the circle. */
@Composable
private fun CircleNavBar(state: LauncherThemeState, entries: List<NavEntry>, core: @Composable () -> Unit, trailing: @Composable () -> Unit) {
    val t = state.tokens
    var open by remember { mutableStateOf(false) }
    val current = entries.firstOrNull { it.selected }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(t.background.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(t.primary.copy(alpha = 0.15f), CircleShape)
                .border(1.5.dp, t.primary, CircleShape)
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Apps, contentDescription = "Sections", tint = t.primary, modifier = Modifier.size(22.dp))
        }
        Text(
            (current?.label ?: "").uppercase(), color = t.onBackground, fontFamily = t.display, fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, maxLines = 1, modifier = Modifier.weight(1f),
        )
        trailing()
    }
    if (open) {
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            CircleMenu(state, entries, core) { open = false }
        }
    }
}

@Composable
private fun CircleMenu(state: LauncherThemeState, entries: List<NavEntry>, core: @Composable () -> Unit, close: () -> Unit) {
    val t = state.tokens
    val orbit = t.nav == NavStyle.Orbit
    val time by animationClock(state.animate)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(t.background.copy(alpha = 0.92f))
            .clickable(onClick = close),
        contentAlignment = Alignment.Center,
    ) {
        val radius = (minOf(maxWidth, maxHeight) * 0.38f).coerceAtMost(280.dp)
        val density = LocalDensity.current
        val radiusPx = with(density) { radius.toPx() }
        // the orbit turns once a minute; the radial menu stays put, its rings turn
        val turn = if (orbit) time * 6f else 0f
        // the sections on the outer circle, search, help and the like on an inner one
        val outer = entries.filter { !it.utility }
        val inner = entries.filter { it.utility }
        fun placeOf(entry: NavEntry, centre: Offset): Offset =
            if (entry.utility) circlePoint(centre, radiusPx * 0.52f, inner.indexOf(entry), inner.size, 45f - turn)
            else circlePoint(centre, radiusPx, outer.indexOf(entry), outer.size, turn)
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            if (orbit) {
                drawCircle(t.primary.copy(alpha = 0.25f), radiusPx, c, style = Stroke(1.dp.toPx()))
                drawCircle(t.secondary.copy(alpha = 0.15f), radiusPx * 0.55f, c, style = Stroke(1.dp.toPx()))
            } else {
                rotate(time * 12f, c) {
                    drawArc(t.primary.copy(alpha = 0.5f), 0f, 300f, false, Offset(c.x - radiusPx * 1.18f, c.y - radiusPx * 1.18f), Size(radiusPx * 2.36f, radiusPx * 2.36f), style = Stroke(1.5.dp.toPx()))
                }
                rotate(-time * 20f, c) {
                    drawArc(t.secondary.copy(alpha = 0.5f), 30f, 120f, false, Offset(c.x - radiusPx * 0.62f, c.y - radiusPx * 0.62f), Size(radiusPx * 1.24f, radiusPx * 1.24f), style = Stroke(4.dp.toPx()))
                }
                drawCircle(t.primary.copy(alpha = 0.2f), radiusPx, c, style = Stroke(1.dp.toPx()))
            }
            outer.forEach { entry ->
                drawLine(t.primary.copy(alpha = 0.15f), c, placeOf(entry, c), strokeWidth = 1.dp.toPx())
            }
        }
        // the core: Play
        Box(contentAlignment = Alignment.Center) { core() }
        entries.forEach { entry ->
            val p = placeOf(entry, Offset.Zero)
            val node = 64.dp
            Column(
                modifier = Modifier
                    .offset { IntOffset(p.x.roundToInt(), p.y.roundToInt()) }
                    .width(node + 24.dp)
                    .clickable { entry.onClick(); close() },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(if (entry.utility) node * 0.7f else node)
                        .clip(CircleShape)
                        .background(if (entry.selected) t.primary.copy(alpha = 0.3f) else t.surface.copy(alpha = 0.9f), CircleShape)
                        .border(if (entry.selected) 2.dp else 1.dp, if (entry.selected) t.primary else t.primary.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(entry.icon, contentDescription = entry.label, tint = if (entry.selected) t.primary else t.onBackground, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    entry.label.uppercase(), color = t.onBackground, fontFamily = t.display, fontSize = 10.sp,
                    letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(40.dp)
                .clip(CircleShape)
                .border(1.dp, t.dim, CircleShape)
                .clickable(onClick = close),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = t.onBackground)
        }
    }
}

/**
 * The home screen's frame: the sections' bar above the content, or (rail themes) down the left of it. Classic is
 * the column it always was.
 */
@Composable
fun ThemedSections(
    state: LauncherThemeState,
    classicBar: @Composable () -> Unit,
    nav: @Composable (vertical: Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    when {
        state.isClassic -> Column {
            classicBar()
            content()
        }
        state.tokens.nav == NavStyle.Rail -> Row(Modifier.fillMaxSize()) {
            nav(true)
            Column(Modifier.weight(1f).fillMaxHeight(), content = content)
        }
        else -> Column {
            nav(false)
            content()
        }
    }
}
