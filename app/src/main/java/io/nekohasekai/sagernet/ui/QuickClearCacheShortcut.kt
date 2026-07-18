package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.getSystemService

class QuickClearCacheShortcut : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 25) {
            getSystemService<ShortcutManager>()!!.reportShortcutUsed("clear-cache-restart")
        }

        recoverVlessTlsStateAndRestart(this)
    }
}
