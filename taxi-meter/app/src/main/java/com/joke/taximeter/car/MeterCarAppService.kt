package com.joke.taximeter.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** 안드로이드 오토가 붙는 입구. 개인용(사이드로드)이라 모든 호스트를 허용한다. */
class MeterCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onCreateSession(): Session = MeterSession()
}

class MeterSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = MeterScreen(carContext)
}
