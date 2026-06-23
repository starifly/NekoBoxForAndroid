package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutAboutBinding
import io.nekohasekai.sagernet.databinding.LayoutAboutItemBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager.loadString
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.ListListener
import libcore.Libcore
import moe.matsuri.nb4a.plugin.Plugins
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject

/**
 * About screen. Previously backed by the abandoned material-about-library; now hand-rolled with
 * a simple RecyclerView so we no longer depend on an unmaintained library. The item list is
 * rebuilt on demand (e.g. after returning from the battery-optimization settings screen).
 */
class AboutFragment : ToolbarFragment(R.layout.layout_about) {

    private var _binding: LayoutAboutBinding? = null
    private val binding get() = _binding!!
    private val adapter = AboutAdapter()

    private val requestIgnoreBatteryOptimizations = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // The battery-optimization request/settings screen returns RESULT_CANCELED even
        // when the user actually granted the exemption, so don't gate on the result code
        // - just rebuild the list so the item's on/off subtext reflects the new state.
        if (isAdded) rebuildList()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        _binding = LayoutAboutBinding.bind(view)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_about)

        binding.aboutList.adapter = adapter
        rebuildList()
    }

    override fun onDestroyView() {
        // The fragment instance outlives its view; release the view-scoped binding and detach
        // the adapter so the destroyed RecyclerView/view tree isn't leaked.
        _binding?.aboutList?.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun rebuildList() {
        // PackageCache.awaitLoadSync() and the plugin enumeration can, in the worst case (cache
        // still loading), block; build the list off the main thread and post the result back so
        // the About screen never janks.
        runOnIoDispatcher {
            val pluginItems = mutableListOf<AboutItem>()
            PackageCache.awaitLoadSync()
            for ((_, pkg) in PackageCache.installedPluginPackages) {
                try {
                    val pluginId = pkg.providers?.get(0)?.loadString(Plugins.METADATA_KEY_ID)
                    if (pluginId.isNullOrBlank()) continue
                    pluginItems += AboutItem(
                        icon = R.drawable.ic_baseline_nfc_24,
                        text = getString(R.string.version_x, pluginId) +
                            " (${Plugins.displayExeProvider(pkg.packageName)})",
                        subText = "v" + pkg.versionName,
                        onClick = {
                            startActivity(Intent().apply {
                                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                data = Uri.fromParts("package", pkg.packageName, null)
                            })
                        },
                    )
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }
            runOnMainDispatcher {
                if (!isAdded) return@runOnMainDispatcher
                adapter.submitList(buildItems(pluginItems))
            }
        }
    }

    /** Assembles the full item list. [pluginItems] is computed off the main thread by the caller. */
    private fun buildItems(pluginItems: List<AboutItem>): List<AboutItem> {
        val items = mutableListOf<AboutItem>()

        items += AboutItem(
            icon = R.drawable.ic_baseline_update_24,
            text = getString(R.string.app_version),
            subText = SagerNet.appVersionNameForDisplay,
            onClick = {
                requireContext().launchCustomTab(
                    "https://github.com/hawkff/NekoBoxForAndroid/releases"
                )
            },
        )
        items += AboutItem(
            text = getString(R.string.check_update_release),
            onClick = { checkUpdate(false) },
        )
        items += AboutItem(
            text = getString(R.string.check_update_preview),
            onClick = { checkUpdate(true) },
        )
        items += AboutItem(
            icon = R.drawable.ic_baseline_layers_24,
            text = getString(R.string.version_x, "sing-box"),
            subText = Libcore.versionBox(),
        )

        items += pluginItems

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val ignoring = pm.isIgnoringBatteryOptimizations(app.packageName)
            items += AboutItem(
                icon = R.drawable.ic_baseline_running_with_errors_24,
                text = getString(R.string.ignore_battery_optimizations),
                subText = getString(
                    if (ignoring) R.string.battery_optimization_enabled
                    else R.string.battery_optimization_disabled
                ),
                onClick = {
                    // The ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog only appears while
                    // the app is still optimized; once exempt it is a no-op. So when already
                    // exempt, send the user to the battery settings screen where they can toggle
                    // it back off.
                    val intent = if (ignoring) {
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    } else {
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            "package:${app.packageName}".toUri()
                        )
                    }
                    requestIgnoreBatteryOptimizations.launch(intent)
                },
            )
        }

        return items
    }

    private fun checkUpdate(checkPreview: Boolean) {
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

    private data class AboutItem(
        @DrawableRes val icon: Int = 0,
        val text: CharSequence,
        val subText: CharSequence? = null,
        val onClick: (() -> Unit)? = null,
    )

    private class AboutAdapter : ListAdapter<AboutItem, AboutViewHolder>(DIFF) {

        companion object {
            // AboutItem carries an onClick lambda, so compare only the visible content.
            private val DIFF = object : DiffUtil.ItemCallback<AboutItem>() {
                override fun areItemsTheSame(oldItem: AboutItem, newItem: AboutItem) =
                    oldItem.text == newItem.text

                override fun areContentsTheSame(oldItem: AboutItem, newItem: AboutItem) =
                    oldItem.icon == newItem.icon &&
                        oldItem.text == newItem.text &&
                        oldItem.subText == newItem.subText
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AboutViewHolder {
            return AboutViewHolder(
                LayoutAboutItemBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
        }

        override fun onBindViewHolder(holder: AboutViewHolder, position: Int) {
            holder.bind(getItem(position))
        }
    }

    private class AboutViewHolder(
        private val binding: LayoutAboutItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AboutItem) {
            if (item.icon != 0) {
                binding.aboutItemIcon.setImageResource(item.icon)
                binding.aboutItemIcon.visibility = View.VISIBLE
            } else {
                binding.aboutItemIcon.visibility = View.INVISIBLE
            }
            binding.aboutItemText.text = item.text
            if (item.subText.isNullOrBlank()) {
                binding.aboutItemSubtext.visibility = View.GONE
            } else {
                binding.aboutItemSubtext.text = item.subText
                binding.aboutItemSubtext.visibility = View.VISIBLE
            }
            val click = item.onClick
            if (click != null) {
                binding.root.isClickable = true
                binding.root.setOnClickListener { click() }
            } else {
                binding.root.isClickable = false
                binding.root.setOnClickListener(null)
            }
        }
    }
}
