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
import java.util.Locale

class SkylarkinQuickLaunchScreen(
    carContext: CarContext
) : Screen(carContext) {

    private companion object {
        const val MAX_PRICE_EUR_PER_KWH = 0.50
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chargeMapClient: OpenChargeMapClient

    private var isLoading = true
    private var errorMessage: String? = null
    private var matches: List<ChargePoint> = emptyList()
    private var csvOnly = true

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

        listBuilder.addItem(directionRow("N", candidates))
        listBuilder.addItem(directionRow("S", candidates))
        listBuilder.addItem(directionRow("E", candidates))
        listBuilder.addItem(directionRow("W", candidates))
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Refresh chargers")
                .setOnClickListener { reloadMatches() }
                .build()
        )

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
        if (!csvOnly) return matches
        return matches.filter { it.usageCost.startsWith("Est.", ignoreCase = true) }
    }

    private fun directionRow(cardinal: String, candidates: List<ChargePoint>): Row {
        val directionMatches = candidates.filter { matchesGeneralDirection(it.directionCode, cardinal) }
        return Row.Builder()
            .setTitle("Launch $cardinal")
            .addText("${directionMatches.size} matches")
            .setOnClickListener {
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
            .build()
    }

    private fun navigateToChargePoint(target: ChargePoint) {
        val uri = Uri.parse("geo:${target.latitude},${target.longitude}")
        val navIntent = Intent(CarContext.ACTION_NAVIGATE, uri)
        runCatching { carContext.startCarApp(navIntent) }
            .onFailure {
                CarToast.makeText(
                    carContext,
                    "Could not open navigation for ${target.name}.",
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
}
