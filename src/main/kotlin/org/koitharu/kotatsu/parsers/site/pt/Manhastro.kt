package org.koitharu.kotatsu.parsers.site.pt

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
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

	private val allMangasCache = suspendLazy {
		val jsonStr = webClient.httpGet("$apiUrl/dados").parseRaw().cleanJson()
		val json = JSONObject(jsonStr)
		val data = json.getJSONArray("data")
		val list = mutableListOf<JSONObject>()
		for (i in 0 until data.length()) {
			list.add(data.getJSONObject(i))
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
			ids.add(data.getJSONObject(i).getInt("manga_id"))
		}

		val all = allMangasCache.get()
		val mangaMap = all.associateBy { it.getInt("manga_id") }

		return ids.mapNotNull { id ->
			mangaMap[id]?.let { parseManga(it) }
		}
	}

	private suspend fun searchManga(query: String, tags: Set<MangaTag>, order: SortOrder, page: Int): List<Manga> {
		var mangas = allMangasCache.get().map { parseManga(it) }

		if (query.isNotBlank()) {
			val q = query.trim()
			mangas = mangas.filter {
				it.title.contains(q, ignoreCase = true) ||
					it.altTitles.any { alt -> alt.contains(q, ignoreCase = true) }
			}
		}

		if (tags.isNotEmpty()) {
			mangas = mangas.filter { manga ->
				val mangaGenres = manga.tags.map { it.key }
				tags.all { tag -> mangaGenres.any { it.equals(tag.key, ignoreCase = true) } }
			}
		}

		mangas = when (order) {
			SortOrder.POPULARITY -> mangas.sortedByDescending { it.rating } // Using rating as a proxy if popularity not available in all
			SortOrder.UPDATED -> mangas.sortedByDescending { it.id }
			SortOrder.ALPHABETICAL -> mangas.sortedBy { it.title.lowercase() }
			SortOrder.NEWEST -> mangas.sortedByDescending { it.id }
			else -> mangas
		}

		val fromIndex = page * pageSize
		if (fromIndex >= mangas.size) return emptyList()
		val toIndex = (fromIndex + pageSize).coerceAtMost(mangas.size)
		return mangas.subList(fromIndex, toIndex)
	}

	private fun parseManga(json: JSONObject): Manga {
		val id = json.getInt("manga_id")
		val title = json.optString("titulo_brasil").takeIf { it.isNotBlank() }
			?: json.optString("titulo")
		val image = json.optString("imagem").takeIf { it.isNotBlank() }
			?: json.optString("capa")

		val fullImage = if (image.startsWith("http")) image else "https://$image"

		val genreStr = json.optString("generos")
		val tags = parseTags(genreStr)

		val desc = json.optString("descricao_brasil").takeIf { it.isNotBlank() }
			?: json.optString("descricao")

		val relativeUrl = "/manga/$id"
		return Manga(
			id = generateUid(relativeUrl),
			title = title,
			altTitles = emptySet(),
			url = relativeUrl,
			publicUrl = relativeUrl.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = ContentRating.SAFE,
			coverUrl = fullImage,
			tags = tags,
			state = null,
			authors = emptySet(),
			description = desc,
			source = source,
		)
	}

	private fun parseTags(genres: String): Set<MangaTag> {
		if (genres.isBlank()) return emptySet()
		val set = mutableSetOf<MangaTag>()
		try {
			if (genres.startsWith("[")) {
				val jsonArray = JSONArray(genres)
				for (i in 0 until jsonArray.length()) {
					val g = jsonArray.getString(i).trim()
					if (g.isNotEmpty()) set.add(MangaTag(g, g, source))
				}
			} else {
				genres.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
					set.add(MangaTag(it, it, source))
				}
			}
		} catch (e: Exception) {
			genres.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
				set.add(MangaTag(it, it, source))
			}
		}
		return set
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val idStr = manga.url.substringAfterLast("/")
		val id = idStr.toIntOrNull() ?: return manga

		val all = allMangasCache.get()
		val json = all.find { it.getInt("manga_id") == id } ?: return manga
		val parsed = parseManga(json)

		return parsed.copy(
			chapters = getChaptersList(idStr),
		)
	}

	private suspend fun getChaptersList(id: String): List<MangaChapter> {
		val jsonStr = webClient.httpGet("$apiUrl/dados/$id").parseRaw().cleanJson()
		val json = JSONObject(jsonStr)
		val data = json.getJSONArray("data")
		val chapters = mutableListOf<MangaChapter>()

		val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

		for (i in 0 until data.length()) {
			val item = data.getJSONObject(i)
			val chId = item.getInt("capitulo_id")
			val name = item.getString("capitulo_nome")
			val dateStr = item.optString("capitulo_data")
			val date = try {
				dateFormat.parse(dateStr)?.time ?: 0L
			} catch (e: Exception) {
				0L
			}

			val number = Regex("""(\d+(?:\.\d+)?)""").find(name)?.value?.toFloatOrNull() ?: -1f

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
		val data = json.getJSONObject("data")
		val chapterData = data.optJSONObject("chapter") ?: return emptyList()

		val baseUrl = chapterData.getString("baseUrl")
		val hash = chapterData.getString("hash")
		val images = chapterData.getJSONArray("data")

		val pages = mutableListOf<MangaPage>()
		for (i in 0 until images.length()) {
			val filename = images.getString(i)
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
