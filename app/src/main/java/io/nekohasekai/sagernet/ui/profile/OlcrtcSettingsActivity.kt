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
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.olcrtc.OlcrtcBean
import io.nekohasekai.sagernet.fmt.olcrtc.validateOlcrtcProfile
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.onMainDispatcher

class OlcrtcSettingsActivity : ProfileSettingsActivity<OlcrtcBean>() {

    override fun createEntity() = OlcrtcBean().applyDefaultValues()

    override fun OlcrtcBean.init() {
        DataStore.profileName = name
        // serverAddress/Port are unused by this protocol; the editor uses the OLCRTC_* fields.
        DataStore.serverAddress = "olcrtc"
        DataStore.olcrtcCarrier = carrier
        DataStore.olcrtcRoomId = roomId
        DataStore.olcrtcClientId = clientId
        DataStore.olcrtcKeyHex = keyHex
        DataStore.olcrtcTransport = transport
        DataStore.olcrtcVp8Fps = vp8Fps
        DataStore.olcrtcVp8Batch = vp8BatchSize
        DataStore.olcrtcDnsServer = dnsServer
    }

    override fun OlcrtcBean.serialize() {
        name = DataStore.profileName
        serverAddress = "olcrtc"
        serverPort = 0
        carrier = DataStore.olcrtcCarrier
        roomId = DataStore.olcrtcRoomId
        clientId = DataStore.olcrtcClientId
        keyHex = DataStore.olcrtcKeyHex
        transport = DataStore.olcrtcTransport
        vp8Fps = DataStore.olcrtcVp8Fps
        vp8BatchSize = DataStore.olcrtcVp8Batch
        dnsServer = DataStore.olcrtcDnsServer

        // Fail fast in the editor instead of saving a profile that can only fail at connect.
        validateOlcrtcProfile(requireClientId = true)
    }

    override suspend fun saveAndExit() {
        try {
            // Validate a temporary bean before the base path can stop an active profile.
            createEntity().apply { serialize() }
            super.saveAndExit()
        } catch (e: IllegalArgumentException) {
            onMainDispatcher {
                Toast.makeText(
                    applicationContext,
                    e.message ?: "Invalid olcRTC profile",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.olcrtc_preferences)
        findPreference<EditTextPreference>(Key.OLCRTC_KEY_HEX)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        for (numberKey in listOf(Key.OLCRTC_VP8_FPS, Key.OLCRTC_VP8_BATCH)) {
            findPreference<EditTextPreference>(numberKey)?.setOnBindEditTextListener(
                EditTextPreferenceModifiers.Number,
            )
        }
    }
}
