package com.rolling7.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GridMonthTest {
    @Test
    fun `parses the month and the kwh from the api`() {
        val bill = parseGridMonth("""{"month":"2026-10","since":"2026-09-30T21:00:00+00:00","kwh":9.335772612691406}""")
        assertEquals("2026-10", bill!!.month)
        assertEquals(9.335772612691406, bill.kwh!!, 1e-12)
        assertEquals("Oct", bill.monthShort)
    }

    @Test
    fun `a month without samples keeps a null kwh`() {
        assertNull(parseGridMonth("""{"month":"2026-11","since":"x","kwh":null}""")!!.kwh)
        assertNull(parseGridMonth("""{"month":"2026-11"}""")!!.kwh)
    }

    @Test
    fun `a reply that is not a month is rejected`() {
        assertNull(parseGridMonth("""{"error":"influx jos"}"""))
        assertNull(parseGridMonth("""{"month":"2026-13","kwh":1.0}"""))
        assertNull(parseGridMonth("""{"month":"octombrie","kwh":1.0}"""))
    }

    @Test
    fun `month names are the romanian short forms`() {
        val names = (1..12).map { billMonthShort("2026-%02d".format(it)) }
        assertEquals(listOf("Ian", "Feb", "Mar", "Apr", "Mai", "Iun", "Iul", "Aug", "Sep", "Oct", "Nov", "Dec"), names)
        assertEquals("—", billMonthShort(""))
    }

    @Test
    fun `kwh shows one decimal below 100, whole kwh above, and a dash when unknown`() {
        assertEquals("9.3", billKwhText(9.335))
        assertEquals("0.0", billKwhText(0.0))
        assertEquals("99.9", billKwhText(99.94))
        assertEquals("100", billKwhText(99.96))
        assertEquals("123", billKwhText(123.44))
        assertEquals("—", billKwhText(null))
    }
}
