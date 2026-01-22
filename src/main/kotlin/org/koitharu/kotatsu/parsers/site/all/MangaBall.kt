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
            isOriginalLocaleSupported = true,
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
        val urlString = "https://$domain"
        val doc = webClient.httpGet(urlString.toHttpUrl()).parseHtml()
        val token = doc.selectFirst("meta[name=csrf-token]")?.attr("content")
            ?: throw ParseException(shortMessage = "CSRF token not found", url = urlString)
        csrfToken = token
        return token
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = TAGS,
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.ABANDONED,
                MangaState.UPCOMING,
                MangaState.PAUSED,
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
            ),
            availableLocales = LANGUAGES.values.toSet()
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val apiUrl = "https://$domain/api/v1/title/search-advanced/".toHttpUrl()
        
        val sortValue = when (order) {
            SortOrder.UPDATED -> "updated_chapters_desc"
            SortOrder.NEWEST -> "created_at_desc"
            SortOrder.POPULARITY -> "views_desc"
            SortOrder.ALPHABETICAL -> "name_asc"
            else -> "updated_chapters_desc"
        }

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
        
        filter.tags.forEach { tag ->
            if (bodyBuilder.isNotEmpty()) bodyBuilder.append("&")
            bodyBuilder.append("filters[tag_included_ids][]".urlEncoded()).append("=").append(tag.key.urlEncoded())
        }

        // Handle Locale (mimic BatoTo langs logic)
        filter.locale?.let { locale ->
            val code = LANGUAGES.entries.find { it.value == locale }?.key
            if (code != null) {
                // If it's a language like 'es' or 'pt', we might want to send variants if the API supports it.
                // But following BatoTo, we just send the language code.
                if (bodyBuilder.isNotEmpty()) bodyBuilder.append("&")
                bodyBuilder.append("filters[translatedLanguage][]".urlEncoded()).append("=").append(code.urlEncoded())
            }
        }

        val response = webClient.httpPost(apiUrl, bodyBuilder.toString())
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
        val urlString = manga.publicUrl.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(urlString.toHttpUrl()).parseHtml()

        val desc = doc.selectFirst("#descriptionContent p")?.text()
        val authors = doc.select("#comicDetail span[data-person-id]").mapToSet { it.text() }
        val statusText = doc.selectFirst("span.badge-status")?.text()
        val status = when (statusText) {
            "Ongoing" -> MangaState.ONGOING
            "Completed" -> MangaState.FINISHED
            "Hiatus" -> MangaState.PAUSED
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
                val date = runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).parse(dateStr)?.time }.getOrNull() ?: 0L

                chapters.add(MangaChapter(
                    id = generateUid(chId),
                    title = if (title.contains(number.formatSimple())) title else "Ch. ${number.formatSimple()} $title",
                    number = number,
                    volume = 0,
                    url = chId,
                    scanlator = if (group != null) "$group ($lang)" else lang,
                    uploadDate = date,
                    branch = group ?: "Unknown", // Group by scanlator like BatoTo
                    source = source
                ))
            }
        }

        return manga.copy(
            description = desc,
            authors = authors,
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
        
        if (imagesJsonStr == null) throw ParseException(shortMessage = "Chapter images not found", url = chapterUrl)
        
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

    companion object {
        private val TAGS = setOf(
             MangaTag("Gore", "685148d115e8b86aae68e4f3", MangaParserSource.MANGABALL),
             MangaTag("Sexual Violence", "685146c5f3ed681c80f257e7", MangaParserSource.MANGABALL),
             MangaTag("4-Koma", "685148d115e8b86aae68e4ec", MangaParserSource.MANGABALL),
             MangaTag("Adaptation", "685148cf15e8b86aae68e4de", MangaParserSource.MANGABALL),
             MangaTag("Anthology", "685148e915e8b86aae68e558", MangaParserSource.MANGABALL),
             MangaTag("Award Winning", "685148fe15e8b86aae68e5a7", MangaParserSource.MANGABALL),
             MangaTag("Doujinshi", "6851490e15e8b86aae68e5da", MangaParserSource.MANGABALL),
             MangaTag("Fan Colored", "6851498215e8b86aae68e704", MangaParserSource.MANGABALL),
             MangaTag("Full Color", "685148d615e8b86aae68e502", MangaParserSource.MANGABALL),
             MangaTag("Long Strip", "685148d915e8b86aae68e517", MangaParserSource.MANGABALL),
             MangaTag("Official Colored", "6851493515e8b86aae68e64a", MangaParserSource.MANGABALL),
             MangaTag("Oneshot", "685148eb15e8b86aae68e56c", MangaParserSource.MANGABALL),
             MangaTag("Self-Published", "6851492e15e8b86aae68e633", MangaParserSource.MANGABALL),
             MangaTag("Web Comic", "685148d715e8b86aae68e50d", MangaParserSource.MANGABALL),
             MangaTag("Action", "685146c5f3ed681c80f257e3", MangaParserSource.MANGABALL),
             MangaTag("Adult", "689371f0a943baf927094f03", MangaParserSource.MANGABALL),
             MangaTag("Adventure", "685146c5f3ed681c80f257e6", MangaParserSource.MANGABALL),
             MangaTag("Boys' Love", "685148ef15e8b86aae68e573", MangaParserSource.MANGABALL),
             MangaTag("Comedy", "685146c5f3ed681c80f257e5", MangaParserSource.MANGABALL),
             MangaTag("Crime", "685148da15e8b86aae68e51f", MangaParserSource.MANGABALL),
             MangaTag("Drama", "685148cf15e8b86aae68e4dd", MangaParserSource.MANGABALL),
             MangaTag("Ecchi", "6892a73ba943baf927094e37", MangaParserSource.MANGABALL),
             MangaTag("Fantasy", "685146c5f3ed681c80f257ea", MangaParserSource.MANGABALL),
             MangaTag("Girls' Love", "685148da15e8b86aae68e524", MangaParserSource.MANGABALL),
             MangaTag("Historical", "685148db15e8b86aae68e527", MangaParserSource.MANGABALL),
             MangaTag("Horror", "685148da15e8b86aae68e520", MangaParserSource.MANGABALL),
             MangaTag("Isekai", "685146c5f3ed681c80f257e9", MangaParserSource.MANGABALL),
             MangaTag("Josei(W)", "694cc2d9f8014f5e0a63ac73", MangaParserSource.MANGABALL),
             MangaTag("Magical Girls", "6851490d15e8b86aae68e5d4", MangaParserSource.MANGABALL),
             MangaTag("Mature", "68932d11a943baf927094e7b", MangaParserSource.MANGABALL),
             MangaTag("Mecha", "6851490c15e8b86aae68e5d2", MangaParserSource.MANGABALL),
             MangaTag("Medical", "6851494e15e8b86aae68e66e", MangaParserSource.MANGABALL),
             MangaTag("Mystery", "685148d215e8b86aae68e4f4", MangaParserSource.MANGABALL),
             MangaTag("Philosophical", "685148e215e8b86aae68e544", MangaParserSource.MANGABALL),
             MangaTag("Psychological", "685148d715e8b86aae68e507", MangaParserSource.MANGABALL),
             MangaTag("Revenge", "694cc2d9f8014f5e0a63ac75", MangaParserSource.MANGABALL),
             MangaTag("Romance", "685148cf15e8b86aae68e4db", MangaParserSource.MANGABALL),
             MangaTag("Sci-Fi", "685148cf15e8b86aae68e4da", MangaParserSource.MANGABALL),
             MangaTag("Shoujo(G)", "694cc2d9f8014f5e0a63ac74", MangaParserSource.MANGABALL),
             MangaTag("Shounen Ai", "689f0ab1f2e66744c6091524", MangaParserSource.MANGABALL),
             MangaTag("Slice of Life", "685148d015e8b86aae68e4e3", MangaParserSource.MANGABALL),
             MangaTag("Smut", "689371f2a943baf927094f04", MangaParserSource.MANGABALL),
             MangaTag("Sports", "685148f515e8b86aae68e588", MangaParserSource.MANGABALL),
             MangaTag("Superhero", "6851492915e8b86aae68e61c", MangaParserSource.MANGABALL),
             MangaTag("Thriller", "685148d915e8b86aae68e51e", MangaParserSource.MANGABALL),
             MangaTag("Tragedy", "685148db15e8b86aae68e529", MangaParserSource.MANGABALL),
             MangaTag("User Created", "68932c3ea943baf927094e77", MangaParserSource.MANGABALL),
             MangaTag("Wuxia", "6851490715e8b86aae68e5c3", MangaParserSource.MANGABALL),
             MangaTag("Yaoi", "68932f68a943baf927094eaa", MangaParserSource.MANGABALL),
             MangaTag("Yuri", "6896a885a943baf927094f66", MangaParserSource.MANGABALL),
             MangaTag("Origin: Comic", "68ecab8507ec62d87e62780f", MangaParserSource.MANGABALL),
             MangaTag("Origin: Manga", "68ecab1e07ec62d87e627806", MangaParserSource.MANGABALL),
             MangaTag("Origin: Manhua", "68ecab4807ec62d87e62780b", MangaParserSource.MANGABALL),
             MangaTag("Origin: Manhwa", "68ecab3b07ec62d87e627809", MangaParserSource.MANGABALL),
             MangaTag("Theme: Aliens", "6851490d15e8b86aae68e5d5", MangaParserSource.MANGABALL),
             MangaTag("Theme: Animals", "685148e715e8b86aae68e54b", MangaParserSource.MANGABALL),
             MangaTag("Theme: Comics", "68bf09ff8fdeab0b6a9bc2b7", MangaParserSource.MANGABALL),
             MangaTag("Theme: Cooking", "685148d215e8b86aae68e4f8", MangaParserSource.MANGABALL),
             MangaTag("Theme: Crossdressing", "685148df15e8b86aae68e534", MangaParserSource.MANGABALL),
             MangaTag("Theme: Delinquents", "685148d915e8b86aae68e519", MangaParserSource.MANGABALL),
             MangaTag("Theme: Demons", "685146c5f3ed681c80f257e4", MangaParserSource.MANGABALL),
             MangaTag("Theme: Genderswap", "685148d715e8b86aae68e505", MangaParserSource.MANGABALL),
             MangaTag("Theme: Ghosts", "685148d615e8b86aae68e501", MangaParserSource.MANGABALL),
             MangaTag("Theme: Gyaru", "685148d015e8b86aae68e4e8", MangaParserSource.MANGABALL),
             MangaTag("Theme: Harem", "685146c5f3ed681c80f257e8", MangaParserSource.MANGABALL),
             MangaTag("Theme: Hentai", "68bfceaf4dbc442a26519889", MangaParserSource.MANGABALL),
             MangaTag("Theme: Incest", "685148f215e8b86aae68e584", MangaParserSource.MANGABALL),
             MangaTag("Theme: Loli", "685148d715e8b86aae68e506", MangaParserSource.MANGABALL),
             MangaTag("Theme: Mafia", "685148d915e8b86aae68e518", MangaParserSource.MANGABALL),
             MangaTag("Theme: Magic", "685148d715e8b86aae68e509", MangaParserSource.MANGABALL),
             MangaTag("Theme: Manhwa 18+", "68f5f5ce5f29d3c1863dec3a", MangaParserSource.MANGABALL),
             MangaTag("Theme: Martial Arts", "6851490615e8b86aae68e5c2", MangaParserSource.MANGABALL),
             MangaTag("Theme: Military", "685148e215e8b86aae68e541", MangaParserSource.MANGABALL),
             MangaTag("Theme: Monster Girls", "685148db15e8b86aae68e52c", MangaParserSource.MANGABALL),
             MangaTag("Theme: Monsters", "685146c5f3ed681c80f257e2", MangaParserSource.MANGABALL),
             MangaTag("Theme: Music", "685148d015e8b86aae68e4e4", MangaParserSource.MANGABALL),
             MangaTag("Theme: Ninja", "685148d715e8b86aae68e508", MangaParserSource.MANGABALL),
             MangaTag("Theme: Office Workers", "685148d315e8b86aae68e4fd", MangaParserSource.MANGABALL),
             MangaTag("Theme: Police", "6851498815e8b86aae68e714", MangaParserSource.MANGABALL),
             MangaTag("Theme: Post-Apocalyptic", "685148e215e8b86aae68e540", MangaParserSource.MANGABALL),
             MangaTag("Theme: Reincarnation", "685146c5f3ed681c80f257e1", MangaParserSource.MANGABALL),
             MangaTag("Theme: Reverse Harem", "685148df15e8b86aae68e533", MangaParserSource.MANGABALL),
             MangaTag("Theme: Samurai", "6851490415e8b86aae68e5b9", MangaParserSource.MANGABALL),
             MangaTag("Theme: School Life", "685148d015e8b86aae68e4e7", MangaParserSource.MANGABALL),
             MangaTag("Theme: Shota", "685148d115e8b86aae68e4ed", MangaParserSource.MANGABALL),
             MangaTag("Theme: Supernatural", "685148db15e8b86aae68e528", MangaParserSource.MANGABALL),
             MangaTag("Theme: Survival", "685148cf15e8b86aae68e4dc", MangaParserSource.MANGABALL),
             MangaTag("Theme: Time Travel", "6851490c15e8b86aae68e5d1", MangaParserSource.MANGABALL),
             MangaTag("Theme: Traditional Games", "6851493515e8b86aae68e645", MangaParserSource.MANGABALL),
             MangaTag("Theme: Vampires", "685148f915e8b86aae68e597", MangaParserSource.MANGABALL),
             MangaTag("Theme: Video Games", "685148e115e8b86aae68e53c", MangaParserSource.MANGABALL),
             MangaTag("Theme: Villainess", "6851492115e8b86aae68e602", MangaParserSource.MANGABALL),
             MangaTag("Theme: Virtual Reality", "68514a1115e8b86aae68e83e", MangaParserSource.MANGABALL),
             MangaTag("Theme: Zombies", "6851490c15e8b86aae68e5d3", MangaParserSource.MANGABALL),
        )

        private val LANGUAGES = mapOf(
            "ar" to Locale("ar"),
            "bg" to Locale("bg"),
            "bn" to Locale("bn"),
            "ca" to Locale("ca"),
            "cs" to Locale("cs"),
            "da" to Locale("da"),
            "de" to Locale("de"),
            "el" to Locale("el"),
            "en" to Locale("en"),
            "es" to Locale("es"),
            "fa" to Locale("fa"),
            "fi" to Locale("fi"),
            "fr" to Locale("fr"),
            "he" to Locale("he"),
            "hi" to Locale("hi"),
            "hu" to Locale("hu"),
            "id" to Locale("id"),
            "it" to Locale("it"),
            "is" to Locale("is"),
            "ja" to Locale("ja"),
            "ko" to Locale("ko"),
            "kn" to Locale("kn"),
            "ml" to Locale("ml"),
            "ms" to Locale("ms"),
            "ne" to Locale("ne"),
            "nl" to Locale("nl"),
            "no" to Locale("no"),
            "pl" to Locale("pl"),
            "pt-br" to Locale("pt", "BR"),
            "pt-pt" to Locale("pt", "PT"),
            "ro" to Locale("ro"),
            "ru" to Locale("ru"),
            "sk" to Locale("sk"),
            "sl" to Locale("sl"),
            "sq" to Locale("sq"),
            "sr" to Locale("sr"),
            "sv" to Locale("sv"),
            "ta" to Locale("ta"),
            "th" to Locale("th"),
            "tr" to Locale("tr"),
            "uk" to Locale("uk"),
            "vi" to Locale("vi"),
            "zh" to Locale("zh")
        )
    }
}
