package io.nekohasekai.sagernet

import android.annotation.SuppressLint
import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.PowerManager
import android.os.StrictMode
import android.os.UserManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import go.Seq
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.isOss
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.utils.*
import kotlinx.coroutines.DEBUG_PROPERTY_NAME
import kotlinx.coroutines.DEBUG_PROPERTY_VALUE_ON
import libcore.Libcore
import moe.matsuri.nb4a.NativeInterface
import moe.matsuri.nb4a.net.LocalResolverImpl
import moe.matsuri.nb4a.utils.JavaUtil
import moe.matsuri.nb4a.utils.cleanWebview
import java.io.File
import androidx.work.Configuration as WorkConfiguration

class SagerNet :
    Application(),
    WorkConfiguration.Provider {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        application = this
    }

    private val nativeInterface = NativeInterface()

    val externalAssets: File by lazy { getExternalFilesDir(null) ?: filesDir }
    val process: String = JavaUtil.getProcessName()
    private val isMainProcess = process == BuildConfig.APPLICATION_ID
    val isBgProcess = process.endsWith(":bg")

    override fun onCreate() {
        super.onCreate()

        Thread.setDefaultUncaughtExceptionHandler(CrashHandler)

        if (isMainProcess || isBgProcess) {
            externalAssets.mkdirs()
            Seq.setContext(this)
            // Prime the cached configurationStore off the main thread before the first
            // synchronous read below (logBufSize/logLevel). PublicDatabase no longer allows
            // main-thread queries, so the bulk-SELECT prime must run on PrefSnapshotExecutor;
            // join here so cold-start reads are served from the snapshot. One-time, pre-UI.
            // Capture any prime failure off the daemon thread (Thread.join does not rethrow) so
            // it is logged here rather than silently deferred to the first read.
            val primeError = arrayOfNulls<Throwable>(1)
            Thread {
                try {
                    DataStore.configurationStore.prime()
                } catch (t: Throwable) {
                    primeError[0] = t
                }
            }.apply {
                isDaemon = true
                start()
                join()
            }
            primeError[0]?.let { Logs.w("configurationStore prime failed", it) }
            Libcore.initCore(
                process,
                cacheDir.absolutePath + "/",
                filesDir.absolutePath + "/",
                externalAssets.absolutePath + "/",
                DataStore.logBufSize,
                DataStore.logLevel > 0,
                nativeInterface, nativeInterface, LocalResolverImpl,
            )

            // fix multi process issue in Android 9+
            JavaUtil.handleWebviewDir(this)

            runOnDefaultDispatcher {
                PackageCache.register()
                cleanWebview()
            }
        }

        if (isMainProcess) {
            if (DataStore.uiDesignVersion < 1) {
                DataStore.dynamicColors = false
                DataStore.uiDesignVersion = 1
            }
            Theme.apply(this)
            Theme.applyNightTheme()
            AppLocale.apply()
            runOnDefaultDispatcher {
                DefaultNetworkListener.start(this) {
                    underlyingNetwork = it
                }

                updateNotificationChannels()
            }
        }

        if (BuildConfig.DEBUG) {
            System.setProperty(DEBUG_PROPERTY_NAME, DEBUG_PROPERTY_VALUE_ON)
            // Plan 027 Stage 1: surface main-thread disk I/O (incl. synchronous SagerDatabase
            // access) so remaining main-thread DAO sites can be found and moved off-thread.
            // penaltyLog only (never penaltyDeath) - this is observation, not enforcement.
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build(),
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateNotificationChannels()
    }

    override val workManagerConfiguration: WorkConfiguration
        get() = WorkConfiguration.Builder()
            .setDefaultProcessName("${BuildConfig.APPLICATION_ID}:bg")
            .build()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        Libcore.forceGc()
    }

    @SuppressLint("InlinedApi")
    companion object {

        lateinit var application: SagerNet

        val isTv by lazy {
            uiMode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        }

        val configureIntent: (Context) -> PendingIntent by lazy {
            {
                PendingIntent.getActivity(
                    it,
                    0,
                    Intent(
                        application,
                        MainActivity::class.java,
                    ).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0,
                )
            }
        }
        val activity by lazy { application.getSystemService<ActivityManager>()!! }
        val clipboard by lazy { application.getSystemService<ClipboardManager>()!! }
        val connectivity by lazy { application.getSystemService<ConnectivityManager>()!! }
        val notification by lazy { application.getSystemService<NotificationManager>()!! }
        val user by lazy { application.getSystemService<UserManager>()!! }
        val uiMode by lazy { application.getSystemService<UiModeManager>()!! }
        val power by lazy { application.getSystemService<PowerManager>()!! }

        fun getClipboardText(): String {
            return clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.text?.toString() ?: ""
        }

        fun trySetPrimaryClip(clip: String) = try {
            clipboard.setPrimaryClip(ClipData.newPlainText(null, clip))
            true
        } catch (e: RuntimeException) {
            Logs.w(e)
            false
        }

        fun updateNotificationChannels() {
            if (Build.VERSION.SDK_INT >= 26) {
                @RequiresApi(26)
                {
                    notification.createNotificationChannels(
                        listOf(
                            NotificationChannel(
                                "service-vpn",
                                application.getText(R.string.service_vpn),
                                if (Build.VERSION.SDK_INT >= 28) {
                                    NotificationManager.IMPORTANCE_MIN
                                } else {
                                    NotificationManager.IMPORTANCE_LOW
                                },
                            ), // #1355
                            NotificationChannel(
                                "service-proxy",
                                application.getText(R.string.service_proxy),
                                NotificationManager.IMPORTANCE_LOW,
                            ),
                            NotificationChannel(
                                "service-subscription",
                                application.getText(R.string.service_subscription),
                                NotificationManager.IMPORTANCE_DEFAULT,
                            ),
                            NotificationChannel(
                                "connection-test",
                                application.getText(R.string.connection_test),
                                NotificationManager.IMPORTANCE_DEFAULT,
                            ),
                        ),
                    )
                }
            }
        }

        // Default to carrying the current in-process selection so :bg starts the profile the UI
        // last selected, even if the async write-through DB commit hasn't landed yet. Callers with
        // a specific id (e.g. a shortcut switching profile) pass it explicitly. A non-resolving id
        // (incl. 0L "none") is ignored by :bg, which then falls back to its refreshed snapshot/DB.
        fun startService(profileId: Long = DataStore.selectedProxy) = ContextCompat.startForegroundService(
            application,
            Intent(application, SagerConnection.serviceClass).apply {
                if (profileId >= 0L) putExtra(Action.EXTRA_PROFILE_ID, profileId)
            },
        )

        fun reloadService(profileId: Long = -1L) = application.sendBroadcast(
            Intent(Action.RELOAD).setPackage(application.packageName).apply {
                if (profileId >= 0L) putExtra(Action.EXTRA_PROFILE_ID, profileId)
            },
        )

        fun stopService() = application.sendBroadcast(Intent(Action.CLOSE).setPackage(application.packageName))

        var underlyingNetwork: Network? = null

        fun isPrivateDnsActiveOnUnderlyingNetwork(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            val network = underlyingNetwork ?: connectivity.activeNetwork ?: return false
            val linkProperties: LinkProperties = connectivity.getLinkProperties(network) ?: return false
            return linkProperties.isPrivateDnsActive
        }

        var appVersionNameForDisplay = {
            var n = BuildConfig.VERSION_NAME
            if (isPreview) {
                n += " " + BuildConfig.PRE_VERSION_NAME
            } else if (!isOss) {
                n += " ${BuildConfig.FLAVOR}"
            }
            if (BuildConfig.DEBUG) {
                n += " DEBUG"
            }
            n
        }()
    }
}
