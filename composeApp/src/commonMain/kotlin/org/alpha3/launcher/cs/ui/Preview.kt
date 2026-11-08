package org.alpha3.launcher.cs.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.alpha3.launcher.cs.model.CsRecord
import org.alpha3.launcher.tools.openmw.Sub
import org.alpha3.launcher.tools.render.AssetViewerCore
import org.alpha3.launcher.tools.render.Bitmap
import org.alpha3.launcher.tools.render.MwAssets
import org.alpha3.launcher.tools.render.decodeBmp
import org.alpha3.launcher.tools.render.decodeDds
import org.alpha3.launcher.tools.render.decodeTga
import org.alpha3.launcher.tools.render.loadModel

/** What a record looks like: the meshes drawn together (data paths under meshes/) and its inventory icon. */
internal class PreviewSpec(val meshes: List<String>, val icon: String?) {
    val isEmpty get() = meshes.isEmpty() && icon == null
}

private fun zstr(sub: Sub?): String? {
    val d = sub?.data ?: return null
    var end = 0
    while (end < d.size && d[end] != 0.toByte()) end++
    return d.decodeToString(0, end).trim().takeIf { it.isNotEmpty() }
}

private fun meshPath(model: String) = model.replace('\\', '/').trimStart('/').let { if (it.startsWith("meshes/", true)) it else "meshes/$it" }

/**
 * The models a record shows: its own MODL; an NPC's head and hair (from the body parts it names; the rest of an
 * NPC is the race's body, which the game assembles on a skeleton and the preview leaves out).
 */
internal fun previewOf(s: CsSession, record: CsRecord): PreviewSpec {
    val subs = record.subs
    fun sub(tag: String) = zstr(subs.firstOrNull { it.tag == tag })
    val meshes = ArrayList<String>()
    if (record.tag == "NPC_") {
        for (tag in listOf("BNAM", "KNAM")) {
            val part = sub(tag) ?: continue
            val body = runCatching { s.doc.find("BODY", part) }.getOrNull() ?: continue
            zstr(body.subs.firstOrNull { it.tag == "MODL" })?.let { meshes += meshPath(it) }
        }
    } else {
        sub("MODL")?.let { meshes += meshPath(it) }
    }
    val icon = sub("ITEX")?.let { it.replace('\\', '/').let { p -> if (p.startsWith("icons/", true)) p else "icons/$p" } }
    return PreviewSpec(meshes, icon)
}

private fun readIcon(assets: MwAssets, path: String): Bitmap {
    // icons are named .tga but often shipped as .dds, like textures
    val base = path.substringBeforeLast('.')
    for (c in listOf(path, "$base.dds", "$base.tga", "$base.bmp")) {
        val bytes = assets.read(c) ?: continue
        val bmp = runCatching {
            when (c.substringAfterLast('.').lowercase()) {
                "dds" -> decodeDds(bytes)
                "tga" -> decodeTga(bytes)
                else -> decodeBmp(bytes)
            }
        }.getOrNull()
        if (bmp != null) return bmp
    }
    error("$path is not in the data folders")
}

/** A turnable 3D view of [spec]'s models (or its icon), with a button for a full-screen view with the part switches. */
@Composable
internal fun RecordPreview(s: CsSession, spec: PreviewSpec, title: String) {
    var showIcon by remember(spec.meshes, spec.icon) { mutableStateOf(spec.meshes.isEmpty()) }
    var full by remember { mutableStateOf(false) }
    val assets = s.workspace.assets
    val icon = showIcon && spec.icon != null
    val key = if (icon) "icon:${spec.icon}" else spec.meshes.joinToString("|")
    val subtitle = if (icon) spec.icon else spec.meshes.joinToString(", ") { it.removePrefix("meshes/") }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (spec.meshes.isNotEmpty() && spec.icon != null) {
                FilterChip(!showIcon, { showIcon = false }, label = { Text("Model") })
                FilterChip(showIcon, { showIcon = true }, label = { Text("Icon") })
            }
            Row(Modifier.weight(1f)) {}
            if (!icon) SmallButton("Full screen") { full = true }
        }
        val viewer: @Composable (Modifier, Boolean, (() -> Unit)?) -> Unit = { m, parts, close ->
            AssetViewerCore(
                title, key, isMesh = !icon,
                loadMesh = {
                    val bytes = spec.meshes.map { p -> assets.read(p) ?: error("${p.removePrefix("meshes/")} is not in the data folders") }
                    loadModel(title, bytes, assets)
                },
                loadImage = { readIcon(assets, spec.icon!!) },
                onDismiss = close, modifier = m, showParts = parts, subtitle = subtitle,
            )
        }
        viewer(Modifier.fillMaxWidth().height(if (icon) 200.dp else 380.dp), false, null)
        if (full) Dialog(onDismissRequest = { full = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            viewer(Modifier.fillMaxSize().padding(8.dp), true) { full = false }
        }
    }
}
