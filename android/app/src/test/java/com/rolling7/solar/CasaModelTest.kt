package com.rolling7.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CasaModelTest {
    private val plugJson = """{"id":"bf1","name":"Boiler","online":true,"on":false,"metered":true,
        "power":0.0,"voltage":231.2,"current":0.0,"energy_today":2.4,
        "schedules":[{"id":"a1b2","start":"10:30","stop":"16:00","enabled":true}],
        "countdown":{"action":"on","at":1730800000,"seconds_left":18720},
        "inching":{"enabled":false,"minutes":30},
        "off_deadline":{"at":1730820000,"seconds_left":3600,"reason":"schedule"},
        "error":null}"""

    private val plainJson = """{"id":"bf2","name":"Bucătărie","online":false,"on":true,"metered":false,
        "power":null,"voltage":null,"current":null,"energy_today":null,
        "schedules":[],"countdown":null,
        "inching":{"enabled":true,"minutes":15},
        "off_deadline":null,"error":"priza nu raspunde"}"""

    @Test
    fun `parses a metered plug with countdown and off deadline`() {
        val p = parsePlug(plugJson)
        assertEquals("bf1", p.id)
        assertEquals("Boiler", p.name)
        assertTrue(p.online)
        assertFalse(p.on)
        assertTrue(p.metered)
        assertEquals(0.0, p.power!!, 0.0)
        assertEquals(231.2, p.voltage!!, 0.0)
        assertEquals(0.0, p.current!!, 0.0)
        assertEquals(2.4, p.energyToday!!, 0.0)
        assertEquals(listOf(CasaSchedule("a1b2", "10:30", "16:00", true)), p.schedules)
        assertEquals(CasaCountdown("on", 1730800000L, 18720L), p.countdown)
        assertFalse(p.inchingEnabled)
        assertEquals(30, p.inchingMinutes)
        assertEquals(CasaOffDeadline(1730820000L, 3600L, "schedule"), p.offDeadline)
        assertNull(p.error)
    }

    @Test
    fun `parses a plain plug with nulls`() {
        val p = parsePlug(plainJson)
        assertFalse(p.online)
        assertTrue(p.on)
        assertFalse(p.metered)
        assertNull(p.power)
        assertNull(p.voltage)
        assertNull(p.current)
        assertNull(p.energyToday)
        assertTrue(p.schedules.isEmpty())
        assertNull(p.countdown)
        assertNull(p.offDeadline)
        assertTrue(p.inchingEnabled)
        assertEquals(15, p.inchingMinutes)
        assertEquals("priza nu raspunde", p.error)
    }

    @Test
    fun `parses the plug list`() {
        val plugs = parsePlugs("""{"plugs":[$plugJson,$plainJson],"timestamp":1730800000}""")
        assertEquals(listOf("bf1", "bf2"), plugs.map { it.id })
    }

    @Test
    fun `parses sensors with spark and a missing value`() {
        val s = parseSensors(
            """{"cloud":"ok","sensors":[
            {"id":"s1","name":"Temp BECI","temperature":21.5,"humidity":48.0,"battery":87,"updated":1730800000,
             "spark":[[1730799000,21.4],[1730799600,21.5]]},
            {"id":"s2","name":"Pod","temperature":null,"humidity":null,"battery":null,"updated":null,"spark":[]}]}"""
        )
        assertTrue(s.cloudOk)
        assertEquals(2, s.sensors.size)
        val a = s.sensors[0]
        assertEquals(21.5, a.temperature!!, 0.0)
        assertEquals(87.0, a.battery!!, 0.0)
        assertEquals(1730800000L, a.updated)
        assertEquals(listOf(1730799000L to 21.4, 1730799600L to 21.5), a.spark)
        val b = s.sensors[1]
        assertNull(b.temperature)
        assertNull(b.humidity)
        assertNull(b.battery)
        assertNull(b.updated)
        assertTrue(b.spark.isEmpty())
    }

    @Test
    fun `cloud other than ok is not ok`() {
        assertFalse(parseSensors("""{"cloud":"disconnected","sensors":[]}""").cloudOk)
        assertFalse(parseSensors("""{"cloud":"no_session","sensors":[]}""").cloudOk)
    }

    @Test
    fun `parses sensor history with stats`() {
        val h = parseSensorHistory(
            """{"id":"s1","name":"x","range":"1d",
            "temperature":[[100,20.0],[700,21.0]],"humidity":[[100,50.0]],
            "stats":{"temp_min":{"v":19.5,"t":150},"temp_max":{"v":22.0,"t":650},
                     "hum_min":null,"hum_max":{"v":60.0,"t":300}}}"""
        )
        assertEquals("1d", h.range)
        assertEquals(listOf(100L to 20.0, 700L to 21.0), h.temperature)
        assertEquals(listOf(100L to 50.0), h.humidity)
        assertEquals(19.5 to 150L, h.tempMin)
        assertEquals(22.0 to 650L, h.tempMax)
        assertNull(h.humMin)
        assertEquals(60.0 to 300L, h.humMax)
    }

    @Test
    fun `history without stats or points is empty`() {
        val h = parseSensorHistory("""{"id":"s1","name":"x","range":"2h","temperature":[],"humidity":[]}""")
        assertTrue(h.temperature.isEmpty())
        assertNull(h.tempMin)
        assertNull(h.humMax)
    }

    @Test
    fun `formats durations`() {
        assertEquals("5H 12", formatDuration(18720))
        assertEquals("1H 05", formatDuration(3900))
        assertEquals("1H 00", formatDuration(3600))
        assertEquals("23 MIN", formatDuration(1380))
        assertEquals("1 MIN", formatDuration(60))
        assertEquals("59 MIN", formatDuration(3599))
        assertEquals("<1 MIN", formatDuration(59))
        assertEquals("<1 MIN", formatDuration(0))
        assertEquals("<1 MIN", formatDuration(-5))
    }

    @Test
    fun `formats age`() {
        val now = 1_000_000L
        assertEquals("FARA DATE", formatAge(now, null))
        assertEquals("ACUM", formatAge(now, now - 59))
        assertEquals("ACUM", formatAge(now, now + 5))
        assertEquals("ACUM 1 MIN", formatAge(now, now - 60))
        assertEquals("ACUM 4 MIN", formatAge(now, now - 4 * 60 - 20))
        assertEquals("ACUM 59 MIN", formatAge(now, now - 3599))
        assertEquals("ACUM 1 H", formatAge(now, now - 3600))
        assertEquals("ACUM 6 H", formatAge(now, now - 6 * 3600 - 100))
        assertEquals("ACUM 1 Z", formatAge(now, now - 86400))
        assertEquals("ACUM 3 Z", formatAge(now, now - 3 * 86400 - 50))
    }

    @Test
    fun `stale after thirty minutes`() {
        val now = 1_000_000L
        assertFalse(isStale(now, now - 1800))
        assertTrue(isStale(now, now - 1801))
        assertFalse(isStale(now, now - 10))
        assertTrue(isStale(now, null))
    }

    @Test
    fun `retro labels are uppercase ascii`() {
        assertEquals("BUCATARIE", retroLabel("Bucătărie"))
        assertEquals("SOPRON SI TARA", retroLabel("șopron şi ţară"))
        assertEquals("AIR FIER", retroLabel("âir fîer"))
        assertEquals("INCALZIRE", retroLabel("Încălzire"))
        assertEquals("BOILER", retroLabel("Boiler"))
    }

    @Test
    fun `http answers map to messages`() {
        assertEquals(CasaResult.Unauthorized, casaFailure(401, """{"error":"token invalid"}"""))
        assertEquals(CasaResult.Error("PREA MULTE INCERCARI"), casaFailure(429, """{"error":"prea multe incercari","retry_after":30}"""))
        assertEquals(CasaResult.Error("PRIZA NU RASPUNDE"), casaFailure(502, """{"error":"priza nu raspunde","plug":{}}"""))
        assertEquals(CasaResult.Error("PRIZA NECUNOSCUTA"), casaFailure(404, """{"error":"x"}"""))
        assertEquals(CasaResult.Error("SERVER INDISPONIBIL"), casaFailure(503, """{"error":"parola nesetata"}"""))
        assertEquals(CasaResult.Error("MAXIM 6 INTERVALE"), casaFailure(400, """{"error":"maxim 6 intervale"}"""))
        assertEquals(CasaResult.Error("DURATA INVALIDA"), casaFailure(400, """{"error":"durată invalidă"}"""))
        assertEquals(CasaResult.Error("CERERE INVALIDA"), casaFailure(400, "nu e json"))
        assertEquals(CasaResult.Error("EROARE SERVER"), casaFailure(500, ""))
    }

    @Test
    fun `badges in display order`() {
        val base = parsePlug(plugJson)
        // countdown "on" + off_deadline reason schedule + enabled schedule; inching off
        assertEquals(
            listOf("10:30-16:00", "PORNIRE IN 5H 12", "STINGE IN 1H 00"),
            plugBadges(base)
        )
        val full = base.copy(
            schedules = listOf(
                CasaSchedule("a", "10:30", "16:00", true),
                CasaSchedule("b", "01:00", "02:00", false),
                CasaSchedule("c", "20:00", "22:15", true)
            ),
            countdown = CasaCountdown("off", 1, 6000),
            inchingEnabled = true,
            inchingMinutes = 30,
            offDeadline = CasaOffDeadline(2, 1380, "inching")
        )
        assertEquals(
            listOf("10:30-16:00", "20:00-22:15", "OPRIRE IN 1H 40", "AUTO 30 MIN", "STINGE IN 23 MIN"),
            plugBadges(full)
        )
        // off_deadline caused by the countdown is already shown
        val cd = full.copy(offDeadline = CasaOffDeadline(2, 6000, "countdown"))
        assertEquals(
            listOf("10:30-16:00", "20:00-22:15", "OPRIRE IN 1H 40", "AUTO 30 MIN"),
            plugBadges(cd)
        )
        assertTrue(plugBadges(parsePlug(plainJson)).contains("AUTO 15 MIN"))
    }
}
