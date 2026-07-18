package moe.matsuri.nb4a

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr
import moe.matsuri.nb4a.proxy.config.ConfigBean

// Settings for all protocols, built-in or plugin
object Protocols {

    // Deduplication

    class Deduplication(val bean: AbstractBean) {

        // Callers build these wrappers after parsing and do not mutate their beans while the
        // wrappers are in a set. Cache the serialization-backed hash so HashSet probes do not
        // serialize the same bean repeatedly.
        private val comparisonHash = if (bean is ConfigBean) bean.config?.hashCode() ?: 0 else bean.hashCode()

        override fun hashCode() = 31 * bean.javaClass.hashCode() + comparisonHash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Deduplication) return false
            if (bean is ConfigBean && other.bean is ConfigBean) {
                return bean.config == other.bean.config
            }
            if (bean.javaClass != other.bean.javaClass) return false
            // AbstractBean equality serializes without the display name. Both dedup callers run
            // serially; they must not compare the same mutable beans concurrently.
            return bean == other.bean
        }
    }

    // Display

    fun Context.getProtocolColor(type: Int): Int {
        return when (type) {
            TYPE_NEKO -> getColorAttr(com.google.android.material.R.attr.colorOnSurface)
            else -> getColorAttr(R.attr.colorPrimary)
        }
    }

    // Test

    fun genFriendlyMsg(msg: String): String {
        val msgL = msg.lowercase()
        return when {
            msgL.contains("timeout") || msgL.contains("deadline") -> {
                app.getString(R.string.connection_test_timeout_error)
            }

            msgL.contains("refused") || msgL.contains("closed pipe") -> {
                app.getString(R.string.connection_test_refused)
            }

            else -> msg
        }
    }
}
