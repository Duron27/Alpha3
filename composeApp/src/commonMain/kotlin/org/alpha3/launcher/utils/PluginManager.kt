package org.alpha3.launcher.utils

/** One plugin jar found in a plugin folder. [loaded] is whether its code is running in this launcher session. */
data class PluginInfo(
    val file: String,
    val name: String,
    val enabled: Boolean,
    val loaded: Boolean,
    val error: String?,
)

/** Lists the plugins a platform can load and switches them on or off; a change applies when the launcher restarts. */
interface PluginManager {
    /** Folders searched for plugin jars. */
    val folders: List<String>

    fun list(): List<PluginInfo>

    fun setEnabled(file: String, enabled: Boolean)
}
