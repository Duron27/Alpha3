package org.alpha3.launcher.ui.page.mod

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.tools.lightfixes.LIGHT_CONFIG_NAME
import org.alpha3.launcher.tools.lightfixes.LightFixesConfig
import org.alpha3.launcher.tools.openmw.ToolFs
import org.alpha3.launcher.tools.openmw.ToolPlatform
import org.alpha3.launcher.tools.openmw.dirName
import org.alpha3.launcher.tools.openmw.joinPath
import org.alpha3.launcher.tools.openmw.readOpenMW
import org.alpha3.launcher.tools.render.LightPreview
import org.alpha3.launcher.tools.render.LightPreviewSettings

private val Gold = Color(202, 165, 96)
private val Panel = Color(0xFF1E1E1E)
private val Section = Color(0xFF282828)

private fun twoDecimals(v: Float): String {
    val n = kotlin.math.round(v * 100f).toInt()
    return (if (n < 0) "-" else "") + (kotlin.math.abs(n) / 100) + "." + (kotlin.math.abs(n) % 100).toString().padStart(2, '0')
}

private fun loadConfig(fs: ToolFs, path: String): LightFixesConfig =
    try {
        val name = fs.list(dirName(path))?.firstOrNull { it.equals(LIGHT_CONFIG_NAME, ignoreCase = true) }
        name?.let { LightFixesConfig.fromToml(fs.readText(joinPath(dirName(path), it))) } ?: LightFixesConfig()
    } catch (e: Exception) {
        LightFixesConfig()
    }

/** Data folders holding Morrowind.bsa, which the preview reads its objects from. */
private fun morrowindDataDirs(fs: ToolFs, platform: ToolPlatform, cfg: String): List<String> =
    runCatching { readOpenMW(cfg, platform, fs).data }.getOrDefault(emptyList())
        .filter { d -> fs.list(d)?.any { it.equals("Morrowind.bsa", ignoreCase = true) } == true }
        .distinct()

/**
 * Edits lightconfig.toml with a live preview: Morrowind objects lit by the standard and coloured light settings,
 * as S3LightFixes would change them. Settings are written when Save is pressed; the next Light fixes run uses them.
 */
