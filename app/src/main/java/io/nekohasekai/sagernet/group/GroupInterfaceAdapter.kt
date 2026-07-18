package io.nekohasekai.sagernet.group

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.ThemedActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class GroupInterfaceAdapter(val context: ThemedActivity) : GroupManager.Interface {

    override suspend fun confirm(message: String): Boolean {
        return suspendCoroutine { cont ->
            runOnMainDispatcher {
                if (context.isFinishing || context.isDestroyed) {
                    cont.resume(false)
                    return@runOnMainDispatcher
                }
                try {
                    MaterialAlertDialogBuilder(context).setTitle(R.string.confirm)
                        .setMessage(message)
                        .setPositiveButton(R.string.yes) { _, _ -> cont.resume(true) }
                        .setNegativeButton(R.string.no) { _, _ -> cont.resume(false) }
                        .setOnCancelListener { _ -> cont.resume(false) }
                        .show()
                } catch (e: Exception) {
                    Logs.w(e)
                    cont.resume(false)
                }
            }
        }
    }

    override suspend fun onUpdateSuccess(
        group: ProxyGroup,
        changed: Int,
        added: List<String>,
        updated: Map<String, String>,
        deleted: List<String>,
        duplicate: List<String>,
        byUser: Boolean,
    ) {
        if (changed == 0 && duplicate.isEmpty()) {
            if (byUser) {
                onMainDispatcher {
                    if (context.isFinishing || context.isDestroyed) return@onMainDispatcher
                    try {
                        context.snackbar(
                            context.getString(
                                R.string.group_no_difference,
                                group.displayName(),
                            ),
                        ).show()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }
            }
        } else {
            var status = ""
            if (added.isNotEmpty()) {
                status += context.getString(
                    R.string.group_added,
                    added.joinToString("\n", postfix = "\n\n"),
                )
            }
            if (updated.isNotEmpty()) {
                status += context.getString(
                    R.string.group_changed,
                    updated.map { it }.joinToString("\n", postfix = "\n\n") {
                        if (it.key == it.value) it.key else "${it.key} => ${it.value}"
                    },
                )
            }
            if (deleted.isNotEmpty()) {
                status += context.getString(
                    R.string.group_deleted,
                    deleted.joinToString("\n", postfix = "\n\n"),
                )
            }
            if (duplicate.isNotEmpty()) {
                status += context.getString(
                    R.string.group_duplicate,
                    duplicate.joinToString("\n", postfix = "\n\n"),
                )
            }

            onMainDispatcher {
                if (context.isFinishing || context.isDestroyed) return@onMainDispatcher
                try {
                    context.snackbar(
                        context.getString(R.string.group_updated, group.name, changed),
                    ).show()
                    delay(1000L)

                    MaterialAlertDialogBuilder(context).setTitle(
                        context.getString(
                            R.string.group_diff,
                            group.displayName(),
                        ),
                    ).setMessage(status.trim()).setPositiveButton(android.R.string.ok, null).show()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }
        }
    }

    override suspend fun onUpdateFailure(group: ProxyGroup, message: String) {
        onMainDispatcher {
            if (context.isFinishing || context.isDestroyed) return@onMainDispatcher
            try {
                context.snackbar(message).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }

    override suspend fun alert(message: String) {
        return suspendCoroutine { cont ->
            runOnMainDispatcher {
                if (context.isFinishing || context.isDestroyed) {
                    cont.resume(Unit)
                    return@runOnMainDispatcher
                }
                try {
                    MaterialAlertDialogBuilder(context).setTitle(R.string.ooc_warning)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok) { _, _ -> cont.resume(Unit) }
                        .setOnCancelListener { _ -> cont.resume(Unit) }
                        .show()
                } catch (e: Exception) {
                    Logs.w(e)
                    cont.resume(Unit)
                }
            }
        }
    }
}
