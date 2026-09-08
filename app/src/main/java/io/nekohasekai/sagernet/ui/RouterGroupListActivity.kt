package io.nekohasekai.sagernet.ui

import android.os.Bundle
import androidx.appcompat.widget.Toolbar
import io.nekohasekai.sagernet.R

class RouterGroupListActivity : ThemedActivity(R.layout.layout_settings_activity) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setTitle(R.string.router_groups_title)
            setDisplayHomeAsUpEnabled(true)
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings, RouterGroupListFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
