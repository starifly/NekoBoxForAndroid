package moe.matsuri.nb4a.plugin

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.plugin.BoundedDeduplicator
import io.nekohasekai.sagernet.plugin.PluginManager.loadString
import io.nekohasekai.sagernet.plugin.PluginTrust
import io.nekohasekai.sagernet.utils.PackageCache

object Plugins {
    const val AUTHORITIES_PREFIX_SEKAI_EXE = "io.nekohasekai.sagernet.plugin."
    const val AUTHORITIES_PREFIX_NEKO_EXE = "moe.matsuri.exe."

    const val ACTION_NATIVE_PLUGIN = "io.nekohasekai.sagernet.plugin.ACTION_NATIVE_PLUGIN"

    const val METADATA_KEY_ID = "io.nekohasekai.sagernet.plugin.id"
    const val METADATA_KEY_EXECUTABLE_PATH = "io.nekohasekai.sagernet.plugin.executable_path"

    private val rejectionDeduplicator = BoundedDeduplicator()
    private val hostCurrentFingerprints by lazy {
        PluginTrust.readSignerRecord(
            SagerNet.application.packageManager,
            SagerNet.application.packageName,
        )?.current.orEmpty()
    }

    fun isExe(pkg: PackageInfo): Boolean {
        if (pkg.providers?.isEmpty() == true) return false
        val provider = pkg.providers?.get(0) ?: return false
        val auth = provider.authority ?: return false
        return auth.startsWith(AUTHORITIES_PREFIX_SEKAI_EXE) ||
            auth.startsWith(AUTHORITIES_PREFIX_NEKO_EXE)
    }

    fun preferExePrefix(): String {
        return AUTHORITIES_PREFIX_NEKO_EXE
    }

    fun isUsingMatsuriExe(pluginId: String): Boolean {
        getPlugin(pluginId)?.apply {
            if (authority.startsWith(AUTHORITIES_PREFIX_NEKO_EXE)) {
                return true
            }
        }
        return false
    }

    fun displayExeProvider(pkgName: String): String {
        return if (pkgName.startsWith(AUTHORITIES_PREFIX_SEKAI_EXE)) {
            "SagerNet"
        } else if (pkgName.startsWith(AUTHORITIES_PREFIX_NEKO_EXE)) {
            "Matsuri"
        } else {
            "Unknown"
        }
    }

    fun getPlugin(pluginId: String): ProviderInfo? {
        if (pluginId.isBlank()) return null
        getPluginExternal(pluginId)?.let { return it }
        // internal so
        return ProviderInfo().apply { authority = AUTHORITIES_PREFIX_NEKO_EXE }
    }

    fun getPluginExternal(pluginId: String): ProviderInfo? {
        if (pluginId.isBlank()) return null

        var providers = discoverExternal(pluginId)
        if (providers.isEmpty()) return null

        val result = evaluateProviders(providers)
        notifyRejected(pluginId, result.rejected)
        providers = result.trusted
        if (providers.isEmpty()) return null

        if (providers.size > 1) {
            val prefer = providers.filter {
                it.authority.startsWith(preferExePrefix())
            }
            if (prefer.size == 1) providers = prefer
        }

        if (providers.size > 1) {
            val message =
                "Conflicting plugins found from: ${providers.joinToString { it.packageName }}"
            Toast.makeText(SagerNet.application, message, Toast.LENGTH_LONG).show()
        }

        return providers[0]
    }

    fun inspectRejectedPlugins(pluginId: String): List<PluginTrust.RejectedPlugin> {
        if (pluginId.isBlank()) return emptyList()
        return evaluateProviders(discoverExternal(pluginId)).rejected.distinctBy {
            PluginTrust.rejectionKey(pluginId, it)
        }
    }

    private fun discoverExternal(pluginId: String): List<ProviderInfo> {
        // Preserve the live fallback: the intent-query source wins whenever it is non-empty.
        val oldProviders = getExtPluginOld(pluginId)
        return if (oldProviders.isNotEmpty()) oldProviders else getExtPluginNew(pluginId)
    }

    private fun evaluateProviders(providers: List<ProviderInfo>) = PluginTrust.filterTrustedCandidates(
        candidates = providers,
        packageName = { it.packageName },
        signerRecord = {
            PluginTrust.readSignerRecord(SagerNet.application.packageManager, it.packageName)
        },
        hostCurrentFingerprints = hostCurrentFingerprints,
        approvals = DataStore.pluginSignerApprovals,
    )

    private fun notifyRejected(pluginId: String, rejected: List<PluginTrust.RejectedPlugin>) {
        rejected.distinctBy { PluginTrust.rejectionKey(pluginId, it) }.forEach { rejection ->
            val key = PluginTrust.rejectionKey(pluginId, rejection)
            if (!rejectionDeduplicator.shouldNotify(key)) return@forEach
            val signer = rejection.currentFingerprints?.sorted()?.firstOrNull()?.take(16)
                ?: SagerNet.application.getString(R.string.plugin_signer_unreadable)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    SagerNet.application,
                    SagerNet.application.getString(
                        R.string.plugin_signer_rejected,
                        rejection.packageName,
                        signer,
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun getExtPluginNew(pluginId: String): List<ProviderInfo> {
        PackageCache.awaitLoadSync()
        val pkgs = PackageCache.installedPluginPackages
            .map { it.value }
            .filter { it.providers?.get(0)?.loadString(METADATA_KEY_ID) == pluginId }
        return pkgs.mapNotNull { it.providers?.get(0) }
    }

    private fun buildUri(id: String, auth: String) = Uri.Builder()
        .scheme("plugin")
        .authority(auth)
        .path("/$id")
        .build()

    private fun getExtPluginOld(pluginId: String): List<ProviderInfo> {
        var flags = PackageManager.GET_META_DATA
        if (Build.VERSION.SDK_INT >= 24) {
            flags =
                flags or PackageManager.MATCH_DIRECT_BOOT_UNAWARE or PackageManager.MATCH_DIRECT_BOOT_AWARE
        }
        val list1 = SagerNet.application.packageManager.queryIntentContentProviders(
            Intent(ACTION_NATIVE_PLUGIN, buildUri(pluginId, "io.nekohasekai.sagernet")),
            flags,
        )
        val list2 = SagerNet.application.packageManager.queryIntentContentProviders(
            Intent(ACTION_NATIVE_PLUGIN, buildUri(pluginId, "moe.matsuri.lite")),
            flags,
        )
        return (list1 + list2).mapNotNull {
            it.providerInfo
        }.filter { it.exported }
    }
}
