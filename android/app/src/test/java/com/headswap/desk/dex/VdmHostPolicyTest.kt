package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class VdmHostPolicyTest {
    @Test fun deviceGroupReplacesLegacyIsolationAndPreservesImeFocusAndTaskCleanup() {
        val requested = DesktopModePolicy.displayFlags(DesktopModePolicy.ANDROID_DESKTOP, 37, true)
        val flags = VdmHostPolicy.displayFlags(requested or 4 or 16 or (1 shl 16))
        assertEquals(0, flags and (4 or 16 or (1 shl 11) or (1 shl 12) or (1 shl 16)))
        for (bit in listOf(1, 2, 8, 64, 256, 1024, 1 shl 14, 1 shl 15)) assertTrue(flags and bit != 0)
    }
    @Test fun neverClaimsOriginalHeadSwapOrAnotherShellAssociation() {
        assertTrue(VdmHostPolicy.ownAssociation(VdmHostPolicy.SHELL_PACKAGE, VdmHostPolicy.PROFILE, VdmHostPolicy.ASSOCIATION_MAC.lowercase()))
        assertFalse(VdmHostPolicy.ownAssociation(VdmHostPolicy.SHELL_PACKAGE, VdmHostPolicy.PROFILE, "02:48:53:57:41:50"))
        assertFalse(VdmHostPolicy.ownAssociation("com.headswap.desk", VdmHostPolicy.PROFILE, VdmHostPolicy.ASSOCIATION_MAC))
        assertFalse(VdmHostPolicy.ownAssociation(VdmHostPolicy.SHELL_PACKAGE, null, VdmHostPolicy.ASSOCIATION_MAC))
    }
    @Test fun bothPlatformParsersReceiveExactProfileAndMacWithoutRestoreOrRoleCommands() {
        val legacy = VdmHostPolicy.associateCommand(10, false)
        val named = VdmHostPolicy.associateCommand(10, true)
        assertEquals(listOf("/system/bin/cmd", "companiondevice", "associate", "10", "com.android.shell"), legacy.take(5))
        assertEquals(listOf(VdmHostPolicy.ASSOCIATION_MAC, VdmHostPolicy.PROFILE, "false"), legacy.drop(5))
        assertEquals(VdmHostPolicy.ASSOCIATION_MAC, named[named.indexOf("--mac-address") + 1])
        assertEquals(VdmHostPolicy.PROFILE, named[named.indexOf("--profile") + 1])
        assertFalse((legacy + named).any { it.contains("restore") || it.contains("bypass") })
    }
    @Test fun challengesExpireAndDoNotAuthenticateWithMissingOrDifferentSecrets() {
        val secret = VdmHostPolicy.nonce()
        assertTrue(VdmHostPolicy.validNonce(secret))
        assertTrue(VdmHostPolicy.sameSecret(secret, secret))
        assertFalse(VdmHostPolicy.sameSecret(secret, VdmHostPolicy.nonce()))
        assertFalse(VdmHostPolicy.sameSecret(null, null))
        assertTrue(VdmHostPolicy.launchFresh(100, 30_100))
        assertFalse(VdmHostPolicy.launchFresh(100, 30_101))
        assertFalse(VdmHostPolicy.launchFresh(100, 99))
    }
    @Test fun parserProbeRequiresActualMacParseFailureAndNeverAcceptsSuccessOrAmbiguity() {
        val legacy = "Exception occurred while executing 'associate':\njava.lang.IllegalArgumentException: --mac-address was not a valid MAC address\n at android.net.MacAddress.fromString(MacAddress.java:316)"
        val named = legacy.replace("--mac-address", "headswap-desk-parser-probe")
        assertEquals(false, VdmHostPolicy.parserFromProbe(1, legacy))
        assertEquals(true, VdmHostPolicy.parserFromProbe(1, named))
        assertNull(VdmHostPolicy.parserFromProbe(0, legacy))
        assertNull(VdmHostPolicy.parserFromProbe(1, legacy + "\n" + named))
        assertNull(VdmHostPolicy.parserFromProbe(1, legacy.replace("android.net.MacAddress.", "some.OtherParser.")))
        assertNull(VdmHostPolicy.parserFromProbe(1, "Unknown option --mac-address"))
        val command = VdmHostPolicy.parserProbeCommand(0)
        assertFalse(command.contains(VdmHostPolicy.ASSOCIATION_MAC))
        assertEquals("headswap-desk-parser-probe", command[command.indexOf("--mac-address") + 1])
    }
    @Test fun commandLineCannotTargetAnotherPackageOrDiscloseNonceThroughToString() {
        val nonce = "a".repeat(64)
        val args = VdmHostPolicy.Args.parse(arrayOf("--package", "com.headswap.desk", "--app-pid", "123", "--nonce", nonce))
        assertEquals(123, args.appPid)
        assertFalse(args.toString().contains(nonce))
        assertThrows(IllegalArgumentException::class.java) {
            VdmHostPolicy.Args.parse(arrayOf("--package", "com.example.other", "--app-pid", "123", "--nonce", nonce))
        }
        assertThrows(IllegalArgumentException::class.java) { VdmHostPolicy.requireGeometry(0, 720, 128) }
        assertThrows(IllegalArgumentException::class.java) { VdmHostPolicy.requireGeometry(1280, 720, Int.MAX_VALUE) }
    }
}
