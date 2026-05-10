package com.skylarkin.evfinder.auto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class OsmSleepSpot(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val distanceKm: Double
)

class OsmSleepSpotClient {
    private companion object {
        const val OVERPASS_ENDPOINT = "https://overpass-api.de/api/interpreter"
        val RADIUS_STEPS_METERS = intArrayOf(20_000, 50_000, 100_000)
        const val ROAD_DENSITY_RADIUS_METERS = 700
        const val MAX_DENSITY_CHECK_CANDIDATES = 8
        const val ROAD_DENSITY_PENALTY_KM = 0.45
        const val OVERPASS_CONNECT_TIMEOUT_MS = 10_000
        const val OVERPASS_READ_TIMEOUT_MS = 10_000
        const val ROAD_DENSITY_CONNECT_TIMEOUT_MS = 4_000
        const val ROAD_DENSITY_READ_TIMEOUT_MS = 4_000
    }

    suspend fun findNearestSleepSpot(
        userLatitude: Double,
        userLongitude: Double,
        requireShowers: Boolean
    ): OsmSleepSpot? {
        return withContext(Dispatchers.IO) {
            for (radius in RADIUS_STEPS_METERS) {
                val strictNearest = runCatching {
                    findNearestStrictWithinRadius(
                        userLatitude = userLatitude,
                        userLongitude = userLongitude,
                        radiusMeters = radius,
                        requireShowers = requireShowers
                    )
                }.getOrNull()
                if (strictNearest != null) return@withContext strictNearest

                val fallbackNearest = runCatching {
                    findNearestNatureWithinRadius(
                        userLatitude = userLatitude,
                        userLongitude = userLongitude,
                        radiusMeters = radius,
                        requireShowers = requireShowers
                    )
                }.getOrNull()
                if (fallbackNearest != null) return@withContext fallbackNearest
            }
            null
        }
    }

    private fun findNearestStrictWithinRadius(
        userLatitude: Double,
        userLongitude: Double,
        radiusMeters: Int,
        requireShowers: Boolean
    ): OsmSleepSpot? {
        val payload = "data=" + URLEncoder.encode(
            buildStrictOverpassQuery(userLatitude, userLongitude, radiusMeters, requireShowers),
            Charsets.UTF_8.name()
        )

        val elements = fetchOverpassElements(
            payload = payload,
            connectTimeoutMs = OVERPASS_CONNECT_TIMEOUT_MS,
            readTimeoutMs = OVERPASS_READ_TIMEOUT_MS
        )
        val candidates = extractStrictCandidates(
            elements = elements,
            userLatitude = userLatitude,
            userLongitude = userLongitude,
            requireShowers = requireShowers
        )
        return rankBySecludedScore(userLatitude, userLongitude, candidates)
    }

    private fun findNearestNatureWithinRadius(
        userLatitude: Double,
        userLongitude: Double,
        radiusMeters: Int,
        requireShowers: Boolean
    ): OsmSleepSpot? {
        val payload = "data=" + URLEncoder.encode(
            buildNatureOverpassQuery(userLatitude, userLongitude, radiusMeters),
            Charsets.UTF_8.name()
        )
        val elements = fetchOverpassElements(
            payload = payload,
            connectTimeoutMs = OVERPASS_CONNECT_TIMEOUT_MS,
            readTimeoutMs = OVERPASS_READ_TIMEOUT_MS
        )
        val candidates = extractNatureCandidates(
            elements = elements,
            userLatitude = userLatitude,
            userLongitude = userLongitude,
            requireShowers = requireShowers
        )
        return rankBySecludedScore(userLatitude, userLongitude, candidates)
    }

    private fun fetchOverpassElements(
        payload: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): JSONArray {
        val connection = (URL(OVERPASS_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Skylarkin/1.0 (Android Auto sleep lookup)")
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
        }

        val responseText = try {
            connection.outputStream.use { output ->
                output.write(payload.toByteArray(Charsets.UTF_8))
            }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }

        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("Overpass error ${connection.responseCode}: $responseText")
        }
        return JSONObject(responseText).optJSONArray("elements") ?: JSONArray()
    }

    private fun buildStrictOverpassQuery(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int,
        requireShowers: Boolean
    ): String {
        val showerFilter = if (requireShowers) """["shower"~"^(yes|designated|customers|public)$"]""" else ""
        val lat = String.format(Locale.US, "%.6f", latitude)
        val lon = String.format(Locale.US, "%.6f", longitude)
        return """
            [out:json][timeout:25];
            (
              nwr(around:$radiusMeters,$lat,$lon)["amenity"="parking"]["overnight"~"^(yes|designated|permissive)$"]$showerFilter["fee"~"^(no|free|0)$"];
              nwr(around:$radiusMeters,$lat,$lon)["tourism"~"^(camp_site|caravan_site|motorhome_site)$"]$showerFilter["fee"~"^(no|free|0)$"];
            );
            out center tags;
        """.trimIndent()
    }

