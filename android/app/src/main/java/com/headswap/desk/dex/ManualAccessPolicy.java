package com.headswap.desk.dex;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure validation shared by the manual, app_process-hosted ADB activator and its tests. */
public final class ManualAccessPolicy {
    public static final String PACKAGE = "com.headswap.desk";
    static final String DEX_CLASS_PACKAGE = "com.headswap.desk.dex";
    public static final String COMPONENT = PACKAGE + "/" + DEX_CLASS_PACKAGE + ".DelegatedDesktopInstrumentation";
    public static final String ACTIVITY = PACKAGE + "/" + DEX_CLASS_PACKAGE + ".DexSetupActivity";
    public static final String AM_CLASS = "com.android.commands.am.Am";
    public static final long MAX_HEARTBEAT_AGE_MS = 15_000;
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern UID = Pattern.compile("^Uid:\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s*$", Pattern.MULTILINE);

    private ManualAccessPolicy() { }

    public static final class Options {
        public final boolean audio, screen, help;
        public Options(boolean audio, boolean screen, boolean help) {
            this.audio = audio; this.screen = screen; this.help = help;
        }
        public static Options parse(String[] args) {
            boolean audio = false, screen = false, help = false;
            for (String arg : args) {
                switch (arg) {
                    case "--audio": if (audio) throw new IllegalArgumentException("Повторный --audio"); audio = true; break;
                    case "--screen": if (screen) throw new IllegalArgumentException("Повторный --screen"); screen = true; break;
                    case "--help": if (help) throw new IllegalArgumentException("Повторный --help"); help = true; break;
                    default: throw new IllegalArgumentException("Неизвестный параметр: " + arg);
                }
            }
            if (help && (audio || screen)) throw new IllegalArgumentException("--help используется отдельно");
            return new Options(audio, screen, help);
        }
    }

    public static List<String> arguments(String token, Options options) {
        requireToken(token);
        List<String> args = new ArrayList<>(Arrays.asList("instrument", "-w", "-r", "-e", "headswap_activation_token", token));
        // Both the playback mix and the physical panel use system APIs. Scope access to
        // this instrumentation invocation; base desktop activation needs neither.
        if (options.audio || options.screen) args.add("--no-hidden-api-checks");
        if (options.audio) args.addAll(Arrays.asList("-e", "headswap_audio", "true"));
        if (options.screen) args.addAll(Arrays.asList("-e", "headswap_screen", "true"));
        args.add(COMPONENT);
        return Collections.unmodifiableList(args);
    }

    public static String launchCommand(String token, Options options) {
        return "echo HEADSWAP_ACCESS_LAUNCHER_PID=$$; exec am " + String.join(" ", arguments(token, options));
    }

