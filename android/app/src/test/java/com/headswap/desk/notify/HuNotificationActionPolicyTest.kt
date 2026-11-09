package com.headswap.desk.notify

import org.junit.Assert.*
import org.junit.Test

class HuNotificationActionPolicyTest {
    @Test fun onlySameUserActivityCapabilityIsAccepted() {
        for (activity in listOf(false, true)) for (pkg in listOf(false, true))
            for (uid in listOf(false, true)) for (user in listOf(false, true))
                assertEquals(activity && pkg && uid && user, HuNotificationActionPolicy.allowed(activity, pkg, uid, user))
    }
}
