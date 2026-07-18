package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class AppFilteringTest {
    private data class Entry(
        override val name: CharSequence,
        override val packageName: String,
        override val uid: Int,
        override val sys: Boolean = false,
    ) : AppFilterEntry

    private val apps = listOf(
        Entry("Alpha Reader", "org.example.reader", 10234),
        Entry("Beta Notes", "com.sample.WRITER", 20567, sys = true),
        Entry("Gamma Maps", "net.example.maps", 30123),
    )

    @Test
    fun query_matchesNameIgnoringCase() {
        assertEquals(listOf(apps[0]), filterApps(apps, "ALPHA", includeSystem = true))
    }

    @Test
    fun query_matchesPackageIgnoringCase() {
        assertEquals(listOf(apps[1]), filterApps(apps, "writer", includeSystem = true))
    }

    @Test
    fun query_matchesUidSubstring() {
        assertEquals(listOf(apps[2]), filterApps(apps, "123", includeSystem = true))
    }

    @Test
    fun systemApps_areIncludedOrExcludedAsRequested() {
        assertEquals(listOf(apps[1]), filterApps(apps, "notes", includeSystem = true))
        assertEquals(emptyList<Entry>(), filterApps(apps, "notes", includeSystem = false))
    }

    @Test
    fun emptyQuery_matchesAllBeforeSystemFiltering() {
        assertEquals(apps, filterApps(apps, "", includeSystem = true))
        assertEquals(listOf(apps[0], apps[2]), filterApps(apps, "", includeSystem = false))
    }

    @Test
    fun noMatch_returnsEmptyList() {
        assertEquals(emptyList<Entry>(), filterApps(apps, "missing", includeSystem = true))
    }

    @Test
    fun results_preserveOrderAndDoNotMutateInput() {
        val input = apps.toMutableList()
        val original = input.toList()

        val result = filterApps(input, "example", includeSystem = true)

        assertEquals(listOf(apps[0], apps[2]), result)
        assertEquals(original, input)
        assertNotSame(input, result)
    }
}
