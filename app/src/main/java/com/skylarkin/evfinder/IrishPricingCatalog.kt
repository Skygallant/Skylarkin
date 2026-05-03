package com.skylarkin.evfinder

import android.content.Context
import java.io.BufferedReader
import java.util.Locale

class IrishPricingCatalog private constructor(
    private val rows: List<Row>
) {

    data class Row(
        val operator: String,
        val brandOrSite: String,
        val network: String,
        val countryScope: String,
        val chargerType: String,
        val minPowerKw: Double?,
        val maxPowerKw: Double?,
        val pricePerKwh: Double?,
        val currency: String,
        val confidence: String
    )

    data class Match(
        val euroPerKwh: Double,
        val originalPricePerKwh: Double,
        val originalCurrency: String,
        val fxRateUsed: Double?,
        val confidence: String,
        val operator: String,
        val chargerType: String
    )

    fun findBestMatch(
        operatorName: String?,
        siteTitle: String?,
        inferredPowerKw: Double?,
        inferredCurrentType: String?,
        gbpToEurRate: Double?
    ): Match? {
        val normalizedOperator = normalize(operatorName)
        val normalizedSite = normalize(siteTitle)
        val currentType = normalize(inferredCurrentType)

        val scored = rows
            .asSequence()
            .filter { it.pricePerKwh != null }
            .filter { row ->
                val scope = normalize(row.countryScope)
                scope.isBlank() || scope.contains("ireland")
            }
            .mapNotNull { row ->
                val originalPrice = row.pricePerKwh ?: return@mapNotNull null
                val normalizedCurrency = normalizeCurrency(row.currency)
                val eurPrice = when (normalizedCurrency) {
                    "EUR" -> originalPrice
                    "GBP" -> gbpToEurRate?.let { originalPrice * it }
                    else -> null
                } ?: return@mapNotNull null

                val score = scoreRow(row, normalizedOperator, normalizedSite, inferredPowerKw, currentType)
                if (score <= 0) return@mapNotNull null
                Triple(score, row, Match(
                    euroPerKwh = eurPrice,
                    originalPricePerKwh = originalPrice,
                    originalCurrency = normalizedCurrency,
                    fxRateUsed = if (normalizedCurrency == "GBP") gbpToEurRate else null,
                    confidence = row.confidence.ifBlank { "Medium" },
                    operator = if (row.network.isNotBlank()) row.network else row.operator,
                    chargerType = row.chargerType.ifBlank { "Unknown" }
                ))
            }
            .sortedWith(compareByDescending<Triple<Int, Row, Match>> { it.first }.thenBy { it.third.euroPerKwh })
            .firstOrNull()
            ?: return null

        return scored.third
    }

    private fun scoreRow(
        row: Row,
        normalizedOperator: String,
        normalizedSite: String,
        inferredPowerKw: Double?,
        inferredCurrentType: String
    ): Int {
        var score = 0
        val rowOperator = normalize(row.operator)
        val rowBrand = normalize(row.brandOrSite)
        val rowNetwork = normalize(row.network)
        val rowChargerType = normalize(row.chargerType)

        if (normalizedOperator.isNotBlank()) {
            if (rowOperator.contains(normalizedOperator) || normalizedOperator.contains(rowOperator)) score += 50
            if (rowNetwork.contains(normalizedOperator) || normalizedOperator.contains(rowNetwork)) score += 35
            if (rowBrand.contains(normalizedOperator) || normalizedOperator.contains(rowBrand)) score += 25
        }

        if (normalizedSite.isNotBlank()) {
            if (rowBrand.isNotBlank() && normalizedSite.contains(rowBrand)) score += 35
            if (rowNetwork.isNotBlank() && normalizedSite.contains(rowNetwork)) score += 15
            if (rowOperator.isNotBlank() && normalizedSite.contains(rowOperator)) score += 10
        }

        if (inferredPowerKw != null) {
            val min = row.minPowerKw
            val max = row.maxPowerKw
            if ((min == null || inferredPowerKw >= min) && (max == null || inferredPowerKw <= max)) {
                score += 20
            }
        }

        if (inferredCurrentType.isNotBlank()) {
            if (inferredCurrentType.contains("dc") && rowChargerType.contains("dc")) score += 15
            if (inferredCurrentType.contains("ac") && rowChargerType.contains("ac")) score += 15
            if (inferredCurrentType.contains("hpc") && (rowChargerType.contains("hpc") || rowChargerType.contains("ultra"))) score += 15
        }

        return score
    }

    companion object {
        private const val ROI_ASSET_FILE_NAME = "irish_ev_charging_public_pricing_best_effort.csv"
        private const val NI_ASSET_FILE_NAME = "northern_ireland_ev_charging_public_pricing_best_effort.csv"

        fun fromAssets(context: Context): IrishPricingCatalog {
            val availableAssets = context.assets.list("")?.toSet().orEmpty()
            val sourceFiles = listOf(ROI_ASSET_FILE_NAME, NI_ASSET_FILE_NAME).filter { it in availableAssets }

            val rows = mutableListOf<Row>()
            for (assetName in sourceFiles) {
                val parsedRows = context.assets.open(assetName).bufferedReader().use { reader ->
                    parseCsv(reader)
                }
                rows.addAll(parsedRows)
            }

            return IrishPricingCatalog(rows)
        }

        private fun parseCsv(reader: BufferedReader): List<Row> {
            val lines = reader.readLines()
            if (lines.isEmpty()) return emptyList()

            val header = parseCsvLine(lines.first())
            val index = header.withIndex().associate { normalize(it.value) to it.index }

            fun field(columns: List<String>, key: String): String {
                val i = index[normalize(key)] ?: return ""
                return columns.getOrNull(i)?.trim().orEmpty()
            }

            fun fieldAny(columns: List<String>, vararg keys: String): String {
                for (key in keys) {
                    val value = field(columns, key)
                    if (value.isNotBlank()) return value
                }
                return ""
            }

            return lines.drop(1)
                .asSequence()
                .filter { it.isNotBlank() }
                .map { parseCsvLine(it) }
                .map { columns ->
                    Row(
                        operator = fieldAny(columns, "operator"),
                        brandOrSite = fieldAny(columns, "brand_or_site", "network_or_brand"),
                        network = fieldAny(columns, "network", "network_or_brand"),
                        countryScope = fieldAny(columns, "country_scope", "region"),
                        chargerType = fieldAny(columns, "charger_type", "charger_category"),
                        minPowerKw = fieldAny(columns, "min_power_kw", "power_kw_min").toDoubleOrNull(),
                        maxPowerKw = fieldAny(columns, "max_power_kw", "power_kw_max").toDoubleOrNull(),
                        pricePerKwh = field(columns, "price_per_kwh").toDoubleOrNull(),
                        currency = field(columns, "currency"),
                        confidence = fieldAny(columns, "confidence", "source_quality")
                    )
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

        private fun normalize(value: String?): String {
            return value
                ?.lowercase(Locale.US)
                ?.replace(Regex("[^a-z0-9]+"), " ")
                ?.trim()
                .orEmpty()
        }

        private fun normalizeCurrency(value: String?): String {
            val raw = value?.trim().orEmpty().uppercase(Locale.US)
            return when {
                raw.contains("EUR") -> "EUR"
                raw.contains("GBP") -> "GBP"
                else -> raw
            }
        }
    }
}

