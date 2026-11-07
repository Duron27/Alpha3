package org.alpha3.launcher.ui.page.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.alpha3.launcher.ui.theme.LauncherTheme
import org.alpha3.launcher.ui.theme.LauncherThemeState
import org.alpha3.launcher.ui.theme.LocalLauncherTheme
import org.alpha3.launcher.ui.theme.NavStyle
import org.alpha3.launcher.ui.theme.ThemeBackdrop
import org.alpha3.launcher.utils.GameFilesPreferences

/** Settings > Appearance: the launcher's theme (a card for each, drawn in its own look) and Reduce motion. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettingsSection() {
    val current = LocalLauncherTheme.current
    val scope = rememberCoroutineScope()
    SettingSectionCard(title = "Appearance", icon = Icons.Outlined.Palette) {
        Text(
            "The launcher's look and layout. Classic is the launcher as it always was.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LauncherTheme.entries.forEach { theme ->
                ThemeCard(theme, selected = theme == current.theme) {
                    scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.UI_THEME_KEY, theme.id) }
                }
            }
        }
        SettingRow(title = "Reduce motion", subtitle = "Holds the animated themes still (battery and heat)") {
            Switch(
                checked = current.reduceMotion,
                onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.REDUCE_MOTION_KEY, it) } }
            )
        }
    }
}

private fun NavStyle.describe(): String = when (this) {
    NavStyle.Classic -> "swiping tabs"
    NavStyle.Tiles -> "tiles"
    NavStyle.Segmented -> "segments"
    NavStyle.Rail -> "side rail"
    NavStyle.Text -> "text tabs"
    NavStyle.Chips -> "chips"
    NavStyle.Radial -> "radial menu"
    NavStyle.Orbit -> "orbit menu"
    NavStyle.Slots -> "item slots"
    NavStyle.Underline -> "Material tabs"
}

@Composable
private fun ThemeCard(theme: LauncherTheme, selected: Boolean, onClick: () -> Unit) {
    val t = theme.tokens
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) t.primary else Color.White.copy(alpha = 0.2f), shape)
            .clickable(onClick = onClick)
    ) {
        // a preview: the theme's background (still), its accent and a mod row as it draws one
        Box(Modifier.fillMaxWidth().height(84.dp)) {
            ThemeBackdrop(LauncherThemeState(theme, reduceMotion = true), Modifier.fillMaxSize())
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(t.primary, t.secondary, t.dim).forEach {
                        Box(Modifier.size(width = 22.dp, height = 8.dp).background(it, RoundedCornerShape(t.corner.coerceAtMost(4).dp)))
                    }
                }
                Box(
                    Modifier.fillMaxWidth().height(14.dp)
                        .background(t.surface, RoundedCornerShape(t.corner.coerceAtMost(6).dp))
                )
                Box(
                    Modifier.width(52.dp).height(18.dp)
                        .background(t.primary, RoundedCornerShape(t.corner.coerceAtMost(9).dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("PLAY", color = t.background, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = t.display)
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(Color(0xFF1F1E23)).padding(8.dp)) {
            Text(theme.title, color = if (selected) t.primary else Color.White, fontFamily = t.display, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            Text(theme.blurb, color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(t.nav.describe(), color = t.primary.copy(alpha = 0.9f), fontSize = 10.sp)
        }
    }
}
