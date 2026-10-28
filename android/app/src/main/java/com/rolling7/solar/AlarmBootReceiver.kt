package com.rolling7.solar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Reporneste serviciul de alarma dupa repornirea telefonului si dupa instalarea unei versiuni noi a
 * aplicatiei. Altfel, cu alarmele pornite in setari, telefonul nu mai suna pana nu deschizi aplicatia.
 */
class AlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = AlarmSettingsStore.read(context)
        if (!settings.anyEnabled) return
        try {
            AlarmSettingsStore.applyServiceState(context, settings)
        } catch (e: Exception) {
            // Sistemul poate refuza pornirea din fundal; serviciul porneste oricum la deschiderea aplicatiei.
            Log.w("SolarAlarm", "nu pot porni alarma dupa ${intent.action}", e)
        }
    }
}
