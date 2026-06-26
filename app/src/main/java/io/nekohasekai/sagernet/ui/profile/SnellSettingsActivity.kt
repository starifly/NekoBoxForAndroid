package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class SnellSettingsActivity : ProfileSettingsActivity<SnellBean>() {

    override fun createEntity() = SnellBean().applyDefaultValues()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val psk = pbm.add(PreferenceBinding(Type.Text, "psk"))
    private val version = pbm.add(PreferenceBinding(Type.TextToInt, "version"))
    private val network = pbm.add(PreferenceBinding(Type.Text, "network"))
    private val obfsMode = pbm.add(PreferenceBinding(Type.Text, "obfsMode"))
    private val obfsHost = pbm.add(PreferenceBinding(Type.Text, "obfsHost"))
    private val reuse = pbm.add(PreferenceBinding(Type.Bool, "reuse"))

    override fun SnellBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun SnellBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.snell_preferences)

        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }

        findPreference<EditTextPreference>("psk")!!.apply {
            summaryProvider = PasswordSummaryProvider
        }

        val versionPref = findPreference<SimpleMenuPreference>("version")!!
        val networkPref = findPreference<SimpleMenuPreference>("network")!!
        val reusePref = findPreference<Preference>("reuse")!!
        val obfsModePref = findPreference<SimpleMenuPreference>("obfsMode")!!

        val initialVersion = versionPref.value?.toIntOrNull() ?: 4
        updateNetworkOptions(initialVersion, networkPref)
        updateReuseEnabled(initialVersion, reusePref)
        updateObfsModeOptions(initialVersion, obfsModePref)

        versionPref.setOnPreferenceChangeListener { _, newValue ->
            val newVersion = (newValue as? String)?.toIntOrNull() ?: 4
            updateNetworkOptions(newVersion, networkPref)
            updateReuseEnabled(newVersion, reusePref)
            updateObfsModeOptions(newVersion, obfsModePref)
            true
        }
    }

    private fun updateNetworkOptions(version: Int, networkPref: SimpleMenuPreference) {
        if (version <= 2) {
            networkPref.entries = arrayOf("Auto", "TCP")
            networkPref.entryValues = arrayOf("", "tcp")
            if (networkPref.value == "udp") {
                networkPref.value = ""
            }
        } else {
            networkPref.setEntries(R.array.snell_network_entry)
            networkPref.setEntryValues(R.array.snell_network_value)
        }
    }

    private fun updateReuseEnabled(version: Int, reusePref: Preference) {
        reusePref.isEnabled = version >= 4
    }

    private fun updateObfsModeOptions(version: Int, obfsModePref: SimpleMenuPreference) {
        if (version >= 4) {
            obfsModePref.entries = arrayOf("None", "HTTP")
            obfsModePref.entryValues = arrayOf("", "http")
            if (obfsModePref.value == "tls") {
                obfsModePref.value = ""
            }
        } else {
            obfsModePref.setEntries(R.array.snell_obfs_modes_entry)
            obfsModePref.setEntryValues(R.array.snell_obfs_modes_value)
        }
    }
}
