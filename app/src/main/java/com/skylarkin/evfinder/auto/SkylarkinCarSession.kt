package com.skylarkin.evfinder.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class SkylarkinCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = SkylarkinQuickLaunchScreen(carContext)
}