    private static void requireToken(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) throw new IllegalArgumentException("Некорректный токен запуска");
    }

    public static final class Identity {
        public final int pid;
        public final long startTicks;
        public final long[] uids;
        public final List<String> argv;
        private Identity(int pid, long startTicks, long[] uids, List<String> argv) {
            this.pid = pid; this.startTicks = startTicks; this.uids = uids;
            this.argv = Collections.unmodifiableList(argv);
        }
    }

    public static Identity parseIdentity(int pid, String status, byte[] cmdline, String stat) {
        if (pid <= 1 || cmdline.length > 16_384 || cmdline.length == 0 || status.length() > 16_384 || stat.length() > 8192)
            throw new IllegalArgumentException("Некорректные данные процесса");
        Matcher match = UID.matcher(status);
        if (!match.find()) throw new IllegalArgumentException("UID процесса не найден");
        long[] uids = new long[4];
        for (int n = 0; n < 4; n++) uids[n] = Long.parseLong(match.group(n + 1));
        if (match.find()) throw new IllegalArgumentException("Повторный UID процесса");
        List<String> argv = new ArrayList<>();
        int start = 0;
        for (int n = 0; n < cmdline.length; n++) {
            if (cmdline[n] != 0) continue;
            if (n == start) { start = n + 1; continue; } // Android process names may leave trailing NUL padding.
            String arg = new String(cmdline, start, n - start, StandardCharsets.UTF_8);
            if (!Arrays.equals(arg.getBytes(StandardCharsets.UTF_8), Arrays.copyOfRange(cmdline, start, n)))
                throw new IllegalArgumentException("Некорректная кодировка процесса");
            for (int k = 0; k < arg.length(); k++) if (arg.charAt(k) < 32 || arg.charAt(k) == 127)
                throw new IllegalArgumentException("Управляющий символ в аргументе процесса");
            argv.add(arg); start = n + 1;
        }
        if (start != cmdline.length || argv.isEmpty()) throw new IllegalArgumentException("Нет завершающего NUL процесса");
        int space = stat.indexOf(' '), end = stat.lastIndexOf(')');
        if (space <= 0 || end <= space || Integer.parseInt(stat.substring(0, space)) != pid)
            throw new IllegalArgumentException("PID не совпал");
        String[] fields = stat.substring(end + 1).trim().split("\\s+");
        if (fields.length < 20) throw new IllegalArgumentException("Короткий stat процесса");
        long ticks = Long.parseLong(fields[19]);
        if (ticks <= 0) throw new IllegalArgumentException("Некорректное время запуска процесса");
        return new Identity(pid, ticks, uids, argv);
    }

    public static boolean ownsLauncher(Identity identity, String token, Options options) {
        if (identity == null || token == null || !TOKEN.matcher(token).matches()) return false;
        for (long uid : identity.uids) if (uid != 2000) return false;
        int index = identity.argv.indexOf(AM_CLASS);
        return index >= 0 && identity.argv.subList(index + 1, identity.argv.size()).equals(arguments(token, options));
    }

    public static String guardedKillCommand(Identity identity, String token, Options options) {
        if (!ownsLauncher(identity, token, options)) throw new IllegalArgumentException("Процесс запуска не принадлежит этой активации");
        return "p=" + identity.pid + "\n"
            + "test \"$(awk '/^Uid:/ {print $2 \":\" $3 \":\" $4 \":\" $5}' /proc/$p/status)\" = 2000:2000:2000:2000 || exit 41\n"
            + "a=$(tr '\\000' '\\n' < /proc/$p/cmdline) || exit 42\n"
            + "expected=" + quote(String.join("\n", identity.argv)) + "\n"
            + "test \"$a\" = \"$expected\" || exit 43\n"
            + "s=$(cat /proc/$p/stat) || exit 44\n"
            + "s=${s##*) }\nset -- $s\n"
            + "test \"${20}\" = " + identity.startTicks + " || exit 45\n"
            + "kill -KILL \"$p\"";
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }

    /** Single-line bounded diagnostics, safe for am's line-oriented result protocol. */
    public static String conciseFailure(Throwable error) {
        StringBuilder text = new StringBuilder();
        Throwable current = error;
        java.util.Set<Throwable> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (int depth = 0; current != null && depth < 4 && seen.add(current); depth++) {
            String message = current.getMessage();
            String part = current.getClass().getSimpleName() + (message == null || message.trim().isEmpty() ? "" : ": " + message);
            if (text.length() > 0) text.append("; причина: ");
            text.append(part);
            current = current.getCause();
        }
        return conciseText(text.length() == 0 ? "Ошибка активации HeadSwap" : text.toString());
    }

    private static String conciseText(String text) {
        String clean = text.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return clean.length() <= 640 ? clean : clean.substring(0, 637) + "...";
    }

    public static final class ReadyOutput {
        public Integer pid;
        public boolean ready;
        public String failure;
        private boolean bundleReady;
        public void feed(String raw) {
            if (raw == null || raw.length() > 8192) throw new IllegalArgumentException("Некорректный ответ запуска");
            String line = raw.trim();
            if (line.startsWith("HEADSWAP_ACCESS_LAUNCHER_PID=")) {
                String value = line.substring("HEADSWAP_ACCESS_LAUNCHER_PID=".length());
                if (!value.matches("[1-9][0-9]{0,9}")) throw new IllegalArgumentException("Некорректный PID запуска");
                long candidate = Long.parseLong(value);
                if (candidate <= 1 || candidate > Integer.MAX_VALUE || (pid != null && pid != candidate))
                    throw new IllegalArgumentException("PID запуска изменился");
                pid = (int) candidate;
            } else if (line.startsWith("INSTRUMENTATION_RESULT: headswap_access_error=")
                    || line.startsWith("INSTRUMENTATION_STATUS: headswap_access_error=")) {
                String detail = line.substring(line.indexOf('=') + 1).trim();
                failure = conciseText(detail.isEmpty() ? "HeadSwap отклонил активацию" : detail);
                ready = false;
                bundleReady = false;
            } else if (line.equals("INSTRUMENTATION_STATUS: headswap_access=ready")) {
                bundleReady = true;
            } else if (line.startsWith("INSTRUMENTATION_STATUS_CODE:")) {
                if (failure == null && line.equals("INSTRUMENTATION_STATUS_CODE: 0") && bundleReady) ready = true;
                bundleReady = false;
            } else if (line.startsWith("INSTRUMENTATION_FAILED:") || line.startsWith("INSTRUMENTATION_RESULT: shortMsg=")) {
                if (failure == null) failure = conciseText(line);
                ready = false;
                bundleReady = false;
            }
        }
    }

    public static boolean freshHeartbeat(boolean ready, boolean checked, int pid, long elapsed, long now, Long after) {
        return ready && checked && pid > 1 && elapsed >= 0 && elapsed <= now
            && now - elapsed <= MAX_HEARTBEAT_AGE_MS && (after == null || elapsed > after);
    }
}
