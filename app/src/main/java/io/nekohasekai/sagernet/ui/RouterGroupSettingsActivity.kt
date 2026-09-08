package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RouterDeleteResult
import io.nekohasekai.sagernet.database.RouterGroup
import io.nekohasekai.sagernet.database.RouterGroupDraft
import io.nekohasekai.sagernet.database.RouterGroupRepository
import io.nekohasekai.sagernet.database.RouterGroupValidationException
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.displayNameOrFallback
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.route.RouterFilterConfig

class RouterGroupSettingsActivity : ThemedActivity(R.layout.layout_settings_activity) {
    private val editor get() = supportFragmentManager.findFragmentById(R.id.settings) as? EditorFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.router_group_settings)
            setDisplayHomeAsUpEnabled(true)
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settings, EditorFragment()).commit()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        menu.findItem(R.id.action_delete).isVisible = intent.getLongExtra(EXTRA_ROUTER_ID, 0) > 0
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_apply -> { editor?.save(); true }
        R.id.action_delete -> {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_group_prompt)
                .setPositiveButton(R.string.yes) { _, _ ->
                    editor?.delete()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    class EditorFragment : PreferenceFragmentCompat() {
        private val routerId get() = requireActivity().intent.getLongExtra(EXTRA_ROUTER_ID, 0L)
        private lateinit var name: EditTextPreference
        private lateinit var enabled: SwitchPreferenceCompat
        private lateinit var mode: ListPreference
        private lateinit var sources: MultiSelectListPreference
        private lateinit var include: EditTextPreference
        private lateinit var exclude: EditTextPreference
        private lateinit var urlCategory: PreferenceCategory
        private lateinit var testUrl: EditTextPreference
        private lateinit var interval: EditTextPreference
        private lateinit var tolerance: EditTextPreference
        private lateinit var selected: ListPreference
        private lateinit var preview: Preference
        private var sourceOrder = emptyList<Long>()

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val group = routerId.takeIf { it > 0 }?.let(RouterGroupRepository::get)
            val filter = group?.matchConfig?.let(RouterFilterConfig::fromJson) ?: RouterFilterConfig()
            val subscriptions = SagerDatabase.groupDao.allGroups().filter { it.type == GroupType.SUBSCRIPTION }
            sourceOrder = subscriptions.map { it.id }
            val screen = preferenceManager.createPreferenceScreen(requireContext())
            name = EditTextPreference(requireContext()).nonPersistent().apply {
                key = "router_group_name"
                title = getString(R.string.router_group_name)
                text = group?.name.orEmpty()
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }
            enabled = SwitchPreferenceCompat(requireContext()).nonPersistent().apply {
                key = "router_group_enabled"
                title = getString(R.string.router_group_enabled)
                isChecked = group?.enabled ?: true
            }
            mode = ListPreference(requireContext()).nonPersistent().apply {
                key = "router_group_mode"
                title = getString(R.string.router_group_mode)
                entries = arrayOf(getString(R.string.router_mode_manual), getString(R.string.router_mode_automatic))
                entryValues = arrayOf(RouterGroup.MODE_SELECTOR.toString(), RouterGroup.MODE_URL_TEST.toString())
                value = (group?.mode ?: RouterGroup.MODE_SELECTOR).toString()
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
            val subMap = subscriptions.associate { it.id.toString() to it.displayName() }
            sources = MultiSelectListPreference(requireContext()).nonPersistent().apply {
                key = "router_group_sources"
                title = getString(R.string.router_group_sources)
                entries = subscriptions.map { it.displayName() }.toTypedArray()
                entryValues = subscriptions.map { it.id.toString() }.toTypedArray()
                values = RouterGroupRepository.sourceIds(routerId).map(Long::toString).toSet()
                summaryProvider = Preference.SummaryProvider<MultiSelectListPreference> { pref ->
                    val selectedNames = pref.values.mapNotNull { subMap[it] }
                    if (selectedNames.isEmpty()) {
                        getString(R.string.router_no_sources_selected)
                    } else {
                        selectedNames.joinToString(", ")
                    }
                }
            }
            include = textPreference("router_group_include", R.string.router_group_include, filter.includeRegex)
            exclude = textPreference("router_group_exclude", R.string.router_group_exclude, filter.excludeRegex)
            urlCategory = PreferenceCategory(requireContext()).apply {
                key = "router_category_url_test"
                title = getString(R.string.router_url_test_settings)
            }
            testUrl = textPreference("router_test_url", R.string.router_test_url, filter.testUrl)
            interval = textPreference("router_test_interval", R.string.router_test_interval, filter.intervalSeconds.toString())
            tolerance = textPreference("router_test_tolerance", R.string.router_test_tolerance, filter.toleranceMs.toString())
            selected = ListPreference(requireContext()).nonPersistent().apply {
                key = "router_select_node"
                title = getString(R.string.router_select_node)
                val members = SagerDatabase.routerMemberDao.getByRouter(routerId)
                    .mapNotNull { SagerDatabase.proxyDao.getById(it.proxyId) }
                entries = members.map { proxy ->
                    val subName = subMap[proxy.groupId.toString()]
                    if (!subName.isNullOrBlank()) "[${subName}] ${proxy.displayNameOrFallback().trim()}"
                    else proxy.displayNameOrFallback().trim()
                }.toTypedArray()
                entryValues = members.map { it.id.toString() }.toTypedArray()
                value = group?.selectedProxyId?.takeIf { it > 0 }?.toString()
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                setOnPreferenceChangeListener { _, newValue ->
                    val proxyId = newValue.toString().toLong()
                    runOnDefaultDispatcher {
                        runCatching { RouterGroupRepository.select(routerId, proxyId) }
                            .onSuccess { updated -> if (DataStore.serviceState.started) SagerNet.reloadService(updated.stableTag, proxyId) }
                            .onFailure { error -> onMainDispatcher { toast(error.message) } }
                    }
                    true
                }
            }
            preview = Preference(requireContext()).apply {
                key = "router_group_preview"
                title = getString(R.string.router_group_preview)
                isSelectable = false
            }
            listOf(name, enabled, mode, sources, include, exclude).forEach(screen::addPreference)
            screen.addPreference(urlCategory)
            listOf(testUrl, interval, tolerance).forEach(urlCategory::addPreference)
            screen.addPreference(selected)
            screen.addPreference(preview)
            preferenceScreen = screen

            listOf(name, enabled, mode, sources, include, exclude, testUrl, interval, tolerance).forEach { preference ->
                preference.setOnPreferenceChangeListener { _, newValue ->
                    if (preference == sources) {
                        @Suppress("UNCHECKED_CAST")
                        sources.values = newValue as? Set<String> ?: emptySet()
                    }
                    view?.post { updateDynamicState() }
                    true
                }
            }
            updateDynamicState()
        }

        private fun updateDynamicState() {
            val automatic = mode.value == RouterGroup.MODE_URL_TEST.toString()
            urlCategory.isVisible = automatic
            selected.isVisible = !automatic && routerId > 0
            runCatching { RouterGroupRepository.preview(draft()) }
                .onSuccess { result ->
                    preview.summary = if (result.names.isEmpty()) getString(R.string.router_no_members)
                    else {
                        val maxDisplay = 20
                        val previewText = result.names.take(maxDisplay).joinToString("\n")
                        val suffix = if (result.names.size > maxDisplay) "\n..." else ""
                        getString(R.string.router_group_preview_count, result.names.size, previewText + suffix)
                    }
                }
                .onFailure { preview.summary = it.message }
        }

        fun save() {
            val draft = draft()
            runOnDefaultDispatcher {
                runCatching { RouterGroupRepository.save(draft) }
                    .onSuccess {
                        if (DataStore.serviceState.started) SagerNet.reloadServiceFully()
                        onMainDispatcher { requireActivity().finish() }
                    }
                    .onFailure { error -> onMainDispatcher { toast(validationMessage(error)) } }
            }
        }

        fun delete() {
            runOnDefaultDispatcher {
                when (val result = RouterGroupRepository.delete(routerId)) {
                    RouterDeleteResult.Deleted -> {
                        if (DataStore.serviceState.started) SagerNet.reloadServiceFully()
                        onMainDispatcher { requireActivity().finish() }
                    }
                    is RouterDeleteResult.Referenced -> onMainDispatcher {
                        toast(getString(R.string.router_group_delete_referenced, result.ruleCount))
                    }
                }
            }
        }

        private fun draft() = RouterGroupDraft(
            id = routerId,
            name = name.text.orEmpty(),
            mode = mode.value?.toIntOrNull() ?: RouterGroup.MODE_SELECTOR,
            enabled = enabled.isChecked,
            sourceGroupIds = sourceOrder.filter { it.toString() in sources.values },
            filter = RouterFilterConfig(
                includeRegex = include.text.orEmpty(),
                excludeRegex = exclude.text.orEmpty(),
                testUrl = testUrl.text.orEmpty(),
                intervalSeconds = interval.text?.toLongOrNull() ?: 0,
                toleranceMs = tolerance.text?.toIntOrNull() ?: -1,
            ),
        )

        private fun textPreference(prefKey: String, titleRes: Int, initial: String) =
            EditTextPreference(requireContext()).nonPersistent().apply {
                key = prefKey
                title = getString(titleRes)
                text = initial
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }

        private fun <T : Preference> T.nonPersistent(): T = apply { isPersistent = false }

        private fun validationMessage(error: Throwable): String = when (error) {
            is RouterGroupValidationException -> getString(R.string.router_group_validation_field, error.field.name, error.message)
            else -> error.message ?: getString(R.string.error_title)
        }

        private fun toast(message: String?) = Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    companion object { const val EXTRA_ROUTER_ID = "router_id" }
}
