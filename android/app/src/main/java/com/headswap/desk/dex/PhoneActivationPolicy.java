package com.headswap.desk.dex;

import java.util.regex.Pattern;

/** Fixed, local-only bootstrap protocol. No user-supplied shell commands are accepted. */
public final class PhoneActivationPolicy {
    public static final int PROTOCOL = 1;
    public static final long DEADLINE_MS = 90_000;
    static final String PANEL_UNAVAILABLE = "HEADSWAP_PANEL_UNAVAILABLE";
    static final String READY_WITHOUT_PANEL = "READY_WITHOUT_PANEL";
    static final String PANEL_MESSAGE = "Прошивка не предоставила управление панелью. Доступ к рабочему столу активен. Выключение экрана и его таймер недоступны.";
    public static final String REQUEST = "files/phone-activation-request.json";
    private static final String STATUS_PREFIX = "files/phone-activation-result-";
    public static final String ENTRY = ManualAccessPolicy.DEX_CLASS_PACKAGE + ".PhoneActivationLauncher";
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32}");

    private PhoneActivationPolicy() { }

    public static boolean validToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    public static String statusPath(String token) {
        if (!validToken(token)) throw new IllegalArgumentException("Invalid activation token");
        return STATUS_PREFIX + token + ".json";
    }

    public static boolean isPending(long started, long now) {
        return started >= 0 && now >= started && now - started <= DEADLINE_MS;
    }

    static boolean sameBuild(int savedCode, int installedCode) {
        return savedCode > 0 && savedCode == installedCode;
    }

    static String logMessage(String text) {
        if (text == null || text.contains("PRIVATE KEY") || text.contains("CERTIFICATE")) return "";
        String clean = text.replaceAll("(?i)\\b[0-9a-f]{32,}\\b|\\b[0-9]{6}\\b", "[скрыто]")
            .replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return clean.substring(0, Math.min(clean.length(), 480));
    }

    public static String dispatchCommand(String apkPath, int appUid, String token) {
        if (!validToken(token) || appUid < 10000 || appUid % 100000 < 10000
                || apkPath == null || !apkPath.startsWith("/data/app/") || !apkPath.endsWith(".apk")
                || apkPath.length() > 4096 || apkPath.chars().anyMatch(c -> c < 32 || c == 127))
            throw new IllegalArgumentException("Invalid local activation request");
        // Every inherited descriptor is closed before the original app is restarted.
        // nohup also protects against ADB shell-v2's explicit SIGHUP on stream loss.
        return "CLASSPATH=" + quote(apkPath) + " /system/bin/setsid /system/bin/nohup /system/bin/app_process / "
            + ENTRY + " " + appUid + " " + token + " </dev/null >/dev/null 2>&1 &\n"
            + "echo HEADSWAP_PHONE_DISPATCHED=" + token;
    }

    static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }

    static String resultPhase(int exit, String output, boolean requestedScreen) {
        if (exit != 0 || !output.lines().anyMatch("HEADSWAP_READY"::equals)) return "FAILED";
        return requestedScreen && output.lines().anyMatch(PANEL_UNAVAILABLE::equals) ? READY_WITHOUT_PANEL : "READY";
    }

    public static String failureMessage(String output) {
        if (output == null || output.contains("PRIVATE KEY") || output.contains("CERTIFICATE"))
            return "Не удалось включить доступ. Откройте HeadSwap и повторите попытку.";
        String prefix = "Активация не завершена:";
        for (String line : output.split("\\r?\\n")) {
            if (line.startsWith(prefix)) {
                String clean = line.substring(prefix.length()).replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
                return clean.isEmpty() ? "Активация не завершена" : clean.substring(0, Math.min(clean.length(), 640));
            }
        }
        return "Не удалось подтвердить доступ. Откройте HeadSwap и повторите попытку.";
    }
}
