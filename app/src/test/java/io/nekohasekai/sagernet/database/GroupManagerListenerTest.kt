package io.nekohasekai.sagernet.database

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class GroupManagerListenerTest {

    @Test
    fun defaultRouterGroupsUpdatedCallbackDoesNotThrow() = runBlocking {
        val listener = object : GroupManager.Listener {
            override suspend fun groupAdd(group: ProxyGroup) = Unit
            override suspend fun groupUpdated(group: ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit
            override suspend fun groupUpdated(groupId: Long) = Unit
        }
        // Default implementation does not throw
        listener.routerGroupsUpdated()
    }

    @Test
    fun iteratorInvokesRouterGroupsUpdated() = runBlocking {
        val invoked = AtomicBoolean(false)
        val listener = object : GroupManager.Listener {
            override suspend fun groupAdd(group: ProxyGroup) = Unit
            override suspend fun groupUpdated(group: ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit
            override suspend fun groupUpdated(groupId: Long) = Unit
            override suspend fun routerGroupsUpdated() {
                invoked.set(true)
            }
        }

        GroupManager.addListener(listener)
        try {
            GroupManager.iterator { routerGroupsUpdated() }
            assertTrue(invoked.get())
        } finally {
            GroupManager.removeListener(listener)
        }
    }
}
