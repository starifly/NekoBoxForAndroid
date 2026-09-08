package io.nekohasekai.sagernet.database

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.Serializable

@Entity(
    tableName = "router_groups",
    indices = [Index("stableTag", unique = true)]
)
data class RouterGroup(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var stableTag: String = "",
    var name: String = "",
    var mode: Int = MODE_SELECTOR,
    var enabled: Boolean = true,
    var matchConfig: String = "{}",
    var selectedProxyId: Long = NO_SELECTION,
    var userOrder: Long = 0L,
    @ColumnInfo(defaultValue = "")
    var selectedNodeKey: String = "",
    @ColumnInfo(defaultValue = "")
    var lastError: String = "",
) : Serializable() {

    override fun initializeDefaultValues() {
    }

    override fun serializeToBuffer(output: ByteBufferOutput) {
        output.writeInt(1)
        output.writeLong(id)
        output.writeString(stableTag)
        output.writeString(name)
        output.writeInt(mode)
        output.writeBoolean(enabled)
        output.writeString(matchConfig)
        output.writeLong(selectedProxyId)
        output.writeLong(userOrder)
        output.writeString(selectedNodeKey)
        output.writeString(lastError)
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        val version = input.readInt()
        id = input.readLong()
        stableTag = input.readString()
        name = input.readString()
        mode = input.readInt()
        enabled = input.readBoolean()
        matchConfig = input.readString()
        selectedProxyId = input.readLong()
        userOrder = input.readLong()
        if (version >= 1) {
            selectedNodeKey = input.readString()
            lastError = input.readString()
        }
    }

    @androidx.room.Dao
    interface Dao {

        @Query("SELECT * FROM router_groups ORDER BY userOrder, id")
        fun all(): List<RouterGroup>

        @Query("SELECT * FROM router_groups WHERE id = :routerId")
        fun getById(routerId: Long): RouterGroup?

        @Query("SELECT * FROM router_groups WHERE stableTag = :stableTag")
        fun getByStableTag(stableTag: String): RouterGroup?

        @Query("SELECT MAX(userOrder) + 1 FROM router_groups")
        fun nextOrder(): Long?

        @Insert
        fun create(router: RouterGroup): Long

        @Update
        fun update(router: RouterGroup): Int

        @Query("""
            UPDATE router_groups SET selectedProxyId = -1, selectedNodeKey = ''
            WHERE selectedProxyId != -1 AND (
                NOT EXISTS (SELECT 1 FROM proxy_entities WHERE id = router_groups.selectedProxyId)
                OR NOT EXISTS (SELECT 1 FROM router_members
                    WHERE routerId = router_groups.id AND proxyId = router_groups.selectedProxyId)
            )
        """)
        fun clearInvalidSelections(): Int

        @Delete
        fun delete(router: RouterGroup): Int

        @Query("DELETE FROM router_groups")
        fun reset()

        @Insert
        fun insert(routers: List<RouterGroup>)

        @Query("UPDATE router_groups SET lastError = :error WHERE id = :routerId")
        fun setLastError(routerId: Long, error: String): Int

        @Query("""
            UPDATE router_groups
            SET selectedProxyId = :selectedProxyId, selectedNodeKey = :selectedNodeKey
            WHERE id = :routerId
        """)
        fun updateSelection(
            routerId: Long,
            selectedProxyId: Long,
            selectedNodeKey: String,
        ): Int

        @Query("""
            UPDATE router_groups
            SET selectedProxyId = :selectedProxyId, selectedNodeKey = :selectedNodeKey, lastError = :lastError
            WHERE id = :routerId
        """)
        fun updateSelectionAndError(
            routerId: Long,
            selectedProxyId: Long,
            selectedNodeKey: String,
            lastError: String,
        ): Int
    }

    companion object {
        const val MODE_SELECTOR = 0
        const val MODE_URL_TEST = 1
        const val NO_SELECTION = -1L

        @JvmField
        val CREATOR = object : Serializable.CREATOR<RouterGroup>() {

            override fun newInstance(): RouterGroup {
                return RouterGroup()
            }

            override fun newArray(size: Int): Array<RouterGroup?> {
                return arrayOfNulls(size)
            }
        }
    }
}
