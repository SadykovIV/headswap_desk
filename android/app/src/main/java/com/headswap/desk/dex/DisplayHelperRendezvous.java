package com.headswap.desk.dex;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/** App-private activation secret. Only the digest of the complete record appears in the heartbeat. */
final class DisplayHelperRendezvous {
    static final String FILE_NAME = "display-helper-session";
    static final String RUN_AS_PATH = "no_backup/" + FILE_NAME;
    final int uid, pid, bootCount;
    final long processTicks, elapsed;
    final String token;
    final boolean panel;

    DisplayHelperRendezvous(int uid, int pid, long processTicks, int bootCount, long elapsed, boolean panel, String token) {
        if (uid % 100000 < 10000 || pid <= 1 || processTicks <= 0 || bootCount < 0 || elapsed < 0 ||
                !PhoneActivationPolicy.validToken(token)) throw new IllegalArgumentException("Invalid display helper session");
        this.uid = uid;
        this.pid = pid;
        this.processTicks = processTicks;
        this.bootCount = bootCount;
        this.elapsed = elapsed;
        this.panel = panel;
        this.token = token;
    }

    static String newToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return hex(bytes);
    }

    String encode() {
        return "1 " + uid + " " + pid + " " + processTicks + " " + bootCount + " " + elapsed + " "
                + (panel ? "ime-and-panel" : "ime") + " " + token;
    }

    static DisplayHelperRendezvous parse(String raw) {
        if (raw == null || raw.length() > 256) return null;
        String[] fields = raw.split(" ", -1);
        if (fields.length != 8 || !"1".equals(fields[0]) ||
                !("ime".equals(fields[6]) || "ime-and-panel".equals(fields[6]))) return null;
        try {
            DisplayHelperRendezvous result = new DisplayHelperRendezvous(Integer.parseInt(fields[1]), Integer.parseInt(fields[2]),
                    Long.parseLong(fields[3]), Integer.parseInt(fields[4]), Long.parseLong(fields[5]),
                    "ime-and-panel".equals(fields[6]), fields[7]);
            return raw.equals(result.encode()) ? result : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    String fingerprint() {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(encode().getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError("SHA-256 unavailable", impossible); }
    }

    boolean matchesOwner(int expectedUid, ManualAccessPolicy.Identity owner, int currentBoot) {
        if (expectedUid != uid || owner.pid != pid || owner.startTicks != processTicks || currentBoot != bootCount ||
                !ManualAccessPolicy.PACKAGE.equals(owner.argv.get(0))) return false;
        for (long id : owner.uids) if (id != uid) return false;
        return true;
    }

    boolean matchesHeartbeat(long heartbeatPid, long heartbeatElapsed, String fingerprint) {
        return pid == heartbeatPid && elapsed <= heartbeatElapsed && fingerprint().equals(fingerprint);
    }

    private static String hex(byte[] bytes) {
        final char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            result[2*i] = alphabet[(bytes[i] & 255) >>> 4];
            result[2*i+1] = alphabet[bytes[i] & 15];
        }
        return new String(result);
    }
}
