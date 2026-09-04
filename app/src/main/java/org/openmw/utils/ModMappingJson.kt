package org.openmw.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.openmw.Constants
import java.io.File

@Serializable
data class ModPluginMapping(
    val dataPath: String,
    val contentFiles: List<String> = emptyList(),
    val groundcoverFiles: List<String> = emptyList()
)

@Serializable
data class ModPluginMappingsStore(
    val mappings: List<ModPluginMapping> = emptyList()
)

object ModMappingJson {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mappingFile: File
        get() = File(Constants.USER_CONFIG, "mod_plugin_mappings.json")

    fun saveMappings(mappings: List<ModPluginMapping>) {
        try {
            val store = ModPluginMappingsStore(mappings)
            mappingFile.parentFile?.mkdirs()
            mappingFile.writeText(json.encodeToString(ModPluginMappingsStore.serializer(), store))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadMappings(): List<ModPluginMapping> {
        try {
            if (!mappingFile.exists()) return emptyList()
            val text = mappingFile.readText()
            if (text.isBlank()) return emptyList()
            val store = json.decodeFromString(ModPluginMappingsStore.serializer(), text)
            return store.mappings
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    /**
     * Automatically links data paths with content/groundcover plugins based on openmw.cfg and directory contents.
     */
    fun syncAndSaveFromOpenMWConfig(filePath: String = Constants.USER_OPENMW_CFG): List<ModPluginMapping> {
        val configMap = OpenMWConfigUtils.getOpenMWConfig(filePath)
        val dataLines = configMap[OpenMWConfigUtils.ConfigKeyType.Data].orEmpty()
        val contentLines = configMap[OpenMWConfigUtils.ConfigKeyType.Content].orEmpty()
        val groundcoverLines = configMap[OpenMWConfigUtils.ConfigKeyType.GroundCover].orEmpty()

        val mappings = mutableListOf<ModPluginMapping>()

        with(OpenMWConfigUtils) {
            // Extract plugin names (values after '=')
            val allContents = contentLines.mapNotNull { it.removeComment().getValue()?.removeSurrounding("\"") }
            val allGroundcovers = groundcoverLines.mapNotNull { it.removeComment().getValue()?.removeSurrounding("\"") }

            for (dataLine in dataLines) {
                val rawPath = dataLine.removeComment().getValue()?.removeSurrounding("\"") ?: continue
                val dataDir = File(rawPath)

                val matchedContents = mutableListOf<String>()
                val matchedGroundcovers = mutableListOf<String>()

                if (dataDir.exists() && dataDir.isDirectory) {
                    // Check which declared content/groundcover files exist inside this data directory (or subdirectories)
                    allContents.forEach { pluginName ->
                        if (findFileInDirectory(dataDir, pluginName)) {
                            matchedContents.add(pluginName)
                        }
                    }
                    allGroundcovers.forEach { pluginName ->
                        if (findFileInDirectory(dataDir, pluginName)) {
                            matchedGroundcovers.add(pluginName)
                        }
                    }
                }

                mappings.add(
                    ModPluginMapping(
                        dataPath = rawPath,
                        contentFiles = matchedContents,
                        groundcoverFiles = matchedGroundcovers
                    )
                )
            }
        }

        saveMappings(mappings)
        return mappings
    }

    private fun findFileInDirectory(dir: File, fileName: String): Boolean {
        val file = File(dir, fileName)
        if (file.exists()) return true
        // Search recursively in subdirectories
        return dir.listFiles()?.any { child ->
            child.isDirectory && findFileInDirectory(child, fileName)
        } ?: false
    }
}
