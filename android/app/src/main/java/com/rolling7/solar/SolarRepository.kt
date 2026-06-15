package com.rolling7.solar

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Valorile live primite de la /solar/latest. */
data class SolarData(
    val pv: Double, val pv1: Double, val pv2: Double,
    val house: Double, val loadPercent: Double,
    val batteryVoltage: Double, val batterySoc: Double,
    val batteryDisplay: Double, val batteryCharge: Double,
    val batteryDischarge: Double, val batterySupport: Double,
    val gridImport: Double, val gridCharge: Double, val gridVoltage: Double,
    val inverterTemp: Double, val inverterLoss: Double,
    val energyPvToday: Double, val energyPvTotal: Double,
    val energyLoadToday: Double, val energyLoadTotal: Double,
    val status: Double, val timestamp: String?
)

object SolarRepository {
    // Endpoint JSON expus prin Caddy (HTTPS self-signed, CA inclus in app).
    private const val URL_LATEST = "https://vyra.go.ro:31443/solar/latest"

    /** Apel de retea sincron - apelat pe Dispatchers.IO. Returneaza null la eroare. */
    fun fetch(): SolarData? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(URL_LATEST).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
            }
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(text)
            fun d(k: String) = j.optDouble(k, 0.0)
            SolarData(
                pv = d("pv_power"), pv1 = d("pv1_power"), pv2 = d("pv2_power"),
                house = d("output_power"), loadPercent = d("load_percent"),
                batteryVoltage = d("battery_voltage"), batterySoc = d("battery_soc"),
                batteryDisplay = d("battery_display_power"), batteryCharge = d("battery_charge_power"),
                batteryDischarge = d("battery_discharge_power"), batterySupport = d("battery_support_power"),
                gridImport = d("grid_import_power"), gridCharge = d("grid_charge_power"),
                gridVoltage = d("grid_voltage"),
                inverterTemp = d("inverter_temp"), inverterLoss = d("inverter_loss"),
                energyPvToday = d("energy_pv_today"), energyPvTotal = d("energy_pv_total"),
                energyLoadToday = d("energy_load_today"), energyLoadTotal = d("energy_load_total"),
                status = d("status"),
                timestamp = if (j.isNull("timestamp")) null else j.optString("timestamp", null)
            )
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
