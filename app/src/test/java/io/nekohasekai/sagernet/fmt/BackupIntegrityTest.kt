package io.nekohasekai.sagernet.fmt

import com.esotericsoftware.kryo.KryoException
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupIntegrityTest {
    @Test
    fun strictBackupDecodeRejectsTruncatedNestedProtocolData() {
        val bean = SOCKSBean().apply { initializeDefaultValues() }
        val bytes = KryoConverters.serialize(bean)
        assertThrows(KryoException::class.java) {
            KryoConverters.withStrictDeserialization {
                KryoConverters.socksDeserialize(bytes.copyOf(bytes.size - 3))
            }
        }
        val decoded = KryoConverters.withStrictDeserialization { KryoConverters.socksDeserialize(bytes) }
        assertEquals(1080, decoded.serverPort)
    }

    @Test
    fun importedRulesCannotReferenceMissingRoutersOrProfiles() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupSerializer.validateRuleReferences(listOf(RuleEntity(id = 1, routerGroupId = 3)), emptySet(), setOf(3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupSerializer.validateRuleReferences(listOf(RuleEntity(id = 1, outbound = 3)), setOf(3), emptySet())
        }
        BackupSerializer.validateRuleReferences(
            listOf(RuleEntity(outbound = 0), RuleEntity(outbound = -1), RuleEntity(outbound = -2),
                RuleEntity(routerGroupId = 3, outbound = 999)), setOf(3), emptySet(),
        )
    }
}
