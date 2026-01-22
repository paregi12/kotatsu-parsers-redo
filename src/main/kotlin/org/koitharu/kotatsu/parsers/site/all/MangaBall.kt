package org.koitharu.kotatsu.parsers.site.all

import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl

@MangaSourceParser("MANGABALL", "MangaBall", "")
internal class MangaBall(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.MANGABALL, 20) {

    override val configKeyDomain = ConfigKey.Domain("mangaball.net")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
        )

    private var csrfToken: String? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.pathSegments.contains("api")) {
            return chain.proceed(request)
        }

        val token = runBlocking { getCsrfToken() }
        var newRequest = request.newBuilder()
            .header("X-CSRF-TOKEN", token)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        val response = chain.proceed(newRequest)

        if (response.code == 403) {
            response.close()
            val newToken = runBlocking { getCsrfToken(forceRefresh = true) }
            newRequest = request.newBuilder()
                .header("X-CSRF-TOKEN", newToken)
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            return chain.proceed(newRequest)
        }
        return response
    }

    private suspend fun getCsrfToken(forceRefresh: Boolean = false): String {
        if (csrfToken != null && !forceRefresh) return csrfToken!!
        val doc = webClient.httpGet("https://$domain".toHttpUrl()).parseHtml()
        val token = doc.selectFirst("meta[name=csrf-token]")?.attr("content")
            ?: throw ParseException("CSRF token not found")
        csrfToken = token
        return token
    }

    private val tags: Set<MangaTag> by lazy {
        setOf(
             MangaTag(title = "Gore", key = "685148d115e8b86aae68e4f3", source = source),
             MangaTag(title = "Sexual Violence", key = "685146c5f3ed681c80f257e7", source = source),
             MangaTag(title = "4-Koma", key = "685148d115e8b86aae68e4ec", source = source),
             MangaTag(title = "Adaptation", key = "685148cf15e8b86aae68e4de", source = source),
             MangaTag(title = "Anthology", key = "685148e915e8b86aae68e558", source = source),
             MangaTag(title = "Award Winning", key = "685148fe15e8b86aae68e5a7", source = source),
             MangaTag(title = "Doujinshi", key = "6851490e15e8b86aae68e5da", source = source),
             MangaTag(title = "Fan Colored", key = "6851498215e8b86aae68e704", source = source),
             MangaTag(title = "Full Color", key = "685148d615e8b86aae68e502", source = source),
             MangaTag(title = "Long Strip", key = "685148d915e8b86aae68e517", source = source),
             MangaTag(title = "Official Colored", key = "6851493515e8b86aae68e64a", source = source),
             MangaTag(title = "Oneshot", key = "685148eb15e8b86aae68e56c", source = source),
             MangaTag(title = "Self-Published", key = "6851492e15e8b86aae68e633", source = source),
             MangaTag(title = "Web Comic", key = "685148d715e8b86aae68e50d", source = source),
             MangaTag(title = "Action", key = "685146c5f3ed681c80f257e3", source = source),
             MangaTag(title = "Adult", key = "689371f0a943baf927094f03", source = source),
             MangaTag(title = "Adventure", key = "685146c5f3ed681c80f257e6", source = source),
             MangaTag(title = "Boys' Love", key = "685148ef15e8b86aae68e573", source = source),
             MangaTag(title = "Comedy", key = "685146c5f3ed681c80f257e5", source = source),
             MangaTag(title = "Crime", key = "685148da15e8b86aae68e51f", source = source),
             MangaTag(title = "Drama", key = "685148cf15e8b86aae68e4dd", source = source),
             MangaTag(title = "Ecchi", key = "6892a73ba943baf927094e37", source = source),
             MangaTag(title = "Fantasy", key = "685146c5f3ed681c80f257ea", source = source),
             MangaTag(title = "Girls' Love", key = "685148da15e8b86aae68e524", source = source),
             MangaTag(title = "Historical", key = "685148db15e8b86aae68e527", source = source),
             MangaTag(title = "Horror", key = "685148da15e8b86aae68e520", source = source),
             MangaTag(title = "Isekai", key = "685146c5f3ed681c80f257e9", source = source),
             MangaTag(title = "Magical Girls", key = "6851490d15e8b86aae68e5d4", source = source),
             MangaTag(title = "Mature", key = "68932d11a943baf927094e7b", source = source),
             MangaTag(title = "Mecha", key = "6851490c15e8b86aae68e5d2", source = source),
             MangaTag(title = "Medical", key = "6851494e15e8b86aae68e66e", source = source),
             MangaTag(title = "Mystery", key = "685148d215e8b86aae68e4f4", source = source),
             MangaTag(title = "Philosophical", key = "685148e215e8b86aae68e544", source = source),
             MangaTag(title = "Psychological", key = "685148d715e8b86aae68e507", source = source),
             MangaTag(title = "Romance", key = "685148cf15e8b86aae68e4db", source = source),
             MangaTag(title = "Sci-Fi", key = "685148cf15e8b86aae68e4da", source = source),
             MangaTag(title = "Shounen Ai", key = "689f0ab1f2e66744c6091524", source = source),
             MangaTag(title = "Slice of Life", key = "685148d015e8b86aae68e4e3", source = source),
             MangaTag(title = "Smut", key = "689371f2a943baf927094f04", source = source),
             MangaTag(title = "Sports", key = "685148f515e8b86aae68e588", source = source),
             MangaTag(title = "Superhero", key = "6851492915e8b86aae68e61c", source = source),
             MangaTag(title = "Thriller", key = "685148d915e8b86aae68e51e", source = source),
             MangaTag(title = "Tragedy", key = "685148db15e8b86aae68e529", source = source),
             MangaTag(title = "User Created", key = "68932c3ea943baf927094e77", source = source),
             MangaTag(title = "Wuxia", key = "6851490715e8b86aae68e5c3", source = source),
             MangaTag(title = "Yaoi", key = "68932f68a943baf927094eaa", source = source),
             MangaTag(title = "Yuri", key = "6896a885a943baf927094f66", source = source),
             MangaTag(title = "Origin: Comic", key = "68ecab8507ec62d87e62780f", source = source),
             MangaTag(title = "Origin: Manga", key = "68ecab1e07ec62d87e627806", source = source),
             MangaTag(title = "Origin: Manhua", key = "68ecab4807ec62d87e62780b", source = source),
             MangaTag(title = "Origin: Manhwa", key = "68ecab3b07ec62d87e627809", source = source),
             MangaTag(title = "Theme: Aliens", key = "6851490d15e8b86aae68e5d5", source = source),
             MangaTag(title = "Theme: Animals", key = "685148e715e8b86aae68e54b", source = source),
             MangaTag(title = "Theme: Comics", key = "68bf09ff8fdeab0b6a9bc2b7", source = source),
             MangaTag(title = "Theme: Cooking", key = "685148d215e8b86aae68e4f8", source = source),
             MangaTag(title = "Theme: Crossdressing", key = "685148df15e8b86aae68e534", source = source),
             MangaTag(title = "Theme: Delinquents", key = "685148d915e8b86aae68e519", source = source),
             MangaTag(title = "Theme: Demons", key = "685146c5f3ed681c80f257e4", source = source),
             MangaTag(title = "Theme: Genderswap", key = "685148d715e8b86aae68e505", source = source),
             MangaTag(title = "Theme: Ghosts", key = "685148d615e8b86aae68e501", source = source),
             MangaTag(title = "Theme: Gyaru", key = "685148d015e8b86aae68e4e8", source = source),
             MangaTag(title = "Theme: Harem", key = "685146c5f3ed681c80f257e8", source = source),
             MangaTag(title = "Theme: Hentai", key = "68bfceaf4dbc442a26519889", source = source),
             MangaTag(title = "Theme: Incest", key = "685148f215e8b86aae68e584", source = source),
             MangaTag(title = "Theme: Loli", key = "685148d715e8b86aae68e506", source = source),
             MangaTag(title = "Theme: Mafia", key = "685148d915e8b86aae68e518", source = source),
             MangaTag(title = "Theme: Magic", key = "685148d715e8b86aae68e509", source = source),
             MangaTag(title = "Theme: Manhwa 18+", key = "68f5f5ce5f29d3c1863dec3a", source = source),
             MangaTag(title = "Theme: Martial Arts", key = "6851490615e8b86aae68e5c2", source = source),
             MangaTag(title = "Theme: Military", key = "685148e215e8b86aae68e541", source = source),
             MangaTag(title = "Theme: Monster Girls", key = "685148db15e8b86aae68e52c", source = source),
             MangaTag(title = "Theme: Monsters", key = "685146c5f3ed681c80f257e2", source = source),
             MangaTag(title = "Theme: Music", key = "685148d015e8b86aae68e4e4", source = source),
             MangaTag(title = "Theme: Ninja", key = "685148d715e8b86aae68e508", source = source),
             MangaTag(title = "Theme: Office Workers", key = "685148d315e8b86aae68e4fd", source = source),
             MangaTag(title = "Theme: Police", key = "6851498815e8b86aae68e714", source = source),
             MangaTag(title = "Theme: Post-Apocalyptic", key = "685148e215e8b86aae68e540", source = source),
             MangaTag(title = "Theme: Reincarnation", key = "685146c5f3ed681c80f257e1", source = source),
             MangaTag("Theme: Reverse Harem", "685148df15e8b86aae68e533", source),
             MangaTag(title = "Theme: Samurai", key = "6851490415e8b86aae68e5b9", source = source),
             MangaTag(title = "Theme: School Life", key = "685148d015e8b86aae68e4e7", source = source),
             MangaTag(title = "Theme: Shota", key = "685148d115e8b86aae68e4ed", source = source),
             MangaTag(title = "Theme: Supernatural", key = "685148db15e8b86aae68e528", source = source),
             MangaTag(title = "Theme: Survival", key = "685148cf15e8b86aae68e4dc", source = source),
             MangaTag(title = "Theme: Time Travel", key = "6851490c15e8b86aae68e5d1", source = source),
             MangaTag(title = "Theme: Traditional Games", key = "6851493515e8b86aae68e645", source = source),
             MangaTag(title = "Theme: Vampires", key = "685148f915e8b86aae68e597", source = source),
             MangaTag(title = "Theme: Video Games", key = "685148e115e8b86aae68e53c", source = source),
             MangaTag(title = "Theme: Villainess", key = "6851492115e8b86aae68e602", source = source),
             MangaTag(title = "Theme: Virtual Reality", key = "68514a1115e8b86aae68e83e", source = source),
             MangaTag(title = "Theme: Zombies", key = "6851490c15e8b86aae68e5d3", source = source),
        )
    }

    private val languages: Set<MangaTag> by lazy {
        setOf(
             MangaTag(title = "Language: Arabic", key = "lang:ar", source = source),
             MangaTag(title = "Language: Bulgarian", key = "lang:bg", source = source),
             MangaTag(title = "Language: Bengali", key = "lang:bn", source = source),
             MangaTag(title = "Language: Catalan", key = "lang:ca", source = source),
             MangaTag(title = "Language: Catalan (Andorra)", key = "lang:ca-ad", source = source),
             MangaTag(title = "Language: Catalan (Spain)", key = "lang:ca-es", source = source),
             MangaTag(title = "Language: Catalan (France)", key = "lang:ca-fr", source = source),
             MangaTag(title = "Language: Catalan (Italy)", key = "lang:ca-it", source = source),
             MangaTag(title = "Language: Catalan (Portugal)", key = "lang:ca-pt", source = source),
             MangaTag(title = "Language: Czech", key = "lang:cs", source = source),
             MangaTag(title = "Language: Danish", key = "lang:da", source = source),
             MangaTag(title = "Language: German", key = "lang:de", source = source),
             MangaTag(title = "Language: Greek", key = "lang:el", source = source),
             MangaTag(title = "Language: English", key = "lang:en", source = source),
             MangaTag(title = "Language: Spanish", key = "lang:es", source = source),
             MangaTag(title = "Language: Spanish (Argentina)", key = "lang:es-ar", source = source),
             MangaTag(title = "Language: Spanish (Mexico)", key = "lang:es-mx", source = source),
             MangaTag(title = "Language: Spanish (Spain)", key = "lang:es-es", source = source),
             MangaTag(title = "Language: Spanish (Latin America)", key = "lang:es-la", source = source),
             MangaTag(title = "Language: Spanish (Latin America)", key = "lang:es-419", source = source),
             MangaTag(title = "Language: Persian", key = "lang:fa", source = source),
             MangaTag(title = "Language: Finnish", key = "lang:fi", source = source),
             MangaTag(title = "Language: French", key = "lang:fr", source = source),
             MangaTag(title = "Language: Hebrew", key = "lang:he", source = source),
             MangaTag(title = "Language: Hindi", key = "lang:hi", source = source),
             MangaTag(title = "Language: Hungarian", key = "lang:hu", source = source),
             MangaTag(title = "Language: Indonesian", key = "lang:id", source = source),
             MangaTag(title = "Language: Italian", key = "lang:it", source = source),
             MangaTag(title = "Language: Italian (Italy)", key = "lang:it-it", source = source),
             MangaTag(title = "Language: Icelandic", key = "lang:ib", source = source),
             MangaTag(title = "Language: Icelandic (Iceland)", key = "lang:ib-is", source = source),
             MangaTag(title = "Language: Icelandic", key = "lang:is", source = source),
             MangaTag(title = "Language: Japanese", key = "lang:jp", source = source),
             MangaTag(title = "Language: Korean", key = "lang:kr", source = source),
             MangaTag(title = "Language: Kannada", key = "lang:kn", source = source),
             MangaTag(title = "Language: Kannada (India)", key = "lang:kn-in", source = source),
             MangaTag(title = "Language: Kannada (Malaysia)", key = "lang:kn-my", source = source),
             MangaTag(title = "Language: Kannada (Singapore)", key = "lang:kn-sg", source = source),
             MangaTag(title = "Language: Kannada (Taiwan)", key = "lang:kn-tw", source = source),
             MangaTag(title = "Language: Malayalam", key = "lang:ml", source = source),
             MangaTag(title = "Language: Malayalam (India)", key = "lang:ml-in", source = source),
             MangaTag(title = "Language: Malayalam (Malaysia)", key = "lang:ml-my", source = source),
             MangaTag(title = "Language: Malayalam (Singapore)", key = "lang:ml-sg", source = source),
             MangaTag(title = "Language: Malayalam (Taiwan)", key = "lang:ml-tw", source = source),
             MangaTag(title = "Language: Malay", key = "lang:ms", source = source),
             MangaTag(title = "Language: Nepali", key = "lang:ne", source = source),
             MangaTag(title = "Language: Dutch", key = "lang:nl", source = source),
             MangaTag(title = "Language: Dutch (Belgium)", key = "lang:nl-be", source = source),
             MangaTag(title = "Language: Norwegian", key = "lang:no", source = source),
             MangaTag(title = "Language: Polish", key = "lang:pl", source = source),
             MangaTag(title = "Language: Portuguese (Brazil)", key = "lang:pt-br", source = source),
             MangaTag(title = "Language: Portuguese (Portugal)", key = "lang:pt-pt", source = source),
             MangaTag(title = "Language: Romanian", key = "lang:ro", source = source),
             MangaTag(title = "Language: Russian", key = "lang:ru", source = source),
             MangaTag(title = "Language: Slovak", key = "lang:sk", source = source),
             MangaTag(title = "Language: Slovenian", key = "lang:sl", source = source),
             MangaTag(title = "Language: Albanian", key = "lang:sq", source = source),
             MangaTag(title = "Language: Serbian", key = "lang:sr", source = source),
             MangaTag(title = "Language: Serbian (Cyrillic)", key = "lang:sr-cyrl", source = source),
             MangaTag(title = "Language: Swedish", key = "lang:sv", source = source),
             MangaTag(title = "Language: Tamil", key = "lang:ta", source = source),
             MangaTag(title = "Language: Thai", key = "lang:th", source = source),
             MangaTag(title = "Language: Thai (Hong Kong)", key = "lang:th-hk", source = source),
             MangaTag(title = "Language: Thai (Cambodia)", key = "lang:th-kh", source = source),
             MangaTag(title = "Language: Thai (Laos)", key = "lang:th-la", source = source),
             MangaTag(title = "Language: Thai (Malaysia)", key = "lang:th-my", source = source),
             MangaTag(title = "Language: Thai (Singapore)", key = "lang:th-sg", source = source),
             MangaTag(title = "Language: Turkish", key = "lang:tr", source = source),
             MangaTag(title = "Language: Ukrainian", key = "lang:uk", source = source),
             MangaTag(title = "Language: Vietnamese", key = "lang:vi", source = source),
             MangaTag(title = "Language: Chinese", key = "lang:zh", source = source),
             MangaTag(title = "Language: Chinese (China)", key = "lang:zh-cn", source = source),
             MangaTag(title = "Language: Chinese (Hong Kong)", key = "lang:zh-hk", source = source),
             MangaTag(title = "Language: Chinese (Macau)", key = "lang:zh-mo", source = source),
             MangaTag(title = "Language: Chinese (Singapore)", key = "lang:zh-sg", source = source),
             MangaTag(title = "Language: Chinese (Taiwan)", key = "lang:zh-tw", source = source)
        )
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = tags + languages,
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.ABANDONED,
                MangaState.UPCOMING
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
            )
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "https://$domain/api/v1/title/search-advanced/"
        
        val sortValue = when (order) {
            SortOrder.UPDATED -> "updated_chapters_desc"
            SortOrder.NEWEST -> "created_at_desc"
            SortOrder.POPULARITY -> "views_desc"
            SortOrder.ALPHABETICAL -> "name_asc"
            else -> "updated_chapters_desc"
        }

        // Separate real tags from language pseudo-tags
        val (languageTags, contentTags) = filter.tags.partition { it.key.startsWith("lang:") }
        
        val form = mutableMapOf(
            "search_input" to (filter.query ?: ""),
            "filters[sort]" to sortValue,
            "filters[page]" to page.toString(),
            "filters[tag_included_mode]" to "and", 
            "filters[tag_excluded_mode]" to "or",
            "filters[contentRating]" to "any",
            "filters[demographic]" to "any",
            "filters[person]" to "any",
            "filters[publicationYear]" to "",
            "filters[publicationStatus]" to "any"
        )

        filter.states.firstOrNull()?.let {
            form["filters[publicationStatus]"] = when (it) {
                MangaState.ONGOING -> "ongoing"
                MangaState.FINISHED -> "completed"
                MangaState.ABANDONED -> "cancelled"
                MangaState.UPCOMING -> "any"
                else -> "any"
            }
        }
        
        val bodyBuilder = StringBuilder()
        form.forEach { (k, v) -> 
            if (bodyBuilder.isNotEmpty()) bodyBuilder.append("&")
            bodyBuilder.append(k.urlEncoded()).append("=").append(v.urlEncoded())
        }
        
        contentTags.forEach { tag ->
            if (bodyBuilder.isNotEmpty()) bodyBuilder.append("&")
            bodyBuilder.append("filters[tag_included_ids][]".urlEncoded()).append("=").append(tag.key.urlEncoded())
        }

        if (languageTags.isNotEmpty()) {
             languageTags.forEach { langTag ->
                 val langCode = langTag.key.removePrefix("lang:")
                 if (bodyBuilder.isNotEmpty()) bodyBuilder.append("&")
                 bodyBuilder.append("filters[translatedLanguage][]".urlEncoded()).append("=").append(langCode.urlEncoded())
             }
        } 

        val response = webClient.httpPost(url.toHttpUrl(), bodyBuilder.toString())
        val json = response.parseJson()
        val data = json.getJSONArray("data")

        val mangaList = ArrayList<Manga>(data.length())
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            val path = item.getString("url")
            val id = path.substringAfterLast("/").ifEmpty { path }
            
            mangaList.add(Manga(
                id = generateUid(path),
                title = item.getString("name"),
                altTitles = emptySet(),
                url = path,
                publicUrl = "https://$domain/title-detail/$path/",
                rating = RATING_UNKNOWN,
                contentRating = null, // Default to null (Safe) or check logic
                coverUrl = item.optString("cover"),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source
            ))
        }
        return mangaList
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val url = manga.publicUrl.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(url).parseHtml()

        val desc = doc.selectFirst("#descriptionContent p")?.text()
        val author = doc.select("#comicDetail span[data-person-id]").joinToString { it.text() }
        val statusText = doc.selectFirst("span.badge-status")?.text()
        val status = when (statusText) {
            "Ongoing" -> MangaState.ONGOING
            "Completed" -> MangaState.FINISHED
            "Hiatus" -> MangaState.ABANDONED
            "Cancelled" -> MangaState.ABANDONED
            else -> null
        }

        val slug = manga.url.substringAfterLast("/")
        val id = slug.substringAfterLast("-")
        
        val chapterBody = "title_id=$id"
        val chapterUrl = "https://$domain/api/v1/chapter/chapter-listing-by-title-id/"
        val chapterJson = webClient.httpPost(chapterUrl.toHttpUrl(), chapterBody).parseJson()
        val chaptersData = chapterJson.getJSONArray("chapters")
        
        val chapters = ArrayList<MangaChapter>()
        
        for (i in 0 until chaptersData.length()) {
            val ch = chaptersData.getJSONObject(i)
            val translations = ch.getJSONArray("translations")
            val number = ch.optDouble("number", 0.0).toFloat()
            
            for (j in 0 until translations.length()) {
                val trans = translations.getJSONObject(j)
                val lang = trans.optString("language")
                
                val chId = trans.getString("id")
                val title = trans.optString("name")
                val group = trans.optJSONObject("group")?.optString("name")
                val dateStr = trans.optString("date")
                val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).parse(dateStr)?.time ?: 0L

                chapters.add(MangaChapter(
                    id = generateUid(chId),
                    title = if (title.contains(number.toString())) title else "Ch. $number $title",
                    url = chId,
                    number = number,
                    volume = 0,
                    uploadDate = date,
                    scanlator = group,
                    branch = lang,
                    source = source
                ))
            }
        }

        return manga.copy(
            description = desc,
            authors = if (author.isNotBlank()) setOf(author) else emptySet(),
            state = status,
            chapters = chapters.reversed()
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = "https://$domain/chapter-detail/${chapter.url}/"
        val doc = webClient.httpGet(chapterUrl.toHttpUrl()).parseHtml()
        
        val scripts = doc.select("script")
        var imagesJsonStr: String? = null
        val regex = Regex("""const\s+chapterImages\s*=\s*JSON\.parse\(`([^`]+)`\)""")
        
        for (script in scripts) {
             val match = regex.find(script.html())
             if (match != null) {
                 imagesJsonStr = match.groupValues[1]
                 break
             }
        }
        
        if (imagesJsonStr == null) throw ParseException("Chapter images not found")
        
        val jsonArray = JSONObject("{\"a\":$imagesJsonStr}").getJSONArray("a")
        
        val pages = ArrayList<MangaPage>()
        for (i in 0 until jsonArray.length()) {
            val imgUrl = jsonArray.getString(i)
            pages.add(MangaPage(
                id = generateUid(imgUrl),
                url = imgUrl,
                preview = null,
                source = source
            ))
        }
        return pages
    }
}