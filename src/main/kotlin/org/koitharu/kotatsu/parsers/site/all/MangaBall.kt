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
        val url = "https://$domain"
        val doc = webClient.httpGet(url.toHttpUrl(), null).parseHtml()
        val token = doc.selectFirst("meta[name=csrf-token]")?.attr("content")
            ?: throw ParseException("CSRF token not found", url)
        csrfToken = token
        return token
    }

    private val tagsSet: Set<MangaTag> by lazy {
        setOf(
             MangaTag("Gore", "685148d115e8b86aae68e4f3", source),
             MangaTag("Sexual Violence", "685146c5f3ed681c80f257e7", source),
             MangaTag("4-Koma", "685148d115e8b86aae68e4ec", source),
             MangaTag("Adaptation", "685148cf15e8b86aae68e4de", source),
             MangaTag("Anthology", "685148e915e8b86aae68e558", source),
             MangaTag("Award Winning", "685148fe15e8b86aae68e5a7", source),
             MangaTag("Doujinshi", "6851490e15e8b86aae68e5da", source),
             MangaTag("Fan Colored", "6851498215e8b86aae68e704", source),
             MangaTag("Full Color", "685148d615e8b86aae68e502", source),
             MangaTag("Long Strip", "685148d915e8b86aae68e517", source),
             MangaTag("Official Colored", "6851493515e8b86aae68e64a", source),
             MangaTag("Oneshot", "685148eb15e8b86aae68e56c", source),
             MangaTag("Self-Published", "6851492e15e8b86aae68e633", source),
             MangaTag("Web Comic", "685148d715e8b86aae68e50d", source),
             MangaTag("Action", "685146c5f3ed681c80f257e3", source),
             MangaTag("Adult", "689371f0a943baf927094f03", source),
             MangaTag("Adventure", "685146c5f3ed681c80f257e6", source),
             MangaTag("Boys' Love", "685148ef15e8b86aae68e573", source),
             MangaTag("Comedy", "685146c5f3ed681c80f257e5", source),
             MangaTag("Crime", "685148da15e8b86aae68e51f", source),
             MangaTag("Drama", "685148cf15e8b86aae68e4dd", source),
             MangaTag("Ecchi", "6892a73ba943baf927094e37", source),
             MangaTag("Fantasy", "685146c5f3ed681c80f257ea", source),
             MangaTag("Girls' Love", "685148da15e8b86aae68e524", source),
             MangaTag("Historical", "685148db15e8b86aae68e527", source),
             MangaTag("Horror", "685148da15e8b86aae68e520", source),
             MangaTag("Isekai", "685146c5f3ed681c80f257e9", source),
             MangaTag("Magical Girls", "6851490d15e8b86aae68e5d4", source),
             MangaTag("Mature", "68932d11a943baf927094e7b", source),
             MangaTag("Mecha", "6851490c15e8b86aae68e5d2", source),
             MangaTag("Medical", "6851494e15e8b86aae68e66e", source),
             MangaTag("Mystery", "685148d215e8b86aae68e4f4", source),
             MangaTag("Philosophical", "685148e215e8b86aae68e544", source),
             MangaTag("Psychological", "685148d715e8b86aae68e507", source),
             MangaTag("Romance", "685148cf15e8b86aae68e4db", source),
             MangaTag("Sci-Fi", "685148cf15e8b86aae68e4da", source),
             MangaTag("Shounen Ai", "689f0ab1f2e66744c6091524", source),
             MangaTag("Slice of Life", "685148d015e8b86aae68e4e3", source),
             MangaTag("Smut", "689371f2a943baf927094f04", source),
             MangaTag("Sports", "685148f515e8b86aae68e588", source),
             MangaTag("Superhero", "6851492915e8b86aae68e61c", source),
             MangaTag("Thriller", "685148d915e8b86aae68e51e", source),
             MangaTag("Tragedy", "685148db15e8b86aae68e529", source),
             MangaTag("User Created", "68932c3ea943baf927094e77", source),
             MangaTag("Wuxia", "6851490715e8b86aae68e5c3", source),
             MangaTag("Yaoi", "68932f68a943baf927094eaa", source),
             MangaTag("Yuri", "6896a885a943baf927094f66", source),
             MangaTag("Origin: Comic", "68ecab8507ec62d87e62780f", source),
             MangaTag("Origin: Manga", "68ecab1e07ec62d87e627806", source),
             MangaTag("Origin: Manhua", "68ecab4807ec62d87e62780b", source),
             MangaTag("Origin: Manhwa", "68ecab3b07ec62d87e627809", source),
             MangaTag("Theme: Aliens", "6851490d15e8b86aae68e5d5", source),
             MangaTag("Theme: Animals", "685148e715e8b86aae68e54b", source),
             MangaTag("Theme: Comics", "68bf09ff8fdeab0b6a9bc2b7", source),
             MangaTag("Theme: Cooking", "685148d215e8b86aae68e4f8", source),
             MangaTag("Theme: Crossdressing", "685148df15e8b86aae68e534", source),
             MangaTag("Theme: Delinquents", "685148d915e8b86aae68e519", source),
             MangaTag("Theme: Demons", "685146c5f3ed681c80f257e4", source),
             MangaTag("Theme: Genderswap", "685148d715e8b86aae68e505", source),
             MangaTag("Theme: Ghosts", "685148d615e8b86aae68e501", source),
             MangaTag("Theme: Gyaru", "685148d015e8b86aae68e4e8", source),
             MangaTag("Theme: Harem", "685146c5f3ed681c80f257e8", source),
             MangaTag("Theme: Hentai", "68bfceaf4dbc442a26519889", source),
             MangaTag("Theme: Incest", "685148f215e8b86aae68e584", source),
             MangaTag("Theme: Loli", "685148d715e8b86aae68e506", source),
             MangaTag("Theme: Mafia", "685148d915e8b86aae68e518", source),
             MangaTag("Theme: Magic", "685148d715e8b86aae68e509", source),
             MangaTag("Theme: Manhwa 18+", "68f5f5ce5f29d3c1863dec3a", source),
             MangaTag("Theme: Martial Arts", "6851490615e8b86aae68e5c2", source),
             MangaTag("Theme: Military", "685148e215e8b86aae68e541", source),
             MangaTag("Theme: Monster Girls", "685148db15e8b86aae68e52c", source),
             MangaTag("Theme: Monsters", "685146c5f3ed681c80f257e2", source),
             MangaTag("Theme: Music", "685148d015e8b86aae68e4e4", source),
             MangaTag("Theme: Ninja", "685148d715e8b86aae68e508", source),
             MangaTag("Theme: Office Workers", "685148d315e8b86aae68e4fd", source),
             MangaTag("Theme: Police", "6851498815e8b86aae68e714", source),
             MangaTag("Theme: Post-Apocalyptic", "685148e215e8b86aae68e540", source),
             MangaTag("Theme: Reincarnation", "685146c5f3ed681c80f257e1", source),
             MangaTag("Theme: Reverse Harem", "685148df15e8b86aae68e533", source),
             MangaTag("Theme: Samurai", "6851490415e8b86aae68e5b9", source),
             MangaTag("Theme: School Life", "685148d015e8b86aae68e4e7", source),
             MangaTag("Theme: Shota", "685148d115e8b86aae68e4ed", source),
             MangaTag("Theme: Supernatural", "685148db15e8b86aae68e528", source),
             MangaTag("Theme: Survival", "685148cf15e8b86aae68e4dc", source),
             MangaTag("Theme: Time Travel", "6851490c15e8b86aae68e5d1", source),
             MangaTag("Theme: Traditional Games", "6851493515e8b86aae68e645", source),
             MangaTag("Theme: Vampires", "685148f915e8b86aae68e597", source),
             MangaTag("Theme: Video Games", "685148e115e8b86aae68e53c", source),
             MangaTag("Theme: Villainess", "6851492115e8b86aae68e602", source),
             MangaTag("Theme: Virtual Reality", "68514a1115e8b86aae68e83e", source),
             MangaTag("Theme: Zombies", "6851490c15e8b86aae68e5d3", source),
        )
    }

    private val languagesSet: Set<MangaTag> by lazy {
        setOf(
             MangaTag("Language: Arabic", "lang:ar", source),
             MangaTag("Language: Bulgarian", "lang:bg", source),
             MangaTag("Language: Bengali", "lang:bn", source),
             MangaTag("Language: Catalan", "lang:ca", source),
             MangaTag("Language: Catalan (Andorra)", "lang:ca-ad", source),
             MangaTag("Language: Catalan (Spain)", "lang:ca-es", source),
             MangaTag("Language: Catalan (France)", "lang:ca-fr", source),
             MangaTag("Language: Catalan (Italy)", "lang:ca-it", source),
             MangaTag("Language: Catalan (Portugal)", "lang:ca-pt", source),
             MangaTag("Language: Czech", "lang:cs", source),
             MangaTag("Language: Danish", "lang:da", source),
             MangaTag("Language: German", "lang:de", source),
             MangaTag("Language: Greek", "lang:el", source),
             MangaTag("Language: English", "lang:en", source),
             MangaTag("Language: Spanish", "lang:es", source),
             MangaTag("Language: Spanish (Argentina)", "lang:es-ar", source),
             MangaTag("Language: Spanish (Mexico)", "lang:es-mx", source),
             MangaTag("Language: Spanish (Spain)", "lang:es-es", source),
             MangaTag("Language: Spanish (Latin America)", "lang:es-la", source),
             MangaTag("Language: Spanish (Latin America)", "lang:es-419", source),
             MangaTag("Language: Persian", "lang:fa", source),
             MangaTag("Language: Finnish", "lang:fi", source),
             MangaTag("Language: French", "lang:fr", source),
             MangaTag("Language: Hebrew", "lang:he", source),
             MangaTag("Language: Hindi", "lang:hi", source),
             MangaTag("Language: Hungarian", "lang:hu", source),
             MangaTag("Language: Indonesian", "lang:id", source),
             MangaTag("Language: Italian", "lang:it", source),
             MangaTag("Language: Italian (Italy)", "lang:it-it", source),
             MangaTag("Language: Icelandic", "lang:ib", source),
             MangaTag("Language: Icelandic (Iceland)", "lang:ib-is", source),
             MangaTag("Language: Icelandic", "lang:is", source),
             MangaTag("Language: Japanese", "lang:jp", source),
             MangaTag("Language: Korean", "lang:kr", source),
             MangaTag("Language: Kannada", "lang:kn", source),
             MangaTag("Language: Kannada (India)", "lang:kn-in", source),
             MangaTag("Language: Kannada (Malaysia)", "lang:kn-my", source),
             MangaTag("Language: Kannada (Singapore)", "lang:kn-sg", source),
             MangaTag("Language: Kannada (Taiwan)", "lang:kn-tw", source),
             MangaTag("Language: Malayalam", "lang:ml", source),
             MangaTag("Language: Malayalam (India)", "lang:ml-in", source),
             MangaTag("Language: Malayalam (Malaysia)", "lang:ml-my", source),
             MangaTag("Language: Malayalam (Singapore)", "lang:ml-sg", source),
             MangaTag("Language: Malayalam (Taiwan)", "lang:ml-tw", source),
             MangaTag("Language: Malay", "lang:ms", source),
             MangaTag("Language: Nepali", "lang:ne", source),
             MangaTag("Language: Dutch", "lang:nl", source),
             MangaTag("Language: Dutch (Belgium)", "lang:nl-be", source),
             MangaTag("Language: Norwegian", "lang:no", source),
             MangaTag("Language: Polish", "lang:pl", source),
             MangaTag("Language: Portuguese (Brazil)", "lang:pt-br", source),
             MangaTag("Language: Portuguese (Portugal)", "lang:pt-pt", source),
             MangaTag("Language: Romanian", "lang:ro", source),
             MangaTag("Language: Russian", "lang:ru", source),
             MangaTag("Language: Slovak", "lang:sk", source),
             MangaTag("Language: Slovenian", "lang:sl", source),
             MangaTag("Language: Albanian", "lang:sq", source),
             MangaTag("Language: Serbian", "lang:sr", source),
             MangaTag("Language: Serbian (Cyrillic)", "lang:sr-cyrl", source),
             MangaTag("Language: Swedish", "lang:sv", source),
             MangaTag("Language: Tamil", "lang:ta", source),
             MangaTag("Language: Thai", "lang:th", source),
             MangaTag("Language: Thai (Hong Kong)", "lang:th-hk", source),
             MangaTag("Language: Thai (Cambodia)", "lang:th-kh", source),
             MangaTag("Language: Thai (Laos)", "lang:th-la", source),
             MangaTag("Language: Thai (Malaysia)", "lang:th-my", source),
             MangaTag("Language: Thai (Singapore)", "lang:th-sg", source),
             MangaTag("Language: Turkish", "lang:tr", source),
             MangaTag("Language: Ukrainian", "lang:uk", source),
             MangaTag("Language: Vietnamese", "lang:vi", source),
             MangaTag("Language: Chinese", "lang:zh", source),
             MangaTag("Language: Chinese (China)", "lang:zh-cn", source),
             MangaTag("Language: Chinese (Hong Kong)", "lang:zh-hk", source),
             MangaTag("Language: Chinese (Macau)", "lang:zh-mo", source),
             MangaTag("Language: Chinese (Singapore)", "lang:zh-sg", source),
             MangaTag("Language: Chinese (Taiwan)", "lang:zh-tw", source)
        )
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = tagsSet + languagesSet,
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.ABANDONED,
                MangaState.UPCOMING,
                MangaState.PAUSED,
                MangaState.RESTRICTED
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
                MangaState.PAUSED -> "hiatus"
                MangaState.RESTRICTED -> "any"
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

        val response = webClient.httpPost(url.toHttpUrl(), bodyBuilder.toString(), null)
        val json = response.parseJson()
        val data = json.getJSONArray("data")

        val mangaList = ArrayList<Manga>(data.length())
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            val path = item.getString("url")
            
            mangaList.add(Manga(
                id = generateUid(path),
                title = item.getString("name"),
                altTitles = emptySet(),
                url = path,
                publicUrl = "https://$domain/title-detail/$path/",
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = item.optString("cover"),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                largeCoverUrl = null,
                description = null,
                chapters = null,
                source = source
            ))
        }
        return mangaList
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val url = manga.publicUrl.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(url.toHttpUrl(), null).parseHtml()

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
        val chapterJson = webClient.httpPost(chapterUrl.toHttpUrl(), chapterBody, null).parseJson()
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
                    number = number,
                    volume = 0,
                    url = chId,
                    scanlator = group,
                    uploadDate = date,
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
        val doc = webClient.httpGet(chapterUrl.toHttpUrl(), null).parseHtml()
        
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
        
        if (imagesJsonStr == null) throw ParseException("Chapter images not found", chapterUrl)
        
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