package com.skylarkin.evfinder

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.LocationServices
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var retryButton: Button
    private lateinit var priceSlider: Slider
    private lateinit var priceValueText: TextView
    private lateinit var sortToggle: CompoundButton
    private lateinit var ignoreUnknownPriceToggle: CompoundButton

    private val adapter = ChargePointAdapter(::openInGoogleMaps)
    private val chargetripPricingClient by lazy {
        ChargetripPricingClient(
            clientId = BuildConfig.CHARGETRIP_CLIENT_ID,
            appId = BuildConfig.CHARGETRIP_APP_ID,
            appIdentifier = BuildConfig.CHARGETRIP_APP_IDENTIFIER,
            appFingerprint = BuildConfig.CHARGETRIP_APP_FINGERPRINT,
            storageDir = filesDir
        )
    }
    private val chargeMapClient by lazy {
        OpenChargeMapClient(
            apiKey = BuildConfig.OPEN_CHARGE_MAP_API_KEY,
            fxRateProvider = FrankfurterFxRateProvider(),
            chargetripPricingClient = chargetripPricingClient
        )
    }
    private var currentResults: List<ChargePoint> = emptyList()
    private var searchJob: Job? = null
    private var startupCacheWarmJob: Job? = null
    private var queryToken: Long = 0L
    private var didStartupCountryCheck = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            ensureLocationAndLoad(startup = true)
        } else {
            showStatus(
                "Location permission is needed to search nearby chargers within 50 km.",
                showRetry = true,
                loading = false
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.chargepointList)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        retryButton = findViewById(R.id.retryButton)
        priceSlider = findViewById(R.id.priceSlider)
        priceValueText = findViewById(R.id.priceValueText)
        sortToggle = findViewById(R.id.sortToggle)
        ignoreUnknownPriceToggle = findViewById(R.id.ignoreUnknownPriceToggle)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        priceSlider.stepSize = 1f
        updatePriceValueLabel()
        priceSlider.addOnChangeListener { _, _, _ ->
            updatePriceValueLabel()
        }
        sortToggle.setOnCheckedChangeListener { _, isChecked ->
            sortToggle.text = if (isChecked) "Farthest first" else "Closest first"
            renderCurrentResults()
        }
        ignoreUnknownPriceToggle.setOnCheckedChangeListener { _, _ ->
            renderCurrentResults()
        }

        retryButton.setOnClickListener { ensureLocationAndLoad() }

        ensureLocationAndLoad(startup = true)
    }

    private fun ensureLocationAndLoad(startup: Boolean = false) {
        if (hasLocationPermission()) {
            if (startup && !didStartupCountryCheck) {
                didStartupCountryCheck = true
                warmCountryOperatorCacheOnStartup()
            }
            loadChargePoints()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun warmCountryOperatorCacheOnStartup() {
        startupCacheWarmJob?.cancel()
        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        try {
            fusedClient.lastLocation
                .addOnSuccessListener(this) { location ->
                    if (location == null) {
                        return@addOnSuccessListener
                    }
                    startupCacheWarmJob = lifecycleScope.launch(Dispatchers.Default) {
                        val countryCode = runCatching {
                            chargetripPricingClient.resolveCountryCodeViaNominatimOnDemand(
                                latitude = location.latitude,
                                longitude = location.longitude
                            )
                        }.getOrDefault("")

                        if (countryCode.isNotBlank()) {
                            runCatching {
                                chargetripPricingClient.warmCountryCacheForLocation(
                                    latitude = location.latitude,
                                    longitude = location.longitude,
                                    countryCodeHint = countryCode
                                )
                            }
                        }
                    }
                }
                .addOnFailureListener(this) { }
        } catch (_: SecurityException) {
            // ignore warm cache failure
        }
    }

    private fun loadChargePoints() {
        searchJob?.cancel()
        val activeToken = ++queryToken

        val maxPriceEuro = selectedMaxPriceEuro()
        val maxPriceCent = (maxPriceEuro * 100.0).toInt()
        showStatus("Getting your current location...", showRetry = false, loading = true)

        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        try {
            fusedClient.lastLocation
                .addOnSuccessListener(this) { location ->
                    if (activeToken != queryToken) {
                        return@addOnSuccessListener
                    }
                    if (location == null) {
                        showStatus(
                            "Could not read current location. Turn on location services and retry.",
                            showRetry = true,
                            loading = false
                        )
                        return@addOnSuccessListener
                    }

                    searchJob = lifecycleScope.launch(Dispatchers.Default) {
                        val result = runCatching {
                            chargeMapClient.findMatchingChargePoints(
                                latitude = location.latitude,
                                longitude = location.longitude,
                                maxPriceEuroPerKwh = maxPriceEuro,
                                onStage = { stage ->
                                    val message = when (stage) {
                                        OpenChargeMapClient.SearchStage.FETCHING_POI ->
                                            "Fetching nearby chargepoints..."
                                        OpenChargeMapClient.SearchStage.OPERATOR_PAGINATION ->
                                            "Loading operator pricing pages..."
                                        OpenChargeMapClient.SearchStage.APPLYING_FILTERS ->
                                            "Matching prices and applying filters..."
                                        OpenChargeMapClient.SearchStage.ISLAND_FILTERING ->
                                            "Removing isolated chargepoint islands..."
                                    }
                                    runOnUiThread {
                                        if (activeToken == queryToken) {
                                            showStatus(message, showRetry = false, loading = true)
                                        }
                                    }
                                },
                                onFilterProgress = { detail ->
                                    runOnUiThread {
                                        if (activeToken == queryToken) {
                                            showStatus(detail, showRetry = false, loading = true)
                                        }
                                    }
                                }
                            )
                        }

                        withContext(Dispatchers.Main) {
                            if (activeToken != queryToken) return@withContext

                            result.onSuccess { chargePoints ->
                                currentResults = chargePoints
                                renderCurrentResults()
                                if (chargePoints.isEmpty()) {
                                    showStatus(
                                        "No chargepoints found with all filters (<= ${maxPriceCent}c/kWh, Type 2, public 24/7, within 50 km).",
                                        showRetry = true,
                                        loading = false
                                    )
                                } else {
                                    showStatus(
                                        "Found ${chargePoints.size} matching chargepoints. Tap one to open Google Maps.",
                                        showRetry = true,
                                        loading = false
                                    )
                                }
                            }.onFailure { error ->
                                showStatus(
                                    "Failed to load data: ${error.message ?: "unknown error"}",
                                    showRetry = true,
                                    loading = false
                                )
                            }
                        }
                    }
                }
                .addOnFailureListener(this) { error ->
                    if (activeToken != queryToken) {
                        return@addOnFailureListener
                    }
                    showStatus(
                        "Location error: ${error.message ?: "unknown error"}",
                        showRetry = true,
                        loading = false
                    )
                }
        } catch (se: SecurityException) {
            showStatus(
                "Location permission missing. Please allow location and retry.",
                showRetry = true,
                loading = false
            )
        }
    }

    override fun onDestroy() {
        searchJob?.cancel()
        startupCacheWarmJob?.cancel()
        recyclerView.adapter = null
        super.onDestroy()
    }

    private fun openInGoogleMaps(chargePoint: ChargePoint) {
        val uri = Uri.parse("geo:${chargePoint.latitude},${chargePoint.longitude}?q=${chargePoint.latitude},${chargePoint.longitude}(${Uri.encode(chargePoint.name)})")

        val googleMapsIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }

        try {
            startActivity(googleMapsIntent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            Toast.makeText(this, "Google Maps not found, opened with available map app.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun selectedMaxPriceEuro(): Double = priceSlider.value.toDouble() / 100.0

    private fun updatePriceValueLabel() {
        val cents = priceSlider.value.toInt()
        priceValueText.text = String.format(Locale.US, "Max price: %dc/kWh", cents)
    }

    private fun renderCurrentResults() {
        val filtered = if (ignoreUnknownPriceToggle.isChecked) {
            currentResults.filter { !it.usageCost.contains("price unknown", ignoreCase = true) }
        } else {
            currentResults
        }

        val sorted = if (sortToggle.isChecked) {
            filtered.sortedByDescending { it.distanceKm }
        } else {
            filtered.sortedBy { it.distanceKm }
        }
        adapter.submitList(sorted)
    }

    private fun showStatus(text: String, showRetry: Boolean, loading: Boolean) {
        statusText.text = text
        retryButton.visibility = if (showRetry) View.VISIBLE else View.GONE
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
