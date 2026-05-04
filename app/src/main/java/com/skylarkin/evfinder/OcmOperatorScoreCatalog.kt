package com.skylarkin.evfinder

import android.content.Context
import java.io.BufferedReader
import java.util.Locale

class OcmOperatorScoreCatalog private constructor(
    private val byOperatorId: Map<Int, Entry>
) {
    data class Entry(
        val operatorName: String,
        val combinedCostScore: Double?,
        val isFleetOperator: Boolean
    )

    fun findByOperatorId(operatorId: Int?): Entry? {
        if (operatorId == null || operatorId <= 0) return null
        return byOperatorId[operatorId]
    }

    companion object {
        private const val ASSET_FILE = "ocm_operator_public_ev_charging_price_rankings_with_combined_cost_score.csv"

        fun fromAssets(context: Context): OcmOperatorScoreCatalog {
            val assets = context.assets.list("")?.toSet().orEmpty()
            if (ASSET_FILE !in assets) {
                return OcmOperatorScoreCatalog(emptyMap())
            }

            val rows = context.assets.open(ASSET_FILE).bufferedReader().use { parseCsv(it) }
            val map = linkedMapOf<Int, Entry>()
            for (row in rows) {
                val ids = parseOperatorIds(row.ocmOperatorIds)
                if (ids.isEmpty()) continue
                val fleet = inferFleet(row)
                val entry = Entry(
                    operatorName = row.operator,
                    combinedCostScore = row.combinedCostScore,
                    isFleetOperator = fleet
                )
                ids.forEach { id ->
                    map.putIfAbsent(id, entry)
                }
            }
            return OcmOperatorScoreCatalog(map)
        }

        private data class CsvRow(
            val operator: String,
            val ocmOperatorIds: String,
            val combinedCostScore: Double?,
            val proxyCategory: String,
            val estimateBasis: String,
            val notes: String
        )

        private fun parseCsv(reader: BufferedReader): List<CsvRow> {
            val lines = reader.readLines()
            if (lines.isEmpty()) return emptyList()

            val header = parseCsvLine(lines.first())
            val index = header.withIndex().associate { normalize(it.value) to it.index }

            fun field(columns: List<String>, key: String): String {
                val i = index[normalize(key)] ?: return ""
                return columns.getOrNull(i)?.trim().orEmpty()
            }

            return lines.drop(1)
                .asSequence()
                .filter { it.isNotBlank() }
                .map { parseCsvLine(it) }
                .map { columns ->
                    CsvRow(
                        operator = field(columns, "operator"),
                        ocmOperatorIds = field(columns, "ocm_operator_ids"),
                        combinedCostScore = field(columns, "combined_cost_score_1_to_10_expensive_high").toDoubleOrNull(),
                        proxyCategory = field(columns, "proxy_category"),
                        estimateBasis = field(columns, "estimate_basis"),
                        notes = field(columns, "notes")
                    )
                }
                .toList()
        }

        private fun parseOperatorIds(raw: String): List<Int> {
            if (raw.isBlank()) return emptyList()
            return raw.split(',', ';', '|', ' ')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { it.toIntOrNull() }
                .filter { it > 0 }
                .distinct()
        }

        private fun inferFleet(row: CsvRow): Boolean {
            val text = listOf(row.proxyCategory, row.estimateBasis, row.notes)
                .joinToString(" ")
                .lowercase(Locale.US)
            if (text.isBlank()) return false
            if (text.contains("fleet")) return true
            if (text.contains("workplace")) return true
            if (text.contains("dealer/host-set/restricted")) return true
            if (text.contains("not comparable to normal public cpo pricing")) return true
            return false
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
    }
}

