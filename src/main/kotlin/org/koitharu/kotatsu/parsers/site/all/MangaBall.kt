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
        SortOrder.RATING,
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
        val newRequest = request.newBuilder()
            .header("X-CSRF-TOKEN", token)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        val response = chain.proceed(newRequest)

        if (response.code == 403) {
            response.close()
            val newToken = runBlocking { getCsrfToken(forceRefresh = true) }
            val retryRequest = request.newBuilder()
                .header("X-CSRF-TOKEN", newToken)
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            return chain.proceed(retryRequest)
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
            availableTags = fetchTags(),
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
                ContentType.COMICS,
            ),
            availableLocales = LANGUAGES.values.toSet()
        )
    }

    private suspend fun fetchTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/search-advanced/").parseHtml()
        return doc.select("button.tag-btn").mapToSet {
            MangaTag(title = it.text(), key = it.attr("data-tag"), source = source)
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val apiUrl = "https://$domain/api/v1/title/search-advanced/".toHttpUrl()
        
        val sortValue = when (order) {
            SortOrder.UPDATED -> "updated_chapters_desc"
            SortOrder.NEWEST -> "created_at_desc"
            SortOrder.POPULARITY -> "views_desc"
            SortOrder.ALPHABETICAL -> "name_asc"
            SortOrder.RATING -> "rating_desc"
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
                else -> "any"
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

        var selectedLang: String? = null
        filter.locale?.let { locale ->
            val code = LANGUAGES.entries.find { it.value == locale }?.key
            if (code != null) {
                selectedLang = code
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
            val fullUrl = item.getString("url")
            val slug = fullUrl.toHttpUrl().pathSegments.getOrNull(1) ?: fullUrl
            
            // Encode the selected language into the URL so getDetails can filter chapters
            val mangaUrl = if (selectedLang != null) "$slug?lang=$selectedLang" else slug
            
            mangaList.add(Manga(
                id = generateUid(slug),
                title = item.getString("name"),
                altTitles = emptySet(),
                url = mangaUrl,
                publicUrl = "https://$domain/title-detail/$slug/",
                rating = RATING_UNKNOWN,
                contentRating = if (item.optBoolean("isAdult")) ContentRating.ADULT else null,
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
        val slug = manga.url.substringBefore("?")
        val langFilter = manga.url.substringAfter("?lang=", "").takeIf { it.isNotEmpty() }
        
        val url = "https://$domain/title-detail/$slug/".toHttpUrl()
        val doc = webClient.httpGet(url).parseHtml()

        val desc = doc.selectFirst("#descriptionContent p")?.text()
        val authors = doc.select("#comicDetail span[data-person-id]").mapToSet { it.text() }
        val tags = doc.select("#comicDetail span[data-tag-id]").mapToSet {
            MangaTag(title = it.text(), key = it.attr("data-tag-id"), source = source)
        }
        val altTitles = doc.select("div.alternate-name-container").text().split("/")
            .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }.toSet()

        val statusText = doc.selectFirst("span.badge-status")?.text()
        val status = when (statusText) {
            "Ongoing" -> MangaState.ONGOING
            "Completed" -> MangaState.FINISHED
            "Hiatus" -> MangaState.PAUSED
            "Cancelled" -> MangaState.ABANDONED
            else -> null
        }

        val id = slug.substringAfterLast("-")
        val chapterBody = "title_id=$id"
        val chapterUrl = "https://$domain/api/v1/chapter/chapter-listing-by-title-id/"
        val chapterJson = webClient.httpPost(chapterUrl.toHttpUrl(), chapterBody).parseJson()
        val chaptersData = chapterJson.getJSONArray("ALL_CHAPTERS")
        
        val chapters = ArrayList<MangaChapter>()
        
        for (i in 0 until chaptersData.length()) {
            val ch = chaptersData.getJSONObject(i)
            val translations = ch.getJSONArray("translations")
            val number = ch.optDouble("number_float", 0.0).toFloat()
            
            for (j in 0 until translations.length()) {
                val trans = translations.getJSONObject(j)
                val lang = trans.optString("language")
                
                // If a language was selected in the search, ONLY show chapters of that language
                if (langFilter != null && lang != langFilter) continue
                
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
                    branch = group ?: "Unknown", // Group strictly by scanlator as requested
                    source = source
                ))
            }
        }

        return manga.copy(
            description = desc,
            authors = authors,
            tags = tags,
            altTitles = altTitles,
            state = status,
            chapters = chapters.reversed()
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = "https://$domain/chapter-detail/${chapter.url}/"
        val doc = webClient.httpGet(chapterUrl.toHttpUrl()).parseHtml()
        
        val scripts = doc.select("script")
        var imagesJsonStr: String? = null
        val regex = Regex("""const\s+chapterImages\s*=\s*JSON\.parse\(`([^`]+)`"))"""
        
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
