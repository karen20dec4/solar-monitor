package com.rolling7.solar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class SolarAlarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var alarmStopJob: Job? = null
    private var ringtone: Ringtone? = null
    private var consecutiveHigh = 0
    private var armed = true
    private var lastAlarmAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_SILENCE -> {
                stopAlarmSound()
                return START_STICKY
            }
            ACTION_TEST -> {
                val settings = AlarmSettingsStore.read(this)
                startInForeground(settings, null)
                triggerAlarm(settings, null, test = true)
                if (!settings.enabled) {
                    scope.launch {
                        delay(30_000)
                        stopEverything()
                    }
                }
                return START_NOT_STICKY
            }
            else -> {
                val settings = AlarmSettingsStore.read(this)
                startInForeground(settings, null)
                startMonitorLoop()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAlarmSound()
        monitorJob?.cancel()
        alarmStopJob?.cancel()
        super.onDestroy()
    }

    private fun startMonitorLoop() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (isActive) {
                val settings = AlarmSettingsStore.read(this@SolarAlarmService)
                if (!settings.enabled) {
                    stopEverything()
                    break
                }

                val data = SolarRepository.fetch()
                updateMonitorNotification(settings, data)
                if (data != null) {
                    evaluateAlarm(settings, data.house)
                }
                delay(2_000)
            }
        }
    }

    private fun evaluateAlarm(settings: AlarmSettings, houseW: Double) {
        val now = System.currentTimeMillis()
        if (houseW <= settings.clearThresholdW) {
            consecutiveHigh = 0
            armed = true
            stopAlarmSound()
            return
        }

        if (houseW >= settings.thresholdW) {
            consecutiveHigh += 1
        } else {
            consecutiveHigh = 0
        }

        val cooldownPassed = now - lastAlarmAt >= settings.cooldownS * 1000L
        if (armed && consecutiveHigh >= 2 && cooldownPassed) {
            armed = false
            lastAlarmAt = now
            triggerAlarm(settings, houseW, test = false)
        }
    }

    private fun triggerAlarm(settings: AlarmSettings, houseW: Double?, test: Boolean) {
        val title = if (test) "Test alarma consum" else "Consum mare casa"
        val valueText = houseW?.roundToInt()?.let { "$it W" } ?: "test"
        val body = if (test) {
            "Sunetul de alarma functioneaza."
        } else {
            "Consum $valueText peste pragul ${settings.thresholdW} W."
        }

        notify(ALERT_ID, alertNotification(title, body))
        playAlarmSound(settings)
        if (settings.vibrate) vibrate()
    }

    private fun startInForeground(settings: AlarmSettings, data: SolarData?) {
        val notification = monitorNotification(settings, data)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                MONITOR_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(MONITOR_ID, notification)
        }
    }

    private fun updateMonitorNotification(settings: AlarmSettings, data: SolarData?) {
        notify(MONITOR_ID, monitorNotification(settings, data))
    }

    private fun monitorNotification(settings: AlarmSettings, data: SolarData?): Notification {
        val current = data?.house?.roundToInt()?.let { "$it W" } ?: "astept date"
        val clear = settings.clearThresholdW
        return NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Solar Monitor - alarma activa")
            .setContentText("Consum $current / prag ${settings.thresholdW} W / clear $clear W")
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Opreste sunet", serviceIntent(ACTION_SILENCE))
            .build()
    }

    private fun alertNotification(title: String, body: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .addAction(0, "Opreste sunet", serviceIntent(ACTION_SILENCE))
            .build()

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun serviceIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, SolarAlarmService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun playAlarmSound(settings: AlarmSettings) {
        stopAlarmSound()
        val uri = AlarmSettingsStore.ringtoneUri(settings)
        val next = RingtoneManager.getRingtone(this, uri) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            next.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            next.isLooping = true
        }
        ringtone = next
        next.play()
        alarmStopJob = scope.launch {
            delay(30_000)
            stopAlarmSound()
        }
    }

    private fun stopAlarmSound() {
        alarmStopJob?.cancel()
        alarmStopJob = null
        ringtone?.stop()
        ringtone = null
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getSystemService(Vibrator::class.java)
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        val pattern = longArrayOf(0, 700, 250, 700, 250, 1200)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun notify(id: Int, notification: Notification) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, "Monitorizare solar", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Alarme consum solar", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(true)
            }
        )
    }

    private fun stopEverything() {
        stopAlarmSound()
        monitorJob?.cancel()
        monitorJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.rolling7.solar.action.START_ALARM"
        const val ACTION_STOP = "com.rolling7.solar.action.STOP_ALARM"
        const val ACTION_SILENCE = "com.rolling7.solar.action.SILENCE_ALARM"
        const val ACTION_TEST = "com.rolling7.solar.action.TEST_ALARM"
        private const val CHANNEL_MONITOR = "solar_monitor"
        private const val CHANNEL_ALERTS = "solar_alerts"
        private const val MONITOR_ID = 7001
        private const val ALERT_ID = 7002
    }
}