    private fun buildNatureOverpassQuery(latitude: Double, longitude: Double, radiusMeters: Int): String {
        val lat = String.format(Locale.US, "%.6f", latitude)
        val lon = String.format(Locale.US, "%.6f", longitude)
        return """
            [out:json][timeout:25];
            (
              nwr(around:$radiusMeters,$lat,$lon)["tourism"~"^(camp_site|caravan_site|wilderness_hut|picnic_site)$"];
              nwr(around:$radiusMeters,$lat,$lon)["leisure"="nature_reserve"];
              nwr(around:$radiusMeters,$lat,$lon)["natural"~"^(beach|wood|heath|scrub)$"];
              nwr(around:$radiusMeters,$lat,$lon)["amenity"="shelter"];
            );
            out center tags;
        """.trimIndent()
    }

    private fun extractStrictCandidates(
        elements: JSONArray,
        userLatitude: Double,
        userLongitude: Double,
        requireShowers: Boolean
    ): List<OsmSleepSpot> {
        val candidates = mutableListOf<OsmSleepSpot>()
        val seenKeys = HashSet<String>()
        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            val tags = element.optJSONObject("tags") ?: JSONObject()
            if (!isPubliclyAccessible(tags)) continue
            if (!isFree(tags)) continue
            if (requireShowers && !hasShowerAccess(tags)) continue
            if (!supportsOvernight(tags)) continue
            if (!isOpenToday(tags)) continue
            val latLon = extractLatLon(element) ?: continue
            val lat = latLon.first
            val lon = latLon.second
            val name = elementName(tags)
            val dedupeKey = "${name.lowercase(Locale.US)}|${formatCoord(lat)}|${formatCoord(lon)}"
            if (!seenKeys.add(dedupeKey)) continue
            val distance = haversineDistanceKm(userLatitude, userLongitude, lat, lon)
            candidates.add(OsmSleepSpot(name, lat, lon, distance))
        }
        return candidates
    }

    private fun extractNatureCandidates(
        elements: JSONArray,
        userLatitude: Double,
        userLongitude: Double,
        requireShowers: Boolean
    ): List<OsmSleepSpot> {
        val candidates = mutableListOf<OsmSleepSpot>()
        val seenKeys = HashSet<String>()
        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            val tags = element.optJSONObject("tags") ?: JSONObject()
            if (!isPubliclyAccessible(tags)) continue
            if (requireShowers && !hasShowerAccess(tags)) continue
            if (!isOpenToday(tags)) continue
            if (!supportsOvernight(tags) && !looksLikeNatureSpot(tags)) continue

            val latLon = extractLatLon(element) ?: continue
            val lat = latLon.first
            val lon = latLon.second
            val name = elementName(tags)
            val dedupeKey = "${name.lowercase(Locale.US)}|${formatCoord(lat)}|${formatCoord(lon)}"
            if (!seenKeys.add(dedupeKey)) continue
            val distance = haversineDistanceKm(userLatitude, userLongitude, lat, lon)
            candidates.add(OsmSleepSpot(name, lat, lon, distance))
        }
        return candidates
    }

    private fun rankBySecludedScore(
        userLatitude: Double,
        userLongitude: Double,
        candidates: List<OsmSleepSpot>
    ): OsmSleepSpot? {
        if (candidates.isEmpty()) return null

        return runCatching {
        val shortlist = candidates
            .sortedBy { it.distanceKm }
            .take(MAX_DENSITY_CHECK_CANDIDATES)

        val withScores = shortlist.map { spot ->
            val roads = runCatching {
                countNearbyRoads(
                    latitude = spot.latitude,
                    longitude = spot.longitude,
                    radiusMeters = ROAD_DENSITY_RADIUS_METERS
                )
            }.getOrDefault(0)
            val score = spot.distanceKm + (roads * ROAD_DENSITY_PENALTY_KM)
            score to spot
        }

        return withScores.minByOrNull { it.first }?.second
            ?: candidates.minByOrNull { haversineDistanceKm(userLatitude, userLongitude, it.latitude, it.longitude) }
        }.getOrElse {
            candidates.minByOrNull { haversineDistanceKm(userLatitude, userLongitude, it.latitude, it.longitude) }
        }
    }

    private fun countNearbyRoads(latitude: Double, longitude: Double, radiusMeters: Int): Int {
        val lat = String.format(Locale.US, "%.6f", latitude)
        val lon = String.format(Locale.US, "%.6f", longitude)
        val query = """
            [out:json][timeout:20];
            (
              way(around:$radiusMeters,$lat,$lon)["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|service)$"];
            );
            out ids;
        """.trimIndent()
        val payload = "data=" + URLEncoder.encode(query, Charsets.UTF_8.name())
        val elements = fetchOverpassElements(
            payload = payload,
            connectTimeoutMs = ROAD_DENSITY_CONNECT_TIMEOUT_MS,
            readTimeoutMs = ROAD_DENSITY_READ_TIMEOUT_MS
        )
        return elements.length()
    }

    private fun extractLatLon(element: JSONObject): Pair<Double, Double>? {
        val lat = element.optDouble("lat", Double.NaN)
        val lon = element.optDouble("lon", Double.NaN)
        if (!lat.isNaN() && !lon.isNaN()) return lat to lon

        val center = element.optJSONObject("center")
        val centerLat = center?.optDouble("lat", Double.NaN) ?: Double.NaN
        val centerLon = center?.optDouble("lon", Double.NaN) ?: Double.NaN
        if (!centerLat.isNaN() && !centerLon.isNaN()) return centerLat to centerLon

        return null
    }

    private fun elementName(tags: JSONObject): String {
        return tags.optString("name", "").trim().ifBlank {
            val tourism = tags.optString("tourism", "").trim()
            val amenity = tags.optString("amenity", "").trim()
            when {
                tourism.isNotBlank() -> tourism.replace('_', ' ')
                amenity.isNotBlank() -> amenity.replace('_', ' ')
                else -> "Sleep stop"
            }
        }
    }

    private fun isFree(tags: JSONObject): Boolean {
        val fee = tags.optString("fee", "").lowercase(Locale.US)
        if (fee in setOf("no", "free", "0")) return true

        val parkingFee = tags.optString("parking:fee", "").lowercase(Locale.US)
        return parkingFee in setOf("no", "free", "0")
    }

    private fun hasShowerAccess(tags: JSONObject): Boolean {
        val shower = tags.optString("shower", "").lowercase(Locale.US)
        return shower in setOf("yes", "designated", "customers", "public")
    }

    private fun isPubliclyAccessible(tags: JSONObject): Boolean {
        val access = tags.optString("access", "").lowercase(Locale.US)
        if (access in setOf("private", "customers", "permit")) return false
        val motorVehicle = tags.optString("motor_vehicle", "").lowercase(Locale.US)
        if (motorVehicle == "no") return false
        return true
    }

    private fun looksLikeNatureSpot(tags: JSONObject): Boolean {
        val tourism = tags.optString("tourism", "").lowercase(Locale.US)
        if (tourism in setOf("camp_site", "caravan_site", "wilderness_hut", "picnic_site", "motorhome_site")) return true
        val leisure = tags.optString("leisure", "").lowercase(Locale.US)
        if (leisure == "nature_reserve") return true
        val natural = tags.optString("natural", "").lowercase(Locale.US)
        if (natural in setOf("beach", "wood", "heath", "scrub")) return true
        val amenity = tags.optString("amenity", "").lowercase(Locale.US)
        return amenity == "shelter"
    }

    private fun supportsOvernight(tags: JSONObject): Boolean {
        val tourism = tags.optString("tourism", "").lowercase(Locale.US)
        if (tourism in setOf("camp_site", "caravan_site", "motorhome_site")) return true

        val amenity = tags.optString("amenity", "").lowercase(Locale.US)
        if (amenity == "parking") {
            val overnight = tags.optString("overnight", "").lowercase(Locale.US)
            return overnight in setOf("yes", "designated", "permissive")
        }

        return false
    }

    private fun isOpenToday(tags: JSONObject): Boolean {
        val seasonal = tags.optString("seasonal", "").lowercase(Locale.US)
        if (seasonal == "yes") return false

        val openingHours = tags.optString("opening_hours", "").lowercase(Locale.US)
        if (openingHours.isBlank()) return true
        if (openingHours.contains("24/7")) return true

        val todayToken = dayToken(LocalDate.now().dayOfWeek).lowercase(Locale.US)
        val hasDayToken = Regex("\\b(mo|tu|we|th|fr|sa|su)\\b").containsMatchIn(openingHours)

        // If no explicit weekday is present, keep it permissive.
        if (!hasDayToken) return true

        // If a rule names today and marks it closed, treat as closed.
        val closedToday = openingHours
            .split(';')
            .map { it.trim() }
            .any { rule ->
                rule.contains(todayToken) && (rule.contains("off") || rule.contains("closed"))
            }
        if (closedToday) return false

        // Open when any rule explicitly includes today and exposes time/open marker.
        val openToday = openingHours
            .split(';')
            .map { it.trim() }
            .any { rule ->
                rule.contains(todayToken) && (
                    Regex("\\b\\d{1,2}:\\d{2}\\s*-\\s*\\d{1,2}:\\d{2}\\b").containsMatchIn(rule) ||
                        rule.contains("open")
                    )
            }

        return openToday
    }

    private fun dayToken(day: DayOfWeek): String {
        return when (day) {
            DayOfWeek.MONDAY -> "Mo"
            DayOfWeek.TUESDAY -> "Tu"
            DayOfWeek.WEDNESDAY -> "We"
            DayOfWeek.THURSDAY -> "Th"
            DayOfWeek.FRIDAY -> "Fr"
            DayOfWeek.SATURDAY -> "Sa"
            DayOfWeek.SUNDAY -> "Su"
        }
    }

    private fun formatCoord(value: Double): String = String.format(Locale.US, "%.5f", value)

    private fun haversineDistanceKm(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(toLat - fromLat)
        val dLon = Math.toRadians(toLon - fromLon)
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)

        val a = sin(dLat / 2).pow(2.0) + sin(dLon / 2).pow(2.0) * cos(lat1) * cos(lat2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusKm * c
    }
}
