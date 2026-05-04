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
import kotlin.random.Random
import kotlin.math.cos
import kotlin.math.pow

class OpenChargeMapClient(
    private val apiKey: String,
    private val operatorScoreCatalog: OcmOperatorScoreCatalog? = null
) {
    enum class SearchStage {
        FETCHING_POI,
        OPERATOR_PAGINATION,
        APPLYING_FILTERS,
        ISLAND_FILTERING
    }

    private companion object {
        const val SEARCH_RADIUS_KM = 50.0
        const val BBOX_GRID_SIZE = 3
        const val BBOX_MAX_RESULTS = 300
        const val ISLAND_SEPARATION_THRESHOLD_KM = 30.0
        const val MAIN_CLUSTER_MAX_USER_DISTANCE_KM = 20.0
        const val OSRM_ROUTE_ENDPOINT = "https://router.project-osrm.org/route/v1/driving"
        const val OSRM_CONNECT_TIMEOUT_MS = 3_000
        const val OSRM_READ_TIMEOUT_MS = 3_000
        val PUBLIC_USAGE_TYPE_IDS = hashSetOf(1, 4, 5, 6, 7)
    }

    private data class BoundingBox(
        val north: Double,
        val south: Double,
        val west: Double,
        val east: Double
    )

    suspend fun findMatchingChargePoints(
        latitude: Double,
        longitude: Double,
        onStage: ((SearchStage) -> Unit)? = null,
        onFilterProgress: ((String) -> Unit)? = null
    ): List<ChargePoint> {
        return withContext(Dispatchers.Default) {
            onStage?.invoke(SearchStage.FETCHING_POI)
            val json = fetchPoi(latitude, longitude)
            onStage?.invoke(SearchStage.OPERATOR_PAGINATION)
            onStage?.invoke(SearchStage.APPLYING_FILTERS)
            val strictMatches = parseAndFilter(
                array = json,
                userLatitude = latitude,
                userLongitude = longitude,
                onFilterProgress = onFilterProgress
            )
            onStage?.invoke(SearchStage.ISLAND_FILTERING)
            filterByChargepointIslandConnectivity(
                candidates = strictMatches,
                userLatitude = latitude,
                userLongitude = longitude
            )
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
        onFilterProgress: ((String) -> Unit)?
    ): List<ChargePoint> {
        data class Candidate(
            val poi: JSONObject,
            val addressInfo: JSONObject,
            val connections: JSONArray,
            val lat: Double,
            val lon: Double,
            val distanceKm: Double
        )

        val strictMatches = mutableListOf<ChargePoint>()
        val total = array.length().coerceAtLeast(1)
        var invalidLocationRejected = 0
        var distanceRejected = 0
        var operatorRejected = 0
        var type2Rejected = 0
        var accessRejected = 0

        onFilterProgress?.invoke("Phase 1/4: checking 50km radius (0/$total)")

        val distanceCandidates = mutableListOf<Candidate>()
        for (index in 0 until array.length()) {
            val poi = array.optJSONObject(index) ?: continue
            val addressInfo = poi.optJSONObject("AddressInfo") ?: continue
            val connections = poi.optJSONArray("Connections") ?: continue
            val operatorTitle = poi.optJSONObject("OperatorInfo")?.optString("Title", "").orEmpty()

            if (shouldRejectOperator(operatorTitle)) {
                operatorRejected += 1
                continue
            }

            val lat = addressInfo.optDouble("Latitude", Double.NaN)
            val lon = addressInfo.optDouble("Longitude", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) {
                invalidLocationRejected += 1
                continue
            }
            val distance = haversineDistanceKm(userLatitude, userLongitude, lat, lon)
            if (distance > SEARCH_RADIUS_KM) {
                distanceRejected += 1
                continue
            }
            distanceCandidates.add(
                Candidate(
                    poi = poi,
                    addressInfo = addressInfo,
                    connections = connections,
                    lat = lat,
                    lon = lon,
                    distanceKm = distance
                )
            )
            if ((index + 1) % 40 == 0 || index == array.length() - 1) {
                onFilterProgress?.invoke("Phase 1/4: checking 50km radius (${index + 1}/$total)")
            }
        }

        onFilterProgress?.invoke("Phase 2/4: filtering Type 2 connectors (remaining ${distanceCandidates.size}/$total)")
        val type2Candidates = mutableListOf<Candidate>()
        for (i in distanceCandidates.indices) {
            val candidate = distanceCandidates[i]
            if (!hasType2Connection(candidate.connections)) {
                type2Rejected += 1
                continue
            }
            type2Candidates.add(candidate)
            if ((i + 1) % 40 == 0 || i == distanceCandidates.lastIndex) {
                onFilterProgress?.invoke("Phase 2/4: filtering Type 2 connectors (${i + 1}/${distanceCandidates.size})")
            }
        }

        onFilterProgress?.invoke("Phase 3/4: checking public 24/7 access (remaining ${type2Candidates.size}/$total)")
        val publicCandidates = mutableListOf<Candidate>()
        for (i in type2Candidates.indices) {
            val candidate = type2Candidates[i]
            val isPublic = isPublic24x7(candidate.poi)
            if (!isPublic) {
                accessRejected += 1
                continue
            }
            publicCandidates.add(candidate)
            if ((i + 1) % 40 == 0 || i == type2Candidates.lastIndex) {
                onFilterProgress?.invoke("Phase 3/4: checking public 24/7 access (${i + 1}/${type2Candidates.size})")
            }
        }

        onFilterProgress?.invoke("Phase 4/4: finalizing matches (remaining ${publicCandidates.size}/$total)")
        for (i in publicCandidates.indices) {
            val candidate = publicCandidates[i]

            val id = candidate.poi.optInt("ID")
            val title = candidate.addressInfo.optString("Title").ifBlank { "Unnamed chargepoint" }
            val address = buildAddressLine(candidate.addressInfo)
            val chargePoint = ChargePoint(
                id = id,
                name = title,
                address = address,
                latitude = candidate.lat,
                longitude = candidate.lon,
                distanceKm = candidate.distanceKm,
                usageCost = "Price unavailable",
                accessSummary = "Public (hours not always published)",
                directionCode = bearingToDirectionCode(
                    fromLat = userLatitude,
                    fromLon = userLongitude,
                    toLat = candidate.lat,
                    toLon = candidate.lon
                ),
                combinedCostScore = resolveCombinedCostScore(candidate.poi),
                isFleetOperator = isFleetOperator(candidate.poi)
            )
            strictMatches.add(chargePoint.copy(accessSummary = "Public 24/7"))

            if ((i + 1) % 40 == 0 || i == publicCandidates.lastIndex) {
                onFilterProgress?.invoke("Phase 4/4: finalizing matches (${i + 1}/${publicCandidates.size})")
            }
        }

        val remaining = (total - operatorRejected - type2Rejected - accessRejected - distanceRejected - invalidLocationRejected)
            .coerceAtLeast(0)
        onFilterProgress?.invoke(
            "Filter summary: kept ${strictMatches.size}, remaining $remaining/$total, rejected operator=$operatorRejected type2=$type2Rejected access=$accessRejected distance=$distanceRejected invalidLocation=$invalidLocationRejected"
        )

        return strictMatches
    }

    private suspend fun filterByChargepointIslandConnectivity(
        candidates: List<ChargePoint>,
        userLatitude: Double,
        userLongitude: Double
    ): List<ChargePoint> {
        if (candidates.size <= 2) return candidates

        val parent = IntArray(candidates.size) { it }
        val rank = IntArray(candidates.size) { 0 }

        fun find(x: Int): Int {
            var n = x
            while (parent[n] != n) {
                parent[n] = parent[parent[n]]
                n = parent[n]
            }
            return n
        }

        fun union(a: Int, b: Int) {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA == rootB) return
            when {
                rank[rootA] < rank[rootB] -> parent[rootA] = rootB
                rank[rootA] > rank[rootB] -> parent[rootB] = rootA
                else -> {
                    parent[rootB] = rootA
                    rank[rootA]++
                }
            }
        }

        for (i in candidates.indices) {
            for (j in i + 1 until candidates.size) {
                val d = haversineDistanceKm(
                    candidates[i].latitude,
                    candidates[i].longitude,
                    candidates[j].latitude,
                    candidates[j].longitude
                )
                if (d <= ISLAND_SEPARATION_THRESHOLD_KM) {
                    union(i, j)
                }
            }
        }

        val components = mutableMapOf<Int, MutableList<Int>>()
        for (i in candidates.indices) {
            val root = find(i)
            components.getOrPut(root) { mutableListOf() }.add(i)
        }

        val mainRoot = components
            .asSequence()
            .filter { (_, members) ->
                members.any { index ->
                    haversineDistanceKm(
                        userLatitude,
                        userLongitude,
                        candidates[index].latitude,
                        candidates[index].longitude
                    ) <= MAIN_CLUSTER_MAX_USER_DISTANCE_KM
                }
            }
            .maxWithOrNull(
                compareBy<Map.Entry<Int, MutableList<Int>>> { it.value.size }
                    .thenByDescending { entry ->
                        -entry.value.minOf { index ->
                            haversineDistanceKm(
                                userLatitude,
                                userLongitude,
                                candidates[index].latitude,
                                candidates[index].longitude
                            )
                        }
                    }
            )
            ?.key
            ?: return candidates

        val mainMembers = components[mainRoot].orEmpty()
        if (mainMembers.isEmpty()) return candidates

        val keepRoots = mutableSetOf(mainRoot)
        for ((root, members) in components) {
            if (root == mainRoot || members.isEmpty()) continue

            val representativeIndex = members.randomOrNull(Random(root)) ?: continue
            val representative = candidates[representativeIndex]
            val closestMainIndex = mainMembers.minByOrNull { mainIndex ->
                haversineDistanceKm(
                    candidates[mainIndex].latitude,
                    candidates[mainIndex].longitude,
                    representative.latitude,
                    representative.longitude
                )
            } ?: continue
            val mainPoint = candidates[closestMainIndex]

            val reachableWithoutFerry = canRouteWithoutFerry(
                fromLat = mainPoint.latitude,
                fromLon = mainPoint.longitude,
                toLat = representative.latitude,
                toLon = representative.longitude
            )
            if (reachableWithoutFerry) {
                keepRoots.add(root)
            }
        }

        return candidates.filterIndexed { index, _ -> keepRoots.contains(find(index)) }
    }

    private suspend fun canRouteWithoutFerry(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): Boolean = withContext(Dispatchers.IO) {
        val coordinates = "${formatCoordinate(fromLon)},${formatCoordinate(fromLat)};${formatCoordinate(toLon)},${formatCoordinate(toLat)}"
        val url = Uri.parse("$OSRM_ROUTE_ENDPOINT/$coordinates")
            .buildUpon()
            .appendQueryParameter("overview", "false")
            .appendQueryParameter("alternatives", "false")
            .appendQueryParameter("steps", "false")
            .appendQueryParameter("exclude", "ferry")
            .build()
            .toString()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            connectTimeout = OSRM_CONNECT_TIMEOUT_MS
            readTimeout = OSRM_READ_TIMEOUT_MS
        }

        return@withContext try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val response = stream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(response)
            val code = json.optString("code", "")
            if (code.equals("NoRoute", ignoreCase = true)) {
                false
            } else if (code.equals("Ok", ignoreCase = true)) {
                val routes = json.optJSONArray("routes")
                routes != null && routes.length() > 0
            } else {
                true
            }
        } catch (_: Exception) {
            true
        } finally {
            connection.disconnect()
        }
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

    private fun shouldRejectOperator(operatorTitle: String): Boolean {
        val normalized = operatorTitle.trim().lowercase(Locale.US)
        if (normalized.isBlank()) return false
        if (normalized == "(unknown operator)") return true
        if (normalized == "(business owner at location)") return true
        if (normalized.contains("tesla")) return true
        return false
    }

    private fun resolveCombinedCostScore(poi: JSONObject): Double? {
        val operatorId = poi.optJSONObject("OperatorInfo")
            ?.optInt("ID", -1)
            ?.takeIf { it > 0 }
        return operatorScoreCatalog?.findByOperatorId(operatorId)?.combinedCostScore
    }

    private fun isFleetOperator(poi: JSONObject): Boolean {
        val operatorId = poi.optJSONObject("OperatorInfo")
            ?.optInt("ID", -1)
            ?.takeIf { it > 0 }
        return operatorScoreCatalog?.findByOperatorId(operatorId)?.isFleetOperator == true
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

    private fun isPublic24x7(poi: JSONObject): Boolean {
        val usageType = poi.optJSONObject("UsageType")
        val usageTitle = usageType?.optString("Title", "").orEmpty()
        val usageTypeId = poi.optInt("UsageTypeID", 0)
        val usageText = usageTitle.lowercase(Locale.US)

        val likelyPublic = usageText.contains("public") || PUBLIC_USAGE_TYPE_IDS.contains(usageTypeId)
        if (!likelyPublic) return false

        val isMembershipRequired = usageType?.optBoolean("IsMembershipRequired", false) == true
        val isAccessKeyRequired = usageType?.optBoolean("IsAccessKeyRequired", false) == true
        if (isMembershipRequired || isAccessKeyRequired) return false

        val statusType = poi.optJSONObject("StatusType")
        val statusTitle = statusType?.optString("Title", "").orEmpty()
        if (statusTitle.contains("planned", ignoreCase = true)) return false

        val generalComments = poi.optString("GeneralComments", "")
        if (generalComments.isBlank()) return true

        val comments = generalComments.lowercase(Locale.US)
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
