package com.rolling7.solar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CasaFreshnessTest {
    @Test
    fun pollWithoutCommandsIsFresh() {
        val f = CasaFreshness()
        val t = f.beginPoll()
        assertTrue(f.isFresh(t))
    }

    @Test
    fun pollStartedBeforeCommandFinishedIsStale() {
        // Living sus, 2026-11-05: GET /plugs pornit inainte de raspunsul comenzii "oprit" a adus "pornit".
        val f = CasaFreshness()
        val t = f.beginPoll()
        f.commandStarted()
        f.commandFinished()
        assertFalse(f.isFresh(t))
    }

    @Test
    fun pollStartedDuringCommandIsStaleEvenIfItReturnsBeforeTheCommand() {
        val f = CasaFreshness()
        f.commandStarted()
        val t = f.beginPoll()
        assertFalse(f.isFresh(t))   // comanda inca in zbor: serverul are starea veche
        f.commandFinished()
        assertFalse(f.isFresh(t))
    }

    @Test
    fun pollStartedAfterCommandIsFresh() {
        val f = CasaFreshness()
        f.commandStarted()
        f.commandFinished()
        val t = f.beginPoll()
        assertTrue(f.isFresh(t))
    }

    @Test
    fun overlappingCommandsBlockUntilBothFinish() {
        val f = CasaFreshness()
        f.commandStarted()
        f.commandStarted()
        f.commandFinished()
        val t = f.beginPoll()
        assertFalse(f.isFresh(t))
        f.commandFinished()
        assertTrue(f.isFresh(f.beginPoll()))
    }
}
