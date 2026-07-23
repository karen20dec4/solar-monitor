package com.rolling7.solar

internal enum class RetroEnergyTopSection {
    PRODUCTION,
    CONSUMPTION,
    HISTORY
}

internal val RetroEnergyRanges = listOf("7d", "30d")

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

internal fun normalizedRetroEnergyRange(range: String): String =
    range.takeIf(RetroEnergyRanges::contains) ?: RetroEnergyRanges.first()
