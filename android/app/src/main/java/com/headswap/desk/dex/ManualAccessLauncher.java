package com.headswap.desk.dex;

import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import androidx.annotation.Keep;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Entry point invoked by an authorized ADB shell, not an Android component or app service. */
@Keep
public final class ManualAccessLauncher {
    private static final int COMMAND_LIMIT = 16_384;
    private static final String STATUS = "files/computer-access-status.json";
    private static final String END = "\u0000";
    private static final String OVERFLOW = "\u0000OVERFLOW";
    private static final Pattern UID_LINE = Pattern.compile("^package:" + Pattern.quote(ManualAccessPolicy.PACKAGE) + "\\s+uid:(\\d+)\\s*$", Pattern.MULTILINE);

    private ManualAccessLauncher() { }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Same bounded activation used by the explicitly requested, detached phone bootstrap. */
    public static int run(String[] args) {
        int result = 0;
        try {
            ManualAccessPolicy.Options options = ManualAccessPolicy.Options.parse(args);
            if (options.help) {
                System.out.println("HeadSwap ADB activation: [--audio] [--screen]\n"
                    + "Основной доступ включает управление текущей музыкой: пауза при отключении USB и продолжение той же сессии после готовности CarLife.\n"
                    + "На новой установке обе опции отмечены. Для USB-звука передайте --audio, для управления панелью и чтения времени активности экрана — --screen.\n"
                    + "Служебные API доступны только в активированном процессе HeadSwap.");
                return 0;
            }
            check(Process.myUid() == 2000, "Запустите команду из обычного ADB shell (UID 2000), без root.");
            check(Build.VERSION.SDK_INT >= 33, "Нужен Android 13 или новее.");
            // A shell-owned, non-symlink lock serializes two manual invocations. No keys or IDs are stored.
            try (FileChannel channel = FileChannel.open(Paths.get("/data/local/tmp/headswap-manual-access.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock lock = channel.tryLock()) {
                check(lock != null, "Другая ручная активация ещё выполняется. Дождитесь её завершения.");
                int uid = packageUid();
                String runAsUid = run(5000, "run-as", ManualAccessPolicy.PACKAGE, "--user", Integer.toString(uid / 100000), "id", "-u").trim();
                check(runAsUid.equals(Integer.toString(uid)), "Нельзя безопасно прочитать статус этого APK через run-as. Используйте подготовленную debug-сборку HeadSwap.");
                activate(uid, options);
                try {
                    String opened = run(5000, "am", "start", "--user", "current", "--display", "0", "-n", ManualAccessPolicy.ACTIVITY);
                    if (opened.contains("Error:") || opened.contains("Exception")) throw new IOException("Activity launch rejected");
                } catch (Exception ignored) {
                    System.out.println("Доступ включён. Откройте HeadSwap на телефоне вручную.");
                }
                System.out.println("HEADSWAP_READY\nГотово. Можно отключить компьютер и подключить телефон к CarLife.\n"
                    + "Повторная активация нужна после перезагрузки, обновления, принудительной остановки или потери доступа.");
            }
        } catch (Exception error) {
            result = 1;
            System.err.println("Активация не завершена: " + safe(error.getMessage())
                + "\nHeadSwap не останавливался принудительно. Проверьте настройку и повторите команду.");
        }
        return result;
    }

    private static int packageUid() throws Exception {
        String paths = run(5000, "pm", "path", ManualAccessPolicy.PACKAGE);
        check(paths.startsWith("package:/"), "HeadSwap Desktop не установлен.");
        String packages = run(5000, "cmd", "package", "list", "packages", "-U", "--user", "current", ManualAccessPolicy.PACKAGE);
        Matcher match = UID_LINE.matcher(packages);
        check(match.find(), "Не удалось проверить UID HeadSwap.");
        int uid = Integer.parseInt(match.group(1));
        check(!match.find() && uid >= 10000 && uid % 100000 >= 10000, "Неоднозначный UID HeadSwap.");
        return uid;
    }

    private static void activate(int uid, ManualAccessPolicy.Options options) throws Exception {
        JSONObject active = heartbeat(uid, null);
        if (active != null) {
            requireCapabilities(active, options);
            System.out.println("Доступ уже работает. Служба не перезапускалась.");
            return;
        }
        if (options.audio) System.out.println("Запрошен USB-звук: только воспроизводимое аудио, без микрофона.");
        if (options.screen) System.out.println("Запрошен временный доступ к панели и системной диагностике (DUMP) для чтения времени активности экрана телефона. Перед выключением панели HeadSwap откроет свой экран для двойного касания.");
        byte[] random = new byte[16]; new SecureRandom().nextBytes(random);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : random) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        String token = hex.toString();
        java.lang.Process launcher = new ProcessBuilder("/system/bin/sh", "-c", ManualAccessPolicy.launchCommand(token, options))
            .redirectErrorStream(true).start();
        ArrayBlockingQueue<String> lines = new ArrayBlockingQueue<>(128);
        Thread reader = new Thread(() -> readLines(launcher.getInputStream(), lines), "headswap-manual-output");
        reader.setDaemon(true); reader.start();
        ManualAccessPolicy.ReadyOutput output = new ManualAccessPolicy.ReadyOutput();
        boolean launcherStopped = false;
        try {
            long deadline = SystemClock.elapsedRealtime() + 30_000;
            while (!output.ready) {
                long left = deadline - SystemClock.elapsedRealtime();
                check(left > 0, "За 30 секунд HeadSwap не подтвердил доступ. Проверьте выбранные опции в приложении.");
                String line = lines.poll(left, TimeUnit.MILLISECONDS);
                check(line != null, "За 30 секунд HeadSwap не подтвердил доступ.");
                check(!END.equals(line), "Процесс запуска завершился без READY.");
                check(!OVERFLOW.equals(line), "Слишком большой ответ процесса запуска.");
                output.feed(line);
                check(output.failure == null, output.failure);
            }
            check(output.pid != null, "Нет PID собственного процесса запуска: завершение запрещено.");
            long beforeKill = SystemClock.elapsedRealtime();
            stopOwnedLauncher(output.pid, token, options);
            launcherStopped = true;
            launcher.waitFor(3, TimeUnit.SECONDS);
            System.out.println("Проверяю доступ после завершения собственного ADB-запуска…");
            long heartbeatDeadline = SystemClock.elapsedRealtime() + 12_000;
            while (SystemClock.elapsedRealtime() < heartbeatDeadline) {
                JSONObject status = heartbeat(uid, beforeKill);
                if (status != null) {
                    requireCapabilities(status, options);
                    return;
                }
                Thread.sleep(500);
            }
            throw new IOException("Нет свежего подтверждения доступа после завершения процесса запуска.");
        } finally {
            if (!launcherStopped && output.pid != null && launcher.isAlive()) {
                try { stopOwnedLauncher(output.pid, token, options); }
                catch (Exception ignored) {
                    if (launcher.isAlive()) System.err.println("Процесс запуска не завершён: его владелец не подтверждён.");
                }
            }
            // Never force-stop the app, kill by process name, or destroy an unverified launcher PID.
            try { launcher.getInputStream().close(); } catch (IOException ignored) { }
        }
    }

    private static void requireCapabilities(JSONObject status, ManualAccessPolicy.Options options) throws IOException {
        check(Boolean.TRUE.equals(status.opt("mediaControl")),
            "Работает предыдущая активация без управления музыкой. Завершите доступ в HeadSwap и активируйте его снова. Текущие права не изменены.");
        check(!options.audio || Boolean.TRUE.equals(status.opt("playbackAudio")),
            "Доступ уже работает без USB-звука. Включите USB-звук в HeadSwap, завершите доступ в приложении и повторите команду с --audio.");
        check(!options.screen || Boolean.TRUE.equals(status.opt("panelControl")),
            "Доступ уже работает без управления экраном. Включите эту опцию в HeadSwap, завершите доступ в приложении и повторите команду с --screen.");
    }

    private static JSONObject heartbeat(int uid, Long after) {
        try {
            String raw = run(3000, "run-as", ManualAccessPolicy.PACKAGE, "--user", Integer.toString(uid / 100000), "cat", STATUS);
            if (raw.length() > 4096) return null;
            JSONObject status = new JSONObject(raw);
            if (number(status.opt("protocol")) != 1) return null;
            long pid = number(status.opt("pid")), elapsed = number(status.opt("elapsedRealtimeMs"));
            if (pid > Integer.MAX_VALUE || pid <= 1 || !ManualAccessPolicy.freshHeartbeat(
                    Boolean.TRUE.equals(status.opt("ready")), Boolean.TRUE.equals(status.opt("permissionsChecked")),
                    (int) pid, elapsed, SystemClock.elapsedRealtime(), after)) return null;
            ManualAccessPolicy.Identity identity = identity((int) pid);
            for (long owner : identity.uids) if (owner != uid) return null;
            if (!identity.argv.get(0).equals(ManualAccessPolicy.PACKAGE)) return null;
            return status;
        } catch (Exception ignored) { return null; }
    }

    private static long number(Object value) {
        return value instanceof Integer || value instanceof Long ? ((Number) value).longValue() : -1;
    }

    private static ManualAccessPolicy.Identity identity(int pid) throws Exception {
        check(pid > 1, "Некорректный PID.");
        String path = "/proc/" + pid + "/";
        return ManualAccessPolicy.parseIdentity(pid, new String(readFile(path + "status", 16_384), StandardCharsets.UTF_8),
            readFile(path + "cmdline", 16_384), new String(readFile(path + "stat", 8192), StandardCharsets.UTF_8));
    }

    private static byte[] readFile(String path, int maximum) throws IOException {
        try (InputStream in = new FileInputStream(path)) { return boundedRead(in, maximum); }
    }

    private static void stopOwnedLauncher(int pid, String token, ManualAccessPolicy.Options options) throws Exception {
        ManualAccessPolicy.Identity owner = identity(pid);
        check(ManualAccessPolicy.ownsLauncher(owner, token, options), "UID/аргументы собственного процесса запуска не совпали; завершение запрещено.");
        run(5000, "/system/bin/sh", "-c", ManualAccessPolicy.guardedKillCommand(owner, token, options));
    }

    private static String run(long timeoutMs, String... command) throws Exception {
        java.lang.Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final byte[][] bytes = new byte[1][];
        final Exception[] failure = new Exception[1];
        Thread reader = new Thread(() -> {
            try { bytes[0] = boundedRead(process.getInputStream(), COMMAND_LIMIT); }
            catch (Exception error) { failure[0] = error; }
        }, "headswap-manual-command");
        reader.setDaemon(true); reader.start();
        // These are only short, directly owned inspection/guard commands, never the instrumentation.
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("Команда Android не ответила вовремя: " + command[0]);
        }
        reader.join(1000);
        check(!reader.isAlive(), "Не завершилось чтение ответа Android.");
        if (failure[0] != null) throw failure[0];
        String text = new String(bytes[0] == null ? new byte[0] : bytes[0], StandardCharsets.UTF_8);
        check(process.exitValue() == 0, "Android " + command[0] + ": " + safe(text));
        return text;
    }

    private static byte[] boundedRead(InputStream in, int maximum) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048]; int count;
        while ((count = in.read(buffer)) != -1) {
            if (out.size() + count > maximum) throw new IOException("Ответ Android превысил лимит.");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static void readLines(InputStream in, ArrayBlockingQueue<String> target) {
        try {
            ByteArrayOutputStream line = new ByteArrayOutputStream(); int value;
            while ((value = in.read()) != -1) {
                if (value == '\n') {
                    if (!target.offer(new String(line.toByteArray(), StandardCharsets.UTF_8))) throw new IOException("Output queue full");
                    line.reset();
                } else {
                    if (line.size() >= 8192) throw new IOException("Output line too large");
                    line.write(value);
                }
            }
            if (line.size() > 0) target.offer(new String(line.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception error) { target.clear(); target.offer(OVERFLOW); }
        finally { target.offer(END); }
    }

    private static void check(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message == null ? "Неизвестная ошибка активации" : message);
    }

    private static String safe(String text) {
        if (text == null || text.isEmpty()) return "ошибка Android";
        String clean = text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "?").trim();
        return clean.length() > 600 ? clean.substring(clean.length() - 600) : clean;
    }
}
