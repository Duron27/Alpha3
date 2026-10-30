package org.alpha3.launcher.mods.downloader

import androidx.room.ColumnInfo
import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.alpha3.launcher.tools.ModAction

@Dao
interface ModDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMod(mod: ModDesc)

    @Query("SELECT COUNT(*) FROM mods")
    suspend fun getModCount(): Int

    @Update
    suspend fun updateMods(mods: List<ModDesc>)

    @Update
    suspend fun updateMod(mod: ModDesc)

    @Delete
    suspend fun deleteMod(mod: ModDesc)

    @Query("SELECT * FROM mods WHERE slug = :slug")
    fun getModBySlugFlow(slug: String): Flow<ModDesc?>

    @Query("SELECT * FROM mods WHERE slug LIKE :slug")
    fun getModsBySlugFlowFilter(slug: String): Flow<List<ModDesc>>

    // The file name is somewhere in the download_info JSON, so let SQL narrow it down first and only
    // decode the few rows that mention it (LIKE can over-match, which the exact check below fixes).
    @Query("SELECT * FROM mods WHERE downloadInfo LIKE '%' || :fileName || '%'")
    suspend fun getModsMentioningFileName(fileName: String): List<ModDesc>

    suspend fun getModByFileName(fileName: String): ModDesc? {
        return getModsMentioningFileName(fileName).find { mod ->
            mod.downloadInfo.any { it.fileName == fileName || it.versions.any { v -> v.fileName == fileName } }
        }
    }

    @Query("SELECT * FROM mods ORDER BY name COLLATE NOCASE ASC")
    fun getAllModsFlow(): Flow<List<ModDesc>>

    @Query("SELECT * FROM mods WHERE category = :category ORDER BY name COLLATE NOCASE ASC")
    suspend fun getModsByCategory(category: String): List<ModDesc>

    @Query("SELECT * FROM mods ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllMods(): List<ModDesc>

    @Query("DELETE FROM mods")
    suspend fun clearAllMods()

    @Transaction
    suspend fun replaceAllMods(mods: List<ModDesc>) {
        clearAllMods()
        insertAll(mods)
    }

    @Query("SELECT * FROM mods WHERE slug IN (:slugs)")
    suspend fun getModsBySlugs(slugs: List<String>): List<ModDesc>

    @Transaction
    suspend fun insertOrAppendMods(mods: List<ModDesc>, currentList: String) {
        // SQLite allows only 999 variables per statement on many Android versions, so look the slugs up in chunks.
        // (UI state like StatusInfo.processing belongs to the caller.)
        val existingMods = mods.map { it.slug }.chunked(500)
            .flatMap { getModsBySlugs(it) }
            .associateBy { it.slug }

        val alreadyProcessed = mods.all { mod ->
            existingMods[mod.slug]?.onLists?.contains(currentList) == true
        }
        if (alreadyProcessed) return

        val mergedMods = mods.map { newMod ->
            val existing = existingMods[newMod.slug]
            if (existing != null) {
                newMod.copy(
                    onLists = (existing.onLists + newMod.onLists).distinct(),
                    downloadInfo = mergeDownloadInfo(existing.downloadInfo, newMod.downloadInfo, currentList)
                )
            } else {
                newMod
            }
        }
        insertOrReplace(mergedMods)
    }

    /**
     * Every list of a sync in one transaction: the observers (the screens that collect [getAllModsFlow]) hear of it once,
     * instead of reloading the whole table once per list. [onMerged] reports each list, with the error if it failed.
     */
    @Transaction
    suspend fun mergeLists(lists: List<Pair<String, List<ModDesc>>>, onMerged: (String, Int, Exception?) -> Unit) {
        for ((listName, listMods) in lists) {
            try {
                insertOrAppendMods(listMods, listName)
                onMerged(listName, listMods.size, null)
            } catch (e: Exception) {
                onMerged(listName, listMods.size, e)
            }
        }
    }

    private fun mergeDownloadInfo(existing: List<DownloadInfo>, new: List<DownloadInfo>, currentList: String): List<DownloadInfo> {
        // Every identifying field is part of the key
        fun key(info: DownloadInfo) =
            "${info.fileName.orEmpty()}|${info.nexusFileId.orEmpty()}|${info.directDownload.orEmpty()}|${info.extractTo.orEmpty()}"

        val mergedMap = existing.associateBy { key(it) }.toMutableMap()
        for (newInfo in new) {
            val k = key(newInfo)
            val existingInfo = mergedMap[k]
            if (existingInfo == null) {
                // A new variant: it comes from the current list
                val sources = if (newInfo.sourceLists.isEmpty()) listOf(currentList) else (newInfo.sourceLists + currentList).distinct()
                mergedMap[k] = newInfo.copy(sourceLists = sources)
            } else {
                // The same variant: merge the sources, keep the fields the existing one already has
                val newSources = newInfo.sourceLists.ifEmpty { listOf(currentList) }
                val mergedSources = (existingInfo.sourceLists + newSources + currentList)
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .sorted()
                mergedMap[k] = existingInfo.copy(
                    sourceLists = mergedSources,
                    directDownload = existingInfo.directDownload ?: newInfo.directDownload,
                    nexusFileId = existingInfo.nexusFileId ?: newInfo.nexusFileId,
                    fileSize = existingInfo.fileSize ?: newInfo.fileSize,
                    extractTo = existingInfo.extractTo ?: newInfo.extractTo,
                    extractedSizeBytes = existingInfo.extractedSizeBytes ?: newInfo.extractedSizeBytes,
                    actionsByList = existingInfo.actionsByList + newInfo.actionsByList
                )
            }
        }
        return mergedMap.values.filter { !it.fileName.isNullOrBlank() }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(mods: List<ModDesc>)

    @Query("SELECT slug FROM mods")
    suspend fun getAllModSlugs(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(mods: List<ModDesc>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateMod(mod: ModDesc)

    @Query("SELECT * FROM mods WHERE tags LIKE '%' || :tag || '%' ORDER BY name COLLATE NOCASE ASC")
    suspend fun getModsByTag(tag: String): List<ModDesc>

    @Query("SELECT DISTINCT category FROM mods ORDER BY category COLLATE NOCASE ASC")
    suspend fun getAllCategories(): List<String>

    @Update
    suspend fun update(mod: ModDesc)

    // on_lists is a JSON array of strings, so quoting the name matches the whole entry only
    // (a bare LIKE '%x%' also matched every list whose name merely contains x).
    @Query("SELECT * FROM mods WHERE on_lists LIKE '%\"' || :modList || '\"%' ORDER BY name COLLATE NOCASE ASC")
    suspend fun getModsByModList(modList: String): List<ModDesc>

    @Query("SELECT * FROM mods WHERE on_lists LIKE '%\"' || :modList || '\"%' ORDER BY name COLLATE NOCASE ASC")
    fun getModsByModListFlow(modList: String): Flow<List<ModDesc>>

    @Query("SELECT * FROM mods WHERE modId = :modId")
    suspend fun getModsByModId(modId: String): List<ModDesc>

    @Query("SELECT * FROM mods WHERE modId = :modId")
    suspend fun getModById(modId: String): ModDesc?

    @Query("SELECT * FROM mods WHERE modId = :modId")
    fun getModsByModIdFlow(modId: String): Flow<List<ModDesc>>
}

class Converters {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @TypeConverter
    fun fromStringList(value: List<String>): String = json.encodeToString(value)

    @TypeConverter
    fun toStringList(value: String): List<String> = json.decodeFromString(value)

    @TypeConverter
    fun fromDownloadInfoList(value: List<DownloadInfo>): String = json.encodeToString(value)

    @TypeConverter
    fun toDownloadInfoList(value: String): List<DownloadInfo> = json.decodeFromString(value)
}

@Serializable
data class DownloadInfo(
    @SerialName("direct_download") val directDownload: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("extract_to") val extractTo: String? = null,
    @SerialName("nexus_file_id") val nexusFileId: String? = null,
    @SerialName("size_kb") val fileSize: String? = null,
    @SerialName("versions") val versions: List<Version> = emptyList(),
    @SerialName("source_lists") val sourceLists: List<String> = emptyList(),
    val extractedSizeBytes: Long? = null,
    /** As the site serves them for one list; moved into [actionsByList] when the list is synced. */
    val actions: List<ModAction> = emptyList(),
    /** What to run after extracting this archive, per mod list: the same archive can differ between lists. */
    val actionsByList: Map<String, List<ModAction>> = emptyMap(),
) {
    fun actionsFor(list: String): List<ModAction> = actionsByList[list].orEmpty()

    /** This archive as listed by [list], with the actions the site served moved under that list. */
    fun forList(list: String) = copy(
        sourceLists = listOf(list),
        actions = emptyList(),
        actionsByList = if (actions.isEmpty()) emptyMap() else mapOf(list to actions),
    )

    @Serializable
    data class Version(
        @SerialName("file_name") val fileName: String,
        @SerialName("file_id") val fileId: String,
        @SerialName("version") val version: String?,
        @SerialName("size_kb") val sizeKb: String?,
        @SerialName("category_name") val category: String
    )
}

// The columns, their order and the version must stay as Android has always had them: the table in an existing
// mods_database.db is checked against them when it opens.
@Entity(tableName = "mods")
@TypeConverters(Converters::class)
@Serializable
data class ModDesc(
    val modId: String = "",
    @PrimaryKey val slug: String = "",
    val name: String = "",
    val author: String = "",
    val description: String = "",
    val url: String = "",
    val category: String = "",
    @ColumnInfo(name = "dl_url") @SerialName("dl_url") val dlUrl: String? = null,
    @ColumnInfo(name = "usage_notes") @SerialName("usage_notes") val usageNotes: String? = null,
    val compat: Int = 0,
    val dir: String = "",
    @ColumnInfo(name = "date_added") @SerialName("date_added") val dateAdded: String? = null,
    @ColumnInfo(name = "date_updated") @SerialName("date_updated") val dateUpdated: String? = null,
    val tags: List<String> = emptyList(),
    @ColumnInfo(name = "on_lists") @SerialName("on_lists") val onLists: List<String> = emptyList(),
    @ColumnInfo(name = "data_paths") @SerialName("data_paths") val dataPaths: List<String> = emptyList(),
    @ColumnInfo(name = "gallery_urls") val galleryUrls: List<String> = emptyList(),
    val plugins: List<String> = emptyList(),
    @SerialName("download_info") val downloadInfo: List<DownloadInfo> = emptyList(),
    @ColumnInfo(name = "gitlab_project_id") @SerialName("gitlab_project_id") val gitlabProjectId: String? = null,
    @ColumnInfo(name = "gitlab_package_id") @SerialName("gitlab_package_id") val gitlabPackageId: String? = null
)

@Database(
    entities = [ModDesc::class],
    version = 21,
    exportSchema = false
)
@TypeConverters(Converters::class)
@ConstructedBy(ModDatabaseConstructor::class)
abstract class ModDatabase : RoomDatabase() {
    abstract fun modDao(): ModDao

    companion object {
        private val instance: ModDatabase by lazy {
            modDatabaseBuilder()
                .fallbackToDestructiveMigration(dropAllTables = false)
                .build()
        }

        fun getDatabase(): ModDatabase = instance
    }
}

// Room's compiler writes the actual for each platform
@Suppress("KotlinNoActualForExpect", "NO_ACTUAL_FOR_EXPECT", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object ModDatabaseConstructor : RoomDatabaseConstructor<ModDatabase> {
    override fun initialize(): ModDatabase
}

/** `mods_database.db` where the platform keeps it (Android: the app's databases folder, as before). */
internal expect fun modDatabaseBuilder(): RoomDatabase.Builder<ModDatabase>
