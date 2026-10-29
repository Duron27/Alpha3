package org.openmw.plugins

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import dalvik.system.DexClassLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.alpha3.launcher.paths.OpenMWPaths
import org.openmw.modDownloader.NxmDownloads
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

private const val TAG = "Plugins"

/** The entry point of a plugin jar: `plugin.properties` in the jar names the implementing class in its `class` key. */
interface LauncherPlugin {
    fun onLoad(host: PluginHost)
}

fun interface NexusPageOpener {
    /** Returns true when the plugin took over opening [url]; false leaves it to the browser. */
    fun open(url: String): Boolean
}

fun interface PluginScreenFactory {
    fun create(activity: Activity, screen: PluginScreen): View
}

interface PluginScreen {
    fun close()
}

interface PluginHost {
    val context: Context
    fun log(message: String)

    /** Replaces the browser tab the downloader opens for a free member's Nexus file page. */
    fun setNexusPageOpener(opener: NexusPageOpener)

    /** Hands an nxm:// link the plugin obtained to the downloader, as if the browser had sent it. */
    fun deliverNxm(uri: Uri)

    /** Shows a full-screen activity with the view the factory builds. */
    fun openScreen(factory: PluginScreenFactory): PluginScreen
}

/**
 * Loads plugin jars (dexed, with a `plugin.properties`) and gives them a small, fixed surface to hook into. Nothing a
 * plugin does lives in the launcher. Jars come from `Alpha3/plugins` on shared storage, so a file manager or adb push can
 * drop one in and a reinstall keeps them.
 */
object Plugins {
    @Volatile
    var nexusPageOpener: NexusPageOpener? = null
        private set

    private val screens = ConcurrentHashMap<Int, Pair<PluginScreenFactory, PluginScreen>>()
    private val nextScreen = AtomicInteger(1)
    private var loaded = false

    internal fun screen(id: Int) = screens[id]
    /** Closes the screens plugins have open (the downloader cancelled the run they were working for). */
    fun closeScreens() {
        screens.values.toList().forEach { runCatching { it.second.close() } }
    }

    internal fun forget(id: Int) {
        screens.remove(id)
    }

    private val loadedFiles = ConcurrentHashMap.newKeySet<String>()
    private val errors = ConcurrentHashMap<String, String>()

    internal fun folders(app: Context): List<File> {
        val dir = File(OpenMWPaths.USER_FILE_STORAGE, "plugins")
        // The folder exists so a jar can be dropped in without creating it first
        dir.mkdirs()
        // Jars an earlier version kept in the app's own folder move out to it
        File(app.filesDir, "plugins").listFiles { f -> f.extension == "jar" }?.forEach { old ->
            val target = File(dir, old.name)
            if (!target.exists()) runCatching { old.copyTo(target); old.delete() }
        }
        return listOf(dir)
    }

    internal fun jars(app: Context): List<File> =
        folders(app).flatMap { dir -> dir.listFiles { f -> f.extension == "jar" }?.toList().orEmpty() }.sortedBy { it.name }

    private fun prefs(app: Context) = app.getSharedPreferences("plugins", Context.MODE_PRIVATE)

    // Plugin code runs inside the launcher, so nothing loads until it was switched on in the Plugins dialog
    internal fun isEnabled(app: Context, file: String) = file in prefs(app).getStringSet("enabled", emptySet()).orEmpty()

    internal fun setEnabled(app: Context, file: String, enabled: Boolean) {
        val set = prefs(app).getStringSet("enabled", emptySet()).orEmpty().toMutableSet()
        if (enabled) set += file else set -= file
        prefs(app).edit().putStringSet("enabled", set).apply()
    }

    internal fun isLoaded(file: String) = file in loadedFiles
    internal fun error(file: String) = errors[file]

    private fun readManifest(jar: File): Properties? = try {
        ZipFile(jar).use { zip ->
            zip.getEntry("plugin.properties")?.let { entry -> Properties().apply { zip.getInputStream(entry).use(::load) } }
        }
    } catch (_: Exception) {
        null
    }

    internal fun displayName(jar: File) = readManifest(jar)?.getProperty("name") ?: jar.nameWithoutExtension

    @Synchronized
    fun loadAll(context: Context) {
        if (loaded) return
        loaded = true
        val app = context.applicationContext
        val cache = File(app.codeCacheDir, "plugins").apply { mkdirs() }
        for (jar in jars(app)) {
            if (!isEnabled(app, jar.name)) continue
            try {
                // Android refuses code from a writable file, so each jar runs from a read-only copy
                val copy = File(cache, "${jar.nameWithoutExtension}-${jar.lastModified()}.jar")
                if (!copy.exists()) {
                    jar.copyTo(copy, overwrite = true)
                    copy.setReadOnly()
                }
                val className = readManifest(copy)?.getProperty("class") ?: error("no plugin.properties with a class key")
                val plugin = DexClassLoader(copy.path, null, null, app.classLoader)
                    .loadClass(className).getDeclaredConstructor().newInstance() as LauncherPlugin
                plugin.onLoad(HostImpl(app, jar.name))
                loadedFiles += jar.name
                Log.i(TAG, "Loaded plugin ${jar.name}")
            } catch (t: Throwable) {
                errors[jar.name] = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "Plugin ${jar.name} failed", t)
            }
        }
    }

    private class HostImpl(override val context: Context, private val name: String) : PluginHost {
        override fun log(message: String) {
            Log.i(TAG, "[$name] $message")
        }

        override fun setNexusPageOpener(opener: NexusPageOpener) {
            nexusPageOpener = opener
        }

        override fun deliverNxm(uri: Uri) {
            CoroutineScope(Dispatchers.Main).launch { NxmDownloads.handle(context, uri) }
        }

        override fun openScreen(factory: PluginScreenFactory): PluginScreen {
            val id = nextScreen.getAndIncrement()
            val screen = object : PluginScreen {
                override fun close() {
                    forget(id)
                    context.sendBroadcast(Intent(PluginActivity.CLOSE_ACTION).setPackage(context.packageName).putExtra(PluginActivity.EXTRA_ID, id))
                }
            }
            screens[id] = factory to screen
            context.startActivity(
                Intent(context, PluginActivity::class.java).putExtra(PluginActivity.EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return screen
        }
    }
}

class AndroidPluginManager(private val app: Context) : org.alpha3.launcher.utils.PluginManager {
    override val folders get() = Plugins.folders(app).map { it.path }

    override fun list() = Plugins.jars(app).map {
        org.alpha3.launcher.utils.PluginInfo(
            file = it.name,
            name = Plugins.displayName(it),
            enabled = Plugins.isEnabled(app, it.name),
            loaded = Plugins.isLoaded(it.name),
            error = Plugins.error(it.name),
        )
    }

    override fun setEnabled(file: String, enabled: Boolean) = Plugins.setEnabled(app, file, enabled)
}
