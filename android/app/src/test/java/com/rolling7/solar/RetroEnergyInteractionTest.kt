package com.rolling7.solar

import org.junit.Assert.assertEquals
import org.junit.Test

class RetroEnergyInteractionTest {
    @Test
    fun `energy page offers two hours, one day and seven days`() {
        assertEquals(listOf("2h", "1d", "7d"), retroEnergyRangesForField("output_power"))
        assertEquals("2h", normalizedRetroEnergyRange("2h", "energy_pv_today"))
        assertEquals("7d", normalizedRetroEnergyRange("7d", "battery_voltage"))
    }

    @Test
    fun `unknown ranges fall back to one day`() {
        assertEquals("1d", normalizedRetroEnergyRange("30d", "output_power"))
        assertEquals("1d", normalizedRetroEnergyRange("", "energy_load_today"))
    }

    @Test
    fun `range buttons describe their span`() {
        assertEquals("Interval 2 ore", retroEnergyRangeDescription("2h"))
        assertEquals("Interval 24 ore", retroEnergyRangeDescription("1d"))
        assertEquals("Interval 7 zile", retroEnergyRangeDescription("7d"))
    }
}
