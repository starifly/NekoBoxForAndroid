package io.nekohasekai.sagernet.bg

import android.os.Build
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.MainThread
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.utils.Commandline
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.selects.select
import libcore.Libcore
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.thread

private data class ProcessGenerationExit(
    val exitCode: Int,
    val readyAtMillis: Long? = null,
)

private data class RestartReadinessResult(
    val readyAtMillis: Long? = null,
    val error: IOException? = null,
)

class GuardedProcessPool(private val onFatal: suspend (IOException) -> Unit) : CoroutineScope {
    companion object {
        private val pid by lazy {
            Class.forName("java.lang.ProcessManager\$ProcessImpl").getDeclaredField("pid")
                .apply { isAccessible = true }
        }
    }

    private inner class Guard(
        private val cmd: List<String>,
        private val env: Map<String, String> = mapOf(),
    ) {
        private lateinit var process: Process

        private fun streamLogger(input: InputStream, logger: (String) -> Unit) = try {
            input.bufferedReader().forEachLine(logger)
        } catch (_: IOException) {
        } // ignore

        fun start() {
            process = ProcessBuilder(cmd).directory(SagerNet.application.noBackupFilesDir).apply {
                environment().putAll(env)
            }.start()
        }

        private fun watchProcess(cmdName: String, exitChannel: Channel<Int>) {
            val proc = process
            thread(name = "stderr-$cmdName") {
                streamLogger(proc.errorStream) {
                    Libcore.nekoLogPrintln("[$cmdName] ${Commandline.redactProcessOutput(it)}")
                }
            }
            thread(name = "stdout-$cmdName") {
                streamLogger(proc.inputStream) {
                    Libcore.nekoLogPrintln("[$cmdName] ${Commandline.redactProcessOutput(it)}")
                }
            }
            // The channel is generation-local and buffered, so this waiter never blocks a
            // later generation and remains available to bounded NonCancellable teardown.
            thread(name = "waitFor-$cmdName") {
                val code = proc.waitFor()
                if (exitChannel.trySendBlocking(code).isFailure) {
                    Logs.w("$cmdName: could not deliver exit code $code (channel closed)")
                }
            }
        }

        private suspend fun observeRestart(
            cmdName: String,
            exitChannel: Channel<Int>,
            onRestartCallback: suspend () -> Unit,
        ): ProcessGenerationExit = coroutineScope {
            val readiness = async {
                try {
                    onRestartCallback()
                    RestartReadinessResult(readyAtMillis = SystemClock.elapsedRealtime())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    RestartReadinessResult(
                        error = if (e is IOException) e else IOException("restart readiness check failed", e),
                    )
                }
            }
            select {
                exitChannel.onReceive { exitCode ->
                    readiness.cancelAndJoin()
                    ProcessGenerationExit(exitCode)
                }
                readiness.onAwait { result ->
                    val readinessError = result.error
                    if (readinessError == null) {
                        ProcessGenerationExit(
                            exitCode = exitChannel.receive(),
                            readyAtMillis = result.readyAtMillis,
                        )
                    } else {
                        Logs.w("$cmdName restart readiness failed; restarting")
                        val exitCode = terminateProcess(exitChannel)
                            ?: throw IOException(
                                "$cmdName could not stop after restart readiness failure",
                                readinessError,
                            )
                        ProcessGenerationExit(exitCode)
                    }
                }
            }
        }

        private fun signalProcess(signal: Int) {
            try {
                Os.kill(pid.get(process) as Int, signal)
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.ESRCH) Logs.w(e)
            } catch (e: ReflectiveOperationException) {
                Logs.w(e)
            }
        }

        private suspend fun terminateProcess(exitChannel: Channel<Int>): Int? = withContext(NonCancellable) {
            exitChannel.tryReceive().getOrNull()?.let { return@withContext it }
            if (Build.VERSION.SDK_INT < 24) {
                signalProcess(OsConstants.SIGTERM)
                withTimeoutOrNull(500) { exitChannel.receive() }?.let { return@withContext it }
            }
            process.destroy()
            withTimeoutOrNull(1000) { exitChannel.receive() }?.let { return@withContext it }
            if (Build.VERSION.SDK_INT >= 26) {
                process.destroyForcibly()
            } else {
                signalProcess(OsConstants.SIGKILL)
            }
            withTimeoutOrNull(1000) { exitChannel.receive() }
        }

