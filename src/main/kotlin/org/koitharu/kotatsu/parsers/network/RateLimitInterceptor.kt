package org.koitharu.kotatsu.parsers.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val HEADER_RATE_LIMIT_PERMITS = "X-Rate-Limit-Permits"
private const val HEADER_RATE_LIMIT_PERIOD = "X-Rate-Limit-Period"

/**
 * An OkHttp interceptor that enforces rate limiting using headers.
 * This allows sharing rate limits across different OkHttp clients if they target the same host.
 */
private object RateLimitInterceptor : Interceptor {
    private val limiters = ConcurrentHashMap<String, RateLimiter>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val permits = request.header(HEADER_RATE_LIMIT_PERMITS)?.toIntOrNull()
        val periodMillis = request.header(HEADER_RATE_LIMIT_PERIOD)?.toLongOrNull()

        val requestWithoutHeaders = if (request.header(HEADER_RATE_LIMIT_PERMITS) != null) {
            request.newBuilder()
                .removeHeader(HEADER_RATE_LIMIT_PERMITS)
                .removeHeader(HEADER_RATE_LIMIT_PERIOD)
                .build()
        } else {
            request
        }

        if (permits != null && periodMillis != null) {
            val host = request.url.host
            val limiter = limiters.computeIfAbsent(host) { RateLimiter() }
            val timestamp = limiter.acquire(chain.call(), permits, periodMillis)

            val response = try {
                chain.proceed(requestWithoutHeaders)
            } catch (e: Exception) {
                throw e
            }

            if (response.networkResponse == null) {
                limiter.release(timestamp)
            }

            return response
        }

        return chain.proceed(requestWithoutHeaders)
    }
}

private class RateLimiter {
    private val requestQueue = ArrayDeque<Long>()
    private val fairLock = Semaphore(1, true)

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    fun acquire(call: okhttp3.Call, permits: Int, periodMillis: Long): Long {
        try {
            fairLock.acquire()
        } catch (e: InterruptedException) {
            throw IOException(e)
        }

        try {
            synchronized(requestQueue) {
                while (requestQueue.size >= permits) {
                    val periodStart = elapsedRealtime() - periodMillis
                    var hasRemovedExpired = false
                    while (!requestQueue.isEmpty() && requestQueue.peekFirst()!! <= periodStart) {
                        requestQueue.removeFirst()
                        hasRemovedExpired = true
                    }
                    if (call.isCanceled()) {
                        throw IOException("Canceled")
                    } else if (hasRemovedExpired) {
                        break
                    } else {
                        val waitTime = requestQueue.peekFirst()!! - periodStart
                        if (waitTime > 0) {
                            try {
                                (requestQueue as Object).wait(waitTime)
                            } catch (_: InterruptedException) {
                                continue
                            }
                        }
                    }
                }

                val timestamp = elapsedRealtime()
                requestQueue.addLast(timestamp)
                return timestamp
            }
        } finally {
            fairLock.release()
        }
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    fun release(timestamp: Long) {
        synchronized(requestQueue) {
            if (requestQueue.isEmpty()) return
            requestQueue.removeFirstOccurrence(timestamp)
            (requestQueue as Object).notifyAll()
        }
    }
}

private fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000

/**
 * An OkHttp interceptor that enforces rate limiting across all requests.
 *
 * Examples:
 * - `permits = 5`, `period = 1.seconds` =>  5 requests per second
 * - `permits = 10`, `period = 2.minutes` =>  10 requests per 2 minutes
 *
 * @param permits [Int]     Number of requests allowed within a period of units.
 * @param period [Duration] The limiting duration. Defaults to 1.seconds.
 */
public fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Duration = 1.seconds
): OkHttpClient.Builder = rateLimit(permits, period) { true }

/**
 * An OkHttp interceptor that handles given url host's rate limiting.
 *
 * Examples:
 * - `url = "https://api.manga.example"`, `permits = 5`, `period = 1.seconds` =>  5 requests per second to any url with host "api.manga.example"
 * - `url = "https://cdn.manga.example/image"`, `permits = 10`, `period = 2.minutes`  =>  10 requests per 2 minutes to any url with host "cdn.manga.example"
 *
 * @param url [String]      The url host that this interceptor should handle. Will get url's host by using HttpUrl.host()
 * @param permits [Int]     Number of requests allowed within a period of units.
 * @param period [Duration] The limiting duration. Defaults to 1.seconds.
 */
public fun OkHttpClient.Builder.rateLimit(
    url: String,
    permits: Int,
    period: Duration = 1.seconds,
): OkHttpClient.Builder = rateLimit(url.toHttpUrl(), permits, period)

/**
 * An OkHttp interceptor that handles given url host's rate limiting.
 *
 * Examples:
 * - `httpUrl = "https://api.manga.example".toHttpUrlOrNull()`, `permits = 5`, `period = 1.seconds` =>  5 requests per second to any url with host "api.manga.example"
 * - `httpUrl = "https://cdn.manga.example/image".toHttpUrlOrNull()`, `permits = 10`, `period = 2.minutes` =>  10 requests per 2 minutes to any url with host "cdn.manga.example
 *
 * @param httpUrl [HttpUrl] The url host that this interceptor should handle. Will get url's host by using HttpUrl.host()
 * @param permits [Int]     Number of requests allowed within a period of units.
 * @param period [Duration] The limiting duration. Defaults to 1.seconds.
 */
public fun OkHttpClient.Builder.rateLimit(
    httpUrl: HttpUrl,
    permits: Int,
    period: Duration = 1.seconds,
): OkHttpClient.Builder = rateLimit(permits, period) { it.host == httpUrl.host }

/**
 * An OkHttp interceptor that enforces conditional rate limiting based on a given condition.
 *
 * Examples:
 * - `permits = 5`, `period = 1.seconds`, `shouldLimit = { it.host == "api.manga.example" }` => 5 requests per second to any url with host "api.manga.example".
 * - `permits = 10`, `period = 2.minutes`, `shouldLimit = { it.encodedPath.startsWith("/images/") }` => 10 requests per 2 minutes to paths starting with "/images/".
 *
 * @param permits [Int]     Number of requests allowed within a period of units.
 * @param period [Duration] The limiting duration. Defaults to 1.seconds.
 * @param shouldLimit       A predicate to determine whether the rate limit should apply to a given request.
 */
public fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Duration = 1.seconds,
    shouldLimit: (HttpUrl) -> Boolean,
): OkHttpClient.Builder {
    addInterceptor { chain ->
        val request = chain.request()
        if (shouldLimit(request.url)) {
            val newRequest = request.newBuilder()
                .header(HEADER_RATE_LIMIT_PERMITS, permits.toString())
                .header(HEADER_RATE_LIMIT_PERIOD, period.inWholeMilliseconds.toString())
                .build()
            chain.proceed(newRequest)
        } else {
            chain.proceed(request)
        }
    }
    return addInterceptor(RateLimitInterceptor)
}
