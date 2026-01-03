package org.koitharu.kotatsu.parsers.site.mangabox.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangabox.MangaboxParser
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl

@MangaSourceParser("MANGANATO", "Manganato", "en")
internal class Manganato(context: MangaLoaderContext) :
	MangaboxParser(context, MangaParserSource.MANGANATO) {
	override val configKeyDomain = ConfigKey.Domain(
		"www.natomanga.com",
		"www.nelomanga.com",
		"www.manganato.gg",
	)
	override val otherDomain = "www.nelomanga.com"

	override val authorUrl = "/author/story"
	override val selectPage = ".container-chapter-reader > img"
	override val listUrl = "/genre/all"

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = if (!filter.query.isNullOrEmpty()) {
			val normalized = normalizeSearchQuery(filter.query)
			"https://$domain/search/story/$normalized?page=$page"
		} else if (!filter.author.isNullOrEmpty()) {
			val normalized = normalizeSearchQuery(filter.author)
			"https://$domain$authorUrl/$normalized?page=$page"
		} else {
			val genre = filter.tags.firstOrNull()?.key ?: "all"

			val state = filter.states.firstOrNull()?.let {
				when (it) {
					MangaState.ONGOING -> "ongoing"
					MangaState.FINISHED -> "completed"
					else -> "all"
				}
			} ?: "all"

			val type = when (order) {
				SortOrder.NEWEST -> "newest"
				SortOrder.POPULARITY -> "topview"
				SortOrder.ALPHABETICAL -> "az"
				else -> "latest"
			}

			"https://$domain/genre/$genre?page=$page&type=$type&state=$state"
		}

		val doc = webClient.httpGet(url).parseHtml()
		return parseMangaList(doc)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		val scriptContent = doc.select("script:containsData(cdns =)").joinToString("\n") { it.data() }
		if (scriptContent.isNotEmpty()) {
			val cdns = extractArray(scriptContent, "cdns")
			val chapterImages = extractArray(scriptContent, "chapterImages")

			if (cdns.isNotEmpty()) {
				cdnSet.addAll(cdns)
			}

			if (cdns.isNotEmpty() && chapterImages.isNotEmpty()) {
				val cdn = cdns.first()
				return chapterImages.map { imagePath ->
					val url = (cdn + "/" + imagePath).replace(Regex("(?<!:)/{2,}"), "/")
					MangaPage(
						id = generateUid(url),
						url = url,
						preview = null,
						source = source,
					)
				}
			}
		}

		return super.getPages(chapter)
	}
}
