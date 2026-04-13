package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.getSystemService
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import java.io.File

class QuickClearCacheShortcut : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 25) {
            getSystemService<ShortcutManager>()!!.reportShortcutUsed("clear-cache-restart")
        }

        runOnDefaultDispatcher {
            try {
                SagerNet.stopService()
                Thread.sleep(300)

                clearDirFiles(SagerNet.application.cacheDir, skipFiles = setOf("neko.log"))

                val parentDir = SagerNet.application.cacheDir.parentFile
                val relativeCache = File(parentDir, "cache")
                if (relativeCache.exists() && relativeCache.isDirectory) {
                    clearDirFiles(relativeCache)
                }

                onMainDispatcher {
                    Toast.makeText(
                        this@QuickClearCacheShortcut,
                        R.string.clear_cache_success,
                        Toast.LENGTH_SHORT
                    ).show()
                    ProcessPhoenix.triggerRebirth(this@QuickClearCacheShortcut)
                }
            } catch (e: Exception) {
                onMainDispatcher {
                    Toast.makeText(
                        this@QuickClearCacheShortcut,
                        getString(R.string.clear_cache_failed, e.message),
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
        }
    }

    private fun clearDirFiles(dir: File, skipFiles: Set<String> = emptySet()): Boolean {
        if (!dir.isDirectory) return false

        val children = dir.list() ?: return true
        for (child in children) {
            val childFile = File(dir, child)

            if (child == "neko.log") {
                try {
                    childFile.writeText("")
                    continue
                } catch (_: Exception) {
                }
            }

            if (child in skipFiles) continue

            if (childFile.isDirectory) {
                clearDirFiles(childFile, skipFiles)
            } else {
                childFile.delete()
            }
        }

        return true
    }
}
