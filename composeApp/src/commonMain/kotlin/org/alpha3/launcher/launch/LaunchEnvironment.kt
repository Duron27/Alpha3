package org.alpha3.launcher.launch

import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.dataStore

/**
 * Environment variables the user can switch on in the Launch options dialog. They are added to the process environment
 * before the engine starts, on top of [baseline].
 *
 * The catalog lists variables the OpenMW fork and OpenSceneGraph read at start-up on Android (checked against
 * getenv() calls in the sources), so every entry does something here. Variables the launcher manages itself
 * (texture shrinking, the Swappy and frame generation switches, GPU driver debug flags) are not in it.
 */
object LaunchEnvironment {
    const val GROUP_RENDER = "Rendering (OpenSceneGraph)"
    const val GROUP_LOADING = "Loading and stutter"
    const val GROUP_OPENMW = "OpenMW behaviour"
    const val GROUP_DEBUG = "Logging and debugging"
    const val GROUP_CUSTOM = "Your own"

    private val onOff = listOf("ON", "OFF")
    private val levels = listOf("ERROR", "WARNING", "INFO", "VERBOSE", "DEBUG")

    val catalog: List<OptionSpec> = listOf(
        // ---- rendering
        OptionSpec(
            "OSG_VERTEX_BUFFER_HINT", GROUP_RENDER, "OpenSceneGraph",
            "How geometry is uploaded to the GPU. VBO (VERTEX_BUFFER_OBJECT) is the most compatible choice and fixes flickering " +
                    "or missing geometry on some drivers. VAO (VERTEX_ARRAY_OBJECT) can be faster where the driver handles it well.",
            OptionKind.CHOICE, "VBO", "no preference",
            listOf("VBO", "VAO", "VERTEX_BUFFER_OBJECT", "VERTEX_ARRAY_OBJECT", "NO_PREFERENCE"), onByDefault = true,
        ),
        OptionSpec(
            "OSG_GL_TEXTURE_STORAGE", GROUP_RENDER, "OpenSceneGraph",
            "Use immutable texture storage (glTexStorage). OpenMW turns this OFF by default because some drivers misbehave; " +
                    "ON can lower memory use and load time on drivers that support it.",
            OptionKind.CHOICE, "ON", "OFF (set by OpenMW)", onOff,
        ),
        OptionSpec(
            "OSG_MAX_TEXTURE_SIZE", GROUP_RENDER, "OpenSceneGraph",
            "Caps the largest texture, in pixels. Lower values (2048, 1024) save video memory at the cost of sharpness. " +
                    "It can only lower the limit the GPU already reports.",
            OptionKind.NUMBER, "2048", "the GPU's own limit",
        ),
        OptionSpec(
            "OSG_GL_EXTENSION_DISABLE", GROUP_RENDER, "OpenSceneGraph",
            "Space separated OpenGL extensions to pretend the GPU does not have, for example GL_ARB_texture_storage. " +
                    "A workaround for driver bugs.",
            OptionKind.TEXT, "GL_ARB_texture_storage", "none disabled",
        ),
        OptionSpec(
            "OSG_GL_ERROR_CHECKING", GROUP_RENDER, "OpenSceneGraph",
            "ON checks for OpenGL errors after every state change, which is slow but pinpoints rendering bugs; the stock " +
                    "OpenMW engine also logs the driver's own message for each error. OFF never checks, which is slightly faster.",
            OptionKind.CHOICE, "ON", "checked once per frame", onOff,
        ),
        OptionSpec(
            "OSG_DEFAULT_BIN_SORT_MODE", GROUP_RENDER, "OpenSceneGraph",
            "How draw calls are ordered. Changes performance, and can change how transparent things overlap.",
            OptionKind.CHOICE, "SORT_BY_STATE_THEN_FRONT_TO_BACK", "SORT_BY_STATE",
            listOf("SORT_BY_STATE", "SORT_BY_STATE_THEN_FRONT_TO_BACK", "SORT_FRONT_TO_BACK", "SORT_BACK_TO_FRONT", "TRAVERSAL_ORDER"),
        ),
        OptionSpec(
            "OSG_BUILD_KDTREES", GROUP_RENDER, "OpenSceneGraph",
            "Build KD-trees for loaded models. They speed up picking and ray tests; OFF saves load time and memory.",
            OptionKind.CHOICE, "OFF", "no preference", onOff,
        ),

        // ---- loading
        OptionSpec(
            "OSG_MAXIMUM_OBJECTS_TO_COMPILE_PER_FRAME", GROUP_LOADING, "OpenSceneGraph",
            "How many new objects are compiled for the GPU each frame while things load in the background (OpenGL renderers). " +
                    "Lower numbers give smoother frames but slower streaming.",
            OptionKind.NUMBER, "10", "20",
        ),
        OptionSpec(
            "OSG_MINIMUM_COMPILE_TIME_PER_FRAME", GROUP_LOADING, "OpenSceneGraph",
            "Minimum time, in seconds, reserved each frame for GPU compiles (OpenGL renderers). Raise it to finish loading sooner.",
            OptionKind.NUMBER, "0.002", "0.001",
        ),
        OptionSpec(
            "OSG_FORCE_TEXTURE_DOWNLOAD", GROUP_LOADING, "OpenSceneGraph",
            "Force textures to be sent to the GPU during the compile step instead of on first use. Can reduce hitching " +
                    "when new areas come into view.",
            OptionKind.CHOICE, "ON", "OFF", onOff,
        ),
        OptionSpec(
            "OSG_ASSIGN_PBO_TO_IMAGES", GROUP_LOADING, "OpenSceneGraph",
            "Upload textures through pixel buffer objects, which lets the driver copy them in the background.",
            OptionKind.CHOICE, "ON", "OFF", onOff,
        ),
        OptionSpec(
            "OSG_DO_PRE_COMPILE", GROUP_LOADING, "OpenSceneGraph",
            "Compile paged-in data on the GPU before it is displayed. OFF can speed up loading but may cause hitches.",
            OptionKind.CHOICE, "OFF", "ON", onOff,
        ),
        OptionSpec(
            "OSG_TEXTURE_POOL_SIZE", GROUP_LOADING, "OpenSceneGraph",
            "Size, in bytes, of the pool that keeps released texture objects for reuse. 0 disables pooling.",
            OptionKind.NUMBER, "268435456", "0",
        ),
        OptionSpec(
            "OSG_BUFFER_OBJECT_POOL_SIZE", GROUP_LOADING, "OpenSceneGraph",
            "Size, in bytes, of the pool that keeps released vertex buffers for reuse. 0 disables pooling.",
            OptionKind.NUMBER, "134217728", "0",
        ),
        OptionSpec(
            "OSG_SYNC_SWAP_BUFFERS", GROUP_LOADING, "OpenSceneGraph",
            "Make swapping the front and back buffers wait for the GPU. Can cure tearing or frame pacing problems on some setups.",
            OptionKind.CHOICE, "ON", "OFF", onOff,
        ),

        // ---- OpenMW
        OptionSpec(
            "OPENMW_PHYSICS_FPS", GROUP_OPENMW, "OpenMW",
            "Physics simulation rate in steps per second. Lower values save CPU at the cost of collision accuracy; " +
                    "higher values are smoother for fast movement.",
            OptionKind.NUMBER, "60", "60",
        ),
        OptionSpec(
            "OPENMW_OPTIMIZE", GROUP_OPENMW, "OpenMW",
            "Mesh optimiser passes OpenMW runs on models (FLATTEN_STATIC_TRANSFORMS, REMOVE_REDUNDANT_NODES and MERGE_GEOMETRY " +
                    "by default). Switching one off, or all with OFF, can fix visual glitches on a specific mesh.",
            OptionKind.CHOICE, "~MERGE_GEOMETRY", "all three passes on",
            listOf("OFF", "~MERGE_GEOMETRY", "~FLATTEN_STATIC_TRANSFORMS", "~REMOVE_REDUNDANT_NODES"),
        ),
        OptionSpec(
            "OPENMW_DONT_PRECOMPILE", GROUP_OPENMW, "OpenMW",
            "Do not use the background incremental GPU compile of the OpenGL renderers. Faster loading screens, but new " +
                    "objects may hitch when they appear.",
            OptionKind.FLAG, "1", "not set",
        ),
        OptionSpec(
            "NODXT", GROUP_OPENMW, "OpenMW (stock)",
            "Makes OSG report S3TC, BPTC and RGTC as unsupported even on a GPU that has them, so DXT textures are converted to ASTC (or " +
                    "software decoded) instead of uploaded as they are. For testing the fallback on a device with " +
                    "hardware DXT.",
            OptionKind.FLAG, "1", "not set",
        ),
        OptionSpec(
            "OPENMW_DECOMPRESS_TEXTURES", GROUP_OPENMW, "OpenMW (stock)",
            "Decodes DXT (S3TC) textures on the CPU when the GPU cannot sample them and ASTC conversion is not available (as on " +
                    "Mesa Zink). Without it those textures fail to load (\"no S3TC texture compression support installed\") and show " +
                    "as the warning texture. Slower and uses more memory than ASTC.",
            OptionKind.FLAG, "1", "not set", onByDefault = true,
        ),
        OptionSpec(
            "OPENMW_TEXTURES_32BIT", GROUP_OPENMW, "OpenMW (stock)",
            "DXT1 textures decoded on the CPU (see OPENMW_DECOMPRESS_TEXTURES) are kept as 16 bit texels (RGB565, RGB5_A1), " +
                    "half the memory of RGBA8. Set this to decode them to 8 bit components instead, if 16 bit shows banding. " +
                    "DXT3 and DXT5 are always 8 bit.",
            OptionKind.FLAG, "1", "not set",
        ),
        OptionSpec(
            "OPENMW_DISABLE_SPIRV", GROUP_OPENMW, "OpenMW (stock, OpenGL ES)",
            "Skip glslang and SPIRV-Cross. By default every shader (core and post-processing) is converted GLSL to " +
                    "SPIR-V to ESSL 3.20, which fixes shaders a GLES driver rejects (implicit conversions) at the cost of " +
                    "slower shader compiles. Only applies when the engine runs on the system OpenGL ES driver.",
            OptionKind.FLAG, "1", "not set",
        ),

        // ---- debug
        OptionSpec(
            "OPENMW_DEBUG_LEVEL", GROUP_DEBUG, "OpenMW",
            "Lowest severity written to openmw.log. ERROR keeps the log small, DEBUG records everything.",
            OptionKind.CHOICE, "DEBUG", "VERBOSE", levels,
        ),
        OptionSpec(
            "OPENMW_RECAST_MAX_LOG_LEVEL", GROUP_DEBUG, "OpenMW",
            "Highest severity of messages that the navmesh library (Recast) is allowed to log.",
            OptionKind.CHOICE, "WARNING", "ERROR", levels,
        ),
        OptionSpec(
            "OSG_NOTIFY_LEVEL", GROUP_DEBUG, "OpenSceneGraph",
            "How chatty the OpenSceneGraph library is in the log.",
            OptionKind.CHOICE, "INFO", "NOTICE",
            listOf("ALWAYS", "FATAL", "WARN", "NOTICE", "INFO", "DEBUG_INFO", "DEBUG_FP", "DEBUG"),
        ),
        OptionSpec(
            "OPENMW_DEBUG_OPENGL", GROUP_DEBUG, "OpenMW",
            "Create an OpenGL debug context and label draw passes, so driver messages and GPU debuggers are easier to read. Slower.",
            OptionKind.FLAG, "1", "not set",
        ),
        OptionSpec(
            "OPENMW_STRICT_COMPILES", GROUP_DEBUG, "OpenMW (Vulkan)",
            "Treat a shader that was not prepared before gameplay as an error instead of only logging it. Finds shaders that " +
                    "would cause hitches on first use.",
            OptionKind.FLAG, "1", "not set",
        ),
        OptionSpec(
            "OPENMW_OSG_STATS_FILE", GROUP_DEBUG, "OpenMW",
            "Write per-frame performance statistics to this file. Use together with OPENMW_OSG_STATS_LIST.",
            OptionKind.TEXT, "${OpenMWPaths.USER_FILE_STORAGE}/stats.txt", "not set",
        ),
        OptionSpec(
            "OPENMW_OSG_STATS_LIST", GROUP_DEBUG, "OpenMW",
            "Statistics to record, separated by semicolons. Examples: FrameNumber, Loading, Compiling, Texture, " +
                    "Mechanics Actors, Physics Objects, Terrain Chunk.",
            OptionKind.TEXT, "FrameNumber;Loading;Compiling;Texture", "not set",
        ),
        OptionSpec(
            "OPENMW_WATER_CULLING_DEBUG", GROUP_DEBUG, "OpenMW",
            "Log extra information about how terrain is culled against the water plane.",
            OptionKind.FLAG, "1", "not set",
        ),
    )

