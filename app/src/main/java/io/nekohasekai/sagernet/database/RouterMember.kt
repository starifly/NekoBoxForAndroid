package io.nekohasekai.sagernet.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.Serializable

@Entity(
    tableName = "router_members",
    primaryKeys = ["routerId", "proxyId"],
    indices = [Index("proxyId")]
)
data class RouterMember(
    var routerId: Long = 0L,
    var proxyId: Long = 0L,
    var userOrder: Long = 0L,
    var lastMatchedAt: Long = 0L
) : Serializable() {

    override fun initializeDefaultValues() {
    }

    override fun serializeToBuffer(output: ByteBufferOutput) {
        output.writeInt(0)
        output.writeLong(routerId)
        output.writeLong(proxyId)
        output.writeLong(userOrder)
        output.writeLong(lastMatchedAt)
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        input.readInt()
        routerId = input.readLong()
        proxyId = input.readLong()
        userOrder = input.readLong()
        lastMatchedAt = input.readLong()
    }

    @androidx.room.Dao
    interface Dao {

        @Query("SELECT * FROM router_members ORDER BY routerId, userOrder, proxyId")
        fun all(): List<RouterMember>

        @Query("SELECT * FROM router_members WHERE routerId = :routerId ORDER BY userOrder, proxyId")
        fun getByRouter(routerId: Long): List<RouterMember>

        @Query("DELETE FROM router_members WHERE routerId = :routerId")
        fun deleteByRouter(routerId: Long): Int

        @Query("DELETE FROM router_members WHERE proxyId = :proxyId")
        fun deleteByProxy(proxyId: Long): Int

        @Query("UPDATE router_members SET userOrder = :userOrder WHERE routerId = :routerId AND proxyId = :proxyId")
        fun updateUserOrder(routerId: Long, proxyId: Long, userOrder: Long): Int

        @Transaction
        fun updateOrders(routerId: Long, orderedProxyIds: List<Long>) {
            val existing = getByRouter(routerId)
            if (existing.isEmpty()) return
            val existingIds = existing.mapTo(hashSetOf()) { it.proxyId }
            val ordered = orderedProxyIds.distinct().filter { it in existingIds }
            val orderedSet = ordered.toSet()
            val merged = ordered + existing.filter { it.proxyId !in orderedSet }.map { it.proxyId }
            for ((index, proxyId) in merged.withIndex()) {
                updateUserOrder(routerId, proxyId, (index + 1).toLong())
            }
        }

        @Insert
        fun insert(members: List<RouterMember>)

        @Transaction
        fun replaceMembers(routerId: Long, members: List<RouterMember>) {
            deleteByRouter(routerId)
            if (members.isNotEmpty()) {
                insert(members.map { it.copy(routerId = routerId) })
            }
        }

        @Query("DELETE FROM router_members")
        fun reset()
    }

    companion object {
        @JvmField
        val CREATOR = object : Serializable.CREATOR<RouterMember>() {

            override fun newInstance(): RouterMember {
                return RouterMember()
            }

            override fun newArray(size: Int): Array<RouterMember?> {
                return arrayOfNulls(size)
            }
        }
    }
}
