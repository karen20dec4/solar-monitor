package com.rolling7.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElasticZoomTest {
    @Test
    fun noPinchMeansNoZoom() {
        assertEquals(1f, elasticScale(1f), 1e-6f)
    }

    @Test
    fun followsFingersClosely_thenSettlesBelowTheLimit() {
        // Aproape de 1x urmareste degetele aproape 1:1...
        assertEquals(1.1f, elasticScale(1.1f), 0.01f)
        // ...apoi se aseaza si nu depaseste niciodata limita, oricat ai desface degetele.
        assertTrue(elasticScale(2f) < 2f)
        assertTrue(elasticScale(5f) < ElasticZoomMax)
        assertTrue(elasticScale(1000f) <= ElasticZoomMax)
    }

    @Test
    fun growsMonotonically() {
        var last = 0f
        for (i in 3..50) {
            val s = elasticScale(i / 10f)
            assertTrue("scara trebuie sa creasca la raw=${i / 10f}", s > last)
            last = s
        }
    }

    @Test
    fun pinchingInOnlyGivesALittle() {
        // Strangerea degetelor cedeaza putin si nu face ecranul sa dispara.
        assertEquals(0.925f, elasticScale(0.7f), 1e-4f)
        assertTrue(elasticScale(0.3f) > 0.8f)
    }

    @Test
    fun rawForScaleInvertsElasticScale() {
        for (raw in listOf(0.4f, 0.8f, 1f, 1.3f, 2f, 3.5f)) {
            assertEquals(raw, elasticRawFor(elasticScale(raw)), 1e-3f)
        }
    }
}
