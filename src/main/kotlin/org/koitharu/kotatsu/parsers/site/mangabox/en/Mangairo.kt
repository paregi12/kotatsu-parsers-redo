package org.koitharu.kotatsu.parsers.site.mangabox.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangabox.MangaboxParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("MANGAIRO", "MangaIro", "en")
internal class Mangairo(context: MangaLoaderContext) :
	MangaboxParser(context, MangaParserSource.MANGAIRO) {
	override val configKeyDomain = ConfigKey.Domain("w.mangairo.com", "chap.mangairo.com")
	override val otherDomain = "chap.mangairo.com"
	override val datePattern = "MMM-dd-yy"
	override val listUrl = "/manga-list"
	override val searchUrl = "/list/search/"
	override val selectDesc = "div#story_discription p"
	override val selectState = "ul.story_info_right li:contains(Status) a"
	override val selectAlt = "ul.story_info_right li:contains(Alter) h2"
	override val selectAut = "ul.story_info_right li:contains(Author) a"
	override val selectTag = "ul.story_info_right li:contains(Genres) a"
	override val selectChapter = "div.chapter_list li"
	override val selectDate = "p"
	override val selectPage = "div.panel-read-story img"
	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	private fun Any?.toQueryParam(): String = when (this) {
		is String -> replace(" ", "_").urlEncoded()
		is MangaTag -> key
		is MangaState -> when (this) {
			MangaState.ONGOING -> "ongoing"
			MangaState.FINISHED -> "completed"
			else -> "all"
		}

		is SortOrder -> when (this) {
			SortOrder.POPULARITY -> "topview"
			SortOrder.UPDATED -> "latest"
			SortOrder.NEWEST -> "newest"
			else -> "latest"
		}

		else -> this.toString().urlEncoded()
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = if (!filter.query.isNullOrEmpty()) {
			"https://${domain}${searchUrl}${filter.query.toQueryParam()}/?page=$page"
		} else {
			val category = filter.tags.firstOrNull()?.toQueryParam() ?: "all"
			val state = filter.states.firstOrNull()?.toQueryParam() ?: "all"

			buildString {
				append("https://${domain}${listUrl}")
				append("/type-${order.toQueryParam()}")
				append("/ctg-$category")
				append("/state-$state")
				append("/page-$page")
			}
		}

		val doc = webClient.httpGet(url).parseHtml()

		return doc.select("div.story-item").map { div ->
			val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(div.host ?: domain),
				coverUrl = div.selectFirst("img")?.src(),
				title = (div.selectFirst("h2")?.text() ?: div.selectFirst("h3")?.text()).orEmpty(),
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = if (source.contentType == ContentType.HENTAI) ContentRating.ADULT else ContentRating.SAFE,
			)
		}
	}

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/$listUrl/type-latest/ctg-all/state-all/page-1").parseHtml()
		return doc.select("div.panel_category a:not(.ctg_select)").mapToSet { a ->
			val key = a.attr("href").substringAfterLast("ctg-").substringBefore("/")
			val name = a.attr("title").replace("Category ", "")
			MangaTag(
				key = key,
				title = name,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val chaptersDeferred = async { getChapters(doc) }
		val desc = doc.selectFirst(selectDesc)?.html()?.nullIfEmpty()
		val stateDiv = doc.select(selectState).text()
		val state = stateDiv.let {
			when (it) {
				in ongoing -> MangaState.ONGOING
				in finished -> MangaState.FINISHED
				else -> null
			}
		}

		val alt = doc.body().select(selectAlt).text().replace("Alternative : ", "").nullIfEmpty()
		val author = doc.body().select(selectAut).eachText().joinToString().nullIfEmpty()
		manga.copy(
			tags = doc.body().select(selectTag).mapToSet { a ->
				MangaTag(
					key = a.attr("href")
						.substringAfterLast("page-"), // Yes the site, it's crashing between page is tag id
					title = a.text().toTitleCase(),
					source = source,
				)
			},
			description = desc,
			altTitles = setOfNotNull(alt),
			authors = setOfNotNull(author),
			state = state,
			chapters = chaptersDeferred.await(),
		)
	}
}
