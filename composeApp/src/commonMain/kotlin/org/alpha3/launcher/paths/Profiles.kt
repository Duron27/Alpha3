package org.alpha3.launcher.paths

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.alpha3.launcher.io.File

/**
 * Profiles: one config folder per mod list, `Alpha3/config/<name>`, holding that list's openmw.cfg and settings.cfg
 * (and, because the engine is started with it as its user-data folder too, its saves, screenshots, navmesh and
 * generated plugins). The default profile is the plain `Alpha3/config` folder and stays in use until another one is
 * switched to.
 *
 * Only [OpenMWPaths] reads [folder]; everything else asks it for a path.
 */
object Profiles {
    /** The name of the default profile, which has no folder of its own. */
    const val DEFAULT = ""

    private const val ACTIVE_FILE = "active_profile.txt"
    private const val MARKER = ".profile"
    private val reserved = setOf("symbols")

    /** Bumped when the active profile changes, so screens that show config files reload. */
    var revision by mutableIntStateOf(0)
        private set

    /** Bumped when a profile is created or deleted; lists of profiles rebuild, other screens are left alone. */
    var listRevision by mutableIntStateOf(0)
        private set

    private val base get() = OpenMWPaths.BASE_CONFIG
    private var cached: String? = null

    /** The active profile name, [DEFAULT] for the plain config folder. */
    val active: String
        get() = cached ?: read().also { cached = it }

    val isDefault: Boolean get() = active == DEFAULT

    /** The config folder of the active profile. */
    val folder: String get() = folderOf(active)

    fun folderOf(name: String): String = if (name == DEFAULT) base else "$base/$name"

    /** The names of the profiles that exist, not counting the default. */
    fun list(): List<String> =
        File(base).listFiles()
            ?.filter { it.isDirectory && it.name !in reserved && !it.name.startsWith(".") &&
                (File(it, MARKER).exists() || File(it, "openmw.cfg").exists()) }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    fun exists(name: String): Boolean = name == DEFAULT || File(folderOf(name)).isDirectory

    /** A name that is safe as a folder name: letters, digits, dot, dash and underscore. */
    fun sanitize(name: String): String =
        name.trim().map { if (it.isLetterOrDigit() && it.code < 128 || it in "._-") it else '-' }.joinToString("")
            .trim('.', '-').take(64)

    /**
     * Makes the profile if it is missing, starting it from the default profile's openmw.cfg and settings.cfg so the
     * game and graphics settings carry over. Returns the folder.
     */
    fun create(name: String): String {
        val clean = sanitize(name)
        require(clean.isNotEmpty() && clean !in reserved) { "Not a usable profile name: \"$name\"" }
        val dir = File(folderOf(clean))
        if (!dir.exists()) {
            dir.mkdirs()
            for (file in listOf("openmw.cfg", "settings.cfg")) {
                val source = File(base, file)
                val target = File(dir, file)
                if (source.isFile && !target.exists()) runCatching { source.copyTo(target) }
            }
        }
        File(dir, MARKER).takeIf { !it.exists() }?.writeText("Alpha3 profile\n")
        listRevision++
        return dir.path
    }

    fun delete(name: String) {
        if (name == DEFAULT || name in reserved) return
        if (active == name) switchTo(DEFAULT)
        File(folderOf(name)).deleteRecursively()
        listRevision++
    }

    /** Makes [name] the active profile (creating it if needed); [DEFAULT] goes back to the plain config folder. */
    fun switchTo(name: String) {
        val clean = if (name == DEFAULT) DEFAULT else sanitize(name)
        if (clean != DEFAULT && !exists(clean)) create(clean)
        if (clean == active) return
        File(base).mkdirs()
        File(base, ACTIVE_FILE).writeText(clean)
        cached = clean
        revision++
    }

    private fun read(): String {
        val file = File(base, ACTIVE_FILE)
        val name = if (file.isFile) runCatching { file.readText().trim() }.getOrDefault(DEFAULT) else DEFAULT
        return if (name != DEFAULT && !File(folderOf(name)).isDirectory) DEFAULT else name
    }

    /**
     * Engine options that make [folder] (the active profile unless given) the config chain and the user-data folder.
     * The default profile adds nothing but its config path, as before. For another profile the app's global config
     * stays first and the profile's folder replaces the user config that the global openmw.cfg would otherwise add.
     */
    fun engineArguments(folder: String = this.folder): List<String> =
        if (folder == OpenMWPaths.BASE_CONFIG) listOf("--config", folder)
        else listOf("--replace=config", "--config", folder, "--user-data", folder)
}
