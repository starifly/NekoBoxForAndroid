package io.nekohasekai.sagernet.fmt

import android.app.Application
import android.content.Context
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.robolectric.RuntimeEnvironment

internal object ConfigBuilderTestEnv {
    private var installed = false

    fun reset(dnsHosts: String = "") {
        installApplication()
        runBlocking {
            withContext(Dispatchers.IO) {
                DataStore.configurationStore.prime()
                SagerDatabase.proxyDao.reset()
                SagerDatabase.groupDao.reset()
                SagerDatabase.rulesDao.reset()
                DataStore.configurationStore.replaceAllDurable(
                    listOf(
                        KeyValuePair(Key.SERVICE_MODE).put(Key.MODE_PROXY),
                        KeyValuePair(Key.REMOTE_DNS).put("https://resolver.example/dns-query"),
                        KeyValuePair(Key.DIRECT_DNS).put("https://direct.example/dns-query"),
                        KeyValuePair(Key.ENABLE_FAKEDNS).put(false),
                        KeyValuePair(Key.ENABLE_DNS_ROUTING).put(false),
                        KeyValuePair(Key.DNS_HOSTS).put(dnsHosts),
                        KeyValuePair(Key.IPV6_MODE).put(IPv6Mode.ENABLE.toString()),
                        KeyValuePair(Key.TRAFFIC_SNIFFING).put("0"),
                        KeyValuePair(Key.RESOLVE_DESTINATION).put(false),
                        KeyValuePair(Key.ALLOW_ACCESS).put(false),
                        KeyValuePair(Key.ENABLE_CLASH_API).put(false),
                        KeyValuePair(Key.CLASH_API_SECRET).put("export-secret"),
                        KeyValuePair(Key.GLOBAL_CUSTOM_CONFIG).put(""),
                        KeyValuePair(Key.GLOBAL_MODE).put(false),
                    ),
                )
            }
        }
    }

    fun <T> io(block: () -> T): T = runBlocking { withContext(Dispatchers.IO) { block() } }

    private fun installApplication() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val base = RuntimeEnvironment.getApplication() as Application
            // Attach the required SagerNet-typed context without calling onCreate(), which starts
            // native initialization that is unavailable in a JVM test.
            val application = SagerNet()
            SagerNet::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
                isAccessible = true
                invoke(application, base)
            }
            installed = true
        }
    }
}
