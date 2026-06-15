package com.rolling7.solar

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.content.ContextCompat

data class AlarmSettings(
    val enabled: Boolean,
    val thresholdW: Int,
    val cooldownS: Int,
    val vibrate: Boolean,
    val ringtoneUri: String?
) {
    val clearThresholdW: Int get() = (thresholdW - 200).coerceAtLeast(0)
}

object AlarmSettingsStore {
    private const val PREFS = "solar_alarm_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_THRESHOLD_W = "threshold_w"
    private const val KEY_COOLDOWN_S = "cooldown_s"
    private const val KEY_VIBRATE = "vibrate"
    private const val KEY_RINGTONE_URI = "ringtone_uri"

    fun read(context: Context): AlarmSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AlarmSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            thresholdW = prefs.getInt(KEY_THRESHOLD_W, 5000),
            cooldownS = prefs.getInt(KEY_COOLDOWN_S, 300),
            vibrate = prefs.getBoolean(KEY_VIBRATE, true),
            ringtoneUri = prefs.getString(KEY_RINGTONE_URI, null)
        )
    }

    fun save(context: Context, settings: AlarmSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putInt(KEY_THRESHOLD_W, settings.thresholdW)
            .putInt(KEY_COOLDOWN_S, settings.cooldownS)
            .putBoolean(KEY_VIBRATE, settings.vibrate)
            .putString(KEY_RINGTONE_URI, settings.ringtoneUri)
            .apply()
    }

    fun ringtoneUri(settings: AlarmSettings): Uri =
        settings.ringtoneUri?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    fun ringtoneTitle(context: Context, settings: AlarmSettings): String {
        val uri = ringtoneUri(settings)
        return RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: "Sunet default"
    }

    fun applyServiceState(context: Context, settings: AlarmSettings) {
        if (settings.enabled) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SolarAlarmService::class.java).setAction(SolarAlarmService.ACTION_START)
            )
        } else {
            context.startService(
                Intent(context, SolarAlarmService::class.java).setAction(SolarAlarmService.ACTION_STOP)
            )
        }
    }

    fun testAlarm(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, SolarAlarmService::class.java).setAction(SolarAlarmService.ACTION_TEST)
        )
    }
}
