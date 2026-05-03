package com.skylarkin.evfinder

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.cos
import kotlin.math.pow

class OpenChargeMapClient(
    private val apiKey: String,
    private val fxRateProvider: FxRateProvider = NoOpFxRateProvider,
    private val chargetripPricingClient: ChargetripPricingClient? = null
) {
    private companion object {
        const val SEARCH_RADIUS_KM = 50.0
        const val BBOX_GRID_SIZE = 3
        const val BBOX_MAX_RESULTS = 300
        const val CLUSTER_BUCKET_KM = 25.0
        const val CLUSTER_REPRESENTATIVE_CHECKS = 2
        const val MAX_DIRECT_ROUTE_CHECKS = 30
        const val OSRM_ROUTE_ENDPOINT = "https://router.project-osrm.org/route/v1/driving"
        const val ENABLE_OCM_PRICING_LOOKUP = false
    }

    private data class PricingInfo(
        val displayText: String,
        val euroPerKwh: Double?
    )
    private data class BoundingBox(
        val north: Double,
        val south: Double,
        val west: Double,
        val east: Double
    )
    private enum class RouteCheck {
        NO_FERRY,
        REQUIRES_FERRY,
        NO_ROUTE,
        UNKNOWN
    }

    suspend fun findMatchingChargePoints(latitude: Double, longitude: Double, maxPriceEuroPerKwh: Double): List<ChargePoint> {
        return withContext(Dispatchers.Default) {
            val gbpToEurRate = runCatching { fxRateProvider.getRate("GBP", "EUR") }.getOrNull()
            val json = fetchPoi(latitude, longitude)
            val strictMatches = parseAndFilter(json, latitude, longitude, maxPriceEuroPerKwh, gbpToEurRate)
            filterByClusteredRouteReachability(latitude, longitude, strictMatches)
                .sortedByDescending { it.distanceKm }
        }
    }

    private suspend fun fetchPoi(latitude: Double, longitude: Double): JSONArray = withContext(Dispatchers.IO) {
        val tiles = buildBoundingBoxTiles(latitude, longitude, SEARCH_RADIUS_KM, BBOX_GRID_SIZE)
        val tileResponses = coroutineScope {
            tiles.map { box ->
                async {
                    fetchPoiByBoundingBox(box)
                }
            }.awaitAll()
        }

        val dedupedById = LinkedHashMap<Int, JSONObject>()
        val withoutId = mutableListOf<JSONObject>()

        for (array in tileResponses) {
            for (i in 0 until array.length()) {
                val poi = array.optJSONObject(i) ?: continue
                val id = poi.optInt("ID", -1)
                if (id > 0) {
                    dedupedById.putIfAbsent(id, poi)
                } else {
                    withoutId.add(poi)
                }
            }
        }

        val merged = JSONArray()
        dedupedById.values.forEach { merged.put(it) }
        withoutId.forEach { merged.put(it) }
        merged
    }

    private suspend fun parseAndFilter(
        array: JSONArray,
        userLatitude: Double,
        userLongitude: Double,
        maxPriceEuroPerKwh: Double,
        gbpToEurRate: Double?
    ): List<ChargePoint> {
        val strictMatches = mutableListOf<ChargePoint>()

        for (index in 0 until array.length()) {
            val poi = array.optJSONObject(index) ?: continue
            val addressInfo = poi.optJSONObject("AddressInfo") ?: continue
            val connections = poi.optJSONArray("Connections") ?: continue

            if (!hasType2Connection(connections)) continue

            val pricingInfo = extractPricingInfo(
                poi = poi,
                addressInfo = addressInfo,
                connections = connections,
                gbpToEurRate = gbpToEurRate
            )
            val parsedEuroCost = pricingInfo.euroPerKwh
            val isPublic = isPublic24x7(poi)

            if (parsedEuroCost == null || parsedEuroCost > maxPriceEuroPerKwh) continue
            if (!isPublic) continue

            val id = poi.optInt("ID")
            val title = addressInfo.optString("Title").ifBlank { "Unnamed chargepoint" }
            val address = buildAddressLine(addressInfo)
            val lat = addressInfo.optDouble("Latitude", Double.NaN)
            val lon = addressInfo.optDouble("Longitude", Double.NaN)
            val distance = haversineDistanceKm(userLatitude, userLongitude, lat, lon)

            if (lat.isNaN() || lon.isNaN()) continue
            if (distance > SEARCH_RADIUS_KM) continue

            val chargePoint = ChargePoint(
                id = id,
                name = title,
                address = address,
                latitude = lat,
                longitude = lon,
                distanceKm = distance,
                usageCost = pricingInfo.displayText,
                accessSummary = if (isPublic) "Public (hours not always published)" else "Access type unknown",
                directionCode = bearingToDirectionCode(
                    fromLat = userLatitude,
                    fromLon = userLongitude,
                    toLat = lat,
                    toLon = lon
                )
            )

            if (isPublic && parsedEuroCost <= maxPriceEuroPerKwh) {
                strictMatches.add(chargePoint.copy(accessSummary = "Public 24/7 - within price cap"))
            }
        }

        return strictMatches
    }

    private suspend fun filterByClusteredRouteReachability(
        userLatitude: Double,
        userLongitude: Double,
        candidates: List<ChargePoint>
    ): List<ChargePoint> = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext emptyList()

        val sortedByDistance = candidates.sortedBy { it.distanceKm }
        val clusters = sortedByDistance.groupBy { (it.distanceKm / CLUSTER_BUCKET_KM).toInt() }.toSortedMap()
        val allowedClusterIndexes = mutableSetOf<Int>()

        for ((clusterIndex, points) in clusters) {
            val representatives = points.sortedBy { it.distanceKm }.take(CLUSTER_REPRESENTATIVE_CHECKS)
            var sawNoFerryRoute = false
            var sawOnlyBlockedRoutes = true

            for (point in representatives) {
                when (checkRouteForFerry(userLatitude, userLongitude, point.latitude, point.longitude)) {
                    RouteCheck.NO_FERRY -> {
                        sawNoFerryRoute = true
                        sawOnlyBlockedRoutes = false
                        break
                    }
                    RouteCheck.REQUIRES_FERRY,
                    RouteCheck.NO_ROUTE -> {
                        // Keep probing additional representatives before rejecting this cluster.
                    }
                    RouteCheck.UNKNOWN -> {
                        // Avoid over-filtering when routing API is unavailable/intermittent.
                        sawOnlyBlockedRoutes = false
                    }
                }
            }

            if (sawNoFerryRoute || !sawOnlyBlockedRoutes) {
                allowedClusterIndexes.add(clusterIndex)
            }
        }

        val clusterFiltered = sortedByDistance.filter { point ->
            val bucket = (point.distanceKm / CLUSTER_BUCKET_KM).toInt()
            bucket in allowedClusterIndexes
        }

        if (clusterFiltered.isEmpty()) {
            return@withContext emptyList()
        }

        val checked = clusterFiltered.take(MAX_DIRECT_ROUTE_CHECKS)
        val uncheckedTail = clusterFiltered.drop(MAX_DIRECT_ROUTE_CHECKS)
        val keepIds = mutableSetOf<Int>()
        val keepCoordinateKeys = mutableSetOf<String>()

        for (point in checked) {
            when (checkRouteForFerry(userLatitude, userLongitude, point.latitude, point.longitude)) {
                RouteCheck.NO_FERRY,
                RouteCheck.UNKNOWN -> {
                    if (point.id > 0) keepIds.add(point.id)
                    keepCoordinateKeys.add(coordinateKey(point.latitude, point.longitude))
                }
                RouteCheck.REQUIRES_FERRY,
                RouteCheck.NO_ROUTE -> {
                    // Exclude points that require ferry or have no drivable route.
                }
            }
        }

        // Preserve tail without extra route checks to keep latency controlled.
        for (point in uncheckedTail) {
            if (point.id > 0) keepIds.add(point.id)
            keepCoordinateKeys.add(coordinateKey(point.latitude, point.longitude))
        }

        clusterFiltered.filter { point ->
            (point.id > 0 && point.id in keepIds) || coordinateKey(point.latitude, point.longitude) in keepCoordinateKeys
        }
    }

    private fun checkRouteForFerry(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): RouteCheck {
        val coordinates = buildString {
            append(formatCoordinate(fromLon))
            append(",")
            append(formatCoordinate(fromLat))
            append(";")
            append(formatCoordinate(toLon))
            append(",")
            append(formatCoordinate(toLat))
        }

        val url = Uri.parse("$OSRM_ROUTE_ENDPOINT/$coordinates")
            .buildUpon()
            .appendQueryParameter("overview", "false")
            .appendQueryParameter("steps", "true")
            .appendQueryParameter("alternatives", "false")
            .build()
            .toString()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            connectTimeout = 6_000
            readTimeout = 6_000
        }

        return try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val response = stream.bufferedReader().use(BufferedReader::readText)

            if (connection.responseCode !in 200..299) {
                return RouteCheck.UNKNOWN
            }

            val json = JSONObject(response)
            val code = json.optString("code", "")
            if (code.equals("NoRoute", ignoreCase = true)) {
                return RouteCheck.NO_ROUTE
            }
            if (!code.equals("Ok", ignoreCase = true)) {
                return RouteCheck.UNKNOWN
            }

            val routes = json.optJSONArray("routes")
            if (routes == null || routes.length() == 0) {
                return RouteCheck.NO_ROUTE
            }

            val route = routes.optJSONObject(0) ?: return RouteCheck.NO_ROUTE
            val legs = route.optJSONArray("legs") ?: return RouteCheck.NO_ROUTE
            var requiresFerry = false

            for (legIndex in 0 until legs.length()) {
                val leg = legs.optJSONObject(legIndex) ?: continue
                val steps = leg.optJSONArray("steps") ?: continue
                for (stepIndex in 0 until steps.length()) {
                    val step = steps.optJSONObject(stepIndex) ?: continue
                    val mode = step.optString("mode", "").lowercase(Locale.US)
                    val maneuverType = step.optJSONObject("maneuver")
                        ?.optString("type", "")
                        ?.lowercase(Locale.US)
                        .orEmpty()
                    if (mode == "ferry" || maneuverType == "ferry") {
                        requiresFerry = true
                        break
                    }
                }
                if (requiresFerry) break
            }

            if (requiresFerry) RouteCheck.REQUIRES_FERRY else RouteCheck.NO_FERRY
        } catch (_: Exception) {
            RouteCheck.UNKNOWN
        } finally {
            connection.disconnect()
        }
    }

    private fun coordinateKey(lat: Double, lon: Double): String {
        return String.format(Locale.US, "%.5f,%.5f", lat, lon)
    }

    private suspend fun extractPricingInfo(
        poi: JSONObject,
        addressInfo: JSONObject,
        connections: JSONArray,
        gbpToEurRate: Double?
    ): PricingInfo {
        if (ENABLE_OCM_PRICING_LOOKUP) {
            val primaryCandidates = mutableListOf<String>()
            val fallbackCandidates = mutableListOf<String>()

            poi.optString("UsageCost", "").trim().takeIf { it.isNotBlank() }?.let { primaryCandidates.add(it) }

            for (i in 0 until connections.length()) {
                val connection = connections.optJSONObject(i) ?: continue
                connection.optString("UsageCost", "").trim().takeIf { it.isNotBlank() }?.let { primaryCandidates.add(it) }
                connection.optString("Comments", "").trim().takeIf { it.isNotBlank() }?.let { fallbackCandidates.add(it) }
            }

            poi.optString("GeneralComments", "").trim().takeIf { it.isNotBlank() }?.let { fallbackCandidates.add(it) }
            poi.optJSONObject("UsageType")?.optString("Title", "")?.trim()?.takeIf { it.isNotBlank() }?.let { fallbackCandidates.add(it) }

            val parsed = (primaryCandidates + fallbackCandidates)
                .asSequence()
                .mapNotNull { parseEuroCostPerKwh(it) }
                .firstOrNull()

            if (parsed != null) {
                return PricingInfo(
                    displayText = "EUR " + String.format(Locale.US, "%.2f", parsed) + "/kWh",
                    euroPerKwh = parsed
                )
            }

            val readableRaw = (primaryCandidates + fallbackCandidates)
                .firstOrNull { looksLikePriceText(it) }
                ?.let { compactPriceText(it) }

            if (readableRaw != null) {
                return PricingInfo(readableRaw, null)
            }
        }

        val operatorName = poi.optJSONObject("OperatorInfo")?.optString("Title", "")?.trim()
        val siteTitle = addressInfo.optString("Title", "").trim()
        val lat = addressInfo.optDouble("Latitude", Double.NaN)
        val lon = addressInfo.optDouble("Longitude", Double.NaN)

        if (!lat.isNaN() && !lon.isNaN()) {
            val chargetripMatch = runCatching {
                chargetripPricingClient?.findBestMatch(
                    operatorName = operatorName,
                    siteTitle = siteTitle,
                    latitude = lat,
                    longitude = lon,
                    gbpToEurRate = gbpToEurRate
                )
            }.getOrNull()

            if (chargetripMatch != null) {
                val estimate = if (chargetripMatch.originalCurrency.equals("GBP", ignoreCase = true)) {
                    val gbpPart = "CPO GBP " + String.format(Locale.US, "%.3f", chargetripMatch.originalPricePerKwh) + "/kWh"
                    val eurPart = " (~EUR " + String.format(Locale.US, "%.2f", chargetripMatch.euroPerKwh) + "/kWh)"
                    gbpPart + eurPart
                } else {
                    "CPO EUR " + String.format(Locale.US, "%.2f", chargetripMatch.euroPerKwh) + "/kWh"
                }
                return PricingInfo("$estimate (${chargetripMatch.sourceLabel})", chargetripMatch.euroPerKwh)
            }
        }

        return PricingInfo("Price unknown", null)
    }

    private fun hasType2Connection(connections: JSONArray): Boolean {
        for (i in 0 until connections.length()) {
            val connection = connections.optJSONObject(i) ?: continue
            val connectionType = connection.optJSONObject("ConnectionType")
            val title = connectionType?.optString("Title", "") ?: ""
            if (title.contains("type 2", ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun parseEuroCostPerKwh(rawText: String): Double? {
        if (rawText.isBlank()) return null

        val normalized = normalizePriceText(rawText)
        val lower = normalized.lowercase(Locale.US)

        if (containsFreePricing(lower)) return 0.0

        val hasUsd = lower.contains("$") || lower.contains("usd")
        val hasGbp = lower.contains("\u00a3") || lower.contains("gbp")
        val hasEur = lower.contains("\u20ac") || lower.contains("eur")

        if ((hasUsd || hasGbp) && !hasEur) return null

        val centMatch = Regex("(\\d+[\\.,]?\\d*)\\s*(?:c|cent|\\u00a2)\\s*(?:/|per)?\\s*kwh").find(lower)
        if (centMatch != null) {
            val cents = centMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return if (cents >= 0.0) cents / 100.0 else null
        }

        val euroMatch = Regex("(\\d+[\\.,]?\\d*)\\s*(?:\\u20ac|eur)?\\s*(?:/|per)?\\s*kwh").find(lower)
            ?: Regex("(?:/|per)\\s*kwh\\s*(\\d+[\\.,]?\\d*)").find(lower)

        val rawValue = euroMatch?.groupValues?.lastOrNull()?.replace(',', '.') ?: return null
        val parsed = rawValue.toDoubleOrNull() ?: return null

        if (parsed <= 0.0) return null

        return if (parsed >= 2.0 && !hasEur) parsed / 100.0 else parsed
    }

    private fun containsFreePricing(text: String): Boolean {
        return text.contains("free") || text.contains("no charge") || text.contains("complimentary") || text.contains("gratis")
    }

    private fun looksLikePriceText(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        return lower.contains("kwh") ||
            lower.contains("price") ||
            lower.contains("cost") ||
            lower.contains("tariff") ||
            containsFreePricing(lower)
    }

    private fun compactPriceText(text: String): String {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        return if (compact.length > 72) compact.take(69) + "..." else compact
    }

    private fun fetchPoiByBoundingBox(box: BoundingBox): JSONArray {
        val topLeft = "(" + formatCoordinate(box.north) + "," + formatCoordinate(box.west) + ")"
        val bottomRight = "(" + formatCoordinate(box.south) + "," + formatCoordinate(box.east) + ")"
        val boundingBox = "$topLeft,$bottomRight"

        val url = Uri.parse("https://api.openchargemap.io/v3/poi")
            .buildUpon()
            .appendQueryParameter("output", "json")
            .appendQueryParameter("boundingbox", boundingBox)
            .appendQueryParameter("maxresults", BBOX_MAX_RESULTS.toString())
            .appendQueryParameter("compact", "false")
            .appendQueryParameter("verbose", "false")
            .build()
            .toString()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-API-Key", apiKey)
            setRequestProperty("X-Requestor", "Skylarkin-EV-Finder")
            connectTimeout = 15_000
            readTimeout = 15_000
        }

        return try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream.bufferedReader().use(BufferedReader::readText)

            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("API error ${connection.responseCode}: $response")
            }

            JSONArray(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildBoundingBoxTiles(latitude: Double, longitude: Double, radiusKm: Double, gridSize: Int): List<BoundingBox> {
        val latDelta = radiusKm / 110.574
        val cosLat = cos(Math.toRadians(latitude)).coerceAtLeast(0.1)
        val lonDelta = radiusKm / (111.320 * cosLat)

        val north = latitude + latDelta
        val south = latitude - latDelta
        val west = longitude - lonDelta
        val east = longitude + lonDelta

        val tileLat = (north - south) / gridSize
        val tileLon = (east - west) / gridSize

        val boxes = mutableListOf<BoundingBox>()
        for (row in 0 until gridSize) {
            val tileNorth = north - (row * tileLat)
            val tileSouth = tileNorth - tileLat
            for (col in 0 until gridSize) {
                val tileWest = west + (col * tileLon)
                val tileEast = tileWest + tileLon
                boxes.add(
                    BoundingBox(
                        north = tileNorth,
                        south = tileSouth,
                        west = tileWest,
                        east = tileEast
                    )
                )
            }
        }
        return boxes
    }

    private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.7f", value)

    private fun bearingToDirectionCode(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): String {
        if (fromLat.isNaN() || fromLon.isNaN()) return "N"
        val bearing = computeBearingDegrees(fromLat, fromLon, toLat, toLon)
        val normalized = (bearing + 360.0) % 360.0
        val sector = ((normalized + 11.25) / 22.5).toInt() % 16
        return listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")[sector]
    }

    private fun computeBearingDegrees(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val fromLatRad = Math.toRadians(fromLat)
        val toLatRad = Math.toRadians(toLat)
        val deltaLonRad = Math.toRadians(toLon - fromLon)

        val y = kotlin.math.sin(deltaLonRad) * kotlin.math.cos(toLatRad)
        val x = kotlin.math.cos(fromLatRad) * kotlin.math.sin(toLatRad) -
            kotlin.math.sin(fromLatRad) * kotlin.math.cos(toLatRad) * kotlin.math.cos(deltaLonRad)

        return Math.toDegrees(kotlin.math.atan2(y, x))
    }

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

    private fun normalizePriceText(text: String): String {
        return text
            .replace("\u00e2\u201a\u00ac", "\u20ac")
            .replace("\u00c2\u00a3", "\u00a3")
            .replace("\u00e2\u0082\u00ac", "\u20ac")
    }

    private fun isPublic24x7(poi: JSONObject): Boolean {
        val usageType = poi.optJSONObject("UsageType")
        val usageTitle = usageType?.optString("Title", "") ?: ""
        val usageTypeId = poi.optInt("UsageTypeID", 0)
        val usageText = usageTitle.lowercase()

        val likelyPublic = usageText.contains("public") || usageTypeId in setOf(1, 4, 5, 6, 7)
        if (!likelyPublic) return false

        val isMembershipRequired = usageType?.optBoolean("IsMembershipRequired", false) ?: false
        val isAccessKeyRequired = usageType?.optBoolean("IsAccessKeyRequired", false) ?: false
        if (isMembershipRequired || isAccessKeyRequired) return false

        val statusType = poi.optJSONObject("StatusType")
        val statusTitle = statusType?.optString("Title", "") ?: ""
        if (statusTitle.contains("planned", ignoreCase = true)) return false

        val comments = listOf(
            poi.optString("GeneralComments", ""),
            usageTitle
        ).joinToString(" ").lowercase()

        val likelyAlwaysOpen = comments.contains("24/7") ||
            comments.contains("24h") ||
            comments.contains("24 hours") ||
            !comments.contains("hours")

        return likelyAlwaysOpen
    }

    private fun buildAddressLine(addressInfo: JSONObject): String {
        val parts = listOf(
            addressInfo.optString("AddressLine1", ""),
            addressInfo.optString("Town", ""),
            addressInfo.optString("StateOrProvince", ""),
            addressInfo.optString("Postcode", "")
        )
            .map { it.trim() }
            .filter { it.isNotBlank() }

        return if (parts.isEmpty()) "Address unavailable" else parts.joinToString(", ")
    }
}
