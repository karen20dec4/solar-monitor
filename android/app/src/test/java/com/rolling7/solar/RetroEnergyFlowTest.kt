package com.rolling7.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetroEnergyFlowTest {
    @Test
    fun `solar supplies house and charges battery without fake battery discharge`() {
        val flow = resolveRetroEnergyFlow(
            pv = 1_559.0,
            house = 220.0,
            batteryDisplay = 1_285.0,
            batteryCharge = 1_285.0,
            batterySupport = 0.0,
            gridImport = 0.0,
            gridCharge = 0.0
        )

        assertEquals(RetroBatteryFlow.CHARGING, flow.battery)
        assertTrue(flow.solarToHouse)
        assertTrue(flow.solarToBattery)
        assertFalse(flow.batteryToHouse)
        assertFalse(flow.gridToHouse)
        assertEquals(RetroSage, retroBatteryFlowColor(flow.battery))
    }

    @Test
    fun `battery supplies house only while discharging`() {
        val flow = resolveRetroEnergyFlow(
            pv = 0.0,
            house = 410.0,
            batteryDisplay = -463.0,
            batteryCharge = 0.0,
            batterySupport = 463.0,
            gridImport = 0.0,
            gridCharge = 0.0
        )

        assertEquals(RetroBatteryFlow.DISCHARGING, flow.battery)
        assertFalse(flow.solarToHouse)
        assertFalse(flow.solarToBattery)
        assertTrue(flow.batteryToHouse)
        assertEquals(RetroYellow, retroBatteryFlowColor(flow.battery))
    }

    @Test
    fun `idle battery does not animate in either direction`() {
        val flow = resolveRetroEnergyFlow(
            pv = 900.0,
            house = 300.0,
            batteryDisplay = 18.0,
            batteryCharge = 18.0,
            batterySupport = 0.0,
            gridImport = 0.0,
            gridCharge = 0.0
        )

        assertEquals(RetroBatteryFlow.IDLE, flow.battery)
        assertTrue(flow.solarToHouse)
        assertFalse(flow.solarToBattery)
        assertFalse(flow.batteryToHouse)
        assertEquals(RetroOlive, retroBatteryFlowColor(flow.battery))
    }

    @Test
    fun `grid charging is not presented as battery discharge`() {
        val flow = resolveRetroEnergyFlow(
            pv = 0.0,
            house = 160.0,
            batteryDisplay = 740.0,
            batteryCharge = 740.0,
            batterySupport = 0.0,
            gridImport = 160.0,
            gridCharge = 740.0
        )

        assertEquals(RetroBatteryFlow.CHARGING, flow.battery)
        assertTrue(flow.gridToHouse)
        assertTrue(flow.gridToBattery)
        assertFalse(flow.batteryToHouse)
    }
}
