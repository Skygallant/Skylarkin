package com.skylarkin.evfinder

interface FxRateProvider {
    suspend fun getRate(baseCurrency: String, quoteCurrency: String): Double?
}

object NoOpFxRateProvider : FxRateProvider {
    override suspend fun getRate(baseCurrency: String, quoteCurrency: String): Double? = null
}
