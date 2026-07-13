package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal suspend fun runServiceTeardown(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    after: suspend () -> Unit = {},
    block: suspend () -> Unit,
) = withContext(NonCancellable) {
    runRequiredCompletion(after) {
        withContext(dispatcher) {
            block()
        }
    }
}

internal suspend fun runRequiredCompletion(after: suspend () -> Unit, block: suspend () -> Unit) {
    var failure: Throwable? = null
    try {
        block()
    } catch (throwable: Throwable) {
        failure = throwable
    }
    try {
        after()
    } catch (throwable: Throwable) {
        val primaryFailure = failure
        if (primaryFailure == null) throw throwable
        if (primaryFailure !== throwable) primaryFailure.addSuppressed(throwable)
    }
    val finalFailure = failure
    if (finalFailure != null) throw finalFailure
}
