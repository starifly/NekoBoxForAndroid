/******************************************************************************
 * Copyright (C) 2026 by nekohasekai <contact-git@sekai.icu>                  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 * (at your option) any later version.                                        *
 ******************************************************************************/

package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.masterdnsvpn.MasterDnsVpnBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues

class MasterDnsVpnSettingsActivity : ProfileSettingsActivity<MasterDnsVpnBean>() {

    override fun createEntity() = MasterDnsVpnBean().applyDefaultValues()

    override fun MasterDnsVpnBean.init() {
        DataStore.profileName = name
        // serverAddress/Port are unused by this protocol but kept for the base editor.
        DataStore.serverAddress = if (serverAddress.isNullOrBlank()) "masterdnsvpn" else serverAddress
        DataStore.mdvDomains = domains
        DataStore.mdvEncryptionMethod = dataEncryptionMethod
        DataStore.mdvEncryptionKey = encryptionKey
        DataStore.mdvResolvers = resolvers
        DataStore.mdvBalancingStrategy = resolverBalancingStrategy
        DataStore.mdvPacketDup = packetDuplicationCount
        DataStore.mdvSetupPacketDup = setupPacketDuplicationCount
        DataStore.mdvAutoDisableTimeout = autoDisableTimeoutServers
        DataStore.mdvAutoRemoveLowMtu = autoRemoveLowMtuServers
        DataStore.mdvBaseEncode = baseEncodeData
        DataStore.mdvUploadCompression = uploadCompressionType
        DataStore.mdvDownloadCompression = downloadCompressionType
        DataStore.mdvCompressionMinSize = compressionMinSize
        DataStore.mdvMinUploadMtu = minUploadMtu
        DataStore.mdvMinDownloadMtu = minDownloadMtu
        DataStore.mdvMaxUploadMtu = maxUploadMtu
        DataStore.mdvMaxDownloadMtu = maxDownloadMtu
        DataStore.mdvLocalDnsEnabled = localDnsEnabled
        DataStore.mdvLocalDnsPort = localDnsPort
        DataStore.mdvLogLevel = logLevel
        DataStore.mdvAdvancedJson = advancedJson
    }

    override fun MasterDnsVpnBean.serialize() {
        name = DataStore.profileName
        serverAddress = "masterdnsvpn"
        serverPort = 0
        domains = DataStore.mdvDomains
        dataEncryptionMethod = DataStore.mdvEncryptionMethod
        encryptionKey = DataStore.mdvEncryptionKey
        resolvers = DataStore.mdvResolvers
        resolverBalancingStrategy = DataStore.mdvBalancingStrategy
        packetDuplicationCount = DataStore.mdvPacketDup
        setupPacketDuplicationCount = DataStore.mdvSetupPacketDup
        autoDisableTimeoutServers = DataStore.mdvAutoDisableTimeout
        autoRemoveLowMtuServers = DataStore.mdvAutoRemoveLowMtu
        baseEncodeData = DataStore.mdvBaseEncode
        uploadCompressionType = DataStore.mdvUploadCompression
        downloadCompressionType = DataStore.mdvDownloadCompression
        compressionMinSize = DataStore.mdvCompressionMinSize
        minUploadMtu = DataStore.mdvMinUploadMtu
        minDownloadMtu = DataStore.mdvMinDownloadMtu
        maxUploadMtu = DataStore.mdvMaxUploadMtu
        maxDownloadMtu = DataStore.mdvMaxDownloadMtu
        localDnsEnabled = DataStore.mdvLocalDnsEnabled
        localDnsPort = DataStore.mdvLocalDnsPort
        logLevel = DataStore.mdvLogLevel
        advancedJson = DataStore.mdvAdvancedJson
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.masterdnsvpn_preferences)
        findPreference<EditTextPreference>(Key.MDV_ENCRYPTION_KEY)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        for (numberKey in listOf(
            Key.MDV_PACKET_DUP, Key.MDV_SETUP_PACKET_DUP, Key.MDV_COMPRESSION_MIN_SIZE,
            Key.MDV_MIN_UPLOAD_MTU, Key.MDV_MIN_DOWNLOAD_MTU, Key.MDV_MAX_UPLOAD_MTU,
            Key.MDV_MAX_DOWNLOAD_MTU,
        )) {
            findPreference<EditTextPreference>(numberKey)?.setOnBindEditTextListener(
                EditTextPreferenceModifiers.Number
            )
        }
        findPreference<EditTextPreference>(Key.MDV_LOCAL_DNS_PORT)?.setOnBindEditTextListener(
            EditTextPreferenceModifiers.Port
        )
    }

}
