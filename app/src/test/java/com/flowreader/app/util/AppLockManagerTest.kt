package com.flowreader.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only the pure static half of [AppLockManager] is testable on the JVM — the biometric half needs a
 * device. That half is also the security-relevant one: PIN storage and verification.
 */
class AppLockManagerTest {

    @Test
    fun hashNeverContainsThePinAndIsSalted() {
        val hash = AppLockManager.hashPin("1234")
        assertFalse(hash.contains("1234"))
        assertTrue(hash.contains(":"))
        // Two hashes of the same PIN differ because the salt is random.
        assertNotEquals(hash, AppLockManager.hashPin("1234"))
    }

    @Test
    fun verifyAcceptsTheRightPinAndRejectsOthers() {
        val hash = AppLockManager.hashPin("4321")
        assertTrue(AppLockManager.verifyPin("4321", hash))
        assertFalse(AppLockManager.verifyPin("1234", hash))
        assertFalse(AppLockManager.verifyPin("4321", null))
        assertFalse(AppLockManager.verifyPin("4321", "garbage"))
    }

    @Test
    fun pinLengthAndDigitsAreValidated() {
        assertFalse(AppLockManager.isPinValid("123"))
        assertTrue(AppLockManager.isPinValid("1234"))
        assertTrue(AppLockManager.isPinValid("12345678"))
        assertFalse(AppLockManager.isPinValid("123456789"))
        assertFalse(AppLockManager.isPinValid("12a4"))
    }
}
