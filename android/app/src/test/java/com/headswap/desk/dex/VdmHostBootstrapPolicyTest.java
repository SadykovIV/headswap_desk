package com.headswap.desk.dex;

import org.junit.Test;
import static org.junit.Assert.*;

public class VdmHostBootstrapPolicyTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String NONCE = TOKEN + TOKEN;
    @Test public void recoveryAlwaysUsesTheFixedEntryAndQuotesTheInstalledApk() {
        String command = VdmHostBootstrapPolicy.recoveryCommand("/data/app/a'b/base.apk", 10231, 987, TOKEN, NONCE);
        assertTrue(command.startsWith("CLASSPATH='/data/app/a'\"'\"'b/base.apk' /system/bin/app_process / "));
        assertTrue(command.contains("com.headswap.desk.dex.VdmHostBootstrap 10231 987 " + TOKEN + " " + NONCE));
        assertFalse(command.contains("tcpip:")); assertFalse(command.contains("setprop"));
    }
    @Test public void rejectsNonAppOwnerInvalidNonceAndArbitraryApkPaths() {
        assertFalse(VdmHostBootstrapPolicy.validOwner(2000, 987));
        assertFalse(VdmHostBootstrapPolicy.validOwner(10231, 1));
        assertFalse(VdmHostBootstrapPolicy.validNonce(NONCE + ";id"));
        assertFalse(VdmHostBootstrapPolicy.validNonce(TOKEN));
        assertFalse(VdmHostBootstrapPolicy.validApk("/sdcard/base.apk"));
        assertFalse(VdmHostBootstrapPolicy.validApk("/data/app/a\n/base.apk"));
    }
    @Test public void malformedActivationSecretCannotReachTheShell() {
        try { VdmHostBootstrapPolicy.recoveryCommand("/data/app/a/base.apk", 10231, 987, "$(id)", NONCE); fail(); }
        catch (IllegalArgumentException expected) { }
    }
}
