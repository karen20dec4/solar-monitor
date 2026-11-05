package com.rolling7.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CasaRetroTest {
    private fun plug(
        id: String = "p",
        online: Boolean = true,
        on: Boolean = false,
        metered: Boolean = true,
        power: Double? = 0.0,
        schedules: List<CasaSchedule> = emptyList(),
        countdown: CasaCountdown? = null,
        inching: Boolean = false,
        off: CasaOffDeadline? = null
    ) = CasaPlug(
        id, id, online, on, metered, power, null, null, null, schedules, countdown,
        inching, 30, off, null
    )

    private fun sch(n: Int) = CasaSchedule("s$n", "0$n:00", "0$n:30", true)

    @Test
    fun `badge lines spell out schedule, countdown, inching and off deadline`() {
        val p = plug(
            schedules = listOf(sch(1)),
            countdown = CasaCountdown("on", 0, 18720),
            inching = true,
            off = CasaOffDeadline(0, 1380, "inching")
        )
        val lines = casaBadgeLines(p, maxLines = 10)
        assertEquals(
            listOf(
                CasaBadge(CasaBadgeKind.SCHEDULE, "PROGRAM 01:00-01:30 ZILNIC"),
                CasaBadge(CasaBadgeKind.TIMER, "PORNIRE IN 5H 12"),
                CasaBadge(CasaBadgeKind.LOOP, "INCHING: STINGE DUPA 30 MIN"),
                CasaBadge(CasaBadgeKind.TIMER, "STINGE IN 23 MIN")
            ),
            lines
        )
    }

    @Test
    fun `offline plug gets an OFFLINE badge first`() {
        val lines = casaBadgeLines(plug(online = false, schedules = listOf(sch(1))))
        assertEquals(CasaBadge(CasaBadgeKind.OFFLINE, "OFFLINE"), lines.first())
        assertEquals(2, lines.size)
    }

    @Test
    fun `too many badges are folded into a +N line`() {
        val lines = casaBadgeLines(plug(schedules = (1..6).map { sch(it) }), maxLines = 3)
        assertEquals(3, lines.size)
        assertEquals(CasaBadge(CasaBadgeKind.MORE, "+4 ALTELE"), lines.last())
    }

    @Test
    fun `no badges for a bare plug`() {
        assertTrue(casaBadgeLines(plug()).isEmpty())
    }

    @Test
    fun `summary counts only running online plugs and sums metered watts`() {
        val plugs = listOf(
            plug("a", on = true, power = 1650.0),
            plug("b", on = true, metered = false, power = null),
            plug("c", on = false, power = 0.0),
            plug("d", online = false, on = true, power = 500.0)
        )
        assertEquals(2 to 1650, casaPlugsSummary(plugs))
        // starea optimista: "c" tocmai a fost pornita de utilizator
        assertEquals(3 to 1650, casaPlugsSummary(plugs) { it.on || it.id == "c" })
    }

    @Test
    fun `summary text singular and plural`() {
        assertEquals("1 PORNITA · 60 W", casaSummaryText(1, 60))
        assertEquals("2 PORNITE · 1650 W", casaSummaryText(2, 1650))
        assertEquals("0 PORNITE · 0 W", casaSummaryText(0, 0))
    }

    @Test
    fun `temperature text and colour`() {
        assertEquals("20.3", casaTemperatureText(20.26))
        assertEquals("—", casaTemperatureText(null))
        assertEquals(RetroHouseBlue, casaTemperatureColor(6.6))
        assertEquals(RetroYellow, casaTemperatureColor(31.0))
    }

    @Test
    fun `sensor title drops the TEMP prefix`() {
        assertEquals("BECI", casaSensorTitle("Temp BECI"))
        assertEquals("ETAJ", casaSensorTitle("temp etaj"))
        assertEquals("TEMP", casaSensorTitle("Temp"))
        assertEquals("AFARA", casaSensorTitle("Afară"))
    }

    @Test
    fun `clock shows Bucharest time`() {
        // 2026-11-05 12:31:19 UTC = 14:31:19 la Bucuresti (EET, UTC+2)
        assertEquals("14:31:19", casaClockText(1_793_881_879_000L))
    }
}
