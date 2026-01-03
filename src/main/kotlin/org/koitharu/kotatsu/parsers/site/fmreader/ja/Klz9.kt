package org.koitharu.kotatsu.parsers.site.fmreader.ja

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("KLZ9", "Klz9", "ja")
internal class Klz9(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KLZ9, pageSize = 36) {

    override val configKeyDomain = ConfigKey.Domain(DOMAIN)

    override val availableSortOrders: Set<SortOrder> = setOf(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isMultipleTagsSupported = true
    )

    private val tagsCache = suspendLazy {
        try {
            webClient.httpGet("$API_URL/genres".toHttpUrl(), generateHeaders())
                .parseJsonArray()
                .mapJSONToSet {
                    val name = it.getString("name")
                    MangaTag(title = name, key = name, source = source)
                }
        } catch (e: Exception) {
            emptySet()
        }
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = tagsCache.get(),
            availableContentRating = setOf(ContentRating.ADULT),
            availableStates = setOf(MangaState.ONGOING, MangaState.FINISHED)
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "$API_URL/manga/list".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", pageSize.toString())

            if (!filter.query.isNullOrEmpty()) {
                addQueryParameter("search", filter.query)
            }

            if (filter.tags.isNotEmpty()) {
                addQueryParameter("genre", filter.tags.joinToString(",") { it.key })
            }

            when (order) {
                SortOrder.POPULARITY -> {
                    addQueryParameter("sort", "views")
                    addQueryParameter("order", "desc")
                }
                SortOrder.UPDATED -> {
                    addQueryParameter("sort", "updated")
                    addQueryParameter("order", "desc")
                }
                SortOrder.NEWEST -> {
                    addQueryParameter("sort", "created")
                    addQueryParameter("order", "desc")
                }
                SortOrder.ALPHABETICAL -> {
                    addQueryParameter("sort", "name")
                    addQueryParameter("order", "asc")
                }
                else -> {
                    addQueryParameter("sort", "updated")
                    addQueryParameter("order", "desc")
                }
            }
        }.build()

        return webClient.httpGet(url, generateHeaders()).parseJson()
            .optJSONArray("items")
            ?.mapJSON(::parseMangaItem)
            .orEmpty()
    }

    private fun parseMangaItem(json: JSONObject): Manga {
        val slug = json.getString("slug")
        val url = "/$slug.html"
        return Manga(
            id = generateUid(url),
            url = url,
            publicUrl = url.toAbsoluteUrl(DOMAIN),
            coverUrl = json.optString("cover", ""),
            title = json.getString("name"),
            altTitles = emptySet(),
            rating = RATING_UNKNOWN,
            tags = emptySet(),
            authors = emptySet(),
            state = when (json.optInt("m_status", 0)) {
                1 -> MangaState.ONGOING
                2 -> MangaState.FINISHED
                else -> null
            },
            source = source,
            contentRating = ContentRating.ADULT,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.substringAfter("/").substringBefore(".html")
        val url = "$API_URL/manga/slug/$slug"
        val json = webClient.httpGet(url, generateHeaders()).parseJson()

        val authors = json.optString("authors", "").split(",").mapNotNullToSet { it.trim().nullIfEmpty() }
        val genres = json.optString("genres", "").split(",").mapNotNullToSet { it.trim().nullIfEmpty() }
        val desc = json.optString("description", "")
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")

        return manga.copy(
            description = desc,
            authors = authors,
            tags = genres.mapToSet { MangaTag(title = it, key = it, source = source) },
            coverUrl = json.optString("cover", manga.coverUrl),
            state = when (json.optInt("m_status", 0)) {
                1 -> MangaState.ONGOING
                2 -> MangaState.FINISHED
                else -> null
            },
            chapters = parseChapters(json)
        )
    }

    private fun parseChapters(json: JSONObject): List<MangaChapter> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        return json.optJSONArray("chapters")?.mapJSON { item ->
            val id = item.getLong("id")
            val chapterNum = item.optDouble("chapter", 0.0).toFloat()
            val dateStr = item.optString("last_update")
            val date = dateFormat.parseSafe(dateStr)

            MangaChapter(
                id = id,
                title = item.optString("name").takeIf { !it.isNullOrEmpty() && it != "null" } ?: "Chapter $chapterNum",
                number = chapterNum,
                volume = 0,
                url = "/chapter/$id",
                uploadDate = date,
                source = source,
                scanlator = null,
                branch = null
            )
        }?.reversed().orEmpty()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = "$API_URL/chapter/${chapter.id}"
        val json = webClient.httpGet(url, generateHeaders()).parseJson()
        val content = json.optString("content", "")

        val urls = if (content.trim().startsWith("[")) {
            content.toJSONArrayOrNull()?.toStringSet().orEmpty()
        } else {
            content.split(Regex("[\r\n]+")).map { it.trim() }.filter { it.startsWith("http") }
        }

        return urls.filter { it !in BLACKLISTED }.map {
            var newUrl = it.replace("http://", "https://")
            for ((old, new) in URL_REPLACEMENTS) {
                newUrl = newUrl.replace(old, new)
            }

            MangaPage(
                id = generateUid(newUrl),
                url = newUrl,
                preview = null,
                source = source
            )
        }
    }

    private fun generateHeaders(): Headers {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val sig = "$ts.$SECRET_KEY".sha256()

        return Headers.Builder()
            .add("x-client-ts", ts)
            .add("x-client-sig", sig)
            .add("User-Agent", UserAgents.CHROME_DESKTOP)
            .build()
    }

    private fun String.sha256(): String {
        return MessageDigest.getInstance("SHA-256").digest(toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {

        const val DOMAIN = "klz9.com"
        const val API_URL = "https://$DOMAIN/api"
        const val SECRET_KEY = "KL9K40zaSyC9K40vOMLLbEcepIFBhUKXwELqxlwTEF"

        val BLACKLISTED = setOf(
            "https://1.bp.blogspot.com/-ZMyVQcnjYyE/W2cRdXQb15I/AAAAAAACDnk/8X1Hm7wmhz4hLvpIzTNBHQnhuKu05Qb0gCHMYCw/s0/LHScan.png",
            "https://s4.imfaclub.com/images/20190814/Credit_LHScan_5d52edc2409e7.jpg",
            "https://s4.imfaclub.com/images/20200112/5e1ad960d67b2_5e1ad962338c7.jpg"
        )

        val URL_REPLACEMENTS = mapOf(
            "https://imfaclub.com" to "https://j1.jfimv2.xyz",
            "https://s2.imfaclub.com" to "https://j2.jfimv2.xyz",
            "https://s4.imfaclub.com" to "https://j4.jfimv2.xyz",
            "https://ihlv1.xyz" to "https://j1.jfimv2.xyz",
            "https://s2.ihlv1.xyz" to "https://j2.jfimv2.xyz",
            "https://s4.ihlv1.xyz" to "https://j4.jfimv2.xyz",
            "https://h1.klimv1.xyz" to "https://j1.jfimv2.xyz",
            "https://h2.klimv1.xyz" to "https://j2.jfimv2.xyz",
            "https://h4.klimv1.xyz" to "https://j4.jfimv2.xyz"
        )
    }
}