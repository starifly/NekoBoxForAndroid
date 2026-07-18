package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RawUpdaterReconcileTest {

    @Test
    fun unchanged_leavesContentAndOrderUnchanged() {
        val entity = entity(bean(), userOrder = 1L)

        val result = RawUpdater.reconcileExistingProfile(entity, bean(), userOrder = 1L)

        assertFalse(result.contentChanged)
        assertFalse(result.orderChanged)
        assertEquals("192.0.2.1", entity.requireBean().serverAddress)
        assertEquals(1L, entity.userOrder)
    }

    @Test
    fun contentChange_persistsContentWithoutChangingOrder() {
        val entity = entity(bean(), userOrder = 1L)
        val incoming = bean().apply { serverAddress = "192.0.2.2" }

        val result = RawUpdater.reconcileExistingProfile(entity, incoming, userOrder = 1L)

        assertTrue(result.contentChanged)
        assertFalse(result.orderChanged)
        assertEquals("192.0.2.2", entity.requireBean().serverAddress)
        assertEquals(1L, entity.userOrder)
    }

    @Test
    fun orderChange_persistsOrderWithoutChangingContent() {
        val entity = entity(bean(), userOrder = 1L)

        val result = RawUpdater.reconcileExistingProfile(entity, bean(), userOrder = 2L)

        assertFalse(result.contentChanged)
        assertTrue(result.orderChanged)
        assertEquals("192.0.2.1", entity.requireBean().serverAddress)
        assertEquals(2L, entity.userOrder)
    }

    @Test
    fun contentAndOrderChange_persistsBothOnSameEntity() {
        val entity = entity(bean(), userOrder = 1L)
        val incoming = bean().apply { serverAddress = "192.0.2.2" }

        val result = RawUpdater.reconcileExistingProfile(entity, incoming, userOrder = 2L)

        assertTrue(result.contentChanged)
        assertTrue(result.orderChanged)
        assertEquals("192.0.2.2", entity.requireBean().serverAddress)
        assertEquals(2L, entity.userOrder)
    }

    @Test
    fun contentChange_preservesStoredCustomOverrides() {
        val stored = bean().apply {
            customOutboundJson = """{"outbound":"stored"}"""
            customConfigJson = """{"config":"stored"}"""
        }
        val entity = entity(stored, userOrder = 1L)
        val incoming = bean().apply {
            serverAddress = "192.0.2.2"
            customOutboundJson = """{"outbound":"incoming"}"""
            customConfigJson = """{"config":"incoming"}"""
        }

        val result = RawUpdater.reconcileExistingProfile(entity, incoming, userOrder = 1L)
        val persisted = entity.requireBean()

        assertTrue(result.contentChanged)
        assertFalse(result.orderChanged)
        assertEquals("192.0.2.2", persisted.serverAddress)
        assertEquals("""{"outbound":"stored"}""", persisted.customOutboundJson)
        assertEquals("""{"config":"stored"}""", persisted.customConfigJson)
        assertEquals(1L, entity.userOrder)
    }

    private fun bean() = SOCKSBean().apply {
        serverAddress = "192.0.2.1"
        serverPort = 1080
        name = "profile"
        username = "user"
        password = "password"
        initializeDefaultValues()
    }

    private fun entity(bean: SOCKSBean, userOrder: Long) = ProxyEntity(userOrder = userOrder).apply {
        putBean(bean)
    }
}
