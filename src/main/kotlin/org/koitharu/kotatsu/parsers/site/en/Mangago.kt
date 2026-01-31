package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("MANGAGO", "MangaGo", "en")
internal class MangaGo(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAGO, 24), Interceptor {

    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("mangago.me")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Cookie", "_m_superu=1")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities:
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = fetchGenres(),
            availableStates = EnumSet.allOf(MangaState::class.java),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val sortParam = when (order) {
            SortOrder.POPULARITY -> "view"
            SortOrder.NEWEST -> "create_date"
            SortOrder.ALPHABETICAL -> "name"
            else -> "update_date"
        }

        val url = if (!filter.query.isNullOrEmpty()) {
            "https://$domain/r/l_search/?name=${filter.query.urlEncoded()}&page=$page"
        } else {
            val genre = filter.tags.firstOrNull()?.key?.lowercase() ?: "all"
            "https://$domain/genre/$genre/$page/?f=1&o=1&sortby=$sortParam&e="
        }

        val doc = webClient.httpGet(url).parseHtml()
        val items = doc.select(".updatesli, .pic_list > li, div.row")

        return items.mapNotNull {
            val linkEl = element.selectFirst(".thm-effect, h2 a, h3 a, .title a") ?: return@mapNotNull null
            val href = linkEl.attrAsRelativeUrl("href")
            val title = linkEl.attr("title").ifEmpty { linkEl.text() }.trim()

            val imgEl = element.selectFirst("img")
            val img = imgEl?.attrAsAbsoluteUrlOrNull("data-src")?.ifEmpty { imgEl.attrAsAbsoluteUrlOrNull("src") }

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = img,
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = ContentRating.SAFE,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val infoArea = doc.getElementById("information") ?: doc.selectFirst("div.manga_right")
        val title = doc.selectFirst(".w-title h1")?.text()?.trim() ?: manga.title

        val authors = infoArea?.select("td:contains(Author) a, li:contains(Author) a")?.map { it.text() }?.toSet() ?: emptySet()
        val genres = infoArea?.select("td:contains(Genre) a, li:contains(Genre) a")?.map { it.text() }?.toSet() ?: emptySet()
        val statusText = infoArea?.select("td:contains(Status) span, li:contains(Status) span")?.text()?.lowercase()
        val summary = doc.select("div.manga_summary").text().removePrefix("Summary:").trim()

        val state = when {
            statusText?.contains("completed") == true -> MangaState.FINISHED
            statusText?.contains("ongoing") == true -> MangaState.ONGOING
            else -> null
        }

        val chapters = doc.select("table#chapter_table tr").mapIndexedNotNull {
            i, tr ->
            val a = tr.selectFirst("a.chico") ?: return@mapIndexedNotNull null
            val href = a.attrAsRelativeUrl("href")
            val chTitle = a.text().trim()
            val dateText = tr.select("td:last-child").text().trim()

            MangaChapter(
                id = generateUid(href),
                title = chTitle,
                number = (i + 1).toFloat(),
                volume = 0,
                url = href,
                uploadDate = parseDate(dateText),
                source = source,
                scanlator = tr.selectFirst("td.no a")?.text()?.trim(),
                branch = null,
            )
        }.reversed()

        return manga.copy(
            title = title,
            authors = authors,
            tags = genres.map { MangaTag(it, it, source) }.toSet(),
            description = summary,
            state = state,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

        val scriptContent = doc.select("script:containsData(imgsrcs)").html()
        val imgsrcsBase64 = IMGSRCS_REGEX.find(scriptContent)?.groupValues?.get(1)
            ?: throw Exception("Could not find imgsrcs")

        val jsUrl = doc.select("script[src*='chapter.js']").attr("abs:src")
        if (jsUrl.isEmpty()) throw Exception("Chapter JS not found")

        val jsContentObfuscated = webClient.httpGet(jsUrl).parseRaw()
        val deobfJs = deobfuscateSoJsonV4(jsContentObfuscated)

        val keyHex = CRYPTO_HEX_REGEX.findAll(deobfJs)
            .elementAtOrNull(0)?.groupValues?.get(1) ?: "e11adc3949ba59abbe56e057f20f883e"

        val ivHex = CRYPTO_HEX_REGEX.findAll(deobfJs)
            .elementAtOrNull(1)?.groupValues?.get(1) ?: "1234567890abcdef1234567890abcdef"

        val decryptedString = decryptAes(imgsrcsBase64, keyHex, ivHex)

        val keyLocations = STR_CHAR_AT_REGEX.findAll(deobfJs)
            .map { it.groupValues[1].toInt() }.toList()

        val finalUrlString = if (keyLocations.isNotEmpty()) {
            unscrambleImageList(decryptedString, keyLocations)
        } else {
            decryptedString
        }

        val cols = GRID_SIZE_REGEX.find(deobfJs)?.groupValues?.get(1) ?: "9"

        val renImgBody = deobfJs
            .substringAfter("var renImg = function(img,width,height,id){")
            .substringBefore("key = key.split(")
            .split("\n")
            .filter { line -> JS_FILTERS.none { line.contains(it) } }
            .joinToString("\n")
            .replace("img.src", "url")

        val urls = finalUrlString.split(",")
        return urls.map {
            val finalUrl = if (url.contains("cspiclink")) {
                val js = """
                    function replacePos(strObj, pos, replacetext) {
                        var str = strObj.substr(0, pos) + replacetext + strObj.substring(pos + 1, strObj.length);
                        return str;
                    }
                    function getDescramblingKey(url) { $renImgBody; return key; }
                    getDescramblingKey('$url');
                """.trimIndent()
                val puzzleKey = context.evaluateJs(js)
                if (puzzleKey != null) {
                    "$url#desckey=$puzzleKey&cols=$cols"
                } else {
                    url
                }
            } else {
                url
            }
            MangaPage(
                id = generateUid(url),
                url = finalUrl,
                preview = null,
                source = source,
            )
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val fragment = request.url.fragment
        if (fragment?.contains("desckey=") == true) {
            val desckey = fragment.substringAfter("desckey=").substringBefore("&")
            val cols = fragment.substringAfter("cols=", "9").substringBefore("&").toIntOrNull() ?: 9

            return context.redrawImageResponse(response) { bitmap ->
                val puzzleKey = desckey.split('a').filter { it.isNotEmpty() }.map { it.toInt() }
                val width = bitmap.width
                val height = bitmap.height
                val result = context.createBitmap(width, height)

                val smWidth = width / cols
                val smHeight = height / cols

                for (i in 0 until (cols * cols)) {
                    val key = puzzleKey.getOrNull(i) ?: i
                    val srcX = (i % cols) * smWidth
                    val srcY = (i / cols) * smHeight
                    val dstX = (key % cols) * smWidth
                    val dstY = (key / cols) * smHeight

                    result.drawBitmap(
                        bitmap,
                        Rect(srcX, srcY, srcX + smWidth, srcY + smHeight),
                        Rect(dstX, dstY, dstX + smWidth, dstY + smHeight),
                    )
                }
                result
            }
        }
        return response
    }

    private fun deobfuscateSoJsonV4(obfuscated: String): String {
        if (!obfuscated.contains("sojson.v4")) return obfuscated
        val match = SOJSON_V4_MATCH_REGEX.find(obfuscated) ?: return obfuscated
        return match.groupValues[1].split(SPLIT_LETTERS_REGEX)
            .filter { it.isNotEmpty() }
            .map { it.toInt().toChar() }
            .joinToString("")
    }

    private fun decryptAes(b64: String, hexKey: String, hexIv: String): String {
        try {
            val keyBytes = hexKey.decodeHex()
            val ivBytes = hexIv.decodeHex()
            val inputBytes = context.decodeBase64(b64)

            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)

            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decryptedBytes = cipher.doFinal(inputBytes)

            var length = decryptedBytes.size
            while (length > 0 && decryptedBytes[length - 1] == 0.toByte()) {
                length--
            }

            return String(decryptedBytes, 0, length, Charsets.UTF_8).trim()
        } catch (e: Exception) {
            throw Exception("Failed to decrypt image list: ${e.message}")
        }
    }

    private fun unscrambleImageList(scrambledStr: String, locations: List<Int>): String {
        val uniqueLocs = locations.distinct().sorted()

        val keys = mutableListOf<Int>()
        for (loc in uniqueLocs) {
            if (loc < scrambledStr.length) {
                val char = scrambledStr[loc]
                if (char.isDigit()) {
                    keys.add(char.toString().toInt())
                }
            }
        }

        val sb = StringBuilder()
        for (i in scrambledStr.indices) {
            if (i !in uniqueLocs) {
                sb.append(scrambledStr[i])
            }
        }
        val cleanedString = sb.toString()

        return stringUnscramble(cleanedString, keys)
    }

    private fun stringUnscramble(str: String, keys: List<Int>): String {
        val charArray = str.toCharArray()

        for (j in keys.indices.reversed()) {
            val keyVal = keys[j]
            for (i in charArray.lastIndex downTo keyVal) {
                if (i % 2 != 0) {
                    val idx1 = i - keyVal
                    if (idx1 >= 0 && i < charArray.size) {
                        val temp = charArray[idx1]
                        charArray[idx1] = charArray[i]
                        charArray[i] = temp
                    }
                }
            }
        }
        return String(charArray)
    }

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun parseDate(dateStr: String): Long {
        val format = if (dateStr.contains(",")) "MMM d, yyyy" else "MMM d yyyy"
        return SimpleDateFormat(format, Locale.ENGLISH).parseSafe(dateStr)
    }

    private fun fetchGenres(): Set<MangaTag> {
        val genres = listOf(
            "Action", "Adventure", "Comedy", "Doujinshi", "Drama", "Ecchi", "Fantasy",
            "Gender Bender", "Harem", "Historical", "Horror", "Josei", "Martial Arts",
            "Mature", "Mecha", "Mystery", "One Shot", "Psychological", "Romance",
            "School Life", "Sci-fi", "Seinen", "Shoujo", "Shoujo Ai", "Shounen",
            "Shounen Ai", "Slice of Life", "Smut", "Sports", "Supernatural", "Tragedy",
            "Webtoons", "Yaoi", "Yuri",
        )
        return genres.map { MangaTag(it, it, source) }.toSet()
    }

    companion object {
        private val IMGSRCS_REGEX = Regex("""var\s+imgsrcs\s*=\s*['"]([^'"']+)['\"];""")
        private val CRYPTO_HEX_REGEX = Regex("""CryptoJS\.enc\.Hex\.parse\s*\(\s*['"]([0-9a-fA-F]+)['"]\s*\)""")
        private val STR_CHAR_AT_REGEX = Regex("""str\.charAt\(\s*(\d+)\s*\)""")
        private val IMG_KEY_KEYS_REGEX = Regex("""_img[a-f0-9]{5,}\[['"]([^'"']+)['"]\s*\]\s*=\s*['"]([^'"']+)['"]""")
        private val GRID_SIZE_REGEX = Regex("""var\s*widthnum\s*=\s*heightnum\s*=\s*(\d+);""")
        private val SOJSON_V4_MATCH_REGEX = Regex("""null,[\"']([^\"']+)[\"']""")
        private val SPLIT_LETTERS_REGEX = Regex("[a-zA-Z]+")
        private val JS_FILTERS = listOf("jQuery", "document", "getContext", "toDataURL", "getImageData", "width", "height")
    }
}