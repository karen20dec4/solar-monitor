package com.rolling7.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId

class EnergySelectionTest {
    private val zone = ZoneId.of("Europe/Bucharest")

    @Test
    fun `every chart is one subject and one mode`() {
        assertEquals("output_power", energyFieldFor(EnergySubject.HOUSE, EnergyMode.POWER))
        assertEquals("energy_load_today", energyFieldFor(EnergySubject.HOUSE, EnergyMode.ENERGY))
        assertEquals("pv_power", energyFieldFor(EnergySubject.PANELS, EnergyMode.POWER))
        assertEquals("energy_pv_today", energyFieldFor(EnergySubject.PANELS, EnergyMode.ENERGY))
        assertEquals("battery_voltage", energyFieldFor(EnergySubject.BATTERY, EnergyMode.VOLTAGE))
        EnergyFields.forEach { field ->
            assertEquals(field, energyFieldFor(energySubjectForField(field), energyModeForField(field)))
        }
    }

    @Test
    fun `changing subject keeps the mode when it exists`() {
        assertEquals("energy_pv_today", energyFieldForSubject(EnergySubject.PANELS, "energy_load_today"))
        assertEquals("output_power", energyFieldForSubject(EnergySubject.HOUSE, "pv_power"))
        assertEquals("battery_voltage", energyFieldForSubject(EnergySubject.BATTERY, "energy_pv_today"))
        assertEquals("output_power", energyFieldForSubject(EnergySubject.HOUSE, "battery_voltage"))
    }

    @Test
    fun `battery only has voltage`() {
        assertEquals(listOf(EnergyMode.VOLTAGE), energyModesFor(EnergySubject.BATTERY))
        assertEquals(listOf(EnergyMode.POWER, EnergyMode.ENERGY), energyModesFor(EnergySubject.HOUSE))
    }

    @Test
    fun `fields from other tabs open a valid chart`() {
        assertEquals("pv_power", normalizedEnergyField("inverter_temp"))
        assertEquals("energy_load_today", normalizedEnergyField("energy_load_today"))
    }

    @Test
    fun `titles are complete and short`() {
        assertEquals("CASA · PUTERE · 2 ORE", energyChartTitle("output_power", "2h"))
        assertEquals("PANOURI · ENERGIE · 7 ZILE", energyChartTitle("energy_pv_today", "7d"))
        assertEquals("BATERIE · TENSIUNE · 24 ORE", energyChartTitle("battery_voltage", "1d"))
    }

    @Test
    fun `axis labels only land on round times`() {
        // ore locale 13:00 13:10 13:20 13:30 si ultimul punct partial 13:34:12
        val times = listOf(
            "2026-10-18T10:00:00Z", "2026-10-18T10:10:00Z", "2026-10-18T10:20:00Z",
            "2026-10-18T10:30:00Z", "2026-10-18T10:34:12Z"
        )
        assertEquals(listOf(0, 3), energyTickIndices(times, "2h", zone))
        assertEquals("13:30", energyTickLabel(times[3], "2h", zone))

        val hours = (0..23).map { "2026-10-17T%02d:00:00Z".format(it) }
        // 4 ore locale rotunde: 00 04 08 12 16 20 -> UTC 21 01 05 09 13 17
        assertEquals(listOf(1, 5, 9, 13, 17, 21), energyTickIndices(hours, "1d", zone))

        val days = (12..18).map { "2026-10-%02dT21:00:00Z".format(it - 1) }
        assertEquals((0..6).toList(), energyTickIndices(days, "7d", zone))
        assertEquals("18.10", energyTickLabel(days.last(), "7d", zone))
    }

    @Test
    fun `span label says where data starts and what a point is`() {
        val bars = listOf("2026-10-18T10:10:00Z", "2026-10-18T10:20:00Z")
        assertEquals("DIN 13:10 · BARE DE 10 MIN", energySpanLabel(bars, "10m", "2h", bars = true, zone = zone))
        val line = listOf("2026-10-18T10:11:00Z", "2026-10-18T10:12:00Z")
        assertEquals("DIN 13:10 · MEDII PE 1 MIN", energySpanLabel(line, "1m", "2h", bars = false, zone = zone))
        val week = listOf("2026-10-11T21:00:00Z")
        assertEquals("DIN 12.10 · BARE PE ZI", energySpanLabel(week, "1d", "7d", bars = true, zone = zone))
    }

    @Test
    fun `freshness turns into a warning after a minute`() {
        val now = OffsetDateTime.parse("2026-10-18T12:00:30Z").toInstant().toEpochMilli()
        val fresh = energyFreshness("2026-10-18T12:00:28.500000+00:00", now)
        assertEquals("ACTUALIZAT ACUM 1 S", fresh.text)
        assertFalse(fresh.stale)
        val old = energyFreshness("2026-10-18T11:55:30+00:00", now)
        assertEquals("DATE VECHI · ACUM 5 MIN", old.text)
        assertTrue(old.stale)
        assertTrue(energyFreshness(null, now).stale)
    }
}
