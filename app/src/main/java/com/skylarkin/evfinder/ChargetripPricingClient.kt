package com.skylarkin.evfinder

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.pow

class ChargetripPricingClient(
    private val clientId: String,
    private val appId: String,
    private val storageDir: File?
) {
    private companion object {
        const val ENDPOINT = "https://api.chargetrip.io/graphql"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 10_000
        const val CACHE_FILE_PREFIX = "chargetrip_operator_prices_cache_v1"
        const val CACHE_TTL_MS = 28L * 24L * 60L * 60L * 1000L
        const val COUNTRY_RESOLVE_DISTANCE_METERS = 10_000
        const val STATION_PAGE_SIZE = 200
        const val OPERATOR_PAGE_SIZE = 1000
        const val OPERATOR_MAX_PAGES = 20
    }

    data class Match(
        val euroPerKwh: Double,
        val originalPricePerKwh: Double,
        val originalCurrency: String,
        val sourceLabel: String
    )

    private data class OperatorPrice(
        val operatorName: String,
        val euroPerKwh: Double,
        val originalPricePerKwh: Double,
        val originalCurrency: String,
        val sampleCount: Int
    )

    private data class CountryCache(
        val countryCode: String,
        val updatedAtEpochMs: Long,
        val pricesByNormalizedOperator: Map<String, OperatorPrice>
    )

    @Volatile
    private var inMemoryCache: CountryCache? = null

    suspend fun findBestMatch(
        operatorName: String?,
        siteTitle: String?,
        latitude: Double,
        longitude: Double,
        gbpToEurRate: Double?
    ): Match? {
        if (clientId.isBlank() || appId.isBlank()) return null

        val countryCode = resolveCountryCode(latitude, longitude).ifBlank {
            Locale.getDefault().country.uppercase(Locale.US)
        }
        if (countryCode.isBlank()) return null

        val cache = ensureCountryCache(countryCode, gbpToEurRate)
        if (cache.pricesByNormalizedOperator.isEmpty()) return null

        val normalizedOperator = normalize(operatorName)
        val normalizedSite = normalize(siteTitle)
        val best = pickBestOperatorPrice(
            pricesByOperator = cache.pricesByNormalizedOperator,
            normalizedOperator = normalizedOperator,
            normalizedSite = normalizedSite
        ) ?: return null

        return Match(
            euroPerKwh = best.euroPerKwh,
            originalPricePerKwh = best.originalPricePerKwh,
            originalCurrency = best.originalCurrency,
            sourceLabel = "Chargetrip cache ${cache.countryCode}"
        )
    }

    private fun ensureCountryCache(
        countryCode: String,
        gbpToEurRate: Double?
    ): CountryCache {
        val now = System.currentTimeMillis()

        inMemoryCache?.let { memory ->
            if (memory.countryCode == countryCode && isFresh(memory.updatedAtEpochMs, now)) {
                return memory
            }
        }

        val diskCache = readCacheFromDisk(countryCode)
        if (diskCache != null && isFresh(diskCache.updatedAtEpochMs, now)) {
            inMemoryCache = diskCache
            return diskCache
        }

        val fetchedPrices = runCatching {
            fetchCountryOperatorPriceMap(
                countryCode = countryCode,
                gbpToEurRate = gbpToEurRate
            )
        }.getOrDefault(emptyMap())

        val refreshed = CountryCache(
            countryCode = countryCode,
            updatedAtEpochMs = now,
            pricesByNormalizedOperator = fetchedPrices
        )
        inMemoryCache = refreshed
        writeCacheToDisk(refreshed)
        return refreshed
    }

    private fun isFresh(updatedAtMs: Long, nowMs: Long): Boolean = nowMs - updatedAtMs <= CACHE_TTL_MS

    private fun cacheFile(countryCode: String): File? {
        val normalized = countryCode.uppercase(Locale.US).ifBlank { "XX" }
        return storageDir?.resolve("${CACHE_FILE_PREFIX}_${normalized}.json")
    }

    private fun readCacheFromDisk(countryCode: String): CountryCache? {
        val file = cacheFile(countryCode) ?: return null
        if (!file.exists()) return null
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val parsedCountryCode = root.optString("countryCode", "").uppercase(Locale.US)
            val updatedAt = root.optLong("updatedAtEpochMs", 0L)
            val prices = root.optJSONArray("operatorPrices") ?: return@runCatching null

            val map = mutableMapOf<String, OperatorPrice>()
            for (i in 0 until prices.length()) {
                val obj = prices.optJSONObject(i) ?: continue
                val operatorName = obj.optString("operatorName", "").trim()
                val euro = obj.optDouble("euroPerKwh", Double.NaN)
                val original = obj.optDouble("originalPricePerKwh", Double.NaN)
                val currency = obj.optString("originalCurrency", "").uppercase(Locale.US)
                val sampleCount = obj.optInt("sampleCount", 1)
                if (operatorName.isBlank() || euro.isNaN() || original.isNaN() || currency.isBlank()) continue
                map[normalize(operatorName)] = OperatorPrice(
                    operatorName = operatorName,
                    euroPerKwh = euro,
                    originalPricePerKwh = original,
                    originalCurrency = currency,
                    sampleCount = sampleCount
                )
            }

            if (parsedCountryCode.isBlank() || updatedAt <= 0L) return@runCatching null
            CountryCache(parsedCountryCode, updatedAt, map)
        }.getOrNull()
    }

    private fun writeCacheToDisk(cache: CountryCache) {
        val file = cacheFile(cache.countryCode) ?: return
        runCatching {
            val array = org.json.JSONArray()
            cache.pricesByNormalizedOperator.values
                .sortedBy { it.operatorName.lowercase(Locale.US) }
                .forEach { value ->
                    array.put(
                        JSONObject()
                            .put("operatorName", value.operatorName)
                            .put("euroPerKwh", value.euroPerKwh)
                            .put("originalPricePerKwh", value.originalPricePerKwh)
                            .put("originalCurrency", value.originalCurrency)
                            .put("sampleCount", value.sampleCount)
                    )
                }
            val root = JSONObject()
                .put("countryCode", cache.countryCode)
                .put("updatedAtEpochMs", cache.updatedAtEpochMs)
                .put("operatorPrices", array)

            file.parentFile?.mkdirs()
            file.writeText(root.toString(), Charsets.UTF_8)
        }
    }

    private fun resolveCountryCode(latitude: Double, longitude: Double): String {
        val query = """
            query {
              stationAround(
                filter: {
                  location: { type: Point, coordinates: [${formatCoordinate(longitude)}, ${formatCoordinate(latitude)}] },
                  distance: $COUNTRY_RESOLVE_DISTANCE_METERS
                }
              ) {
                country_code
                country
                physical_address { country }
              }
            }
        """.trimIndent()

        val data = runCatching {
            postGraphQl(JSONObject().put("query", query).toString())
                .optJSONObject("data")
        }.getOrNull() ?: return ""

        val stations = data.optJSONArray("stationAround") ?: return ""
        for (i in 0 until stations.length()) {
            val station = stations.optJSONObject(i) ?: continue
            val direct = station.optString("country_code", "").trim()
            if (isCountryCode(direct)) return direct.uppercase(Locale.US)

            val fallback = station.optString("country", "").trim()
            if (isCountryCode(fallback)) return fallback.uppercase(Locale.US)

            val addrCountry = station.optJSONObject("physical_address")
                ?.optString("country", "")
                ?.trim()
                .orEmpty()
            if (isCountryCode(addrCountry)) return addrCountry.uppercase(Locale.US)
        }

        return ""
    }

    private fun fetchCountryOperatorPriceMap(
        countryCode: String,
        gbpToEurRate: Double?
    ): Map<String, OperatorPrice> {
        val operators = fetchOperatorNames(countryCode)
        if (operators.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, OperatorPrice>()
        for (operatorName in operators) {
            val price = fetchOperatorAverageFromSingleStationPage(
                countryCode = countryCode,
                operatorName = operatorName,
                gbpToEurRate = gbpToEurRate
            ) ?: continue
            result[normalize(operatorName)] = price
        }
        return result
    }

    private fun fetchOperatorNames(countryCode: String): List<String> {
        val allNames = mutableSetOf<String>()
        for (page in 0 until OPERATOR_MAX_PAGES) {
            val query = """
                query {
                  operatorList(
                    filter: { countries: [$countryCode] },
                    size: $OPERATOR_PAGE_SIZE,
                    page: $page
                  ) {
                    name
                  }
                }
            """.trimIndent()

            val data = runCatching {
                postGraphQl(JSONObject().put("query", query).toString()).optJSONObject("data")
            }.getOrNull() ?: break

            val items = data.optJSONArray("operatorList") ?: break
            if (items.length() == 0) break

            for (i in 0 until items.length()) {
                val name = items.optJSONObject(i)?.optString("name", "")?.trim().orEmpty()
                if (name.isNotBlank()) allNames.add(name)
            }

            if (items.length() < OPERATOR_PAGE_SIZE) break
        }
        return allNames.toList()
    }

    private fun fetchOperatorAverageFromSingleStationPage(
        countryCode: String,
        operatorName: String,
        gbpToEurRate: Double?
    ): OperatorPrice? {
        val escapedSearch = operatorName.replace("\"", "\\\"")
        val query = """
            query {
              stationList(
                filter: { countries: [$countryCode] },
                search: "$escapedSearch",
                size: $STATION_PAGE_SIZE,
                page: 0
              ) {
                operator { name }
                chargers { price }
              }
            }
        """.trimIndent()

        val data = runCatching {
            postGraphQl(JSONObject().put("query", query).toString()).optJSONObject("data")
        }.getOrNull() ?: return null

        val stations = data.optJSONArray("stationList") ?: return null
        if (stations.length() == 0) return null

        val normalizedTarget = normalize(operatorName)
        var sumEuro = 0.0
        var sumOriginal = 0.0
        var sampleCount = 0
        var currency: String? = null

        for (i in 0 until stations.length()) {
            val station = stations.optJSONObject(i) ?: continue
            val stationOperatorName = station.optJSONObject("operator")?.optString("name", "")?.trim().orEmpty()
            val normalizedStationOperator = normalize(stationOperatorName)
            if (normalizedStationOperator != normalizedTarget) continue

            val chargers = station.optJSONArray("chargers") ?: continue
            val parsed = findPriceInChargers(chargers, gbpToEurRate) ?: continue

            if (currency != null && currency != parsed.third) continue
            currency = parsed.third
            sumEuro += parsed.first
            sumOriginal += parsed.second
            sampleCount += 1
        }

        if (sampleCount == 0 || currency == null) return null

        return OperatorPrice(
            operatorName = operatorName,
            euroPerKwh = sumEuro / sampleCount,
            originalPricePerKwh = sumOriginal / sampleCount,
            originalCurrency = currency,
            sampleCount = sampleCount
        )
    }

    private fun findPriceInChargers(
        chargers: org.json.JSONArray,
        gbpToEurRate: Double?
    ): Triple<Double, Double, String>? {
        for (i in 0 until chargers.length()) {
            val charger = chargers.optJSONObject(i) ?: continue
            val raw = charger.optString("price", "").trim()
            if (raw.isBlank()) continue
            val parsed = parsePricePerKwh(raw, gbpToEurRate) ?: continue
            return parsed
        }
        return null
    }

    private fun parsePricePerKwh(text: String, gbpToEurRate: Double?): Triple<Double, Double, String>? {
        val normalized = text
            .replace(',', '.')
            .replace("\u00e2\u201a\u00ac", "\u20ac")
            .replace("\u00c2\u00a3", "\u00a3")
            .replace("\u00e2\u0082\u00ac", "\u20ac")
            .lowercase(Locale.US)

        if (!normalized.contains("kwh")) return null

        val hasEur = normalized.contains("€") || normalized.contains("eur")
        val hasGbp = normalized.contains("£") || normalized.contains("gbp")
        val value = Regex("(\\d+(?:\\.\\d+)?)").find(normalized)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null

        return when {
            hasEur -> Triple(value, value, "EUR")
            hasGbp -> {
                val rate = gbpToEurRate ?: return null
                Triple(value * rate, value, "GBP")
            }
            else -> Triple(value, value, "EUR")
        }
    }

    private fun pickBestOperatorPrice(
        pricesByOperator: Map<String, OperatorPrice>,
        normalizedOperator: String,
        normalizedSite: String
    ): OperatorPrice? {
        var best: OperatorPrice? = null
        var bestScore = Int.MIN_VALUE

        for ((normalizedName, price) in pricesByOperator) {
            var score = 0
            if (normalizedOperator.isNotBlank()) {
                if (normalizedName == normalizedOperator) score += 100
                else if (normalizedName.contains(normalizedOperator) || normalizedOperator.contains(normalizedName)) score += 70
            }
            if (normalizedSite.isNotBlank()) {
                if (normalizedSite.contains(normalizedName)) score += 35
            }
            score += price.sampleCount.coerceAtMost(20)

            if (score > bestScore) {
                bestScore = score
                best = price
            }
        }

        if (bestScore <= 0) return null
        return best
    }

    private fun postGraphQl(body: String): JSONObject {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-client-id", clientId)
            setRequestProperty("x-app-id", appId)
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }

        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(response)
            if (json.has("errors")) {
                throw IllegalStateException("Chargetrip GraphQL returned errors.")
            }
            json
        } finally {
            connection.disconnect()
        }
    }

    private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.6f", value)

    private fun normalize(value: String?): String {
        return value
            ?.lowercase(Locale.US)
            ?.replace(Regex("[^a-z0-9]+"), " ")
            ?.trim()
            .orEmpty()
    }

    private fun isCountryCode(value: String): Boolean {
        return value.length == 2 && value.all { it.isLetter() }
    }

    @Suppress("unused")
    private fun haversineDistanceKm(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(toLat - fromLat)
        val dLon = Math.toRadians(toLon - fromLon)
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)

        val a = kotlin.math.sin(dLat / 2).pow(2.0) +
            kotlin.math.sin(dLon / 2).pow(2.0) * kotlin.math.cos(lat1) * kotlin.math.cos(lat2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return earthRadiusKm * c
    }
}
