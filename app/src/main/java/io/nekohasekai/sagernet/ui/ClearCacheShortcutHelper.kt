package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.nekohasekai.sagernet.R

private const val CLEAR_CACHE_SHORTCUT_ID = "clear-cache-restart"

private fun buildClearCacheShortcut(context: Context): ShortcutInfoCompat {
    val intent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        putExtra(MainActivity.EXTRA_CLEAR_CACHE_AND_RESTART, true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    return ShortcutInfoCompat.Builder(context, CLEAR_CACHE_SHORTCUT_ID)
        .setShortLabel(context.getString(R.string.quick_clear_cache_restart))
        .setLongLabel(context.getString(R.string.quick_clear_cache_restart))
        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_qu_shadowsocks_launcher))
        .setIntent(intent)
        .build()
}

fun publishClearCacheShortcut(context: Context) {
    runCatching {
        ShortcutManagerCompat.pushDynamicShortcut(context, buildClearCacheShortcut(context))
    }
}

fun requestPinClearCacheShortcut(context: Context): Boolean {
    publishClearCacheShortcut(context)
    return ShortcutManagerCompat.requestPinShortcut(context, buildClearCacheShortcut(context), null)
}
