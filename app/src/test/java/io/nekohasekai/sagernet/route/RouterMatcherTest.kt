package io.nekohasekai.sagernet.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RouterMatcherTest {

    @Test
    fun sameNodeMayAppearInMultipleGroups() {
        val node = RouterNodeSnapshot(7, "node-a", "US A", subscriptionId = 10)
        val requests = listOf(
            RouterMatchRequest(1, listOf(10), RouterFilterConfig(includeRegex = "US").validate()),
            RouterMatchRequest(2, listOf(10), RouterFilterConfig(includeRegex = "A").validate()),
        )
        assertEquals(
            mapOf(1L to listOf(7L), 2L to listOf(7L)),
            RouterMatcher.match(listOf(node), requests),
        )
    }

    @Test
    fun matchesOnlySelectedSourcesAndPreservesSourceNodeOrder() {
        val nodes = listOf(
            RouterNodeSnapshot(30, "a", "US 2", subscriptionId = 10),
            RouterNodeSnapshot(10, "b", "US 1", subscriptionId = 10),
            RouterNodeSnapshot(20, "c", "US other source", subscriptionId = 20),
        )
        val request = RouterMatchRequest(1, listOf(10), RouterFilterConfig(includeRegex = "US").validate())
        assertEquals(listOf(30L, 10L), RouterMatcher.match(nodes, listOf(request))[1])
    }

    @Test
    fun emptyIncludeMatchesAllAndExcludeWins() {
        val nodes = listOf(
            RouterNodeSnapshot(1, "a", "US Premium", subscriptionId = 10),
            RouterNodeSnapshot(2, "b", "US Expired", subscriptionId = 10),
            RouterNodeSnapshot(3, "c", "Singapore", subscriptionId = 10),
        )
        val request = RouterMatchRequest(3, listOf(10), RouterFilterConfig(excludeRegex = "Expired").validate())
        assertEquals(listOf(1L, 3L), RouterMatcher.match(nodes, listOf(request))[3])
    }

    @Test
    fun disabledUnavailableAndDuplicateNodesAreIgnoredWithinAGroup() {
        val nodes = listOf(
            RouterNodeSnapshot(1, "dead", "US", subscriptionId = 10, enabled = false),
            RouterNodeSnapshot(2, "bad", "US", subscriptionId = 10, available = false),
            RouterNodeSnapshot(3, "live", "US", subscriptionId = 10),
            RouterNodeSnapshot(3, "copy", "US copy", subscriptionId = 10),
        )
        val request = RouterMatchRequest(1, listOf(10), RouterFilterConfig().validate())
        assertEquals(listOf(3L), RouterMatcher.match(nodes, listOf(request))[1])
    }

    @Test
    fun invalidRegexIdentifiesTheField() {
        assertEquals(
            RouterFilterException.Field.INCLUDE,
            assertThrows(RouterFilterException::class.java) {
                RouterFilterConfig(includeRegex = "[").validate()
            }.field,
        )
        assertEquals(
            RouterFilterException.Field.EXCLUDE,
            assertThrows(RouterFilterException::class.java) {
                RouterFilterConfig(excludeRegex = "[").validate()
            }.field,
        )
    }
}
