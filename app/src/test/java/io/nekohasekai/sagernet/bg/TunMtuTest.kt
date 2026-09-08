package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Test

class TunMtuTest {
    @Test
    fun usesCoreMtuInsteadOfGlobalDefault() {
        // libcore marshals sing-tun.Options, whose exported field is uppercase MTU.
        assertEquals(1420, tunMtu("""{"MTU":1420}""", 9000))
    }

    @Test
    fun missingMtuKeepsGlobalDefault() {
        assertEquals(9000, tunMtu("{}", 9000))
    }
}
