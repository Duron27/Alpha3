@file:Suppress("unused")   // silence warnings for the JNI stubs
package org.openmw.utils

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.paths.OpenMWPathsAndroid
import org.alpha3.launcher.ui.controls.UIStateManager.uqmVersion
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

private const val NUM_DRIVE_SLOTS = 11   // same value as in uqm/units.h
private const val NUM_JET_SLOTS   = 8   // same value as in uqm/units.h

// ---------------------------------------------------------------------
// JNI stubs
// ---------------------------------------------------------------------
external fun getActivity(): Int
external fun isInBattle(): Boolean
external fun getShipScreenX(): Int
external fun getShipScreenY(): Int
external fun getLogX(): Int
external fun getLogY(): Int
external fun getAutopilotX(): Int
external fun getAutopilotY(): Int
external fun getIPLocationX(): Int
external fun getIPLocationY(): Int
external fun getCrew(): Int
external fun getFuel(): Int
external fun getRU(): Int
external fun getMinerals(out: IntArray)
external fun getModules(out: ByteArray)
external fun getDriveSlots(out: ByteArray)
external fun getJetSlots(out: ByteArray)
external fun getShipName(): String
external fun getCommanderName(): String

external fun getShipFacing(): Int
external fun getTravelAngle(): Int
external fun getSpeedX(): Float
external fun getSpeedY(): Float
external fun isInOrbit(): Boolean
external fun getIPPlanet(): Int
external fun getEncounterRace(): Int
external fun getBattleRace(side: Int): Int

// More state (see cpp/MegaMod/jni_engine.c for the layout of each array)
external fun getGameClock(out: IntArray)
external fun getSisInfo(out: IntArray)
external fun getPlanetName(): String
external fun getCosts(): IntArray
external fun getQueueCounts(out: IntArray)
external fun getVelocity(out: IntArray)
external fun getCurrentStar(out: IntArray): Boolean
external fun getFleets(): IntArray
external fun getEscortShips(): IntArray
external fun getBattleShip(side: Int, out: IntArray): Boolean
external fun getGameStateNames(): Array<String>
external fun getGameStateValues(): IntArray
external fun getGameStateValue(name: String): Int

// SPECIES_ID order of the engine (uqm/races.h)
private val speciesNames = listOf(
    "None", "Arilou", "Chmmr", "Earthling", "Orz", "Pkunk", "Shofixti", "Spathi", "Supox", "Thraddash", "Utwig",
    "Vux", "Yehat", "Melnorme", "Druuge", "Ilwrath", "Mycon", "Slylandro", "Umgah", "Ur-Quan", "Zoq-Fot-Pik",
    "Syreen", "Kohr-Ah", "Androsynth", "Chenjesu", "Mmrnmhrm", "SIS", "Sa-Matra", "Ur-Quan probe"
)
private fun speciesName(id: Int) = speciesNames.getOrNull(id)?.let { "$it ($id)" } ?: "$id"
private val alliedNames = listOf("allied", "hostile", "dead")
private val mineralNames = listOf("Common", "Corrosive", "Base metal", "Noble", "Rare earth", "Precious", "Radioactive", "Exotic")

// ---------------------------------------------------------------------
// Helper data class – one snapshot of the whole game state
// ---------------------------------------------------------------------
private data class GameState(
    val activity: Int,
    val inBattle: Boolean,
    val shipScreen: Pair<Int, Int>,
    val universe: Pair<Int, Int>,
    val autopilot: Pair<Int, Int>,
    val ipCursor: Pair<Int, Int>,
    val crew: Int,
    val fuel: Int,
    val ru: Int,
    val minerals: IntArray,
    val modules: ByteArray,
    val driveSlots: ByteArray,
    val jetSlots: ByteArray,
    val shipName: String,
    val commanderName: String,
    val shipFacing: Int,
    val travelAngle: Int,
    val speedX: Float,
    val speedY: Float,
    val inOrbit: Boolean,
    val ipPlanet: Int,
    val encounterRace: Int,
    val playerBattleRace: Int,
    val npcBattleRace: Int,
    val clock: IntArray,
    val sis: IntArray,
    val planetName: String,
    val costs: IntArray,
    val queues: IntArray,
    val velocity: IntArray,
    val star: IntArray?,
    val fleets: IntArray,
    val escorts: IntArray,
    val playerShip: IntArray?,
    val npcShip: IntArray?,
    val flagValues: IntArray
)

