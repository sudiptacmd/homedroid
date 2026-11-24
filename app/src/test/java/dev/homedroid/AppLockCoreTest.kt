package dev.homedroid

import org.junit.Assert.*
import org.junit.Test

class AppLockCoreTest {
    @Test fun pinFormat() {
        assertTrue(AppLockCore.validPin("1234"))
        assertTrue(AppLockCore.validPin("12345678"))
        assertFalse(AppLockCore.validPin("123"))
        assertFalse(AppLockCore.validPin("123456789"))
        assertFalse(AppLockCore.validPin("12a4"))
    }

    @Test fun hashIsSaltedAndVerifies() {
        val a = AppLockCore.encode("2580", iterations = 1000)
        val b = AppLockCore.encode("2580", iterations = 1000)
        assertNotEquals(a, b)
        assertTrue(AppLockCore.verify("2580", a))
        assertFalse(AppLockCore.verify("2581", a))
        assertFalse(AppLockCore.verify("2580", "garbage"))
        assertEquals("1000", a.substringBefore(':'))
    }

    @Test fun backoffAfterFiveFailures() {
        repeat(5) { assertEquals(0L, AppLockCore.lockoutMs(it)) }
        assertEquals(30_000L, AppLockCore.lockoutMs(5))
        assertEquals(60_000L, AppLockCore.lockoutMs(6))
        assertEquals(3600_000L, AppLockCore.lockoutMs(40))
        assertEquals(30_000L, AppLockCore.waitMs(5, 1000, 1000))
        assertEquals(0L, AppLockCore.waitMs(5, 1000, 31_000))
        // A failure "in the future" (clock moved back) can't lock for longer than the lockout.
        assertEquals(30_000L, AppLockCore.waitMs(5, 10_000_000, 1000))
    }
}
