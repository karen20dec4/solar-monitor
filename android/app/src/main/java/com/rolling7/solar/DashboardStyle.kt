package com.rolling7.solar

import android.content.Context

enum class DashboardStyle(val label: String) {
    RETRO("Retro"),
    SIMPLE("Simple")
}

object DashboardStyleStore {
    private const val PREFS = "solar_dashboard_style"
    private const val KEY_STYLE = "style"

    fun read(context: Context): DashboardStyle {
        val stored = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STYLE, null)
        return stored
            ?.let { value -> runCatching { DashboardStyle.valueOf(value) }.getOrNull() }
            ?: DashboardStyle.RETRO
    }

    fun save(context: Context, style: DashboardStyle) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STYLE, style.name)
            .apply()
    }
}
