package io.nekohasekai.sagernet.ui

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
import androidx.recyclerview.widget.DiffUtil
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
import io.nekohasekai.sagernet.ktx.onDefaultDispatcher
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.scrollTo
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.tryToShow
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor
import java.util.Objects
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.set

private data class ProfileListSubmission(
    val ids: List<Long>,
    val profiles: Map<Long, ProxyEntity>,
    val stamps: Map<Long, ProfileRowStamp>,
    val scrollIndex: Int = -1,
)

class ConfigurationGroupFragment : Fragment() {

    lateinit var proxyGroup: ProxyGroup
    var selected = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return LayoutProfileListBinding.inflate(inflater).root
    }

    var undoManager: UndoSnackbarManager<ProxyEntity>? = null
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
                return if (isEnabled && adapter?.canDrag() == true) {
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

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) adapter?.beginDrag()
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

        adapter?.onLayoutModeChanged()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::proxyGroup.isInitialized) return

        configurationListView = LayoutProfileListBinding.bind(view).configurationList
        setupLayoutManager()
        configurationListView.layoutManager = layoutManager
        adapter?.let {
            ProfileManager.removeListener(it)
            GroupManager.removeListener(it)
            it.dispose()
        }
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

    override fun onDestroyView() {
        undoManager?.flush()
        undoManager = null
        adapter?.let {
            ProfileManager.removeListener(it)
            GroupManager.removeListener(it)
            it.dispose()
        }
        adapter = null
        if (::itemTouchHelper.isInitialized) itemTouchHelper.attachToRecyclerView(null)
        super.onDestroyView()
    }

    override fun onDestroy() {
        adapter?.let {
            ProfileManager.removeListener(it)
            GroupManager.removeListener(it)
        }

        super.onDestroy()

        undoManager?.flush()
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
        private val configurationStamps = HashMap<Long, ProfileRowStamp>()
        private val displayPositions = HashMap<Long, Int>()
        private val masterIds = ArrayList<Long>()
        private val masterProfiles = HashMap<Long, ProxyEntity>()
        private val masterStamps = HashMap<Long, Int>()
        private val hostView = configurationListView
        private val reloadGeneration = AtomicLong()
        private var displayGeneration = 0L
        private var diffJob: Job? = null
        private var filterQuery = ""
        private var filterPending = false
        private var masterUpdatePending = false
        private var displayPending = false
        private var dragInProgress = false
        private var disposed = false
        private val filterRunnable = Runnable {
            if (!disposed) {
                filterPending = false
                submitMasterList()
            }
        }
        private val masterUpdateRunnable = Runnable {
            if (!disposed) {
                masterUpdatePending = false
                sortMasterProfiles()
                submitMasterList()
            }
        }

        // Keep this aligned with ConfigurationHolder.bind. TrafficData updates both the entities
        // and these cached stamps directly, without scheduling a list diff for every traffic tick.
        private fun contentStamp(profile: ProxyEntity) = Objects.hash(
            profile.displayName(),
            profile.displayType(),
            profile.displayAddress(),
            profile.requireBean().name,
            profile.ping,
            profile.status,
            profile.error,
            profile.tx,
            profile.rx,
            profile.lifetimeTx,
            profile.lifetimeRx,
        )

        private fun profileStateStamp(contentStamp: Int, profileId: Long): Int {
            val host = parentFragment as? ConfigurationFragment
            return Objects.hash(
                contentStamp,
                host?.isSelectedProfile(profileId) == true,
                host?.isRunningProfile(profileId) == true,
            )
        }

        private fun profileStateStamp(profile: ProxyEntity) = profileStateStamp(contentStamp(profile), profile.id)

        private fun matchesFilter(profile: ProxyEntity): Boolean {
            if (filterQuery.isEmpty()) return true
            val lower = filterQuery.lowercase()
            return profile.displayName().lowercase().contains(lower) ||
                profile.displayType().lowercase().contains(lower) ||
                profile.displayAddress().lowercase().contains(lower)
        }

        private fun rebuildDisplayPositions() {
            displayPositions.clear()
            configurationIdList.forEachIndexed { index, id -> displayPositions[id] = index }
        }

        private fun applySubmission(submission: ProfileListSubmission) {
            configurationIdList.clear()
            configurationIdList.addAll(submission.ids)
            configurationList.clear()
            configurationList.putAll(submission.profiles)
            configurationStamps.clear()
            configurationStamps.putAll(submission.stamps)
            pendingRemovalIds.removeAll { id -> id !in configurationList && id !in masterProfiles }
            rebuildDisplayPositions()
        }

        private fun submit(submission: ProfileListSubmission) {
            if (disposed || dragInProgress) return
            val generation = ++displayGeneration
            displayPending = true
            val oldIds = configurationIdList.toList()
            val oldStamps = configurationStamps.toMap()
            if (oldIds.isEmpty()) {
                applySubmission(submission)
                displayPending = false
                notifyDataSetChanged()
                scrollAfterSubmission(submission)
                return
            }
            diffJob?.cancel()
            diffJob = runOnDefaultDispatcher {
                val diff = try {
                    DiffUtil.calculateDiff(
                        ProfileDiffCallback(oldIds, submission.ids, oldStamps, submission.stamps),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        if (disposed || dragInProgress || generation != displayGeneration) {
                            return@onMainDispatcher
                        }
                        applySubmission(submission)
                        displayPending = false
                        // Exceptional fallback only: keep RecyclerView state consistent if DiffUtil
                        // rejects an otherwise valid immutable snapshot.
                        notifyDataSetChanged()
                        scrollAfterSubmission(submission)
                    }
                    return@runOnDefaultDispatcher
                }
                onMainDispatcher {
                    if (disposed || dragInProgress || generation != displayGeneration) return@onMainDispatcher
                    applySubmission(submission)
                    displayPending = false
                    diff.dispatchUpdatesTo(this@ConfigurationAdapter)
                    scrollAfterSubmission(submission)
                }
            }
        }

        private fun scrollAfterSubmission(submission: ProfileListSubmission) {
            if (submission.scrollIndex >= 0) {
                configurationListView.scrollTo(submission.scrollIndex, true)
            }
        }

        private fun submitMasterList(scrollIndex: Int = -1) {
            if (disposed || dragInProgress) return
            val ids = masterIds.filter { id -> masterProfiles[id]?.let(::matchesFilter) == true }
            val profiles = masterProfiles.toMap()
            submit(
                ProfileListSubmission(
                    ids = ids,
                    profiles = profiles,
                    stamps = buildDisplayStamps(ids, profiles),
                    scrollIndex = scrollIndex.coerceAtMost(ids.lastIndex),
                ),
            )
        }

        private fun sortMasterProfiles() {
            val sorted = when (proxyGroup.order) {
                GroupOrder.BY_NAME -> masterProfiles.values.sortedBy { it.displayName() }
                GroupOrder.BY_DELAY -> masterProfiles.values.sortedBy {
                    if (it.status == 1) it.ping else 114514
                }
                else -> masterProfiles.values.sortedBy { it.userOrder }
            }
            masterIds.clear()
            masterIds.addAll(sorted.map { it.id })
        }

        private fun scheduleMasterUpdate() {
            ++displayGeneration
            diffJob?.cancel()
            displayPending = false
            masterUpdatePending = true
            hostView.removeCallbacks(masterUpdateRunnable)
            hostView.postDelayed(masterUpdateRunnable, 50L)
        }

        fun canDrag() = !disposed &&
            filterQuery.isEmpty() &&
            !filterPending &&
            !masterUpdatePending &&
            !displayPending &&
            configurationIdList == masterIds

        fun beginDrag() {
            if (!canDrag()) return
            reloadGeneration.incrementAndGet()
            hostView.removeCallbacks(filterRunnable)
            hostView.removeCallbacks(masterUpdateRunnable)
            filterPending = false
            masterUpdatePending = false
            displayPending = false
            dragInProgress = true
            ++displayGeneration
            diffJob?.cancel()
        }

        fun dispose() {
            disposed = true
            dragInProgress = false
            reloadGeneration.incrementAndGet()
            ++displayGeneration
            diffJob?.cancel()
            filterPending = false
            masterUpdatePending = false
            displayPending = false
            hostView.removeCallbacks(filterRunnable)
            hostView.removeCallbacks(masterUpdateRunnable)
        }

        private fun getItem(profileId: Long): ProxyEntity = configurationList[profileId]!!

        private fun getItemAt(index: Int) = getItem(configurationIdList[index])

        private fun hasMiddleRow(profile: ProxyEntity): Boolean {
            val showTraffic = profile.rx + profile.tx != 0L
            val address = if (
                profile.requireBean().name.isNotBlank() &&
                (parentFragment as? ConfigurationFragment)?.alwaysShowAddress == true
            ) {
                profile.displayAddress()
            } else {
                ""
            }
            return !((!showTraffic || profile.status <= 0) && address.isBlank())
        }

        private fun displaySpanCount() = (layoutManager as? FixedGridLayoutManager)?.spanCount ?: 1

        private fun buildDisplayStamps(ids: List<Long>, profiles: Map<Long, ProxyEntity>): Map<Long, ProfileRowStamp> {
            val baseStamps = ids.associateWith { id ->
                profiles[id]?.let(::profileStateStamp)
                    ?: configurationStamps[id]?.content
                    ?: masterStamps[id]
                    ?: 0
            }
            return buildProfileRowStamps(ids, baseStamps, displaySpanCount()) { id ->
                profiles[id]?.let(::hasMiddleRow) == true
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
                val id = configurationIdList[position]
                val rowStamp = configurationStamps[id] ?: return
                holder.bind(getItemAt(position), rowStamp.reserveMiddleRow)
            } catch (ignored: NullPointerException) { // when group deleted
            }
        }

        override fun getItemCount(): Int {
            return configurationIdList.size
        }

        fun refreshProfileState(profileIds: Set<Long>) {
            if (disposed) return
            var changed = false
            profileIds.forEach { profileId ->
                masterProfiles[profileId]?.let { profile ->
                    masterStamps[profileId] = profileStateStamp(profile)
                    changed = true
                }
            }
            if (changed) submitMasterList()
        }

        fun onLayoutModeChanged() {
            if (disposed) return
            ++displayGeneration
            diffJob?.cancel()
            displayPending = false
            submitMasterList()
        }

        fun profileById(profileId: Long): ProxyEntity? {
            if (profileId in pendingRemovalIds) return null
            return masterProfiles[profileId] ?: configurationList[profileId]
        }

        private val updated = HashSet<ProxyEntity>()
        private val pendingRemovalIds = HashSet<Long>()

        fun filter(name: String) {
            if (disposed) return
            filterQuery = name
            hostView.removeCallbacks(filterRunnable)
            ++displayGeneration
            diffJob?.cancel()
            displayPending = false
            if (name.isEmpty()) {
                filterPending = false
                submitMasterList()
            } else {
                filterPending = true
                hostView.postDelayed(filterRunnable, 150L)
            }
        }

        fun move(from: Int, to: Int) {
            if (!dragInProgress || from == to) return

            if (DataStore.groupLayoutMode == 1) {
                moveDualColumn(from, to)
            } else {
                moveLinear(from, to)
            }
            masterIds.clear()
            masterIds.addAll(configurationIdList)
            rebuildDisplayPositions()
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

        fun commitMove() {
            val snapshot = updated.toList()
            updated.clear()
            dragInProgress = false
            if (snapshot.isEmpty()) {
                submitMasterList()
                return
            }
            displayPending = true
            runOnDefaultDispatcher {
                try {
                    SagerDatabase.proxyDao.updateProxy(snapshot)
                } catch (e: Exception) {
                    Logs.w(e)
                }
                try {
                    reloadProfiles()
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        if (!disposed) displayPending = false
                    }
                }
            }
        }

        fun removeById(profileId: Long): Pair<Int, ProxyEntity>? {
            if (disposed || profileId in pendingRemovalIds) return null
            val removed = findItemById(configurationIdList, configurationList, profileId) ?: return null
            reloadGeneration.incrementAndGet()
            pendingRemovalIds.add(profileId)
            masterIds.remove(profileId)
            masterProfiles.remove(profileId)
            masterStamps.remove(profileId)
            submitMasterList()
            return removed
        }

        fun removeProfiles(profileIds: Collection<Long>) {
            if (profileIds.isEmpty()) return
            reloadGeneration.incrementAndGet()
            val ids = profileIds.toHashSet()
            pendingRemovalIds.addAll(ids)
            masterIds.removeAll(ids)
            ids.forEach {
                masterProfiles.remove(it)
                masterStamps.remove(it)
            }
            submitMasterList()
        }

        override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
            reloadGeneration.incrementAndGet()
            for ((_, item) in actions) {
                pendingRemovalIds.remove(item.id)
                masterProfiles[item.id] = item
                masterStamps[item.id] = profileStateStamp(item)
            }
            sortMasterProfiles()
            submitMasterList()
        }

        override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
            val profiles = actions.map { it.second }
            val profileIds = profiles.map { it.id }
            runOnDefaultDispatcher {
                try {
                    for (entity in profiles) {
                        ProfileManager.deleteProfile(entity.groupId, entity.id)
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher { pendingRemovalIds.removeAll(profileIds) }
                    reloadProfiles()
                }
            }
        }

        override suspend fun onAdd(profile: ProxyEntity) {
            if (profile.groupId != proxyGroup.id) return
            onMainDispatcher {
                if (disposed) return@onMainDispatcher
                reloadGeneration.incrementAndGet()
                masterProfiles[profile.id] = profile
                masterStamps[profile.id] = profileStateStamp(profile)
                scheduleMasterUpdate()
            }
        }

        override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {
            if (profile.groupId != proxyGroup.id) return
            onMainDispatcher {
                if (disposed || profile.id in pendingRemovalIds) return@onMainDispatcher
                reloadGeneration.incrementAndGet()
                val oldProfile = masterProfiles[profile.id] ?: configurationList[profile.id]
                if (noTraffic && oldProfile != null) {
                    profile.rx = oldProfile.rx
                    profile.tx = oldProfile.tx
                }
                masterProfiles[profile.id] = profile
                masterStamps[profile.id] = profileStateStamp(profile)
                scheduleMasterUpdate()
            }
        }

        private fun applyTraffic(data: TrafficData) {
            masterProfiles[data.id]?.let {
                it.rx = data.rx
                it.tx = data.tx
                masterStamps[data.id] = profileStateStamp(it)
            }
            configurationList[data.id]?.let {
                it.rx = data.rx
                it.tx = data.tx
            }
        }

        private fun rebindVisible(position: Int) {
            val expectedId = configurationIdList.getOrNull(position) ?: return
            val profile = configurationList[expectedId] ?: return
            val rowStamp = configurationStamps[expectedId] ?: return
            val holder = layoutManager.findViewByPosition(position)
                ?.let { configurationListView.getChildViewHolder(it) } as? ConfigurationHolder
                ?: return
            if (holder.bindingAdapterPosition != position || !holder.isBoundTo(expectedId)) return
            holder.bind(profile, rowStamp.reserveMiddleRow)
        }

        private fun applyTrafficUpdates(updates: List<TrafficData>) {
            val oldStamps = configurationStamps.toMap()
            val changedIds = HashSet<Long>()
            updates.forEach { data ->
                applyTraffic(data)
                if (data.id in displayPositions) changedIds.add(data.id)
            }

            if (diffJob?.isActive == true) {
                ++displayGeneration
                diffJob?.cancel()
                displayPending = false
                submitMasterList()
                return
            }

            val newStamps = buildDisplayStamps(configurationIdList, configurationList)
            configurationStamps.clear()
            configurationStamps.putAll(newStamps)
            configurationIdList.forEachIndexed { position, id ->
                if (id in changedIds || oldStamps[id] != newStamps[id]) {
                    rebindVisible(position)
                }
            }
        }

        override suspend fun onUpdated(data: TrafficData) {
            try {
                onMainDispatcher {
                    if (!disposed) applyTrafficUpdates(listOf(data))
                }
            } catch (e: Exception) {
                Logs.w(e)
            }
        }

        override suspend fun onUpdated(data: List<TrafficData>) {
            try {
                onMainDispatcher {
                    if (!disposed) applyTrafficUpdates(data)
                }
            } catch (e: Exception) {
                Logs.w(e)
            }
        }

        override suspend fun onRemoved(groupId: Long, profileId: Long) {
            if (groupId != proxyGroup.id) return
            onMainDispatcher {
                if (disposed) return@onMainDispatcher
                reloadGeneration.incrementAndGet()
                pendingRemovalIds.add(profileId)
                masterIds.remove(profileId)
                masterProfiles.remove(profileId)
                masterStamps.remove(profileId)
                submitMasterList()
            }
        }

        override suspend fun groupAdd(group: ProxyGroup) = Unit
        override suspend fun groupRemoved(groupId: Long) = Unit

        override suspend fun groupUpdated(group: ProxyGroup) {
            if (group.id != proxyGroup.id) return
            onMainDispatcher { proxyGroup = group }
            reloadProfiles()
        }

        override suspend fun groupUpdated(groupId: Long) {
            if (groupId != proxyGroup.id) return
            val group = SagerDatabase.groupDao.getById(groupId) ?: return
            onMainDispatcher { proxyGroup = group }
            reloadProfiles()
        }

        suspend fun reloadProfiles() {
            val generation = reloadGeneration.incrementAndGet()
            val groupId = proxyGroup.id
            val order = proxyGroup.order
            val (newProfiles, newProfileIds, baseStamps) = onDefaultDispatcher {
                var profiles = SagerDatabase.proxyDao.getByGroup(groupId)
                profiles = when (order) {
                    GroupOrder.BY_NAME -> profiles.sortedBy { it.displayName() }
                    GroupOrder.BY_DELAY -> profiles.sortedBy {
                        if (it.status == 1) it.ping else 114514
                    }
                    else -> profiles
                }
                Triple(
                    profiles.associateBy { it.id },
                    profiles.map { it.id },
                    profiles.associate { it.id to contentStamp(it) },
                )
            }
            onMainDispatcher {
                if (disposed || generation != reloadGeneration.get()) return@onMainDispatcher
                hostView.removeCallbacks(masterUpdateRunnable)
                masterUpdatePending = false
                masterIds.clear()
                masterIds.addAll(newProfileIds.filterNot(pendingRemovalIds::contains))
                masterProfiles.clear()
                masterIds.forEach { id ->
                    newProfiles[id]?.let { masterProfiles[id] = it }
                }
                masterStamps.clear()
                masterIds.forEach { id ->
                    baseStamps[id]?.let { stamp ->
                        masterStamps[id] = profileStateStamp(stamp, id)
                    }
                }
                val displayedIds = masterIds.filter { id -> masterProfiles[id]?.let(::matchesFilter) == true }
                val selectedId = selectedItem?.id ?: DataStore.selectedProxy
                val selectedIndex = if (selected) displayedIds.indexOf(selectedId) else -1
                val scrollIndex = if (selectedIndex >= 0) {
                    selectedIndex
                } else if (displayedIds.isNotEmpty()) {
                    0
                } else {
                    -1
                }
                submitMasterList(scrollIndex)
            }
        }
    }

    val profileAccess = Mutex()
    val reloadAccess = Mutex()

    inner class ConfigurationHolder(val binding: LayoutProfileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        val view: View get() = binding.root

        lateinit var entity: ProxyEntity

        private fun showShareMenu(anchor: View, profileId: Long) {
            val profile = adapter?.profileById(profileId) ?: return
            val popup = PopupMenu(requireContext(), anchor)
            popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)

            when {
                !profile.haveStandardLink() -> {
                    popup.menu.findItem(R.id.action_group_qr).subMenu?.removeItem(R.id.action_standard_qr)
                    popup.menu.findItem(R.id.action_group_clipboard).subMenu?.removeItem(
                        R.id.action_standard_clipboard,
                    )
                }

                !profile.haveLink() -> {
                    popup.menu.removeItem(R.id.action_group_qr)
                    popup.menu.removeItem(R.id.action_group_clipboard)
                }
            }

            popup.setOnMenuItemClickListener { handleShareMenu(it, profileId) }
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

        init {
            view.setOnClickListener {
                val profileId = entity.id
                if (select) {
                    (requireActivity() as ConfigurationFragment.SelectCallback).returnProfile(profileId)
                } else {
                    selectProfile(profileId)
                }
            }
            profileStatus.setOnClickListener {
                val profile = adapter?.profileById(entity.id) ?: return@setOnClickListener
                if (profile.status == 3) alert(profile.error ?: "<?>").tryToShow()
            }
            profileStatus.isFocusable = false
            editButton.setOnClickListener { openSettings(it, entity.id) }
            removeButton.setOnClickListener { requestRemove(entity.id) }
            doubleColumnMenuButton.setOnClickListener { showDoubleColumnMenu(it, entity.id) }
            shareLayout.setOnClickListener { showShareMenu(it, entity.id) }
        }

        fun isBoundTo(profileId: Long) = ::entity.isInitialized && entity.id == profileId

        private fun openSettings(anchor: View, profileId: Long) {
            val host = parentFragment as? ConfigurationFragment ?: return
            if (host.isRunningProfile(profileId)) return
            val profile = adapter?.profileById(profileId) ?: return
            anchor.context.startActivity(
                profile.settingIntent(
                    anchor.context,
                    proxyGroup.type == GroupType.SUBSCRIPTION,
                ),
            )
        }

        private fun performRemove(profileId: Long) {
            val host = parentFragment as? ConfigurationFragment ?: return
            if (select || host.isRunningProfile(profileId)) return
            val manager = undoManager ?: return
            val removed = adapter?.removeById(profileId) ?: return
            manager.remove(removed)
        }

        private fun requestRemove(profileId: Long) {
            val host = parentFragment as? ConfigurationFragment ?: return
            if (select || host.isRunningProfile(profileId)) return
            if (DataStore.confirmProfileDelete) {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.delete_confirm_prompt)
                    .setPositiveButton(R.string.yes) { _, _ -> performRemove(profileId) }
                    .setNegativeButton(R.string.no, null)
                    .show()
            } else {
                performRemove(profileId)
            }
        }

        private fun showDoubleColumnMenu(anchor: View, profileId: Long) {
            val host = parentFragment as? ConfigurationFragment ?: return
            val popup = PopupMenu(requireContext(), anchor)
            popup.menuInflater.inflate(R.menu.double_column_item_menu, popup.menu)
            if (select) popup.menu.removeItem(R.id.action_delete)
            val running = host.isRunningProfile(profileId)
            popup.menu.findItem(R.id.action_edit)?.isEnabled = !running
            popup.menu.findItem(R.id.action_delete)?.isEnabled = !running
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_edit -> {
                        openSettings(anchor, profileId)
                        true
                    }
                    R.id.action_share -> {
                        showShareMenu(anchor, profileId)
                        true
                    }
                    R.id.action_delete -> {
                        requestRemove(profileId)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        private fun selectProfile(profileId: Long) {
            val host = parentFragment as? ConfigurationFragment ?: return
            if (adapter?.profileById(profileId) == null) return
            runOnDefaultDispatcher {
                var update: Boolean
                var lastSelected: Long
                profileAccess.withLock {
                    update = DataStore.selectedProxy != profileId
                    lastSelected = DataStore.selectedProxy
                    DataStore.selectedProxy = profileId
                }
                host.refreshProfileState()

                if (update) {
                    ProfileManager.postUpdate(lastSelected, noTraffic = true)
                    if (DataStore.serviceState.canStop && reloadAccess.tryLock()) {
                        try {
                            SagerNet.reloadService(profileId)
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

        fun bind(proxyEntity: ProxyEntity, reserveMiddleRow: Boolean) {
            val pf = parentFragment as? ConfigurationFragment ?: return

            entity = proxyEntity

            profileName.text = proxyEntity.displayName()
            profileName.setTextColor(requireContext().getColorAttr(R.attr.profileNameColor))
            profileType.text = proxyEntity.displayType()
            profileType.setTextColor(requireContext().getProtocolColor(proxyEntity.type))

            val rx = proxyEntity.rx
            val tx = proxyEntity.tx
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
                reserveMiddleRow -> View.INVISIBLE
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

            val selected = pf.isSelectedProfile(proxyEntity.id)
            val running = pf.isRunningProfile(proxyEntity.id)
            editButton.isEnabled = !running
            removeButton.isEnabled = !running
            applySelected(selected)

            if (!(select || proxyEntity.type == ProxyEntity.TYPE_CHAIN)) {
                shareLayer.setBackgroundColor(Color.TRANSPARENT)
                shareButton.setImageResource(R.drawable.ic_social_share)
                shareButton.setColorFilter(Color.GRAY)
                shareButton.isVisible = true
            }
        }

        private fun showCode(link: String, name: String) {
            QRCodeDialog(link, name).showAllowingStateLoss(parentFragmentManager)
        }

        private fun export(link: String) {
            val success = SagerNet.trySetPrimaryClip(link)
            (activity as MainActivity).snackbar(
                if (success) R.string.action_export_msg else R.string.action_export_err,
            ).show()
        }

        private fun handleShareMenu(item: MenuItem, profileId: Long): Boolean {
            val profile = adapter?.profileById(profileId) ?: return true
            try {
                val name = profile.displayName().orEmpty()
                when (item.itemId) {
                    R.id.action_standard_qr -> showCode(profile.toStdLink(), name)
                    R.id.action_standard_clipboard -> export(profile.toStdLink())
                    R.id.action_universal_qr -> showCode(profile.requireBean().toUniversalLink(), name)
                    R.id.action_universal_clipboard -> export(
                        profile.requireBean().toUniversalLink(),
                    )

                    R.id.action_config_export_clipboard -> export(profile.exportConfig().first)
                    R.id.action_config_export_file -> {
                        val config = profile.exportConfig()
                        DataStore.serverConfig = config.first
                        startFilesForResult(
                            (parentFragment as ConfigurationFragment).exportConfig,
                            config.second,
                        )
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
                (activity as MainActivity).snackbar(e.readableMessage).show()
            }
            return true
        }
    }
}
