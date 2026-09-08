package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.core.app.ActivityCompat
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.AppLocale
import io.nekohasekai.sagernet.utils.Theme
import moe.matsuri.nb4a.ui.*
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.textfield.TextInputEditText
import java.io.File

class SettingsPreferenceFragment : PreferenceFragmentCompat() {

    private lateinit var isProxyApps: SwitchPreference

    private lateinit var globalCustomConfig: EditConfigPreference


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView.layoutManager = FixedLinearLayoutManager(listView)
    }

    private val reloadListener = Preference.OnPreferenceChangeListener { _, _ ->
        needReload()
        true
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        DataStore.initGlobal()
        addPreferencesFromResource(R.xml.global_preferences)

        val appTheme = findPreference<ColorPickerPreference>(Key.APP_THEME)!!
        appTheme.setOnPreferenceChangeListener { _, newTheme ->
            if (DataStore.serviceState.started) {
                SagerNet.reloadService()
            }
            val theme = Theme.getTheme(newTheme as Int)
            app.setTheme(theme)
            requireActivity().apply {
                setTheme(theme)
                ActivityCompat.recreate(this)
            }
            true
        }

        val nightTheme = findPreference<SimpleMenuPreference>(Key.NIGHT_THEME)!!
        nightTheme.setOnPreferenceChangeListener { _, newTheme ->
            Theme.currentNightMode = (newTheme as String).toInt()
            Theme.applyNightTheme()
            true
        }
        val appLanguage = findPreference<SimpleMenuPreference>(Key.APP_LANGUAGE)!!
        appLanguage.setOnPreferenceChangeListener { _, newValue ->
            AppLocale.apply(newValue as String)
            true
        }
        val localProxySettings = findPreference<Preference>("localProxySettings")!!
        val serviceMode = findPreference<Preference>(Key.SERVICE_MODE)!!
        val dnsHosts = findPreference<EditTextPreference>(Key.DNS_HOSTS)!!
        val strictRoute = findPreference<SwitchPreference>(Key.STRICT_ROUTE)!!

        val showDirectSpeed = findPreference<SwitchPreference>(Key.SHOW_DIRECT_SPEED)!!
        val ipv6Mode = findPreference<Preference>(Key.IPV6_MODE)!!
        val trafficSniffing = findPreference<Preference>(Key.TRAFFIC_SNIFFING)!!

        val bypassLan = findPreference<SwitchPreference>(Key.BYPASS_LAN)!!
        val bypassLanInCore = findPreference<SwitchPreference>(Key.BYPASS_LAN_IN_CORE)!!

        val remoteDns = findPreference<EditTextPreference>(Key.REMOTE_DNS)!!
        val directDns = findPreference<EditTextPreference>(Key.DIRECT_DNS)!!
        val enableDnsRouting = findPreference<SwitchPreference>(Key.ENABLE_DNS_ROUTING)!!
        val enableFakeDns = findPreference<SwitchPreference>(Key.ENABLE_FAKEDNS)!!

        val logLevel = findPreference<LongClickListPreference>(Key.LOG_LEVEL)!!
        val mtu = findPreference<MTUPreference>(Key.MTU)!!
        globalCustomConfig = findPreference(Key.GLOBAL_CUSTOM_CONFIG)!!
        globalCustomConfig.useConfigStore(Key.GLOBAL_CUSTOM_CONFIG)

        logLevel.dialogLayoutResource = R.layout.layout_loglevel_help
        logLevel.setOnPreferenceChangeListener { _, _ ->
            needRestart()
            true
        }
        logLevel.setOnLongClickListener {
            if (context == null) return@setOnLongClickListener true

            val view = EditText(context).apply {
                inputType = EditorInfo.TYPE_CLASS_NUMBER
                var size = DataStore.logBufSize
                if (size == 0) size = 50
                setText(size.toString())
            }

            MaterialAlertDialogBuilder(requireContext()).setTitle("Log buffer size (kb)")
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    DataStore.logBufSize = view.text.toString().toInt()
                    if (DataStore.logBufSize <= 0) DataStore.logBufSize = 50
                    needRestart()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        dnsHosts.setOnBindEditTextListener(EditTextPreferenceModifiers.Hosts)
        dnsHosts.summaryProvider = ListSummaryProvider(maxLines = 1)

        isProxyApps = findPreference(Key.PROXY_APPS)!!
        isProxyApps.setOnPreferenceChangeListener { _, newValue ->
            startActivity(Intent(activity, AppManagerActivity::class.java))
            if (newValue as Boolean) DataStore.dirty = true
            newValue
        }

        val profileTrafficStatistics =
            findPreference<SwitchPreference>(Key.PROFILE_TRAFFIC_STATISTICS)!!
        val speedInterval = findPreference<SimpleMenuPreference>(Key.SPEED_INTERVAL)!!
        profileTrafficStatistics.isEnabled = speedInterval.value.toString() != "0"
        speedInterval.setOnPreferenceChangeListener { _, newValue ->
            profileTrafficStatistics.isEnabled = newValue.toString() != "0"
            needReload()
            true
        }

        serviceMode.setOnPreferenceChangeListener { _, _ ->
            if (DataStore.serviceState.started) SagerNet.stopService()
            true
        }

        val tunImplementation = findPreference<SimpleMenuPreference>(Key.TUN_IMPLEMENTATION)!!
        tunImplementation.onPreferenceChangeListener = reloadListener
        val resolveDestination = findPreference<SwitchPreference>(Key.RESOLVE_DESTINATION)!!
        val acquireWakeLock = findPreference<SwitchPreference>(Key.ACQUIRE_WAKE_LOCK)!!
        val hideFromRecentApps = findPreference<SwitchPreference>(Key.HIDE_FROM_RECENT_APPS)!!
        val enableClashAPI = findPreference<SwitchPreference>(Key.ENABLE_CLASH_API)!!
        enableClashAPI.setOnPreferenceChangeListener { _, newValue ->
            (activity as MainActivity?)?.refreshNavMenu(newValue as Boolean)
            needReload()
            true
        }

        localProxySettings.summary = localProxySummary()
        localProxySettings.setOnPreferenceClickListener {
            showLocalProxySettingsDialog(localProxySettings)
            true
        }
        dnsHosts.onPreferenceChangeListener = reloadListener
        strictRoute.onPreferenceChangeListener = reloadListener
        showDirectSpeed.onPreferenceChangeListener = reloadListener
        trafficSniffing.onPreferenceChangeListener = reloadListener
        bypassLan.onPreferenceChangeListener = reloadListener
        bypassLanInCore.onPreferenceChangeListener = reloadListener
        mtu.onPreferenceChangeListener = reloadListener

        val concurrentDial = findPreference<SwitchPreference>(Key.CONCURRENT_DIAL)!!
        concurrentDial.onPreferenceChangeListener = reloadListener

        enableFakeDns.onPreferenceChangeListener = reloadListener
        remoteDns.onPreferenceChangeListener = reloadListener
        directDns.onPreferenceChangeListener = reloadListener
        enableDnsRouting.onPreferenceChangeListener = reloadListener

        ipv6Mode.onPreferenceChangeListener = reloadListener

        resolveDestination.onPreferenceChangeListener = reloadListener
        tunImplementation.onPreferenceChangeListener = reloadListener
        acquireWakeLock.onPreferenceChangeListener = reloadListener
        hideFromRecentApps.setOnPreferenceChangeListener { _, newValue ->
            (activity as? MainActivity)?.applyHideFromRecentApps(newValue as Boolean)
            true
        }

        findPreference<SwitchPreference>(Key.SHOW_PROFILE_IN_NOTIFICATION)?.onPreferenceChangeListener = reloadListener
        findPreference<SwitchPreference>(Key.GLOBAL_ALLOW_INSECURE)?.onPreferenceChangeListener = reloadListener

        // 恢复默认设置功能
        val resetSettings = findPreference<Preference>("resetSettings")!!
        resetSettings.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext()).apply {
                setTitle(R.string.confirm)
                setMessage(R.string.reset_settings_message)
                setNegativeButton(R.string.no, null)
                setPositiveButton(R.string.yes) { _, _ ->
                    DataStore.configurationStore.reset()
                    triggerFullRestart(requireContext())
                }
            }.show()
            true
        }

        // 清理缓存功能
        val clearCache = findPreference<Preference>(Key.CLEAR_CACHE)!!
        clearCache.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext()).apply {
                setTitle(R.string.clear_cache)
                setMessage(R.string.clear_cache_confirm)
                setPositiveButton(android.R.string.ok) { _, _ ->
                    clearAppCache()
                }
                setNegativeButton(android.R.string.cancel, null)
            }.show()
            true
        }
    }

    override fun onResume() {
        super.onResume()

        if (::isProxyApps.isInitialized) {
            isProxyApps.isChecked = DataStore.proxyApps
        }
        if (::globalCustomConfig.isInitialized) {
            globalCustomConfig.notifyChanged()
        }
    }

    private fun localProxySummary(): String {
        val host = if (DataStore.allowAccess) "0.0.0.0" else "127.0.0.1"
        return "$host:${DataStore.mixedPort}"
    }

    private fun showLocalProxySettingsDialog(preference: Preference) {
        val view = layoutInflater.inflate(R.layout.layout_local_proxy_dialog, null)
        val portField = view.findViewById<TextInputEditText>(R.id.proxyPort)
        val allowAccessSwitch = view.findViewById<SwitchCompat>(R.id.allowAccessSwitch)
        val usernameField = view.findViewById<TextInputEditText>(R.id.proxyUsername)
        val passwordField = view.findViewById<TextInputEditText>(R.id.proxyPassword)

        portField.setText(DataStore.mixedPort.toString())
        allowAccessSwitch.isChecked = DataStore.allowAccess
        usernameField.setText(DataStore.mixedUsername)
        passwordField.setText(DataStore.mixedSecret)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.local_proxy_settings)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val port = portField.text.toString().toIntOrNull()
                if (port == null || port !in 1..65535) {
                    Toast.makeText(
                        requireContext(), R.string.port_out_of_range, Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                DataStore.mixedPort = port
                DataStore.allowAccess = allowAccessSwitch.isChecked
                DataStore.mixedUsername = usernameField.text.toString().trim()
                DataStore.configurationStore.putString(
                    Key.MIXED_SECRET, passwordField.text.toString()
                )
                preference.summary = localProxySummary()
                needReload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearAppCache() {
        try {
            val cacheDir = SagerNet.application.cacheDir
            clearDirFiles(cacheDir, skipFiles = setOf("neko.log"))
            
            val parentDir = cacheDir.parentFile
            val relativeCache = File(parentDir, "cache")
            if (relativeCache.exists() && relativeCache.isDirectory) {
                clearDirFiles(relativeCache)
            }
            
            Toast.makeText(requireContext(), R.string.clear_cache_success, Toast.LENGTH_SHORT).show()
            
            Handler(Looper.getMainLooper()).postDelayed({
                needReload()
            }, 500)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.clear_cache_failed, e.message), Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun clearDirFiles(dir: File, skipFiles: Set<String> = emptySet()): Boolean {
        if (dir.isDirectory) {
            val children = dir.list() ?: return true
            
            for (child in children) {
                val childFile = File(dir, child)
                
                if (child == "neko.log") {
                    try {
                        childFile.writeText("")
                        continue
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                if (child in skipFiles) {
                    continue
                }
                
                if (childFile.isDirectory) {
                    clearDirFiles(childFile, skipFiles)
                } else {
                    childFile.delete()
                }
            }
            
            return true
        }
        return false
    }

    class ListSummaryProvider(
        private val maxLines: Int,
    ) : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val lines = preference.text.orEmpty()
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()
            if (lines.isEmpty()) {
                return preference.context.getString(androidx.preference.R.string.not_set)
            }
            return if (lines.size > maxLines) {
                lines.take(maxLines).joinToString("\n", postfix = "\n...")
            } else {
                lines.joinToString("\n")
            }
        }

    }

}
