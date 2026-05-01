package com.skylarkin.evfinder

data class ChargePoint(
    val id: Int,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val distanceKm: Double,
    val usageCost: String,
    val accessSummary: String,
    val directionCode: String
)
