package org.koitharu.kotatsu.parsers.site.mangabox.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangabox.MangaboxParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@Broken("Connection refused")
@MangaSourceParser("MANGAKAKALOTTV", "Mangakakalot.tv", "en")
internal class MangakakalotTv(context: MangaLoaderContext) :
	MangaboxParser(context, MangaParserSource.MANGAKAKALOTTV) {

	override val configKeyDomain = ConfigKey.Domain("ww8.mangakakalot.tv")
	override val searchUrl = "/search/"
	override val listUrl = "/manga_list"
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
		is String -> urlEncoded()
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
			else -> ""
		}

		else -> this.toString().urlEncoded()
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = if (!filter.query.isNullOrEmpty()) {
			"https://${domain}${searchUrl}${filter.query.toQueryParam()}/?page=$page"
		} else {
			buildString {
				append("https://$domain/?")

				if (filter.tags.isNotEmpty()) {
					append("&category=${filter.tags.first().toQueryParam()}")
				}

				if (filter.states.isNotEmpty()) {
					append("&state=${filter.states.first().toQueryParam()}")
				}

				append("&page=$page")
				append("&type=${order.toQueryParam()}")
			}
		}

		val doc = webClient.httpGet(url).parseHtml()

		return doc.select("div.list-comic-item-wrap").ifEmpty {
			doc.select("div.story_item")
		}.map { div ->
			val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(div.host ?: domain),
				coverUrl = div.selectFirst("img")?.src(),
				title = div.selectFirstOrThrow("h3").text(),
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = null,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val chaptersDeferred = async { getChapters(doc) }
		val desc = doc.selectFirstOrThrow(selectDesc).html()
		val stateDiv = doc.select(selectState).text().replace("Status : ", "")
		val state = stateDiv.let {
			when (it.lowercase()) {
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
					key = a.attr("href").substringAfterLast("category=").substringBefore("&"),
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

	override val selectTagMap = "ul.tag li a"

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/$listUrl").parseHtml()
		return doc.select(selectTagMap).mapToSet { a ->
			MangaTag(
				key = a.attr("href").substringAfterLast("category=").substringBefore("&"),
				title = a.attr("title"),
				source = source,
			)
		}
	}
}
