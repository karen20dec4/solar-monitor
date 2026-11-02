package com.rolling7.solar

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/** Sub tensiunea asta reteaua Enel lipseste; acelasi prag ca GRID_DOWN_V din api/app.py. */
internal const val GRID_DOWN_VOLTS = 100.0

/** O pana de retea tinuta minte de server; [end] null = inca dureaza. Timpii sunt epoch, in secunde. */
data class GridOutage(val start: Long, val end: Long?, val seconds: Long)

/** Pana de la care s-a anuntat deja tot ([seen]) si pana anuntata ca in curs ([open], 0 daca nu e). */
internal data class GridOutageMark(val seen: Long, val open: Long)

/** Un mesaj de trimis: pana a inceput, sau [restored] = reteaua a revenit. */
internal data class GridNotice(val outage: GridOutage, val restored: Boolean)

/** Null cand raspunsul nu arata a /grid-outage. */
internal fun parseGridOutages(text: String): List<GridOutage>? {
    val items = JSONObject(text).optJSONArray("outages") ?: return null
    return (0 until items.length()).map { index ->
        val item = items.getJSONObject(index)
        GridOutage(
            start = item.getLong("start"),
            end = if (item.isNull("end")) null else item.getLong("end"),
            seconds = item.getLong("seconds")
        )
    }
}

/**
 * Ce e nou fata de [mark]. Telefonul afla de pana cu minute intarziere (internetul casei cade odata cu
 * reteaua), deci pana poate fi deja terminata cand o vede prima oara: atunci primeste direct mesajul de revenire.
 * La prima rulare ([mark] null) penele trecute nu se mai anunta.
 */
internal fun evaluateGridOutages(outages: List<GridOutage>, mark: GridOutageMark?): Pair<List<GridNotice>, GridOutageMark> {
    var seen = mark?.seen ?: outages.filter { it.end != null }.maxOfOrNull { it.start } ?: 0L
    var open = mark?.open ?: 0L
    val notices = mutableListOf<GridNotice>()
    outages.sortedBy { it.start }.forEach { outage ->
        if (outage.end == null) {
            // Peste 24 h inceputul penei iese din fereastra serverului si pare alta pana: tot pe aceea o anuntasem.
            if (open == 0L && outage.start > seen) {
                notices += GridNotice(outage, restored = false)
                open = outage.start
                seen = outage.start
            }
        } else if (outage.start == open || outage.start > seen) {
            notices += GridNotice(outage, restored = true)
            if (outage.start == open) open = 0L
            seen = maxOf(seen, outage.start)
        }
    }
    if (open != 0L && outages.none { it.start == open }) open = 0L
    return notices to GridOutageMark(seen, open)
}

internal fun gridDurationText(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3_600 -> if (seconds % 60 == 0L) "${seconds / 60} min" else "${seconds / 60} min ${seconds % 60} s"
    else -> "${seconds / 3_600} h ${seconds % 3_600 / 60} min"
}

internal fun gridNoticeTitle(notice: GridNotice): String =
    if (notice.restored) "Curent Enel revenit" else "Curent Enel picat"

internal fun gridNoticeBody(notice: GridNotice, clock: (Long) -> String = ::gridClock): String {
    val outage = notice.outage
    val end = outage.end
    return if (notice.restored && end != null) {
        "A picat la ${clock(outage.start)}, a revenit la ${clock(end)} (${gridDurationText(outage.seconds)})."
    } else {
        "Reteaua Enel lipseste de la ${clock(outage.start)}. Casa merge din panouri si baterie."
    }
}

private fun gridClock(epochS: Long): String =
    SimpleDateFormat("HH:mm", Locale.US).format(Date(epochS * 1000L))
