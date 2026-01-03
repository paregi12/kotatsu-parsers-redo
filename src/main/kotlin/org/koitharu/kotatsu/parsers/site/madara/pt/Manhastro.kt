package org.koitharu.kotatsu.parsers.site.madara.pt

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.AbstractMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("MANHASTRO", "Manhastro", "pt")
internal class Manhastro(context: MangaLoaderContext) :
    AbstractMangaParser(context, MangaParserSource.MANHASTRO) {

    private val apiUrl = "https://api2.manhastro.net"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    override val configKeyDomain = ConfigKey.Domain("manhastro.net")

    override val availableSortOrders: Set<SortOrder> = setOf(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true // We handle filters locally
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT)
        )
    }

    override suspend fun getList(
        offset: Int,
        order: SortOrder,
        filter: MangaListFilter
    ): List<Manga> {
        val query = filter.query.orEmpty().trim().lowercase().normalize()
        
        // Fetch all mangas (needed for details in all cases)
        val allMangas = fetchAllMangas()

        var mangas = if (query.isNotEmpty()) {
            allMangas.filter { 
                it.title.lowercase().normalize().contains(query) || 
                it.titleBrasil?.lowercase()?.normalize()?.contains(query) == true
            }
        } else {
            allMangas
        }

        // Apply sorts
        mangas = when (order) {
            SortOrder.UPDATED -> {
                // Fetch latest IDs
                val latestIds = fetchLatestIds()
                val latestMap = mangas.associateBy { it.id }
                latestIds.mapNotNull { latestMap[it] }
            }
            SortOrder.POPULARITY -> {
                 mangas.sortedByDescending { it.views }
            }
            SortOrder.NEWEST -> {
                mangas.sortedByDescending { it.id }
            }
            SortOrder.ALPHABETICAL -> {
                mangas.sortedBy { it.displayTitle.lowercase() }
            }
            else -> mangas
        }

        // Pagination (local slice)
        val end = (offset + 24).coerceAtMost(mangas.size)
        if (offset >= mangas.size) return emptyList()
        
        return mangas.subList(offset, end).map { dto ->
            Manga(
                id = generateUid(dto.id.toString()),
                title = dto.displayTitle,
                altTitles = emptySet(),
                url = "/manga/${dto.id}",
                publicUrl = "$domain/manga/${dto.id}",
                rating = RATING_UNKNOWN,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                coverUrl = dto.thumbnailUrl,
                tags = dto.genres.map { MangaTag(key = it, title = it, source = source) }.toSet(),
                state = null,
                authors = emptySet(),
                source = source
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val id = manga.url.substringAfterLast("/").toInt()
        val allMangas = fetchAllMangas()
        val dto = allMangas.find { it.id == id } ?: throw Exception("Manga not found")
        
        val chapters = fetchChapters(dto.id)

        return manga.copy(
            title = dto.displayTitle,
            description = dto.displayDescription,
            tags = dto.genres.map { MangaTag(key = it, title = it, source = source) }.toSet(),
            coverUrl = dto.thumbnailUrl,
            chapters = chapters
        )
    }

    private suspend fun fetchChapters(mangaId: Int): List<MangaChapter> {
        val json = webClient.httpGet("$apiUrl/dados/$mangaId").parseJson().getJSONArray("data")
        val chapters = mutableListOf<MangaChapter>()

        for (i in 0 until json.length()) {
            val obj = json.getJSONObject(i)
            val chapterId = obj.getInt("capitulo_id")
            val name = obj.getString("capitulo_nome")
            val dateStr = obj.optString("capitulo_data")
            
            val number = extractChapterNumber(name)
            val date = try {
                dateFormat.parse(dateStr)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }

            chapters.add(
                MangaChapter(
                    id = generateUid(chapterId.toString()),
                    url = "/capitulo/$chapterId",
                    title = name,
                    number = number,
                    volume = 0,
                    uploadDate = date,
                    source = source,
                    scanlator = null,
                    branch = null
                )
            )
        }
        
        // Sort descending by number (newest first)
        return chapters.sortedByDescending { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val id = chapter.url.substringAfterLast("/")
        val response = webClient.httpGet("$apiUrl/paginas/$id").parseJson()
        val data = response.getJSONObject("data").getJSONObject("chapter")
        
        val baseUrl = data.getString("baseUrl")
        val hash = data.getString("hash")
        val files = data.getJSONArray("data")
        
        val pages = mutableListOf<MangaPage>()
        for (i in 0 until files.length()) {
            val file = files.getString(i)
            val url = "$baseUrl/$hash/$file"
            pages.add(
                MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = null,
                    source = source
                )
            )
        }
        return pages
    }

    private suspend fun fetchAllMangas(): List<MangaDto> {
        val response = webClient.httpGet("$apiUrl/dados").parseJson()
        val array = response.getJSONArray("data")
        val list = ArrayList<MangaDto>(array.length())
        for (i in 0 until array.length()) {
            list.add(MangaDto.fromJson(array.getJSONObject(i)))
        }
        return list
    }

    private suspend fun fetchLatestIds(): List<Int> {
        val response = webClient.httpGet("$apiUrl/lancamentos").parseJson()
        val array = response.getJSONArray("data")
        val list = ArrayList<Int>(array.length())
        for (i in 0 until array.length()) {
            list.add(array.getJSONObject(i).getInt("manga_id"))
        }
        return list
    }

    private fun extractChapterNumber(name: String): Float {
        val regex = Regex("""(\d+(?:\.\d+)?)""")
        val match = regex.find(name)
        return match?.value?.toFloatOrNull() ?: -1f
    }
    
    private fun String.normalize(): String {
        return java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD)
            .replace(Regex("""[\p{InCombiningDiacriticalMarks}]"""), "")
    }

    private data class MangaDto(
        val id: Int,
        val title: String,
        val titleBrasil: String?,
        val description: String?,
        val descriptionBrasil: String?,
        val image: String?,
        val cover: String?,
        val genres: List<String>,
        val views: Int
    ) {
        val displayTitle: String get() = if (!titleBrasil.isNullOrBlank()) titleBrasil else title
        val displayDescription: String? get() = if (!descriptionBrasil.isNullOrBlank()) descriptionBrasil else description
        val thumbnailUrl: String? get() {
            val url = if (!image.isNullOrBlank()) image else cover
            return url?.let { if (it.startsWith("http")) it else "https://$it" }
        }

        companion object {
            fun fromJson(json: JSONObject): MangaDto {
                val genresStr = json.optString("generos")
                val genres = if (genresStr.startsWith("[")) {
                     try {
                         val arr = JSONArray(genresStr)
                         val list = mutableListOf<String>()
                         for(i in 0 until arr.length()) list.add(arr.getString(i))
                         list
                     } catch(e: Exception) { emptyList() }
                } else if (genresStr.contains(",")) {
                    genresStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                } else if (genresStr.isNotBlank()) {
                    listOf(genresStr.trim())
                } else {
                    emptyList()
                }

                return MangaDto(
                    id = json.getInt("manga_id"),
                    title = json.optString("titulo"),
                    titleBrasil = json.optString("titulo_brasil").takeIf { it != "null" },
                    description = json.optString("descricao").takeIf { it != "null" },
                    descriptionBrasil = json.optString("descricao_brasil").takeIf { it != "null" },
                    image = json.optString("imagem").takeIf { it != "null" },
                    cover = json.optString("capa").takeIf { it != "null" },
                    genres = genres,
                    views = json.optString("views_mes").toIntOrNull() ?: 0
                )
            }
        }
    }
}
