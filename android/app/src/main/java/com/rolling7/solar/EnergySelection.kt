package com.rolling7.solar

import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Pagina ENERGIE, in ambele teme, se citeste de sus in jos: CE urmaresti (subiect), CUM (mod),
 * CAT timp (interval), apoi graficul si cifrele. Graficul ales e tot un nume de camp din API;
 * functiile de aici traduc intre camp si perechea subiect + mod.
 */

internal enum class EnergySubject(val label: String) {
    HOUSE("CASA"),
    PANELS("PANOURI"),
    BATTERY("BATERIE")
}

internal enum class EnergyMode(val label: String, val unit: String) {
    POWER("PUTERE", "W"),
    ENERGY("ENERGIE", "kWh"),
    VOLTAGE("TENSIUNE", "V")
}

/** Bateria are doar tensiune: API-ul nu are energie incarcata/descarcata. */
internal fun energyModesFor(subject: EnergySubject): List<EnergyMode> =
    if (subject == EnergySubject.BATTERY) {
        listOf(EnergyMode.VOLTAGE)
    } else {
        listOf(EnergyMode.POWER, EnergyMode.ENERGY)
    }

internal fun energySubjectForField(field: String): EnergySubject = when (field) {
    "output_power", "energy_load_today" -> EnergySubject.HOUSE
    "battery_voltage" -> EnergySubject.BATTERY
    else -> EnergySubject.PANELS
}

internal fun energyModeForField(field: String): EnergyMode = when (field) {
    "energy_pv_today", "energy_load_today" -> EnergyMode.ENERGY
    "battery_voltage" -> EnergyMode.VOLTAGE
    else -> EnergyMode.POWER
}

internal fun energyFieldFor(subject: EnergySubject, mode: EnergyMode): String = when (subject) {
    EnergySubject.HOUSE -> if (mode == EnergyMode.ENERGY) "energy_load_today" else "output_power"
    EnergySubject.PANELS -> if (mode == EnergyMode.ENERGY) "energy_pv_today" else "pv_power"
    EnergySubject.BATTERY -> "battery_voltage"
}

/** Schimbi subiectul si pastrezi modul, daca noul subiect il are (CASA ENERGIE -> PANOURI ENERGIE). */
internal fun energyFieldForSubject(subject: EnergySubject, currentField: String): String {
    val modes = energyModesFor(subject)
    val mode = energyModeForField(currentField).takeIf(modes::contains) ?: modes.first()
    return energyFieldFor(subject, mode)
}

internal val EnergyFields = setOf(
    "output_power", "pv_power", "battery_voltage", "energy_pv_today", "energy_load_today"
)

/** Orice camp venit din alt tab, care nu are grafic pe ENERGIE, deschide producția PV. */
internal fun normalizedEnergyField(field: String): String =
    field.takeIf(EnergyFields::contains) ?: "pv_power"

internal fun energyRangeLabel(range: String): String = when (range) {
    "2h" -> "2 ORE"
    "1d" -> "24 ORE"
    "7d" -> "7 ZILE"
    "30d" -> "30 ZILE"
    else -> range.uppercase()
}

/** Titlul complet al graficului, scurt cat sa incapa pe placa Retro: "CASA · PUTERE · 2 ORE". */
internal fun energyChartTitle(field: String, range: String): String =
    "${energySubjectForField(field).label} · ${energyModeForField(field).label} · ${energyRangeLabel(range)}"

/** Durata ferestrei de agregare intoarse de API ("1m", "10m", "1h", "1d"). */
internal fun windowMinutes(window: String): Long {
    val amount = window.dropLast(1).toLongOrNull() ?: return 0L
    return when (window.last()) {
        'm' -> amount
        'h' -> amount * 60
        'd' -> amount * 1440
        else -> 0L
    }
}

private val TickTime: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val TickDay: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")

internal fun energyTickStepMinutes(range: String): Long = when (range) {
    "1h" -> 10L
    "2h" -> 30L
    "6h" -> 60L
    "1d", "24h" -> 240L
    "7d" -> 1440L
    else -> 1440L * 5
}

/**
 * Indicii punctelor care primesc eticheta pe axa timpului: doar momente rotunde (la 30 min pe 2 ore,
 * la 4 ore pe 24 de ore, la miezul noptii pe 7 zile). Ultimul punct, partial, nu cade pe o ora
 * rotunda, deci nu mai apare "13:00 15:01" lipite.
 */
internal fun energyTickIndices(times: List<String>, range: String, zone: ZoneId): List<Int> {
    val step = energyTickStepMinutes(range)
    return times.indices.filter { index ->
        val time = parseLocal(times[index], zone) ?: return@filter false
        if (time.second != 0 || time.nano != 0) return@filter false
        val minuteOfDay = time.hour * 60L + time.minute
        if (step <= 1440L) {
            minuteOfDay % step == 0L
        } else {
            minuteOfDay == 0L && time.dayOfYear % (step / 1440L) == 0L
        }
    }
}

internal fun energyTickLabel(time: String, range: String, zone: ZoneId): String {
    val local = parseLocal(time, zone) ?: return ""
    return if (energyTickStepMinutes(range) < 1440L) local.format(TickTime) else local.format(TickDay)
}

/**
 * "DIN 13:10 · MEDII PE 1 MIN": de cand pornesc datele si ce reprezinta un punct. Barele poarta ora
 * de inceput; punctele de linie poarta sfarsitul ferestrei, deci inceputul e cu o fereastra mai devreme.
 */
internal fun energySpanLabel(
    times: List<String>,
    window: String,
    range: String,
    bars: Boolean,
    zone: ZoneId
): String {
    val first = times.firstOrNull()?.let { parseLocal(it, zone) } ?: return ""
    val minutes = windowMinutes(window)
    val start = if (bars) first else first.minusMinutes(minutes)
    val from = if (range == "7d" || range == "30d") start.format(TickDay) else start.format(TickTime)
    val unit = when {
        minutes >= 1440L -> "ZI"
        minutes == 60L -> "ORA"
        else -> "$minutes MIN"
    }
    val what = when {
        !bars -> "MEDII PE $unit"
        minutes >= 60L -> "BARE PE $unit"
        else -> "BARE DE $unit"
    }
    return "DIN $from · $what"
}

/** Cat de proaspete sunt datele live; peste un minut fara date noi devine avertisment. */
internal data class EnergyFreshness(val text: String, val stale: Boolean)

internal fun energyFreshness(timestamp: String?, nowMillis: Long): EnergyFreshness {
    val time = timestamp?.let {
        try {
            OffsetDateTime.parse(it).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    } ?: return EnergyFreshness("ASTEPT DATE", stale = true)
    val seconds = Duration.ofMillis((nowMillis - time).coerceAtLeast(0L)).seconds
    return when {
        seconds < 60 -> EnergyFreshness("ACTUALIZAT ACUM $seconds S", stale = false)
        seconds < 3600 -> EnergyFreshness("DATE VECHI · ACUM ${seconds / 60} MIN", stale = true)
        else -> EnergyFreshness("DATE VECHI · ACUM ${seconds / 3600} ORE", stale = true)
    }
}

private fun parseLocal(value: String, zone: ZoneId) =
    try {
        OffsetDateTime.parse(value).atZoneSameInstant(zone)
    } catch (e: Exception) {
        null
    }
