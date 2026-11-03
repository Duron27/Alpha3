package org.alpha3.launcher.mods.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.IOException
import org.alpha3.launcher.io.File
import org.alpha3.launcher.utils.Log
import org.alpha3.launcher.utils.currentTimeMillis

/** cpp/modtools, the native mod tools: one JSON call each (see cpp/modtools/src/lib.rs). */
internal expect object ModTools {
    /** False when this build has no mod tools (the library is missing, or the platform does not link it yet). */
    val available: Boolean

    /** LOOT's sorting (cpp/modtools/src/lib.rs). */
    fun lootSort(requestJson: String): String

    /** A Wabbajack list as an install plan (cpp/modtools/src/wabbajack.rs). */
    fun wabbajackPlan(requestJson: String): String
}

/**
 * LOOT's sorting for the organizer: the plugins in the order LOOT's Morrowind masterlist and their masters give, and
 * what the masterlist says about them. It only returns an order; the organizer shows it, and the person saves it or
 * undoes it.
 */
object LootSort {
    // The masterlist branch that goes with libloot 0.29 (cpp/modtools/Cargo.toml)
    private const val BRANCH = "v0.29"
    private const val MASTERLIST_URL = "https://raw.githubusercontent.com/loot/morrowind/$BRANCH/masterlist.yaml"
    private const val PRELUDE_URL = "https://raw.githubusercontent.com/loot/prelude/$BRANCH/prelude.yaml"
    private const val REFRESH_MS = 24L * 60 * 60 * 1000

    val available: Boolean get() = ModTools.available

    private val dir get() = "$modStorage/loot"
    private val masterlist get() = File(dir, "masterlist.yaml")
    private val prelude get() = File(dir, "prelude.yaml")
    /** The person's own LOOT rules, when they put one there (LOOT's userlist.yaml format). */
    private val userlist get() = File(dir, "userlist.yaml")

    data class Message(val plugin: String, val level: String, val text: String)

    data class Result(
        /** Every plugin entry, the sorted ones in LOOT's order in the places they had, the others where they were. */
        val order: List<LoadOrder.Entry>,
        val messages: List<Message>,
        /** Plugins left where they were: switched off, or no file found in the switched-on data folders. */
        val skipped: List<String>,
    )

    /** Fetches the masterlist when there is none or it is a day old; an old copy is used when the fetch fails. */
    private suspend fun updateMasterlist(force: Boolean) {
        File(dir).mkdirs()
        for ((file, url) in listOf(masterlist to MASTERLIST_URL, prelude to PRELUDE_URL)) {
            val fresh = file.isFile && currentTimeMillis() - file.lastModified() < REFRESH_MS
            if (fresh && !force) continue
            try {
                val text = ModHttp.getText(url)
                File("${file.path}.new").apply { writeText(text) }.renameTo(file.also { it.delete() })
            } catch (e: Exception) {
                if (!file.isFile) throw IOException("Could not download LOOT's masterlist: ${e.message}")
                Log.w("LootSort", "Using the saved ${file.name}: ${e.message}")
            }
        }
    }

    /**
     * Sorts the switched-on [plugins] whose files [find] finds. [dataFolders] are the switched-on data folders, in
     * order, with their paths as on disk.
     */
    suspend fun sort(
        plugins: List<LoadOrder.Entry>,
        dataFolders: List<String>,
        find: (String) -> File?,
        refreshMasterlist: Boolean = false,
    ): Result = withContext(Dispatchers.IO) {
        if (!ModTools.available) throw IOException("LOOT is not in this build")
        updateMasterlist(refreshMasterlist)
        val found = plugins.filter { it.enabled }.mapNotNull { p -> find(p.value)?.let { p to it } }
            // LOOT needs each file name once; a second one with the same name is OpenMW's to overrule
            .distinctBy { it.first.value.lowercase() }
        val request = buildJsonObject {
            put("work_dir", "$dir/work")
            put("masterlist", masterlist.path)
            put("prelude", prelude.path)
            if (userlist.isFile) put("userlist", userlist.path)
            putJsonArray("data_paths") { dataFolders.forEach { add(it) } }
            putJsonArray("plugins") {
                for ((entry, file) in found) addJsonObject { put("name", entry.value); put("path", file.path) }
            }
            put("language", "en")
        }
        val answer = ModListManager.json.parseToJsonElement(ModTools.lootSort(request.toString())).jsonObject
        answer["error"]?.jsonPrimitive?.contentOrNull?.let { throw IOException(it) }
        val sorted = answer["order"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: throw IOException("LOOT gave no order")
        val messages = answer["messages"]?.jsonArray.orEmpty().map { m ->
            val o = m.jsonObject
            Message(
                plugin = o["plugin"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                level = o["level"]?.jsonPrimitive?.contentOrNull ?: "note",
                text = o["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }

        // The sorted plugins go into the places the sorted set had, in LOOT's order; the rest stay put
        val byName = found.associate { it.first.value.lowercase() to it.first }
        val queue = ArrayDeque(sorted.mapNotNull { byName[it.lowercase()] })
        val inSet = found.map { it.first }.toSet()
        val order = plugins.map { if (it in inSet) queue.removeFirstOrNull() ?: it else it }
        Result(order, messages, plugins.filter { it !in inSet }.map { it.value })
    }
}