@Composable
fun LightSettingsDialog(fs: ToolFs, platform: ToolPlatform, onDismiss: () -> Unit) {
    val cfgPath = OpenMWPaths.USER_OPENMW_CFG
    val config = remember { loadConfig(fs, cfgPath) }
    val dataDirs = remember { morrowindDataDirs(fs, platform, cfgPath) }

    var standardHue by remember { mutableFloatStateOf(config.standardHue) }
    var standardSat by remember { mutableFloatStateOf(config.standardSaturation) }
    var standardVal by remember { mutableFloatStateOf(config.standardValue) }
    var standardRadius by remember { mutableFloatStateOf(config.standardRadius) }
    var coloredHue by remember { mutableFloatStateOf(config.coloredHue) }
    var coloredSat by remember { mutableFloatStateOf(config.coloredSaturation) }
    var coloredVal by remember { mutableFloatStateOf(config.coloredValue) }
    var coloredRadius by remember { mutableFloatStateOf(config.coloredRadius) }
    var durationMult by remember { mutableFloatStateOf(config.durationMult) }
    var disableFlickering by remember { mutableStateOf(config.disableFlickering) }
    var disablePulse by remember { mutableStateOf(config.disablePulse) }
    var disableInteriorSun by remember { mutableStateOf(config.disableInteriorSun) }
    var disableNegative by remember { mutableStateOf(config.disableNegativeLights) }
    var autoEnable by remember { mutableStateOf(config.autoEnable) }
    var rain by remember { mutableStateOf(false) }
    var puddles by remember { mutableStateOf(false) }
    var rainAmount by remember { mutableFloatStateOf(0.4f) }
    var message by remember { mutableStateOf("") }

    fun reset() {
        standardHue = 0.62f; standardSat = 0.80f; standardVal = 0.57f; standardRadius = 1.20f
        coloredHue = 1.00f; coloredSat = 0.90f; coloredVal = 0.70f; coloredRadius = 1.10f
        durationMult = 2.50f; disableFlickering = true; disablePulse = false; disableInteriorSun = false
        disableNegative = true; autoEnable = false
    }

    fun save() {
        config.standardHue = standardHue; config.standardSaturation = standardSat
        config.standardValue = standardVal; config.standardRadius = standardRadius
        config.coloredHue = coloredHue; config.coloredSaturation = coloredSat
        config.coloredValue = coloredVal; config.coloredRadius = coloredRadius
        config.durationMult = durationMult; config.disableFlickering = disableFlickering
        config.disablePulse = disablePulse; config.disableInteriorSun = disableInteriorSun
        config.disableNegativeLights = disableNegative; config.autoEnable = autoEnable
        message = try {
            val dir = dirName(cfgPath)
            val existing = fs.list(dir)?.firstOrNull { it.equals(LIGHT_CONFIG_NAME, ignoreCase = true) } ?: LIGHT_CONFIG_NAME
            val target = joinPath(dir, existing)
            fs.mkdirs(dir)
            fs.delete(target)
            val bytes = config.toToml().encodeToByteArray()
            val w = fs.openWrite(target)
            try { w.write(bytes, 0, bytes.size) } finally { w.close() }
            "Saved $target"
        } catch (e: Exception) {
            "Could not save: ${e.message ?: e}"
        }
    }

    val preview = LightPreviewSettings(
        standardHue, standardSat, standardVal, standardRadius, coloredHue, coloredSat, coloredVal, coloredRadius,
        durationMult, disablePulse, disableFlickering, rain, puddles, rainAmount,
    )

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(8.dp)) {
            val wide = maxWidth > 700.dp
            val previewPane: @Composable (Modifier) -> Unit = { mod ->
                Column(modifier = mod, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LightPreview(
                        preview, fs, dataDirs,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                            .clip(RoundedCornerShape(8.dp)).background(Color.Black)
                            .border(1.dp, Gold.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Swatch("Standard light", standardHue, standardSat, standardVal)
                        Swatch("Coloured light", coloredHue, coloredSat, coloredVal)
                    }
                }
            }
            val controls: @Composable (Modifier) -> Unit = { mod ->
                Column(modifier = mod.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Light settings (lightconfig.toml)", color = Gold, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    SettingsCard("Standard light") {
                        Slide("Hue", standardHue, 0f..1f) { standardHue = it }
                        Slide("Saturation", standardSat, 0f..1f) { standardSat = it }
                        Slide("Value (brightness)", standardVal, 0f..1f) { standardVal = it }
                        Slide("Radius", standardRadius, 0.1f..5f) { standardRadius = it }
                    }
                    SettingsCard("Coloured light") {
                        Slide("Hue", coloredHue, 0f..1f) { coloredHue = it }
                        Slide("Saturation", coloredSat, 0f..1f) { coloredSat = it }
                        Slide("Value (brightness)", coloredVal, 0f..1f) { coloredVal = it }
                        Slide("Radius", coloredRadius, 0.1f..5f) { coloredRadius = it }
                    }
                    SettingsCard("Dynamics") {
                        Slide("Duration multiplier", durationMult, 0.1f..10f) { durationMult = it }
                        Toggle("Disable flickering", disableFlickering) { disableFlickering = it }
                        Toggle("Disable pulse", disablePulse) { disablePulse = it }
                        Toggle("Disable interior sun", disableInteriorSun) { disableInteriorSun = it }
                        Toggle("Disable negative lights", disableNegative) { disableNegative = it }
                        Toggle("Enable the plugin in openmw.cfg automatically", autoEnable) { autoEnable = it }
                    }
                    SettingsCard("Preview only") {
                        Toggle("Rain", rain) { rain = it }
                        if (rain) Slide("Rain intensity", rainAmount, 0f..1f) { rainAmount = it }
                        Toggle("Puddles", puddles) { puddles = it }
                    }
                    if (message.isNotEmpty()) Text(message, color = Color.LightGray, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = ::save, modifier = Modifier.weight(1f)) { Text("Save") }
                        OutlinedButton(onClick = ::reset, modifier = Modifier.weight(1f)) { Text("Defaults") }
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Close") }
                    }
                }
            }
            if (wide) {
                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    previewPane(Modifier.weight(1f).fillMaxHeight())
                    controls(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    previewPane(Modifier.fillMaxWidth().weight(1f))
                    controls(Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Swatch(label: String, h: Float, s: Float, v: Float) {
    Column {
        Text(label, color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(Color.hsv((h * 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))))
            Text(
                "  H ${(h * 360).toInt()}°  S ${(s * 100).toInt()}%  V ${(v * 100).toInt()}%",
                color = Color.White, fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Section),
        border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f)),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            content()
        }
    }
}

@Composable
private fun Slide(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.LightGray, fontSize = 12.sp)
            Text(twoDecimals(value), color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold, inactiveTrackColor = Color.Gray.copy(alpha = 0.3f)),
        )
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Gold, checkedTrackColor = Gold.copy(alpha = 0.5f)),
        )
    }
}
