package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkChangePolicyTest {

    @Test
    fun firstAndSameInterfaceDoNothing() {
        assertEquals(
            NetworkChangeAction.NONE,
            networkChangeAction(null, "wlan0", restartProfile = true, resetConnections = true),
        )
        assertEquals(
            NetworkChangeAction.NONE,
            networkChangeAction("wlan0", null, restartProfile = true, resetConnections = true),
        )
        assertEquals(
            NetworkChangeAction.NONE,
            networkChangeAction("wlan0", "wlan0", restartProfile = true, resetConnections = true),
        )
    }

    @Test
    fun restartTakesPriorityForInterfaceChange() {
        assertEquals(
            NetworkChangeAction.RESTART_PROFILE,
            networkChangeAction(
                "rmnet_data0",
                "wlan0",
                restartProfile = true,
                resetConnections = true,
            ),
        )
    }

    @Test
    fun resetRemainsFallbackWhenRestartIsDisabled() {
        assertEquals(
            NetworkChangeAction.RESET_CONNECTIONS,
            networkChangeAction(
                "wlan0",
                "rmnet_data0",
                restartProfile = false,
                resetConnections = true,
            ),
        )
        assertEquals(
            NetworkChangeAction.NONE,
            networkChangeAction(
                "wlan0",
                "rmnet_data0",
                restartProfile = false,
                resetConnections = false,
            ),
        )
    }
}
