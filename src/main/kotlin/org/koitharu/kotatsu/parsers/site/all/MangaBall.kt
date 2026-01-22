package org.koitharu.kotatsu.parsers.site.all

import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.EnumSet
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
        SortOrder.POPULAR,
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

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = TAGS + LANGUAGES,
            availableSortOrders = availableSortOrders,
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
            SortOrder.POPULAR -> "views_desc"
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

        // If specific languages are selected, use them. 
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
                url = path,
                publicUrl = "https://$domain/title-detail/$path/",
                coverUrl = item.optString("cover"),
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
                
                // Always include, use 'branch' for language
                val chId = trans.getString("id")
                val title = trans.optString("name")
                val group = trans.optJSONObject("group")?.optString("name")
                val dateStr = trans.optString("date")
                val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).parse(dateStr)?.time ?: 0L

                chapters.add(MangaChapter(
                    id = generateUid(chId),
                    title = if (title.contains(number.toString())) title else "Ch. $number $title",
                    url = chId,
                    number = number,
                    uploadDate = date,
                    scanlator = group,
                    branch = lang, // Use branch to indicate language
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
                source = source
            ))
        }
        return pages
    }

    companion object {
        private val TAGS = setOf(
             MangaTag("685148d115e8b86aae68e4f3", "Gore"),
             MangaTag("685146c5f3ed681c80f257e7", "Sexual Violence"),
             MangaTag("685148d115e8b86aae68e4ec", "4-Koma"),
             MangaTag("685148cf15e8b86aae68e4de", "Adaptation"),
             MangaTag("685148e915e8b86aae68e558", "Anthology"),
             MangaTag("685148fe15e8b86aae68e5a7", "Award Winning"),
             MangaTag("6851490e15e8b86aae68e5da", "Doujinshi"),
             MangaTag("6851498215e8b86aae68e704", "Fan Colored"),
             MangaTag("685148d615e8b86aae68e502", "Full Color"),
             MangaTag("685148d915e8b86aae68e517", "Long Strip"),
             MangaTag("6851493515e8b86aae68e64a", "Official Colored"),
             MangaTag("685148eb15e8b86aae68e56c", "Oneshot"),
             MangaTag("6851492e15e8b86aae68e633", "Self-Published"),
             MangaTag("685148d715e8b86aae68e50d", "Web Comic"),
             MangaTag("685146c5f3ed681c80f257e3", "Action"),
             MangaTag("689371f0a943baf927094f03", "Adult"),
             MangaTag("685146c5f3ed681c80f257e6", "Adventure"),
             MangaTag("685148ef15e8b86aae68e573", "Boys' Love"),
             MangaTag("685146c5f3ed681c80f257e5", "Comedy"),
             MangaTag("685148da15e8b86aae68e51f", "Crime"),
             MangaTag("685148cf15e8b86aae68e4dd", "Drama"),
             MangaTag("6892a73ba943baf927094e37", "Ecchi"),
             MangaTag("685146c5f3ed681c80f257ea", "Fantasy"),
             MangaTag("685148da15e8b86aae68e524", "Girls' Love"),
             MangaTag("685148db15e8b86aae68e527", "Historical"),
             MangaTag("685148da15e8b86aae68e520", "Horror"),
             MangaTag("685146c5f3ed681c80f257e9", "Isekai"),
             MangaTag("6851490d15e8b86aae68e5d4", "Magical Girls"),
             MangaTag("68932d11a943baf927094e7b", "Mature"),
             MangaTag("6851490c15e8b86aae68e5d2", "Mecha"),
             MangaTag("6851494e15e8b86aae68e66e", "Medical"),
             MangaTag("685148d215e8b86aae68e4f4", "Mystery"),
             MangaTag("685148e215e8b86aae68e544", "Philosophical"),
             MangaTag("685148d715e8b86aae68e507", "Psychological"),
             MangaTag("685148cf15e8b86aae68e4db", "Romance"),
             MangaTag("685148cf15e8b86aae68e4da", "Sci-Fi"),
             MangaTag("689f0ab1f2e66744c6091524", "Shounen Ai"),
             MangaTag("685148d015e8b86aae68e4e3", "Slice of Life"),
             MangaTag("689371f2a943baf927094f04", "Smut"),
             MangaTag("685148f515e8b86aae68e588", "Sports"),
             MangaTag("6851492915e8b86aae68e61c", "Superhero"),
             MangaTag("685148d915e8b86aae68e51e", "Thriller"),
             MangaTag("685148db15e8b86aae68e529", "Tragedy"),
             MangaTag("68932c3ea943baf927094e77", "User Created"),
             MangaTag("6851490715e8b86aae68e5c3", "Wuxia"),
             MangaTag("68932f68a943baf927094eaa", "Yaoi"),
             MangaTag("6896a885a943baf927094f66", "Yuri"),
             MangaTag("68ecab8507ec62d87e62780f", "Origin: Comic"),
             MangaTag("68ecab1e07ec62d87e627806", "Origin: Manga"),
             MangaTag("68ecab4807ec62d87e62780b", "Origin: Manhua"),
             MangaTag("68ecab3b07ec62d87e627809", "Origin: Manhwa"),
             MangaTag("6851490d15e8b86aae68e5d5", "Theme: Aliens"),
             MangaTag("685148e715e8b86aae68e54b", "Theme: Animals"),
             MangaTag("68bf09ff8fdeab0b6a9bc2b7", "Theme: Comics"),
             MangaTag("685148d215e8b86aae68e4f8", "Theme: Cooking"),
             MangaTag("685148df15e8b86aae68e534", "Theme: Crossdressing"),
             MangaTag("685148d915e8b86aae68e519", "Theme: Delinquents"),
             MangaTag("685146c5f3ed681c80f257e4", "Theme: Demons"),
             MangaTag("685148d715e8b86aae68e505", "Theme: Genderswap"),
             MangaTag("685148d615e8b86aae68e501", "Theme: Ghosts"),
             MangaTag("685148d015e8b86aae68e4e8", "Theme: Gyaru"),
             MangaTag("685146c5f3ed681c80f257e8", "Theme: Harem"),
             MangaTag("68bfceaf4dbc442a26519889", "Theme: Hentai"),
             MangaTag("685148f215e8b86aae68e584", "Theme: Incest"),
             MangaTag("685148d715e8b86aae68e506", "Theme: Loli"),
             MangaTag("685148d915e8b86aae68e518", "Theme: Mafia"),
             MangaTag("685148d715e8b86aae68e509", "Theme: Magic"),
             MangaTag("68f5f5ce5f29d3c1863dec3a", "Theme: Manhwa 18+"),
             MangaTag("6851490615e8b86aae68e5c2", "Theme: Martial Arts"),
             MangaTag("685148e215e8b86aae68e541", "Theme: Military"),
             MangaTag("685148db15e8b86aae68e52c", "Theme: Monster Girls"),
             MangaTag("685146c5f3ed681c80f257e2", "Theme: Monsters"),
             MangaTag("685148d015e8b86aae68e4e4", "Theme: Music"),
             MangaTag("685148d715e8b86aae68e508", "Theme: Ninja"),
             MangaTag("685148d315e8b86aae68e4fd", "Theme: Office Workers"),
             MangaTag("6851498815e8b86aae68e714", "Theme: Police"),
             MangaTag("685148e215e8b86aae68e540", "Theme: Post-Apocalyptic"),
             MangaTag("685146c5f3ed681c80f257e1", "Theme: Reincarnation"),
             MangaTag("685148df15e8b86aae68e533", "Theme: Reverse Harem"),
             MangaTag("6851490415e8b86aae68e5b9", "Theme: Samurai"),
             MangaTag("685148d015e8b86aae68e4e7", "Theme: School Life"),
             MangaTag("685148d115e8b86aae68e4ed", "Theme: Shota"),
             MangaTag("685148db15e8b86aae68e528", "Theme: Supernatural"),
             MangaTag("685148cf15e8b86aae68e4dc", "Theme: Survival"),
             MangaTag("6851490c15e8b86aae68e5d1", "Theme: Time Travel"),
             MangaTag("6851493515e8b86aae68e645", "Theme: Traditional Games"),
             MangaTag("685148f915e8b86aae68e597", "Theme: Vampires"),
             MangaTag("685148e115e8b86aae68e53c", "Theme: Video Games"),
             MangaTag("6851492115e8b86aae68e602", "Theme: Villainess"),
             MangaTag("68514a1115e8b86aae68e83e", "Theme: Virtual Reality"),
             MangaTag("6851490c15e8b86aae68e5d3", "Theme: Zombies"),
        )
        
        private val LANGUAGES = setOf(
             MangaTag("lang:ar", "Language: Arabic"),
             MangaTag("lang:bg", "Language: Bulgarian"),
             MangaTag("lang:bn", "Language: Bengali"),
             MangaTag("lang:ca", "Language: Catalan"),
             MangaTag("lang:ca-ad", "Language: Catalan (Andorra)"),
             MangaTag("lang:ca-es", "Language: Catalan (Spain)"),
             MangaTag("lang:ca-fr", "Language: Catalan (France)"),
             MangaTag("lang:ca-it", "Language: Catalan (Italy)"),
             MangaTag("lang:ca-pt", "Language: Catalan (Portugal)"),
             MangaTag("lang:cs", "Language: Czech"),
             MangaTag("lang:da", "Language: Danish"),
             MangaTag("lang:de", "Language: German"),
             MangaTag("lang:el", "Language: Greek"),
             MangaTag("lang:en", "Language: English"),
             MangaTag("lang:es", "Language: Spanish"),
             MangaTag("lang:es-ar", "Language: Spanish (Argentina)"),
             MangaTag("lang:es-mx", "Language: Spanish (Mexico)"),
             MangaTag("lang:es-es", "Language: Spanish (Spain)"),
             MangaTag("lang:es-la", "Language: Spanish (Latin America)"),
             MangaTag("lang:es-419", "Language: Spanish (Latin America)"),
             MangaTag("lang:fa", "Language: Persian"),
             MangaTag("lang:fi", "Language: Finnish"),
             MangaTag("lang:fr", "Language: French"),
             MangaTag("lang:he", "Language: Hebrew"),
             MangaTag("lang:hi", "Language: Hindi"),
             MangaTag("lang:hu", "Language: Hungarian"),
             MangaTag("lang:id", "Language: Indonesian"),
             MangaTag("lang:it", "Language: Italian"),
             MangaTag("lang:it-it", "Language: Italian (Italy)"),
             MangaTag("lang:ib", "Language: Icelandic"),
             MangaTag("lang:ib-is", "Language: Icelandic (Iceland)"),
             MangaTag("lang:is", "Language: Icelandic"),
             MangaTag("lang:jp", "Language: Japanese"),
             MangaTag("lang:kr", "Language: Korean"),
             MangaTag("lang:kn", "Language: Kannada"),
             MangaTag("lang:kn-in", "Language: Kannada (India)"),
             MangaTag("lang:kn-my", "Language: Kannada (Malaysia)"),
             MangaTag("lang:kn-sg", "Language: Kannada (Singapore)"),
             MangaTag("lang:kn-tw", "Language: Kannada (Taiwan)"),
             MangaTag("lang:ml", "Language: Malayalam"),
             MangaTag("lang:ml-in", "Language: Malayalam (India)"),
             MangaTag("lang:ml-my", "Language: Malayalam (Malaysia)"),
             MangaTag("lang:ml-sg", "Language: Malayalam (Singapore)"),
             MangaTag("lang:ml-tw", "Language: Malayalam (Taiwan)"),
             MangaTag("lang:ms", "Language: Malay"),
             MangaTag("lang:ne", "Language: Nepali"),
             MangaTag("lang:nl", "Language: Dutch"),
             MangaTag("lang:nl-be", "Language: Dutch (Belgium)"),
             MangaTag("lang:no", "Language: Norwegian"),
             MangaTag("lang:pl", "Language: Polish"),
             MangaTag("lang:pt-br", "Language: Portuguese (Brazil)"),
             MangaTag("lang:pt-pt", "Language: Portuguese (Portugal)"),
             MangaTag("lang:ro", "Language: Romanian"),
             MangaTag("lang:ru", "Language: Russian"),
             MangaTag("lang:sk", "Language: Slovak"),
             MangaTag("lang:sl", "Language: Slovenian"),
             MangaTag("lang:sq", "Language: Albanian"),
             MangaTag("lang:sr", "Language: Serbian"),
             MangaTag("lang:sr-cyrl", "Language: Serbian (Cyrillic)"),
             MangaTag("lang:sv", "Language: Swedish"),
             MangaTag("lang:ta", "Language: Tamil"),
             MangaTag("lang:th", "Language: Thai"),
             MangaTag("lang:th-hk", "Language: Thai (Hong Kong)"),
             MangaTag("lang:th-kh", "Language: Thai (Cambodia)"),
             MangaTag("lang:th-la", "Language: Thai (Laos)"),
             MangaTag("lang:th-my", "Language: Thai (Malaysia)"),
             MangaTag("lang:th-sg", "Language: Thai (Singapore)"),
             MangaTag("lang:tr", "Language: Turkish"),
             MangaTag("lang:uk", "Language: Ukrainian"),
             MangaTag("lang:vi", "Language: Vietnamese"),
             MangaTag("lang:zh", "Language: Chinese"),
             MangaTag("lang:zh-cn", "Language: Chinese (China)"),
             MangaTag("lang:zh-hk", "Language: Chinese (Hong Kong)"),
             MangaTag("lang:zh-mo", "Language: Chinese (Macau)"),
             MangaTag("lang:zh-sg", "Language: Chinese (Singapore)"),
             MangaTag("lang:zh-tw", "Language: Chinese (Taiwan)")
        )
    }
}