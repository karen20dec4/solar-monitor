package com.rolling7.solar

import org.junit.Assert.assertEquals
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
        assertEquals("grid_power", energyFieldFor(EnergySubject.GRID, EnergyMode.POWER))
        assertEquals("energy_grid", energyFieldFor(EnergySubject.GRID, EnergyMode.ENERGY))
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
        assertEquals("energy_grid", energyFieldForSubject(EnergySubject.GRID, "energy_load_today"))
        assertEquals("grid_power", energyFieldForSubject(EnergySubject.GRID, "pv_power"))
        assertEquals("energy_load_today", energyFieldForSubject(EnergySubject.HOUSE, "energy_grid"))
    }

    @Test
    fun `the grid is a fourth subject with power and energy`() {
        assertEquals(
            listOf("CASA", "PANOURI", "BATERIE", "RETEA"),
            EnergySubject.values().map { it.label }
        )
        assertEquals(listOf(EnergyMode.POWER, EnergyMode.ENERGY), energyModesFor(EnergySubject.GRID))
        assertEquals(EnergySubject.GRID, energySubjectForField("energy_grid"))
        assertEquals(EnergyMode.ENERGY, energyModeForField("energy_grid"))
        assertEquals("energy_grid", normalizedEnergyField("energy_grid"))
        assertEquals("RETEA · ENERGIE", energyChartTitle("energy_grid"))
        assertEquals("RETEA · PUTERE", energyChartTitle("grid_power"))
        // La fel ca la celelalte, 2 ore / 24 ore / 7 zile.
        assertEquals(listOf("2h", "1d", "7d"), retroEnergyRangesForField("energy_grid"))
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
    fun `titles name subject and mode, not the range`() {
        assertEquals("CASA · PUTERE", energyChartTitle("output_power"))
        assertEquals("PANOURI · ENERGIE", energyChartTitle("energy_pv_today"))
        assertEquals("BATERIE · TENSIUNE", energyChartTitle("battery_voltage"))
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
    fun `led is green up to 10 s, orange up to a minute, then red`() {
        val now = OffsetDateTime.parse("2026-10-18T12:00:30Z").toInstant().toEpochMilli()
        assertEquals(LiveLed.FRESH, liveLedFor("2026-10-18T12:00:28.500000+00:00", now))
        assertEquals(LiveLed.FRESH, liveLedFor("2026-10-18T12:00:20+00:00", now))
        assertEquals(LiveLed.LATE, liveLedFor("2026-10-18T12:00:19+00:00", now))
        assertEquals(LiveLed.LATE, liveLedFor("2026-10-18T11:59:30+00:00", now))
        assertEquals(LiveLed.STALE, liveLedFor("2026-10-18T11:59:29+00:00", now))
        assertEquals(LiveLed.STALE, liveLedFor(null, now))
        // Ceasul telefonului putin in urma serverului nu aprinde avertismentul.
        assertEquals(LiveLed.FRESH, liveLedFor("2026-10-18T12:00:33+00:00", now))
    }
}
