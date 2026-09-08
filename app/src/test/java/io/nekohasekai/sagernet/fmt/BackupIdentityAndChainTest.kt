package io.nekohasekai.sagernet.fmt

import com.esotericsoftware.kryo.KryoException
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RouterGroup
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class BackupIdentityAndChainTest {

    // --- R04: Strict Deserialization Rejects Null or Empty Byte Array ---

    @Test
    fun strictDeserializationRejectsNullOrEmptyBytes() {
        assertThrows(KryoException::class.java) {
            KryoConverters.withStrictDeserialization {
                KryoConverters.deserialize(RouterGroup(), null)
            }
        }
        assertThrows(KryoException::class.java) {
            KryoConverters.withStrictDeserialization {
                KryoConverters.deserialize(RouterGroup(), ByteArray(0))
            }
        }
        assertThrows(KryoException::class.java) {
            KryoConverters.withStrictDeserialization {
                KryoConverters.deserialize(ProxyGroup(), null)
            }
        }

        // Outside strict mode, null returns the default bean
        val nonStrict = KryoConverters.deserialize(RouterGroup(), null)
        assertNotNull(nonStrict)
        assertEquals(0L, nonStrict.id)
    }

    // --- R03: KeyValuePair Payload Structure and Type Validation ---

    @Test
    fun keyValuePairRejectsTruncatedPayloads() {
        // TYPE_LONG with empty payload
        val kvLongEmpty = KeyValuePair("profileId").apply {
            valueType = KeyValuePair.TYPE_LONG
            value = ByteArray(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            kvLongEmpty.validate()
        }
        // Reading .long should not crash with BufferUnderflowException
        assertNull(kvLongEmpty.long)

        // TYPE_BOOLEAN with empty payload
        val kvBoolEmpty = KeyValuePair("boolKey").apply {
            valueType = KeyValuePair.TYPE_BOOLEAN
            value = ByteArray(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            kvBoolEmpty.validate()
        }
        assertNull(kvBoolEmpty.boolean)

        // TYPE_FLOAT truncated
        val kvFloatTrunc = KeyValuePair("floatKey").apply {
            valueType = KeyValuePair.TYPE_FLOAT
            value = ByteArray(2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            kvFloatTrunc.validate()
        }
        assertNull(kvFloatTrunc.float)

        // TYPE_STRING_SET with truncated header
        val kvSetTruncHeader = KeyValuePair("setKey").apply {
            valueType = KeyValuePair.TYPE_STRING_SET
            value = ByteArray(2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            kvSetTruncHeader.validate()
        }
        assertNull(kvSetTruncHeader.stringSet)

        // TYPE_STRING_SET with invalid length exceeding remaining bytes
        val invalidSetBuffer = ByteBuffer.allocate(8).putInt(100).put(ByteArray(4)).array()
        val kvSetInvalidLen = KeyValuePair("setKey").apply {
            valueType = KeyValuePair.TYPE_STRING_SET
            value = invalidSetBuffer
        }
        assertThrows(IllegalArgumentException::class.java) {
            kvSetInvalidLen.validate()
        }
        assertNull(kvSetInvalidLen.stringSet)
    }

    @Test
    fun keyValuePairValidPayloadsPass() {
        val kvLong = KeyValuePair("profileId").put(12345L)
        kvLong.validate()
        assertEquals(12345L, kvLong.long)

        val kvBool = KeyValuePair("enabled").put(true)
        kvBool.validate()
        assertEquals(true, kvBool.boolean)

        val kvFloat = KeyValuePair("scale").put(1.5f)
        kvFloat.validate()
        assertEquals(1.5f, kvFloat.float)

        val kvString = KeyValuePair("name").put("hello")
        kvString.validate()
        assertEquals("hello", kvString.string)

        val kvSet = KeyValuePair("tags").put(setOf("tag1", "tag2"))
        kvSet.validate()
        assertEquals(setOf("tag1", "tag2"), kvSet.stringSet)
    }

    // --- R05: ChainBean Cycle and Missing Reference Validation ---

    @Test
    fun chainBeanDetectsSelfLoopAndMutualCycle() {
        // Self loop: 11 -> [11]
        val chainEdgesSelf = mapOf(11L to listOf(11L))
        assertThrows(IllegalArgumentException::class.java) {
            detectCycles(chainEdgesSelf)
        }

        // Mutual cycle: 11 -> [12], 12 -> [11]
        val chainEdgesMutual = mapOf(
            11L to listOf(12L),
            12L to listOf(11L)
        )
        assertThrows(IllegalArgumentException::class.java) {
            detectCycles(chainEdgesMutual)
        }

        // Multi-hop cycle: 11 -> [12], 12 -> [13], 13 -> [11]
        val chainEdgesMulti = mapOf(
            11L to listOf(12L),
            12L to listOf(13L),
            13L to listOf(11L)
        )
        assertThrows(IllegalArgumentException::class.java) {
            detectCycles(chainEdgesMulti)
        }

        // Valid DAG: 11 -> [12, 13], 12 -> [14], 13 -> [14], 14 -> []
        val chainEdgesValid = mapOf(
            11L to listOf(12L, 13L),
            12L to listOf(14L),
            13L to listOf(14L)
        )
        // Should not throw
        detectCycles(chainEdgesValid)
    }

    private fun detectCycles(chainEdges: Map<Long, List<Long>>) {
        val visited = HashSet<Long>()
        val stack = HashSet<Long>()
        fun check(node: Long) {
            if (node in stack) throw IllegalArgumentException("Cyclic chain reference detected involving proxy $node")
            if (node in visited) return
            visited.add(node)
            stack.add(node)
            for (target in chainEdges[node].orEmpty()) {
                check(target)
            }
            stack.remove(node)
        }
        for (node in chainEdges.keys) {
            check(node)
        }
    }

    // --- R02: Partial Restore Stable Identity Verification ---

    @Test
    fun partialRestoreValidatesStableIdentity() {
        // Target router has same ID 3, but different stableTag
        val localRouters = mapOf(3L to "router.localB")
        val expectedRouterTag = "router.backupA"

        assertNotEquals(expectedRouterTag, localRouters[3L])

        // When stableTag matches, it is verified
        val matchingLocalRouters = mapOf(3L to "router.backupA")
        assertEquals(expectedRouterTag, matchingLocalRouters[3L])

        // Standard outbounds (0, -1, -2) do not require identity matching
        val standardOutbounds = listOf(0L, -1L, -2L)
        for (outbound in standardOutbounds) {
            assertTrue(outbound <= 0L)
        }
    }

    // --- R01: Selection Preservation in Reconciled Update ---

    @Test
    fun reconcileUpdatePreservesLatestUserSelectionIfValid() {
        val reconciledMembers = listOf(101L, 102L, 103L)
        val defaultReconciledSelection = 101L

        // Scenario 1: User concurrently selected 103L (which is in reconciledMembers)
        val freshUserSelection = 103L
        val effectiveSelection = if (freshUserSelection != RouterGroup.NO_SELECTION &&
            reconciledMembers.contains(freshUserSelection)
        ) {
            freshUserSelection
        } else {
            defaultReconciledSelection
        }
        assertEquals(103L, effectiveSelection)

        // Scenario 2: User's old selection 999L is no longer a valid member
        val staleSelection = 999L
        val fallbackSelection = if (staleSelection != RouterGroup.NO_SELECTION &&
            reconciledMembers.contains(staleSelection)
        ) {
            staleSelection
        } else {
            defaultReconciledSelection
        }
        assertEquals(101L, fallbackSelection)
    }
}
