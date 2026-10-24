package com.rolling7.solar

import org.json.JSONObject
import java.util.Locale

/**
 * Cat s-a luat din retea de la inceputul lunii calendaristice (ora Bucuresti), asa cum il trimite
 * /solar/grid-month: baza pentru factura. [kwh] e null cand luna nu are inca nicio mostra.
 */
data class GridMonth(val month: String, val kwh: Double?) {
    /** "Oct" pentru "2026-10". */
    val monthShort: String get() = billMonthShort(month)
}

private val MonthsRo = listOf("Ian", "Feb", "Mar", "Apr", "Mai", "Iun", "Iul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val MonthKey = Regex("\\d{4}-(0[1-9]|1[0-2])")

internal fun billMonthShort(month: String): String =
    if (MonthKey.matches(month)) MonthsRo[month.substring(5).toInt() - 1] else "—"

/**
 * O zecimala ("9.3"), fara zecimale de la 100 kWh in sus ("123"), ca cifrele sa ramana mari pe afisajul
 * VFD; "—" cand nu exista valoare.
 */
internal fun billKwhText(kwh: Double?): String = when {
    kwh == null -> "—"
    kwh >= 99.95 -> String.format(Locale.US, "%.0f", kwh)
    else -> String.format(Locale.US, "%.1f", kwh)
}

/** Null cand raspunsul nu arata a /grid-month (luna lipsa sau alta forma). */
internal fun parseGridMonth(text: String): GridMonth? {
    val j = JSONObject(text)
    val month = j.optString("month", "")
    if (!MonthKey.matches(month)) return null
    val kwh = if (j.has("kwh") && !j.isNull("kwh")) j.optDouble("kwh").takeIf { it.isFinite() } else null
    return GridMonth(month, kwh)
}