        @DelicateCoroutinesApi
        suspend fun looper(
            onRestartPrepare: (() -> Unit)?,
            onRestartCallback: (suspend () -> Unit)?,
            restartPolicy: GuardedProcessRestartPolicy?,
            restartOnExit: Boolean,
        ) {
            var running = true
            var restarted = false
            var currentExitChannel: Channel<Int>? = null
            val cmdName = File(cmd.first()).nameWithoutExtension
            val backoff = restartPolicy.createBackoff()
            try {
                while (true) {
                    val exitChannel = Channel<Int>(capacity = 1)
                    currentExitChannel = exitChannel
                    watchProcess(cmdName, exitChannel)
                    val startTime = SystemClock.elapsedRealtime()
                    val generation = if (restarted && onRestartCallback != null) {
                        observeRestart(cmdName, exitChannel, onRestartCallback)
                    } else {
                        ProcessGenerationExit(exitChannel.receive())
                    }
                    running = false
                    currentExitChannel = null
                    exitChannel.close()

                    val exitTime = SystemClock.elapsedRealtime()
                    val processUptimeMillis = exitTime - startTime
                    if (shouldFailAfterProcessExit(restartOnExit, restartPolicy, processUptimeMillis)) {
                        throw IOException("$cmdName exited (exit code: ${generation.exitCode})")
                    }
                    when (generation.exitCode) {
                        128 + OsConstants.SIGKILL -> Logs.w("$cmdName was killed")
                        else -> Logs.w(
                            IOException("$cmdName unexpectedly exits with code ${generation.exitCode}"),
                        )
                    }

                    val readyDurationMillis = generation.readyAtMillis?.let {
                        (exitTime - it).coerceAtLeast(0L)
                    }
                    val restartDelayMillis = backoff?.delayAfterExit(readyDurationMillis)
                    try {
                        onRestartPrepare?.invoke()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw if (e is IOException) {
                            e
                        } else {
                            IOException("$cmdName restart preparation failed", e)
                        }
                    }
                    if (restartDelayMillis != null) {
                        Logs.i(
                            "restart process after ${restartDelayMillis}ms: " +
                                Commandline.toRedactedString(cmd),
                        )
                        delay(restartDelayMillis)
                    } else {
                        Logs.i(
                            "restart process: ${Commandline.toRedactedString(cmd)} " +
                                "(last exit code: ${generation.exitCode})",
                        )
                    }
                    start()
                    running = true
                    restarted = true
                }
            } catch (e: IOException) {
                Logs.w("error occurred. stop guard: ${Commandline.toRedactedString(cmd)}")
                // Structured (cancelled with the pool) so a torn-down pool can't fire onFatal
                // and stop a freshly-restarted instance.
                this@GuardedProcessPool.launch(Dispatchers.Main.immediate) { onFatal(e) }
            } finally {
                val exitChannel = currentExitChannel
                if (running && exitChannel != null) {
                    terminateProcess(exitChannel)
                } else if (running) {
                    process.destroy()
                }
            }
        }
    }

    override val coroutineContext = Dispatchers.Main.immediate + Job()
    var processCount = 0

    @MainThread
    fun start(
        cmd: List<String>,
        env: MutableMap<String, String> = mutableMapOf(),
        onRestartPrepare: (() -> Unit)? = null,
        onRestartCallback: (suspend () -> Unit)? = null,
        restartPolicy: GuardedProcessRestartPolicy? = null,
        restartOnExit: Boolean = true,
    ) {
        Logs.i("start process: ${Commandline.toRedactedString(cmd)}")
        Guard(cmd, env).apply {
            start() // if start fails, IOException will be thrown directly
            launch { looper(onRestartPrepare, onRestartCallback, restartPolicy, restartOnExit) }
        }
        processCount += 1
    }

    @MainThread
    fun close(scope: CoroutineScope) {
        cancel()
        coroutineContext[Job]!!.also { job -> scope.launch { job.cancelAndJoin() } }
    }
}
