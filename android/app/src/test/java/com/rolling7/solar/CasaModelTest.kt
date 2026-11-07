package com.rolling7.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CasaModelTest {
    private val zone = ZoneId.of("Europe/Bucharest")
    private fun epoch(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toEpochSecond()

    private val plugJson = """{"id":"bf1","name":"Boiler","online":true,"on":false,"metered":true,
        "power":0.0,"voltage":231.2,"current":0.0,"energy_today":2.4,
        "events":[{"id":"e1","time":"10:30","action":"on","days":null,"enabled":true},
                  {"id":"e2","time":"16:00","action":"off","days":[1,2,3,4,5],"enabled":false}],
        "next_event":{"id":"e1","time":"10:30","action":"on","at":1793867400},
        "schedules":[],
        "countdown":{"action":"on","at":1730800000,"seconds_left":18720},
        "inching":{"enabled":false,"minutes":30},
        "off_deadline":{"at":1730820000,"seconds_left":3600,"reason":"schedule"},
        "error":null}"""

    private val plainJson = """{"id":"bf2","name":"Bucătărie","online":false,"on":true,"metered":false,
        "power":null,"voltage":null,"current":null,"energy_today":null,
        "events":[],"next_event":null,"schedules":[],"countdown":null,
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
        assertEquals(
            listOf(
                CasaEvent("e1", "10:30", "on", null, true),
                CasaEvent("e2", "16:00", "off", listOf(1, 2, 3, 4, 5), false)
            ),
            p.events
        )
        assertEquals(CasaNextEvent("e1", "10:30", "on", 1793867400L), p.nextEvent)
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
        assertTrue(p.events.isEmpty())
        assertNull(p.nextEvent)
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
    fun `reply from an old server without events has no events and no next event`() {
        val p = parsePlug(
            """{"id":"bf9","name":"Vechi","online":true,"on":true,"metered":false,
            "schedules":[{"id":"a1b2","start":"10:30","stop":"16:00","enabled":true}],
            "countdown":null,"inching":{"enabled":false,"minutes":30},"off_deadline":null,"error":null}"""
        )
        assertTrue(p.events.isEmpty())
        assertNull(p.nextEvent)
    }

    @Test
    fun `event days null is daily and a missing enabled means enabled`() {
        val p = parsePlug(
            """{"id":"bf8","name":"x","online":true,"on":false,"metered":false,
            "events":[{"id":"e1","time":"17:00","action":"off"},
                      {"id":"e2","time":"09:00","action":"on","days":[6,7],"enabled":true}],
            "next_event":null,"countdown":null,"inching":{"enabled":false,"minutes":30},
            "off_deadline":null,"error":null}"""
        )
        assertEquals(CasaEvent("e1", "17:00", "off", null, true), p.events[0])
        assertEquals(listOf(6, 7), p.events[1].days)
        assertNull(p.nextEvent)
    }

    @Test
    fun `event days text`() {
        assertEquals("ZILNIC", casaDaysText(null))
        assertEquals("L-V", casaDaysText(listOf(1, 2, 3, 4, 5)))
        assertEquals("SA · DU", casaDaysText(listOf(6, 7)))
        assertEquals("L · MI · V", casaDaysText(listOf(1, 3, 5)))
        assertEquals("J", casaDaysText(listOf(4)))
        assertEquals(listOf("L", "MA", "MI", "J", "V", "SA", "DU"), CasaDayShort)
    }

    @Test
    fun `event action text`() {
        assertEquals("PORNESTE", casaActionText("on"))
        assertEquals("OPRESTE", casaActionText("off"))
        assertEquals("OPRESTE", casaActionText("altceva"))
    }

    @Test
    fun `events are limited to twelve per plug`() {
        assertEquals(12, MaxEvents)
        assertTrue(casaCanAddEvent(0))
        assertTrue(casaCanAddEvent(11))
        assertFalse(casaCanAddEvent(12))
    }

    @Test
    fun `next event text for today and for another day`() {
        val now = epoch(2026, 11, 5, 14, 7) // joi
        // azi: fara zi
        assertEquals(
            "OPRESTE 16:00 · IN 1H 53M",
            casaNextEventText(CasaNextEvent("e", "16:00", "off", epoch(2026, 11, 5, 16, 0)), now, zone)
        )
        // maine (vineri): ziua scurta inaintea orei
        assertEquals(
            "PORNESTE V 09:00 · IN 18H 53M",
            casaNextEventText(CasaNextEvent("e", "09:00", "on", epoch(2026, 11, 6, 9, 0)), now, zone)
        )
        // luni 17:00, peste 4 zile 2 ore 53 min: zile si ore
        assertEquals(
            "OPRESTE L 17:00 · IN 4Z 2H",
            casaNextEventText(CasaNextEvent("e", "17:00", "off", epoch(2026, 11, 9, 17, 0)), now, zone)
        )
    }

    @Test
    fun `next event day is judged by the local date not by UTC`() {
        // 2026-11-05 20:00 UTC = 22:00 la Bucuresti; 22:30 UTC = 00:30 vineri la Bucuresti
        val now = epoch(2026, 11, 5, 22, 0)
        val at = epoch(2026, 11, 6, 0, 30)
        assertEquals(
            "OPRESTE V 00:30 · IN 2H 30M",
            casaNextEventText(CasaNextEvent("e", "00:30", "off", at), now, zone)
        )
    }

    @Test
    fun `next event countdown rounds up and has its own units`() {
        val min = 60L
        val h = 3600L
        // rotunjit in sus: niciodata 0M
        assertEquals("IN 1M", casaNextEventIn(30))
        assertEquals("IN 1M", casaNextEventIn(1))
        assertEquals("IN 1M", casaNextEventIn(min))
        assertEquals("IN 2M", casaNextEventIn(min + 1))
        assertEquals("IN 59M", casaNextEventIn(59 * min))
        // de la o ora: ore si minute, fara zero in fata
        assertEquals("IN 1H 0M", casaNextEventIn(h))
        assertEquals("IN 1H 1M", casaNextEventIn(h + 1))
        assertEquals("IN 2H 5M", casaNextEventIn(2 * h + 5 * min))
        assertEquals("IN 23H 59M", casaNextEventIn(23 * h + 59 * min))
        // de la 24 h: zile si ore
        assertEquals("IN 1Z 0H", casaNextEventIn(24 * h))
        assertEquals("IN 1Z 0H", casaNextEventIn(24 * h + 59 * min))
        assertEquals("IN 3Z 2H", casaNextEventIn(3 * 24 * h + 2 * h))
        // trecut sau chiar acum (sondare veche): ACUM
        assertEquals("ACUM", casaNextEventIn(0))
        assertEquals("ACUM", casaNextEventIn(-5))
        assertEquals("ACUM", casaNextEventIn(-3 * h))
    }

    @Test
    fun `next event text says ACUM when the time has come`() {
        val now = epoch(2026, 11, 5, 16, 0)
        assertEquals(
            "OPRESTE 16:00 · ACUM",
            casaNextEventText(CasaNextEvent("e", "16:00", "off", epoch(2026, 11, 5, 16, 0)), now, zone)
        )
        assertEquals(
            "PORNESTE 15:59 · ACUM",
            casaNextEventText(CasaNextEvent("e", "15:59", "on", epoch(2026, 11, 5, 15, 59)), now, zone)
        )
    }

    @Test
    fun `a malformed event is skipped and the plug still parses`() {
        val p = parsePlug(
            """{"id":"bf7","name":"x","online":true,"on":false,"metered":false,
            "events":[
              {"id":"ok1","time":"17:00","action":"off","days":null,"enabled":true},
              {"id":"bad1","time":"17:00","action":"off","days":["L","MA"],"enabled":true},
              {"id":"bad2","time":"18:00","action":"off","days":[],"enabled":true},
              {"id":"bad3","time":"19:00","action":"off","days":"1,2","enabled":true},
              {"id":"bad4","time":"19:00","action":"off","days":[1,2.5],"enabled":true},
              {"id":"bad5","time":"19:00","action":"off","days":[0,9],"enabled":true},
              {"time":"20:00","action":"off"},
              {"id":"bad6","action":"off"},
              {"id":"ok2","time":"09:00","action":"on","days":[6,7]}],
            "next_event":null,"countdown":null,"inching":{"enabled":false,"minutes":30},
            "off_deadline":null,"error":null}"""
        )
        assertEquals(listOf("ok1", "ok2"), p.events.map { it.id })
        assertEquals(listOf(6, 7), p.events[1].days)
    }

    @Test
    fun `event request bodies`() {
        val daily = casaEventBody("17:00", "off", null)
        assertEquals("17:00", daily.getString("time"))
        assertEquals("off", daily.getString("action"))
        assertTrue(daily.has("days") && daily.isNull("days"))
        assertEquals(3, daily.length())
        val some = casaEventBody("09:00", "on", listOf(6, 7))
        assertEquals(listOf(6, 7), (0 until some.getJSONArray("days").length()).map { some.getJSONArray("days").getInt(it) })
    }

    @Test
    fun `event update body sends only what changes`() {
        assertEquals(1, casaEventUpdateBody(enabled = false).length())
        assertFalse(casaEventUpdateBody(enabled = false).getBoolean("enabled"))
        val t = casaEventUpdateBody(time = "16:25", action = "on")
        assertEquals(setOf("time", "action"), t.keySet())
        // zilnic = days null explicit; lista ramane lista
        val d = casaEventUpdateBody(daily = true)
        assertTrue(d.has("days") && d.isNull("days"))
        assertEquals(1, d.length())
        val l = casaEventUpdateBody(days = listOf(1, 2))
        assertEquals(2, l.getJSONArray("days").length())
        // days nul si daily=false: nu trimite "days" (nu schimba zilele)
        assertFalse(casaEventUpdateBody(time = "10:00").has("days"))
        // daily bate days
        assertTrue(casaEventUpdateBody(days = listOf(1), daily = true).isNull("days"))
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
    fun `register 401 is a wrong password not a revoked token`() {
        assertEquals(CasaResult.Error("PAROLA GRESITA"), casaFailure(401, """{"error":"parola gresita"}""", authenticated = false))
        assertEquals(CasaResult.Error("PREA MULTE INCERCARI"), casaFailure(429, "", authenticated = false))
    }

    @Test
    fun `json nulls never become the text null`() {
        val p = parsePlug(
            """{"id":"bf3","name":null,"online":true,"on":false,"metered":false,"events":[],"next_event":null,
            "countdown":null,"inching":{"enabled":false,"minutes":30},
            "off_deadline":{"at":1,"seconds_left":2,"reason":null},"error":null}"""
        )
        assertEquals("bf3", p.name)
        assertEquals("", p.offDeadline!!.reason)
        assertNull(p.error)
        val s = parseSensors("""{"cloud":null,"sensors":[{"id":"s9","name":null,"temperature":null,"spark":[]}]}""")
        assertFalse(s.cloudOk)
        assertEquals("s9", s.sensors[0].name)
        assertEquals("", parseSensorHistory("""{"id":"s9","name":null,"range":null,"temperature":[],"humidity":[]}""").range)
        assertEquals(CasaResult.Error("CERERE INVALIDA"), casaFailure(400, """{"error":null}"""))
    }

    @Test
    fun `http answers map to messages`() {
        assertEquals(CasaResult.Unauthorized, casaFailure(401, """{"error":"token invalid"}"""))
        assertEquals(CasaResult.Error("PREA MULTE INCERCARI"), casaFailure(429, """{"error":"prea multe incercari","retry_after":30}"""))
        assertEquals(CasaResult.Error("PRIZA NU RASPUNDE"), casaFailure(502, """{"error":"priza nu raspunde","plug":{}}"""))
        assertEquals(CasaResult.Error("X"), casaFailure(404, """{"error":"x"}"""))
        assertEquals(CasaResult.Error("CEL MULT 12 EVENIMENTE PE PRIZA"), casaFailure(400, """{"error":"cel mult 12 evenimente pe priza"}"""))
        assertEquals(CasaResult.Error("DURATA INVALIDA"), casaFailure(400, """{"error":"durată invalidă"}"""))
        assertEquals(CasaResult.Error("CERERE INVALIDA"), casaFailure(400, "nu e json"))
        assertEquals(CasaResult.Error("EROARE SERVER"), casaFailure(500, ""))
    }

    @Test
    fun `conflict message from the server is shown as it is`() {
        assertEquals(
            CasaResult.Error("CONFLICT: EXISTA DEJA UN EVENIMENT ACTIV LA 17:00 IN ACELEASI ZILE"),
            casaFailure(400, """{"error":"conflict: exista deja un eveniment activ la 17:00 in aceleasi zile"}""")
        )
    }

    @Test
    fun `server error text is used for 400 404 and 503`() {
        assertEquals(CasaResult.Error("PRIZA NECUNOSCUTA"), casaFailure(404, """{"error":"priza necunoscută"}"""))
        assertEquals(CasaResult.Error("SENZOR NECUNOSCUT"), casaFailure(404, """{"error":"senzor necunoscut"}"""))
        assertEquals(CasaResult.Error("PRIZA E OCUPATA, REINCEARCA"), casaFailure(503, """{"error":"priza e ocupată, reîncearcă"}"""))
        assertEquals(CasaResult.Error("PAROLA NESETATA"), casaFailure(503, """{"error":"parola nesetata"}"""))
        assertEquals(CasaResult.Error("ISTORIC INDISPONIBIL"), casaFailure(503, """{"error":"istoric indisponibil"}"""))
        assertEquals(CasaResult.Error("CEL MULT 12 EVENIMENTE PE PRIZA"), casaFailure(400, """{"error":"cel mult 12 evenimente pe priza"}"""))
    }

    @Test
    fun `502 with plug key is plug not answering, without json is server unavailable`() {
        assertEquals(CasaResult.Error("PRIZA NU RASPUNDE"), casaFailure(502, """{"error":"priza nu raspunde","plug":{"id":"p1"}}"""))
        assertEquals(CasaResult.Error("SERVER INDISPONIBIL"), casaFailure(502, ""))
        assertEquals(CasaResult.Error("SERVER INDISPONIBIL"), casaFailure(502, "<html>Bad Gateway</html>"))
        assertEquals(CasaResult.Error("SERVER INDISPONIBIL"), casaFailure(502, """{"error":"altceva"}"""))
    }

    @Test
    fun `503 and 504 without json body are server unavailable`() {
        assertEquals(CasaResult.Error("SERVER INDISPONIBIL"), casaFailure(503, ""))
        assertEquals(CasaResult.Error("SERVER INDISPONIBIL"), casaFailure(503, "Service Unavailable"))
        assertEquals(CasaResult.Error("SERVER INDISPONIBIL"), casaFailure(504, ""))
        assertEquals(CasaResult.Error("SERVER INDISPONIBIL"), casaFailure(504, """{"error":"timeout"}"""))
    }

    @Test
    fun `404 and 400 without json fall back to generic labels`() {
        assertEquals(CasaResult.Error("NEGASIT"), casaFailure(404, "<html>404</html>"))
        assertEquals(CasaResult.Error("CERERE INVALIDA"), casaFailure(400, ""))
    }

    @Test
    fun `badges in display order`() {
        val now = epoch(2026, 11, 5, 9, 0) // joi
        val base = parsePlug(plugJson) // next_event: PORNESTE 10:30 azi
        // next event first, then countdown "on" + off_deadline reason schedule; inching off
        assertEquals(
            listOf("PORNESTE 10:30", "PORNIRE IN 5H 12", "STINGE IN 1H 00"),
            plugBadges(base, now, zone)
        )
        val full = base.copy(
            nextEvent = CasaNextEvent("e2", "16:00", "off", epoch(2026, 11, 9, 16, 0)),
            countdown = CasaCountdown("off", 1, 6000),
            inchingEnabled = true,
            inchingMinutes = 30,
            offDeadline = CasaOffDeadline(2, 1380, "inching")
        )
        assertEquals(
            listOf("OPRESTE L 16:00", "OPRIRE IN 1H 40", "AUTO 30 MIN", "STINGE IN 23 MIN"),
            plugBadges(full, now, zone)
        )
        // off_deadline caused by the countdown is already shown
        val cd = full.copy(offDeadline = CasaOffDeadline(2, 6000, "countdown"))
        assertEquals(
            listOf("OPRESTE L 16:00", "OPRIRE IN 1H 40", "AUTO 30 MIN"),
            plugBadges(cd, now, zone)
        )
        assertTrue(plugBadges(parsePlug(plainJson), now, zone).contains("AUTO 15 MIN"))
        // fara urmator eveniment nu apare nicio insigna de program
        assertEquals(listOf("AUTO 15 MIN"), plugBadges(parsePlug(plainJson), now, zone))
    }

    // ---- ordinea prizelor ----

    private fun plugId(id: String) = parsePlug("""{"id":"$id","name":"$id"}""")

    @Test
    fun `casaMove moves a row down`() {
        assertEquals(listOf("b", "c", "a", "d"), casaMove(listOf("a", "b", "c", "d"), 0, 2))
        assertEquals(listOf("a", "b", "d", "c"), casaMove(listOf("a", "b", "c", "d"), 2, 3))
    }

    @Test
    fun `casaMove moves a row up`() {
        assertEquals(listOf("a", "d", "b", "c"), casaMove(listOf("a", "b", "c", "d"), 3, 1))
        assertEquals(listOf("b", "a", "c", "d"), casaMove(listOf("a", "b", "c", "d"), 1, 0))
    }

    @Test
    fun `casaMove with the same index returns an equal list`() {
        val ids = listOf("a", "b", "c")
        assertEquals(ids, casaMove(ids, 1, 1))
        assertEquals(emptyList<String>(), casaMove(emptyList(), 0, 3))
        assertEquals(listOf("a"), casaMove(listOf("a"), 0, 5))
    }

    @Test
    fun `casaMove clamps out of range indices and leaves the input alone`() {
        val ids = listOf("a", "b", "c")
        assertEquals(listOf("b", "c", "a"), casaMove(ids, 0, 99))
        assertEquals(listOf("c", "a", "b"), casaMove(ids, 2, -7))
        assertEquals(listOf("b", "c", "a"), casaMove(ids, -4, 2))   // from -4 = primul
        assertEquals(listOf("c", "a", "b"), casaMove(ids, 50, 0))   // from 50 = ultimul
        assertEquals(listOf("a", "b", "c"), ids)
    }

    @Test
    fun `casaApplyOrder follows the given order and appends the rest`() {
        val plugs = listOf("a", "b", "c").map(::plugId)
        assertEquals(listOf("a", "b", "c"), casaApplyOrder(plugs, null).map { it.id })
        assertEquals(listOf("c", "a", "b"), casaApplyOrder(plugs, listOf("c", "a")).map { it.id })
        // necunoscute si duplicate se ignora; o priza noua (lipsa din ordine) vine la sfarsit
        assertEquals(
            listOf("b", "a", "c"),
            casaApplyOrder(plugs, listOf("zzz", "b", "b", "a")).map { it.id }
        )
        assertEquals(emptyList<CasaPlug>(), casaApplyOrder(emptyList(), listOf("a")))
    }

    @Test
    fun `casaOrderBody lists the ids in order`() {
        val body = casaOrderBody(listOf("b", "a"))
        assertEquals(1, body.length())
        val a = body.getJSONArray("order")
        assertEquals(listOf("b", "a"), (0 until a.length()).map { a.getString(it) })
    }

    @Test
    fun `casaDropIndex rounds the dragged distance to whole rows and stays in the list`() {
        assertEquals(1, casaDropIndex(from = 1, offsetPx = 20f, pitchPx = 55f, count = 5))   // sub jumatate
        assertEquals(2, casaDropIndex(from = 1, offsetPx = 40f, pitchPx = 55f, count = 5))   // peste jumatate
        assertEquals(0, casaDropIndex(from = 1, offsetPx = -40f, pitchPx = 55f, count = 5))
        assertEquals(4, casaDropIndex(from = 1, offsetPx = 5000f, pitchPx = 55f, count = 5))
        assertEquals(0, casaDropIndex(from = 3, offsetPx = -5000f, pitchPx = 55f, count = 5))
        assertEquals(2, casaDropIndex(from = 2, offsetPx = 900f, pitchPx = 0f, count = 5))   // fara geometrie: pe loc
        assertEquals(0, casaDropIndex(from = 0, offsetPx = 100f, pitchPx = 55f, count = 1))
    }

    @Test
    fun `casaRowShift shifts only the rows between the picked row and its target`() {
        // randul 1 tras pana pe pozitia 3: 2 si 3 urca cu un loc
        assertEquals(listOf(0, 0, -1, -1, 0), (0..4).map { casaRowShift(it, from = 1, to = 3) })
        // randul 3 tras pana pe pozitia 1: 1 si 2 coboara cu un loc
        assertEquals(listOf(0, 1, 1, 0, 0), (0..4).map { casaRowShift(it, from = 3, to = 1) })
        assertEquals(listOf(0, 0, 0), (0..2).map { casaRowShift(it, from = 1, to = 1) })
    }
}
