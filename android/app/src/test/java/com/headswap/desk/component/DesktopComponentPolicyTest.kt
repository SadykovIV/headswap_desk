package com.headswap.desk.component

import android.content.pm.ActivityInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopComponentPolicyTest {
    private val pkg = "org.example.desktop"
    private val host = "com.headswap.desk"
    private val pin = DesktopComponentPolicy.Pin("$pkg/$pkg.DesktopActivity", 11, setOf("signerA", "signerB"))

    @Test fun panelCannotReclaimAnExistingPhoneTask() {
        fun ok(launch: Int, document: Int = ActivityInfo.DOCUMENT_LAUNCH_NONE, reparent: Boolean = false) =
            DesktopComponentPolicy.supportsIsolatedTask(launch, document, reparent)
        assertTrue(ok(ActivityInfo.LAUNCH_MULTIPLE))
        assertTrue(ok(ActivityInfo.LAUNCH_SINGLE_TOP))
        assertFalse(ok(ActivityInfo.LAUNCH_SINGLE_TASK))
        assertFalse(ok(ActivityInfo.LAUNCH_SINGLE_INSTANCE))
        assertFalse(ok(ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK))
        assertFalse(ok(ActivityInfo.LAUNCH_MULTIPLE, ActivityInfo.DOCUMENT_LAUNCH_INTO_EXISTING))
        assertFalse(ok(ActivityInfo.LAUNCH_MULTIPLE, reparent = true))
    }

    @Test fun exactProfileAndIntegerApiAreRequiredOnAccessibleEnabledActivity() {
        fun accepts(api: Any?, profile: Any?, exported: Boolean = true, enabled: Boolean = true, accessible: Boolean = true) =
            DesktopComponentPolicy.acceptsDeclaration(api, profile, exported, enabled, accessible)
        assertTrue(accepts(1, "android-desktop"))
        listOf(null, 2, "1", 1L, true).forEach { assertFalse(accepts(it, "android-desktop")) }
        listOf(null, "drivedeck", "samsung-dex", "ANDROID-DESKTOP", "", 1).forEach { assertFalse(accepts(1, it)) }
        assertFalse(accepts(1, "android-desktop", exported = false))
        assertFalse(accepts(1, "android-desktop", enabled = false))
        assertFalse(accepts(1, "android-desktop", accessible = false))
    }

    @Test fun rejectsSelfSharedUidAndCrossUser() {
        fun accepts(name: String = pkg, uid: Int = 10112, sameUser: Boolean = true,
                    shared: String? = null, packages: Set<String> = setOf(pkg)) =
            DesktopComponentPolicy.acceptsOwner(name, host, 10110, uid, sameUser, shared, packages)
        assertTrue(accepts())
        assertFalse(accepts(name = host))
        assertFalse(accepts(uid = 10110))
        assertFalse(accepts(sameUser = false))
        assertFalse(accepts(shared = "org.example.group"))
        assertFalse(accepts(packages = setOf(pkg, "org.example.peer")))
        assertFalse(accepts(packages = emptySet()))
    }

    @Test fun optionalModeClaimIsJustAValidatedToken() {
        fun accepts(mode: Any?) = DesktopComponentPolicy.acceptsDeclaration(1, "android-desktop", true, true, true, mode)
        listOf(null, "carbridge", "reader_2", "x.test-1", "a".repeat(64)).forEach { assertTrue(accepts(it)) }
        listOf("", "CarBridge", "car/bridge", ".x", "safe\nname", "a".repeat(65), 1).forEach { assertFalse(accepts(it)) }
        // A matching name supplied by two packages does not make the second package inherit approval.
        assertFalse(pin.accepts(pin.copy(component = "org.other/org.other.CarBridge")))
    }

    @Test fun trustPinsExactComponentCurrentSignersAndUserRatherThanLabelOrVersion() {
        assertTrue(pin.accepts(pin.copy(signers = setOf("signerB", "signerA"))))
        assertFalse(pin.accepts(null)) // Removed provider.
        assertFalse(pin.accepts(pin.copy(component = "$pkg/$pkg.SecondActivity")))
        assertFalse(pin.accepts(pin.copy(component = "org.lookalike/$pkg.DesktopActivity")))
        assertFalse(pin.accepts(pin.copy(signers = setOf("signerA"))))
        assertFalse(pin.accepts(pin.copy(signers = setOf("signerC"))))
        assertFalse(pin.accepts(pin.copy(modeId = "carbridge")))
        assertFalse(pin.accepts(pin.copy(userSerial = 12)))
        val empty = pin.copy(signers = emptySet())
        assertFalse(empty.accepts(empty))
        val unknownUser = pin.copy(userSerial = -1)
        assertFalse(unknownUser.accepts(unknownUser))
    }
}