// ---------------------------------------------------------------------
// Native wrapper
// ---------------------------------------------------------------------
private fun fetchGameState(): GameState {
    val minerals = IntArray(8)
    val modules = ByteArray(16)
    val driveSlots = ByteArray(NUM_DRIVE_SLOTS)
    val jetSlots   = ByteArray(NUM_JET_SLOTS)
    val act = getActivity()

    getDriveSlots(driveSlots)
    getJetSlots(jetSlots)
    getMinerals(minerals)
    getModules(modules)
    val clock = IntArray(5).also { getGameClock(it) }
    val sis = IntArray(10).also { getSisInfo(it) }
    val queues = IntArray(5).also { getQueueCounts(it) }
    val velocity = IntArray(7).also { getVelocity(it) }
    val star = IntArray(6).takeIf { getCurrentStar(it) }
    val playerShip = IntArray(9).takeIf { getBattleShip(0, it) }
    val npcShip = IntArray(9).takeIf { getBattleShip(1, it) }

    return GameState(
        activity = act,
        inBattle = isInBattle(),
        shipScreen = getShipScreenX() to getShipScreenY(),
        universe = getLogX() to getLogY(),
        autopilot = getAutopilotX() to getAutopilotY(),
        ipCursor = getIPLocationX() to getIPLocationY(),
        crew = getCrew(),
        fuel = getFuel(),
        ru = getRU(),
        minerals = minerals,
        modules = modules,
        driveSlots = driveSlots,
        jetSlots = jetSlots,
        shipName = getShipName(),
        commanderName = getCommanderName(),
        shipFacing = getShipFacing(),
        travelAngle = getTravelAngle(),
        speedX = getSpeedX(),
        speedY = getSpeedY(),
        inOrbit = isInOrbit(),
        ipPlanet = getIPPlanet(),
        encounterRace = getEncounterRace(),
        playerBattleRace = getBattleRace(0),
        npcBattleRace = getBattleRace(1),
        clock = clock,
        sis = sis,
        planetName = getPlanetName(),
        costs = getCosts(),
        queues = queues,
        velocity = velocity,
        star = star,
        fleets = getFleets(),
        escorts = getEscortShips(),
        playerShip = playerShip,
        npcShip = npcShip,
        flagValues = getGameStateValues()
    )
}

// ---------------------------------------------------------------------
// Compose UI
// ---------------------------------------------------------------------
/** Logs every named story flag that changed since the last look (logcat tag UQMState), after one full dump. */
private object FlagLog {
    private var names: Array<String>? = null
    private var last: IntArray? = null
    private val changes = ArrayDeque<String>()

    /** The latest changes, newest last. */
    fun recent(): List<String> = changes.toList()

    fun update(values: IntArray) {
        val n = names ?: getGameStateNames().also { names = it }
        val before = last
        if (before == null || before.size != values.size) {
            android.util.Log.i("UQMState", "Game state: " + n.indices.filter { values.getOrElse(it) { 0 } != 0 }
                .joinToString { "${n[it]}=${values[it]}" })
        } else {
            for (i in values.indices) if (values[i] != before[i]) {
                val line = "${n.getOrElse(i) { "#$i" }}: ${before[i]} -> ${values[i]}"
                android.util.Log.i("UQMState", line)
                changes.addLast(line)
                if (changes.size > 8) changes.removeFirst()
            }
        }
        last = values
    }

    fun names(): Array<String> = names ?: getGameStateNames().also { names = it }
}

// CurrentActivity: the low byte says where the player is, the high byte holds flags (battle, pause, ...)
private val activityNames = listOf(
    "Main menu / Super Melee", "Final battle", "Encounter", "Hyperspace", "Solar system", "Won final battle",
    "Quasispace", "Planet orbit", "Starbase"
)

private fun battleShipText(ship: IntArray?): String = if (ship == null) "no ship yet" else
    "${speciesName(ship[0])} ${ship[1]}/${ship[2]} ${if (ship[7] != 0) "flagship" else "escort"}, ${ship[8]} left"

/**
 * The text of the "UQM game info" box in the overlay bar (see Overlay.kt): one block per topic, battle first while a
 * fight is on. The full list of story flags is in logcat (tag UQMState); the box shows how many are set and the latest
 * changes.
 */
