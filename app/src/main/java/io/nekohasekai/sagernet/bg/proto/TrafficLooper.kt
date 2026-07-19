package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.aidl.TrafficDataBatch
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TrafficLooper(
    val data: BaseService.Data,
    private val sc: CoroutineScope,
) {

    companion object {
        private const val TRAFFIC_BATCH_SIZE = 500
    }

    private var job: Job? = null
    private val idMap = mutableMapOf<Long, TrafficUpdater.TrafficLooperData>() // id to 1 data
    private val tagMap = mutableMapOf<String, TrafficUpdater.TrafficLooperData>() // tag to 1 data
    private val stateMutex = Mutex()
    private var trafficUpdater: TrafficUpdater? = null

    private data class LoopSnapshot(
        val speed: SpeedDisplayData,
        val trafficUpdates: ArrayList<TrafficData>,
    )

    private suspend fun <T> withStateLock(block: suspend () -> T): T {
        stateMutex.lock()
        return try {
            block()
        } finally {
            stateMutex.unlock()
        }
    }

    // Selector switches arrive from the JNI selector callback (NativeInterface) on a separate
    // coroutine. Funnel them through this channel so idMap/tagMap are only ever touched on the
    // single loop coroutine (no concurrent HashMap mutation -> no CME / corrupt accounting).
    // CONFLATED: only the latest selected id matters; superseded switches are safe to drop.
    private val selectChannel = Channel<Long>(Channel.CONFLATED)

    // Serializes the lifetime read-modify-write so the loop coroutine (persist) and the
    // selector-switch coroutine (GlobalScope via runOnDefaultDispatcher) cannot interleave the
    // check-and-advance of lifetimeFlushed* and double-count a delta.
    private val lifetimeMutex = Mutex()

    suspend fun stop() {
        // cancelAndJoin (not cancel): wait for the loop coroutine to actually finish before the
        // final flush, so persist() reads idMap/tagMap only after the loop stops mutating them.
        job?.cancelAndJoin()
        selectChannel.close()
        // finally traffic post
        persist()
    }

    /**
     * Flush the current per-profile cumulative rx/tx into the DB (ProxyEntity.tx/rx via
     * ProfileManager.updateTraffic) and broadcast a final traffic update. Safe to call more
     * than once. Called from stop() (normal teardown) and from the service's persistStats()
     * hook on ACTION_SHUTDOWN so a hard shutdown doesn't drop the last session's bytes.
     */
    suspend fun persist() {
        if (!DataStore.profileTrafficStatistics) return
        withStateLock {
            val traffic = mutableMapOf<Long, TrafficData>()
            data.proxy?.config?.trafficMap?.forEach { (_, ents) ->
                for (ent in ents) {
                    // Skip just this entity if its live data isn't in the map yet (e.g. on the
                    // hard-shutdown path before the loop has populated it); continue with the
                    // rest of the group rather than abandoning the whole forEach.
                    val item = idMap[ent.id] ?: continue
                    ent.rx = item.rx
                    ent.tx = item.tx
                    ProfileManager.updateTraffic(ent.id, ent.rx, ent.tx)
                    flushLifetimeDelta(ent.id, item)
                    traffic[ent.id] = TrafficData(
                        id = ent.id,
                        rx = ent.rx,
                        tx = ent.tx,
                    )
                }
            }
            if (traffic.isNotEmpty()) {
                // Keep chunking to prevent Binder transaction buffer stretching/overflow,
                // but use the new upstream cbTrafficUpdateList API.
                traffic.values.chunked(TRAFFIC_BATCH_SIZE).forEach { batch ->
                    data.binder.broadcast { callback ->
                        callback.cbTrafficUpdateList(ArrayList(batch))
                    }
                }
            }
        }
        Logs.d("finally traffic post done")
    }

    /**
     * Add this session's not-yet-persisted delta into the profile's lifetime columns, advancing
     * the flushed marker only AFTER the DB write completes so a failed/incomplete write does not
     * silently drop bytes. suspend so the caller awaits the write on its own coroutine.
     * Idempotent: a second call before more traffic flows adds nothing, so re-entrant persist()
     * / a selector switch never double-counts. Gated by profileTrafficStatistics like its callers.
     */
    private suspend fun flushLifetimeDelta(id: Long, item: TrafficUpdater.TrafficLooperData) {
        lifetimeMutex.withLock {
            val rxDelta = (item.rx - item.rxBase) - item.lifetimeFlushedRx
            val txDelta = (item.tx - item.txBase) - item.lifetimeFlushedTx
            val rxAdd = if (rxDelta > 0) rxDelta else 0
            val txAdd = if (txDelta > 0) txDelta else 0
            if (rxAdd == 0L && txAdd == 0L) return
            ProfileManager.addLifetimeTraffic(id, rxAdd, txAdd)
            item.lifetimeFlushedRx += rxAdd
            item.lifetimeFlushedTx += txAdd
        }
    }

    fun start() {
        job = sc.launch { loop() }
    }

    var selectorNowId = -114514L
    var selectorNowFakeTag = ""

    // Non-blocking entry for the JNI selector callback (NativeInterface). Posts the request;
    // it is applied on the loop coroutine via applySelect(). Never touches the maps directly.
    fun selectMain(id: Long) {
        selectChannel.trySend(id)
    }

    private fun applySelect(id: Long) {
        Logs.d("select traffic count $TAG_PROXY to $id, old id is $selectorNowId")
        val oldData = idMap[selectorNowId]
        val newData = idMap[id] ?: return
        oldData?.apply {
            tag = selectorNowFakeTag
            ignore = true
            // post traffic when switch
            if (DataStore.profileTrafficStatistics) {
                val switchedFrom = this
                data.proxy?.config?.trafficMap?.get(tag)?.firstOrNull()?.let {
                    it.rx = rx
                    it.tx = tx
                    runOnDefaultDispatcher {
                        ProfileManager.updateTraffic(it.id, it.rx, it.tx)
                        flushLifetimeDelta(it.id, switchedFrom)
                    }
                }
            }
        }
        selectorNowFakeTag = newData.tag
        selectorNowId = id
        newData.apply {
            tag = TAG_PROXY
            ignore = false
        }
    }

    suspend fun resetTraffic(profileIds: LongArray) {
        val targetIds = profileIds.asSequence().filter { it > 0L }.toHashSet()
        if (targetIds.isEmpty()) return

        withStateLock {
            trafficUpdater?.updateAll()
            val changed = linkedMapOf<Long, TrafficData>()
            idMap.forEach { (id, item) ->
                if (id > 0L && id !in targetIds && item.hasTrafficDelta) {
                    changed[id] = TrafficData(id = id, rx = item.rx, tx = item.tx)
                }
            }

            data.proxy?.config?.trafficMap?.values?.forEach { entities ->
                entities.forEach { entity ->
                    if (entity.id in targetIds) {
                        entity.tx = 0L
                        entity.rx = 0L
                    }
                }
            }
            targetIds.forEach { id ->
                idMap[id]?.apply {
                    tx = 0L
                    rx = 0L
                    txBase = 0L
                    rxBase = 0L
                    txRate = 0L
                    rxRate = 0L
                    hasTrafficDelta = false
                }
                changed[id] = TrafficData(id = id, rx = 0L, tx = 0L)
            }
            ProfileManager.resetTraffic(targetIds.toLongArray())
            val batches = changed.values.chunked(TRAFFIC_BATCH_SIZE).map {
                TrafficDataBatch(ArrayList(it))
            }
            data.binder.broadcast { callback ->
                if (data.binder.callbackIdMap[callback] ==
                    SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND
                ) {
                    batches.forEach { callback.cbTrafficUpdateBatch(it) }
                }
            }
        }
    }

    private suspend fun loop() {
        val delayMs = DataStore.speedInterval.toLong()
        val showDirectSpeed = DataStore.showDirectSpeed
        val profileTrafficStatistics = DataStore.profileTrafficStatistics
        if (delayMs == 0L) return

        // for display
        val itemBypass = TrafficUpdater.TrafficLooperData(tag = TAG_BYPASS)

        while (currentCoroutineContext().isActive) {
            val proxy = data.proxy
            if (proxy == null) {
                delay(delayMs)
                continue
            }
            if (!proxy.isInitialized()) continue

            if (trafficUpdater == null) {
                if (!proxy.isInitialized()) {
                    delay(delayMs)
                    continue
                }
                idMap.clear()
                idMap[-1] = itemBypass
                //
                val tags = hashSetOf(TAG_PROXY, TAG_BYPASS)
                proxy.config.trafficMap.forEach { (tag, ents) ->
                    tags.add(tag)
                    for (ent in ents) {
                        val item = TrafficUpdater.TrafficLooperData(
                            tag = tag,
                            rx = ent.rx,
                            tx = ent.tx,
                            rxBase = ent.rx,
                            txBase = ent.tx,
                            ignore = proxy.config.selectorGroupId >= 0L,
                        )
                        idMap[ent.id] = item
                        tagMap[tag] = item
                        Logs.d("traffic count $tag to ${ent.id}")
                    }
                }
                if (proxy.config.selectorGroupId >= 0L) {
                    applySelect(proxy.config.mainEntId)
                }
                //
                trafficUpdater = TrafficUpdater(
                    box = proxy.box,
                    items = idMap.values.toList(),
                )
                proxy.box.setV2rayStats(tags.joinToString("\n"))
            }

            // Apply any selector switches posted from the JNI callback, on THIS coroutine, so
            // idMap/tagMap mutation stays single-confined to the loop.
            while (true) {
                val sel = selectChannel.tryReceive().getOrNull() ?: break
                applySelect(sel)
            }

            trafficUpdater.updateAll()
            if (!sc.isActive) return

            // add all non-bypass to "main"
            var mainTxRate = 0L
            var mainRxRate = 0L
            var mainTx = 0L
            var mainRx = 0L
            tagMap.forEach { (_, it) ->
                if (!it.ignore) {
                    mainTxRate += it.txRate
                    mainRxRate += it.rxRate
                }
                mainTx += it.tx - it.txBase
                mainRx += it.rx - it.rxBase
            }

            // speed
            val speed = SpeedDisplayData(
                mainTxRate,
                mainRxRate,
                if (showDirectSpeed) itemBypass.txRate else 0L,
                if (showDirectSpeed) itemBypass.rxRate else 0L,
                mainTx,
                mainRx,
            )

            // broadcast (MainActivity)
            if (data.state == BaseService.State.Connected &&
                data.binder.callbackIdMap.containsValue(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
            ) {
                data.binder.broadcast { b ->
                    if (data.binder.callbackIdMap[b] == SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) {
                        b.cbSpeedUpdate(speed)
                        if (profileTrafficStatistics) {
                            val batch = ArrayList<TrafficData>(idMap.size)
                            idMap.forEach { (id, item) ->
                                batch.add(TrafficData(id = id, rx = item.rx, tx = item.tx)) // display
                            }
                            b.cbTrafficUpdateList(batch)
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()

            // ServiceNotification
            data.notification?.apply {
                if (listenPostSpeed) {
                    postNotificationSpeedUpdate(speed)
                }
            }

            delay(delayMs)
        }
    }
}
