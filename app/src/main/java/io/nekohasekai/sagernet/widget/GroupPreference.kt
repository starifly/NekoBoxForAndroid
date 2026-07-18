package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class GroupPreference
@JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = R.attr.dropdownPreferenceStyle,
) : SimpleMenuPreference(context, attrs, defStyle, 0) {

    private val groupNames = mutableMapOf<Long, String>()

    init {
        val wasEnabled = isEnabled
        entries = emptyArray()
        entryValues = emptyArray()
        isEnabled = false

        runOnDefaultDispatcher {
            val groups = SagerDatabase.groupDao.allGroups()
            runOnMainDispatcher {
                groupNames.clear()
                groupNames.putAll(groups.associate { it.id to it.displayName() })
                entries = groups.map { it.displayName() }.toTypedArray()
                entryValues = groups.map { "${it.id}" }.toTypedArray()
                isEnabled = wasEnabled
                notifyChanged()
            }
        }
    }

    override fun getSummary(): CharSequence? {
        if (!value.isNullOrBlank() && value != "0") {
            return groupNames[value.toLongOrNull()] ?: super.getSummary()
        }
        return super.getSummary()
    }
}
