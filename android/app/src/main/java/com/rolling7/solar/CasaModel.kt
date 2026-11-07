package com.rolling7.solar

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Un eveniment al unei prize (ora locala Europe/Bucharest, "HH:MM"): `action` = "on" / "off";
 * `days` = null (zilnic) sau zilele ISO 1 (luni)..7 (duminica).
 */
data class CasaEvent(val id: String, val time: String, val action: String, val days: List<Int>?, val enabled: Boolean)

/** Urmatorul eveniment activ al prizei: `at` = epoch-ul (secunde) la care se executa. */
data class CasaNextEvent(val id: String, val time: String, val action: String, val at: Long)

/** Numaratoare inversa: `action` = "on" / "off", `at` = epoch-ul la care se executa. */
data class CasaCountdown(val action: String, val at: Long, val secondsLeft: Long)

/** Momentul la care serverul va stinge priza (`reason`: "schedule", "countdown", "inching"...). */
data class CasaOffDeadline(val at: Long, val secondsLeft: Long, val reason: String)

data class CasaPlug(
    val id: String,
    val name: String,
    val online: Boolean,
    val on: Boolean,
    val metered: Boolean,
    val power: Double?,
    val voltage: Double?,
    val current: Double?,
    val energyToday: Double?,
    val events: List<CasaEvent>,
    val nextEvent: CasaNextEvent?,
    val countdown: CasaCountdown?,
    val inchingEnabled: Boolean,
    val inchingMinutes: Int,
    val offDeadline: CasaOffDeadline?,
    val error: String?
)

data class CasaSensor(
    val id: String,
    val name: String,
    val temperature: Double?,
    val humidity: Double?,
    val battery: Double?,
    /** Epoch (secunde) al ultimei citiri; null daca senzorul n-a raportat niciodata. */
    val updated: Long?,
    val spark: List<Pair<Long, Double>>
)

/** `cloudOk` = sesiunea cloud Tuya e buna (`cloud == "ok"`); altfel valorile pot fi vechi. */
data class CasaSensors(val cloudOk: Boolean, val sensors: List<CasaSensor>)

data class CasaSensorHistory(
    val range: String,
    val temperature: List<Pair<Long, Double>>,
    val humidity: List<Pair<Long, Double>>,
    /** (valoare, epoch) */
    val tempMin: Pair<Double, Long>?,
    val tempMax: Pair<Double, Long>?,
    val humMin: Pair<Double, Long>?,
    val humMax: Pair<Double, Long>?
)

private fun JSONObject.doubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null

private fun JSONObject.longOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

private fun JSONObject.objOrNull(key: String): JSONObject? =
    if (has(key) && !isNull(key)) optJSONObject(key) else null

// Pe telefon optString/getString intorc textul "null" pentru un null din JSON (json.org din teste intoarce
// valoarea implicita), asa ca orice text trece prin acest filtru.
internal fun JSONObject.stringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).ifBlank { null } else null

private fun JSONObject.requiredString(key: String): String =
    stringOrNull(key) ?: throw JSONException("lipseste $key")

private fun parsePairs(a: JSONArray?): List<Pair<Long, Double>> {
    if (a == null) return emptyList()
    val out = ArrayList<Pair<Long, Double>>(a.length())
    for (i in 0 until a.length()) {
        val p = a.optJSONArray(i) ?: continue
        if (p.length() < 2 || p.isNull(0) || p.isNull(1)) continue
        out += p.getLong(0) to p.getDouble(1)
    }
    return out
}

private fun parseStat(o: JSONObject?, key: String): Pair<Double, Long>? {
    val s = o?.objOrNull(key) ?: return null
    val v = s.doubleOrNull("v") ?: return null
    val t = s.longOrNull("t") ?: return null
    return v to t
}

/** `days` al unui eveniment: null (lipsa sau JSON null) = zilnic, altfel lista zilelor ISO. */
private fun parseDays(o: JSONObject): List<Int>? {
    val a = o.optJSONArray("days") ?: return null
    return (0 until a.length()).map { a.getInt(it) }
}

