package org.koitharu.kotatsu.parsers.site.en

import androidx.collection.ArrayMap
import okhttp3.HttpUrl
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANHWA18COM", "Manhwa18.com", "en", type = ContentType.HENTAI)
internal class Manhwa18Com(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANHWA18COM, pageSize = 18, searchPageSize = 18) {

	override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("manhwa18.com")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder>
		get() = EnumSet.of(
			SortOrder.UPDATED,
			SortOrder.POPULARITY,
			SortOrder.ALPHABETICAL,
			SortOrder.NEWEST,
			SortOrder.RATING,
		)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = tagsMap.get().values.toSet(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
		),
	)

	override suspend fun getFavicons(): Favicons {
		return Favicons(
			listOf(
				Favicon("https://$domain/favicon1.ico", 32, null),
			),
			domain,
		)
	}

	private val dateFormat by lazy {
		SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/tim-kiem?page=")
			append(page.toString())

			filter.query?.let {
				append("&q=")
				append(filter.query.urlEncoded())
			}

			if (filter.tags.isNotEmpty()) {
				append("&accept_genres=")
				append(filter.tags.joinToString(",") { it.key })
			}

			if (filter.tagsExclude.isNotEmpty()) {
				append("&reject_genres=")
				append(filter.tagsExclude.joinToString(",") { it.key })
			}

			append("&sort=")
			append(
				when (order) {
					SortOrder.ALPHABETICAL -> "az"
					SortOrder.ALPHABETICAL_DESC -> "za"
					SortOrder.POPULARITY -> "top"
					SortOrder.UPDATED -> "update"
					SortOrder.NEWEST -> "new"
					SortOrder.RATING -> "like"
					else -> "update"
				},
			)

			filter.states.oneOrThrowIfMany()?.let {
				append("&status=")
				append(
					when (it) {
						MangaState.ONGOING -> "1"
						MangaState.FINISHED -> "3"
						MangaState.PAUSED -> "2"
						else -> ""
					},
				)
			}
		}

		val docs = webClient.httpGet(url).parseHtml()

		return docs.select(".thumb-wrapper")
			.map {
				val titleElement = it.nextElementSibling()?.selectFirst("a") ?: it.selectFirstOrThrow("a")
				val absUrl = titleElement.attrAsAbsoluteUrl("href")
				Manga(
					id = generateUid(absUrl.toRelativeUrl(domain)),
					title = titleElement.text(),
					altTitles = emptySet(),
					url = absUrl.toRelativeUrl(domain),
					publicUrl = absUrl,
					rating = RATING_UNKNOWN,
					contentRating = ContentRating.ADULT,
					coverUrl = it.selectFirst(".img-in-ratio")?.attrAsAbsoluteUrl("data-bg"),
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					largeCoverUrl = null,
					description = null,
					source = source,
				)
			}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val cardInfoElement = docs.selectFirst("div.series-information")
		val author = cardInfoElement?.selectFirst(".info-name:contains(Author)")?.parent()
			?.select("a")
			?.joinToString(", ") { it.text() }
			?.nullIfEmpty()
		val availableTags = tagsMap.get()
		val tags = cardInfoElement?.selectFirst(".info-name:contains(Genre)")?.parent()
			?.select("a")
			?.mapNotNullToSet { availableTags[it.text().lowercase(Locale.ENGLISH)] }
		val state = cardInfoElement?.selectFirst(".info-name:contains(Status)")?.parent()
			?.selectFirst("a")
			?.let {
				when (it.text().lowercase()) {
					"on going" -> MangaState.ONGOING
					"completed" -> MangaState.FINISHED
					"on hold" -> MangaState.PAUSED
					else -> null
				}
			}

		return manga.copy(
			altTitles = setOfNotNull(
				cardInfoElement?.selectFirst("b:contains(Other names)")?.parent()?.ownTextOrNull()
					?.removePrefix(": "),
			),
			authors = setOfNotNull(author),
			description = docs.selectFirst(".series-summary .summary-content")?.html(),
			tags = tags.orEmpty(),
			state = state,
			chapters = docs.select(".list-chapters > a").mapChapters(reversed = true) { index, element ->
				val chapterUrl = element.attrAsAbsoluteUrlOrNull("href")?.toRelativeUrl(domain)
					?: return@mapChapters null
				val uploadDate = parseUploadDate(element.selectFirst(".chapter-time")?.text())
				MangaChapter(
					id = generateUid(chapterUrl),
					title = element.selectFirst(".chapter-name")?.textOrNull(),
					number = index + 1f,
					volume = 0,
					url = chapterUrl,
					scanlator = null,
					uploadDate = uploadDate,
					branch = null,
					source = source,
				)
			},
		)
	}

	private fun parseUploadDate(timeStr: String?): Long {
		timeStr ?: return 0
		val dateStr = timeStr.substringAfter(" - ").trim()
		return dateFormat.parseSafe(dateStr)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(chapterUrl).parseHtml()
		return doc.requireElementById("chapter-content").select("img").mapNotNull {
			val url = it.attrAsRelativeUrlOrNull("data-src")
				?: it.attrAsRelativeUrlOrNull("src")
				?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val path = link.encodedPath
		if (!path.startsWith("/manga/")) return null
		val slug = path.removePrefix("/manga/").substringBefore("/")
		if (slug.isEmpty()) return null
		val mangaUrl = "/manga/$slug"
		return resolver.resolveManga(this, mangaUrl)
	}

	private val tagsMap = suspendLazy(initializer = ::parseTags)

	private suspend fun parseTags(): Map<String, MangaTag> {
		val doc = webClient.httpGet("https://$domain/tim-kiem?q=").parseHtml()
		val list = doc.getElementsByAttribute("data-genre-id")
		if (list.isEmpty()) {
			return emptyMap()
		}
		val result = ArrayMap<String, MangaTag>(list.size)
		for (item in list) {
			val id = item.attr("data-genre-id")
			val name = item.text()
			result[name.lowercase(Locale.ENGLISH)] = MangaTag(
				title = name.toTitleCase(Locale.ENGLISH),
				key = id,
				source = source,
			)
		}
		return result
	}
}
