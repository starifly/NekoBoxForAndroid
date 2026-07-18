package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceTeardownExecutionTest {

    @Test
    fun callerWaitsForTeardownToComplete() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val releaseTeardown = CompletableDeferred<Unit>()
        val caller = launch {
            runServiceTeardown(dispatcher) {
                releaseTeardown.await()
            }
        }

        runCurrent()
        assertFalse(caller.isCompleted)

        releaseTeardown.complete(Unit)
        advanceUntilIdle()
        assertTrue(caller.isCompleted)
    }

    @Test
    fun parentCancellationAfterEntryDoesNotSkipTeardown() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val teardownEntered = CompletableDeferred<Unit>()
        val releaseTeardown = CompletableDeferred<Unit>()
        var teardownCompleted = false
        val caller = launch {
            runServiceTeardown(dispatcher) {
                teardownEntered.complete(Unit)
                releaseTeardown.await()
                teardownCompleted = true
            }
        }

        runCurrent()
        teardownEntered.await()
        caller.cancel()
        releaseTeardown.complete(Unit)
        advanceUntilIdle()

        assertTrue(teardownCompleted)
        assertTrue(caller.isCancelled)
    }

    @Test
    fun parentCancellationDoesNotSkipRequiredCompletion() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val teardownEntered = CompletableDeferred<Unit>()
        val releaseTeardown = CompletableDeferred<Unit>()
        var completionRan = false
        val caller = launch {
            runServiceTeardown(
                dispatcher = dispatcher,
                after = { completionRan = true },
            ) {
                teardownEntered.complete(Unit)
                releaseTeardown.await()
            }
        }

        runCurrent()
        teardownEntered.await()
        caller.cancel()
        releaseTeardown.complete(Unit)
        advanceUntilIdle()

        assertTrue(completionRan)
        assertTrue(caller.isCancelled)
    }

    @Test
    fun teardownExceptionPropagatesAfterRequiredCompletion() = runBlocking {
        val failure = IllegalStateException("teardown failure")
        var completionRan = false

        val result = runCatching {
            runServiceTeardown(
                dispatcher = Dispatchers.Unconfined,
                after = { completionRan = true },
            ) {
                throw failure
            }
        }

        assertTrue(completionRan)
        var propagated = result.exceptionOrNull()
        while (propagated != null && propagated !== failure) {
            propagated = propagated.cause
        }
        assertSame(failure, propagated)
    }

    @Test
    fun completionExceptionIsSuppressedByTeardownFailure() = runBlocking {
        val teardownFailure = IllegalStateException("teardown failure")
        val completionFailure = IllegalArgumentException("completion failure")
        val result = runCatching {
            runRequiredCompletion(
                after = { throw completionFailure },
            ) {
                throw teardownFailure
            }
        }

        val failure = result.exceptionOrNull()
        assertSame(teardownFailure, failure)
        assertSame(completionFailure, failure?.suppressed?.single())
    }

    @Test
    fun repeatedFailureInstancePreservesOriginalFailure() = runBlocking {
        val failure = IllegalStateException("shared failure")
        val result = runCatching {
            runRequiredCompletion(
                after = { throw failure },
            ) {
                throw failure
            }
        }

        val thrown = result.exceptionOrNull()
        assertSame(failure, thrown)
        assertTrue(thrown?.suppressed?.isEmpty() == true)
    }

    @Test
    fun teardownUsesSuppliedDispatcher() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var observedDispatcher: ContinuationInterceptor? = null

        runServiceTeardown(dispatcher) {
            observedDispatcher = currentCoroutineContext()[ContinuationInterceptor]
        }

        assertSame(dispatcher, observedDispatcher)
    }
}
