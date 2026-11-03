package org.alpha3.launcher.mods.downloader

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okio.IOException
import org.alpha3.launcher.io.File
import org.alpha3.launcher.utils.Log

/**
 * Searching and browsing Morrowind's mods on Nexus, through its GraphQL API (v2): the only Nexus API that searches by
 * name. It answers without an API key; with one, mods by authors and with tags the member blocked are left out.
 * Downloads still go through the v1 API, as for every other Nexus mod of a list.
 */
object NexusBrowse {
    private const val GRAPHQL = "https://api.nexusmods.com/v2/graphql"
    private const val GAME_DOMAIN = "morrowind"
    private const val GAME_ID = "100"
    const val PAGE_SIZE = 20

    enum class Sort(val label: String, val field: String) {
        RELEVANCE("Best match", "relevance"),
        ENDORSEMENTS("Endorsements", "endorsements"),
        DOWNLOADS("Downloads", "downloads"),
        UPDATED("Updated", "updatedAt"),
        NEW("New", "createdAt"),
    }

    data class Mod(
        val modId: Int,
        val name: String,
        val summary: String,
        val version: String,
        val author: String,
        val uploader: String,
        val category: String,
        val thumbnailUrl: String?,
        val pictureUrl: String?,
        val endorsements: Int,
        val downloads: Int,
        val updatedAt: String,
        val fileSizeKb: Long?,
        val adult: Boolean,
    ) {
        val pageUrl get() = "https://www.nexusmods.com/$GAME_DOMAIN/mods/$modId"
    }

    data class Page(val mods: List<Mod>, val total: Int)

    data class ModFile(
        val fileId: Long,
        val name: String,
        val version: String,
        /** MAIN, UPDATE, OPTIONAL, MISCELLANEOUS (OLD_VERSION, ARCHIVED and REMOVED are left out). */
        val category: String,
        /** Unix seconds. */
        val date: Long,
        val sizeKb: Long,
        /** The archive's file name, as the download saves it. */
        val fileName: String,
        val description: String?,
    )

    private const val MOD_FIELDS =
        "modId name summary version author uploader { name } category thumbnailUrl pictureUrl endorsements downloads updatedAt fileSize adultContent"

    /** One page of mods: those whose name matches [query] (all of them when blank), in [sort]'s order. */
    suspend fun search(query: String, sort: Sort, page: Int, includeAdult: Boolean = false): Page {
        val words = query.trim()
        val sortBy = if (sort == Sort.RELEVANCE && words.isEmpty()) Sort.ENDORSEMENTS else sort
        val base = buildJsonObject {
            putJsonArray("gameDomainName") { addJsonObject { put("value", GAME_DOMAIN) } }
            if (!includeAdult) putJsonArray("adultContent") { addJsonObject { put("value", false) } }
        }
        // The game AND (the name word by word, "tamriel rebuilt", OR a part of it, "rebui")
        val filter = if (words.isEmpty()) base else buildJsonObject {
            put("op", "AND")
            putJsonArray("filter") {
                add(base)
                addJsonObject {
                    put("op", "OR")
                    putJsonArray("filter") {
                        addJsonObject { putJsonArray("nameStemmed") { addJsonObject { put("value", words); put("op", "MATCHES") } } }
                        addJsonObject { putJsonArray("name") { addJsonObject { put("value", "*$words*"); put("op", "WILDCARD") } } }
                    }
                }
            }
        }
        val variables = buildJsonObject {
            put("filter", filter)
            putJsonArray("sort") { addJsonObject { putJsonObject(sortBy.field) { put("direction", "DESC") } } }
            put("offset", page * PAGE_SIZE)
            put("count", PAGE_SIZE)
        }
        val data = query(
            "query(\$filter: ModsFilter, \$sort: [ModsSort!], \$offset: Int, \$count: Int) { " +
                "mods(filter: \$filter, sort: \$sort, offset: \$offset, count: \$count) { totalCount nodes { $MOD_FIELDS } } }",
            variables,
        )
        val mods = data["mods"]?.jsonObject ?: throw IOException("Nexus answered without mods")
        return Page(
            mods = mods["nodes"]?.jsonArray.orEmpty().mapNotNull { parseMod(it.jsonObject) },
            total = mods["totalCount"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    /** The mod's files that can be downloaded, newest first within each category (main files first). */
    suspend fun files(modId: Int): List<ModFile> {
        val data = query(
            "query(\$modId: ID!, \$gameId: ID!) { modFiles(modId: \$modId, gameId: \$gameId) { " +
                "fileId name version category date size uri description } }",
            buildJsonObject { put("modId", modId.toString()); put("gameId", GAME_ID) },
        )
        val order = listOf("MAIN", "UPDATE", "OPTIONAL", "MISCELLANEOUS")
        return data["modFiles"]?.jsonArray.orEmpty().mapNotNull { element ->
            val o = element.jsonObject
            val category = o.str("category") ?: return@mapNotNull null
            if (category !in order) return@mapNotNull null
            ModFile(
                fileId = o["fileId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                name = o.str("name").orEmpty(),
                version = o.str("version").orEmpty(),
                category = category,
                date = o["date"]?.jsonPrimitive?.longOrNull ?: 0L,
                sizeKb = o["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                fileName = o.str("uri") ?: return@mapNotNull null,
                description = o.str("description")?.takeIf { it.isNotBlank() },
            )
        }.sortedWith(compareBy<ModFile> { order.indexOf(it.category) }.thenByDescending { it.date })
    }

    private fun parseMod(o: JsonObject): Mod? = Mod(
        modId = o["modId"]?.jsonPrimitive?.intOrNull ?: return null,
        name = o.str("name") ?: return null,
        summary = o.str("summary").orEmpty().replace("<br />", "\n").replace(Regex("<[^>]+>"), ""),
        version = o.str("version").orEmpty(),
        author = o.str("author").orEmpty(),
        uploader = o["uploader"]?.jsonObject?.str("name").orEmpty(),
        category = o.str("category").orEmpty(),
        thumbnailUrl = o.str("thumbnailUrl"),
        pictureUrl = o.str("pictureUrl"),
        endorsements = o["endorsements"]?.jsonPrimitive?.intOrNull ?: 0,
        downloads = o["downloads"]?.jsonPrimitive?.intOrNull ?: 0,
        updatedAt = o.str("updatedAt").orEmpty(),
        fileSizeKb = o["fileSize"]?.jsonPrimitive?.longOrNull,
        adult = o["adultContent"]?.jsonPrimitive?.booleanOrNull ?: false,
    )

    private fun JsonObject.str(name: String): String? = (this[name] as? JsonElement)?.let {
        if (it is JsonObject || it is JsonArray) null else it.jsonPrimitive.contentOrNull
    }

    private suspend fun query(query: String, variables: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("query", query); put("variables", variables) }.toString()
        val response = ModHttp.client.post(GRAPHQL) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json")
            header("User-Agent", "Alpha3/2.71")
            if (ModListManager.apiKey.isNotBlank()) header("apikey", ModListManager.apiKey)
            setBody(body)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw IOException("Nexus search failed: HTTP ${response.status.value}")
        val root = ModListManager.json.parseToJsonElement(text).jsonObject
        root["errors"]?.jsonArray?.firstOrNull()?.let { error ->
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()
            throw IOException("Nexus search failed: $message")
        }
        root["data"]?.jsonObject ?: throw IOException("Nexus search answered without data")
    }
}
