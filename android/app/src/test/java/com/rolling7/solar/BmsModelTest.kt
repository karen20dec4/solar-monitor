package com.rolling7.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BmsModelTest {
    private fun reading(
        cells: List<Double> = List(14) { 3.93 },
        chargeOn: Boolean = true,
        dischargeOn: Boolean = true,
        temp1: Double = 21.0,
        temp2: Double = 20.0
    ) = BmsReading(
        cells = cells, voltage = 55.0, current = -4.7, power = -258.0, soc = 87.0,
        tempBattery1 = temp1, tempBattery2 = temp2, tempMos = 26.0,
        chargeOn = chargeOn, dischargeOn = dischargeOn, balancing = 0,
        remainingAh = 156.0, nominalAh = 180.0, cycles = 219, alarmBits = 0
    )

    private val sampleJson = """
        {"timestamp":"2026-10-22T18:29:53+00:00","voltage":55.114,"current":-7.344,"power":-404.7,
         "soc":88.0,"temp_1":21.6,"temp_2":19.8,"temp_mos":26.5,"charge_mosfet":1.0,"discharge_mosfet":1.0,
         "balancing":2.0,"capacity_remaining_ah":158.4,"capacity_nominal_ah":180.0,"cycles":219.0,"alarm_bits":0.0,
         "cell_count":3.0,"cell_min":3.931,"cell_max":3.936,"cell_delta":0.005,"cell_avg":3.933,
         "cell_02":3.934,"cell_01":3.931,"cell_03":3.936,
         "alarms":[{"key":"bms_mosfet","title":"BMS: bateria deconectata!","msg":"MOSFET incarcare oprit","prio":"urgent"}]}
    """.trimIndent()

    @Test
    fun `parses a live response with ordered cells and alarms`() {
        val state = parseBmsState(sampleJson)
        val r = state.reading
        assertNotNull(r)
        assertEquals(listOf(3.931, 3.934, 3.936), r!!.cells)
        assertEquals(55.114, r.voltage, 1e-9)
        assertEquals(-7.344, r.current, 1e-9)
        assertEquals(88.0, r.soc, 1e-9)
        assertEquals(2, r.balancing)
        assertEquals(219, r.cycles)
        assertTrue(r.chargeOn && r.dischargeOn)
        assertEquals(5.0, r.deltaMv, 1e-6)
        assertEquals(21.6, r.batteryTempMax, 1e-9)
        assertEquals(1, state.alarms.size)
        assertEquals("bms_mosfet", state.alarms[0].key)
        assertEquals("MOSFET incarcare oprit", state.alarms[0].message)
        assertTrue(state.alarms[0].rings)
    }

    @Test
    fun `the shown soc comes from the voltage when the server sends it`() {
        val fromVoltage = parseBmsState(sampleJson.replace("\"soc\":88.0,", "\"soc\":88.0,\"soc_voltage\":75.6,")).reading!!
        assertEquals(75.6, fromVoltage.soc, 1e-9)
        assertEquals(88.0, fromVoltage.bmsSoc, 1e-9)
        assertEquals("76", bmsSocText(fromVoltage.soc))
        val olderServer = parseBmsState(sampleJson.replace("\"soc\":88.0,", "\"soc\":88.0,\"soc_voltage\":null,")).reading!!
        assertEquals(88.0, olderServer.soc, 1e-9)
    }

    @Test
    fun `no data from the BMS gives a null reading but keeps alarms`() {
        val state = parseBmsState(
            """{"timestamp":null,"alarms":[{"key":"bms_legatura","title":"BMS: fara date","msg":"3 min","prio":"default"}]}"""
        )
        assertNull(state.reading)
        assertEquals(1, state.alarms.size)
        assertFalse(state.alarms[0].rings)
        assertEquals(BmsLevel.NO_DATA, bmsLevel(state))
    }

    @Test
    fun `a partial response is treated as no data instead of zeros`() {
        assertNull(parseBmsState("""{"timestamp":"2026-10-22T18:29:53+00:00","voltage":55.1,"alarms":[]}""").reading)
        assertNull(parseBmsState("""{"timestamp":null,"alarms":[]}""").reading)
    }

    @Test
    fun `status level follows alarms then MOSFETs then soft limits`() {
        fun state(r: BmsReading, alarms: List<BmsAlarm> = emptyList()) = BmsState(r, alarms)
        val urgent = BmsAlarm("a", "t", "m", "urgent")
        val info = BmsAlarm("b", "t", "m", "default")
        assertEquals(BmsLevel.NO_DATA, bmsLevel(null))
        assertEquals(BmsLevel.OK, bmsLevel(state(reading())))
        assertEquals(BmsLevel.ALARM, bmsLevel(state(reading(), listOf(urgent))))
        assertEquals(BmsLevel.WARNING, bmsLevel(state(reading(), listOf(info))))
        // MOSFET oprit = bateria deconectata, chiar daca serverul n-a apucat sa trimita alarma.
        assertEquals(BmsLevel.ALARM, bmsLevel(state(reading(chargeOn = false))))
        assertEquals(BmsLevel.ALARM, bmsLevel(state(reading(dischargeOn = false))))
        assertEquals(BmsLevel.WARNING, bmsLevel(state(reading(cells = List(13) { 3.93 } + 3.99))))
        assertEquals(BmsLevel.WARNING, bmsLevel(state(reading(temp2 = 41.0))))
    }

    @Test
    fun `current text keeps the sign and drops decimals above 100 A`() {
        assertEquals("-4.7", bmsCurrentText(-4.7))
        assertEquals("+12.3", bmsCurrentText(12.34))
        assertEquals("0.0", bmsCurrentText(0.0))
        assertEquals("-120", bmsCurrentText(-119.6))
        assertEquals("+70.0", bmsCurrentText(70.0))
    }

    @Test
    fun `soc and delta texts are whole numbers`() {
        assertEquals("88", bmsSocText(87.6))
        assertEquals("5", bmsDeltaText(4.6))
    }

    @Test
    fun `cell deviation is relative to the average and clipped`() {
        assertEquals(emptyList<Float>(), cellDeviationFractions(emptyList()))
        assertEquals(List(14) { 0f }, cellDeviationFractions(List(14) { 3.93 }))
        val bars = cellDeviationFractions(listOf(3.950, 3.930, 3.910), fullScaleV = 0.020)
        assertEquals(1f, bars[0], 1e-4f)
        assertEquals(0f, bars[1], 1e-4f)
        assertEquals(-1f, bars[2], 1e-4f)
        // o celula la 100 mV de medie nu depaseste scara
        assertEquals(-1f, cellDeviationFractions(listOf(3.9, 3.9, 3.9, 3.8))[3], 1e-4f)
    }

    @Test
    fun `an alarm rings once while active and re-arms when it clears`() {
        val tracker = BmsAlarmTracker()
        val mosfet = BmsAlarm("bms_mosfet", "t", "m", "urgent")
        val cooldown = 300_000L
        assertEquals(listOf(mosfet), tracker.evaluate(listOf(mosfet), 0L, cooldown))
        assertTrue(tracker.evaluate(listOf(mosfet), 2_000L, cooldown).isEmpty())
        assertTrue(tracker.evaluate(listOf(mosfet), 400_000L, cooldown).isEmpty())   // inca activa: nu suna iar
        assertTrue(tracker.evaluate(emptyList(), 401_000L, cooldown).isEmpty())       // s-a stins
        assertEquals(listOf(mosfet), tracker.evaluate(listOf(mosfet), 402_000L, cooldown)) // cooldown trecut
    }

    @Test
    fun `a flickering alarm does not ring again inside the cooldown`() {
        val tracker = BmsAlarmTracker()
        val temp = BmsAlarm("bms_temp_sus", "t", "m", "urgent")
        assertEquals(1, tracker.evaluate(listOf(temp), 0L, 300_000L).size)
        assertTrue(tracker.evaluate(emptyList(), 10_000L, 300_000L).isEmpty())
        assertTrue(tracker.evaluate(listOf(temp), 20_000L, 300_000L).isEmpty())
    }

    @Test
    fun `non ringing alarms and several alarms at once`() {
        val tracker = BmsAlarmTracker()
        val link = BmsAlarm("bms_legatura", "t", "m", "default")
        val a = BmsAlarm("bms_mosfet", "t", "m", "urgent")
        val b = BmsAlarm("bms_dezechilibru", "t", "m", "high")
        assertTrue(tracker.evaluate(listOf(link), 0L, 0L).isEmpty())
        assertEquals(listOf(a, b), tracker.evaluate(listOf(link, a, b), 1_000L, 0L))
        assertTrue(tracker.evaluate(listOf(link, a, b), 2_000L, 0L).isEmpty())
    }
}
