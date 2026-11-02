package com.rolling7.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GridOutageTest {
    private val ended = GridOutage(start = 100, end = 135, seconds = 36)
    private val ongoing = GridOutage(start = 200, end = null, seconds = 20)

    @Test
    fun `parses the outages from the api`() {
        val outages = parseGridOutages(
            """{"down":true,"outages":[{"start":100,"end":135,"seconds":36},{"start":200,"end":null,"seconds":20}],"timestamp":"x"}"""
        )
        assertEquals(listOf(ended, ongoing), outages)
    }

    @Test
    fun `a reply without outages is rejected`() {
        assertNull(parseGridOutages("""{"error":"influx jos"}"""))
    }

    @Test
    fun `the first run stays quiet about the past but announces an outage in progress`() {
        val (past, pastMark) = evaluateGridOutages(listOf(ended), null)
        assertTrue(past.isEmpty())
        assertEquals(GridOutageMark(seen = 100, open = 0), pastMark)

        val (now, nowMark) = evaluateGridOutages(listOf(ended, ongoing), null)
        assertEquals(listOf(GridNotice(ongoing, restored = false)), now)
        assertEquals(GridOutageMark(seen = 200, open = 200), nowMark)
    }

    @Test
    fun `an outage over before the phone saw it gets a single message`() {
        val (notices, mark) = evaluateGridOutages(listOf(ended), GridOutageMark(seen = 0, open = 0))
        assertEquals(listOf(GridNotice(ended, restored = true)), notices)
        assertEquals(GridOutageMark(seen = 100, open = 0), mark)
    }

    @Test
    fun `an outage is announced once and closed once`() {
        val start = GridOutageMark(seen = 100, open = 0)
        val (first, afterFirst) = evaluateGridOutages(listOf(ended, ongoing), start)
        assertEquals(1, first.size)
        assertFalse(first[0].restored)

        val (repeat, afterRepeat) = evaluateGridOutages(listOf(ended, ongoing.copy(seconds = 50)), afterFirst)
        assertTrue(repeat.isEmpty())

        val closed = ongoing.copy(end = 260, seconds = 61)
        val (last, afterLast) = evaluateGridOutages(listOf(ended, closed), afterRepeat)
        assertEquals(listOf(GridNotice(closed, restored = true)), last)
        assertEquals(GridOutageMark(seen = 200, open = 0), afterLast)

        assertTrue(evaluateGridOutages(listOf(ended, closed), afterLast).first.isEmpty())
    }

    @Test
    fun `several outages missed while offline each get a message`() {
        val second = GridOutage(start = 300, end = 340, seconds = 41)
        val (notices, mark) = evaluateGridOutages(listOf(second, ended), GridOutageMark(seen = 50, open = 0))
        assertEquals(listOf(ended, second), notices.map { it.outage })
        assertEquals(300L, mark.seen)
    }

    @Test
    fun `an outage longer than the server window is not announced again`() {
        val mark = GridOutageMark(seen = 200, open = 200)
        val (notices, next) = evaluateGridOutages(listOf(ongoing.copy(start = 900)), mark)
        assertTrue(notices.isEmpty())
        assertEquals(0L, next.open)
    }

    @Test
    fun `durations read naturally`() {
        assertEquals("35 s", gridDurationText(35))
        assertEquals("4 min 12 s", gridDurationText(252))
        assertEquals("2 min", gridDurationText(120))
        assertEquals("1 h 40 min", gridDurationText(6_000))
    }

    @Test
    fun `messages name the times and the duration`() {
        val clock = { epoch: Long -> if (epoch == 100L) "12:42" else "12:43" }
        assertEquals("Curent Enel revenit", gridNoticeTitle(GridNotice(ended, true)))
        assertEquals("A picat la 12:42, a revenit la 12:43 (36 s).", gridNoticeBody(GridNotice(ended, true), clock))
        assertEquals("Curent Enel picat", gridNoticeTitle(GridNotice(ended.copy(end = null), false)))
        assertEquals(
            "Reteaua Enel lipseste de la 12:42. Casa merge din panouri si baterie.",
            gridNoticeBody(GridNotice(ended.copy(end = null), false), clock)
        )
    }
}
