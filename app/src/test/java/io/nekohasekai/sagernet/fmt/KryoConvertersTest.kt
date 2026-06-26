package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM round-trip + boundary tests for KryoConverters.
 *
 * These exercise only the Kryo serialize/deserialize path (no JNI/libcore), so they run on
 * the JVM unit-test source set. The corruption-recovery path calls Logs.w (-> libcore JNI),
 * so it is covered by an instrumented test instead (not unit-testable on the JVM).
 *
 * The round-trip fixtures here also seed the byte-stability checks Plan 021 (kryo bump) needs.
 */
class KryoConvertersTest {

    @Test
    fun roundTrip_socksBean_preservesFields() {
        val bean = SOCKSBean().apply {
            serverAddress = "192.0.2.10"
            serverPort = 1080
            username = "user"
            password = "pass"
            protocol = 2
            sUoT = true
            name = "my socks"
        }
        val bytes = KryoConverters.serialize(bean)
        val decoded = KryoConverters.socksDeserialize(bytes)!!

        assertEquals("192.0.2.10", decoded.serverAddress)
        assertEquals(1080, decoded.serverPort)
        assertEquals("user", decoded.username)
        assertEquals("pass", decoded.password)
        assertEquals(2, decoded.protocol)
        assertEquals(true, decoded.sUoT)
        assertEquals("my socks", decoded.name)
    }

    @Test
    fun roundTrip_trojanBean_preservesFields() {
        val bean = TrojanBean().apply {
            serverAddress = "example.com"
            serverPort = 443
            password = "secret"
            name = "trojan node"
        }
        bean.initializeDefaultValues()
        val bytes = KryoConverters.serialize(bean)
        val decoded = KryoConverters.trojanDeserialize(bytes)!!

        assertEquals("example.com", decoded.serverAddress)
        assertEquals(443, decoded.serverPort)
        assertEquals("secret", decoded.password)
        assertEquals("trojan node", decoded.name)
    }

    @Test
    fun roundTrip_serializationIsStableAcrossDecode() {
        // Stronger than serialize-vs-itself: serialize -> deserialize -> serialize must yield
        // identical bytes, catching any asymmetry between the write and read paths (a decode
        // that drops/reorders a field would change the re-serialized bytes). A checked-in
        // golden fixture for cross-version drift detection is added with Plan 021 (kryo bump).
        val bean = SOCKSBean().apply {
            serverAddress = "10.0.0.1"
            serverPort = 1080
            username = "u"
            password = "p"
            protocol = 2
            sUoT = false
            initializeDefaultValues()
        }
        val first = KryoConverters.serialize(bean)
        val decoded = KryoConverters.socksDeserialize(first)!!
        val second = KryoConverters.serialize(decoded)
        assertArrayEquals(first, second)
    }

    @Test
    fun nullBytes_typedDeserializerReturnsNull() {
        // Empty/null blob -> typed converter returns null (JavaUtil.isEmpty guard).
        assertNull(KryoConverters.socksDeserialize(null))
        assertNull(KryoConverters.socksDeserialize(ByteArray(0)))
    }

    @Test
    fun nullBean_serializesToEmpty() {
        assertArrayEquals(ByteArray(0), KryoConverters.serialize(null))
    }
}
