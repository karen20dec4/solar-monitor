package com.rolling7.solar

internal val RetroEnergyRanges = listOf("2h", "1d", "7d")

/** Intervalul cu care se deschide pagina; 2h e la un buton distanta. */
internal const val RetroEnergyDefaultRange = "1d"

internal fun retroEnergyRangesForField(field: String): List<String> = RetroEnergyRanges

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
