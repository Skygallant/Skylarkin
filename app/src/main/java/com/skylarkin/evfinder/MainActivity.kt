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
    private companion object {
        const val USE_DETAILED_LOADING_MESSAGES = false
        const val SIMPLE_LOADING_MESSAGE = "Searching nearby chargepoints..."
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var retryButton: Button
    private lateinit var sortToggle: CompoundButton
    private lateinit var hideUnknownFleetToggle: CompoundButton
    private lateinit var scoreSlider: Slider
    private lateinit var scoreValueText: TextView

    private val adapter = ChargePointAdapter(::openInGoogleMaps)
    private val operatorScoreCatalog by lazy { OcmOperatorScoreCatalog.fromAssets(this) }
    private val chargeMapClient by lazy {
        OpenChargeMapClient(
            apiKey = BuildConfig.OPEN_CHARGE_MAP_API_KEY,
            operatorScoreCatalog = operatorScoreCatalog
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
        hideUnknownFleetToggle = findViewById(R.id.hideUnknownFleetToggle)
        scoreSlider = findViewById(R.id.scoreSlider)
        scoreValueText = findViewById(R.id.scoreValueText)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        sortToggle.setOnCheckedChangeListener { _, isChecked ->
            sortToggle.text = if (isChecked) "Farthest first" else "Closest first"
            renderCurrentResults()
        }
        hideUnknownFleetToggle.setOnCheckedChangeListener { _, _ ->
            renderCurrentResults()
        }

        scoreSlider.stepSize = 1f
        updateScoreValueLabel()
        scoreSlider.addOnChangeListener { _, _, _ ->
            updateScoreValueLabel()
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
                                val visibleCount = renderCurrentResults()
                                if (chargePoints.isEmpty()) {
                                    showStatus(
                                        "No chargepoints found with all filters (Type 2, public 24/7, within 50 km).",
                                        showRetry = true,
                                        loading = false
                                    )
                                } else {
                                    showStatus(
                                        "Found $visibleCount matching chargepoints. Tap one to open Google Maps.",
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

    private fun renderCurrentResults(): Int {
        val maxScore = selectedMaxCombinedScore()
        val scoreFiltered = currentResults.filter { point ->
            point.combinedCostScore?.let { it <= maxScore } ?: true
        }
        val unknownFleetFiltered = if (hideUnknownFleetToggle.isChecked) {
            scoreFiltered.filter { point ->
                !point.isFleetOperator && point.combinedCostScore != null
            }
        } else {
            scoreFiltered
        }

        val sorted = if (sortToggle.isChecked) {
            unknownFleetFiltered.sortedByDescending { it.distanceKm }
        } else {
            unknownFleetFiltered.sortedBy { it.distanceKm }
        }
        adapter.submitList(sorted)
        if (currentResults.isNotEmpty() && progressBar.visibility != View.VISIBLE) {
            showStatus(
                "Found ${sorted.size} matching chargepoints. Tap one to open Google Maps.",
                showRetry = true,
                loading = false
            )
        }
        return sorted.size
    }

    private fun selectedMaxCombinedScore(): Double = scoreSlider.value.toDouble()

    private fun updateScoreValueLabel() {
        val score = scoreSlider.value.toInt()
        scoreValueText.text = String.format(Locale.US, "Cost score: %d/10", score)
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