    val byName: Map<String, OptionSpec> = catalog.associateBy { it.name }
    val groups: List<String> = catalog.map { it.group }.distinct()

    fun isValidName(name: String) =
        name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' } && !name[0].isDigit()

    /**
     * What the launcher itself decides, always set: the other games' folders, and text shading.
     * The user's entries come after and win over these.
     */
    suspend fun baseline(): Map<String, String> {
        return linkedMapOf(
            "UQM_CONFIG_DIR" to "${OpenMWPaths.USER_FILE_STORAGE}/uqm",
            "OSG_TEXT_SHADER_TECHNIQUE" to "ALL",
            "DETHRACE_ROOT_DIR" to "${OpenMWPaths.USER_FILE_STORAGE}/dethrace/",
            "HARNESS_OPENGL" to "1",
        )
    }

    private fun defaults(): Map<String, OptionEntry> =
        catalog.filter { it.onByDefault }.associate { it.name to OptionEntry(true, it.defaultValue) }

    /** The saved entries. The first time, the old single line is read in: the launcher's own variables drop out, the rest stay as entries. */
    suspend fun load(): Map<String, OptionEntry> {
        val prefs = dataStore.data.first()
        prefs[GameFilesPreferences.LAUNCH_ENV_KEY]?.let { return OptionCodec.decode(it) }
        val legacy = prefs[GameFilesPreferences.ENV_LINE_KEY]
        val migrated = if (legacy.isNullOrBlank()) {
            defaults()
        } else {
            val managed = baseline().keys + setOf("OPENMW_NO_SWAPPY", "OPENMW_FRAMEGEN")
            legacy.split(Regex("\\s+")).filter { it.contains('=') }.mapNotNull { token ->
                val name = token.substringBefore('=').trim()
                val value = token.substringAfter('=').trim()
                if (name in managed || !isValidName(name) || value.isEmpty()) null else name to OptionEntry(true, value)
            }.toMap()
        }
        save(migrated)
        return migrated
    }

    suspend fun save(entries: Map<String, OptionEntry>) {
        dataStore.edit { it[GameFilesPreferences.LAUNCH_ENV_KEY] = OptionCodec.encode(entries) }
    }

    /** Everything to put into the environment: [baseline], then the entries that are switched on. */
    suspend fun effective(): Map<String, String> {
        val result = LinkedHashMap(baseline())
        for ((name, entry) in load()) {
            if (entry.enabled && isValidName(name)) result[name] = entry.value
        }
        return result
    }
}