internal fun parsePlugObject(j: JSONObject): CasaPlug {
    // Un raspuns fara `events` (server vechi) nu are evenimente: lista goala, nu eroare.
    val events = j.optJSONArray("events")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            CasaEvent(
                id = it.requiredString("id"),
                time = it.requiredString("time"),
                action = it.requiredString("action"),
                days = parseDays(it),
                enabled = it.optBoolean("enabled", true)
            )
        }
    } ?: emptyList()
    val inching = j.objOrNull("inching")
    return CasaPlug(
        id = j.requiredString("id"),
        name = j.stringOrNull("name") ?: j.requiredString("id"),
        online = j.optBoolean("online", false),
        on = j.optBoolean("on", false),
        metered = j.optBoolean("metered", false),
        power = j.doubleOrNull("power"),
        voltage = j.doubleOrNull("voltage"),
        current = j.doubleOrNull("current"),
        energyToday = j.doubleOrNull("energy_today"),
        events = events,
        nextEvent = j.objOrNull("next_event")?.let {
            CasaNextEvent(
                id = it.requiredString("id"),
                time = it.requiredString("time"),
                action = it.requiredString("action"),
                at = it.getLong("at")
            )
        },
        countdown = j.objOrNull("countdown")?.let {
            CasaCountdown(it.requiredString("action"), it.getLong("at"), it.optLong("seconds_left", 0))
        },
        inchingEnabled = inching?.optBoolean("enabled", false) ?: false,
        inchingMinutes = inching?.optInt("minutes", 0) ?: 0,
        offDeadline = j.objOrNull("off_deadline")?.let {
            CasaOffDeadline(it.getLong("at"), it.optLong("seconds_left", 0), it.stringOrNull("reason") ?: "")
        },
        error = j.stringOrNull("error")
    )
}

/** Raspunsul `GET /plugs`: `{"plugs":[...], "timestamp":...}`. */
fun parsePlugs(json: String): List<CasaPlug> {
    val a = JSONObject(json).getJSONArray("plugs")
    return (0 until a.length()).map { parsePlugObject(a.getJSONObject(it)) }
}

/** O singura priza, cum o intorc comenzile. */
fun parsePlug(json: String): CasaPlug = parsePlugObject(JSONObject(json))

fun parseSensors(json: String): CasaSensors {
    val j = JSONObject(json)
    val a = j.optJSONArray("sensors")
    val sensors = (0 until (a?.length() ?: 0)).map { i ->
        val s = a!!.getJSONObject(i)
        CasaSensor(
            id = s.requiredString("id"),
            name = s.stringOrNull("name") ?: s.requiredString("id"),
            temperature = s.doubleOrNull("temperature"),
            humidity = s.doubleOrNull("humidity"),
            battery = s.doubleOrNull("battery"),
            updated = s.longOrNull("updated"),
            spark = parsePairs(s.optJSONArray("spark"))
        )
    }
    return CasaSensors(cloudOk = j.stringOrNull("cloud") == "ok", sensors = sensors)
}

fun parseSensorHistory(json: String): CasaSensorHistory {
    val j = JSONObject(json)
    val stats = j.objOrNull("stats")
    return CasaSensorHistory(
        range = j.stringOrNull("range") ?: "",
        temperature = parsePairs(j.optJSONArray("temperature")),
        humidity = parsePairs(j.optJSONArray("humidity")),
        tempMin = parseStat(stats, "temp_min"),
        tempMax = parseStat(stats, "temp_max"),
        humMin = parseStat(stats, "hum_min"),
        humMax = parseStat(stats, "hum_max")
    )
}

/** "5H 12" de la o ora in sus, "23 MIN" sub o ora, "<1 MIN" sub un minut. */
fun formatDuration(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600}H ${((seconds % 3600) / 60).toString().padStart(2, '0')}"
    seconds >= 60 -> "${seconds / 60} MIN"
    else -> "<1 MIN"
}

/** Cat de veche e o citire: "ACUM", "ACUM 4 MIN", "ACUM 6 H", "ACUM 2 Z"; "FARA DATE" fara timp. */
fun formatAge(nowEpoch: Long, updated: Long?): String {
    if (updated == null) return "FARA DATE"
    val age = nowEpoch - updated
    return when {
        age < 60 -> "ACUM"
        age < 3600 -> "ACUM ${age / 60} MIN"
        age < 86400 -> "ACUM ${age / 3600} H"
        else -> "ACUM ${age / 86400} Z"
    }
}

/** O citire mai veche de 30 de minute (sau lipsa) nu se arata ca valoare proaspata. */
fun isStale(nowEpoch: Long, updated: Long?): Boolean = updated == null || nowEpoch - updated > 30 * 60

private val CombiningMarks = Regex("\\p{M}+")

/** Eticheta pentru ecranul Retro: litere mari, fara diacritice (ă/â→A, î→I, ș/ş→S, ț/ţ→T). */
fun retroLabel(name: String): String =
    Normalizer.normalize(name, Normalizer.Form.NFD).replace(CombiningMarks, "").uppercase(Locale.ROOT)

/** Cel mult atatea evenimente pe priza (aceeasi limita ca pe server, `MAX_EVENTS`). */
internal const val MaxEvents = 12

internal fun casaCanAddEvent(count: Int): Boolean = count < MaxEvents

/** Numele scurte ale zilelor; indexul = ziua ISO - 1. */
internal val CasaDayShort = listOf("L", "MA", "MI", "J", "V", "SA", "DU")

