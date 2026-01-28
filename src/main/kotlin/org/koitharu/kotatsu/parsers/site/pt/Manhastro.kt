package org.koitharu.kotatsu.parsers.site.pt

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANHASTRO", "Manhastro", "pt")
internal class Manhastro(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.MANHASTRO, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("manhastro.net")
	private val apiUrl = "https://api2.manhastro.net"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.ALPHABETICAL,
		SortOrder.NEWEST,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isMultipleTagsSupported = true,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = getGenreList().mapToSet { (name, key) -> MangaTag(name, key, source) },
	)

	// Lightweight data class to store minimal info for search/listing
	private data class SimpleManga(
		val id: Int,
		val title: String,
		val altTitles: Set<String>,
		val image: String,
		val rating: Float,
		val tags: Set<String>,
		val description: String
	)

	private val allMangasCache = suspendLazy {
		val jsonStr = webClient.httpGet("$apiUrl/dados").parseRaw().cleanJson()
		val json = JSONObject(jsonStr)
		val data = json.getJSONArray("data")
		val list = ArrayList<SimpleManga>(data.length())
		
		for (i in 0 until data.length()) {
			val obj = data.optJSONObject(i) ?: continue
			val id = obj.optInt("manga_id", -1)
			if (id == -1) continue

			val title = obj.optString("titulo_brasil").takeIf { it.isNotBlank() }
				?: obj.optString("titulo", "")
			
			// Extract tags for filtering
			val genreStr = obj.optString("generos")
			val tags = parseTagsStrings(genreStr)

			// Simple rating logic if available, or unknown
			// The original code used RATING_UNKNOWN constant (-1f)
			val rating = RATING_UNKNOWN

			val image = obj.optString("imagem").takeIf { it.isNotBlank() }
				?: obj.optString("capa", "")
			val fullImage = when {
				image.startsWith("http") -> image
				image.startsWith("//") -> "https:$image"
				else -> "https://$image"
			}

			val desc = obj.optString("descricao_brasil").takeIf { it.isNotBlank() }
				?: obj.optString("descricao", "")

			list.add(SimpleManga(
				id = id,
				title = title,
				altTitles = emptySet(), // API doesn't seem to provide alts in the main list easily
				image = fullImage,
				rating = rating,
				tags = tags,
				description = desc
			))
		}
		list
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query.orEmpty()
		val tags = filter.tags

		if (query.isNotBlank() || tags.isNotEmpty()) {
			return searchManga(query, tags, order, page)
		}

		if (page > 0) return emptyList()

		// If no search, use the specific endpoints for initial views
		val endpoint = when (order) {
			SortOrder.POPULARITY -> "$apiUrl/rank/diario/all"
			SortOrder.UPDATED -> "$apiUrl/lancamentos/all"
			else -> return searchManga("", emptySet(), order, page)
		}

		val jsonStr = webClient.httpGet(endpoint).parseRaw().cleanJson()
		val jsonObj = JSONObject(jsonStr)
		val data = jsonObj.getJSONArray("data")
		val ids = mutableListOf<Int>()
		for (i in 0 until data.length()) {
			val obj = data.optJSONObject(i)
			val id = obj?.optInt("manga_id", -1) ?: -1
			if (id != -1) ids.add(id)
		}

		val all = allMangasCache.get()
		val mangaMap = all.associateBy { it.id }

		return ids.mapNotNull { id ->
			mangaMap[id]?.toManga()
		}
	}

	private suspend fun searchManga(query: String, tags: Set<MangaTag>, order: SortOrder, page: Int): List<Manga> {
		var mangas = allMangasCache.get()

		if (query.isNotBlank()) {
			val q = query.trim()
			mangas = mangas.filter {
				it.title.contains(q, ignoreCase = true)
			}
		}

		if (tags.isNotEmpty()) {
			val requiredTags = tags.map { it.key }
			mangas = mangas.filter { manga ->
				requiredTags.all { required -> 
					manga.tags.any { it.equals(required, ignoreCase = true) }
				}
			}
		}

		mangas = when (order) {
			// Sorting based on ID as a proxy for 'updated' or 'newest' since we don't have dates in SimpleManga
			// If we needed dates, we'd add 'lastUpdate' to SimpleManga
			SortOrder.POPULARITY -> mangas // Already sorted by popularity usually, or we'd need a popularity metric
			SortOrder.UPDATED -> mangas.sortedByDescending { it.id } 
			SortOrder.ALPHABETICAL -> mangas.sortedBy { it.title.lowercase() }
			SortOrder.NEWEST -> mangas.sortedByDescending { it.id }
			else -> mangas
		}

		val fromIndex = page * pageSize
		if (fromIndex >= mangas.size || fromIndex < 0) return emptyList()
		val toIndex = (fromIndex + pageSize).coerceAtMost(mangas.size)
		
		return mangas.subList(fromIndex, toIndex).map { it.toManga() }
	}

	private fun SimpleManga.toManga(): Manga {
		val relativeUrl = "/manga/$id"
		return Manga(
			id = generateUid(relativeUrl),
			title = title,
			altTitles = altTitles,
			url = relativeUrl,
			publicUrl = relativeUrl.toAbsoluteUrl(domain),
			rating = rating,
			contentRating = ContentRating.SAFE,
			coverUrl = image,
			tags = tags.mapToSet { MangaTag(it, it, source) },
			state = null,
			authors = emptySet(),
			description = description,
			source = source,
		)
	}

	// Used only for the full detail parsing if needed, but we rely on SimpleManga mostly
	private fun parseTagsStrings(genres: String): Set<String> {
		if (genres.isBlank()) return emptySet()
		val set = mutableSetOf<String>()
		
		if (genres.startsWith("[")) {
			runCatching {
				val jsonArray = JSONArray(genres)
				for (i in 0 until jsonArray.length()) {
					val g = jsonArray.optString(i, "").trim()
					if (g.isNotEmpty()) set.add(g)
				}
			}
		} else {
			genres.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
				set.add(it)
			}
		}
		return set
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val idStr = manga.url.substringAfterLast("/")
		val id = idStr.toIntOrNull() ?: return manga

		val all = allMangasCache.get()
		val simpleManga = all.find { it.id == id } ?: return manga
		
		return simpleManga.toManga().copy(
			chapters = getChaptersList(idStr),
		)
	}

	private suspend fun getChaptersList(id: String): List<MangaChapter> {
		val jsonStr = webClient.httpGet("$apiUrl/dados/$id").parseRaw().cleanJson()
		val json = JSONObject(jsonStr)
		val data = json.getJSONArray("data")
		val chapters = mutableListOf<MangaChapter>()

		val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
		val numberRegex = Regex("""(\d+(?:\.\d+)?)""")

		for (i in 0 until data.length()) {
			val item = data.optJSONObject(i) ?: continue
			val chId = item.optInt("capitulo_id", -1)
			if (chId == -1) continue
			
			val name = item.optString("capitulo_nome", "")
			val dateStr = item.optString("capitulo_data", "")
			val date = try {
				dateFormat.parse(dateStr)?.time ?: 0L
			} catch (e: Exception) {
				0L
			}

			val number = numberRegex.find(name)?.value?.toFloatOrNull() ?: -1f

			val relativeUrl = "/capitulo/$chId"
			chapters.add(
				MangaChapter(
					id = generateUid(relativeUrl),
					url = relativeUrl,
					title = name,
					number = number,
					volume = 0,
					uploadDate = date,
					source = source,
					scanlator = null,
					branch = null,
				),
			)
		}
		chapters.sortByDescending { it.number }
		return chapters
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val id = chapter.url.substringAfterLast("/")
		val jsonStr = webClient.httpGet("$apiUrl/paginas/$id").parseRaw().cleanJson()
		val json = JSONObject(jsonStr)
		val data = json.optJSONObject("data") ?: return emptyList()
		val chapterData = data.optJSONObject("chapter") ?: return emptyList()

		val baseUrl = chapterData.optString("baseUrl")
		val hash = chapterData.optString("hash")
		
		if (baseUrl.isBlank() || hash.isBlank()) return emptyList()
		
		val images = chapterData.optJSONArray("data") ?: return emptyList()

		val pages = mutableListOf<MangaPage>()
		for (i in 0 until images.length()) {
			val filename = images.optString(i, "")
			if (filename.isEmpty()) continue
			
			val url = "$baseUrl/$hash/$filename"
			pages.add(
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				),
			)
		}
		return pages
	}

	private fun String.cleanJson(): String {
		return this.removePrefix("\uFEFF")
			.removePrefix(")]}'")
			.removePrefix(",")
			.removePrefix("_")
			.trim()
	}

	private fun getGenreList() = listOf(
		"4-koma" to "4-koma",
		"Ação" to "ação",
		"Adaptação" to "adaptação",
		"Adaptation" to "adaptation",
		"Adulto" to "adulto",
		"Aliens" to "aliens",
		"Animais" to "animais",
		"Animals" to "animals",
		"Anthology" to "anthology",
		"Apocalipse" to "apocalipse",
		"Argos" to "argos",
		"Artes Marciais" to "artes marciais",
		"Ativo" to "ativo",
		"Atualização" to "atualização",
		"Auto-publicado" to "auto-publicado",
		"Aventura" to "aventura",
		"Award Winning" to "award winning",
		"BL" to "bl",
		"Bizarro" to "bizarro",
		"Comédia" to "comédia",
		"Completo" to "completo",
		"Cooking" to "cooking",
		"Crime" to "crime",
		"Crossdressing" to "crossdressing",
		"Culinária" to "culinária",
		"Cultivo" to "cultivo",
		"Delinquents" to "delinquents",
		"Demônios" to "demônios",
		"Deuses" to "deuses",
		"Doujinshi" to "doujinshi",
		"Drama" to "drama",
		"Ecchi" to "ecchi",
		"Escola" to "escola",
		"Esporte" to "esporte",
		"Esportes" to "esportes",
		"FKscan" to "fkscan",
		"Familia" to "familia",
		"Fantasia" to "fantasia",
		"Ficção Científica" to "ficção científica",
		"Filosófico" to "filosófico",
		"Full Color" to "full color",
		"Genérico" to "genérico",
		"Gender Bender" to "gender bender",
		"Ghosts" to "ghosts",
		"Gore" to "gore",
		"Guerra" to "guerra",
		"Gyaru" to "gyaru",
		"Harém" to "harém",
		"Histórico" to "histórico",
		"Horror" to "horror",
		"Incest" to "incest",
		"Isekai" to "isekai",
		"Josei" to "josei",
		"Kamen Rider" to "kamen rider",
		"Linha do Tempo" to "linha do tempo",
		"Loli" to "loli",
		"Long Strip" to "long strip",
		"Luta" to "luta",
		"Médico" to "médico",
		"Música" to "música",
		"Mafia" to "mafia",
		"Magia" to "magia",
		"Mecha" to "mecha",
		"Mechas" to "mechas",
		"Militar" to "militar",
		"Mistério" to "mistério",
		"Moderno" to "moderno",
		"Monster Girls" to "monster girls",
		"Monstros" to "monstros",
		"Murim" to "murim",
		"Music" to "music",
		"NextSakura Scan" to "nextsakura scan",
		"Ninja" to "ninja",
		"Nitury Scan" to "nitury scan",
		"Office Workers" to "office workers",
		"Official Colored" to "official colored",
		"One Shot" to "one shot",
		"Oneshot" to "oneshot",
		"Police" to "police",
		"Policial" to "policial",
		"Post-Apocalyptic" to "post-apocalyptic",
		"Psicológico" to "psicológico",
		"Reencarnação" to "reencarnação",
		"Regressão" to "regressão",
		"Reincarnation" to "reincarnation",
		"Reverse Harem" to "reverse harem",
		"Romance" to "romance",
		"Sagrado Império da Britannia" to "sagrado império da britannia",
		"Saint Seiya" to "saint seiya",
		"Samurai" to "samurai",
		"School Life" to "school life",
		"Seinen" to "seinen",
		"Sempre ao seu lado Scan" to "sempre ao seu lado scan",
		"Shota" to "shota",
		"Shoujo" to "shoujo",
		"Shoujo Ai" to "shoujo ai",
		"Shounen" to "shounen",
		"Shounen Ai" to "shounen ai",
		"Sistema" to "sistema",
		"Slice of Life" to "slice of life",
		"Sobrenatural" to "sobrenatural",
		"Sobrevivência" to "sobrevivência",
		"Super Poderes" to "super poderes",
		"Super-herói" to "super-herói",
		"Supernatural" to "supernatural",
		"Survival" to "survival",
		"Suspense" to "suspense",
		"Terror" to "terror",
		"Thriller" to "thriller",
		"Time Travel" to "time travel",
		"Tokusatsu" to "tokusatsu",
		"Tragédia" to "tragédia",
		"Vampires" to "vampires",
		"Vampiros" to "vampiros",
		"Vida Escolar" to "vida escolar",
		"Video Games" to "video games",
		"Villainess" to "villainess",
		"Vingança" to "vingança",
		"Volta no Tempo" to "volta no tempo",
		"Wuxia" to "wuxia",
		"Yaoi" to "yaoi",
		"Yuri" to "yuri",
		"Zombies" to "zombies",
		"jogos" to "jogos",
		"shonen ai" to "shonen ai",
	)
}
