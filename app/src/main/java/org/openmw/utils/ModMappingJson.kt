package org.openmw.utils

import android.util.Log
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
            Log.d("ModMappingJson", "Successfully saved ${mappings.size} mappings to ${mappingFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("ModMappingJson", "Error saving mappings to JSON", e)
            e.printStackTrace()
        }
    }

    fun loadMappings(): List<ModPluginMapping> {
        try {
            if (!mappingFile.exists()) {
                Log.d("ModMappingJson", "Mapping file does not exist at ${mappingFile.absolutePath}")
                return emptyList()
            }
            val text = mappingFile.readText()
            if (text.isBlank()) return emptyList()
            val store = json.decodeFromString(ModPluginMappingsStore.serializer(), text)
            Log.d("ModMappingJson", "Loaded ${store.mappings.size} mappings from ${mappingFile.absolutePath}")
            return store.mappings
        } catch (e: Exception) {
            Log.e("ModMappingJson", "Error loading mappings from JSON", e)
            e.printStackTrace()
            return emptyList()
        }
    }

    /**
     * Automatically links data paths with content/groundcover plugins based on openmw.cfg and directory contents.
     */
    fun syncAndSaveFromOpenMWConfig(
        filePath: String = Constants.USER_OPENMW_CFG,
        onProgress: (String) -> Unit = {}
    ): List<ModPluginMapping> {
        onProgress("Reading configuration...")
        Log.d("ModMappingJson", "Starting sync and indexing from config: $filePath")
        val configMap = OpenMWConfigUtils.getOpenMWConfig(filePath)
        val dataLines = configMap[OpenMWConfigUtils.ConfigKeyType.Data].orEmpty()
        val contentLines = configMap[OpenMWConfigUtils.ConfigKeyType.Content].orEmpty()
        val groundcoverLines = configMap[OpenMWConfigUtils.ConfigKeyType.GroundCover].orEmpty()

        onProgress("Found ${dataLines.size} data paths, ${contentLines.size} plugins")
        Log.d("ModMappingJson", "Found ${dataLines.size} data paths, ${contentLines.size} content plugins, ${groundcoverLines.size} groundcover plugins.")

        val mappings = mutableListOf<ModPluginMapping>()

        with(OpenMWConfigUtils) {
            // Extract plugin names (values after '=')
            val allContents = contentLines.mapNotNull { it.removeComment().getValue()?.removeSurrounding("\"") }
            val allGroundcovers = groundcoverLines.mapNotNull { it.removeComment().getValue()?.removeSurrounding("\"") }

            for ((index, dataLine) in dataLines.withIndex()) {
                val rawPath = dataLine.removeComment().getValue()?.removeSurrounding("\"") ?: continue
                val dirName = File(rawPath).name
                val percent = if (dataLines.isNotEmpty()) ((index + 1) * 100) / dataLines.size else 100
                onProgress("Indexing [$percent%] (${index + 1}/${dataLines.size}): $dirName")

                val dataDir = File(rawPath)
                val dirFiles = mutableSetOf<String>()

                if (dataDir.exists() && dataDir.isDirectory) {
                    getAllFileNamesInDirectory(dataDir, dirFiles)
                }

                val matchedContents = allContents.filter { it in dirFiles }
                val matchedGroundcovers = allGroundcovers.filter { it in dirFiles }

                Log.d("ModMappingJson", "Mapped dataPath: $rawPath -> content: $matchedContents, groundcover: $matchedGroundcovers")

                mappings.add(
                    ModPluginMapping(
                        dataPath = rawPath,
                        contentFiles = matchedContents,
                        groundcoverFiles = matchedGroundcovers
                    )
                )
            }
        }

        onProgress("Saving mappings...")
        saveMappings(mappings)
        Log.d("ModMappingJson", "Finished syncing and indexing. Total mappings: ${mappings.size}")
        onProgress("Indexing complete!")
        return mappings
    }

    private fun getAllFileNamesInDirectory(dir: File, set: MutableSet<String>) {
        dir.listFiles()?.forEach { file ->
            if (file.isFile) {
                set.add(file.name)
            } else if (file.isDirectory) {
                getAllFileNamesInDirectory(file, set)
            }
        }
    }
}
