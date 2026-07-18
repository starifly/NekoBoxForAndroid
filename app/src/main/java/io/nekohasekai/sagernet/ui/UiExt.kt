package io.nekohasekai.sagernet.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.delay

// UI-layer extension helpers. These live in the `ui` package (not `ktx`) because they
// reference MainActivity/ThemedActivity; keeping them here makes `ktx` a leaf w.r.t. `ui`
// (breaks the ktx -> ui dependency cycle). Behavior is identical to the former ktx versions.

fun Fragment.snackbar(textId: Int) = (requireActivity() as MainActivity).snackbar(textId)
fun Fragment.snackbar(text: CharSequence) = (requireActivity() as MainActivity).snackbar(text)

fun ThemedActivity.startFilesForResult(launcher: ActivityResultLauncher<String>, input: String) {
    try {
        return launcher.launch(input)
    } catch (_: ActivityNotFoundException) {
    } catch (_: SecurityException) {
    }
    snackbar(getString(R.string.file_manager_missing)).show()
}

fun Fragment.startFilesForResult(launcher: ActivityResultLauncher<String>, input: String) {
    try {
        return launcher.launch(input)
    } catch (_: ActivityNotFoundException) {
    } catch (_: SecurityException) {
    }
    (requireActivity() as ThemedActivity).snackbar(getString(R.string.file_manager_missing)).show()
}

fun Fragment.needReload() {
    if (DataStore.serviceState.started) {
        snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
            // Drain the cached settings store's async write-through commits before telling :bg to
            // reload, so the service re-reads the just-changed setting from a durable DB rather
            // than a stale row.
            runOnDefaultDispatcher {
                try {
                    DataStore.configurationStore.awaitWrites()
                    SagerNet.reloadService()
                } catch (e: Exception) {
                    Logs.w(e)
                    // The coroutine can outlive the fragment; only touch fragment APIs while attached.
                    onMainDispatcher {
                        if (isAdded) snackbar(getString(R.string.service_failed)).show()
                    }
                }
            }
        }.show()
    }
}

fun Fragment.needRestart() {
    snackbar(R.string.need_restart).setAction(R.string.apply) {
        triggerFullRestart(requireContext())
    }.show()
}

fun triggerFullRestart(ctx: Context) {
    runOnDefaultDispatcher {
        // Drain async preference write-through before tearing down + rebirthing, so same-gesture
        // restart-required settings (e.g. logLevel/logBufSize, read by SagerNet.onCreate in both
        // processes) are durable before the process restarts. Best-effort: a drain failure must
        // not block the user-requested restart.
        try {
            DataStore.configurationStore.awaitWrites()
        } catch (e: Exception) {
            Logs.w(e)
        }
        SagerNet.stopService()
        delay(500)
        SagerConnection.restartingApp = true
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_RESTART_BG)
        connection.connect(
            ctx,
            RestartCallback {
                ProcessPhoenix.triggerRebirth(ctx, Intent(ctx, MainActivity::class.java))
            },
        )
    }
}

private class RestartCallback(val callback: () -> Unit) : SagerConnection.Callback {
    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
    }

    override fun onServiceConnected(service: ISagerNetService) {
        callback()
    }
}