/** Zilele unui eveniment: "ZILNIC", "L-V", "SA · DU" sau numele scurte despartite prin " · ". */
internal fun casaDaysText(days: List<Int>?): String = when {
    days == null -> "ZILNIC"
    days == listOf(1, 2, 3, 4, 5) -> "L-V"
    else -> days.mapNotNull { CasaDayShort.getOrNull(it - 1) }.joinToString(" · ")
}

/** "PORNESTE" pentru "on", altfel "OPRESTE". */
internal fun casaActionText(action: String): String = if (action == "on") "PORNESTE" else "OPRESTE"

/** "OPRESTE 16:00" azi, "OPRESTE L 17:00" in alta zi (data locala a evenimentului fata de azi). */
internal fun casaNextEventShort(e: CasaNextEvent, nowEpoch: Long, zone: ZoneId): String {
    val today = Instant.ofEpochSecond(nowEpoch).atZone(zone).toLocalDate()
    val day = Instant.ofEpochSecond(e.at).atZone(zone)
    val prefix = if (day.toLocalDate() == today) "" else CasaDayShort[day.dayOfWeek.value - 1] + " "
    return "${casaActionText(e.action)} $prefix${e.time}"
}

/** "OPRESTE 16:00 · IN 2H 14" (rand "URMATORUL" din detaliu); in alta zi, ziua scurta inaintea orei. */
internal fun casaNextEventText(e: CasaNextEvent, nowEpoch: Long, zone: ZoneId): String =
    "${casaNextEventShort(e, nowEpoch, zone)} · IN ${formatDuration(e.at - nowEpoch)}"

/** Corpul `POST /plugs/<id>/events`: `days` null = zilnic (trimis ca null explicit). */
internal fun casaEventBody(time: String, action: String, days: List<Int>?): JSONObject =
    JSONObject().put("time", time).put("action", action).put("days", if (days == null) JSONObject.NULL else JSONArray(days))

/**
 * Corpul `PUT /plugs/<id>/events/<eid>`: doar ce se schimba. `daily = true` trimite `"days": null`
 * (si bate `days`); fara `daily` si fara `days`, zilele nu se ating.
 */
internal fun casaEventUpdateBody(
    time: String? = null, action: String? = null, days: List<Int>? = null,
    daily: Boolean = false, enabled: Boolean? = null
): JSONObject {
    val body = JSONObject()
    if (time != null) body.put("time", time)
    if (action != null) body.put("action", action)
    if (daily) body.put("days", JSONObject.NULL) else if (days != null) body.put("days", JSONArray(days))
    if (enabled != null) body.put("enabled", enabled)
    return body
}

/**
 * Insemnele de sub numele prizei, in ordinea afisarii: urmatorul eveniment ("OPRESTE 17:00", cu ziua
 * scurta daca nu e azi), apoi numaratoarea, inchingul si termenul de oprire.
 */
fun plugBadges(p: CasaPlug, nowEpoch: Long, zone: ZoneId): List<String> {
    val out = ArrayList<String>()
    p.nextEvent?.let { out += casaNextEventShort(it, nowEpoch, zone) }
    p.countdown?.let {
        out += (if (it.action == "on") "PORNIRE IN " else "OPRIRE IN ") + formatDuration(it.secondsLeft)
    }
    if (p.inchingEnabled) out += "AUTO ${p.inchingMinutes} MIN"
    p.offDeadline?.let {
        if (it.reason != "countdown") out += "STINGE IN ${formatDuration(it.secondsLeft)}"
    }
    return out
}

/**
 * Ordinea dintre sondarea `GET /plugs` si comenzi. O sondare pornita inainte ca o comanda sa se termine
 * poate sosi DUPA raspunsul comenzii, cu starea veche (serverul raspunde la GET cat priza inca executa):
 * aplicatia arata atunci 2-3 s starea dinainte (Living sus, 2026-11-05). Sondarea isi ia un bilet la
 * plecare; raspunsul ei se foloseste doar daca intre timp n-a inceput/terminat nicio comanda si nu e
 * nicio comanda in zbor. Apelat doar din firul principal (Compose).
 */
class CasaFreshness {
    private var generation = 0L
    private var inFlight = 0

    fun beginPoll(): Long = generation

    fun commandStarted() {
        inFlight++
        generation++
    }

    fun commandFinished() {
        if (inFlight > 0) inFlight--
        generation++
    }

    fun isFresh(ticket: Long): Boolean = inFlight == 0 && ticket == generation
}

/** Lungimea maxima a unui nume dat din aplicatie (aceeasi ca pe server, `NAME_MAX`). */
internal const val CasaNameMax = 24

/** Numele nou, curatat; null daca e gol, prea lung, are caractere de control sau e acelasi cu cel vechi. */
internal fun casaCleanName(input: String, current: String): String? {
    val name = input.trim()
    if (name.isEmpty() || name.length > CasaNameMax || name.any { it.isISOControl() } || name == current) return null
    return name
}
