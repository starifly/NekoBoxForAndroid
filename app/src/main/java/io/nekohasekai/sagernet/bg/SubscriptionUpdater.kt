package io.nekohasekai.sagernet.bg

import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import java.util.concurrent.TimeUnit

internal data class SubscriptionWorkSchedule(
    val intervalMinutes: Long,
    val initialDelaySeconds: Long,
)

internal data class SubscriptionScheduleInput(
    val lastUpdated: Int,
    val autoUpdateDelay: Int,
)

internal fun computeSubscriptionWorkSchedule(
    subscriptions: List<SubscriptionScheduleInput>,
    nowSeconds: Long = System.currentTimeMillis() / 1000L,
): SubscriptionWorkSchedule? {
    if (subscriptions.isEmpty()) return null

    val intervalMinutes = subscriptions
        .minOf { it.autoUpdateDelay.toLong() }
        .coerceAtLeast(15L)
    val initialDelaySeconds = subscriptions.minOf { subscription ->
        val dueAt = subscription.lastUpdated.toLong() +
            subscription.autoUpdateDelay.toLong().coerceAtLeast(15L) * 60L
        dueAt - nowSeconds
    }.coerceAtLeast(0L)

    return SubscriptionWorkSchedule(intervalMinutes, initialDelaySeconds)
}

object SubscriptionUpdater {

    private const val WORK_NAME = "SubscriptionUpdater"

    suspend fun reconfigureUpdater() {
        RemoteWorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)

        val subscriptions = SagerDatabase.groupDao.subscriptions()
            .mapNotNull { group -> group.subscription?.let { group to it } }
            .filter { (_, sub) -> sub.autoUpdate }
        if (subscriptions.isEmpty()) return

        val schedule = computeSubscriptionWorkSchedule(
            subscriptions.map { (_, sub) ->
                SubscriptionScheduleInput(
                    lastUpdated = sub.lastUpdated ?: 0,
                    autoUpdateDelay = sub.autoUpdateDelay ?: 1440,
                )
            },
        ) ?: return

        // main process
        RemoteWorkManager.getInstance(app).enqueueUniquePeriodicWork(
            WORK_NAME,
            UPDATE,
            PeriodicWorkRequest.Builder(UpdateTask::class.java, schedule.intervalMinutes, TimeUnit.MINUTES)
                .apply {
                    if (schedule.initialDelaySeconds > 0) {
                        setInitialDelay(schedule.initialDelaySeconds, TimeUnit.SECONDS)
                    }
                }
                .build(),
        )
    }

    class UpdateTask(
        appContext: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(appContext, params) {

        private val nm = NotificationManagerCompat.from(applicationContext)

        private val notification = NotificationCompat.Builder(applicationContext, "service-subscription")
            .setWhen(0)
            .setTicker(applicationContext.getString(R.string.forward_success))
            .setContentTitle(applicationContext.getString(R.string.subscription_update))
            .setSmallIcon(R.drawable.ic_service_active)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        override suspend fun doWork(): Result {
            var subscriptions =
                SagerDatabase.groupDao.subscriptions()
                    .mapNotNull { group -> group.subscription?.let { group to it } }
                    .filter { (_, sub) -> sub.autoUpdate }
            if (!DataStore.serviceState.connected) {
                Logs.d("work: not connected")
                subscriptions = subscriptions.filter { (_, sub) -> !sub.updateWhenConnectedOnly }
            }

            var attempted = false
            var failed = false
            if (subscriptions.isNotEmpty()) {
                val nowSeconds = System.currentTimeMillis() / 1000L
                for ((profile, subscription) in subscriptions) {
                    val lastUpdated = (subscription.lastUpdated ?: 0).toLong()
                    val delaySeconds =
                        (subscription.autoUpdateDelay ?: 1440).toLong().coerceAtLeast(15L) * 60L
                    if (nowSeconds - lastUpdated < delaySeconds) {
                        Logs.d("work: not updating " + profile.displayName())
                        continue
                    }
                    Logs.d("work: updating " + profile.displayName())

                    notification.setContentText(
                        applicationContext.getString(
                            R.string.subscription_update_message,
                            profile.displayName(),
                        ),
                    )
                    notifyProgress()

                    attempted = true
                    if (!GroupUpdater.executeUpdate(profile, false)) {
                        failed = true
                    }
                }
            }

            try {
                nm.cancel(2)
            } catch (e: SecurityException) {
                Logs.w("subscription notification cancel skipped", e)
            }

            return if (attempted && failed) Result.retry() else Result.success()
        }

        private fun notifyProgress() {
            if (!nm.areNotificationsEnabled()) return
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(applicationContext, POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            try {
                nm.notify(2, notification.build())
            } catch (e: SecurityException) {
                Logs.w("subscription notification update skipped", e)
            }
        }
    }
}
