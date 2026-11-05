package com.rolling7.solar

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Vibratia la pornirea/oprirea unei prize (cerere a userului, 2026-11-05): un impuls scurt cand
 * serverul confirma comanda, doua cand esueaza. Fara ea, cu becul in alta camera, nu stii daca
 * atingerea s-a luat si apesi din nou.
 */
internal object CasaHaptics {
    private val CONFIRM = longArrayOf(0, 70)
    private val REJECT = longArrayOf(0, 90, 110, 90)

    fun confirm(context: Context) = vibrate(context, CONFIRM)

    fun reject(context: Context) = vibrate(context, REJECT)

    private fun vibrate(context: Context, pattern: LongArray) {
        try {
            val vibrator = context.getSystemService(Vibrator::class.java) ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
            // vibratia e doar un semnal; o eroare a ei nu strica nimic
        }
    }
}
