package com.rolling7.solar

import org.junit.Assert.assertEquals
import org.junit.Test

class RetroEnergyInteractionTest {
    @Test
    fun `production and consumption tabs select their daily histories`() {
        assertEquals(
            "energy_pv_today",
            retroEnergyFieldForTopSection(RetroEnergyTopSection.PRODUCTION, "battery_voltage")
        )
        assertEquals(
            "energy_load_today",
            retroEnergyFieldForTopSection(RetroEnergyTopSection.CONSUMPTION, "pv_power")
        )
    }

    @Test
    fun `history tab restores the selected detailed graph`() {
        assertEquals(
            "battery_voltage",
            retroEnergyFieldForTopSection(RetroEnergyTopSection.HISTORY, "battery_voltage")
        )
        assertEquals(
            "pv_power",
            retroEnergyFieldForTopSection(RetroEnergyTopSection.HISTORY, "energy_pv_today")
        )
    }

    @Test
    fun `fields activate the matching top section`() {
        assertEquals(RetroEnergyTopSection.PRODUCTION, retroEnergyTopSectionForField("energy_pv_today"))
        assertEquals(RetroEnergyTopSection.CONSUMPTION, retroEnergyTopSectionForField("energy_load_today"))
        assertEquals(RetroEnergyTopSection.HISTORY, retroEnergyTopSectionForField("pv_power"))
        assertEquals(RetroEnergyTopSection.HISTORY, retroEnergyTopSectionForField("output_power"))
        assertEquals(RetroEnergyTopSection.HISTORY, retroEnergyTopSectionForField("battery_voltage"))
    }

    @Test
    fun `only seven and thirty day ranges are accepted`() {
        assertEquals("7d", normalizedRetroEnergyRange("7d"))
        assertEquals("30d", normalizedRetroEnergyRange("30d"))
        assertEquals("7d", normalizedRetroEnergyRange("24h"))
    }

    @Test
    fun `every energy field has a precise dynamic title`() {
        assertEquals("ISTORIC CONSUM CASA (W)", retroEnergyChartTitle("output_power"))
        assertEquals("ISTORIC PANOURI (W)", retroEnergyChartTitle("pv_power"))
        assertEquals("ISTORIC BATERIE (V)", retroEnergyChartTitle("battery_voltage"))
        assertEquals("ISTORIC PRODUCTIE (kWh)", retroEnergyChartTitle("energy_pv_today"))
        assertEquals("ISTORIC CONSUM (kWh)", retroEnergyChartTitle("energy_load_today"))
    }
}
