package com.rolling7.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CasaDetailTest {
    private val zone = ZoneId.of("Europe/Bucharest")
    private fun epoch(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toEpochSecond()

    @Test
    fun `inching steps by 5 and clamps to 5 and 720`() {
        assertEquals(35, casaInchingStep(30, 1))
        assertEquals(25, casaInchingStep(30, -1))
        assertEquals(5, casaInchingStep(5, -1))
        assertEquals(720, casaInchingStep(720, 1))
        assertEquals(720, casaInchingStep(718, 1))
        assertEquals(10, casaInchingStep(7, 1))
    }

    @Test
    fun `inching clamp handles server zero`() {
        assertEquals(30, casaInchingClamp(0))
        assertEquals(5, casaInchingClamp(2))
        assertEquals(720, casaInchingClamp(1000))
    }

    @Test
    fun `countdown defaults to the opposite action and validates 1 to 1440`() {
        assertEquals("off", casaCountdownDefaultAction(true))
        assertEquals("on", casaCountdownDefaultAction(false))
        assertFalse(casaCountdownValid(0, 0))
        assertTrue(casaCountdownValid(0, 5))
        assertTrue(casaCountdownValid(24, 0))
        assertFalse(casaCountdownValid(24, 5))
        assertEquals(125, casaCountdownTotal(2, 5))
    }

    @Test
    fun `duration parts round up and deadline text`() {
        assertEquals(5 to 12, casaDurationParts(18720))
        assertEquals(0 to 1, casaDurationParts(30))
        assertEquals(0 to 0, casaDurationParts(-5))
        assertEquals("OPRIRE IN 23 MIN", casaDeadlineText(1380))
        assertEquals("OPRIRE IN 1 H 05 MIN", casaDeadlineText(3900))
    }

    @Test
    fun `interval validation and limit`() {
        assertEquals("INTERVAL INVALID", casaIntervalError("10:00", "10:00"))
        assertNull(casaIntervalError("22:00", "06:00"))
        assertTrue(casaCanAddInterval(5))
        assertFalse(casaCanAddInterval(6))
        assertEquals("07:05", casaHm(7, 5))
    }

    @Test
    fun `nice axis covers the data in four equal steps`() {
        val a = casaNiceAxis(1.3, 8.1, 4, 1.0)
        assertEquals(0.0, a.lo, 1e-9)
        assertEquals(10.0, a.hi, 1e-9)
        assertEquals(2.5, a.step, 1e-9)
        val flat = casaNiceAxis(6.0, 6.0, 4, 1.0)
        assertTrue(flat.lo <= 6.0 && flat.hi >= 6.0)
        assertEquals(4.0, flat.hi - flat.lo, 1e-9)
        val h = casaNiceAxis(78.0, 100.0, 4, 5.0, cap = 100.0)
        assertEquals(100.0, h.hi, 1e-9)
        assertTrue(h.lo <= 78.0)
        val neg = casaNiceAxis(-3.0, 4.0, 4, 1.0)
        assertTrue(neg.lo <= -3.0 && neg.hi >= 4.0)
    }

    @Test
    fun `axis labels`() {
        assertEquals("6°", casaAxisLabel(6.0, 2.0, "°"))
        assertEquals("2.5°", casaAxisLabel(2.5, 2.5, "°"))
        assertEquals("80%", casaAxisLabel(80.0, 20.0, "%"))
    }

    @Test
    fun `time ticks for 2h and 1d are HH-MM and 4 to 7 in count`() {
        val now = epoch(2026, 11, 5, 14, 7)
        val t2 = casaTimeTicks("2h", now, zone)
        assertTrue(t2.size in 4..7)
        assertEquals("12:20", t2.first().label)
        val t1 = casaTimeTicks("1d", now, zone)
        assertTrue(t1.size in 4..7)
        assertEquals(listOf("16:00", "20:00", "00:00", "04:00", "08:00", "12:00"), t1.map { it.label })
    }

    @Test
    fun `time ticks for 7d are day abbreviations`() {
        val now = epoch(2026, 11, 5, 14, 7) // joi
        val t = casaTimeTicks("7d", now, zone)
        assertTrue(t.size in 4..7)
        assertEquals(listOf("VI", "SA", "DU", "LU", "MA", "MI", "JO"), t.map { it.label })
    }

    @Test
    fun `stat time and fractions`() {
        assertEquals("12:26", casaStatTime("1d", epoch(2026, 11, 5, 12, 26), zone))
        assertEquals("JO 12:26", casaStatTime("7d", epoch(2026, 11, 5, 12, 26), zone))
        assertEquals(0f, casaChartFraction(5, 10, 20), 0f)
        assertEquals(0.5f, casaChartFraction(15, 10, 20), 1e-6f)
        assertEquals(1f, casaChartFraction(30, 10, 20), 0f)
    }

    @Test
    fun `battery text`() {
        assertEquals("BATERIE 87%", casaBatteryText(87.0))
        assertEquals("BATERIE ?", casaBatteryText(null))
    }
}
