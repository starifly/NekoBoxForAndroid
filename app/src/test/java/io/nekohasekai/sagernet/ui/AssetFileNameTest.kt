package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for sanitizeAssetFileName (Plan 015): an untrusted DISPLAY_NAME must be reduced to a
 * safe basename, with path traversal rejected.
 */
class AssetFileNameTest {

    @Test
    fun validDbName_passesThrough() {
        assertEquals("geoip.db", sanitizeAssetFileName("geoip.db"))
        assertEquals("geosite.db", sanitizeAssetFileName("geosite.db"))
    }

    @Test
    fun traversalName_rejected() {
        // The regression: a ".db"-suffixed traversal name must not pass.
        assertNull(sanitizeAssetFileName("../../databases/sager_net.db"))
    }

    @Test
    fun separatorsAndDotDot_rejected() {
        assertNull(sanitizeAssetFileName("a/b.db"))
        assertNull(sanitizeAssetFileName(".."))
        assertNull(sanitizeAssetFileName("."))
        assertNull(sanitizeAssetFileName("\u0000x.db"))
        assertNull(sanitizeAssetFileName(""))
    }

    @Test
    fun nonDb_rejected() {
        assertNull(sanitizeAssetFileName("evil.sh"))
        assertNull(sanitizeAssetFileName("geoip.db.tmp"))
    }
}
