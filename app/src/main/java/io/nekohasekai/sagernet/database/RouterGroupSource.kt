package io.nekohasekai.sagernet.database

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.Serializable

@Entity(
    tableName = "router_group_sources",
    primaryKeys = ["routerId", "sourceGroupId"],
    indices = [Index("sourceGroupId")],
)
data class RouterGroupSource(
    var routerId: Long = 0L,
    var sourceGroupId: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    var userOrder: Long = 0L,
) : Serializable() {

    override fun initializeDefaultValues() = Unit

    override fun serializeToBuffer(output: ByteBufferOutput) {
        output.writeInt(0)
        output.writeLong(routerId)
        output.writeLong(sourceGroupId)
        output.writeLong(userOrder)
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        input.readInt()
        routerId = input.readLong()
        sourceGroupId = input.readLong()
        userOrder = input.readLong()
    }

    @androidx.room.Dao
    interface Dao {
        @Query("SELECT * FROM router_group_sources ORDER BY routerId, userOrder, sourceGroupId")
        fun all(): List<RouterGroupSource>

        @Query("SELECT * FROM router_group_sources WHERE routerId = :routerId ORDER BY userOrder, sourceGroupId")
        fun sourcesFor(routerId: Long): List<RouterGroupSource>

        @Query("SELECT * FROM router_group_sources WHERE sourceGroupId = :sourceGroupId ORDER BY routerId")
        fun routersForSource(sourceGroupId: Long): List<RouterGroupSource>

        @Query("DELETE FROM router_group_sources WHERE routerId = :routerId")
        fun deleteByRouter(routerId: Long): Int

        @Query("DELETE FROM router_group_sources WHERE sourceGroupId = :sourceGroupId")
        fun deleteBySource(sourceGroupId: Long): Int

        @Insert
        fun insert(sources: List<RouterGroupSource>)

        @Transaction
        fun replaceSources(routerId: Long, sourceGroupIds: Iterable<Long>) {
            deleteByRouter(routerId)
            val rows = sourceGroupIds.distinct().mapIndexed { index, sourceGroupId ->
                RouterGroupSource(routerId, sourceGroupId, index.toLong())
            }
            if (rows.isNotEmpty()) insert(rows)
        }

        @Query("DELETE FROM router_group_sources")
        fun reset()
    }

    companion object {
        @JvmField
        val CREATOR = object : Serializable.CREATOR<RouterGroupSource>() {
            override fun newInstance() = RouterGroupSource()
            override fun newArray(size: Int): Array<RouterGroupSource?> = arrayOfNulls(size)
        }
    }
}
