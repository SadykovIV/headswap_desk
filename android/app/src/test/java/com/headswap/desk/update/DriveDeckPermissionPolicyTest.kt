package com.headswap.desk.update

import org.junit.Assert.*
import org.junit.Test

class DriveDeckPermissionPolicyTest {
    @Test fun onlyTheExactDriveDeckListenerIsTheMusicGrant() {
        val exact = DriveDeckPermissionPolicy.LISTENER
        assertTrue(DriveDeckPermissionPolicy.listenerEnabled(exact))
        assertTrue(DriveDeckPermissionPolicy.listenerEnabled("other/Listener:$exact:third/Listener"))
        for (value in listOf(null, "", "$exact.fake", "fake.$exact", "com.headswap.desk/NotificationListener",
            "ru.drivedeck.launcher/ru.drivedeck.launcher.NotificationBridgeService",
            "ru.drivedeck.headswap/OtherListener"))
            assertFalse(value, DriveDeckPermissionPolicy.listenerEnabled(value))
    }
}
