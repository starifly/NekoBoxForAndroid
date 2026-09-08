package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.widget.Toolbar
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.RouterGroup
import io.nekohasekai.sagernet.database.RouterGroupRepository
import io.nekohasekai.sagernet.database.SagerDatabase

class RouterGroupSelectActivity : ThemedActivity(R.layout.layout_settings_activity) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.route_proxy_group)
            setDisplayHomeAsUpEnabled(true)
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settings, PickerFragment()).commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    class PickerFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val selected = requireActivity().intent.getLongExtra(EXTRA_SELECTED, 0L)
            val screen = preferenceManager.createPreferenceScreen(requireContext())
            RouterGroupRepository.all().filter { group ->
                group.enabled && SagerDatabase.routerMemberDao.getByRouter(group.id).isNotEmpty()
            }.forEach { group ->
                screen.addPreference(Preference(requireContext()).apply {
                    title = group.name
                    summary = if (group.id == selected) getString(R.string.router_group_selected) else getString(
                        R.string.router_status,
                        getString(if (group.mode == RouterGroup.MODE_URL_TEST) R.string.router_mode_automatic else R.string.router_mode_manual),
                        SagerDatabase.routerMemberDao.getByRouter(group.id).size,
                    )
                    setOnPreferenceClickListener {
                        requireActivity().setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_ROUTER_ID, group.id))
                        requireActivity().finish()
                        true
                    }
                })
            }
            preferenceScreen = screen
        }
    }

    companion object {
        const val EXTRA_SELECTED = "selected"
        const val EXTRA_ROUTER_ID = "router_id"
    }
}
