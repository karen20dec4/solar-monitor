package com.rolling7.solar

import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** O alarma BMS activa, asa cum o trimite /solar/bms (regulile si pragurile sunt pe server, in .env). */
data class BmsAlarm(
    val key: String,
    val title: String,
    val message: String,
    val priority: String
) {
    /** Suna doar alarmele urgente / importante; "BMS: fara date" doar se afiseaza. */
    val rings: Boolean get() = priority == "urgent" || priority == "high"
}

/** Citirea BMS-ului JK. Tensiuni in V, curent in A (pozitiv = incarca), temperaturi in °C. */
data class BmsReading(
    val cells: List<Double>,
    val voltage: Double,
    val current: Double,
    val power: Double,
    val soc: Double,
    val tempBattery1: Double,
    val tempBattery2: Double,
    val tempMos: Double,
    val chargeOn: Boolean,
    val dischargeOn: Boolean,
    val balancing: Int,
    val remainingAh: Double,
    val nominalAh: Double,
    val cycles: Int,
    val alarmBits: Int
) {
    val cellMin: Double get() = cells.minOrNull() ?: Double.NaN
    val cellMax: Double get() = cells.maxOrNull() ?: Double.NaN
    val cellAvg: Double get() = if (cells.isEmpty()) Double.NaN else cells.average()

    /** Diferenta dintre cea mai incarcata si cea mai descarcata celula, in mV. */
    val deltaMv: Double get() = if (cells.isEmpty()) 0.0 else (cellMax - cellMin) * 1000.0
    val batteryTempMax: Double get() = max(tempBattery1, tempBattery2)
}

/** Raspunsul /solar/bms: citirea (null cand BMS-ul n-a dat date in ultimele 15 s) si alarmele active. */
data class BmsState(val reading: BmsReading?, val alarms: List<BmsAlarm>)

enum class BmsLevel { NO_DATA, OK, WARNING, ALARM }

private val CellKey = Regex("cell_(\\d{2})")

internal fun parseBmsState(text: String): BmsState {
    val j = JSONObject(text)
    fun has(k: String) = j.has(k) && !j.isNull(k)
    fun d(k: String) = j.optDouble(k, Double.NaN)

    val alarmsJson = j.optJSONArray("alarms")
    val alarms = buildList {
        if (alarmsJson != null) {
            for (i in 0 until alarmsJson.length()) {
                val a = alarmsJson.optJSONObject(i) ?: continue
                add(
                    BmsAlarm(
                        key = a.optString("key"),
                        title = a.optString("title"),
                        message = a.optString("msg"),
                        priority = a.optString("prio", "default")
                    )
                )
            }
        }
    }

    // Fara timestamp = fara date proaspete; campurile de baza trebuie sa fie toate prezente.
    val required = listOf("voltage", "current", "soc", "temp_1", "temp_2", "temp_mos")
    if (!has("timestamp") || required.any { !has(it) }) return BmsState(null, alarms)

    val cells = j.keys().asSequence()
        .mapNotNull { key -> CellKey.matchEntire(key)?.let { it.groupValues[1].toInt() to key } }
        .sortedBy { it.first }
        .map { j.optDouble(it.second, Double.NaN) }
        .filter { it.isFinite() }
        .toList()

    return BmsState(
        reading = BmsReading(
            cells = cells,
            voltage = d("voltage"),
            current = d("current"),
            power = j.optDouble("power", d("voltage") * d("current")),
            soc = d("soc"),
            tempBattery1 = d("temp_1"),
            tempBattery2 = d("temp_2"),
            tempMos = d("temp_mos"),
            chargeOn = d("charge_mosfet") >= 1.0,
            dischargeOn = d("discharge_mosfet") >= 1.0,
            balancing = j.optDouble("balancing", 0.0).roundToInt(),
            remainingAh = j.optDouble("capacity_remaining_ah", Double.NaN),
            nominalAh = j.optDouble("capacity_nominal_ah", Double.NaN),
            cycles = j.optDouble("cycles", 0.0).roundToInt(),
            alarmBits = j.optDouble("alarm_bits", 0.0).roundToInt()
        ),
        alarms = alarms
    )
}

/** Culoarea de stare a bateriei: rosu la alarma, galben la avertisment, gri fara date. */
internal fun bmsLevel(state: BmsState?): BmsLevel {
    val reading = state?.reading ?: return BmsLevel.NO_DATA
    return when {
        state.alarms.any { it.rings } -> BmsLevel.ALARM
        state.alarms.isNotEmpty() -> BmsLevel.WARNING
        !reading.chargeOn || !reading.dischargeOn -> BmsLevel.ALARM
        reading.deltaMv >= 50.0 || reading.batteryTempMax >= 40.0 -> BmsLevel.WARNING
        else -> BmsLevel.OK
    }
}

/** Curent cu semn explicit ("+12.3" incarca, "-4.7" descarca); peste 100 A fara zecimale. */
internal fun bmsCurrentText(amps: Double): String {
    val sign = if (amps > 0.0) "+" else ""
    return if (abs(amps) >= 100.0) {
        sign + String.format(Locale.US, "%.0f", amps)
    } else {
        sign + String.format(Locale.US, "%.1f", amps)
    }
}

internal fun bmsSocText(soc: Double): String = soc.roundToInt().toString()

internal fun bmsDeltaText(deltaMv: Double): String = deltaMv.roundToInt().toString()

/**
 * Abaterea fiecarei celule de la medie, ca fractie din scara plina (±15 mV implicit), taiata la ±1.
 * O celula buna e o bara scurta; una care se desprinde de celelalte se vede imediat.
 */
internal fun cellDeviationFractions(cells: List<Double>, fullScaleV: Double = 0.015): List<Float> {
    if (cells.isEmpty()) return emptyList()
    val avg = cells.average()
    return cells.map { ((it - avg) / fullScaleV).toFloat().coerceIn(-1f, 1f) }
}

/**
 * Hotaraste cand suna alarma bateriei: o alarma care apare suna o singura data cat timp ramane
 * activa, cu pauza (cooldown) intre doua sunete ale aceleiasi alarme. Cand dispare, se rearmeaza,
 * dar cooldown-ul ramane, ca o alarma care clipeste sa nu sune la fiecare clipire.
 */
internal class BmsAlarmTracker {
    private val active = mutableSetOf<String>()
    private val lastRang = mutableMapOf<String, Long>()

    fun evaluate(alarms: List<BmsAlarm>, nowMs: Long, cooldownMs: Long): List<BmsAlarm> {
        val ringing = alarms.filter { it.rings }
        active.retainAll(ringing.map { it.key }.toSet())
        val toRing = ringing.filter { alarm ->
            val isNew = active.add(alarm.key)
            val last = lastRang[alarm.key]
            isNew && (last == null || nowMs - last >= cooldownMs)
        }
        toRing.forEach { lastRang[it.key] = nowMs }
        return toRing
    }
}
