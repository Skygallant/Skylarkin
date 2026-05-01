package com.skylarkin.evfinder

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class FrankfurterFxRateProvider : FxRateProvider {

    private data class CachedRate(
        val rate: Double,
        val fetchedAtMs: Long
    )

    private companion object {
        const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    }

    private val cacheMutex = Mutex()
    private val cache = mutableMapOf<String, CachedRate>()

    override suspend fun getRate(baseCurrency: String, quoteCurrency: String): Double? {
        val base = baseCurrency.uppercase(Locale.US)
        val quote = quoteCurrency.uppercase(Locale.US)
        if (base == quote) return 1.0

        val cacheKey = "$base->$quote"
        val now = System.currentTimeMillis()
        cacheMutex.withLock {
            val cached = cache[cacheKey]
            if (cached != null && now - cached.fetchedAtMs < CACHE_TTL_MS) {
                return cached.rate
            }
        }

        val fetchedRate = runCatching { fetchRateV2(base, quote) }.getOrNull()
            ?: runCatching { fetchRateV1(base, quote) }.getOrNull()
            ?: return null

        cacheMutex.withLock {
            cache[cacheKey] = CachedRate(rate = fetchedRate, fetchedAtMs = System.currentTimeMillis())
        }
        return fetchedRate
    }

    private suspend fun fetchRateV2(base: String, quote: String): Double? = withContext(Dispatchers.IO) {
        val url = Uri.parse("https://api.frankfurter.dev/v2/rate/$base/$quote")
            .buildUpon()
            .appendQueryParameter("providers", "ECB")
            .build()
            .toString()

        val json = fetchJson(url)
        val value = json.optDouble("rate", Double.NaN)
        return@withContext value.takeIf { !it.isNaN() && it > 0.0 }
    }

    private suspend fun fetchRateV1(base: String, quote: String): Double? = withContext(Dispatchers.IO) {
        val url = Uri.parse("https://api.frankfurter.dev/v1/latest")
            .buildUpon()
            .appendQueryParameter("base", base)
            .appendQueryParameter("symbols", quote)
            .build()
            .toString()

        val json = fetchJson(url)
        val rates = json.optJSONObject("rates") ?: return@withContext null
        val value = rates.optDouble(quote, Double.NaN)
        return@withContext value.takeIf { !it.isNaN() && it > 0.0 }
    }

    private fun fetchJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            connectTimeout = 10_000
            readTimeout = 10_000
        }

        return try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader().use(BufferedReader::readText)
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Frankfurter API error ${connection.responseCode}: $response")
            }
            JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }
}
