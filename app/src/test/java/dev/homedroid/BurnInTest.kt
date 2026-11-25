package dev.homedroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BurnInTest {
    private val minute = 60_000L

    @Test
    fun standbyStartsAfterTheIdleTime() {
        assertFalse(BurnIn.shouldStandby(true, 0, 2 * minute - 1, 2))
        assertTrue(BurnIn.shouldStandby(true, 0, 2 * minute, 2))
        assertTrue(BurnIn.shouldStandby(true, 0, 30 * minute, 2))
    }

    @Test
    fun standbyNeverStartsWhenTurnedOff() {
        assertFalse(BurnIn.shouldStandby(false, 0, 99 * minute, 2))
    }

    @Test
    fun aTouchRestartsTheCountdown() {
        assertFalse(BurnIn.shouldStandby(true, 5 * minute, 6 * minute, 2))
        assertEquals(minute, BurnIn.msUntilStandby(5 * minute, 6 * minute, 2))
        assertEquals(0, BurnIn.msUntilStandby(0, 10 * minute, 2))
    }

    @Test
    fun zeroMinutesIsTreatedAsOne() {
        assertFalse(BurnIn.shouldStandby(true, 0, minute - 1, 0))
        assertTrue(BurnIn.shouldStandby(true, 0, minute, 0))
    }

    @Test
    fun stepsAdvanceOncePerMinute() {
        assertEquals(0, BurnIn.step(1000, 1000 + minute - 1))
        assertEquals(1, BurnIn.step(1000, 1000 + minute))
        assertEquals(0, BurnIn.step(5000, 1000)) // clock went back
    }

    @Test
    fun positionsAlwaysFitTheArea() {
        for (step in 0L until 500L) {
            val (x, y) = BurnIn.position(step, 1080, 2340, 600, 200)
            assertTrue(x in 0..480)
            assertTrue(y in 0..2140)
        }
    }

    @Test
    fun consecutivePositionsDiffer() {
        for (step in 0L until 200L) {
            assertNotEquals(BurnIn.position(step, 1080, 2340, 600, 200), BurnIn.position(step + 1, 1080, 2340, 600, 200))
        }
    }

    @Test
    fun positionsSpreadAcrossTheScreen() {
        val spots = (0L until 20L).map { BurnIn.position(it, 1080, 2340, 600, 200) }
        assertTrue(spots.minOf { it.second } < 2140 / 5)
        assertTrue(spots.maxOf { it.second } >= 2140 * 4 / 5)
        assertTrue(spots.minOf { it.first } < 480 / 4)
        assertTrue(spots.maxOf { it.first } >= 480 * 3 / 4)
    }

    @Test
    fun contentLargerThanTheAreaSitsAtTheCorner() {
        assertEquals(0 to 0, BurnIn.position(7, 300, 300, 600, 600))
    }
}
