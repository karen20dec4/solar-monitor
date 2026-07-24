package com.rolling7.solar

import org.junit.Assert.assertEquals
import org.junit.Test

class RetroSystemReadingsTest {
    @Test
    fun `system cards use the correct live API fields and units`() {
        val readings = retroSystemReadings(sampleData())

        assertEquals("383", readings.house)
        assertEquals("445", readings.solar)
        assertEquals("55.92", readings.batteryVoltage)
        assertEquals("98", readings.inverterConsumption)
        assertEquals("33.5", readings.temperature)
        assertEquals("237.9", readings.gridVoltage)
    }

    @Test
    fun `missing telemetry produces placeholders instead of fake zeroes`() {
        val readings = retroSystemReadings(null)

        assertEquals("—", readings.house)
        assertEquals("—", readings.solar)
        assertEquals("—", readings.batteryVoltage)
        assertEquals("—", readings.inverterConsumption)
        assertEquals("—", readings.temperature)
        assertEquals("—", readings.gridVoltage)
    }

    @Test
    fun `temperature status changes from green to yellow and red`() {
        assertEquals(RetroOlive, retroSystemTemperatureColor(null))
        assertEquals(RetroSage, retroSystemTemperatureColor(33.5))
        assertEquals(RetroYellow, retroSystemTemperatureColor(45.0))
        assertEquals(RetroRed, retroSystemTemperatureColor(55.0))
    }

    @Test
    fun `system history shortcuts target matching energy graphs`() {
        assertEquals("output_power", RetroSystemSlot.HOUSE.historyField)
        assertEquals("pv_power", RetroSystemSlot.SOLAR.historyField)
        assertEquals("battery_voltage", RetroSystemSlot.BATTERY.historyField)
        assertEquals(null, RetroSystemSlot.INVERTER_CONSUMPTION.historyField)
        assertEquals(null, RetroSystemSlot.TEMPERATURE.historyField)
        assertEquals(null, RetroSystemSlot.GRID.historyField)
    }

    @Test
    fun `diagnostic console reports health battery grid and uptime without guessing`() {
        val data = sampleData()

        assertEquals("STATUS OPTIM · COD 12", retroSystemHealthText(data))
        assertEquals("DESCARCARE 36W · 55.92V", retroSystemBatteryText(data))
        assertEquals("CONECTATA · 237.9V", retroSystemGridText(data))
        assertEquals("UP 2z 0h", retroSystemUptimeText(data.serverUptimeSeconds))
        assertEquals("UP —", retroSystemUptimeText(null))
    }

    @Test
    fun `diagnostic console exposes explicit warning states`() {
        val hot = sampleData().copy(inverterTemp = 56.0)
        val batteryHigh = sampleData().copy(batteryVoltage = 57.1)
        val disconnectedGrid = sampleData().copy(gridVoltage = 0.0)

        assertEquals("ATENTIE TEMP · COD 12", retroSystemHealthText(hot))
        assertEquals("ATENTIE BATERIE · COD 12", retroSystemHealthText(batteryHigh))
        assertEquals("DECONECTATA · 0.0V", retroSystemGridText(disconnectedGrid))
    }

    private fun sampleData() = SolarData(
        pv = 445.0,
        pv1 = 220.0,
        pv2 = 225.0,
        house = 383.0,
        loadPercent = 6.0,
        batteryVoltage = 55.92,
        batterySoc = 81.0,
        batteryPower = -36.0,
        batteryDisplay = -36.0,
        batteryCharge = 0.0,
        batteryDischarge = 36.0,
        batterySupport = 36.0,
        gridImport = 0.0,
        gridCharge = 0.0,
        gridVoltage = 237.9,
        inverterTemp = 33.5,
        inverterLoss = 98.0,
        serverCpuPercent = 12.5,
        serverMemoryPercent = 43.2,
        serverUploadKbps = 10.4,
        serverUptimeSeconds = 172_800.0,
        energyPvToday = 8.2,
        energyPvTotal = 6_586.0,
        energyLoadToday = 4.6,
        energyLoadTotal = 6_102.5,
        status = 12.0,
        houseSource = 1.0,
        timestamp = "2026-07-24T11:04:45.238392+00:00"
    )
}
