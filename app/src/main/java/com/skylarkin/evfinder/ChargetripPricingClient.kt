package com.skylarkin.evfinder

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class ChargetripPricingClient(
    private val clientId: String,
    private val appId: String,
    private val appIdentifier: String,
    private val appFingerprint: String,
    private val storageDir: File?
) {
    private companion object {
        const val CHARGETRIP_ENDPOINT = "https://api.chargetrip.io/graphql"
        const val CONNECT_TIMEOUT_MS = 7_000
        const val READ_TIMEOUT_MS = 7_000
        const val CACHE_TTL_MS = 28L * 24L * 60L * 60L * 1000L
        const val COUNTRY_LOOKUP_CACHE_TTL_MS = 6L * 60L * 60L * 1000L
        const val NOMINATIM_REVERSE_ENDPOINT = "https://nominatim.openstreetmap.org/reverse"
        const val OPERATOR_PAGE_SIZE = 300
        const val STATION_PAGE_SIZE = 20
        const val REQUIRED_SAMPLES_PER_OPERATOR = 10
        const val OPERATOR_LIST_CACHE_PREFIX = "chargetrip_operator_list_cache_v1"
        const val OPERATOR_PRICE_BUILD_CACHE_PREFIX = "chargetrip_operator_price_build_cache_v1"
    }

    data class Match(
        val euroPerKwh: Double,
        val originalPricePerKwh: Double,
        val originalCurrency: String,
        val sourceLabel: String
    )

    private data class CountryLookupEntry(
        val countryCode: String,
        val updatedAtEpochMs: Long
    )

    private data class OperatorRef(
        val id: String?,
        val name: String,
        val normalizedName: String
    )

    private data class CountryOperatorListCache(
        val countryCode: String,
        val updatedAtEpochMs: Long,
        val operatorsByNormalizedName: Map<String, OperatorRef>,
        val uniqueKeywordToOperatorNormalizedName: Map<String, String>
    )

    private data class OperatorPriceSample(
        val stationKey: String,
        val euroPerKwh: Double,
        val originalPricePerKwh: Double,
        val originalCurrency: String,
        val capturedAtEpochMs: Long
    )

    private data class OperatorPriceEntry(
        val operatorId: String?,
        val operatorName: String,
        val normalizedOperatorName: String,
        val samples: MutableList<OperatorPriceSample>,
        var updatedAtEpochMs: Long
    )

    private data class CountryOperatorPriceBuildCache(
        val countryCode: String,
        var updatedAtEpochMs: Long,
        val entriesByNormalizedName: MutableMap<String, OperatorPriceEntry>
    )

    private val countryLookupCache = ConcurrentHashMap<String, CountryLookupEntry>()

    @Volatile
    private var inMemoryOperatorListCache: CountryOperatorListCache? = null

    @Volatile
    private var inMemoryBuildCache: CountryOperatorPriceBuildCache? = null

    suspend fun resolveCountryCodeForLocation(latitude: Double, longitude: Double): String {
        val key = countryLookupKey(latitude, longitude)
        val now = System.currentTimeMillis()
        countryLookupCache[key]?.let { cached ->
            if (now - cached.updatedAtEpochMs <= COUNTRY_LOOKUP_CACHE_TTL_MS && isCountryCode(cached.countryCode)) {
                return cached.countryCode
            }
        }

        val nominatim = resolveCountryCodeViaNominatim(latitude, longitude)
        if (isCountryCode(nominatim)) {
            val normalized = nominatim.uppercase(Locale.US)
            countryLookupCache[key] = CountryLookupEntry(normalized, now)
            return normalized
        }

        return Locale.getDefault().country.uppercase(Locale.US)
    }

    suspend fun resolveCountryCodeViaNominatimOnDemand(latitude: Double, longitude: Double): String {
        val code = resolveCountryCodeViaNominatim(latitude, longitude)
        if (isCountryCode(code)) {
            val normalized = code.uppercase(Locale.US)
            countryLookupCache[countryLookupKey(latitude, longitude)] = CountryLookupEntry(normalized, System.currentTimeMillis())
            return normalized
        }
        return ""
    }

    suspend fun warmCountryCacheForLocation(
        latitude: Double,
        longitude: Double,
        countryCodeHint: String?
    ) {
        if (clientId.isBlank() || appId.isBlank()) return
        val countryCode = resolveCountryCodeWithHint(countryCodeHint, latitude, longitude)
        if (countryCode.isBlank()) return
        ensureCountryOperatorListCache(countryCode)
        loadBuildCache(countryCode)
    }

    suspend fun findBestMatch(
        operatorName: String?,
        stationName: String?,
        stationExternalId: String?,
        latitude: Double,
        longitude: Double,
        countryCodeHint: String? = null,
        gbpToEurRate: Double?
    ): Match? {
        if (clientId.isBlank() || appId.isBlank()) return null

        val normalizedOperator = normalize(operatorName)
        if (normalizedOperator.isBlank()) return null

        val countryCode = resolveCountryCodeWithHint(countryCodeHint, latitude, longitude)
        if (countryCode.isBlank()) return null

        val operatorList = ensureCountryOperatorListCache(countryCode)
        val operatorRef = resolveOperatorRefFromOcmOperator(
            normalizedOcmOperatorName = normalizedOperator,
            operatorListCache = operatorList
        ) ?: return null

        val buildCache = loadBuildCache(countryCode)
        val now = System.currentTimeMillis()
        if (!isFresh(buildCache.updatedAtEpochMs, now)) {
            buildCache.entriesByNormalizedName.clear()
            buildCache.updatedAtEpochMs = now
        }

        val entry = buildCache.entriesByNormalizedName.getOrPut(operatorRef.normalizedName) {
            OperatorPriceEntry(
                operatorId = operatorRef.id,
                operatorName = operatorRef.name,
                normalizedOperatorName = operatorRef.normalizedName,
                samples = mutableListOf(),
                updatedAtEpochMs = now
            )
        }

        if (!isFresh(entry.updatedAtEpochMs, now)) {
            entry.samples.clear()
            entry.updatedAtEpochMs = now
        }

        if (entry.samples.size < REQUIRED_SAMPLES_PER_OPERATOR) {
            val stationKey = buildStationKey(stationExternalId, stationName)
            val alreadySampled = entry.samples.any { it.stationKey == stationKey }
            if (!alreadySampled) {
                val searchQuery = stationName?.trim().takeUnless { it.isNullOrBlank() } ?: operatorRef.name
                val sample = fetchSingleStationSample(
                    operatorRef = operatorRef,
                    searchQuery = searchQuery,
                    gbpToEurRate = gbpToEurRate,
                    stationKey = stationKey,
                    now = now
                )
                if (sample != null) {
                    entry.samples.add(sample)
                    entry.updatedAtEpochMs = now
                    buildCache.updatedAtEpochMs = now
                    writeBuildCacheToDisk(buildCache)
                }
            }
        }

        if (entry.samples.size < REQUIRED_SAMPLES_PER_OPERATOR) {
            return null
        }

        val avgEuro = entry.samples.map { it.euroPerKwh }.average()
        val avgOriginal = entry.samples.map { it.originalPricePerKwh }.average()
        val dominantCurrency = entry.samples
            .groupingBy { it.originalCurrency }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: "EUR"

        return Match(
            euroPerKwh = avgEuro,
            originalPricePerKwh = avgOriginal,
            originalCurrency = dominantCurrency,
            sourceLabel = "Chargetrip build-cache $countryCode (${entry.samples.size}/$REQUIRED_SAMPLES_PER_OPERATOR samples)"
        )
    }

    private suspend fun resolveCountryCodeWithHint(countryCodeHint: String?, latitude: Double, longitude: Double): String {
        val hint = countryCodeHint?.trim()?.uppercase(Locale.US).orEmpty()
        if (isCountryCode(hint)) return hint
        val resolved = resolveCountryCodeForLocation(latitude, longitude)
        return if (isCountryCode(resolved)) resolved else ""
    }

    private fun ensureCountryOperatorListCache(countryCode: String): CountryOperatorListCache {
        val now = System.currentTimeMillis()
        val inMemory = inMemoryOperatorListCache
        if (inMemory != null && inMemory.countryCode == countryCode && isFresh(inMemory.updatedAtEpochMs, now) && inMemory.operatorsByNormalizedName.isNotEmpty()) {
            return inMemory
        }

        val disk = readOperatorListCacheFromDisk(countryCode)
        if (disk != null && isFresh(disk.updatedAtEpochMs, now) && disk.operatorsByNormalizedName.isNotEmpty()) {
            inMemoryOperatorListCache = disk
            return disk
        }

        val fetched = fetchOperatorListFromApi(countryCode)
        inMemoryOperatorListCache = fetched
        writeOperatorListCacheToDisk(fetched)
        return fetched
    }

    private fun loadBuildCache(countryCode: String): CountryOperatorPriceBuildCache {
        val now = System.currentTimeMillis()
        val inMemory = inMemoryBuildCache
        if (inMemory != null && inMemory.countryCode == countryCode) {
            if (!isFresh(inMemory.updatedAtEpochMs, now)) {
                inMemory.entriesByNormalizedName.clear()
                inMemory.updatedAtEpochMs = now
                writeBuildCacheToDisk(inMemory)
            }
            return inMemory
        }

        val disk = readBuildCacheFromDisk(countryCode)
        if (disk != null) {
            if (!isFresh(disk.updatedAtEpochMs, now)) {
                disk.entriesByNormalizedName.clear()
                disk.updatedAtEpochMs = now
                writeBuildCacheToDisk(disk)
            }
            inMemoryBuildCache = disk
            return disk
        }

        val empty = CountryOperatorPriceBuildCache(
            countryCode = countryCode,
            updatedAtEpochMs = now,
            entriesByNormalizedName = mutableMapOf()
        )
        inMemoryBuildCache = empty
        writeBuildCacheToDisk(empty)
        return empty
    }

    private fun fetchOperatorListFromApi(countryCode: String): CountryOperatorListCache {
        val map = linkedMapOf<String, OperatorRef>()
        var page = 0

        while (true) {
            val query = """
                query {
                  operatorList(
                    filter: { countries: [$countryCode] },
                    size: $OPERATOR_PAGE_SIZE,
                    page: $page
                  ) {
                    id
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
                val op = items.optJSONObject(i) ?: continue
                val name = op.optString("name", "").trim()
                if (name.isBlank()) continue
                val normalized = normalize(name)
                if (normalized.isBlank()) continue
                val id = op.optString("id", "").trim().ifBlank { null }
                map.putIfAbsent(normalized, OperatorRef(id = id, name = name, normalizedName = normalized))
            }

            if (items.length() < OPERATOR_PAGE_SIZE) break
            page += 1
        }

        return CountryOperatorListCache(
            countryCode = countryCode,
            updatedAtEpochMs = System.currentTimeMillis(),
            operatorsByNormalizedName = map,
            uniqueKeywordToOperatorNormalizedName = buildUniqueKeywordIndex(map)
        )
    }

    private fun fetchSingleStationSample(
        operatorRef: OperatorRef,
        searchQuery: String,
        gbpToEurRate: Double?,
        stationKey: String,
        now: Long
    ): OperatorPriceSample? {
        val escapedSearch = searchQuery.replace("\"", "\\\"")
        val query = """
            query {
              stationList(
                search: "$escapedSearch",
                size: $STATION_PAGE_SIZE,
                page: 0
              ) {
                name
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

        for (i in 0 until stations.length()) {
            val station = stations.optJSONObject(i) ?: continue
            val stationOperatorName = station.optJSONObject("operator")?.optString("name", "")?.trim().orEmpty()
            if (!operatorsLikelyMatch(operatorRef.normalizedName, normalize(stationOperatorName))) continue

            val chargers = station.optJSONArray("chargers") ?: continue
            val parsed = findPriceInChargers(chargers, gbpToEurRate) ?: continue

            return OperatorPriceSample(
                stationKey = stationKey,
                euroPerKwh = parsed.first,
                originalPricePerKwh = parsed.second,
                originalCurrency = parsed.third,
                capturedAtEpochMs = now
            )
        }

        return null
    }

    private fun findPriceInChargers(
        chargers: JSONArray,
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

        val hasEur = normalized.contains("\u20ac") || normalized.contains("eur")
        val hasGbp = normalized.contains("\u00a3") || normalized.contains("gbp")
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

    private fun postGraphQl(body: String): JSONObject {
        val connection = (URL(CHARGETRIP_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-client-id", clientId)
            setRequestProperty("x-app-id", appId)
            if (appIdentifier.isNotBlank()) {
                setRequestProperty("x-app-identifier", appIdentifier)
            }
            if (appFingerprint.isNotBlank()) {
                setRequestProperty("x-app-fingerprint", appFingerprint)
            }
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }

        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(response)
            if (json.has("errors")) {
                val errors = json.optJSONArray("errors")
                val summarized = buildList {
                    if (errors != null) {
                        for (i in 0 until errors.length()) {
                            val item = errors.optJSONObject(i) ?: continue
                            val message = item.optString("message", "").trim()
                            if (message.isNotBlank()) add(message)
                        }
                    }
                }
                val summary = summarized.distinct().take(3).joinToString(" | ").ifBlank {
                    "Chargetrip GraphQL returned errors."
                }
                throw IllegalStateException(summary)
            }
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}: ${response.take(220)}")
            }
            json
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveCountryCodeViaNominatim(latitude: Double, longitude: Double): String {
        val url = "$NOMINATIM_REVERSE_ENDPOINT?format=jsonv2&lat=${formatCoordinate(latitude)}&lon=${formatCoordinate(longitude)}&zoom=3&addressdetails=1"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SkylarkinEVFinder/1.0 (country-cache)")
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }

        return try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (connection.responseCode !in 200..299) return ""
            val json = JSONObject(response)
            val address = json.optJSONObject("address")
            val countryCode = address?.optString("country_code", "")?.trim().orEmpty()
            if (isCountryCode(countryCode)) countryCode.uppercase(Locale.US) else ""
        } catch (_: Exception) {
            ""
        } finally {
            connection.disconnect()
        }
    }

    private fun operatorListCacheFile(countryCode: String): File? {
        val normalized = countryCode.uppercase(Locale.US).ifBlank { "XX" }
        return storageDir?.resolve("${OPERATOR_LIST_CACHE_PREFIX}_${normalized}.json")
    }

    private fun buildCacheFile(countryCode: String): File? {
        val normalized = countryCode.uppercase(Locale.US).ifBlank { "XX" }
        return storageDir?.resolve("${OPERATOR_PRICE_BUILD_CACHE_PREFIX}_${normalized}.json")
    }

    private fun readOperatorListCacheFromDisk(countryCode: String): CountryOperatorListCache? {
        val file = operatorListCacheFile(countryCode) ?: return null
        if (!file.exists()) return null

        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val parsedCountryCode = root.optString("countryCode", "").uppercase(Locale.US)
            val updatedAt = root.optLong("updatedAtEpochMs", 0L)
            val operators = root.optJSONArray("operators") ?: return@runCatching null

            val map = linkedMapOf<String, OperatorRef>()
            for (i in 0 until operators.length()) {
                val obj = operators.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").trim().ifBlank { null }
                val name = obj.optString("name", "").trim()
                val normalizedName = normalize(name)
                if (name.isBlank() || normalizedName.isBlank()) continue
                map[normalizedName] = OperatorRef(id = id, name = name, normalizedName = normalizedName)
            }
            val keywordsJson = root.optJSONArray("uniqueKeywords")
            val keywordMap = mutableMapOf<String, String>()
            if (keywordsJson != null) {
                for (i in 0 until keywordsJson.length()) {
                    val obj = keywordsJson.optJSONObject(i) ?: continue
                    val keyword = normalize(obj.optString("keyword", ""))
                    val normalizedOperatorName = normalize(obj.optString("operatorNormalizedName", ""))
                    if (keyword.isBlank() || normalizedOperatorName.isBlank()) continue
                    if (!map.containsKey(normalizedOperatorName)) continue
                    keywordMap[keyword] = normalizedOperatorName
                }
            }
            val effectiveKeywordMap =
                if (keywordMap.isNotEmpty()) keywordMap else buildUniqueKeywordIndex(map)

            if (!isCountryCode(parsedCountryCode) || updatedAt <= 0L) return@runCatching null
            CountryOperatorListCache(
                countryCode = parsedCountryCode,
                updatedAtEpochMs = updatedAt,
                operatorsByNormalizedName = map,
                uniqueKeywordToOperatorNormalizedName = effectiveKeywordMap
            )
        }.getOrNull()
    }

    private fun writeOperatorListCacheToDisk(cache: CountryOperatorListCache) {
        val file = operatorListCacheFile(cache.countryCode) ?: return
        runCatching {
            val operatorsArray = JSONArray()
            cache.operatorsByNormalizedName.values
                .sortedBy { it.name.lowercase(Locale.US) }
                .forEach { op ->
                    operatorsArray.put(
                        JSONObject()
                            .put("id", op.id ?: "")
                            .put("name", op.name)
                    )
                }
            val keywordsArray = JSONArray()
            cache.uniqueKeywordToOperatorNormalizedName.entries
                .sortedBy { it.key }
                .forEach { (keyword, normalizedOperatorName) ->
                    keywordsArray.put(
                        JSONObject()
                            .put("keyword", keyword)
                            .put("operatorNormalizedName", normalizedOperatorName)
                    )
                }

            val root = JSONObject()
                .put("countryCode", cache.countryCode)
                .put("updatedAtEpochMs", cache.updatedAtEpochMs)
                .put("operators", operatorsArray)
                .put("uniqueKeywords", keywordsArray)

            file.parentFile?.mkdirs()
            file.writeText(root.toString(), Charsets.UTF_8)
        }
    }

    private fun readBuildCacheFromDisk(countryCode: String): CountryOperatorPriceBuildCache? {
        val file = buildCacheFile(countryCode) ?: return null
        if (!file.exists()) return null

        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val parsedCountryCode = root.optString("countryCode", "").uppercase(Locale.US)
            val updatedAt = root.optLong("updatedAtEpochMs", 0L)
            val entriesArray = root.optJSONArray("operatorEntries") ?: JSONArray()

            val map = mutableMapOf<String, OperatorPriceEntry>()
            for (i in 0 until entriesArray.length()) {
                val obj = entriesArray.optJSONObject(i) ?: continue
                val operatorId = obj.optString("operatorId", "").trim().ifBlank { null }
                val operatorName = obj.optString("operatorName", "").trim()
                val normalizedOperatorName = normalize(operatorName)
                if (normalizedOperatorName.isBlank()) continue
                val entryUpdatedAt = obj.optLong("updatedAtEpochMs", updatedAt)
                val samplesJson = obj.optJSONArray("samples") ?: JSONArray()
                val samples = mutableListOf<OperatorPriceSample>()
                for (j in 0 until samplesJson.length()) {
                    val s = samplesJson.optJSONObject(j) ?: continue
                    val stationKey = s.optString("stationKey", "").trim()
                    val eur = s.optDouble("euroPerKwh", Double.NaN)
                    val original = s.optDouble("originalPricePerKwh", Double.NaN)
                    val currency = s.optString("originalCurrency", "").trim().uppercase(Locale.US)
                    val captured = s.optLong("capturedAtEpochMs", entryUpdatedAt)
                    if (stationKey.isBlank() || eur.isNaN() || original.isNaN() || currency.isBlank()) continue
                    samples.add(
                        OperatorPriceSample(
                            stationKey = stationKey,
                            euroPerKwh = eur,
                            originalPricePerKwh = original,
                            originalCurrency = currency,
                            capturedAtEpochMs = captured
                        )
                    )
                }
                map[normalizedOperatorName] = OperatorPriceEntry(
                    operatorId = operatorId,
                    operatorName = operatorName.ifBlank { normalizedOperatorName },
                    normalizedOperatorName = normalizedOperatorName,
                    samples = samples,
                    updatedAtEpochMs = entryUpdatedAt
                )
            }

            if (!isCountryCode(parsedCountryCode) || updatedAt <= 0L) return@runCatching null
            CountryOperatorPriceBuildCache(parsedCountryCode, updatedAt, map)
        }.getOrNull()
    }

    private fun writeBuildCacheToDisk(cache: CountryOperatorPriceBuildCache) {
        val file = buildCacheFile(cache.countryCode) ?: return
        runCatching {
            val entriesArray = JSONArray()
            cache.entriesByNormalizedName.values
                .sortedBy { it.operatorName.lowercase(Locale.US) }
                .forEach { entry ->
                    val samplesArray = JSONArray()
                    entry.samples.forEach { sample ->
                        samplesArray.put(
                            JSONObject()
                                .put("stationKey", sample.stationKey)
                                .put("euroPerKwh", sample.euroPerKwh)
                                .put("originalPricePerKwh", sample.originalPricePerKwh)
                                .put("originalCurrency", sample.originalCurrency)
                                .put("capturedAtEpochMs", sample.capturedAtEpochMs)
                        )
                    }
                    entriesArray.put(
                        JSONObject()
                            .put("operatorId", entry.operatorId ?: "")
                            .put("operatorName", entry.operatorName)
                            .put("updatedAtEpochMs", entry.updatedAtEpochMs)
                            .put("samples", samplesArray)
                    )
                }

            val root = JSONObject()
                .put("countryCode", cache.countryCode)
                .put("updatedAtEpochMs", cache.updatedAtEpochMs)
                .put("requiredSamplesPerOperator", REQUIRED_SAMPLES_PER_OPERATOR)
                .put("operatorEntries", entriesArray)

            file.parentFile?.mkdirs()
            file.writeText(root.toString(), Charsets.UTF_8)
        }
    }

    private fun buildStationKey(stationExternalId: String?, stationName: String?): String {
        val idKey = stationExternalId?.trim().orEmpty()
        if (idKey.isNotBlank()) return "id:$idKey"
        val nameKey = normalize(stationName)
        return if (nameKey.isBlank()) "unknown" else "name:$nameKey"
    }

    private fun resolveOperatorRefFromOcmOperator(
        normalizedOcmOperatorName: String,
        operatorListCache: CountryOperatorListCache
    ): OperatorRef? {
        operatorListCache.operatorsByNormalizedName[normalizedOcmOperatorName]?.let { return it }

        val tokens = normalizedOcmOperatorName
            .split(' ')
            .map { it.trim() }
            .filter { it.length >= 3 }
            .toSet()
        if (tokens.isEmpty()) return null

        val matchedNormalizedOperators = tokens
            .mapNotNull { token -> operatorListCache.uniqueKeywordToOperatorNormalizedName[token] }
            .distinct()

        if (matchedNormalizedOperators.size != 1) return null
        return operatorListCache.operatorsByNormalizedName[matchedNormalizedOperators.first()]
    }

    private fun buildUniqueKeywordIndex(
        operatorsByNormalizedName: Map<String, OperatorRef>
    ): Map<String, String> {
        val tokenToOperators = mutableMapOf<String, MutableSet<String>>()
        operatorsByNormalizedName.values.forEach { operator ->
            val tokens = operator.normalizedName
                .split(' ')
                .map { it.trim() }
                .filter { it.length >= 3 && it !in genericOperatorTokens }
                .toSet()
            tokens.forEach { token ->
                tokenToOperators.getOrPut(token) { mutableSetOf() }.add(operator.normalizedName)
            }
        }

        return tokenToOperators
            .filter { (_, owners) -> owners.size == 1 }
            .mapValues { (_, owners) -> owners.first() }
    }

    private fun operatorsLikelyMatch(target: String, candidate: String): Boolean {
        if (target.isBlank() || candidate.isBlank()) return false
        if (target == candidate) return true
        if (target.length >= 4 && candidate.contains(target)) return true
        if (candidate.length >= 4 && target.contains(candidate)) return true
        return false
    }

    private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.6f", value)

    private fun normalize(value: String?): String {
        return value
            ?.lowercase(Locale.US)
            ?.replace(Regex("[^a-z0-9]+"), " ")
            ?.trim()
            .orEmpty()
    }

    private val genericOperatorTokens = setOf(
        "the",
        "and",
        "for",
        "with",
        "group",
        "energy",
        "electric",
        "electricity",
        "charging",
        "charge",
        "network",
        "solutions",
        "solution",
        "services",
        "service",
        "limited",
        "ltd",
        "plc",
        "ireland",
        "uk",
        "ie",
        "ev"
    )

    private fun isFresh(updatedAtMs: Long, nowMs: Long): Boolean = nowMs - updatedAtMs <= CACHE_TTL_MS

    private fun isCountryCode(value: String): Boolean {
        return value.length == 2 && value.all { it.isLetter() }
    }

    private fun countryLookupKey(latitude: Double, longitude: Double): String {
        val latKey = String.format(Locale.US, "%.1f", latitude)
        val lonKey = String.format(Locale.US, "%.1f", longitude)
        return "$latKey,$lonKey"
    }
}
