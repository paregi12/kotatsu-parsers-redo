package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("READCOMICONLINE", "ReadComicOnline", "en", ContentType.COMICS)
internal class ReadComicOnline(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.READCOMICONLINE, 36) {

	override val configKeyDomain = ConfigKey.Domain("readcomiconline.li")

	override val availableSortOrders: Set<SortOrder> =
		EnumSet.of(SortOrder.ALPHABETICAL)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	private val defaultGenreTags by lazy {
		setOf(
			MangaTag("1", "Action", source),
			MangaTag("2", "Adventure", source),
			MangaTag("38", "Anthology", source),
			MangaTag("46", "Anthropomorphic", source),
			MangaTag("41", "Biography", source),
			MangaTag("49", "Children", source),
			MangaTag("3", "Comedy", source),
			MangaTag("17", "Crime", source),
			MangaTag("19", "Drama", source),
			MangaTag("25", "Family", source),
			MangaTag("20", "Fantasy", source),
			MangaTag("31", "Fighting", source),
			MangaTag("5", "Graphic Novels", source),
			MangaTag("28", "Historical", source),
			MangaTag("15", "Horror", source),
			MangaTag("35", "Leading Ladies", source),
			MangaTag("51", "LGBTQ", source),
			MangaTag("44", "Literature", source),
			MangaTag("40", "Manga", source),
			MangaTag("4", "Martial Arts", source),
			MangaTag("8", "Mature", source),
			MangaTag("33", "Military", source),
			MangaTag("56", "Mini-Series", source),
			MangaTag("47", "Movies & TV", source),
			MangaTag("55", "Music", source),
			MangaTag("23", "Mystery", source),
			MangaTag("21", "Mythology", source),
			MangaTag("48", "Personal", source),
			MangaTag("42", "Political", source),
			MangaTag("43", "Post-Apocalyptic", source),
			MangaTag("27", "Psychological", source),
			MangaTag("39", "Pulp", source),
			MangaTag("53", "Religious", source),
			MangaTag("9", "Robots", source),
			MangaTag("32", "Romance", source),
			MangaTag("52", "School Life", source),
			MangaTag("16", "Sci-Fi", source),
			MangaTag("50", "Slice of Life", source),
			MangaTag("54", "Sport", source),
			MangaTag("30", "Spy", source),
			MangaTag("22", "Superhero", source),
			MangaTag("24", "Supernatural", source),
			MangaTag("29", "Suspense", source),
			MangaTag("57", "Teen", source),
			MangaTag("18", "Thriller", source),
			MangaTag("34", "Vampires", source),
			MangaTag("37", "Video Games", source),
			MangaTag("26", "War", source),
			MangaTag("45", "Western", source),
			MangaTag("36", "Zombies", source),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			when {
				!filter.query.isNullOrEmpty() -> {
					if (filter.tags.isNotEmpty() || filter.tagsExclude.isNotEmpty()) {
						// Advanced search with filters
						append("/AdvanceSearch?comicName=")
						append(filter.query.urlEncoded())
						append("&ig=")
						append(filter.tags.joinToString(",") { it.key })
						if (filter.tags.isNotEmpty()) append(",")
						append("&eg=")
						append(filter.tagsExclude.joinToString(",") { it.key })
						if (filter.tagsExclude.isNotEmpty()) append(",")
						append("&status=")
						append("&pubDate=")
					} else {
						// Simple search - POST request
						return getSearchResults(filter.query)
					}
				}

				filter.tags.isNotEmpty() || filter.tagsExclude.isNotEmpty() -> {
					// Advanced search with genres only
					append("/AdvanceSearch?comicName=")
					append("&ig=")
					append(filter.tags.joinToString(",") { it.key })
					if (filter.tags.isNotEmpty()) append(",")
					append("&eg=")
					append(filter.tagsExclude.joinToString(",") { it.key })
					if (filter.tagsExclude.isNotEmpty()) append(",")
					append("&status=")
					append("&pubDate=")
				}

				else -> {
					// Default listing
					append("/ComicList")
					if (page > 1) {
						append("?page=")
						append(page)
					}
				}
			}
		}

		val doc = webClient.httpGet(url).parseHtml()

		// Check if this is search results page or comic list page
		val searchResults = doc.select("div.list-comic div.item")
		if (searchResults.isNotEmpty()) {
			return parseSearchResultsPage(doc)
		}

		return doc.select("div.item-list div.section.group.list").map { div ->
			val a = div.selectFirstOrThrow("div.col.info p a")
			val href = a.attrAsRelativeUrl("href")
			val coverImg = div.selectFirst("div.col.cover img")
			Manga(
				id = generateUid(href),
				title = a.text(),
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = coverImg?.attrAsAbsoluteUrl("src"),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	private suspend fun getSearchResults(query: String): List<Manga> {
		val formData = mapOf("keyword" to query)
		val headers = Headers.Builder()
			.add("Content-Type", "application/x-www-form-urlencoded")
			.build()

		val url = "https://$domain/Search/Comic".toHttpUrl()
		val doc = webClient.httpPost(url, formData, headers).parseHtml()

		return parseSearchResultsPage(doc)
	}

	private fun parseSearchResultsPage(doc: Document): List<Manga> {
		return doc.select("div.list-comic div.item").map { div ->
			val a = div.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			val coverImg = div.selectFirst("img")
			val title = div.selectFirst("span.title")?.text() ?: a.attr("title")
				.substringBefore("<p class=\"title\">")
				.substringAfter("<p class=\"title\">")
				.substringBefore("</p>")
				.trim()
				.ifEmpty { coverImg?.attr("alt") ?: "" }

			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = coverImg?.attrAsAbsoluteUrl("src"),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		return try {
			val doc = webClient.httpGet("https://$domain/AdvanceSearch").parseHtml()
			doc.select("ul#genres li").mapToSet { li ->
				val select = li.selectFirst("select")
				val gid = select?.attr("gid") ?: return@mapToSet null
				val title = li.selectFirst("label a")?.text()?.trim() ?: return@mapToSet null
				MangaTag(
					key = gid,
					title = title,
					source = source,
				)
			}.filterNotNull().toSet()
		} catch (_: Exception) {
			defaultGenreTags
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val infoDiv = doc.selectFirst("div.barContent")
		val altTitle = infoDiv?.selectFirst("p:has(span.info:contains(Other name)) a")?.text()
		val genres = infoDiv?.select("p:has(span.info:contains(Genres)) a")?.mapToSet { a ->
			MangaTag(
				key = a.attr("href").substringAfterLast('/'),
				title = a.text(),
				source = source,
			)
		} ?: emptySet()

		val publisher = infoDiv?.selectFirst("p:has(span.info:contains(Publisher)) a")?.text()
		val writer = infoDiv?.selectFirst("p:has(span.info:contains(Writer)) a")?.text()
		val artist = infoDiv?.selectFirst("p:has(span.info:contains(Artist)) a")?.text()
		val description = doc.selectFirst("div.barContent div.section.group div")?.text()

		val statusText = infoDiv?.selectFirst("p:has(span.info:contains(Status)) a")?.text()
		val state = when {
			statusText?.contains("Ongoing", ignoreCase = true) == true -> MangaState.ONGOING
			statusText?.contains("Completed", ignoreCase = true) == true -> MangaState.FINISHED
			else -> null
		}

		val authors = mutableSetOf<String>()
		if (!writer.isNullOrBlank() && writer != "N/a" && writer != "Various") {
			authors.add(writer)
		}
		if (!artist.isNullOrBlank() && artist != "N/a" && artist != "Various" && artist != writer) {
			authors.add(artist)
		}

		val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.US)
		val chapters = doc.select("table.listing tr:has(a)").mapIndexed { index, tr ->
			val a = tr.selectFirstOrThrow("td a")
			val url = a.attrAsRelativeUrl("href")
			val name = a.text().trim()
			val dateText = tr.select("td").lastOrNull()?.text()?.trim()
			val date = try {
				dateText?.let { dateFormat.parse(it)?.time } ?: 0L
			} catch (_: Exception) {
				0L
			}

			MangaChapter(
				id = generateUid(url),
				title = name,
				number = (doc.select("table.listing tr:has(a)").size - index).toFloat(),
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = date,
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			altTitles = setOfNotNull(altTitle),
			tags = genres,
			authors = authors,
			description = description,
			state = state,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain) + "&quality=hq&readType=1"
		val doc = captureDocumentWithJs(fullUrl)

		return doc.select("div#divImage p img").mapNotNull { img ->
			val src = img.attr("src")
			// Skip blank placeholder images
			if (src.contains("blank.gif") || src.startsWith("data:") || src.isBlank()) {
				return@mapNotNull null
			}
			MangaPage(
				id = generateUid(src),
				url = src,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun captureDocumentWithJs(url: String): Document {
		val script = """
			(() => {
				const divImage = document.querySelector('div#divImage');
				if (!divImage) {
					return null;
				}

				// Check if images are loaded (not blank.gif)
				const images = divImage.querySelectorAll('p img');
				if (images.length === 0) {
					return null;
				}

				// Wait for at least some real images to load
				let loadedCount = 0;
				for (const img of images) {
					const src = img.getAttribute('src') || '';
					if (src && !src.includes('blank.gif') && !src.startsWith('data:')) {
						loadedCount++;
					}
				}

				// Return if we have enough loaded images (at least 3 or all of them)
				if (loadedCount >= Math.min(3, images.length)) {
					window.stop();
					return document.documentElement.outerHTML;
				}

				return null;
			})();
		""".trimIndent()

		val rawHtml = context.evaluateJs(url, script, 30000L)
			?: throw ParseException("Failed to load chapter images", url)

		val html = if (rawHtml.startsWith("\"") && rawHtml.endsWith("\"")) {
			rawHtml.substring(1, rawHtml.length - 1)
				.replace("\\\"", "\"")
				.replace("\\n", "\n")
				.replace("\\r", "\r")
				.replace("\\t", "\t")
				.replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { match ->
					val hexValue = match.groupValues[1]
					hexValue.toInt(16).toChar().toString()
				}
		} else rawHtml

		return Jsoup.parse(html, url)
	}
}