fun uqmInfoText(): String {
    val s = fetchGameState().also { FlagLog.update(it.flagValues) }
    return buildString {
        if (s.inBattle) {
            appendLine("BATTLE")
            appendLine("  you: ${battleShipText(s.playerShip)}")
            appendLine("  foe: ${battleShipText(s.npcShip)}")
        }
        appendLine("SHIP  ${s.shipName.ifBlank { "-" }} (${s.commanderName.ifBlank { "-" }})")
        appendLine("  crew ${s.crew}  fuel ${s.fuel}  ${s.ru} RU")
        appendLine("  cargo ${s.sis[0]} minerals, ${s.sis[1]} bio, ${s.sis[2]} landers")
        appendLine("  " + s.minerals.withIndex().filter { it.value > 0 }
            .joinToString { (i, v) -> "${mineralNames.getOrElse(i) { "#$i" }} $v" }.ifEmpty { "no minerals" })
        if (s.escorts.size >= 3) {
            appendLine("ESCORTS (${s.escorts.size / 3})")
            for (i in 0..s.escorts.size - 3 step 3) {
                appendLine("  ${speciesName(s.escorts[i])} ${s.escorts[i + 1]}/${s.escorts[i + 2]}")
            }
        }
        val c = s.clock
        appendLine("SPACE  ${activityNames.getOrNull(s.activity and 0xFF) ?: s.activity}")
        appendLine("  date %d-%02d-%02d".format(c[2], c[1], c[0]))
        appendLine("  at ${s.universe.first}, ${s.universe.second}" +
            if (s.autopilot.first >= 0) "  autopilot ${s.autopilot.first}, ${s.autopilot.second}" else "")
        s.star?.let { appendLine("  star (${it[0]}, ${it[1]}) type ${it[2]} #${it[3]}") }
        if (s.planetName.isNotBlank()) appendLine("  planet ${s.planetName}")
        val speed = sqrt(s.speedX * s.speedX + s.speedY * s.speedY)
        appendLine("  facing ${s.shipFacing} heading ${s.travelAngle} speed %.2f".format(speed))
        appendLine("  orbiting ${if (s.inOrbit) "yes (planet ${s.ipPlanet})" else "no"}  encounter ${speciesName(s.encounterRace)}")
        appendLine("  nearby ${s.queues[3]} groups, ${s.queues[4]} encounters, ${s.queues[2]} enemy ships")
        val f = s.fleets
        if (f.size >= 14) {
            appendLine("ALIENS")
            for (i in 0..f.size - 14 step 14) {
                val moving = if (f[i + 2] > 0) ", ${f[i + 2]} d away" else ""
                val where = if (f[i + 1] == 2) "" else " (${f[i + 5]}, ${f[i + 6]}) str ${f[i + 7]}/${f[i + 8]}$moving"
                appendLine("  ${speciesName(f[i])} ${alliedNames.getOrElse(f[i + 1]) { "?" }}$where")
            }
        }
        appendLine("STORY  ${s.flagValues.count { it != 0 }} of ${FlagLog.names().size} flags set")
        FlagLog.recent().forEach { appendLine("  $it") }
    }.trimEnd()
}

suspend fun fetchLatestContentCommit(): String = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://api.github.com/repos/JHGuitarFreak/UQM-MegaMod-Content/commits/HEAD")
            .header("Accept", "application/vnd.github.VERSION.sha")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                response.body.string().trim()
            } else {
                "master"
            }
        }
    } catch (e: Exception) {
        "master"
    }
}

fun getContentCommitHash(context: Context): String {
    return try {
        context.assets.open("uqm_content_commit.txt").bufferedReader().use { it.readText().trim() }
    } catch (e: Exception) {
        "master"
    }
}

data class DownloadItem(
    val fileName: String,
    val url: String,
    val isMainContent: Boolean = false,
    val progress: MutableStateFlow<Float> = MutableStateFlow(0f),
    val isDownloading: MutableStateFlow<Boolean> = MutableStateFlow(false),
    val isComplete: MutableStateFlow<Boolean> = MutableStateFlow(false),
    val fileSize: MutableStateFlow<Long> = MutableStateFlow(-1L)
)

object UqmDownloadManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val items = mutableMapOf<String, DownloadItem>()

    fun getItem(fileName: String, url: String, isMainContent: Boolean = false): DownloadItem {
        return items.getOrPut(fileName) {
            val item = DownloadItem(fileName, url, isMainContent)
            checkInitialStatus(item)
            item
        }
    }

    private fun checkInitialStatus(item: DownloadItem) {
        if (item.isMainContent) {
            if (uqmContentInstalled()) {
                item.isComplete.value = true
                item.fileSize.value = contentPackageFile().length()
            }
        } else {
            val file = getFileFor(item.fileName)
            if (file.exists()) {
                item.isComplete.value = true
                item.fileSize.value = file.length()
            } else {
                scope.launch(Dispatchers.IO) {
                    val size = getFileSize(item.url)
                    item.fileSize.value = size
                }
            }
        }
    }

    fun getFileFor(fileName: String): File {
        val directory = OpenMWPaths.USER_FILE_STORAGE + "/uqm/content/addons"
        return File(directory, fileName)
    }

    fun startDownload(context: Context, item: DownloadItem) {
        if (item.isDownloading.value || item.isComplete.value) return
        item.isDownloading.value = true
        scope.launch(Dispatchers.IO) {
            try {
                if (item.isMainContent) {
                    extractMainContent(context) { progress ->
                        item.progress.value = progress
                    }
                } else {
                    val file = getFileFor(item.fileName)
                    file.parentFile?.mkdirs()
                    downloadFile(item.url, file) { progress ->
                        item.progress.value = progress
                    }
                }
                item.isComplete.value = true
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                item.isDownloading.value = false
            }
        }
    }

    fun uninstall(item: DownloadItem) {
        if (item.isMainContent) {
            uqmContentRoot().deleteRecursively()
        } else {
            val file = getFileFor(item.fileName)
            if (file.exists()) {
                file.delete()
            }
        }
        item.isComplete.value = false
        item.progress.value = 0f
    }
}

// The core content (root files + base/) is bundled into the APK by the native build as the engine's base content
// package (a zip). The engine mounts the package straight from content/packages, so only the handful of root files it
// reads from disk (version, uqm.rmp, gamecontrollerdb.txt, ...) are unpacked; base/ stays inside the package.
private const val CONTENT_PACKAGE = "mm-content.uqm"
private const val CONTENT_PACKAGE_BYTES = 27_600_000L

private fun contentPackageFile(): File = File(uqmContentRoot(), "packages/$CONTENT_PACKAGE")

private suspend fun extractMainContent(
    context: Context,
    updateProgress: (Float) -> Unit
) = withContext(Dispatchers.IO) {
    val targetDir = uqmContentRoot()
    targetDir.mkdirs()
    val rootDir = targetDir.canonicalFile

    // The package is copied beside a temp name first, so a half-copied file is never mounted
    val pack = contentPackageFile()
    pack.parentFile?.mkdirs()
    val partial = File(pack.parentFile, "$CONTENT_PACKAGE.part")
    val total = CONTENT_PACKAGE_BYTES // only used for the progress bar
    context.assets.open(CONTENT_PACKAGE).use { input ->
        partial.outputStream().use { out ->
            val buffer = ByteArray(256 * 1024)
            var copied = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                copied += read
                updateProgress((copied.toFloat() / total).coerceAtMost(1f) * 0.95f)
            }
        }
    }
    if (pack.exists()) pack.delete()
    if (!partial.renameTo(pack)) throw java.io.IOException("Could not move the content package into place")

    // Root files only: anything in a folder stays in the package
    ZipInputStream(context.assets.open(CONTENT_PACKAGE).buffered()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && !entry.name.contains('/')) {
                val outFile = File(targetDir, entry.name).canonicalFile
                if (outFile.path.startsWith(rootDir.path + File.separator)) {
                    outFile.outputStream().use { out -> zip.copyTo(out) }
                }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    updateProgress(1f)
}

/**
 * Where the core UQM content lives: the base content package in `packages/` plus a few root files. On the app's own
 * storage so launching never waits on shared storage. Only the add-on packs stay in Alpha3/uqm/content/addons.
 */
fun uqmContentRoot(): File = File(OpenMWPathsAndroid.appContext.filesDir, "uqm-content")

/** True when the base package and the root files the engine reads from disk are in place. */
private fun uqmContentInstalled(): Boolean =
    contentPackageFile().length() > 0 && File(uqmContentRoot(), "uqm.rmp").exists() && File(uqmContentRoot(), "version").exists()

/** Installs the bundled core content if it is not there yet, so launching UQM never depends on the download card. */
fun ensureUqmContent(context: Context) {
    if (uqmContentInstalled()) return
    try {
        // Unpacks of earlier versions left ~12,000 loose files in base/; the package replaces them
        File(uqmContentRoot(), "base").deleteRecursively()
        runBlocking { extractMainContent(context) { } }
        // the bundled archive carries its own "version" file; keep the one the app writes
        UserManageAssets(context).installUQMResourceFiles()
    } catch (e: Exception) {
        android.util.Log.e("UQM", "Could not install the bundled content", e)
    }
}

