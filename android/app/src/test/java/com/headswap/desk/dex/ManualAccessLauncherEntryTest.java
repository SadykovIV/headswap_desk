package com.headswap.desk.dex;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

/** These early-return cases do not contact Android or start a process. */
public final class ManualAccessLauncherEntryTest {
    @Test public void activationCommandsUseTheInstalledIdAndActualImplementationClasses() {
        String installed = com.headswap.desk.BuildConfig.APPLICATION_ID;
        assertEquals(installed, ManualAccessPolicy.PACKAGE);
        assertEquals(installed + "/" + DelegatedDesktopInstrumentation.class.getName(), ManualAccessPolicy.COMPONENT);
        assertEquals(installed + "/" + DexSetupActivity.class.getName(), ManualAccessPolicy.ACTIVITY);
        assertEquals(PhoneActivationLauncher.class.getName(), PhoneActivationPolicy.ENTRY);
    }
    @Test public void helpReturnsZeroWithoutExitingTheCaller() throws Exception {
        PrintStream previous = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, "UTF-8"));
            assertEquals(0, ManualAccessLauncher.run(new String[]{"--help"}));
        } finally { System.setOut(previous); }
        assertTrue(output.toString("UTF-8").contains("HeadSwap ADB activation"));
        assertFalse(output.toString("UTF-8").contains("HEADSWAP_READY"));
    }
    @Test public void invalidArgumentsReturnFailureWithoutActivation() throws Exception {
        PrintStream previous = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(output, true, "UTF-8"));
            assertEquals(1, ManualAccessLauncher.run(new String[]{"--unknown"}));
        } finally { System.setErr(previous); }
        assertTrue(output.toString("UTF-8").contains("Активация не завершена"));
        assertFalse(output.toString("UTF-8").contains("HEADSWAP_READY"));
    }
}
