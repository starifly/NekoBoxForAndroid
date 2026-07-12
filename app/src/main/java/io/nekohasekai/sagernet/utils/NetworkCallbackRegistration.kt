package io.nekohasekai.sagernet.utils

internal class NetworkCallbackRegistration {
    internal var isRegistered = false
        private set
    internal var requiresFallback = false
        private set

    fun register(block: () -> Unit): Result<Unit> {
        if (isRegistered) return Result.success(Unit)
        return runCatching(block).onSuccess {
            isRegistered = true
            requiresFallback = false
        }
    }

    fun unregister(block: () -> Unit): Result<Unit> {
        if (!isRegistered) return Result.success(Unit)
        return runCatching(block)
            .onSuccess {
                isRegistered = false
                requiresFallback = false
            }
            .onFailure { throwable ->
                if (throwable is IllegalArgumentException) {
                    isRegistered = false
                    requiresFallback = false
                } else {
                    requiresFallback = true
                }
            }
    }
}
