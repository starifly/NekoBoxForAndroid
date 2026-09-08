package io.nekohasekai.sagernet.bg.proto

class TrafficUpdater(
    private val queryStats: (String, String) -> Long,
    val items: List<TrafficLooperData>, // contain "bypass"
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    constructor(box: libcore.BoxInstance, items: List<TrafficLooperData>) :
        this(box::queryStats, items)

    init {
        val now = monotonicMillis()
        items.forEach { it.lastUpdate = now }
    }

    class TrafficLooperData(
        // Don't associate proxyEntity
        var tag: String,
        var tx: Long = 0,
        var rx: Long = 0,
        var txBase: Long = 0,
        var rxBase: Long = 0,
        var txRate: Long = 0,
        var rxRate: Long = 0,
        var lastUpdate: Long = 0,
        var ignore: Boolean = false,
        var hasTrafficDelta: Boolean = false,
    )

    /**
     * Writes the diff of [item] into [out] instead of allocating a new holder.
     */
    private fun updateOne(item: TrafficLooperData, out: TrafficLooperData): TrafficLooperData {
        // last update
        val now = monotonicMillis()
        val interval = now - item.lastUpdate
        item.lastUpdate = now
        out.tag = item.tag
        if (interval <= 0) {
            item.rxRate = 0
            item.txRate = 0
            out.rx = 0L
            out.tx = 0L
            out.rxRate = 0L
            out.txRate = 0L
            return out
        }

        // query
        val tx = queryStats(item.tag, "uplink")
        val rx = queryStats(item.tag, "downlink")

        // add diff
        item.rx += rx
        item.tx += tx
        item.rxRate = rx * 1000 / interval
        item.txRate = tx * 1000 / interval

        // return diff
        out.rx = rx
        out.tx = tx
        out.rxRate = item.rxRate
        out.txRate = item.txRate
        return out
    }

    // updateAll() runs on every traffic tick (down to 1s) and used to allocate a new
    // map plus one diff holder per tag each time. Both are reused here so the steady
    // state is allocation free.
    private val diffByTag = HashMap<String, TrafficLooperData>()
    private val queriedTags = HashSet<String>()

    fun updateAll() {
        queriedTags.clear()
        items.forEach { item ->
            item.hasTrafficDelta = false
            if (item.ignore) return@forEach
            val tag = item.tag
            // query a tag only once
            if (queriedTags.add(tag)) {
                val diff = diffByTag.getOrPut(tag) { TrafficLooperData(tag = tag) }
                updateOne(item, diff)
                item.hasTrafficDelta = diff.rx != 0L || diff.tx != 0L
            } else {
                val diff = diffByTag[tag]!!
                item.rx += diff.rx
                item.tx += diff.tx
                item.rxRate = diff.rxRate
                item.txRate = diff.txRate
                item.hasTrafficDelta = diff.rx != 0L || diff.tx != 0L
                item.lastUpdate = monotonicMillis()
            }
        }
    }
}
