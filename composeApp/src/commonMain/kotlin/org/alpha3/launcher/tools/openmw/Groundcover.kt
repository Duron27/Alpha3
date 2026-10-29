// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.openmw

/**
 * Whether [path] looks like an OpenMW groundcover plugin, to be loaded with `groundcover=` rather than
 * `content=`: nothing but grass statics (STAT records whose model is under grass\, or whose id starts with
 * grs_) and the cells placing them, with nearly every reference pointing at those statics. A plugin that
 * also adds other records, or places other objects, is a normal content plugin.
 */
fun isGroundcoverPlugin(fs: ToolFs, path: String, encoding: String = "win1252"): Boolean {
    val codec = TextCodec.forName(encoding)
    val name = baseName(path)
    val statics = HashSet<String>()
    var staticRecords = 0
    var grassStatics = 0
    var cells = 0
    val cellRefIds = ArrayList<String>()
    val records = try {
        scanPlugin(PluginFile(name, path, fs.size(path)), setOf("STAT", "CELL"), codec, fs) { tag, _, body ->
            if (tag == "STAT") {
                var id = ""
                var model = ""
                for (s in readSubs(body)) {
                    if (s.tag == "NAME") id = codec.decode(s.data).lowercase()
                    if (s.tag == "MODL") model = codec.decode(s.data).lowercase()
                }
                staticRecords++
                statics.add(id)
                if ("grass" in model || id.startsWith("grs_")) grassStatics++
            } else {
                cells++
                for (r in parseCell(body, name, emptyList(), codec).refs) cellRefIds.add(r.objectId.lowercase())
            }
        }
    } catch (e: ToolException) {
        return false
    }
    if (staticRecords == 0 || grassStatics != staticRecords || records != staticRecords + cells) return false
    val ownRefs = cellRefIds.count { it in statics }
    return cellRefIds.isNotEmpty() && ownRefs * 10 >= cellRefIds.size * 9
}
