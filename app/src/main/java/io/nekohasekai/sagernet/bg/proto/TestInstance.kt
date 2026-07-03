package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.Commandline
import kotlinx.coroutines.suspendCancellableCoroutine
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TestInstance(profile: ProxyEntity, val link: String, private val timeout: Int) :
    BoxInstance(profile) {

    // close() can be reached from two paths that may overlap on cancellation: the
    // suspendCancellableCoroutine's invokeOnCancellation and the `use { }` block's
    // exit. BoxInstance.close() is not safe to run twice (native box.close()), so
    // guard it to run exactly once.
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.getAndSet(true)) return
        super.close()
    }

    suspend fun doTest(): Int = suspendCancellableCoroutine { c ->
        processes = GuardedProcessPool {
            Logs.w(it)
            if (c.isActive) c.resumeWithException(it)
        }
        // Close the box/sidecars if the caller cancels while the test is in flight
        // (e.g. the user pressed Stop). This prevents leaking up to
        // connectionTestConcurrent full sing-box instances + plugin sidecars that
        // otherwise run to completion in the background.
        c.invokeOnCancellation {
            try {
                close()
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
        runOnDefaultDispatcher {
            use {
                try {
                    init()
                    launch()
                    if (processes.processCount > 0) {
                        // Wait until the external plugin sidecar(s) have actually bound
                        // their loopback SOCKS port before testing, instead of a fixed
                        // 500ms guess that often raced the sidecar (flaky "connection
                        // refused"). strict = true turns a never-bound listener into a
                        // clear error rather than a misleading connection failure.
                        awaitExternalProcessesReady(strict = true)
                    }
                    val result = Libcore.urlTest(box, link, timeout)
                    if (c.isActive) c.resume(result)
                } catch (e: Exception) {
                    if (c.isActive) c.resumeWithException(e)
                }
            }
        }
    }

    override fun buildConfig() {
        config = buildConfig(profile, true)
    }

    override suspend fun loadConfig() {
        // don't call destroyAllJsi here
        if (BuildConfig.DEBUG) Logs.d(Commandline.redactProcessOutput(config.config))
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }
}
