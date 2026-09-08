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

class TrafficLooper
    (
    val data: BaseService.Data, private val sc: CoroutineScope
) {

    companion object {
        private const val TRAFFIC_BATCH_SIZE = 500
    }

    private var job: Job? = null
    private val updateRequests = Channel<Unit>(Channel.CONFLATED)

    fun requestUpdate() {
        updateRequests.trySend(Unit)
    }

    private suspend fun awaitUpdate(delayMillis: Long) {
        withTimeoutOrNull(delayMillis) { updateRequests.receive() }
    }
    private val idMap = mutableMapOf<Long, TrafficUpdater.TrafficLooperData>() // id to 1 data
    private val tagMap = mutableMapOf<String, TrafficUpdater.TrafficLooperData>() // tag to 1 data
    private val stateMutex = Mutex()
    private var trafficUpdater: TrafficUpdater? = null

    private data class LoopSnapshot(
        val speed: SpeedDisplayData,
        val trafficUpdates: List<TrafficData>,
    )

    private suspend fun <T> withStateLock(block: suspend () -> T): T {
        stateMutex.lock()
        return try {
            block()
        } finally {
            stateMutex.unlock()
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        // finally traffic post
        if (!DataStore.profileTrafficStatistics) return
        withStateLock {
            trafficUpdater?.updateAll()
            val traffic = mutableMapOf<Long, TrafficData>()
            data.proxy?.config?.trafficMap?.forEach { (_, ents) ->
                for (ent in ents) {
                    val item = idMap[ent.id] ?: return@forEach
                    ent.rx = item.rx
                    ent.tx = item.tx
                    ProfileManager.updateTraffic(ent.id, ent.rx, ent.tx)
                    traffic[ent.id] = TrafficData(
                        id = ent.id,
                        rx = ent.rx,
                        tx = ent.tx,
                    )
                }
            }
            if (traffic.isNotEmpty()) {
                val batches = traffic.values.chunked(TRAFFIC_BATCH_SIZE).map {
                    TrafficDataBatch(ArrayList(it))
                }
                data.binder.broadcast { callback ->
                    batches.forEach { callback.cbTrafficUpdate(it) }
                }
            }
        }
        Logs.d("finally traffic post done")
    }

    fun start() {
        job = sc.launch { loop() }
    }

    var selectorNowId = -114514L
    var selectorNowFakeTag = ""

    suspend fun selectMain(id: Long) = withStateLock {
        selectMainLocked(id)
    }

    private suspend fun selectMainLocked(id: Long, statsTag: String = TAG_PROXY) {
        Logs.d("select traffic count $TAG_PROXY to $id, old id is $selectorNowId")
        val oldData = idMap[selectorNowId]
        val newData = idMap[id] ?: return
        oldData?.apply {
            tag = selectorNowFakeTag
            ignore = true
            // post traffic when switch
            if (DataStore.profileTrafficStatistics) {
                data.proxy?.config?.trafficMap?.get(tag)?.firstOrNull()?.let {
                    it.rx = rx
                    it.tx = tx
                    ProfileManager.updateTraffic(it.id, it.rx, it.tx)
                }
            }
        }
        selectorNowFakeTag = newData.tag
        selectorNowId = id
        newData.apply {
            tag = statsTag
            ignore = false
        }
    }

    private suspend fun syncUrlTestWinnerLocked(proxy: ProxyInstance): LongArray {
        val selections = proxy.currentUrlTestSelections()
        val mainTag = proxy.config.mainUrlTestTag ?: return selections
        val mainRouterId = proxy.config.routerUrlTestTags.entries
            .firstOrNull { it.value == mainTag }?.key ?: return selections
        val selectionMap = io.nekohasekai.sagernet.route.RouterRuntimeSelection.toMap(selections)
        val winnerId = selectionMap[mainRouterId] ?: return selections
        if (winnerId != selectorNowId) selectMainLocked(winnerId, mainTag)
        return selections
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
                    batches.forEach { callback.cbTrafficUpdate(it) }
                }
            }
        }
    }

    private suspend fun loop() {
        val delayMs = DataStore.speedInterval.toLong()
        val showDirectSpeed = DataStore.showDirectSpeed
        val profileTrafficStatistics = DataStore.profileTrafficStatistics

        // for display
        val itemBypass = TrafficUpdater.TrafficLooperData(tag = TAG_BYPASS)

        while (currentCoroutineContext().isActive) {
            val proxy = data.proxy
            if (proxy == null) {
                awaitUpdate(TrafficLoopPolicy.initializationRetryMillis(delayMs))
                continue
            }
            if (!proxy.isInitialized()) {
                awaitUpdate(TrafficLoopPolicy.initializationRetryMillis(delayMs))
                continue
            }

            // Resolved once per tick: containsValue() scans the whole callback map,
            // and the value cannot change meaningfully within a single tick.
            val mainActivityForeground = data.binder.callbackIdMap.containsValue(
                SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND
            )

            if (delayMs <= 0L) {
                // Nobody is listening -> skip the selection query and the IPC round-trip.
                if (mainActivityForeground && data.state == BaseService.State.Connected) {
                    val selections = proxy.currentUrlTestSelections()
                    data.binder.broadcast { callback ->
                        if (data.binder.callbackIdMap[callback] ==
                            SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND
                        ) {
                            callback.cbSpeedUpdate(SpeedDisplayData(urlTestSelections = selections))
                        }
                    }
                }
                awaitUpdate(TrafficLoopPolicy.delayMillis(
                    delayMs,
                    mainActivityForeground,
                    false,
                ))
                continue
            }
            val snapshot = withStateLock {
                if (trafficUpdater == null) {
                    idMap.clear()
                    idMap[-1] = itemBypass
                    //
                    val tags = hashSetOf(TAG_PROXY, TAG_BYPASS)
                    val dynamicMain = proxy.config.selectorGroupId >= 0L ||
                        proxy.config.mainUrlTestTag != null
                    // Nodes belonging to independent Router groups must never be mass-ignored:
                    // they accumulate traffic independently of the main selector winner.
                    val routerMemberIds = proxy.config.routerAllMemberIds
                    proxy.config.trafficMap.forEach { (tag, ents) ->
                        tags.add(tag)
                        for (ent in ents) {
                            // Only ignore nodes that are NOT already accounted for by an
                            // independent Router. Router member nodes get their own ignore=false
                            // path and are updated regardless of main selector state.
                            val belongsToRouter = ent.id in routerMemberIds
                            val item = TrafficUpdater.TrafficLooperData(
                                tag = tag,
                                rx = ent.rx,
                                tx = ent.tx,
                                rxBase = ent.rx,
                                txBase = ent.tx,
                                ignore = dynamicMain && !belongsToRouter,
                            )
                            idMap[ent.id] = item
                            tagMap[tag] = item
                        }
                    }
                    if (proxy.config.mainUrlTestTag != null) {
                        syncUrlTestWinnerLocked(proxy)
                    } else if (proxy.config.selectorGroupId >= 0L) {
                        selectMainLocked(proxy.config.mainEntId)
                    }
                    trafficUpdater = TrafficUpdater(
                        box = proxy.box, items = idMap.values.toList()
                    )
                    proxy.config.mainUrlTestTag?.let(tags::add)
                    proxy.box.setV2rayStats(tags.joinToString("\n"))
                }

                val urlTestSelections = syncUrlTestWinnerLocked(proxy)
                trafficUpdater!!.updateAll()
                currentCoroutineContext().ensureActive()

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

                // The list is only ever consumed by the foreground broadcast below, so
                // building it while nobody is watching is pure garbage per tick.
                val trafficUpdates = if (profileTrafficStatistics && mainActivityForeground) {
                    val updates = arrayListOf<TrafficData>()
                    idMap.forEach { (id, item) ->
                        if (id > 0L && item.hasTrafficDelta) {
                            updates.add(TrafficData(id = id, rx = item.rx, tx = item.tx))
                        }
                    }
                    updates
                } else {
                    emptyList()
                }
                val snapshot = LoopSnapshot(
                    speed = SpeedDisplayData(
                        mainTxRate,
                        mainRxRate,
                        if (showDirectSpeed) itemBypass.txRate else 0L,
                        if (showDirectSpeed) itemBypass.rxRate else 0L,
                        mainTx,
                        mainRx,
                        urlTestSelections,
                    ),
                    trafficUpdates = trafficUpdates,
                )
                if (mainActivityForeground && data.state == BaseService.State.Connected) {
                    data.binder.broadcast { callback ->
                        if (data.binder.callbackIdMap[callback] ==
                            SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND
                        ) {
                            callback.cbSpeedUpdate(snapshot.speed)
                            if (snapshot.trafficUpdates.isNotEmpty()) {
                                snapshot.trafficUpdates.chunked(TRAFFIC_BATCH_SIZE).forEach {
                                    callback.cbTrafficUpdate(TrafficDataBatch(ArrayList(it)))
                                }
                            }
                        }
                    }
                }
                snapshot
            }
            currentCoroutineContext().ensureActive()

            // ServiceNotification
            data.notification?.apply {
                if (listenPostSpeed) postNotificationSpeedUpdate(snapshot.speed)
            }

            awaitUpdate(
                TrafficLoopPolicy.delayMillis(
                    configuredMillis = delayMs,
                    mainActivityForeground = mainActivityForeground,
                    notificationSpeedVisible = data.notification?.listenPostSpeed == true,
                )
            )
        }
    }
}
