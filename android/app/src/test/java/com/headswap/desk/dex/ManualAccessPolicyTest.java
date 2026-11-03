package com.headswap.desk.dex;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class ManualAccessPolicyTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static ManualAccessPolicy.Options options(String... values) { return ManualAccessPolicy.Options.parse(values); }
    private static byte[] cmdline(List<String> args) { return (String.join("\0", args) + "\0").getBytes(StandardCharsets.UTF_8); }
    private static String stat(int pid, long ticks) {
        String[] fields = new String[25]; Arrays.fill(fields, "0"); fields[0] = "S"; fields[19] = Long.toString(ticks);
        return pid + " (am worker (test)) " + String.join(" ", fields);
    }
    private static List<String> launcherArgs(ManualAccessPolicy.Options opts) {
        List<String> values = new ArrayList<>(Arrays.asList("app_process", "/system/bin", ManualAccessPolicy.AM_CLASS));
        values.addAll(ManualAccessPolicy.arguments(TOKEN, opts)); return values;
    }
    private static ManualAccessPolicy.Identity identity(List<String> args, String uid, long ticks) {
        return ManualAccessPolicy.parseIdentity(314, "Name:\tam\nUid:\t" + uid + "\n", cmdline(args), stat(314, ticks));
    }
    private static void rejected(Runnable run) {
        try { run.run(); fail("Expected rejection"); } catch (IllegalArgumentException expected) { }
    }

    @Test public void optionsRequireExplicitIndependentCapabilities() {
        assertFalse(options().audio); assertFalse(options().screen);
        assertTrue(options("--audio").audio); assertFalse(options("--audio").screen);
        assertTrue(options("--screen").screen); assertFalse(options("--screen").audio);
        assertTrue(options("--audio", "--screen").screen);
        rejected(() -> options("--all")); rejected(() -> options("--audio", "--audio"));
        rejected(() -> options("--help", "--screen"));
    }

    @Test public void baseActivationDoesNotRequestPlaybackApisOrPanel() {
        List<String> args = ManualAccessPolicy.arguments(TOKEN, options());
        assertTrue(args.contains("--no-hidden-api-checks")); // WindowManager IME policy is a framework API.
        assertEquals("false", args.get(args.indexOf(ManualAccessPolicy.HUD_ARGUMENT) + 1));
        assertFalse(args.contains("headswap_audio"));
        assertFalse(args.contains("headswap_screen"));
        assertEquals(ManualAccessPolicy.COMPONENT, args.get(args.size() - 1));
    }

    @Test public void approvedHudAloneEnablesFrameworkApiDuringThisActivation() {
        List<String> args = ManualAccessPolicy.arguments(TOKEN, options().withHudApi());
        assertTrue(args.contains("--no-hidden-api-checks"));
        assertEquals("true", args.get(args.indexOf(ManualAccessPolicy.HUD_ARGUMENT) + 1));
        assertFalse(args.contains("headswap_audio"));
        assertFalse(args.contains("headswap_screen"));
    }

    @Test public void audioOnlyCanRegisterSystemMixWithoutRequestingPanel() {
        List<String> args = ManualAccessPolicy.arguments(TOKEN, options("--audio"));
        assertTrue(args.contains("--no-hidden-api-checks"));
        assertEquals("true", args.get(args.indexOf("headswap_audio") + 1));
        assertFalse(args.contains("headswap_screen"));
        assertEquals(1, ManualAccessPolicy.arguments(TOKEN, options("--audio", "--screen")).stream()
            .filter("--no-hidden-api-checks"::equals).count());
    }

    @Test public void screenOptInScopesBothFlagAndArgumentToSingleInstrumentation() {
        List<String> args = ManualAccessPolicy.arguments(TOKEN, options("--screen"));
        assertTrue(args.contains("--no-hidden-api-checks"));
        assertEquals("true", args.get(args.indexOf("headswap_screen") + 1));
        assertFalse(args.contains("headswap_audio"));
        assertEquals(1, args.stream().filter("--no-hidden-api-checks"::equals).count());
    }

    @Test public void launchUsesExecAndRejectsShellInjectedTokens() {
        String command = ManualAccessPolicy.launchCommand(TOKEN, options());
        assertTrue(command.startsWith("echo HEADSWAP_ACCESS_LAUNCHER_PID=$$; exec am instrument -w -r"));
        assertFalse(command.contains("force-stop")); assertFalse(command.contains("pkill"));
        rejected(() -> ManualAccessPolicy.launchCommand(TOKEN + ";id", options()));
        rejected(() -> ManualAccessPolicy.launchCommand("", options()));
    }

    @Test public void exactOwnUidAndArgumentsAreRequired() {
        ManualAccessPolicy.Options opts = options("--audio", "--screen");
        ManualAccessPolicy.Identity own = identity(launcherArgs(opts), "2000 2000 2000 2000", 12345);
        assertTrue(ManualAccessPolicy.ownsLauncher(own, TOKEN, opts));
        assertFalse(ManualAccessPolicy.ownsLauncher(own, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", opts));
        assertFalse(ManualAccessPolicy.ownsLauncher(own, TOKEN, options()));
        assertFalse(ManualAccessPolicy.ownsLauncher(identity(launcherArgs(opts), "0 2000 2000 2000", 12345), TOKEN, opts));
        assertFalse(ManualAccessPolicy.ownsLauncher(identity(launcherArgs(opts), "10001 10001 10001 10001", 12345), TOKEN, opts));
    }

    @Test public void wrongComponentOrExtraArgumentCannotBeKilled() {
        List<String> wrong = launcherArgs(options()); wrong.set(wrong.size()-1, "foreign/.Instrumentation");
        ManualAccessPolicy.Identity other = identity(wrong, "2000 2000 2000 2000", 12);
        assertFalse(ManualAccessPolicy.ownsLauncher(other, TOKEN, options()));
        rejected(() -> ManualAccessPolicy.guardedKillCommand(other, TOKEN, options()));
        wrong = launcherArgs(options()); wrong.add("extra");
        assertFalse(ManualAccessPolicy.ownsLauncher(identity(wrong, "2000 2000 2000 2000", 12), TOKEN, options()));
    }

    @Test public void guardRechecksUidExactArgumentsAndKernelStartTime() {
        ManualAccessPolicy.Identity own = identity(launcherArgs(options()), "2000 2000 2000 2000", 12345);
        String guard = ManualAccessPolicy.guardedKillCommand(own, TOKEN, options());
        assertTrue(guard.contains("p=314\n")); assertTrue(guard.contains("2000:2000:2000:2000"));
        assertTrue(guard.contains("test \"$a\" = \"$expected\""));
        assertTrue(guard.contains("test \"${20}\" = 12345"));
        assertTrue(guard.endsWith("kill -KILL \"$p\""));
        assertFalse(guard.contains("pkill")); assertFalse(guard.contains("killall")); assertFalse(guard.contains("force-stop"));
    }

    @Test public void changedKernelStartTimeChangesGuard() {
        String first = ManualAccessPolicy.guardedKillCommand(identity(launcherArgs(options()), "2000 2000 2000 2000", 12), TOKEN, options());
        String second = ManualAccessPolicy.guardedKillCommand(identity(launcherArgs(options()), "2000 2000 2000 2000", 13), TOKEN, options());
        assertNotEquals(first, second);
    }

    @Test public void processParsingHandlesAppNameNulPaddingAndParentheses() {
        byte[] padded = (ManualAccessPolicy.PACKAGE + "\0\0\0\0").getBytes(StandardCharsets.UTF_8);
        ManualAccessPolicy.Identity app = ManualAccessPolicy.parseIdentity(314, "Uid:\t10123 10123 10123 10123\n", padded, stat(314, 8));
        assertEquals(Arrays.asList(ManualAccessPolicy.PACKAGE), app.argv); assertEquals(8, app.startTicks);
    }

    @Test public void malformedOrDifferentProcIdentityFailsClosed() {
        rejected(() -> ManualAccessPolicy.parseIdentity(1, "Uid: 2000 2000 2000 2000", cmdline(launcherArgs(options())), stat(1, 2)));
        rejected(() -> ManualAccessPolicy.parseIdentity(314, "Uid: 2000 2000", cmdline(launcherArgs(options())), stat(314, 2)));
        rejected(() -> ManualAccessPolicy.parseIdentity(314, "Uid: 2000 2000 2000 2000", cmdline(launcherArgs(options())), stat(315, 2)));
        rejected(() -> ManualAccessPolicy.parseIdentity(314, "Uid: 2000 2000 2000 2000", new byte[]{'x'}, stat(314, 2)));
        rejected(() -> identity(Arrays.asList("bad\narg"), "2000 2000 2000 2000", 2));
        rejected(() -> identity(launcherArgs(options()), "2000 2000 2000 2000", 0));
    }

    @Test public void readyNeedsMatchingSuccessfulStatusBundleAndKnownPid() {
        ManualAccessPolicy.ReadyOutput out = new ManualAccessPolicy.ReadyOutput();
        out.feed("HEADSWAP_ACCESS_LAUNCHER_PID=314");
        out.feed("INSTRUMENTATION_STATUS_CODE: 0"); assertFalse(out.ready);
        out.feed("INSTRUMENTATION_STATUS: headswap_access=ready"); assertFalse(out.ready);
        out.feed("INSTRUMENTATION_STATUS_CODE: -1"); assertFalse(out.ready);
        out.feed("INSTRUMENTATION_STATUS_CODE: 0"); assertFalse(out.ready);
        out.feed("INSTRUMENTATION_STATUS: headswap_access=ready");
        out.feed("INSTRUMENTATION_STATUS_CODE: 0"); assertTrue(out.ready); assertEquals(Integer.valueOf(314), out.pid);
    }

    @Test public void conflictingPidAndFailureAreNotAcceptedAsSuccess() {
        ManualAccessPolicy.ReadyOutput out = new ManualAccessPolicy.ReadyOutput();
        out.feed("HEADSWAP_ACCESS_LAUNCHER_PID=314");
        rejected(() -> out.feed("HEADSWAP_ACCESS_LAUNCHER_PID=315"));
        rejected(() -> out.feed("HEADSWAP_ACCESS_LAUNCHER_PID=2147483648"));
        out.feed("INSTRUMENTATION_FAILED: rejected"); assertNotNull(out.failure); assertFalse(out.ready);
    }

    @Test public void heartbeatMustBeLiveRecentAndNewerThanLauncherExitBoundary() {
        assertTrue(ManualAccessPolicy.freshHeartbeat(true, true, 100, 9000, 10000, null));
        assertTrue(ManualAccessPolicy.freshHeartbeat(true, true, 100, 10001, 11000, 10000L));
        assertFalse(ManualAccessPolicy.freshHeartbeat(true, true, 100, 9000, 11000, 10000L));
        assertFalse(ManualAccessPolicy.freshHeartbeat(true, true, 100, 10000, 11000, 10000L));
        assertFalse(ManualAccessPolicy.freshHeartbeat(true, true, 100, 9000, 25001, null));
        assertFalse(ManualAccessPolicy.freshHeartbeat(true, true, 100, 12000, 11000, null));
        assertFalse(ManualAccessPolicy.freshHeartbeat(false, true, 100, 9000, 10000, null));
        assertFalse(ManualAccessPolicy.freshHeartbeat(true, false, 100, 9000, 10000, null));
        assertFalse(ManualAccessPolicy.freshHeartbeat(true, true, 1, 9000, 10000, null));
    }
    @Test public void instrumentationResultCarriesTheActualFailureBeforeEof() {
        ManualAccessPolicy.ReadyOutput out = new ManualAccessPolicy.ReadyOutput();
        out.feed("HEADSWAP_ACCESS_LAUNCHER_PID=314");
        out.feed("INSTRUMENTATION_RESULT: headswap_access=failed");
        out.feed("INSTRUMENTATION_RESULT: headswap_access_error=NoSuchMethodException: SurfaceControl.setDisplayPowerMode");
        out.feed("INSTRUMENTATION_CODE: 0");
        assertEquals("NoSuchMethodException: SurfaceControl.setDisplayPowerMode", out.failure);
        assertFalse(out.ready);
    }

    @Test public void failureCannotBeOverriddenByLaterReadyOrGenericShortMessage() {
        ManualAccessPolicy.ReadyOutput out = new ManualAccessPolicy.ReadyOutput();
        out.feed("INSTRUMENTATION_STATUS: headswap_access_error=Включите управление экраном в приложении");
        out.feed("INSTRUMENTATION_STATUS: headswap_access=ready");
        out.feed("INSTRUMENTATION_STATUS_CODE: 0");
        out.feed("INSTRUMENTATION_RESULT: shortMsg=generic failure");
        assertFalse(out.ready);
        assertEquals("Включите управление экраном в приложении", out.failure);
    }

    @Test public void exceptionDetailsAreBoundedSingleLineAndKeepRootCause() {
        Throwable cause = new NoSuchMethodException("SurfaceControl.method");
        String result = ManualAccessPolicy.conciseFailure(new IllegalStateException("Панель не поддерживается\n\tпрошивкой", cause));
        assertTrue(result.contains("IllegalStateException"));
        assertTrue(result.contains("NoSuchMethodException: SurfaceControl.method"));
        assertFalse(result.contains("\n")); assertFalse(result.contains("\t"));
        String huge = ManualAccessPolicy.conciseFailure(new IllegalStateException(String.join("", java.util.Collections.nCopies(2000, "x"))));
        assertTrue(huge.length() <= 640); assertTrue(huge.endsWith("..."));
        assertEquals("IllegalStateException", ManualAccessPolicy.conciseFailure(new IllegalStateException()));
    }

    @Test public void emptyFailureStillFailsClosedAndUnrecognizedOutputCannotBecomeReady() {
        ManualAccessPolicy.ReadyOutput out = new ManualAccessPolicy.ReadyOutput();
        out.feed("INSTRUMENTATION_RESULT: headswap_access_error=");
        assertNotNull(out.failure); assertFalse(out.ready);
        ManualAccessPolicy.ReadyOutput unrelated = new ManualAccessPolicy.ReadyOutput();
        unrelated.feed("some log: headswap_access=ready"); unrelated.feed("INSTRUMENTATION_CODE: 0");
        assertFalse(unrelated.ready);
    }

}
