package io.nekohasekai.sagernet.database

import android.content.Context
import android.content.Intent
import androidx.room.*
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.*
import io.nekohasekai.sagernet.fmt.amneziawg.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.*
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.masterdnsvpn.MasterDnsVpnBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.mieru.buildMieruConfig
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildNaiveConfig
import io.nekohasekai.sagernet.fmt.olcrtc.OlcrtcBean
import io.nekohasekai.sagernet.fmt.shadowsocks.*
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.buildTrojanGoConfig
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.*
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import moe.matsuri.nb4a.SingBoxOptions.BrutalOptions
import moe.matsuri.nb4a.SingBoxOptions.MultiplexOptions
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean

@Entity(
    tableName = "proxy_entities",
    indices = [Index("groupId", name = "groupId")],
)
data class ProxyEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var groupId: Long = 0L,
    var type: Int = 0,
    var userOrder: Long = 0L,
    var tx: Long = 0L,
    var rx: Long = 0L,
    // Lifetime (all-time) totals, accumulated across sessions. Additive columns (schema v12);
    // tx/rx above stay the live/session value the UI already shows. Not part of the Kryo
    // serializeToBuffer wire format (backup/export stats are out of scope), so the on-disk
    // blob format is unchanged. A DB default of 0 is required for the additive AutoMigration.
    @ColumnInfo(defaultValue = "0")
    var lifetimeRx: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    var lifetimeTx: Long = 0L,
    var status: Int = 0,
    var ping: Int = 0,
    var uuid: String = "",
    var error: String? = null,
    var socksBean: SOCKSBean? = null,
    var httpBean: HttpBean? = null,
    var ssBean: ShadowsocksBean? = null,
    var ssrBean: ShadowsocksRBean? = null,
    var vmessBean: VMessBean? = null,
    var trojanBean: TrojanBean? = null,
    var trojanGoBean: TrojanGoBean? = null,
    var mieruBean: MieruBean? = null,
    var naiveBean: NaiveBean? = null,
    var hysteriaBean: HysteriaBean? = null,
    var tuicBean: TuicBean? = null,
    var juicityBean: JuicityBean? = null,
    var sshBean: SSHBean? = null,
    var wgBean: WireGuardBean? = null,
    var shadowTLSBean: ShadowTLSBean? = null,
    var anyTLSBean: AnyTLSBean? = null,
    var chainBean: ChainBean? = null,
    var configBean: ConfigBean? = null,
    var snellBean: SnellBean? = null,
    var masterDnsVpnBean: MasterDnsVpnBean? = null,
    var awgBean: AmneziaWGBean? = null,
    var olcrtcBean: OlcrtcBean? = null,
) : Serializable() {

    companion object {
        const val TYPE_SOCKS = 0
        const val TYPE_HTTP = 1
        const val TYPE_SS = 2
        const val TYPE_SSR = 3
        const val TYPE_VMESS = 4
        const val TYPE_TROJAN = 6

        const val TYPE_SSH = 17
        const val TYPE_WG = 18

        const val TYPE_TROJAN_GO = 7
        const val TYPE_NAIVE = 9
        const val TYPE_HYSTERIA = 15
        const val TYPE_SHADOWTLS = 19
        const val TYPE_TUIC = 20
        const val TYPE_MIERU = 21
        const val TYPE_ANYTLS = 22
        const val TYPE_JUICITY = 23
        const val TYPE_SNELL = 24
        const val TYPE_MASTERDNSVPN = 25

        // 25 is reserved for the MasterDnsVPN sidecar type on the
        // feature/masterdnsvpn-sidecar branch (PR #18); do not reuse it here so
        // persisted type IDs stay stable when both branches merge.
        const val TYPE_AWG = 26
        const val TYPE_OLCRTC = 27

        const val TYPE_CONFIG = 998

        // 999 was the Matsuri "Neko Plugin" protocol (removed). Reserved: do not
        // reuse the id; rows with this type are purged by the v11 migration.
        const val TYPE_NEKO = 999

        const val TYPE_CHAIN = 8

        val chainName by lazy { app.getString(R.string.proxy_chain) }

        @JvmField
        val CREATOR = object : CREATOR<ProxyEntity>() {

            override fun newInstance(): ProxyEntity {
                return ProxyEntity()
            }

            override fun newArray(size: Int): Array<ProxyEntity?> {
                return arrayOfNulls(size)
            }
        }
    }

    @Ignore
    @Transient
    var dirty: Boolean = false

    override fun initializeDefaultValues() {
    }

    override fun serializeToBuffer(output: ByteBufferOutput) {
        output.writeInt(0)

        output.writeLong(id)
        output.writeLong(groupId)
        output.writeInt(type)
        output.writeLong(userOrder)
        output.writeLong(tx)
        output.writeLong(rx)
        output.writeInt(status)
        output.writeInt(ping)
        output.writeString(uuid)
        output.writeString(error)

        val data = KryoConverters.serialize(requireBean())
        output.writeVarInt(data.size, true)
        output.writeBytes(data)

        output.writeBoolean(dirty)
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        val version = input.readInt()

        id = input.readLong()
        groupId = input.readLong()
        type = input.readInt()
        userOrder = input.readLong()
        tx = input.readLong()
        rx = input.readLong()
        status = input.readInt()
        ping = input.readInt()
        uuid = input.readString()
        error = input.readString()
        putByteArray(input.readBytes(input.readVarInt(true)))

        dirty = input.readBoolean()
    }

    fun putByteArray(byteArray: ByteArray) {
        // Registry routes each persisted type id to the same KryoConverters.*Deserialize the
        // historical when(type) ladder used and stores it in the matching typed field. An
        // unknown/dead id is a no-op, matching the old ladder's absent else-branch.
        ProtocolRegistry.forType(type)?.let { it.setBean(this, it.deserialize(byteArray)) }
    }

    fun displayType(): String = ProtocolRegistry.forType(type)?.displayType?.invoke(this) ?: "Undefined type $type"

    fun displayName() = requireBean().displayName()
    fun displayAddress() = requireBean().displayAddress()

    fun requireBean(): AbstractBean {
        val descriptor = ProtocolRegistry.forType(type) ?: error("Undefined type $type")
        return descriptor.getBean(this) ?: error("Null ${displayType()} profile")
    }

    fun haveLink(): Boolean {
        return when (type) {
            TYPE_CHAIN -> false
            else -> true
        }
    }

    fun haveStandardLink(): Boolean {
        requireBean()
        return ProtocolRegistry.forType(type)!!.hasStandardLink
    }

    fun toStdLink(compact: Boolean = false): String {
        val bean = requireBean()
        return ProtocolRegistry.forType(type)!!.toStandardLink?.invoke(bean) ?: bean.toUniversalLink()
    }

    fun exportConfig(): Pair<String, String> {
        var name = "${requireBean().displayName()}.json"

        return with(requireBean()) {
            StringBuilder().apply {
                val config = buildConfig(this@ProxyEntity, forExport = true)
                append(config.config)

                if (!config.externalIndex.all { it.chain.isEmpty() }) {
                    name = "profiles.txt"
                }

                for ((chain) in config.externalIndex) {
                    chain.entries.forEachIndexed { index, (port, profile) ->
                        when (val bean = profile.requireBean()) {
                            is TrojanGoBean -> {
                                append("\n\n")
                                append(bean.buildTrojanGoConfig(port))
                            }

                            is MieruBean -> {
                                append("\n\n")
                                append(bean.buildMieruConfig(port))
                            }

                            is NaiveBean -> {
                                append("\n\n")
                                append(bean.buildNaiveConfig(port))
                            }

                            is HysteriaBean -> {
                                append("\n\n")
                                append(bean.buildHysteria1Config(port, null))
                            }
                        }
                    }
                }
            }.toString()
        } to name
    }

    fun needExternal(): Boolean {
        return ProtocolRegistry.forType(type)?.needExternal?.invoke(this) ?: false
    }

    fun singMux(): MultiplexOptions? {
        return when (type) {
            TYPE_VMESS -> MultiplexOptions().apply {
                enabled = vmessBean!!.enableMux
                padding = vmessBean!!.muxPadding
                protocol = when (vmessBean!!.muxType) {
                    1 -> "smux"
                    2 -> "yamux"
                    else -> "h2mux"
                }
                // muxMode 0: max_streams mode, 1: connections mode
                if (vmessBean!!.muxMode == 1) {
                    max_connections = vmessBean!!.muxMaxConnections
                    min_streams = vmessBean!!.muxMinStreams
                } else {
                    max_streams = vmessBean!!.muxConcurrency
                }
                if (vmessBean!!.muxBrutal == true) {
                    brutal = BrutalOptions().apply {
                        enabled = true
                        up_mbps = vmessBean!!.muxBrutalUpMbps
                        down_mbps = vmessBean!!.muxBrutalDownMbps
                    }
                }
            }

            TYPE_TROJAN -> MultiplexOptions().apply {
                enabled = trojanBean!!.enableMux
                padding = trojanBean!!.muxPadding
                protocol = when (trojanBean!!.muxType) {
                    1 -> "smux"
                    2 -> "yamux"
                    else -> "h2mux"
                }
                // muxMode 0: max_streams mode, 1: connections mode
                if (trojanBean!!.muxMode == 1) {
                    max_connections = trojanBean!!.muxMaxConnections
                    min_streams = trojanBean!!.muxMinStreams
                } else {
                    max_streams = trojanBean!!.muxConcurrency
                }
                if (trojanBean!!.muxBrutal == true) {
                    brutal = BrutalOptions().apply {
                        enabled = true
                        up_mbps = trojanBean!!.muxBrutalUpMbps
                        down_mbps = trojanBean!!.muxBrutalDownMbps
                    }
                }
            }

            TYPE_SS -> MultiplexOptions().apply {
                enabled = ssBean!!.enableMux
                padding = ssBean!!.muxPadding
                protocol = when (ssBean!!.muxType) {
                    1 -> "smux"
                    2 -> "yamux"
                    else -> "h2mux"
                }
                // muxMode 0: max_streams mode, 1: connections mode
                if (ssBean!!.muxMode == 1) {
                    max_connections = ssBean!!.muxMaxConnections
                    min_streams = ssBean!!.muxMinStreams
                } else {
                    max_streams = ssBean!!.muxConcurrency
                }
                if (ssBean!!.muxBrutal == true) {
                    brutal = BrutalOptions().apply {
                        enabled = true
                        up_mbps = ssBean!!.muxBrutalUpMbps
                        down_mbps = ssBean!!.muxBrutalDownMbps
                    }
                }
            }

            else -> null
        }
    }

    fun putBean(bean: AbstractBean): ProxyEntity {
        // Registry clears every typed field then assigns the one matching this bean's class and
        // sets the corresponding type id - identical result to the historical null-out block +
        // when(bean) ladder, but declared once per protocol. An unregistered bean class errors,
        // matching the old else-branch.
        ProtocolRegistry.clearAllBeans(this)
        val descriptor = ProtocolRegistry.forBean(bean) ?: error("Unregistered bean class ${bean.javaClass.simpleName}")
        type = descriptor.type
        descriptor.setBean(this, bean)
        return this
    }

    fun settingIntent(ctx: Context, isSubscription: Boolean): Intent {
        val activityClass = ProtocolRegistry.forType(type)?.settingsActivityClass
            ?: throw IllegalArgumentException("No settings activity for type $type")
        return Intent(ctx, activityClass).apply {
            putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, id)
            putExtra(ProfileSettingsActivity.EXTRA_IS_SUBSCRIPTION, isSubscription)
        }
    }

    @androidx.room.Dao
    interface Dao {

        @Query("select * from proxy_entities")
        fun getAll(): List<ProxyEntity>

        @Query("SELECT id FROM proxy_entities WHERE groupId = :groupId ORDER BY userOrder")
        fun getIdsByGroup(groupId: Long): List<Long>

        @Query("SELECT * FROM proxy_entities WHERE groupId = :groupId ORDER BY userOrder")
        fun getByGroup(groupId: Long): List<ProxyEntity>

        @Query("SELECT * FROM proxy_entities WHERE id in (:proxyIds)")
        fun getEntities(proxyIds: List<Long>): List<ProxyEntity>

        @Query("SELECT COUNT(*) FROM proxy_entities WHERE groupId = :groupId")
        fun countByGroup(groupId: Long): Long

        @Query("SELECT  MAX(userOrder) + 1 FROM proxy_entities WHERE groupId = :groupId")
        fun nextOrder(groupId: Long): Long?

        @Query("SELECT * FROM proxy_entities WHERE id = :proxyId")
        fun getById(proxyId: Long): ProxyEntity?

        @Query("DELETE FROM proxy_entities WHERE id IN (:proxyId)")
        fun deleteById(proxyId: Long): Int

        @Query("DELETE FROM proxy_entities WHERE id IN (:proxyIds)")
        fun deleteByIds(proxyIds: List<Long>): Int

        @Query("DELETE FROM proxy_entities WHERE groupId = :groupId")
        fun deleteByGroup(groupId: Long)

        @Query("DELETE FROM proxy_entities WHERE groupId in (:groupId)")
        fun deleteByGroup(groupId: LongArray)

        @Delete
        fun deleteProxy(proxy: ProxyEntity): Int

        @Delete
        fun deleteProxy(proxies: List<ProxyEntity>): Int

        @Update
        fun updateProxy(proxy: ProxyEntity): Int

        @Update
        fun updateProxy(proxies: List<ProxyEntity>): Int

        @Query("UPDATE proxy_entities SET rx = :rx, tx = :tx WHERE id = :proxyId")
        fun updateTraffic(proxyId: Long, rx: Long, tx: Long): Int

        // Additive lifetime accumulation (schema v12). Callers pass the per-session DELTA since
        // the last flush (never absolute totals) so re-entrant persist() cannot double-count.
        @Query(
            "UPDATE proxy_entities SET lifetimeRx = lifetimeRx + :rxDelta, lifetimeTx = lifetimeTx + :txDelta WHERE id = :proxyId",
        )
        fun addLifetimeTraffic(proxyId: Long, rxDelta: Long, txDelta: Long): Int

        @Insert
        fun addProxy(proxy: ProxyEntity): Long

        @Insert
        fun insert(proxies: List<ProxyEntity>)

        @Query("DELETE FROM proxy_entities WHERE groupId = :groupId")
        fun deleteAll(groupId: Long): Int

        @Query("DELETE FROM proxy_entities")
        fun reset()
    }

    override fun describeContents(): Int {
        return 0
    }
}
