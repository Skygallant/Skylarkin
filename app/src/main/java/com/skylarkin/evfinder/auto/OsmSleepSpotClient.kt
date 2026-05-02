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
    }

    suspend fun findNearestSleepSpot(userLatitude: Double, userLongitude: Double): OsmSleepSpot? {
        return withContext(Dispatchers.IO) {
            for (radius in RADIUS_STEPS_METERS) {
                val nearest = runCatching {
                    findNearestWithinRadius(userLatitude, userLongitude, radius)
                }.getOrNull()
                if (nearest != null) return@withContext nearest
            }
            null
        }
    }

    private fun findNearestWithinRadius(
        userLatitude: Double,
        userLongitude: Double,
        radiusMeters: Int
    ): OsmSleepSpot? {
        val payload = "data=" + URLEncoder.encode(
            buildOverpassQuery(userLatitude, userLongitude, radiusMeters),
            Charsets.UTF_8.name()
        )

        val connection = (URL(OVERPASS_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Skylarkin/1.0 (Android Auto sleep lookup)")
            connectTimeout = 20_000
            readTimeout = 20_000
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

        val elements = JSONObject(responseText).optJSONArray("elements") ?: JSONArray()
        val candidates = mutableListOf<OsmSleepSpot>()
        val seenKeys = HashSet<String>()

        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            val tags = element.optJSONObject("tags") ?: JSONObject()

            if (!isFree(tags)) continue
            if (!hasShowerAccess(tags)) continue
            if (!supportsOvernight(tags)) continue
            if (!isOpenToday(tags)) continue

            val latLon = extractLatLon(element) ?: continue
            val lat = latLon.first
            val lon = latLon.second
            val name = elementName(tags)
            val dedupeKey = "${name.lowercase(Locale.US)}|${formatCoord(lat)}|${formatCoord(lon)}"
            if (!seenKeys.add(dedupeKey)) continue

            val distance = haversineDistanceKm(userLatitude, userLongitude, lat, lon)
            candidates.add(
                OsmSleepSpot(
                    name = name,
                    latitude = lat,
                    longitude = lon,
                    distanceKm = distance
                )
            )
        }

        return candidates.minByOrNull { it.distanceKm }
    }

    private fun buildOverpassQuery(latitude: Double, longitude: Double, radiusMeters: Int): String {
        val lat = String.format(Locale.US, "%.6f", latitude)
        val lon = String.format(Locale.US, "%.6f", longitude)
        return """
            [out:json][timeout:25];
            (
              nwr(around:$radiusMeters,$lat,$lon)["amenity"="parking"]["overnight"~"^(yes|designated|permissive)$"]["shower"~"^(yes|designated|customers|public)$"]["fee"~"^(no|free|0)$"];
              nwr(around:$radiusMeters,$lat,$lon)["tourism"~"^(camp_site|caravan_site|motorhome_site)$"]["shower"~"^(yes|designated|customers|public)$"]["fee"~"^(no|free|0)$"];
            );
            out center tags;
        """.trimIndent()
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
