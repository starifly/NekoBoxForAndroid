package io.nekohasekai.sagernet.fmt.internal

import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.KryoConverters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChainBeanTest {

    @Test
    fun roundTripsAllStrategies() {
        for (strategy in listOf(
            ChainBean.STRATEGY_CHAIN,
            ChainBean.STRATEGY_WATERFALL,
            ChainBean.STRATEGY_FASTEST,
        )) {
            val source = ChainBean().apply {
                initializeDefaultValues()
                name = "group-$strategy"
                this.strategy = strategy
                proxies = listOf(11L, 22L, 33L)
            }

            val restored = KryoConverters.chainDeserialize(KryoConverters.serialize(source))

            assertEquals(strategy, restored.strategy)
            assertEquals(source.name, restored.name)
            assertEquals(source.proxies, restored.proxies)
        }
    }

    @Test
    fun versionOneDataDefaultsToChainStrategy() {
        val output = ByteBufferOutput(128)
        output.writeInt(1)
        output.writeInt(2)
        output.writeLong(7L)
        output.writeLong(8L)
        output.writeInt(1)
        output.writeString("legacy")
        output.writeString("")
        output.writeString("")

        val restored = KryoConverters.chainDeserialize(output.toBytes())

        assertEquals(ChainBean.STRATEGY_CHAIN, restored.strategy)
        assertEquals(listOf(7L, 8L), restored.proxies)
        assertEquals("legacy", restored.name)
    }

    @Test
    fun strategiesMapToDistinctProfileTypes() {
        val expectedTypes = mapOf(
            ChainBean.STRATEGY_CHAIN to ProxyEntity.TYPE_CHAIN,
            ChainBean.STRATEGY_WATERFALL to ProxyEntity.TYPE_WATERFALL,
            ChainBean.STRATEGY_FASTEST to ProxyEntity.TYPE_FASTEST,
        )

        for ((strategy, expectedType) in expectedTypes) {
            val bean = ChainBean().apply {
                initializeDefaultValues()
                this.strategy = strategy
                proxies = listOf(1L)
            }
            val entity = ProxyEntity().putBean(bean)

            assertEquals(expectedType, entity.type)
            assertSame(bean, entity.chainBean)
        }
    }
}
