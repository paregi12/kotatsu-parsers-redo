package org.koitharu.kotatsu.parsers.site.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.jsoup.nodes.Document
import java.util.*

@MangaSourceParser("COMICKFAN", "ComicK Fanmade", "en")
internal class ComicKFan(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.COMICKFAN, 30) {

	override val configKeyDomain = ConfigKey.Domain("comickfan.com")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("/advanced-search?page=$page")
			if (!filter.query.isNullOrEmpty()) {
				append("&q=")
				append(filter.query.urlEncoded())
			}
			if (filter.tags.isNotEmpty()) {
				append("&genre=")
				append(filter.tags.first().key)
			}
			filter.states.firstOrNull()?.let {
				append("&status=")
				append(when (it) {
					MangaState.ONGOING -> "Ongoing"
					MangaState.FINISHED -> "Completed"
					else -> ""
				})
			}
			filter.types.firstOrNull()?.let {
				append("&type=")
				append(when (it) {
					ContentType.MANGA -> "Manga"
					ContentType.MANHWA -> "Manhwa"
					ContentType.MANHUA -> "Manhua"
					else -> ""
				})
			}
			append("&orderby=")
			append(when (order) {
				SortOrder.UPDATED -> "update"
				SortOrder.POPULARITY -> "trending"
				SortOrder.RATING -> "rating"
				SortOrder.ALPHABETICAL -> "latest"
				else -> ""
			})
		}

		val doc = webClient.httpGet(url.toAbsoluteUrl(domain)).parseHtml()
		return parseMangaList(doc)
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("a[href^=\"/manga/\"]").filter { it.selectFirst("img") != null }.mapNotNull { element ->
			val href = element.attrAsRelativeUrl("href") ?: return@mapNotNull null
			val img = element.selectFirst("img") ?: return@mapNotNull null
			val coverUrl = img.src() ?: ""
			val title = element.select("div").lastOrNull()?.text()?.takeIf { it.isNotEmpty() } 
				?: element.select("span").lastOrNull()?.text()
				?: element.attr("description") 
				?: return@mapNotNull null
			
			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				coverUrl = coverUrl,
				tags = emptySet(),
				state = if (element.text().contains("Ongoing", true)) MangaState.ONGOING else if (element.text().contains("Completed", true)) MangaState.FINISHED else null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val description = doc.selectFirst("div.flex.flex-col.gap-2 > span")?.text()
			?: doc.select("p").firstOrNull { it.text().length > 50 }?.text()
		
		val statusText = doc.select("div").firstOrNull { it.text().startsWith("Status") }?.nextElementSibling()?.text()
		val state = when {
			statusText?.contains("Ongoing", true) == true -> MangaState.ONGOING
			statusText?.contains("Completed", true) == true -> MangaState.FINISHED
			else -> null
		}

		val tags = doc.select("button.text-white").mapNotNullToSet { element ->
			val tagTitle = element.text()
			if (tagTitle.isEmpty() || tagTitle == "See more") return@mapNotNullToSet null
			MangaTag(
				key = tagTitle.lowercase().replace(" ", "-"),
				title = tagTitle,
				source = source,
			)
		}

		val chapters = doc.select("a[href*=\"/chapter-\"]").mapNotNull { element ->
			val href = element.attrAsRelativeUrl("href") ?: return@mapNotNull null
			val title = element.selectFirst("span")?.text() ?: element.text().substringBefore("RESETSCAN").trim()
			val chapterNumber = title.substringAfter("Chapter ").substringBefore(" ").toFloatOrNull() ?: 0f

			MangaChapter(
				id = generateUid(href),
				title = title,
				url = href,
				number = chapterNumber,
				volume = 0,
				scanlator = element.select("span").getOrNull(1)?.text(),
				uploadDate = 0,
				branch = null,
				source = source,
			)
		}.reversed()

		return manga.copy(
			description = description,
			state = state,
			tags = tags,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("img[description*=\"page\"]").map { img ->
			val url = img.src() ?: ""
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun fetchTags(): Set<MangaTag> {
		return setOf(
			"Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Mystery", "Psychological", "Romance", "Sci-fi", "Slice of Life", "Supernatural", "Thriller"
		).mapToSet { 
			MangaTag(key = it.lowercase().replace(" ", "-"), title = it, source = source)
		}
	}
}
