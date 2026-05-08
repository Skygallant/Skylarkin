package com.skylarkin.evfinder.auto

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class SkylarkinCarAppService : CarAppService() {

    override fun onCreateSession(): Session = SkylarkinCarSession()

    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
}
