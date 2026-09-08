package io.nekohasekai.sagernet.database

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows
import io.nekohasekai.sagernet.GroupType

class ProxyGroupBackupTest {
    @Test
    fun exportRejectsSubscriptionGroupWithNoSubscriptionData() {
        assertThrows(IllegalStateException::class.java) {
            ProxyGroup(type = GroupType.SUBSCRIPTION).serializeToBuffer(ByteBufferOutput(256, -1))
        }
    }
    @Test
    fun backupRoundTripPreservesSelectorAndChainEndpoints() {
        val group = ProxyGroup(id = 5, name = "Local", isSelector = true, frontProxy = 11, landingProxy = 12)
        val output = ByteBufferOutput(256, -1)
        group.serializeToBuffer(output)
        val restored = ProxyGroup()
        restored.deserializeFromBuffer(ByteBufferInput(output.toBytes()))
        assertTrue(restored.isSelector)
        assertEquals(11L, restored.frontProxy)
        assertEquals(12L, restored.landingProxy)
    }

    @Test
    fun legacyGroupRecordKeepsDefaultSelectorAndChainEndpoints() {
        val output = ByteBufferOutput(256, -1)
        output.writeInt(0)
        output.writeLong(5)
        output.writeLong(1)
        output.writeBoolean(true)
        output.writeString("Local")
        output.writeInt(0)
        output.writeInt(0)
        val restored = ProxyGroup()
        restored.deserializeFromBuffer(ByteBufferInput(output.toBytes()))
        assertEquals(5L, restored.id)
        assertEquals(false, restored.isSelector)
        assertEquals(-1L, restored.frontProxy)
        assertEquals(-1L, restored.landingProxy)
    }
}
