package io.nekohasekai.sagernet.database

import android.app.Application
import android.database.sqlite.SQLiteException
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GroupManagerDeleteTest {

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
    }

    @Test
    fun deleteGroup_singleSuccessCommitsBeforeSideEffects() = runTest {
        withContext(Dispatchers.IO) {
            val (group, profile) = createGroupWithProfile(1L)
            DataStore.selectedProxy = profile.id
            val listener = RecordingListener()
            var reconfigurationCount = 0
            GroupManager.addListener(listener)
            try {
                GroupManager.deleteGroup(group.id) { reconfigurationCount++ }

                assertNull(SagerDatabase.groupDao.getById(group.id))
                assertEquals(emptyList<Long>(), SagerDatabase.proxyDao.getIdsByGroup(group.id))
                assertEquals(0L, DataStore.selectedProxy)
                assertEquals(listOf(group.id), listener.removedGroupIds)
                assertEquals(1, reconfigurationCount)
            } finally {
                GroupManager.removeListener(listener)
            }
        }
    }

    @Test
    fun deleteGroup_batchSuccessCommitsBeforeSideEffects() = runTest {
        withContext(Dispatchers.IO) {
            val (firstGroup, firstProfile) = createGroupWithProfile(1L)
            val (secondGroup, secondProfile) = createGroupWithProfile(2L)
            val (untargetedGroup, untargetedProfile) = createGroupWithProfile(3L)
            DataStore.selectedProxy = secondProfile.id
            val listener = RecordingListener()
            var reconfigurationCount = 0
            GroupManager.addListener(listener)
            try {
                GroupManager.deleteGroup(listOf(firstGroup, secondGroup)) {
                    reconfigurationCount++
                }

                assertEquals(
                    setOf(untargetedGroup.id),
                    SagerDatabase.groupDao.allGroups().map { it.id }.toSet(),
                )
                assertNull(SagerDatabase.proxyDao.getById(firstProfile.id))
                assertNull(SagerDatabase.proxyDao.getById(secondProfile.id))
                assertNotNull(SagerDatabase.proxyDao.getById(untargetedProfile.id))
                assertEquals(0L, DataStore.selectedProxy)
                assertEquals(
                    listOf(firstGroup.id, secondGroup.id),
                    listener.removedGroupIds,
                )
                assertEquals(1, reconfigurationCount)
            } finally {
                GroupManager.removeListener(listener)
            }
        }
    }

    @Test
    fun deleteGroup_doesNotClearASelectionChangedAfterDeletionStarted() = runTest {
        withContext(Dispatchers.IO) {
            val (_, deletedProfile) = createGroupWithProfile(1L)
            val (_, replacementProfile) = createGroupWithProfile(2L)
            DataStore.selectedProxy = deletedProfile.id
            val selectedBeforeDelete = DataStore.selectedProxy
            DataStore.selectedProxy = replacementProfile.id

            GroupManager.clearDeletedSelection(selectedBeforeDelete, selectedWasDeleted = true)

            assertEquals(replacementProfile.id, DataStore.selectedProxy)
        }
    }

    @Test
    fun deleteGroup_singleRollbackRestoresRowsAndSuppressesSideEffects() = runTest {
        withContext(Dispatchers.IO) {
            val (group, profile) = createGroupWithProfile(1L)
            DataStore.selectedProxy = profile.id
            val listener = RecordingListener()
            var reconfigurationCount = 0
            GroupManager.addListener(listener)
            try {
                withFailingGroupDeleteTrigger {
                    assertDeletionFails {
                        GroupManager.deleteGroup(group.id) { reconfigurationCount++ }
                    }
                }

                assertNotNull(SagerDatabase.groupDao.getById(group.id))
                assertEquals(listOf(profile.id), SagerDatabase.proxyDao.getIdsByGroup(group.id))
                assertEquals(profile.id, DataStore.selectedProxy)
                assertEquals(emptyList<Long>(), listener.removedGroupIds)
                assertEquals(0, reconfigurationCount)
            } finally {
                GroupManager.removeListener(listener)
            }
        }
    }

    @Test
    fun deleteGroup_batchRollbackRestoresRowsAndSuppressesSideEffects() = runTest {
        withContext(Dispatchers.IO) {
            val (firstGroup, firstProfile) = createGroupWithProfile(1L)
            val (secondGroup, secondProfile) = createGroupWithProfile(2L)
            DataStore.selectedProxy = secondProfile.id
            val listener = RecordingListener()
            var reconfigurationCount = 0
            GroupManager.addListener(listener)
            try {
                withFailingGroupDeleteTrigger {
                    assertDeletionFails {
                        GroupManager.deleteGroup(listOf(firstGroup, secondGroup)) {
                            reconfigurationCount++
                        }
                    }
                }

                assertEquals(
                    setOf(firstGroup.id, secondGroup.id),
                    SagerDatabase.groupDao.allGroups().map { it.id }.toSet(),
                )
                assertEquals(
                    listOf(firstProfile.id),
                    SagerDatabase.proxyDao.getIdsByGroup(firstGroup.id),
                )
                assertEquals(
                    listOf(secondProfile.id),
                    SagerDatabase.proxyDao.getIdsByGroup(secondGroup.id),
                )
                assertEquals(secondProfile.id, DataStore.selectedProxy)
                assertEquals(emptyList<Long>(), listener.removedGroupIds)
                assertEquals(0, reconfigurationCount)
            } finally {
                GroupManager.removeListener(listener)
            }
        }
    }

    @Test
    fun deleteGroup_publicWrappersSuppressSideEffectsOnRollback() = runTest {
        withContext(Dispatchers.IO) {
            val (singleGroup, singleProfile) = createGroupWithProfile(1L)
            val (batchGroup, batchProfile) = createGroupWithProfile(2L)
            val listener = RecordingListener()
            GroupManager.addListener(listener)
            try {
                withFailingGroupDeleteTrigger {
                    DataStore.selectedProxy = singleProfile.id
                    assertDeletionFails { GroupManager.deleteGroup(singleGroup.id) }
                    assertEquals(singleProfile.id, DataStore.selectedProxy)

                    DataStore.selectedProxy = batchProfile.id
                    assertDeletionFails { GroupManager.deleteGroup(listOf(batchGroup)) }
                    assertEquals(batchProfile.id, DataStore.selectedProxy)
                }

                assertNotNull(SagerDatabase.groupDao.getById(singleGroup.id))
                assertNotNull(SagerDatabase.groupDao.getById(batchGroup.id))
                assertEquals(
                    listOf(singleProfile.id),
                    SagerDatabase.proxyDao.getIdsByGroup(singleGroup.id),
                )
                assertEquals(
                    listOf(batchProfile.id),
                    SagerDatabase.proxyDao.getIdsByGroup(batchGroup.id),
                )
                assertEquals(emptyList<Long>(), listener.removedGroupIds)
            } finally {
                GroupManager.removeListener(listener)
            }
        }
    }

    private fun createGroupWithProfile(order: Long): Pair<ProxyGroup, ProxyEntity> {
        val group = ProxyGroup(userOrder = order).apply {
            id = SagerDatabase.groupDao.createGroup(this)
        }
        val profile = ProxyEntity(groupId = group.id, userOrder = 1L).apply {
            id = SagerDatabase.proxyDao.addProxy(this)
        }
        return group to profile
    }

    private suspend fun withFailingGroupDeleteTrigger(block: suspend () -> Unit) {
        val database = SagerDatabase.instance.openHelper.writableDatabase
        database.execSQL(
            """
            CREATE TEMP TRIGGER fail_group_delete
            BEFORE DELETE ON proxy_groups
            BEGIN
                SELECT RAISE(ABORT, 'forced group deletion failure');
            END
            """.trimIndent(),
        )
        try {
            block()
        } finally {
            database.execSQL("DROP TRIGGER IF EXISTS fail_group_delete")
        }
    }

    private suspend fun assertDeletionFails(block: suspend () -> Unit) {
        var failure: SQLiteException? = null
        try {
            block()
        } catch (exception: SQLiteException) {
            failure = exception
        }
        assertNotNull("Expected group deletion to fail", failure)
    }

    private class RecordingListener : GroupManager.Listener {
        val removedGroupIds = mutableListOf<Long>()

        override suspend fun groupAdd(group: ProxyGroup) = Unit

        override suspend fun groupUpdated(group: ProxyGroup) = Unit

        override suspend fun groupRemoved(groupId: Long) {
            removedGroupIds += groupId
        }

        override suspend fun groupUpdated(groupId: Long) = Unit
    }
}
