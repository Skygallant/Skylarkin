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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private companion object {
        const val USE_DETAILED_LOADING_MESSAGES = false
        const val SIMPLE_LOADING_MESSAGE = "Searching nearby chargepoints..."
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var retryButton: Button
    private lateinit var sortToggle: CompoundButton

    private val adapter = ChargePointAdapter(::openInGoogleMaps)
    private val chargeMapClient by lazy {
        OpenChargeMapClient(
            apiKey = BuildConfig.OPEN_CHARGE_MAP_API_KEY
        )
    }
    private var currentResults: List<ChargePoint> = emptyList()
    private var searchJob: Job? = null
    private var queryToken: Long = 0L

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            ensureLocationAndLoad()
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
        sortToggle = findViewById(R.id.sortToggle)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        sortToggle.setOnCheckedChangeListener { _, isChecked ->
            sortToggle.text = if (isChecked) "Farthest first" else "Closest first"
            renderCurrentResults()
        }

        retryButton.setOnClickListener { ensureLocationAndLoad() }

        ensureLocationAndLoad()
    }

    private fun ensureLocationAndLoad() {
        if (hasLocationPermission()) {
            loadChargePoints()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun loadChargePoints() {
        searchJob?.cancel()
        val activeToken = ++queryToken

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
                                onStage = { stage ->
                                    val message = if (USE_DETAILED_LOADING_MESSAGES) {
                                        detailedStageMessage(stage)
                                    } else {
                                        SIMPLE_LOADING_MESSAGE
                                    }
                                    runOnUiThread {
                                        if (activeToken == queryToken) {
                                            showStatus(message, showRetry = false, loading = true)
                                        }
                                    }
                                },
                                onFilterProgress = { detail ->
                                    val message = if (USE_DETAILED_LOADING_MESSAGES) {
                                        detail
                                    } else {
                                        SIMPLE_LOADING_MESSAGE
                                    }
                                    runOnUiThread {
                                        if (activeToken == queryToken) {
                                            showStatus(message, showRetry = false, loading = true)
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
                                        "No chargepoints found with all filters (Type 2, public 24/7, within 50 km).",
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

    private fun renderCurrentResults() {
        val sorted = if (sortToggle.isChecked) {
            currentResults.sortedByDescending { it.distanceKm }
        } else {
            currentResults.sortedBy { it.distanceKm }
        }
        adapter.submitList(sorted)
    }

    private fun showStatus(text: String, showRetry: Boolean, loading: Boolean) {
        statusText.text = text
        retryButton.visibility = if (showRetry) View.VISIBLE else View.GONE
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun detailedStageMessage(stage: OpenChargeMapClient.SearchStage): String {
        return when (stage) {
            OpenChargeMapClient.SearchStage.FETCHING_POI ->
                "Fetching nearby chargepoints..."
            OpenChargeMapClient.SearchStage.OPERATOR_PAGINATION ->
                "Preparing search..."
            OpenChargeMapClient.SearchStage.APPLYING_FILTERS ->
                "Applying filters..."
            OpenChargeMapClient.SearchStage.ISLAND_FILTERING ->
                "Removing isolated chargepoint islands..."
        }
    }
}
