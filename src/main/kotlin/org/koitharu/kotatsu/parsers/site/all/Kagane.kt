package org.koitharu.kotatsu.parsers.site.all

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.webview.InterceptedRequest
import org.koitharu.kotatsu.parsers.webview.InterceptionConfig
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("KAGANE", "Kagane")
internal class Kagane(context: MangaLoaderContext) : 
    PagedMangaParser(context, MangaParserSource.KAGANE, pageSize = 35) {

    override val configKeyDomain = ConfigKey.Domain("kagane.org")
    private val apiUrl = "https://api.kagane.org"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val genres = listOf(
            "Romance", "Drama", "Manhwa", "Fantasy", "Manga", "Comedy", "Action", "Mature",
            "Shoujo", "Josei", "Shounen", "Slice of Life", "Seinen", "Adventure", "Manhua",
            "School Life", "Smut", "Yaoi", "Hentai", "Historical", "Isekai", "Mystery",
            "Psychological", "Tragedy", "Harem", "Martial Arts", "Sci-Fi", "Ecchi", "Horror"
        ).map { MangaTag(it, it, source) }.toSet()

        return MangaListFilterOptions(
            availableTags = genres,
            availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT)
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val sortParam = when (order) {
            SortOrder.UPDATED -> "updated_at,desc"
            SortOrder.POPULARITY -> "total_views,desc"
            SortOrder.NEWEST -> "created_at,desc"
            SortOrder.ALPHABETICAL -> "series_name,asc"
            else -> "updated_at,desc"
        }

        val url = "$apiUrl/api/v1/search?page=${page - 1}&size=$pageSize&sort=$sortParam"
        val jsonBody = JSONObject()

        if (filter.tags.isNotEmpty()) {
            val genresArr = JSONArray()
            filter.tags.forEach { genresArr.put(it.key) }
            val inclusiveObj = JSONObject()
            inclusiveObj.put("values", genresArr)
            inclusiveObj.put("match_all", false)
            jsonBody.put("inclusive_genres", inclusiveObj)
        }

        val requestUrl = if (!filter.query.isNullOrEmpty()) "$url&name=${filter.query.urlEncoded()}" else url
        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())

        val headers = getRequestHeaders().newBuilder()
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/")
            .build()

        val response = context.httpClient.newCall(
            Request.Builder()
                .url(requestUrl)
                .post(requestBody)
                .headers(headers)
                .build()
        ).await().parseJson()

        val content = response.optJSONArray("content") ?: return emptyList()

        return (0 until content.length()).map { i ->
            val item = content.getJSONObject(i)
            val id = item.getString("id")
            val name = item.getString("name")
            val src = item.optString("source")
            val title = if (src.isNotEmpty()) "$name [$src]" else name

            Manga(
                id = generateUid(id),
                url = id,
                publicUrl = "https://$domain/series/$id",
                coverUrl = "$apiUrl/api/v1/series/$id/thumbnail",
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = ContentRating.SAFE
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val seriesId = manga.url
        val url = "$apiUrl/api/v1/series/$seriesId"
        val headers = getRequestHeaders().newBuilder()
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/")
            .build()
        val json = webClient.httpGet(url, headers).parseJson()

        val desc = StringBuilder()
        json.optString("summary").takeIf { it.isNotEmpty() }?.let { desc.append(it).append("\n\n") }
        val sourceStr = json.optString("source")
        if (sourceStr.isNotEmpty()) desc.append("Source: $sourceStr\n")

        val statusStr = json.optString("status")
        val state = when (statusStr.uppercase()) {
            "ONGOING" -> MangaState.ONGOING
            "ENDED" -> MangaState.FINISHED
            "HIATUS" -> MangaState.PAUSED
            else -> null
        }

        val genres = json.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()

        val authors = json.optJSONArray("authors")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } ?: emptySet()

        val booksUrl = "$apiUrl/api/v1/books/$seriesId"
        val booksJson = webClient.httpGet(booksUrl, headers).parseJson()
        val content = booksJson.optJSONArray("content") ?: JSONArray()

        val chapters = ArrayList<MangaChapter>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)

        for (i in 0 until content.length()) {
            val ch = content.getJSONObject(i)
            val chId = ch.getString("id")
            val chTitle = ch.optString("title")
            val number = ch.optDouble("number_sort", 0.0).toFloat()
            val dateStr = ch.optString("release_date")
            val pageCount = ch.optInt("pages_count", 0)

            chapters.add(
                MangaChapter(
                    id = generateUid(chId),
                    title = chTitle,
                    number = number,
                    volume = 0,
                    // Store URL for WebView
                    url = "/series/$seriesId/$chId?pages=$pageCount",
                    uploadDate = try { dateFormat.parse(dateStr)?.time ?: 0L } catch (e: Exception) { 0L },
                    source = source,
                    scanlator = null,
                    branch = null
                )
            )
        }

        return manga.copy(
            description = desc.toString(),
            state = state,
            authors = authors,
            tags = genres.map { MangaTag(it, it, source) }.toSet(),
            chapters = chapters.reversed()
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val uri = java.net.URI(chapter.url)
        val pathParts = uri.path.split("/").filter { it.isNotEmpty() }
        if (pathParts.size < 3) throw Exception("Invalid chapter URL format: ${chapter.url}")
        
        val seriesId = pathParts[1]
        val chapterId = pathParts[2]
        val query = uri.query ?: ""
        val pageCount = query.split("&")
            .find { it.startsWith("pages=") }
            ?.substringAfter("=")
            ?.toIntOrNull() ?: throw Exception("Missing page count in URL")

        // 1. Fetch certificate
        val cert = getCertificate()
        
        // 2. Generate PSSH
        val pssh = getPssh(seriesId, chapterId)
        
        // 3. Construct JS
        val script = """
            (async function() {
                try {
                    const certBase64 = "$cert";
                    const psshBase64 = "$pssh";
                    const binaryString = atob(certBase64);
                    const bytes = new Uint8Array(binaryString.length);
                    for (var i = 0; i < binaryString.length; i++) {
                        bytes[i] = binaryString.charCodeAt(i);
                    }
                    const serverCert = bytes.buffer;

                    const config = [{
                        initDataTypes: ["cenc"],
                        audioCapabilities: [],
                        videoCapabilities: [{ contentType: 'video/mp4; codecs="avc1.42E01E"' }]
                    }];
                    
                    let access;
                    try {
                        access = await navigator.requestMediaKeySystemAccess("com.widevine.alpha", config);
                    } catch (e) {
                        // Fallback or retry
                        access = await navigator.requestMediaKeySystemAccess("com.widevine.alpha", config);
                    }
                    
                    const mediaKeys = await access.createMediaKeys();
                    await mediaKeys.setServerCertificate(serverCert);
                    
                    const session = mediaKeys.createSession();
                    
                    const psshBinary = atob(psshBase64);
                    const psshBytes = new Uint8Array(psshBinary.length);
                    for (var i = 0; i < psshBinary.length; i++) {
                        psshBytes[i] = psshBinary.charCodeAt(i);
                    }
                    
                    const promise = new Promise((resolve, reject) => {
                        session.addEventListener("message", (event) => {
                             resolve(event.message);
                        });
                        session.addEventListener("error", (err) => {
                             reject(err);
                        });
                    });
                    
                    await session.generateRequest("cenc", psshBytes.buffer);
                    const message = await promise;
                    
                    // Convert ArrayBuffer to Base64
                    let binary = '';
                    const bytesMsg = new Uint8Array(message);
                    for (let i = 0; i < bytesMsg.byteLength; i++) {
                        binary += String.fromCharCode(bytesMsg[i]);
                    }
                    const challenge = btoa(binary);
                    
                    // POST to API
                    const challengeUrl = "$apiUrl/api/v1/books/$seriesId/file/$chapterId";
                    const payload = { challenge: challenge };
                    
                    const resp = await fetch(challengeUrl, {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/json',
                            'Origin': 'https://$domain',
                            'Referer': 'https://$domain/'
                        },
                        body: JSON.stringify(payload)
                    });
                    
                    const json = await resp.json();
                    
                    // Exfiltrate via dummy URL
                    const result = {
                        token: json.access_token,
                        cache: json.cache_url,
                        mapping: json.page_mapping
                    };
                    
                    window.location.href = "https://kotatsu.intercept/result?data=" + encodeURIComponent(JSON.stringify(result));
                    
                } catch (e) {
                    window.location.href = "https://kotatsu.intercept/error?msg=" + encodeURIComponent(e.toString());
                }
            })();
        ".trimIndent()

        // 4. Intercept
        val config = InterceptionConfig(
            timeoutMs = 60000,
            urlPattern = Regex("https://kotatsu\.intercept/.*"),
            pageScript = script
        )
        
        // Load the actual chapter page to set correct Origin/Referer context for DRM
        val interceptUrl = "https://$domain/series/$seriesId/$chapterId"
        val requests = context.interceptWebViewRequests(interceptUrl, config)
        
        val resultRequest = requests.firstOrNull() ?: throw Exception("Failed to intercept DRM token")
        
        if (resultRequest.url.contains("/error")) {
            val msg = resultRequest.getQueryParameter("msg") ?: "Unknown error"
            throw Exception("DRM JS Error: $msg")
        }
        
        val dataStr = resultRequest.getQueryParameter("data") ?: throw Exception("No data in interception")
        val data = JSONObject(dataStr)
        
        val token = data.getString("token")
        val cacheUrl = data.getString("cache")
        val mappingJson = data.optJSONObject("mapping")
        
        val mapping = mutableMapOf<Int, String>()
        mappingJson?.keys()?.forEach {
            val idx = it.toIntOrNull()
            if (idx != null) mapping[idx] = mappingJson.getString(it)
        }

        return (0 until pageCount).map { index ->
            val pageIndex = index + 1
            val fileId = mapping[pageIndex] ?: "page_$pageIndex.jpg"
            
            val imageUrl = "$cacheUrl/api/v1/books/$seriesId/file/$chapterId/$fileId?token=$token&index=$pageIndex"
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source
            )
        }
    }

    private var cachedCert: String? = null
    
    private suspend fun getCertificate(): String {
        cachedCert?.let { return it }
        val url = "$apiUrl/api/v1/static/bin.bin"
        val req = Request.Builder().url(url)
            .addHeader("Origin", "https://$domain")
            .addHeader("Referer", "https://$domain/")
            .build()
            
        val bytes = context.httpClient.newCall(req).await().body?.bytes()
            ?: throw Exception("Failed to fetch certificate")
        
        val b64 = Base64.getEncoder().encodeToString(bytes)
        cachedCert = b64
        return b64
    }
    
    private fun getPssh(seriesId: String, chapterId: String): String {
        val hash = sha256("$seriesId:$chapterId").copyOfRange(0, 16)
        
        // Widevine System ID
        val systemId = Base64.getDecoder().decode("7e+LqXnWSs6jyCfc1R0h7Q==")
        val zeroes = ByteArray(4)
        
        // Header: 18 (byte), hash.size (byte) + hash
        val header = byteArrayOf(18, hash.size.toByte()) + hash
        val headerSize = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(header.size).array()
        
        val innerBox = zeroes + systemId + headerSize + header
        
        val outerSize = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(innerBox.size + 8).array()
        val psshTag = "pssh".toByteArray(StandardCharsets.UTF_8)
        
        val fullBox = outerSize + psshTag + innerBox
        return Base64.getEncoder().encodeToString(fullBox)
    }

    private fun sha256(str: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(str.toByteArray(StandardCharsets.UTF_8))
        
    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val result = ByteArray(this.size + other.size)
        System.arraycopy(this, 0, result, 0, this.size)
        System.arraycopy(other, 0, result, this.size, other.size)
        return result
    }
}