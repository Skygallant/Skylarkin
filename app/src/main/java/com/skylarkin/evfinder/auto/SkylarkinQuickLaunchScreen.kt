package com.skylarkin.evfinder.auto

import android.content.Intent
import android.location.Location
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import com.google.android.gms.location.LocationServices
import com.skylarkin.evfinder.BuildConfig
import com.skylarkin.evfinder.ChargePoint
import com.skylarkin.evfinder.FrankfurterFxRateProvider
import com.skylarkin.evfinder.IrishPricingCatalog
import com.skylarkin.evfinder.OpenChargeMapClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.io.BufferedReader
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class SkylarkinQuickLaunchScreen(
    carContext: CarContext
) : Screen(carContext) {

    private companion object {
        const val MAX_PRICE_EUR_PER_KWH = 0.50
        const val APPLEGREEN_ASSET_FILE = "applegreen_roi_motorway_service_stations_best_effort.csv"
    }

    private data class ApplegreenSite(
        val name: String,
        val latitude: Double,
        val longitude: Double
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chargeMapClient: OpenChargeMapClient

    private var isLoading = true
    private var errorMessage: String? = null
    private var matches: List<ChargePoint> = emptyList()
    private var csvOnly = true
    private var lastKnownLocation: Location? = null
    private var pendingDirectionLaunch: String? = null
    private val applegreenSites: List<ApplegreenSite> by lazy { loadApplegreenSites() }

    init {
        val pricingCatalog = runCatching { IrishPricingCatalog.fromAssets(carContext) }.getOrNull()
        chargeMapClient = OpenChargeMapClient(
            apiKey = BuildConfig.OPEN_CHARGE_MAP_API_KEY,
            irishPricingCatalog = pricingCatalog,
            fxRateProvider = FrankfurterFxRateProvider()
        )
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
            }
        })
        reloadMatches()
    }

    override fun onGetTemplate(): Template {
        if (isLoading) {
            return ListTemplate.Builder()
                .setHeader(
                    Header.Builder()
                        .setTitle("Skylarkin Quick Launch")
                        .setStartHeaderAction(Action.APP_ICON)
                        .build()
                )
                .setLoading(true)
                .build()
        }

        val failure = errorMessage
        if (failure != null) {
            return ListTemplate.Builder()
                .setHeader(
                    Header.Builder()
                        .setTitle("Skylarkin Quick Launch")
                        .setStartHeaderAction(Action.APP_ICON)
                        .build()
                )
                .setSingleList(
                    ItemList.Builder()
                        .addItem(
                            Row.Builder()
                                .setTitle("Could not load chargers")
                                .addText(failure)
                                .build()
                        )
                        .addItem(
                            Row.Builder()
                                .setTitle("Retry")
                                .setOnClickListener { reloadMatches() }
                                .build()
                        )
                        .build()
                )
                .build()
        }

        val candidates = filteredCandidates()
        val listBuilder = ItemList.Builder()

        listBuilder.addItem(directionRow("N", candidates))
        listBuilder.addItem(directionRow("S", candidates))
        listBuilder.addItem(directionRow("E", candidates))
        listBuilder.addItem(directionRow("W", candidates))
        listBuilder.addItem(
            Row.Builder()
                .setTitle("CSV pricing only")
                .addText("Exclude chargers not matched by pricing CSV")
                .setToggle(
                    Toggle.Builder { isChecked ->
                        csvOnly = isChecked
                        invalidate()
                    }
                        .setChecked(csvOnly)
                        .build()
                )
                .build()
        )
        listBuilder.addItem(sleepRow())

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("Skylarkin Quick Launch")
                    .setStartHeaderAction(Action.APP_ICON)
                    .build()
            )
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun reloadMatches() {
        isLoading = true
        errorMessage = null
        invalidate()

        scope.launch {
            runCatching {
                val location = awaitLastLocation()
                    ?: throw IllegalStateException("Current location is unavailable.")
                lastKnownLocation = location
                chargeMapClient.findMatchingChargePoints(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    maxPriceEuroPerKwh = MAX_PRICE_EUR_PER_KWH
                )
            }.onSuccess { loaded ->
                carContext.mainExecutor.execute {
                    isLoading = false
                    errorMessage = null
                    matches = loaded
                    val pending = pendingDirectionLaunch
                    if (pending != null) {
                        pendingDirectionLaunch = null
                        launchDirectionFromMatches(pending, loaded)
                    }
                    invalidate()
                }
            }.onFailure { error ->
                carContext.mainExecutor.execute {
                    isLoading = false
                    errorMessage = error.message ?: "Unknown error"
                    matches = emptyList()
                    invalidate()
                }
            }
        }
    }

    private suspend fun awaitLastLocation(): Location? = suspendCancellableCoroutine { cont ->
        val fusedClient = LocationServices.getFusedLocationProviderClient(carContext)
        try {
            fusedClient.lastLocation
                .addOnSuccessListener { location ->
                    if (cont.isActive) {
                        cont.resume(location)
                    }
                }
                .addOnFailureListener { error ->
                    if (cont.isActive) {
                        cont.resumeWithException(error)
                    }
                }
        } catch (se: SecurityException) {
            if (cont.isActive) {
                cont.resumeWithException(se)
            }
        }
    }

    private fun filteredCandidates(): List<ChargePoint> {
        return filteredCandidates(matches)
    }

    private fun filteredCandidates(source: List<ChargePoint>): List<ChargePoint> {
        if (!csvOnly) return source
        return source.filter { it.usageCost.startsWith("Est.", ignoreCase = true) }
    }

    private fun directionRow(cardinal: String, candidates: List<ChargePoint>): Row {
        val directionMatches = candidates.filter { matchesGeneralDirection(it.directionCode, cardinal) }
        val directionLabel = when (cardinal) {
            "N" -> "North"
            "S" -> "South"
            "E" -> "East"
            "W" -> "West"
            else -> cardinal
        }
        return Row.Builder()
            .setTitle(directionLabel)
            .addText("${directionMatches.size} matches")
            .setOnClickListener {
                pendingDirectionLaunch = cardinal
                reloadMatches()
            }
            .build()
    }

    private fun launchDirectionFromMatches(cardinal: String, sourceMatches: List<ChargePoint>) {
        val candidates = filteredCandidates(sourceMatches)
        val directionMatches = candidates.filter { matchesGeneralDirection(it.directionCode, cardinal) }
        val farthest = directionMatches.maxByOrNull { it.distanceKm }
        if (farthest == null) {
            CarToast.makeText(
                carContext,
                "No matching chargers for $cardinal.",
                CarToast.LENGTH_SHORT
            ).show()
        } else {
            navigateToChargePoint(farthest)
        }
    }

    private fun sleepRow(): Row {
        return Row.Builder()
            .setTitle("Sleep")
            .addText("Navigate to nearest Applegreen service station")
            .setOnClickListener { startSleepNavigation() }
            .build()
    }

    private fun startSleepNavigation() {
        if (applegreenSites.isEmpty()) {
            CarToast.makeText(
                carContext,
                "No Applegreen stations available in CSV.",
                CarToast.LENGTH_SHORT
            ).show()
            return
        }

        scope.launch {
            val location = lastKnownLocation ?: awaitLastLocation()
            if (location == null) {
                carContext.mainExecutor.execute {
                    CarToast.makeText(
                        carContext,
                        "Current location unavailable.",
                        CarToast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            lastKnownLocation = location
            val nearest = applegreenSites.minByOrNull {
                haversineDistanceKm(location.latitude, location.longitude, it.latitude, it.longitude)
            } ?: return@launch

            carContext.mainExecutor.execute {
                navigateToCoordinates(nearest.latitude, nearest.longitude, nearest.name)
            }
        }
    }

    private fun navigateToChargePoint(target: ChargePoint) {
        navigateToCoordinates(target.latitude, target.longitude, target.name)
    }

    private fun navigateToCoordinates(latitude: Double, longitude: Double, label: String) {
        val uri = Uri.parse("geo:$latitude,$longitude")
        val navIntent = Intent(CarContext.ACTION_NAVIGATE, uri)
        runCatching { carContext.startCarApp(navIntent) }
            .onFailure {
                CarToast.makeText(
                    carContext,
                    "Could not open navigation for $label.",
                    CarToast.LENGTH_SHORT
                ).show()
            }
    }

    private fun matchesGeneralDirection(directionCode: String, cardinal: String): Boolean {
        val code = directionCode.uppercase(Locale.US)
        return when (cardinal) {
            "N" -> code in setOf("N", "NNE", "NNW", "NE", "NW")
            "S" -> code in setOf("S", "SSE", "SSW", "SE", "SW")
            "E" -> code in setOf("E", "ENE", "ESE", "NE", "SE")
            "W" -> code in setOf("W", "WNW", "WSW", "NW", "SW")
            else -> false
        }
    }

    private fun loadApplegreenSites(): List<ApplegreenSite> {
        return runCatching {
            carContext.assets.open(APPLEGREEN_ASSET_FILE).bufferedReader().use { reader ->
                parseApplegreenCsv(reader)
            }
        }.getOrDefault(emptyList())
    }

    private fun parseApplegreenCsv(reader: BufferedReader): List<ApplegreenSite> {
        val lines = reader.readLines()
        if (lines.isEmpty()) return emptyList()

        val header = parseCsvLine(lines.first())
        val index = header.withIndex().associate { it.value.trim().lowercase(Locale.US) to it.index }

        fun field(columns: List<String>, key: String): String {
            val i = index[key] ?: return ""
            return columns.getOrNull(i)?.trim().orEmpty()
        }

        return lines.drop(1)
            .asSequence()
            .filter { it.isNotBlank() }
            .map { parseCsvLine(it) }
            .mapNotNull { columns ->
                val name = field(columns, "site_name").ifBlank { "Applegreen" }
                val lat = field(columns, "latitude").toDoubleOrNull() ?: return@mapNotNull null
                val lon = field(columns, "longitude").toDoubleOrNull() ?: return@mapNotNull null
                ApplegreenSite(name = name, latitude = lat, longitude = lon)
            }
            .toList()
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result
    }

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
