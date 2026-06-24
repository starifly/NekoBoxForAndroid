package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.databinding.LayoutAddEntityBinding
import io.nekohasekai.sagernet.databinding.LayoutProfileBinding
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import moe.matsuri.nb4a.Protocols.getProtocolColor

class ChainSettingsActivity : ProfileSettingsActivity<ChainBean>(R.layout.layout_chain_settings) {

    companion object {
        const val EXTRA_STRATEGY = "chain_strategy"
    }

    private var currentStrategy = ChainBean.STRATEGY_CHAIN

    override fun createEntity() = ChainBean().apply {
        strategy = intent.getIntExtra(EXTRA_STRATEGY, ChainBean.STRATEGY_CHAIN)
        currentStrategy = strategy
    }

    val proxyList = ArrayList<ProxyEntity>()

    override fun ChainBean.init() {
        currentStrategy = strategy
        DataStore.profileName = name
        DataStore.serverProtocol = proxies.joinToString(",")
    }

    override fun ChainBean.serialize() {
        name = DataStore.profileName
        strategy = currentStrategy
        proxies = proxyList.map { it.id }
        initializeDefaultValues()
    }

    override suspend fun saveAndExit() {
        if (proxyList.isEmpty()) {
            onMainDispatcher {
                Toast.makeText(this@ChainSettingsActivity, R.string.profile_empty, Toast.LENGTH_SHORT)
                    .show()
            }
            return
        }
        super.saveAndExit()
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.name_preferences)
    }

    lateinit var configurationList: RecyclerView
    lateinit var configurationAdapter: ProxiesAdapter
    lateinit var layoutManager: LinearLayoutManager

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentStrategy = intent.getIntExtra(EXTRA_STRATEGY, ChainBean.STRATEGY_CHAIN)
        supportActionBar!!.setTitle(
            when (currentStrategy) {
                ChainBean.STRATEGY_WATERFALL -> R.string.waterfall_settings
                ChainBean.STRATEGY_FASTEST -> R.string.fastest_settings
                else -> R.string.chain_settings
            }
        )
        configurationList = findViewById(R.id.configuration_list)
        layoutManager = FixedLinearLayoutManager(configurationList)
        configurationList.layoutManager = layoutManager
        configurationAdapter = ProxiesAdapter()
        configurationList.adapter = configurationAdapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is ProfileHolder) {
                super.getSwipeDirs(recyclerView, viewHolder)
            } else 0

            override fun getDragDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is ProfileHolder) {
                super.getDragDirs(recyclerView, viewHolder)
            } else 0

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                return if (target !is ProfileHolder) false else {
                    configurationAdapter.move(
                        viewHolder.bindingAdapterPosition, target.bindingAdapterPosition
                    )
                    true
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                configurationAdapter.remove(viewHolder.bindingAdapterPosition)
            }

        }).attachToRecyclerView(configurationList)
    }

    override fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        view.rootView.findViewById<RecyclerView>(R.id.recycler_view).apply {
            (layoutParams ?: LinearLayout.LayoutParams(-1, -2)).apply {
                height = -2
                layoutParams = this
            }
        }

        runOnDefaultDispatcher {
            configurationAdapter.reload()
        }
    }

    inner class ProxiesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        suspend fun reload() {
            val idList = DataStore.serverProtocol.split(",")
                .mapNotNull { it.takeIf { it.isNotBlank() }?.toLong() }
            if (idList.isNotEmpty()) {
                val profiles = ProfileManager.getProfiles(idList).map { it.id to it }.toMap()
                for (id in idList) {
                    proxyList.add(profiles[id] ?: continue)
                }
            }
            onMainDispatcher {
                notifyDataSetChanged()
            }
        }

        fun move(from: Int, to: Int) {
            val toMove = proxyList[to - 1]
            proxyList[to - 1] = proxyList[from - 1]
            proxyList[from - 1] = toMove
            notifyItemMoved(from, to)
            DataStore.dirty = true
        }

        fun remove(index: Int) {
            proxyList.removeAt(index - 1)
            notifyItemRemoved(index)
            DataStore.dirty = true
        }

        override fun getItemId(position: Int): Long {
            return if (position == 0) 0 else proxyList[position - 1].id
        }

        override fun getItemViewType(position: Int): Int {
            return if (position == 0) 0 else 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                AddHolder(LayoutAddEntityBinding.inflate(layoutInflater, parent, false))
            } else {
                ProfileHolder(LayoutProfileBinding.inflate(layoutInflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is AddHolder) {
                holder.bind()
            } else if (holder is ProfileHolder) {
                holder.bind(proxyList[position - 1])
            }
        }

        override fun getItemCount(): Int {
            return proxyList.size + 1
        }

    }

    fun testProfileAllowed(profile: ProxyEntity): Boolean {
        if (profile.id == DataStore.editingId) return false
        if (proxyList.withIndex().any { (index, entity) ->
                index != replacing - 1 && entity.id == profile.id
            }) return false

        if (profile.type == ProxyEntity.TYPE_CHAIN && chainContainsDynamicProfile(profile)) {
            return false
        }

        when (currentStrategy) {
            ChainBean.STRATEGY_CHAIN, ChainBean.STRATEGY_FASTEST -> {
                if (profile.type == ProxyEntity.TYPE_WATERFALL ||
                    profile.type == ProxyEntity.TYPE_FASTEST
                ) return false
            }

            ChainBean.STRATEGY_WATERFALL -> {
                if (profile.type == ProxyEntity.TYPE_WATERFALL) return false
            }
        }

        return DataStore.editingId == 0L || !testProfileContains(profile, DataStore.editingId)
    }

    fun chainContainsDynamicProfile(
        profile: ProxyEntity,
        visited: MutableSet<Long> = HashSet(),
    ): Boolean {
        if (!visited.add(profile.id)) return false
        if (profile.type == ProxyEntity.TYPE_WATERFALL ||
            profile.type == ProxyEntity.TYPE_FASTEST
        ) return true
        if (profile.type != ProxyEntity.TYPE_CHAIN) return false
        return ProfileManager.getProfiles(profile.chainBean?.proxies.orEmpty()).any {
            chainContainsDynamicProfile(it, visited)
        }
    }

    fun testProfileContains(
        profile: ProxyEntity,
        profileId: Long,
        visited: MutableSet<Long> = HashSet(),
    ): Boolean {
        if (!visited.add(profile.id)) return false
        if (profile.id == profileId) return true
        val proxies = profile.chainBean?.proxies ?: return false
        if (proxies.contains(profileId)) return true
        for (entity in ProfileManager.getProfiles(proxies)) {
            if (testProfileContains(entity, profileId, visited)) return true
        }
        return false
    }

    var replacing = 0

    val selectProfileForAdd =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { (resultCode, data) ->
            if (resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
                DataStore.dirty = true

                val profile = ProfileManager.getProfile(
                    data!!.getLongExtra(
                        ProfileSelectActivity.EXTRA_PROFILE_ID, 0
                    )
                )!!

                if (!testProfileAllowed(profile)) {
                    onMainDispatcher {
                        MaterialAlertDialogBuilder(this@ChainSettingsActivity).setTitle(R.string.circular_reference)
                            .setMessage(R.string.profile_reference_not_allowed)
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                } else {
                    configurationList.post {
                        if (replacing != 0) {
                            proxyList[replacing - 1] = profile
                            configurationAdapter.notifyItemChanged(replacing)
                        } else {
                            proxyList.add(profile)
                            configurationAdapter.notifyItemInserted(proxyList.size)
                        }
                    }
                }
            }
        }

    inner class AddHolder(val binding: LayoutAddEntityBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.root.setOnClickListener {
                replacing = 0
                selectProfileForAdd.launch(
                    Intent(
                        this@ChainSettingsActivity, ProfileSelectActivity::class.java
                    )
                )
            }
        }
    }

    inner class ProfileHolder(binding: LayoutProfileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        val profileName = binding.profileName
        val profileType = binding.profileType
        val trafficText: TextView = binding.trafficText
        val editButton = binding.edit
        val shareButton = binding.shareIcon

        fun bind(proxyEntity: ProxyEntity) {

            profileName.text = proxyEntity.displayName()
            profileType.text = proxyEntity.displayType()
            profileType.setTextColor(getProtocolColor(proxyEntity.type))

            val rx = proxyEntity.rx
            val tx = proxyEntity.tx

            val showTraffic = rx + tx != 0L
            trafficText.isVisible = showTraffic
            if (showTraffic) {
                trafficText.text = itemView.context.getString(
                    R.string.traffic,
                    Formatter.formatFileSize(itemView.context, tx),
                    Formatter.formatFileSize(itemView.context, rx)
                )
            }

            editButton.setOnClickListener {
                replacing = bindingAdapterPosition
                selectProfileForAdd.launch(Intent(
                    this@ChainSettingsActivity, ProfileSelectActivity::class.java
                ).apply {
                    putExtra(ProfileSelectActivity.EXTRA_SELECTED, proxyEntity)
                })
            }

            shareButton.isVisible = false
        }

    }

}