suspend fun getFileSize(url: String): Long {
    return withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val request = Request.Builder().url(url).head().build()
            client.newCall(request).execute().use { response ->
                response.header("Content-Length")?.toLongOrNull() ?: -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }
}

@Composable
fun UqmDownloads() {
    val context = LocalContext.current
    val commitHash = remember(context) { getContentCommitHash(context) }

    val files = remember {
        listOf(
            "mm-${uqmVersion}-dosmode.uqm",
            "mm-${uqmVersion}-volasaurus-space-music.uqm",
            "uqm-0.8.0-3DOMusicRemastered.uqm",
            "mm-${uqmVersion}-3dovoice.uqm",
            "mm-${uqmVersion}-3domode.uqm",
            "uqm-0.8.0-3dovideo.uqm",
            "mm-remix-timing.uqm",
            "mm-${uqmVersion}-volasaurus-remix-pack.uqm",
            "mm-${uqmVersion}-SyreenVoiceFix.uqm",
            "mm-${uqmVersion}-sol-textures.uqm",
            "mm-${uqmVersion}-rmx-utwig.uqm",
            "mm-${uqmVersion}-purple-urquan-background.uqm",
            "mm-${uqmVersion}-MelnormeVoiceFix.uqm",
            "mm-${uqmVersion}-hd-content.uqm",
            "mm-${uqmVersion}-hd-classic-pack.uqm",
            "mm-${uqmVersion}-distorted-hayes-audio.uqm"
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            val mainItem = remember(commitHash) {
                UqmDownloadManager.getItem(
                    "Core Game Content (${commitHash.take(7)})",
                    "https://github.com/JHGuitarFreak/UQM-MegaMod-Content/archive/$commitHash.zip",
                    isMainContent = true
                )
            }
            DownloadCard(mainItem, commitHash)
        }

        items(files) { fileName ->
            val url = "https://raw.githubusercontent.com/JHGuitarFreak/UQM-MegaMod-Content/$commitHash/addons/$fileName"
            val item = remember(fileName, commitHash) { UqmDownloadManager.getItem(fileName, url) }
            DownloadCard(item, commitHash)
        }
    }
}

@Composable
fun DownloadCard(item: DownloadItem, commitHash: String) {
    val context = LocalContext.current
    val progress by item.progress.collectAsState()
    val isDownloading by item.isDownloading.collectAsState()
    val isComplete by item.isComplete.collectAsState()
    val fileSize by item.fileSize.collectAsState()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
        color = Color.Black.copy(alpha = 0.4f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (isComplete) Color.Green.copy(alpha = 0.2f)
                            else Color.White.copy(alpha = 0.1f),
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isComplete) Icons.Default.DownloadDone else Icons.Default.Download,
                        contentDescription = null,
                        tint = if (isComplete) Color.Green else Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    val sizeText = if (fileSize > 0) "${fileSize / (1024 * 1024)} MB" else if (item.isMainContent) "Full Package" else "---"
                    Text(
                        text = if (isDownloading) {
                            if (progress > 0f) "${(progress * 100).toInt()}%" else "Downloading..."
                        } else sizeText,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                }

                if (isComplete) {
                    IconButton(
                        onClick = { UqmDownloadManager.uninstall(item) },
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.Red.copy(alpha = 0.7f))
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Uninstall")
                    }
                } else {
                    Button(
                        onClick = { UqmDownloadManager.startDownload(context, item) },
                        enabled = !isDownloading,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.2f),
                            contentColor = Color.White,
                            disabledContainerColor = Color.White.copy(alpha = 0.05f)
                        )
                    ) {
                        Text(
                            text = if (isDownloading) "..." else "Install",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (isDownloading) {
                Spacer(modifier = Modifier.height(8.dp))
                val barModifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                // Size is unknown (e.g. GitHub archive zips are chunked), so progress stays 0
                if (progress > 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = barModifier,
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = barModifier,
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )
                }
            }
        }
    }
}

private suspend fun downloadFile(
    url: String,
    outputFile: File,
    updateProgress: (Float) -> Unit
) {
    withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection()
        val totalSize = connection.contentLength.toFloat()
        var downloadedSize = 0

        println("Downloading to: ${outputFile.absolutePath}")
        println("Total size: $totalSize bytes")

        connection.getInputStream().use { input ->
            outputFile.outputStream().use { output ->
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedSize += bytesRead
                    if (totalSize > 0) {
                        updateProgress(downloadedSize / totalSize)
                    }
                }
            }
        }
        println("Download complete. File size on disk: ${outputFile.length()} bytes")
    }
}
