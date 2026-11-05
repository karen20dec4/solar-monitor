package com.rolling7.solar

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Un interval de program al unei prize (ora locala Europe/Bucharest, "HH:MM"). */
data class CasaSchedule(val id: String, val start: String, val stop: String, val enabled: Boolean)

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
    val schedules: List<CasaSchedule>,
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

private fun JSONObject.stringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).ifBlank { null } else null

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

internal fun parsePlugObject(j: JSONObject): CasaPlug {
    val schedules = j.optJSONArray("schedules")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            CasaSchedule(
                id = it.getString("id"),
                start = it.getString("start"),
                stop = it.getString("stop"),
                enabled = it.optBoolean("enabled", true)
            )
        }
    } ?: emptyList()
    val inching = j.objOrNull("inching")
    return CasaPlug(
        id = j.getString("id"),
        name = j.optString("name", j.getString("id")),
        online = j.optBoolean("online", false),
        on = j.optBoolean("on", false),
        metered = j.optBoolean("metered", false),
        power = j.doubleOrNull("power"),
        voltage = j.doubleOrNull("voltage"),
        current = j.doubleOrNull("current"),
        energyToday = j.doubleOrNull("energy_today"),
        schedules = schedules,
        countdown = j.objOrNull("countdown")?.let {
            CasaCountdown(it.getString("action"), it.getLong("at"), it.optLong("seconds_left", 0))
        },
        inchingEnabled = inching?.optBoolean("enabled", false) ?: false,
        inchingMinutes = inching?.optInt("minutes", 0) ?: 0,
        offDeadline = j.objOrNull("off_deadline")?.let {
            CasaOffDeadline(it.getLong("at"), it.optLong("seconds_left", 0), it.optString("reason", ""))
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
            id = s.getString("id"),
            name = s.optString("name", s.getString("id")),
            temperature = s.doubleOrNull("temperature"),
            humidity = s.doubleOrNull("humidity"),
            battery = s.doubleOrNull("battery"),
            updated = s.longOrNull("updated"),
            spark = parsePairs(s.optJSONArray("spark"))
        )
    }
    return CasaSensors(cloudOk = j.optString("cloud") == "ok", sensors = sensors)
}

fun parseSensorHistory(json: String): CasaSensorHistory {
    val j = JSONObject(json)
    val stats = j.objOrNull("stats")
    return CasaSensorHistory(
        range = j.optString("range", ""),
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

/** Insemnele de sub numele prizei, in ordinea afisarii. */
fun plugBadges(p: CasaPlug): List<String> {
    val out = ArrayList<String>()
    p.schedules.filter { it.enabled }.forEach { out += "${it.start}-${it.stop}" }
    p.countdown?.let {
        out += (if (it.action == "on") "PORNIRE IN " else "OPRIRE IN ") + formatDuration(it.secondsLeft)
    }
    if (p.inchingEnabled) out += "AUTO ${p.inchingMinutes} MIN"
    p.offDeadline?.let {
        if (it.reason != "countdown") out += "STINGE IN ${formatDuration(it.secondsLeft)}"
    }
    return out
}
