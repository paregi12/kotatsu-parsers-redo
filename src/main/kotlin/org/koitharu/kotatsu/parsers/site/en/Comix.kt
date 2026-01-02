package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import java.util.*
import java.math.BigDecimal
import java.math.RoundingMode

@MangaSourceParser("COMIX", "Comix", "en", ContentType.MANGA)
internal class Comix(context: MangaLoaderContext) :
	pagedMangaParser(context, MangaParserSource.COMIX, 28) {

	override val configKeyDomain = ConfigKey.Domain("comix.to")
	private val apiBase = "api/v2"
	private val apiBaseUrl get() = "https://$domain/$apiBase"

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.add("Origin", "https://$domain")
		.build()

	private val nsfwGenreIds = listOf("87264", "8", "87265", "13", "87266", "87268")

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override val availableSortOrders: Set<SortOrder> = linkedSetOf(
		SortOrder.RELEVANCE,
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTagsMap.map { MangaTag(it.key, it.value, source) }.toSet()
	)

	// -------------------------
	// List / Search
	// -------------------------
	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val builder = "$apiBaseUrl/manga".toHttpUrl().newBuilder().apply {
			if (!filter.query.isNullOrBlank()) {
				addQueryParameter("keyword", filter.query)
			}

			val (param, dir) = when (order) {
				SortOrder.RELEVANCE -> "relevance" to "desc"
				SortOrder.UPDATED -> "chapter_updated_at" to "desc"
				SortOrder.POPULARITY -> "views_30d" to "desc"
				SortOrder.NEWEST -> "created_at" to "desc"
				SortOrder.ALPHABETICAL -> "title" to "asc"
				else -> "chapter_updated_at" to "desc"
			}
			addQueryParameter("order[$param]", dir)

			if (filter.tags.isNotEmpty()) {
				filter.tags.forEach { addQueryParameter("genres[]", it.key) }
			}
			if (filter.tagsExclude.isNotEmpty()) {
				filter.tagsExclude.forEach { addQueryParameter("genres[]", "-${it.key}") }
			}

			if (filter.tags.isEmpty() && filter.tagsExclude.isEmpty()) {
				nsfwGenreIds.forEach { addQueryParameter("genres[]", "-$it") }
			}

			addQueryParameter("limit", pageSize.toString())
			addQueryParameter("page", page.toString())
		}

		val response = webClient.httpGet(builder.build()).parseJson()
		val items = response.optJSONObject("result")?.optJSONArray("items") ?: return emptyList()
		val list = ArrayList<Manga>(items.length())

		for (i in 0 until items.length()) {
			val it = items.optJSONObject(i) ?: continue
			list.add(parseMangaFromJson(it))
		}
		return list
	}

	private fun parseMangaFromJson(json: JSONObject): Manga {
		val hashId = json.optString("hash_id", "").nullIfEmpty()
		val slug = json.optString("slug", "").nullIfEmpty()
		val title = json.optString("title", "Unknown")
		val description = json.optString("synopsis", "").nullIfEmpty()

		val poster = json.optJSONObject("poster")
		val coverUrl = poster?.optString("medium", "")?.nullIfEmpty()
			?: poster?.optString("large", "")?.nullIfEmpty()
			?: poster?.optString("small", "")?.nullIfEmpty()
			?: ""

		val state = when (json.optString("status", "").lowercase()) {
			"finished" -> MangaState.FINISHED
			"releasing" -> MangaState.ONGOING
			"on_hiatus" -> MangaState.PAUSED
			"discontinued" -> MangaState.ABANDONED
			else -> null
		}

		val ratedAvg = json.optDouble("rated_avg", 0.0)
		val rating = if (ratedAvg > 0.0) (ratedAvg / 20.0).toFloat() else RATING_UNKNOWN

		val resolvedHash = hashId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
		val urlSlug = if (slug != null) "$resolvedHash-$slug" else resolvedHash

		val tags = json.optJSONArray("term_ids")?.let { arr ->
			(0 until arr.length()).mapNotNull { i ->
				val id = arr.optInt(i).toString()
				availableTagsMap[id]?.let { name ->
					MangaTag(key = id, title = name, source = source)
				}
			}.toSet()
		} ?: emptySet()

		return Manga(
			id = generateUid(resolvedHash),
			url = "/title/$urlSlug",
			publicUrl = "https://$domain/title/$urlSlug",
			coverUrl = coverUrl,
			title = title,
			altTitles = emptySet(),
			description = description,
			rating = rating,
			tags = tags,
			authors = emptySet(),
			state = state,
			source = source,
			contentRating = if (json.optBoolean("is_nsfw", false)) ContentRating.ADULT else ContentRating.SAFE
		)
	}

	// -------------------------
	// Details
	// -------------------------
	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val hash = manga.url.substringAfter("/title/").substringBefore("-").nullIfEmpty()
			?: throw ParseException("Invalid manga URL", manga.url)

		val detailsUrl = "$apiBaseUrl/manga/$hash".toHttpUrl().newBuilder()
			.addQueryParameter("includes[]", "author")
			.addQueryParameter("includes[]", "artist")
			.build()
		val detailsDeferred = async { webClient.httpGet(detailsUrl).parseJson() }
		val chaptersDeferred = async { getChapters(hash) }

		val response = try {
			detailsDeferred.await()
		} catch (_: Exception) {
			JSONObject()
		}
		val chapters = try {
			chaptersDeferred.await()
		} catch (_: Exception) {
			emptyList()
		}

		val result = response.optJSONObject("result")
		if (result != null) {
			val updated = parseMangaFromJson(result)

			val authors = LinkedHashSet<String>()
			result.optJSONArray("author")?.let { arr ->
				for (i in 0 until arr.length()) {
					arr.optJSONObject(i)?.optString("title")?.nullIfEmpty()?.let { authors.add(it) }
				}
			}
			result.optJSONArray("artist")?.let { arr ->
				for (i in 0 until arr.length()) {
					arr.optJSONObject(i)?.optString("title")?.nullIfEmpty()?.let { authors.add(it) }
				}
			}

			val ratedAvg = result.optDouble("rated_avg", 0.0)
			val fancyScore = generateFancyScore(ratedAvg)
			val synopsis = result.optString("synopsis", "")
			val altTitles = result.optJSONArray("alt_titles")?.let { arr ->
				(0 until arr.length()).map { arr.getString(it) }
			} ?: emptyList()

			val newDesc = buildString {
				if (fancyScore.isNotEmpty()) {
					append(fancyScore).append("\n\n")
				}
				append(synopsis)
				if (altTitles.isNotEmpty()) {
					append("\n\nAlternative Names:\n")
					append(altTitles.joinToString("\n"))
				}
			}

			return@coroutineScope updated.copy(
				chapters = chapters,
				authors = authors,
				description = newDesc,
				altTitles = altTitles.toSet()
			)
		}

		return@coroutineScope manga.copy(chapters = chapters)
	}

	private fun generateFancyScore(ratedAvg: Double): String {
		if (ratedAvg == 0.0) return ""
		val score = BigDecimal(ratedAvg).setScale(1, RoundingMode.HALF_UP)
		val stars = score.divide(BigDecimal(20), 0, RoundingMode.HALF_UP).toInt()

		return buildString {
			append("★".repeat(stars))
			if (stars < 5) append("☆".repeat(5 - stars))
			append(" ").append(score.toPlainString())
		}
	}

	// -------------------------
	// Chapters
	// -------------------------
	private suspend fun getChapters(hashId: String): List<MangaChapter> = coroutineScope {
		val firstPageUrl = "$apiBaseUrl/manga/$hashId/chapters".toHttpUrl().newBuilder().apply {
			addQueryParameter("order[number]", "asc")
			addQueryParameter("limit", "100")
			addQueryParameter("page", "1")
		}.build()

		val firstResp = try {
			webClient.httpGet(firstPageUrl).parseJson()
		} catch (_: Exception) {
			return@coroutineScope emptyList()
		}

		val allItems = ArrayList<JSONObject>()
		val firstItems = firstResp.optJSONObject("result")?.optJSONArray("items") ?: JSONArray()
		for (i in 0 until firstItems.length()) {
			val item = firstItems.optJSONObject(i) ?: continue
			allItems.add(item)
		}

		val pagination = firstResp.optJSONObject("result")?.optJSONObject("pagination")
		val lastPage = pagination?.optInt("last_page", 1) ?: 1

		if (lastPage > 1) {
			val maxPage = minOf(lastPage, 200)
			val deferreds = (2..maxPage).map {
				async {
					val url = "$apiBaseUrl/manga/$hashId/chapters".toHttpUrl().newBuilder().apply {
						addQueryParameter("order[number]", "asc")
						addQueryParameter("limit", "100")
						addQueryParameter("page", page.toString())
					}.build()

					try {
						webClient.httpGet(url).parseJson()
					} catch (_: Exception) {
						null
					}
				}
			}
			deferreds.awaitAll().forEach {
				val items = resp?.optJSONObject("result")?.optJSONArray("items") ?: return@forEach
				for (i in 0 until items.length()) {
					val item = items.optJSONObject(i) ?: continue
					allItems.add(item)
				}
			}
		}

		allItems.mapNotNull {
			val num = item.optDouble("number", Double.NaN)
			if (num.isNaN()) return@mapNotNull null

			val chapterId = item.optLong("chapter_id", 0L)
			val number = num.toFloat()
			val name = item.optString("name", "").nullIfEmpty()
			val createdAt = item.optLong("created_at", 0L)

			val scanlationGroup = item.optJSONObject("scanlation_group")
			var scanlatorName = scanlationGroup?.optString("name", null)?.nullIfEmpty()

			if (scanlatorName == null) {
				val groups = item.optJSONArray("scanlation_groups")
				if (groups != null && groups.length() > 0) {
					scanlatorName = groups.optJSONObject(0)?.optString("name", null)?.nullIfEmpty()
				}
			}

			if (scanlatorName == null && item.optInt("is_official", 0) == 1) {
				scanlatorName = "Official"
			}

			val groupId = item.optInt("scanlation_group_id", 0)

			val title = buildString {
				if (item.optString("volume", "0") != "0") append("Vol. ").append(item.optString("volume")).append(" ")
				append("Ch. ").append(if (number == number.toLong().toFloat()) number.toLong() else number)
				if (name != null) append(" - ").append(name)
				if (scanlatorName != null) append(" [").append(scanlatorName).append("]")
			}.trim()

			val uid = if (chapterId != 0L) "$chapterId-$groupId" else UUID.randomUUID().toString()

			MangaChapter(
				id = generateUid(uid),
				title = title.ifBlank { "Chapter $number" },
				number = number,
				volume = item.optString("volume", "0").toIntOrNull() ?: 0,
				url = "/title/$hashId/dummy-slug/$chapterId-chapter-${number.toInt()}",
				uploadDate = createdAt * 1000L,
				source = source,
				scanlator = scanlatorName,
				branch = scanlatorName
			)
		}
	}

	// -------------------------
	// Pages
	// -------------------------
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterId = chapter.url.substringAfterLast("/").substringBefore("-")
		val response = webClient.httpGet("$apiBaseUrl/chapters/$chapterId").parseJson()

		val images = response.optJSONObject("result")?.optJSONArray("images") ?: return emptyList()
		val pages = ArrayList<MangaPage>(images.length())

		for (i in 0 until images.length()) {
			val imgObj = images.optJSONObject(i) ?: continue
			val url = imgObj.optString("url", "").nullIfEmpty() ?: continue

			pages.add(MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source
			))
		}

		return pages
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

	private val availableTagsMap by lazy {
		mapOf(
			"1" to "Shounen",
			"2" to "Shoujo",
			"3" to "Josei",
			"4" to "Seinen",
			"5" to "Kodomo",
			"6" to "Action",
			"7" to "Adventure",
			"8" to "Boys Love",
			"9" to "Comedy",
			"10" to "Crime",
			"11" to "Drama",
			"12" to "Fantasy",
			"13" to "Girls Love",
			"14" to "Historical",
			"15" to "Horror",
			"16" to "Isekai",
			"17" to "Magical Girls",
			"18" to "Mecha",
			"19" to "Medical",
			"20" to "Mystery",
			"21" to "Philosophical",
			"22" to "Psychological",
			"23" to "Romance",
			"24" to "Sci-Fi",
			"25" to "Slice of Life",
			"26" to "Sports",
			"27" to "Superhero",
			"28" to "Thriller",
			"29" to "Tragedy",
			"30" to "Wuxia",
			"31" to "Aliens",
			"32" to "Animals",
			"33" to "Cooking",
			"34" to "Crossdressing",
			"35" to "Delinquents",
			"36" to "Demons",
			"37" to "Genderswap",
			"38" to "Ghosts",
			"39" to "Gyaru",
			"40" to "Harem",
			"41" to "Incest",
			"42" to "Loli",
			"43" to "Mafia",
			"44" to "Magic",
			"45" to "Martial Arts",
			"46" to "Military",
			"47" to "Monster Girls",
			"48" to "Monsters",
			"49" to "Music",
			"50" to "Ninja",
			"51" to "Office Workers",
			"52" to "Police",
			"53" to "Post-Apocalyptic",
			"54" to "Reincarnation",
			"55" to "Reverse Harem",
			"56" to "Samurai",
			"57" to "School Life",
			"58" to "Shota",
			"59" to "Supernatural",
			"60" to "Survival",
			"61" to "Time Travel",
			"62" to "Traditional Games",
			"63" to "Vampires",
			"64" to "Video Games",
			"65" to "Villainess",
			"66" to "Virtual Reality",
			"67" to "Zombies",
			"68" to "Manga",
			"69" to "Manhwa",
			"70" to "Manhua",
			"71" to "Comic",
			"72" to "Webtoon",
			"73" to "One-shot",
			"74" to "Doujinshi",
			"87264" to "Adult",
			"87265" to "Ecchi",
			"87266" to "Hentai",
			"87267" to "Mature",
			"87268" to "Smut"
		)
	}
}
