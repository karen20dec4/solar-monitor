package com.rolling7.solar

internal enum class RetroEnergyTopSection {
    PRODUCTION,
    CONSUMPTION,
    HISTORY
}

internal val RetroEnergyRanges = listOf("2h", "1d", "7d")

/** Intervalul cu care se deschide pagina; 2h e la un buton distanta. */
internal const val RetroEnergyDefaultRange = "1d"

internal fun retroEnergyRangesForField(field: String): List<String> = RetroEnergyRanges

internal fun retroEnergyTopSectionForField(field: String): RetroEnergyTopSection = when (field) {
    "energy_pv_today" -> RetroEnergyTopSection.PRODUCTION
    "energy_load_today" -> RetroEnergyTopSection.CONSUMPTION
    else -> RetroEnergyTopSection.HISTORY
}

internal fun retroEnergyFieldForTopSection(
    section: RetroEnergyTopSection,
    historyField: String
): String = when (section) {
    RetroEnergyTopSection.PRODUCTION -> "energy_pv_today"
    RetroEnergyTopSection.CONSUMPTION -> "energy_load_today"
    RetroEnergyTopSection.HISTORY ->
        historyField.takeIf(::isRetroEnergyDetailedHistoryField) ?: "pv_power"
}

internal fun isRetroEnergyDetailedHistoryField(field: String): Boolean =
    field == "output_power" || field == "pv_power" || field == "battery_voltage"

internal fun retroEnergyChartTitle(field: String): String = when (field) {
    "output_power" -> "ISTORIC CONSUM CASA (W)"
    "pv_power" -> "ISTORIC PANOURI (W)"
    "battery_voltage" -> "ISTORIC BATERIE (V)"
    "energy_pv_today" -> "ISTORIC PRODUCTIE (kWh)"
    "energy_load_today" -> "ISTORIC CONSUM (kWh)"
    else -> "ISTORIC ENERGIE"
}

internal fun retroEnergyRangeDescription(range: String): String = when (range) {
    "2h" -> "Interval 2 ore"
    "1d" -> "Interval 24 ore"
    "7d" -> "Interval 7 zile"
    "30d" -> "Interval 30 zile"
    else -> "Interval $range"
}

internal fun normalizedRetroEnergyRange(range: String, field: String = ""): String {
    val allowed = if (field.isNotEmpty()) retroEnergyRangesForField(field) else RetroEnergyRanges
    return range.takeIf(allowed::contains)
        ?: RetroEnergyDefaultRange.takeIf(allowed::contains)
        ?: allowed.first()
}
