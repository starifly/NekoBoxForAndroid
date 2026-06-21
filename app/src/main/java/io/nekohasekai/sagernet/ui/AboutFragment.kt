package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.danielstone.materialaboutlibrary.MaterialAboutFragment
import com.danielstone.materialaboutlibrary.items.MaterialAboutActionItem
import com.danielstone.materialaboutlibrary.model.MaterialAboutCard
import com.danielstone.materialaboutlibrary.model.MaterialAboutList
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager.loadString
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.ListListener
import libcore.Libcore
import moe.matsuri.nb4a.plugin.Plugins
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject

class AboutFragment : ToolbarFragment(R.layout.layout_about) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_about)

        parentFragmentManager.beginTransaction()
            .replace(R.id.about_fragment_holder, AboutContent())
            .commitAllowingStateLoss()
    }

    class AboutContent : MaterialAboutFragment() {

        val requestIgnoreBatteryOptimizations = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            // The battery-optimization request/settings screen returns RESULT_CANCELED even
            // when the user actually granted the exemption, so don't gate on the result code
            // — just rebuild the list so the item's on/off subtext reflects the new state.
            // Guard with isAdded: the fragment may be detached while the settings screen was
            // open, and refreshMaterialAboutList() would otherwise touch a null adapter.
            if (isAdded) refreshMaterialAboutList()
        }

        override fun getMaterialAboutList(activityContext: Context): MaterialAboutList {
            return MaterialAboutList.Builder()
                .addCard(
                    MaterialAboutCard.Builder()
                        .outline(true)
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_update_24)
                                .text(R.string.app_version)
                                .subText(SagerNet.appVersionNameForDisplay)
                                .setOnClickAction {
                                    requireContext().launchCustomTab(
                                        "https://github.com/hawkff/NekoBoxForAndroid/releases"
                                    )
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .text(R.string.check_update_release)
                                .setOnClickAction {
                                    checkUpdate(false)
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .text(R.string.check_update_preview)
                                .setOnClickAction {
                                    checkUpdate(true)
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_layers_24)
                                .text(activityContext.getString(R.string.version_x, "sing-box"))
                                .subText(Libcore.versionBox())
                                .setOnClickAction { }
                                .build())
                        .apply {
                            PackageCache.awaitLoadSync()
                            for ((_, pkg) in PackageCache.installedPluginPackages) {
                                try {
                                    val pluginId =
                                        pkg.providers?.get(0)?.loadString(Plugins.METADATA_KEY_ID)
                                    if (pluginId.isNullOrBlank()) continue
                                    addItem(
                                        MaterialAboutActionItem.Builder()
                                            .icon(R.drawable.ic_baseline_nfc_24)
                                            .text(
                                                activityContext.getString(
                                                    R.string.version_x,
                                                    pluginId
                                                ) + " (${Plugins.displayExeProvider(pkg.packageName)})"
                                            )
                                            .subText("v" + pkg.versionName)
                                            .setOnClickAction {
                                                startActivity(Intent().apply {
                                                    action =
                                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                                    data = Uri.fromParts(
                                                        "package", pkg.packageName, null
                                                    )
                                                })
                                            }
                                            .build())
                                } catch (e: Exception) {
                                    Logs.w(e)
                                }
                            }
                        }
                        .apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                                val ignoring = pm.isIgnoringBatteryOptimizations(app.packageName)
                                addItem(
                                    MaterialAboutActionItem.Builder()
                                        .icon(R.drawable.ic_baseline_running_with_errors_24)
                                        .text(R.string.ignore_battery_optimizations)
                                        .subText(
                                            if (ignoring) R.string.battery_optimization_enabled
                                            else R.string.battery_optimization_disabled
                                        )
                                        .setOnClickAction {
                                            // The ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                            // dialog only appears while the app is still
                                            // optimized; once exempt it is a no-op. So when
                                            // already exempt, send the user to the battery
                                            // settings screen where they can toggle it back off.
                                            val intent = if (ignoring) {
                                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                            } else {
                                                Intent(
                                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                    "package:${app.packageName}".toUri()
                                                )
                                            }
                                            requestIgnoreBatteryOptimizations.launch(intent)
                                        }
                                        .build())
                            }
                        }
                        .build())
                .build()

        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            view.findViewById<RecyclerView>(R.id.mal_recyclerview).apply {
                overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            }
        }

        fun checkUpdate(checkPreview: Boolean) {
            runOnIoDispatcher {
                try {
                    val client = Libcore.newHttpClient().apply {
                        modernTLS()
                        trySocks5(
                            DataStore.mixedPort,
                            DataStore.mixedInboundUser,
                            DataStore.mixedInboundPass
                        )
                    }
                    val response = client.newRequest().apply {
                        if (checkPreview) {
                            setURL("https://api.github.com/repos/hawkff/NekoBoxForAndroid/releases/tags/preview")
                        } else {
                            setURL("https://api.github.com/repos/hawkff/NekoBoxForAndroid/releases/latest")
                        }
                    }.execute()
                    val release = JSONObject(Util.getStringBox(response.contentString))
                    val releaseName = release.getString("name")
                    val releaseUrl = release.getString("html_url")
                    var haveUpdate = releaseName.isNotBlank()
                    haveUpdate = if (isPreview) {
                        if (checkPreview) {
                            haveUpdate && releaseName != BuildConfig.PRE_VERSION_NAME
                        } else {
                            // User: 1.3.9 pre-1.4.0 Stable: 1.3.9 -> No update
                            haveUpdate && releaseName != BuildConfig.VERSION_NAME
                        }
                    } else {
                        // User: 1.4.0 Preview: pre-1.4.0 -> No update
                        // User: 1.4.0 Preview: pre-1.4.1 -> Update
                        // User: 1.4.0 Stable: 1.4.0 -> No update
                        // User: 1.4.0 Stable: 1.4.1 -> Update
                        haveUpdate && !releaseName.contains(BuildConfig.VERSION_NAME)
                    }
                    runOnMainDispatcher {
                        // The async work above may outlive the fragment's attachment
                        // (e.g. user navigates away). Touching requireContext()/app
                        // resources while detached throws IllegalStateException
                        // (issue #1192). Bail out if no longer attached.
                        if (!isAdded) return@runOnMainDispatcher
                        if (haveUpdate) {
                            val context = requireContext()
                            MaterialAlertDialogBuilder(context)
                                .setTitle(R.string.update_dialog_title)
                                .setMessage(
                                    context.getString(
                                        R.string.update_dialog_message,
                                        SagerNet.appVersionNameForDisplay,
                                        releaseName
                                    )
                                )
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    val intent = Intent(Intent.ACTION_VIEW, releaseUrl.toUri())
                                    context.startActivity(intent)
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        } else {
                            Toast.makeText(app, R.string.check_update_no, Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    runOnMainDispatcher {
                        if (!isAdded) return@runOnMainDispatcher
                        Toast.makeText(app, e.readableMessage, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    }

}
