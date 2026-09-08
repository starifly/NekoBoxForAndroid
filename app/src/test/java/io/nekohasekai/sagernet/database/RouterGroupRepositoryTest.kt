package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.route.RouterFilterConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RouterGroupRepositoryTest {

    @Test
    fun enabledGroupRequiresASelectedSubscription() {
        val error = assertThrows(RouterGroupValidationException::class.java) {
            RouterGroupDraft(
                name = "US1",
                mode = RouterGroup.MODE_SELECTOR,
                enabled = true,
                sourceGroupIds = emptyList(),
                filter = RouterFilterConfig(),
            ).validate(emptyList(), setOf(10))
        }
        assertEquals(RouterGroupValidationException.Field.SOURCES, error.field)
    }

    @Test
    fun disabledEmptyDraftIsAllowed() {
        RouterGroupDraft(
            name = "Draft",
            mode = RouterGroup.MODE_SELECTOR,
            enabled = false,
            sourceGroupIds = emptyList(),
            filter = RouterFilterConfig(),
        ).validate(emptyList(), emptySet())
    }

    @Test
    fun groupNameMustBeUniqueIgnoringCaseAndWhitespace() {
        val error = assertThrows(RouterGroupValidationException::class.java) {
            RouterGroupDraft(
                id = 2,
                name = " us1 ",
                mode = RouterGroup.MODE_SELECTOR,
                enabled = true,
                sourceGroupIds = listOf(10),
                filter = RouterFilterConfig(),
            ).validate(listOf(RouterGroup(id = 1, name = "US1")), setOf(10))
        }
        assertEquals(RouterGroupValidationException.Field.NAME, error.field)
    }

    @Test
    fun invalidModeSourceAndTimingAreRejected() {
        val invalidMode = validDraft().copy(mode = 99)
        assertEquals(
            RouterGroupValidationException.Field.MODE,
            assertThrows(RouterGroupValidationException::class.java) {
                invalidMode.validate(emptyList(), setOf(10))
            }.field,
        )
        val missingSource = validDraft().copy(sourceGroupIds = listOf(11))
        assertEquals(
            RouterGroupValidationException.Field.SOURCES,
            assertThrows(RouterGroupValidationException::class.java) {
                missingSource.validate(emptyList(), setOf(10))
            }.field,
        )
        val invalidTiming = validDraft().copy(filter = RouterFilterConfig(intervalSeconds = 9))
        assertEquals(
            RouterGroupValidationException.Field.INTERVAL,
            assertThrows(RouterGroupValidationException::class.java) {
                invalidTiming.validate(emptyList(), setOf(10))
            }.field,
        )
    }

    private fun validDraft() = RouterGroupDraft(
        name = "US1",
        mode = RouterGroup.MODE_URL_TEST,
        enabled = true,
        sourceGroupIds = listOf(10),
        filter = RouterFilterConfig(),
    )
}
