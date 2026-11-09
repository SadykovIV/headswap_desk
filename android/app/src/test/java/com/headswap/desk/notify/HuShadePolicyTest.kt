package com.headswap.desk.notify

import org.junit.Assert.*
import org.junit.Test

class HuShadePolicyTest {
    @Test fun onlyTheLiveOwnedSecondaryScreenMayReceiveTheShade() {
        // Preserve the ownership/permission policy while the production feature is suspended.
        assertTrue(HuShadePolicy.canShow(true, true, true, 8, true, featureAvailable = true))
        assertFalse(HuShadePolicy.canShow(true, true, true, 0, true, featureAvailable = true))
        assertFalse(HuShadePolicy.canShow(true, true, true, -1, true, featureAvailable = true))
        assertFalse(HuShadePolicy.canShow(false, true, true, 8, true, featureAvailable = true))
        assertFalse(HuShadePolicy.canShow(true, false, true, 8, true, featureAvailable = true))
        assertFalse(HuShadePolicy.canShow(true, true, false, 8, true, featureAvailable = true))
        assertFalse(HuShadePolicy.canShow(true, true, true, 8, false, featureAvailable = true))
    }
    @Test fun suspensionOverridesSavedOptInEvenWithAllPermissionsAndALiveOwnedDisplay() {
        assertFalse(HuNotificationFeature.enabled(true))
        assertFalse(HuShadePolicy.canShow(true, true, true, 8, true))
    }
    @Test fun suspensionRemovesOnlyNotificationCapabilityFromOldAndNewComponentContracts() {
        val notification = 1 shl 6
        for (advertised in listOf(127, 0b10100101, 0, notification, Int.MAX_VALUE)) {
            val effective = HuNotificationFeature.capabilities(advertised)
            assertEquals(0, effective and notification)
            assertEquals(advertised and notification.inv(), effective)
        }
    }
    @Test fun lockScreenNeverRevealsNotificationContentEvenIfHuIsIndependent() {
        assertTrue(HuShadePolicy.revealText(false, false))
        assertFalse(HuShadePolicy.revealText(true, false))
        assertFalse(HuShadePolicy.revealText(false, true))
        assertFalse(HuShadePolicy.revealText(true, true))
    }
    @Test fun onlyClearableOrdinaryNotificationsEnterTheList() {
        fun entry(flags: Int = 0, clearable: Boolean = true, category: String? = null,
                  template: String? = null, pkg: String = "test.sender", title: String = "Title", text: String = "Body") =
            NotifRaw(pkg, flags, clearable, template, category, title, text)
        assertTrue(NotificationsLogic.accept(entry(), "com.headswap.desk"))
        for (flags in listOf(NotificationsLogic.FLAG_ONGOING, NotificationsLogic.FLAG_NO_CLEAR, NotificationsLogic.FLAG_GROUP_SUMMARY))
            assertFalse(NotificationsLogic.accept(entry(flags), "com.headswap.desk"))
        assertFalse(NotificationsLogic.accept(entry(clearable = false), "com.headswap.desk"))
        assertFalse(NotificationsLogic.accept(entry(category = "call"), "com.headswap.desk"))
        assertFalse(NotificationsLogic.accept(entry(template = "android.app.Notification\$MediaStyle"), "com.headswap.desk"))
        assertFalse(NotificationsLogic.accept(entry(pkg = "com.headswap.desk"), "com.headswap.desk"))
        assertFalse(NotificationsLogic.accept(entry(title = "", text = " "), "com.headswap.desk"))
    }
    @Test fun theBoundedListReplacesUpdatedNotificationsAndKeepsRecentFirst() {
        val entries = (1..100).map { NotifEntry(it.toString(), "pkg", "Title", "Body", it.toLong(), 3) }
        val bounded = entries.fold(emptyList<NotifEntry>(), NotificationsLogic::upsert)
        assertEquals(50, bounded.size)
        assertEquals("100", bounded.first().key)
        val updated = bounded.first().copy(text = "Updated", postTime = 110)
        val replacement = NotificationsLogic.upsert(bounded, updated)
        assertEquals(50, replacement.size)
        assertEquals(updated, replacement.first())
        assertEquals(1, replacement.count { it.key == updated.key })
    }
}
