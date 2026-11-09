package com.headswap.desk.dex;

/** Fixed APK entry and literal arguments. No arbitrary commands, peers or ADB reconfiguration. */
public final class VdmHostBootstrapPolicy {
    public static final String ENTRY = ManualAccessPolicy.DEX_CLASS_PACKAGE + ".VdmHostBootstrap";
    public static final String HOST_ENTRY = ManualAccessPolicy.DEX_CLASS_PACKAGE + ".VdmHostMain";
    public static final long DEADLINE_MS = 15_000;
    private VdmHostBootstrapPolicy() { }

    public static boolean validNonce(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    public static boolean validOwner(int uid, int pid) { return uid >= 10000 && uid % 100000 >= 10000 && pid > 1; }
    public static boolean validApk(String path) {
        return path != null && path.startsWith("/data/app/") && path.endsWith("/base.apk")
            && path.length() <= 4096 && path.chars().noneMatch(c -> c < 32 || c == 127);
    }
    public static String recoveryCommand(String apk, int uid, int pid, String token, String nonce) {
        if (!validApk(apk) || !validOwner(uid, pid) || !PhoneActivationPolicy.validToken(token) || !validNonce(nonce))
            throw new IllegalArgumentException("Invalid VDM activation owner");
        return "CLASSPATH=" + PhoneActivationPolicy.quote(apk) + " /system/bin/app_process / " + ENTRY
            + " " + uid + " " + pid + " " + token + " " + nonce;
    }
}
