package org.koitharu.kotatsu.parsers.site.all

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.util.*

@MangaSourceParser("MANGABALL", "MangaBall")
internal class MangaBall(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.MANGABALL, 20) {

	override val configKeyDomain = ConfigKey.Domain("mangaball.net")

	private val csrfMutex = Mutex()
	private var cachedCsrfToken: String? = null

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
		SortOrder.NEWEST,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
		isMultipleTagsSupported = true,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.ABANDONED),
		availableLocales = SUPPORTED_LOCALES,
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val token = getCsrfToken()
		val headers = Headers.Builder()
			.add("X-CSRF-TOKEN", token)
			.add("X-Requested-With", "XMLHttpRequest")
			.build()

		val payload = buildString {
			append("filters[page]=$page")
			append("&filters[sort]=${order.toApiSort()}")
			if (!filter.query.isNullOrEmpty()) {
				append("&filters[name]=${filter.query.urlEncoded()}")
			}
			filter.tags.forEach { tag ->
				append("&filters[tags][]=${tag.key}")
			}
			filter.states.forEach { state ->
				append("&filters[status][]=${state.toApiStatus()}")
			}
			filter.locale.forEach { locale ->
				append("&filters[langs][]=${locale.toApiLang()}")
			}
		}

		val response = webClient.httpPost("https://$domain/api/v1/title/search-advanced/".toHttpUrl(), payload, headers)
		val json = response.parseJson()
		val data = json.getJSONArray("data")

		return data.mapJSON { jo ->
			val url = jo.getString("url")
			Manga(
				id = generateUid(url),
				title = jo.getString("name"),
				altTitles = emptySet(),
				url = url,
				publicUrl = url.toAbsoluteUrl(domain),
				rating = jo.optDouble("rating", 0.0).toFloat(),
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				coverUrl = jo.optString("cover_url", ""),
				tags = emptySet(),
				state = jo.optString("status").toMangaState(),
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val titleId = manga.url.substringAfterLast("-")

		val token = getCsrfToken()
		val headers = Headers.Builder()
			.add("X-CSRF-TOKEN", token)
			.add("X-Requested-With", "XMLHttpRequest")
			.build()

		val chaptersPayload = "title_id=$titleId"
		val chaptersResponse =
			webClient.httpPost("https://$domain/api/v1/chapter/chapter-listing-by-title-id/".toHttpUrl(), chaptersPayload, headers)
		val chaptersJson = chaptersResponse.parseJson()

		val chapters = mutableListOf<MangaChapter>()
		val allChapters = chaptersJson.optJSONArray("ALL_CHAPTERS") ?: JSONArray()
		for (i in 0 until allChapters.length()) {
			val ch = allChapters.getJSONObject(i)
			val chapterNumber = ch.optDouble("number", 0.0).toFloat()
			val translations = ch.optJSONArray("translations") ?: JSONArray()
			for (j in 0 until translations.length()) {
				val tr = translations.getJSONObject(j)
				val language = tr.getString("language")
				val id = tr.getString("id")
				val volume = tr.optInt("volume", 0)
				val title = tr.optString("name", "").replace(":", " -")
				val group = tr.optJSONObject("group")?.optString("_id")

				val locale = Locale.forLanguageTag(language)
				val branch = locale.getDisplayName(Locale.US)

				chapters.add(
					MangaChapter(
						id = generateUid("chapter-detail/$id"),
						title = title.ifEmpty { null },
						number = chapterNumber,
						volume = volume,
						url = "chapter-detail/$id",
						scanlator = group,
						uploadDate = 0,
						branch = branch,
						source = source,
					),
				)
			}
		}

		return manga.copy(
			description = doc.selectFirst("div.description-text p")?.text(),
			state = doc.selectFirst("span.badge-status")?.text()?.toMangaState(),
			authors = doc.select("span[data-person-id]").mapToSet { it.text() },
			tags = doc.select("span[data-tag-id]").mapToSet {
				MangaTag(key = it.attr("data-tag-id"), title = it.text(), source = source)
			},
			chapters = chapters.reversed(),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val script = doc.selectFirst("script:containsData(chapterImages)")?.data()
			?: throw ParseException("Chapter images not found", chapter.url)
		val jsonStr = script.substringAfter("parse(`").substringBefore("`)").unescapeJson()
		val json = JSONObject(jsonStr)
		val images = json.getJSONArray("images")

		return List(images.length()) { i ->
			val url = images.getString(i)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun getCsrfToken(): String {
		cachedCsrfToken?.let { return it }
		return csrfMutex.withLock {
			cachedCsrfToken?.let { return@withLock it }
			val doc = webClient.httpGet("https://$domain/".toHttpUrl()).parseHtml()
			val token = doc.selectFirst("meta[name=csrf-token]")?.attr("content")
				?: throw ParseException("CSRF token not found", "https://$domain/")
			cachedCsrfToken = token
			token
		}
	}

	private fun SortOrder.toApiSort(): String = when (this) {
		SortOrder.POPULARITY -> "views_desc"
		SortOrder.RATING -> "rating_desc"
		SortOrder.ALPHABETICAL -> "name_asc"
		SortOrder.NEWEST -> "created_at_desc"
		else -> "updated_at_desc"
	}

	private fun MangaState.toApiStatus(): String = when (this) {
		MangaState.ONGOING -> "ongoing"
		MangaState.FINISHED -> "completed"
		MangaState.ABANDONED -> "cancelled"
		else -> ""
	}

	private fun String?.toMangaState(): MangaState? {
		val s = this?.lowercase() ?: return null
		return when {
			s.contains("ongoing") || s.contains("publishing") -> MangaState.ONGOING
			s.contains("completed") || s.contains("finished") -> MangaState.FINISHED
			s.contains("cancelled") || s.contains("abandoned") -> MangaState.ABANDONED
			else -> null
		}
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		return try {
			val doc = webClient.httpGet("https://$domain/search".toHttpUrl()).parseHtml()
			doc.select("div.tags-container span[data-tag-id]").mapToSet {
				MangaTag(key = it.attr("data-tag-id"), title = it.text(), source = source)
			}
		} catch (e: Exception) {
			emptySet()
		}
	}

	override fun getRequestHeaders(): Headers {
		return super.getRequestHeaders().newBuilder()
			.add("Cookie", "show18PlusContent=true")
			.build()
	}

	private fun Locale.toApiLang(): String = when (language) {
		"id" -> "id"
		"pt" -> if (country == "BR") "pt-br" else "pt-pt"
		"zh" -> if (country == "HK") "zh-hk" else "zh"
		"es" -> when (country) {
			"LA" -> "es-la"
			"419" -> "es-419"
			else -> "es"
		}

		else -> language
	}

	companion object {
		private val SUPPORTED_LOCALES = listOf(
			"sq", "ar", "bn", "bg", "ca", "zh", "zh-hk", "cs", "da", "nl", "en", "fi", "fr", "de", "el", "he", "hi", "hu", "is", "id", "it", "jp", "kn", "kr", "ml", "ms", "ne", "no", "fa", "pl", "pt-br", "pt-pt", "ro", "ru", "sr", "sk", "sl", "es", "es-la", "es-419", "sv", "ta", "th", "tr", "uk", "vi",
		).mapToSet { Locale.forLanguageTag(it) }
	}
}
