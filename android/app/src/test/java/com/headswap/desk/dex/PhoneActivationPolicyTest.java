package com.headswap.desk.dex;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PhoneActivationPolicyTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static String command(String path, int uid, String token) {
        return PhoneActivationPolicy.dispatchCommand(path, uid, token);
    }
    private static void rejected(Runnable call) {
        try { call.run(); fail("Expected rejection"); } catch (IllegalArgumentException expected) { }
    }
    @Test public void dispatchIsDetachedAndOnlyRunsTheOwnedEntry() {
        String text = command("/data/app/opaque/base.apk", 10123, TOKEN);
        assertTrue(text.contains("/system/bin/setsid /system/bin/nohup /system/bin/app_process / " + PhoneActivationPolicy.ENTRY));
        assertTrue(text.contains(" </dev/null >/dev/null 2>&1 &\n"));
        assertTrue(text.endsWith("echo HEADSWAP_PHONE_DISPATCHED=" + TOKEN));
        for (String forbidden : new String[]{"pm grant", "settings put", "force-stop", "pkill", "killall", "tcpip", "headswap_audio"})
            assertFalse(text.contains(forbidden));
    }
    @Test public void tokenAndUidCannotBecomeShellCode() {
        rejected(() -> command("/data/app/x/base.apk", 2000, TOKEN));
        rejected(() -> command("/data/app/x/base.apk", 0, TOKEN));
        rejected(() -> command("/data/app/x/base.apk", 10123, TOKEN + ";id"));
        rejected(() -> command("/sdcard/foreign.apk", 10123, TOKEN));
        rejected(() -> command("/data/app/a\n/base.apk", 10123, TOKEN));
    }
    @Test public void apkPathIsShellQuotedEvenWithMetacharacters() {
        String text = command("/data/app/a'$(id)/base.apk", 10123, TOKEN);
        assertTrue(text.startsWith("CLASSPATH='/data/app/a'\"'\"'$(id)/base.apk' "));
    }
    @Test public void expiredOrFutureBootTimeCannotRemainPending() {
        assertTrue(PhoneActivationPolicy.isPending(1000, 91000));
        assertFalse(PhoneActivationPolicy.isPending(1000, 91001));
        assertFalse(PhoneActivationPolicy.isPending(100000, 1000));
        assertFalse(PhoneActivationPolicy.isPending(-1, 0));
    }
    @Test public void aLateOldResultCannotOverwriteTheCurrentAttemptPath() {
        String old = PhoneActivationPolicy.statusPath(TOKEN);
        String current = PhoneActivationPolicy.statusPath("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        java.util.Map<String, String> files = new java.util.HashMap<>();
        files.put(current, "READY");
        files.put(old, "FAILED");
        assertEquals("READY", files.get(current));
        assertNotEquals(old, current);
        rejected(() -> PhoneActivationPolicy.statusPath("../request"));
        rejected(() -> PhoneActivationPolicy.statusPath(TOKEN + ";rm"));
    }
    @Test public void unsupportedPanelRequiresAnExplicitMarkerAndSuccessfulBaseActivation() {
        String success = "HEADSWAP_PANEL_UNAVAILABLE\nHEADSWAP_READY\n";
        assertEquals("READY_WITHOUT_PANEL", PhoneActivationPolicy.resultPhase(0, success, true));
        assertEquals("READY", PhoneActivationPolicy.resultPhase(0, success, false));
        assertEquals("READY", PhoneActivationPolicy.resultPhase(0, "HEADSWAP_READY\n", true));
        assertEquals("FAILED", PhoneActivationPolicy.resultPhase(1, success, true));
        assertEquals("FAILED", PhoneActivationPolicy.resultPhase(0, "HEADSWAP_PANEL_UNAVAILABLE\n", true));
        assertEquals("FAILED", PhoneActivationPolicy.resultPhase(0, "Error: HEADSWAP_READY\n", true));
    }

    @Test public void requestsBeforeTheLoggingUpdateCannotRestoreAnOldFailureScreen() {
        assertFalse(PhoneActivationPolicy.sameBuild(0, 23));
        assertFalse(PhoneActivationPolicy.sameBuild(22, 23));
        assertTrue(PhoneActivationPolicy.sameBuild(23, 23));
    }

    @Test public void diagnosticRetainsFailureReasonButOmitsPairingAndActivationSecrets() {
        String output = PhoneActivationPolicy.logMessage("ошибка панели\n" + TOKEN + " 123456");
        assertTrue(output.contains("ошибка панели"));
        assertFalse(output.contains(TOKEN));
        assertFalse(output.contains("123456"));
        assertEquals("", PhoneActivationPolicy.logMessage("-----BEGIN PRIVATE KEY-----"));
    }

    @Test public void onlyBoundedFailureLineIsPersisted() {
        String text = "unrelated log\nАктивация не завершена: Включите USB-звук\nHEADSWAP_READY\n";
        assertEquals("Включите USB-звук", PhoneActivationPolicy.failureMessage(text));
        assertTrue(PhoneActivationPolicy.failureMessage("Активация не завершена: " + new String(new char[1000]).replace('\0', 'x')).length() <= 640);
        assertFalse(PhoneActivationPolicy.failureMessage("Активация не завершена: -----BEGIN PRIVATE KEY-----").contains("PRIVATE KEY"));
    }
}
