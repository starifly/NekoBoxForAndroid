package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.canonicalHysteria2ECHConfig
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class HysteriaSettingsActivity : ProfileSettingsActivity<HysteriaBean>() {

    override fun createEntity() = HysteriaBean().applyDefaultValues()

    override fun HysteriaBean.init() {
        DataStore.profileName = name
        DataStore.protocolVersion = protocolVersion
        DataStore.serverAddress = serverAddress
        DataStore.serverPorts = serverPorts
        DataStore.serverObfs = obfuscation
        DataStore.serverHy2ObfsType = hysteria2ObfsType
        DataStore.serverHy2GeckoMinPacket = geckoMinPacketSize
        DataStore.serverHy2GeckoMaxPacket = geckoMaxPacketSize
        DataStore.serverHy2EchEnabled = enableECH
        DataStore.serverHy2EchConfig = echConfig
        DataStore.serverAuthType = authPayloadType
        DataStore.serverProtocolInt = protocol
        DataStore.serverPassword = authPayload
        DataStore.serverSNI = sni
        DataStore.serverALPN = alpn
        DataStore.serverCertificates = caText
        DataStore.serverAllowInsecure = allowInsecure
        DataStore.serverUploadSpeed = uploadMbps
        DataStore.serverDownloadSpeed = downloadMbps
        DataStore.serverStreamReceiveWindow = streamReceiveWindow
        DataStore.serverConnectionReceiveWindow = connectionReceiveWindow
        DataStore.serverDisableMtuDiscovery = disableMtuDiscovery
        DataStore.serverHopInterval = hopInterval
    }

    override suspend fun saveAndExit() {
        if (DataStore.protocolVersion == 2 && DataStore.serverHy2EchEnabled) {
            val failure = runCatching {
                canonicalHysteria2ECHConfig(DataStore.serverHy2EchConfig)
            }.exceptionOrNull()
            if (failure != null) {
                onMainDispatcher {
                    Toast.makeText(
                        this@HysteriaSettingsActivity,
                        failure.message ?: getString(R.string.hysteria2_ech_config_invalid),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return
            }
        }
        super.saveAndExit()
    }

    override fun HysteriaBean.serialize() {
        name = DataStore.profileName
        protocolVersion = DataStore.protocolVersion
        serverAddress = DataStore.serverAddress
        serverPorts = DataStore.serverPorts
        obfuscation = DataStore.serverObfs
        hysteria2ObfsType = DataStore.serverHy2ObfsType
        geckoMinPacketSize = DataStore.serverHy2GeckoMinPacket
        geckoMaxPacketSize = DataStore.serverHy2GeckoMaxPacket
        enableECH = DataStore.serverHy2EchEnabled
        echConfig = DataStore.serverHy2EchConfig
        authPayloadType = DataStore.serverAuthType
        authPayload = DataStore.serverPassword
        protocol = DataStore.serverProtocolInt
        sni = DataStore.serverSNI
        alpn = DataStore.serverALPN
        caText = DataStore.serverCertificates
        allowInsecure = DataStore.serverAllowInsecure
        uploadMbps = DataStore.serverUploadSpeed
        downloadMbps = DataStore.serverDownloadSpeed
        streamReceiveWindow = DataStore.serverStreamReceiveWindow
        connectionReceiveWindow = DataStore.serverConnectionReceiveWindow
        disableMtuDiscovery = DataStore.serverDisableMtuDiscovery
        hopInterval = DataStore.serverHopInterval
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.hysteria_preferences)

        val authType = findPreference<SimpleMenuPreference>(Key.SERVER_AUTH_TYPE)!!
        val authPayload = findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!
        authPayload.isVisible = authType.value != "${HysteriaBean.TYPE_NONE}"
        authType.setOnPreferenceChangeListener { _, newValue ->
            authPayload.isVisible = newValue != "${HysteriaBean.TYPE_NONE}"
            true
        }

        val protocol = findPreference<SimpleMenuPreference>(Key.SERVER_PROTOCOL)!!
        val alpn = findPreference<EditTextPreference>(Key.SERVER_ALPN)!!

        val echCategory = findPreference<PreferenceCategory>(Key.SERVER_HY2_ECH_CATEGORY)!!
        val enableECH = findPreference<SwitchPreference>(Key.SERVER_HY2_ECH_ENABLED)!!
        val echConfig = findPreference<EditTextPreference>(Key.SERVER_HY2_ECH_CONFIG)!!

        fun updateECH(enabled: Boolean, version: Int) {
            val isHy2 = version == 2
            echCategory.isVisible = isHy2
            echConfig.isVisible = isHy2 && enabled
        }
        enableECH.setOnPreferenceChangeListener { _, newValue ->
            updateECH(newValue as Boolean, DataStore.protocolVersion)
            true
        }

        // Hysteria2 obfs type selector: controls visibility of the obfs password and the
        // Gecko packet-size fields. Only shown for HY2.
        val obfsType = findPreference<SimpleMenuPreference>(Key.SERVER_HY2_OBFS_TYPE)!!
        val obfsPassword = findPreference<EditTextPreference>(Key.SERVER_OBFS)!!
        val geckoMin = findPreference<EditTextPreference>(Key.SERVER_HY2_GECKO_MIN_PACKET)!!
        val geckoMax = findPreference<EditTextPreference>(Key.SERVER_HY2_GECKO_MAX_PACKET)!!
        geckoMin.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        geckoMax.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)

        fun updateObfs(type: Int, version: Int) {
            // For HY1 the legacy single obfs password field is used; for HY2 use the
            // type selector and show password/gecko fields accordingly.
            val isHy2 = version == 2
            obfsType.isVisible = isHy2
            if (isHy2) {
                // Both Salamander and Gecko require an obfs password (apernet/hysteria
                // clientConfigObfsGecko.Password is mandatory), so show it for either.
                obfsPassword.isVisible = type != HysteriaBean.OBFS_NONE
                val isGecko = type == HysteriaBean.OBFS_GECKO
                geckoMin.isVisible = isGecko
                geckoMax.isVisible = isGecko
            } else {
                obfsPassword.isVisible = true
                geckoMin.isVisible = false
                geckoMax.isVisible = false
            }
        }
        obfsType.setOnPreferenceChangeListener { _, newValue ->
            updateObfs(
                newValue.toString().toIntOrNull() ?: HysteriaBean.OBFS_NONE,
                DataStore.protocolVersion,
            )
            true
        }

        fun updateVersion(v: Int) {
            if (v == 2) {
                authPayload.isVisible = true
                //
                authType.isVisible = false
                protocol.isVisible = false
                alpn.isVisible = false
                //
                findPreference<EditTextPreference>(Key.SERVER_STREAM_RECEIVE_WINDOW)!!.isVisible =
                    false
                findPreference<EditTextPreference>(Key.SERVER_CONNECTION_RECEIVE_WINDOW)!!.isVisible =
                    false
                findPreference<SwitchPreferenceCompat>(Key.SERVER_DISABLE_MTU_DISCOVERY)!!.isVisible =
                    false
                //
                authPayload.title = resources.getString(R.string.password)
            } else {
                authType.isVisible = true
                authPayload.isVisible = true
                protocol.isVisible = true
                alpn.isVisible = true
                //
                findPreference<EditTextPreference>(Key.SERVER_STREAM_RECEIVE_WINDOW)!!.isVisible =
                    true
                findPreference<EditTextPreference>(Key.SERVER_CONNECTION_RECEIVE_WINDOW)!!.isVisible =
                    true
                findPreference<SwitchPreferenceCompat>(Key.SERVER_DISABLE_MTU_DISCOVERY)!!.isVisible =
                    true
                //
                authPayload.title = resources.getString(R.string.hysteria_auth_payload)
            }
            updateObfs(DataStore.serverHy2ObfsType, v)
            updateECH(DataStore.serverHy2EchEnabled, v)
        }
        findPreference<SimpleMenuPreference>(Key.PROTOCOL_VERSION)!!.setOnPreferenceChangeListener { _, newValue ->
            updateVersion(newValue.toString().toIntOrNull() ?: 1)
            true
        }
        updateVersion(DataStore.protocolVersion)

        findPreference<EditTextPreference>(Key.SERVER_UPLOAD_SPEED)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>(Key.SERVER_DOWNLOAD_SPEED)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>(Key.SERVER_STREAM_RECEIVE_WINDOW)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>(Key.SERVER_CONNECTION_RECEIVE_WINDOW)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }

        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        findPreference<EditTextPreference>(Key.SERVER_OBFS)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }

        findPreference<EditTextPreference>(Key.SERVER_HOP_INTERVAL)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
    }
}
