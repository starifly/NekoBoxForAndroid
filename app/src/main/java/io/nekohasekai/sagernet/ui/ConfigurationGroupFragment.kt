package io.nekohasekai.sagernet.ui

import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.core.graphics.ColorUtils
import androidx.core.os.BundleCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutProfileBinding
import io.nekohasekai.sagernet.databinding.LayoutProfileListBinding
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.ktx.FixedGridLayoutManager
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.scrollTo
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.tryToShow
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor
import kotlin.collections.set

class ConfigurationGroupFragment : Fragment() {

    lateinit var proxyGroup: ProxyGroup
    var selected = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return LayoutProfileListBinding.inflate(inflater).root
    }

    lateinit var undoManager: UndoSnackbarManager<ProxyEntity>
    var adapter: ConfigurationAdapter? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (::proxyGroup.isInitialized) {
            outState.putParcelable("proxyGroup", proxyGroup)
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)

        savedInstanceState?.let { BundleCompat.getParcelable(it, "proxyGroup", ProxyGroup::class.java) }?.also {
            proxyGroup = it
            onViewCreated(requireView(), null)
        }
    }

    private val isEnabled: Boolean
        get() {
            return DataStore.serviceState.let { it.canStop || it == BaseService.State.Stopped }
        }

    lateinit var layoutManager: RecyclerView.LayoutManager
    private lateinit var itemTouchHelper: ItemTouchHelper

    private fun setupItemTouchHelper() {
        if (select) return

        if (::itemTouchHelper.isInitialized) {
            itemTouchHelper.attachToRecyclerView(null)
        }

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, 0) {
            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val dragFlags = if (DataStore.groupLayoutMode == 1) {
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                } else {
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN
                }
                return makeMovementFlags(dragFlags, 0) // No swipe flags
            }

            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                return 0
            }

            override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                return if (isEnabled) {
                    if (DataStore.groupLayoutMode == 1) {
                        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                    } else {
                        ItemTouchHelper.UP or ItemTouchHelper.DOWN
                    }
                } else {
                    0
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val fromPosition = viewHolder.bindingAdapterPosition
                val toPosition = target.bindingAdapterPosition

                if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION) {
                    return false
                }

                adapter?.move(fromPosition, toPosition)
                return true
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                adapter?.commitMove()
            }
        })
        itemTouchHelper.attachToRecyclerView(configurationListView)
    }
    lateinit var configurationListView: RecyclerView

    val select by lazy {
        try {
            (parentFragment as ConfigurationFragment).select
        } catch (e: Exception) {
            Logs.e(e)
            false
        }
    }
    val selectedItem by lazy {
        try {
            (parentFragment as ConfigurationFragment).selectedItem
        } catch (e: Exception) {
            Logs.e(e)
            null
        }
    }

    override fun onResume() {
        super.onResume()

        if (::configurationListView.isInitialized && configurationListView.size == 0) {
            configurationListView.adapter = adapter
            runOnDefaultDispatcher {
                adapter?.reloadProfiles()
            }
        } else if (!::configurationListView.isInitialized) {
            onViewCreated(requireView(), null)
        }
        checkOrderMenu()
        configurationListView.requestFocus()
    }

    fun checkOrderMenu() {
        if (select) return

        val pf = requireParentFragment() as? ToolbarFragment ?: return
        val menu = pf.toolbar.menu
        val origin = menu.findItem(R.id.action_order_origin)
        val byName = menu.findItem(R.id.action_order_by_name)
        val byDelay = menu.findItem(R.id.action_order_by_delay)
        when (proxyGroup.order) {
            GroupOrder.ORIGIN -> {
                origin.isChecked = true
            }

            GroupOrder.BY_NAME -> {
                byName.isChecked = true
            }

            GroupOrder.BY_DELAY -> {
                byDelay.isChecked = true
            }
        }

        fun updateTo(order: Int) {
            if (proxyGroup.order == order) return
            runOnDefaultDispatcher {
                proxyGroup.order = order
                GroupManager.updateGroup(proxyGroup)
            }
        }

        origin.setOnMenuItemClickListener {
            it.isChecked = true
            updateTo(GroupOrder.ORIGIN)
            true
        }
        byName.setOnMenuItemClickListener {
            it.isChecked = true
            updateTo(GroupOrder.BY_NAME)
            true
        }
        byDelay.setOnMenuItemClickListener {
            it.isChecked = true
            updateTo(GroupOrder.BY_DELAY)
            true
        }

        val layoutSingle = menu.findItem(R.id.action_layout_single)
        val layoutDouble = menu.findItem(R.id.action_layout_double)
        when (DataStore.groupLayoutMode) {
            0 -> layoutSingle.isChecked = true
            1 -> layoutDouble.isChecked = true
        }
        layoutSingle.setOnMenuItemClickListener {
            it.isChecked = true
            if (DataStore.groupLayoutMode != 0) {
                DataStore.groupLayoutMode = 0
                (parentFragment as? ConfigurationFragment)?.switchAllGroupFragmentsLayout()
            }
            true
        }
        layoutDouble.setOnMenuItemClickListener {
            it.isChecked = true
            if (DataStore.groupLayoutMode != 1) {
                DataStore.groupLayoutMode = 1
                (parentFragment as? ConfigurationFragment)?.switchAllGroupFragmentsLayout()
            }
            true
        }
    }

    private fun setupLayoutManager() {
        layoutManager = if (DataStore.groupLayoutMode == 1) {
            FixedGridLayoutManager(configurationListView, 2)
        } else {
            FixedLinearLayoutManager(configurationListView)
        }
    }

    fun switchLayoutMode() {
        setupLayoutManager()
        configurationListView.layoutManager = layoutManager

        setupItemTouchHelper()

        adapter?.notifyDataSetChanged()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::proxyGroup.isInitialized) return

        configurationListView = LayoutProfileListBinding.bind(view).configurationList
        setupLayoutManager()
        configurationListView.layoutManager = layoutManager
        adapter = ConfigurationAdapter()
        ProfileManager.addListener(adapter!!)
        GroupManager.addListener(adapter!!)
        configurationListView.adapter = adapter
        configurationListView.setItemViewCacheSize(20)
        FastScrollerBuilder(configurationListView).useMd2Style().build()

        // Hide the docked FAB while scrolling down (so it never overlaps the
        // bottom card) and bring it back on upward scroll or when the list
        // settles. Mirrors the stats bar's hideOnScroll behavior. Only act in
        // stable states (Stopped/Connected) so we don't fight the FAB's own
        // show/hide animation during Connecting/Stopping.
        configurationListView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            private fun stableState(): Boolean = DataStore.serviceState.let {
                it == BaseService.State.Stopped || it == BaseService.State.Connected
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!stableState()) return
                val fab = (activity as? MainActivity)?.binding?.fab ?: return
                if (dy > 4) {
                    fab.hide()
                } else if (dy < -4) {
                    fab.show()
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE && stableState()) {
                    (activity as? MainActivity)?.binding?.fab?.show()
                }
            }
        })

        if (!select) {
            undoManager = UndoSnackbarManager(activity as MainActivity, adapter!!)
            setupItemTouchHelper()
        }
    }

    override fun onDestroy() {
        adapter?.let {
            ProfileManager.removeListener(it)
            GroupManager.removeListener(it)
        }

        super.onDestroy()

        if (!::undoManager.isInitialized) return
        undoManager.flush()
    }

    inner class ConfigurationAdapter :
        RecyclerView.Adapter<ConfigurationHolder>(),
        ProfileManager.Listener,
        GroupManager.Listener,
        UndoSnackbarManager.Interface<ProxyEntity> {

        init {
            setHasStableIds(true)
        }

        var configurationIdList: MutableList<Long> = mutableListOf()
        val configurationList = HashMap<Long, ProxyEntity>()

        private fun getItem(profileId: Long): ProxyEntity {
            var profile = configurationList[profileId]
            if (profile == null) {
                profile = ProfileManager.getProfile(profileId)
                if (profile != null) {
                    configurationList[profileId] = profile
                }
            }
            return profile!!
        }

        private fun getItemAt(index: Int) = getItem(configurationIdList[index])

        private fun hasMiddleRow(p: ProxyEntity): Boolean {
            val showTraffic = p.rx + p.tx != 0L
            var address = p.displayAddress()
            if (p.requireBean().name.isBlank() || !DataStore.alwaysShowAddress) {
                address = ""
            }
            return !((!showTraffic || p.status <= 0) && address.isBlank())
        }

        fun neighbourHasMiddleRow(position: Int): Boolean {
            if (position == RecyclerView.NO_POSITION) return false
            val np = if (position % 2 == 0) position + 1 else position - 1
            if (np < 0 || np >= itemCount) return false
            return try {
                hasMiddleRow(getItemAt(np))
            } catch (e: Exception) {
                false
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConfigurationHolder {
            return ConfigurationHolder(
                LayoutProfileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                ),
            )
        }

        override fun getItemId(position: Int): Long {
            return configurationIdList[position]
        }

        override fun onBindViewHolder(holder: ConfigurationHolder, position: Int) {
            try {
                holder.bind(getItemAt(position))
            } catch (ignored: NullPointerException) { // when group deleted
            }
        }

        override fun getItemCount(): Int {
            return configurationIdList.size
        }

        private val updated = HashSet<ProxyEntity>()

        fun filter(name: String) {
            if (name.isEmpty()) {
                // Clearing the search box fires from onQueryTextChange on the UI thread;
                // reloadProfiles() runs a full-group DB scan (getByGroup), so route it to a
                // background dispatcher (its UI updates are already posted via
                // configurationListView.post). Mirrors the onResume reload pattern.
                runOnDefaultDispatcher { reloadProfiles() }
                return
            }
            configurationIdList.clear()
            val lower = name.lowercase()
            configurationIdList.addAll(
                configurationList.filter {
                    it.value.displayName().lowercase().contains(lower) ||
                        it.value.displayType().lowercase().contains(lower) ||
                        it.value.displayAddress().lowercase().contains(lower)
                }.keys,
            )
            notifyDataSetChanged()
        }

        fun move(from: Int, to: Int) {
            if (from == to) return

            if (DataStore.groupLayoutMode == 1) {
                moveDualColumn(from, to)
            } else {
                moveLinear(from, to)
            }
        }

        private fun moveLinear(from: Int, to: Int) {
            val first = getItemAt(from)
            var previousOrder = first.userOrder
            val (step, range) = if (from < to) {
                Pair(1, from until to)
            } else {
                Pair(
                    -1,
                    to + 1 downTo from,
                )
            }
            for (i in range) {
                val next = getItemAt(i + step)
                val order = next.userOrder
                next.userOrder = previousOrder
                previousOrder = order
                configurationIdList[i] = next.id
                updated.add(next)
            }
            first.userOrder = previousOrder
            configurationIdList[to] = first.id
            updated.add(first)
            notifyItemMoved(from, to)
        }

        private fun moveDualColumn(from: Int, to: Int) {
            val draggedItemId = configurationIdList[from]

            configurationIdList.removeAt(from)
            configurationIdList.add(to, draggedItemId)

            for (i in configurationIdList.indices) {
                val item = getItem(configurationIdList[i])
                val newOrder = (i + 1).toLong()
                if (item.userOrder != newOrder) {
                    item.userOrder = newOrder
                    updated.add(item)
                }
            }

            notifyItemMoved(from, to)
        }

        fun commitMove() = runOnDefaultDispatcher {
            updated.forEach { SagerDatabase.proxyDao.updateProxy(it) }
            updated.clear()
        }

        fun remove(pos: Int) {
            if (pos < 0) return
            configurationIdList.removeAt(pos)
            notifyItemRemoved(pos)
        }

        override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
            for ((index, item) in actions) {
                configurationListView.post {
                    configurationList[item.id] = item
                    configurationIdList.add(index, item.id)
                    notifyItemInserted(index)
                }
            }
        }

        override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
            val profiles = actions.map { it.second }
            runOnDefaultDispatcher {
                for (entity in profiles) {
                    ProfileManager.deleteProfile(entity.groupId, entity.id)
                }
            }
        }

        override suspend fun onAdd(profile: ProxyEntity) {
            if (profile.groupId != proxyGroup.id) return

            configurationListView.post {
                if (::undoManager.isInitialized) {
                    undoManager.flush()
                }
                val pos = itemCount
                configurationList[profile.id] = profile
                configurationIdList.add(profile.id)
                notifyItemInserted(pos)
            }
        }

        override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {
            if (profile.groupId != proxyGroup.id) return
            val index = configurationIdList.indexOf(profile.id)
            if (index < 0) return
            configurationListView.post {
                if (::undoManager.isInitialized) {
                    undoManager.flush()
                }
                val oldProfile = configurationList[profile.id]
                configurationList[profile.id] = profile
                notifyItemChanged(index)
                if (noTraffic && oldProfile != null) {
                    runOnDefaultDispatcher {
                        onUpdated(
                            TrafficData(
                                id = profile.id,
                                rx = oldProfile.rx,
                                tx = oldProfile.tx,
                            ),
                        )
                    }
                }
            }
        }

        override suspend fun onUpdated(data: TrafficData) {
            try {
                val index = configurationIdList.indexOf(data.id)
                if (index != -1) {
                    val holder = layoutManager.findViewByPosition(index)
                        ?.let { configurationListView.getChildViewHolder(it) } as ConfigurationHolder?
                    if (holder != null) {
                        onMainDispatcher {
                            holder.bind(holder.entity, data)
                        }
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
            }
        }

        override suspend fun onUpdated(data: List<TrafficData>) {
            try {
                val positions = HashMap<Long, Int>(configurationIdList.size)
                configurationIdList.forEachIndexed { index, id -> positions[id] = index }
                val updates = ArrayList<Pair<ConfigurationHolder, TrafficData>>()
                for (item in data) {
                    val index = positions[item.id] ?: continue
                    val holder = layoutManager.findViewByPosition(index)
                        ?.let { configurationListView.getChildViewHolder(it) } as ConfigurationHolder?
                    if (holder != null) updates.add(holder to item)
                }
                if (updates.isNotEmpty()) {
                    onMainDispatcher {
                        for ((holder, item) in updates) holder.bind(holder.entity, item)
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
            }
        }

        override suspend fun onRemoved(groupId: Long, profileId: Long) {
            if (groupId != proxyGroup.id) return
            val index = configurationIdList.indexOf(profileId)
            if (index < 0) return

            configurationListView.post {
                configurationIdList.removeAt(index)
                configurationList.remove(profileId)
                notifyItemRemoved(index)
            }
        }

        override suspend fun groupAdd(group: ProxyGroup) = Unit
        override suspend fun groupRemoved(groupId: Long) = Unit

        override suspend fun groupUpdated(group: ProxyGroup) {
            if (group.id != proxyGroup.id) return
            proxyGroup = group
            reloadProfiles()
        }

        override suspend fun groupUpdated(groupId: Long) {
            if (groupId != proxyGroup.id) return
            proxyGroup = SagerDatabase.groupDao.getById(groupId)!!
            reloadProfiles()
        }

        fun reloadProfiles() {
            var newProfiles = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
            when (proxyGroup.order) {
                GroupOrder.BY_NAME -> {
                    newProfiles = newProfiles.sortedBy { it.displayName() }
                }

                GroupOrder.BY_DELAY -> {
                    newProfiles =
                        newProfiles.sortedBy { if (it.status == 1) it.ping else 114514 }
                }
            }

            configurationList.clear()
            configurationList.putAll(newProfiles.associateBy { it.id })
            val newProfileIds = newProfiles.map { it.id }

            var selectedProfileIndex = -1

            if (selected) {
                val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                selectedProfileIndex = newProfileIds.indexOf(selectedProxy)
            }

            configurationListView.post {
                configurationIdList.clear()
                configurationIdList.addAll(newProfileIds)
                notifyDataSetChanged()

                if (selectedProfileIndex != -1) {
                    configurationListView.scrollTo(selectedProfileIndex, true)
                } else if (newProfiles.isNotEmpty()) {
                    configurationListView.scrollTo(0, true)
                }
            }
        }
    }

    val profileAccess = Mutex()
    val reloadAccess = Mutex()

    inner class ConfigurationHolder(val binding: LayoutProfileBinding) :
        RecyclerView.ViewHolder(binding.root),
        PopupMenu.OnMenuItemClickListener {

        val view: View get() = binding.root

        lateinit var entity: ProxyEntity

        private fun showShareMenu(anchor: View, proxyEntity: ProxyEntity) {
            val popup = PopupMenu(requireContext(), anchor)
            popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)

            when {
                !proxyEntity.haveStandardLink() -> {
                    popup.menu.findItem(R.id.action_group_qr).subMenu?.removeItem(R.id.action_standard_qr)
                    popup.menu.findItem(R.id.action_group_clipboard).subMenu?.removeItem(
                        R.id.action_standard_clipboard,
                    )
                }

                !proxyEntity.haveLink() -> {
                    popup.menu.removeItem(R.id.action_group_qr)
                    popup.menu.removeItem(R.id.action_group_clipboard)
                }
            }

            popup.setOnMenuItemClickListener(this)
            popup.show()
        }

        val profileName: TextView = binding.profileName
        val profileType: TextView = binding.profileType
        val profileAddress: TextView = binding.profileAddress
        val profileStatus: TextView = binding.profileStatus

        val trafficText: TextView = binding.trafficText
        private val card = view as MaterialCardView
        val editButton: ImageView = binding.edit
        val doubleColumnMenuButton: ImageView = binding.doubleColumnMenu
        val shareLayout: LinearLayout = binding.share
        val shareLayer: LinearLayout = binding.shareLayer
        val shareButton: ImageView = binding.shareIcon
        val removeButton: ImageView = binding.remove

        private fun applySelected(selected: Boolean) {
            val ctx = card.context
            val primary = ctx.getColorAttr(R.attr.colorPrimary)
            val surface = ctx.getColorAttr(R.attr.colorSurface)
            card.strokeWidth = ctx.resources.getDimensionPixelSize(
                if (selected) R.dimen.card_stroke_width_selected else R.dimen.card_stroke_width,
            )
            card.strokeColor =
                if (selected) primary else ctx.getColour(R.color.card_stroke)
            card.setCardBackgroundColor(
                if (selected) {
                    ColorUtils.compositeColors(
                        ColorUtils.setAlphaComponent(primary, 26),
                        surface,
                    )
                } else {
                    surface
                },
            )
        }

        fun bind(proxyEntity: ProxyEntity, trafficData: TrafficData? = null) {
            val pf = parentFragment as? ConfigurationFragment ?: return

            entity = proxyEntity

            if (select) {
                view.setOnClickListener {
                    (requireActivity() as ConfigurationFragment.SelectCallback).returnProfile(proxyEntity.id)
                }
            } else {
                view.setOnClickListener {
                    runOnDefaultDispatcher {
                        var update: Boolean
                        var lastSelected: Long
                        profileAccess.withLock {
                            update = DataStore.selectedProxy != proxyEntity.id
                            lastSelected = DataStore.selectedProxy
                            DataStore.selectedProxy = proxyEntity.id
                            onMainDispatcher {
                                applySelected(true)
                            }
                        }

                        if (update) {
                            ProfileManager.postUpdate(lastSelected)
                            if (DataStore.serviceState.canStop && reloadAccess.tryLock()) {
                                try {
                                    SagerNet.reloadService(proxyEntity.id)
                                } finally {
                                    reloadAccess.unlock()
                                }
                            }
                        } else if (SagerNet.isTv) {
                            if (DataStore.serviceState.started) {
                                SagerNet.stopService()
                            } else {
                                SagerNet.startService()
                            }
                        }
                    }
                }
            }

            profileName.text = proxyEntity.displayName()
            profileName.setTextColor(requireContext().getColorAttr(R.attr.profileNameColor))
            profileType.text = proxyEntity.displayType()
            profileType.setTextColor(requireContext().getProtocolColor(proxyEntity.type))

            var rx = proxyEntity.rx
            var tx = proxyEntity.tx
            if (trafficData != null) {
                // use new data
                tx = trafficData.tx
                rx = trafficData.rx
            }

            val showTraffic = rx + tx != 0L
            trafficText.isVisible = showTraffic
            if (showTraffic) {
                trafficText.text = view.context.getString(
                    R.string.traffic,
                    Formatter.formatFileSize(view.context, tx),
                    Formatter.formatFileSize(view.context, rx),
                )
            }

            // Read-only lifetime (all-time) totals surfaced as a long-press tooltip on the traffic
            // text (schema v12; accumulated by TrafficLooper). Zero layout change; shown only when
            // there is accumulated history.
            if (proxyEntity.lifetimeRx + proxyEntity.lifetimeTx > 0L) {
                TooltipCompat.setTooltipText(
                    trafficText,
                    view.context.getString(
                        R.string.lifetime_traffic,
                        Formatter.formatFileSize(view.context, proxyEntity.lifetimeTx),
                        Formatter.formatFileSize(view.context, proxyEntity.lifetimeRx),
                    ),
                )
            } else {
                TooltipCompat.setTooltipText(trafficText, null)
            }

            var address = proxyEntity.displayAddress()
            if (showTraffic && address.length >= 30) {
                address = address.substring(0, 27) + "..."
            }

            if (proxyEntity.requireBean().name.isBlank() || !pf.alwaysShowAddress) {
                address = ""
            }

            profileAddress.text = address
            val trafficRowEmpty =
                (!showTraffic || proxyEntity.status <= 0) && address.isBlank()
            (trafficText.parent as View).visibility = when {
                !trafficRowEmpty -> View.VISIBLE
                DataStore.groupLayoutMode == 1 && adapter?.neighbourHasMiddleRow(
                    bindingAdapterPosition,
                ) == true -> View.INVISIBLE
                else -> View.GONE
            }

            if (proxyEntity.status <= 0) {
                if (showTraffic) {
                    profileStatus.text = trafficText.text
                    profileStatus.setTextColor(requireContext().getColorAttr(android.R.attr.textColorSecondary))
                    trafficText.text = ""
                } else {
                    profileStatus.text = ""
                }
            } else if (proxyEntity.status == 1) {
                profileStatus.text = getString(R.string.available, proxyEntity.ping)
                profileStatus.setTextColor(requireContext().getColour(R.color.material_green_500))
            } else {
                profileStatus.setTextColor(requireContext().getColorAttr(R.attr.testFailColor))
                if (proxyEntity.status == 2) {
                    profileStatus.text = proxyEntity.error
                }
            }

            if (proxyEntity.status == 3) {
                val err = proxyEntity.error ?: "<?>"
                val msg = Protocols.genFriendlyMsg(err)
                profileStatus.text = if (msg != err) msg else getString(R.string.unavailable)
                profileStatus.setOnClickListener {
                    alert(err).tryToShow()
                }
                profileStatus.isFocusable = false
            } else {
                profileStatus.setOnClickListener { }
                profileStatus.isFocusable = false
            }

            editButton.setOnClickListener {
                it.context.startActivity(
                    proxyEntity.settingIntent(
                        it.context,
                        proxyGroup.type == GroupType.SUBSCRIPTION,
                    ),
                )
            }

            removeButton.setOnClickListener {
                adapter?.let { adapter ->
                    val index = adapter.configurationIdList.indexOf(proxyEntity.id)
                    if (DataStore.confirmProfileDelete) {
                        AlertDialog.Builder(requireContext())
                            .setTitle(R.string.delete_confirm_prompt)
                            // .setMessage(getString(R.string.delete_confirm_prompt))
                            .setPositiveButton(R.string.yes) { dialog: DialogInterface, which: Int ->
                                adapter.remove(index)
                                undoManager.remove(index to proxyEntity)
                            }
                            .setNegativeButton(R.string.no, null)
                            .show()
                    } else {
                        adapter.remove(index)
                        undoManager.remove(index to proxyEntity)
                    }
                }
            }

            doubleColumnMenuButton.setOnClickListener {
                val popup = PopupMenu(requireContext(), it)
                popup.menuInflater.inflate(R.menu.double_column_item_menu, popup.menu)
                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.action_edit -> {
                            it.context.startActivity(
                                proxyEntity.settingIntent(
                                    it.context,
                                    proxyGroup.type == GroupType.SUBSCRIPTION,
                                ),
                            )
                            true
                        }
                        R.id.action_share -> {
                            showShareMenu(it, proxyEntity)
                            true
                        }
                        R.id.action_delete -> {
                            adapter?.let { adapter ->
                                val index = adapter.configurationIdList.indexOf(proxyEntity.id)
                                if (DataStore.confirmProfileDelete) {
                                    AlertDialog.Builder(requireContext())
                                        .setTitle(R.string.delete_confirm_prompt)
                                        .setPositiveButton(R.string.yes) { dialog: DialogInterface, which: Int ->
                                            adapter.remove(index)
                                            undoManager.remove(index to proxyEntity)
                                        }
                                        .setNegativeButton(R.string.no, null)
                                        .show()
                                } else {
                                    adapter.remove(index)
                                    undoManager.remove(index to proxyEntity)
                                }
                            }
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
            }

            val selectOrChain = select || proxyEntity.type == ProxyEntity.TYPE_CHAIN
            val isDoubleColumn = DataStore.groupLayoutMode == 1

            if (isDoubleColumn) {
                editButton.isGone = true
                shareLayout.isGone = true
                removeButton.isGone = true
                doubleColumnMenuButton.isVisible = true
            } else {
                shareLayout.isGone = selectOrChain
                editButton.isGone = select
                removeButton.isGone = select
                doubleColumnMenuButton.isGone = true
            }

            runOnDefaultDispatcher {
                val selected = (selectedItem?.id ?: DataStore.selectedProxy) == proxyEntity.id
                val started =
                    selected && DataStore.serviceState.started && DataStore.currentProfile == proxyEntity.id
                onMainDispatcher {
                    editButton.isEnabled = !started
                    removeButton.isEnabled = !started
                    applySelected(selected)
                }

                if (!(select || proxyEntity.type == ProxyEntity.TYPE_CHAIN)) {
                    onMainDispatcher {
                        shareLayer.setBackgroundColor(Color.TRANSPARENT)
                        shareButton.setImageResource(R.drawable.ic_social_share)
                        shareButton.setColorFilter(Color.GRAY)
                        shareButton.isVisible = true

                        shareLayout.setOnClickListener {
                            showShareMenu(it, proxyEntity)
                        }
                    }
                }
            }
        }

        var currentName = ""
        fun showCode(link: String) {
            QRCodeDialog(link, currentName).showAllowingStateLoss(parentFragmentManager)
        }

        fun export(link: String) {
            val success = SagerNet.trySetPrimaryClip(link)
            (activity as MainActivity).snackbar(
                if (success) R.string.action_export_msg else R.string.action_export_err,
            )
                .show()
        }

        override fun onMenuItemClick(item: MenuItem): Boolean {
            try {
                currentName = entity.displayName()!!
                when (item.itemId) {
                    R.id.action_standard_qr -> showCode(entity.toStdLink())
                    R.id.action_standard_clipboard -> export(entity.toStdLink())
                    R.id.action_universal_qr -> showCode(entity.requireBean().toUniversalLink())
                    R.id.action_universal_clipboard -> export(
                        entity.requireBean().toUniversalLink(),
                    )

                    R.id.action_config_export_clipboard -> export(entity.exportConfig().first)
                    R.id.action_config_export_file -> {
                        val cfg = entity.exportConfig()
                        DataStore.serverConfig = cfg.first
                        startFilesForResult(
                            (parentFragment as ConfigurationFragment).exportConfig,
                            cfg.second,
                        )
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
                (activity as MainActivity).snackbar(e.readableMessage).show()
                return true
            }
            return true
        }
    }
}
