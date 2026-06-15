package com.rolling7.solar

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/** Valorile live primite de la /solar/latest. */
data class SolarData(
    val pv: Double, val pv1: Double, val pv2: Double,
    val house: Double, val loadPercent: Double,
    val batteryVoltage: Double, val batterySoc: Double,
    val batteryPower: Double, val batteryDisplay: Double, val batteryCharge: Double,
    val batteryDischarge: Double, val batterySupport: Double,
    val gridImport: Double, val gridCharge: Double, val gridVoltage: Double,
    val inverterTemp: Double, val inverterLoss: Double,
    val energyPvToday: Double, val energyPvTotal: Double,
    val energyLoadToday: Double, val energyLoadTotal: Double,
    val status: Double, val houseSource: Double, val timestamp: String?
)

data class HistoryPoint(val time: String, val value: Double)

data class HistoryStats(
    val min: Double,
    val max: Double,
    val avg: Double,
    val last: Double
)

data class HistorySeries(
    val field: String,
    val label: String,
    val unit: String,
    val range: String,
    val points: List<HistoryPoint>,
    val stats: HistoryStats?
)

object SolarRepository {
    // Endpoint JSON expus prin Caddy (HTTPS self-signed, CA inclus in app).
    private const val URL_LATEST = "https://vyra.go.ro:31443/solar/latest"
    private const val URL_HISTORY = "https://vyra.go.ro:31443/solar/history"

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
                batteryPower = d("battery_power"),
                batteryDisplay = d("battery_display_power"), batteryCharge = d("battery_charge_power"),
                batteryDischarge = d("battery_discharge_power"), batterySupport = d("battery_support_power"),
                gridImport = d("grid_import_power"), gridCharge = d("grid_charge_power"),
                gridVoltage = d("grid_voltage"),
                inverterTemp = d("inverter_temp"), inverterLoss = d("inverter_loss"),
                energyPvToday = d("energy_pv_today"), energyPvTotal = d("energy_pv_total"),
                energyLoadToday = d("energy_load_today"), energyLoadTotal = d("energy_load_total"),
                status = d("status"), houseSource = d("house_source"),
                timestamp = if (j.isNull("timestamp")) null else j.optString("timestamp").ifBlank { null }
            )
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Istoric agregat din InfluxDB prin API; nu citeste direct invertorul. */
    fun fetchHistory(field: String, range: String): HistorySeries? {
        var conn: HttpURLConnection? = null
        return try {
            val query = "field=${enc(field)}&range=${enc(range)}"
            conn = (URL("$URL_HISTORY?$query").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                requestMethod = "GET"
            }
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(text)
            val pointsJson = j.optJSONArray("points") ?: JSONArray()
            val points = buildList {
                for (i in 0 until pointsJson.length()) {
                    val p = pointsJson.optJSONObject(i) ?: continue
                    add(HistoryPoint(p.optString("t"), p.optDouble("v", 0.0)))
                }
            }
            val statsJson = j.optJSONObject("stats")
            val stats = if (statsJson == null) {
                null
            } else {
                HistoryStats(
                    min = statsJson.optDouble("min", 0.0),
                    max = statsJson.optDouble("max", 0.0),
                    avg = statsJson.optDouble("avg", 0.0),
                    last = statsJson.optDouble("last", 0.0)
                )
            }
            HistorySeries(
                field = j.optString("field", field),
                label = j.optString("label", field),
                unit = j.optString("unit", ""),
                range = j.optString("range", range),
                points = points,
                stats = stats
            )
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
