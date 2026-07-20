package com.rolling7.solar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stare partajata in-proces intre SolarAlarmService (care suna) si MainActivity (care afiseaza
 * pop-up-ul cu buton de oprire). Serviciul si activitatea ruleaza in acelasi proces, deci un
 * simplu StateFlow in memorie e suficient.
 */
object AlarmState {
    private val _ringing = MutableStateFlow(false)
    val ringing: StateFlow<Boolean> = _ringing

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun onRingStart(message: String?) {
        _message.value = message
        _ringing.value = true
    }

    fun onRingStop() {
        _ringing.value = false
    }
}
