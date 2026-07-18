package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkCallbackRegistrationTest {

    @Test
    fun successfulRegisterThenUnregisterInvokesEachLambdaOnce() {
        val registration = NetworkCallbackRegistration()
        var registerCalls = 0
        var unregisterCalls = 0

        val registerResult = registration.register { registerCalls++ }
        val unregisterResult = registration.unregister { unregisterCalls++ }

        assertTrue(registerResult.isSuccess)
        assertTrue(unregisterResult.isSuccess)
        assertEquals(1, registerCalls)
        assertEquals(1, unregisterCalls)
        assertFalse(registration.isRegistered)
    }

    @Test
    fun failedRegisterLeavesStateFalseAndSkipsUnregisterLambda() {
        val registration = NetworkCallbackRegistration()
        val failure = IllegalStateException("registration failed")
        var unregisterCalls = 0

        val registerResult = registration.register { throw failure }
        val unregisterResult = registration.unregister { unregisterCalls++ }

        assertSame(failure, registerResult.exceptionOrNull())
        assertTrue(unregisterResult.isSuccess)
        assertEquals(0, unregisterCalls)
        assertFalse(registration.isRegistered)
    }

    @Test
    fun failedUnregisterKeepsStateForRetry() {
        val registration = NetworkCallbackRegistration()
        val failure = IllegalStateException("unregistration failed")
        var successfulUnregisterCalls = 0
        registration.register {}

        val failedResult = registration.unregister { throw failure }
        val retryResult = registration.unregister { successfulUnregisterCalls++ }

        assertSame(failure, failedResult.exceptionOrNull())
        assertTrue(retryResult.isSuccess)
        assertEquals(1, successfulUnregisterCalls)
        assertFalse(registration.isRegistered)
        assertFalse(registration.requiresFallback)
    }

    @Test
    fun alreadyUnregisteredFailureAllowsFreshRegistration() {
        val registration = NetworkCallbackRegistration()
        var registerCalls = 0
        registration.register {}

        val unregisterResult = registration.unregister {
            throw IllegalArgumentException("callback was not registered")
        }
        val registerResult = registration.register { registerCalls++ }

        assertTrue(unregisterResult.isFailure)
        assertTrue(registerResult.isSuccess)
        assertEquals(1, registerCalls)
        assertTrue(registration.isRegistered)
        assertFalse(registration.requiresFallback)
    }

    @Test
    fun registerAfterEitherFailureDoesNotDuplicateARegisteredCallback() {
        val registration = NetworkCallbackRegistration()
        var successfulRegisterCalls = 0

        registration.register { throw IllegalStateException("registration failed") }
        val afterRegisterFailure = registration.register { successfulRegisterCalls++ }
        registration.unregister { throw IllegalStateException("unregistration failed") }
        val afterUnregisterFailure = registration.register { successfulRegisterCalls++ }

        assertTrue(afterRegisterFailure.isSuccess)
        assertTrue(afterUnregisterFailure.isSuccess)
        assertEquals(1, successfulRegisterCalls)
        assertTrue(registration.isRegistered)
        assertTrue(registration.requiresFallback)
    }

    @Test
    fun repeatedUnregisterWhileFalseIsHarmless() {
        val registration = NetworkCallbackRegistration()
        var unregisterCalls = 0

        val firstResult = registration.unregister { unregisterCalls++ }
        val secondResult = registration.unregister { unregisterCalls++ }

        assertTrue(firstResult.isSuccess)
        assertTrue(secondResult.isSuccess)
        assertEquals(0, unregisterCalls)
        assertFalse(registration.isRegistered)
    }
}
