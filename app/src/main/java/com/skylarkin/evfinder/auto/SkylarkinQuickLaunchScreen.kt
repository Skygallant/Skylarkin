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
import com.skylarkin.evfinder.OcmOperatorScoreCatalog
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
import java.util.Locale

class SkylarkinQuickLaunchScreen(
    carContext: CarContext
) : Screen(carContext) {
    private companion object {
        const val USE_DETAILED_LOADING_MESSAGES = false
        const val SIMPLE_LOADING_MESSAGE = "Searching nearby chargepoints..."
        const val AUTO_MAX_COMBINED_SCORE = 6.0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operatorScoreCatalog = OcmOperatorScoreCatalog.fromAssets(carContext)
    private val chargeMapClient: OpenChargeMapClient
    private val sleepSpotClient = OsmSleepSpotClient()

    private var isLoading = false
    private var loadingStatusText: String = "Preparing search..."
    private var errorMessage: String? = null
    private var matches: List<ChargePoint> = emptyList()
    private var ignorePriceScoring = false
    private var requireShowers = false
    private var lastKnownLocation: Location? = null
    private var pendingDirectionLaunch: String? = null

    init {
        chargeMapClient = OpenChargeMapClient(
            apiKey = BuildConfig.OPEN_CHARGE_MAP_API_KEY,
            operatorScoreCatalog = operatorScoreCatalog
        )
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
            }
        })
    }

    override fun onGetTemplate(): Template {
        if (isLoading) {
            val loadingRow = Row.Builder()
                .setTitle("Loading")
                .addText(loadingStatusText)
                .build()
            return ListTemplate.Builder()
                .setHeader(
                    Header.Builder()
                        .setTitle("Skylarkin Quick Launch")
                        .setStartHeaderAction(Action.APP_ICON)
                        .build()
                )
                .setSingleList(
                    ItemList.Builder()
                        .addItem(loadingRow)
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

        val listBuilder = ItemList.Builder()

        listBuilder.addItem(directionRow("N"))
        listBuilder.addItem(directionRow("S"))
        listBuilder.addItem(directionRow("E"))
        listBuilder.addItem(directionRow("W"))
        listBuilder.addItem(ignorePriceScoringRow())
        listBuilder.addItem(sleepRow())
        listBuilder.addItem(requireShowersRow())

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
        loadingStatusText = "Getting current location..."
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
                    onStage = { stage ->
                        val status = if (USE_DETAILED_LOADING_MESSAGES) {
                            detailedStageMessage(stage)
                        } else {
                            SIMPLE_LOADING_MESSAGE
                        }
                        carContext.mainExecutor.execute {
                            if (isLoading) {
                                loadingStatusText = status
                                invalidate()
                            }
                        }
                    },
                    onFilterProgress = { detail ->
                        val status = if (USE_DETAILED_LOADING_MESSAGES) {
                            detail
                        } else {
                            SIMPLE_LOADING_MESSAGE
                        }
                        carContext.mainExecutor.execute {
                            if (isLoading) {
                                loadingStatusText = status
                                invalidate()
                            }
                        }
                    }
                )
            }.onSuccess { loaded ->
                carContext.mainExecutor.execute {
                    isLoading = false
                    loadingStatusText = "Preparing search..."
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
                    loadingStatusText = "Preparing search..."
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
        val withoutUnknownAndFleet = source.filter { point ->
            !point.isFleetOperator && point.combinedCostScore != null
        }
        if (ignorePriceScoring) return withoutUnknownAndFleet
        return withoutUnknownAndFleet.filter { point ->
            point.combinedCostScore?.let { it <= AUTO_MAX_COMBINED_SCORE } == true
        }
    }

    private fun directionRow(cardinal: String): Row {
        val directionLabel = when (cardinal) {
            "N" -> "North"
            "S" -> "South"
            "E" -> "East"
            "W" -> "West"
            else -> cardinal
        }
        return Row.Builder()
            .setTitle(directionLabel)
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
            .setOnClickListener { startSleepNavigation() }
            .build()
    }

    private fun ignorePriceScoringRow(): Row {
        return Row.Builder()
            .setTitle("Ignore price scoring")
            .setToggle(
                Toggle.Builder { isChecked ->
                    ignorePriceScoring = isChecked
                    invalidate()
                }
                    .setChecked(ignorePriceScoring)
                    .build()
            )
            .build()
    }

    private fun startSleepNavigation() {
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
            val nearest = runCatching {
                sleepSpotClient.findNearestSleepSpot(
                    userLatitude = location.latitude,
                    userLongitude = location.longitude,
                    requireShowers = requireShowers
                )
            }.getOrNull()

            carContext.mainExecutor.execute {
                if (nearest == null) {
                    val criteria = if (requireShowers) {
                        "free, overnight, open-today, shower"
                    } else {
                        "free, overnight, open-today"
                    }
                    CarToast.makeText(
                        carContext,
                        "No OSM sleep spot matched ($criteria).",
                        CarToast.LENGTH_SHORT
                    ).show()
                } else {
                    navigateToCoordinates(nearest.latitude, nearest.longitude, nearest.name)
                }
            }
        }
    }

    private fun requireShowersRow(): Row {
        return Row.Builder()
            .setTitle("Require showers")
            .setToggle(
                Toggle.Builder { isChecked ->
                    requireShowers = isChecked
                    invalidate()
                }
                    .setChecked(requireShowers)
                    .build()
            )
            .build()
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

    private fun detailedStageMessage(stage: OpenChargeMapClient.SearchStage): String {
        return when (stage) {
            OpenChargeMapClient.SearchStage.FETCHING_POI -> "Fetching nearby chargepoints..."
            OpenChargeMapClient.SearchStage.OPERATOR_PAGINATION -> "Preparing search..."
            OpenChargeMapClient.SearchStage.APPLYING_FILTERS -> "Applying filters..."
            OpenChargeMapClient.SearchStage.ISLAND_FILTERING -> "Checking island routing without ferries..."
        }
    }
}
