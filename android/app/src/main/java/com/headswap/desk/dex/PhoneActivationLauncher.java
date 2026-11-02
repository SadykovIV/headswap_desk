package com.headswap.desk.dex;

import android.os.Process;
import android.os.SystemClock;
import androidx.annotation.Keep;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

/** Short-lived shell entry, detached before am instrument restarts the in-app ADB client. */
@Keep
public final class PhoneActivationLauncher {
    private PhoneActivationLauncher() { }

    public static void main(String[] args) {
        int exit = 1;
        String token = null;
        int uid = -1;
        try {
            if (Process.myUid() != 2000 || args.length != 2) throw new IllegalArgumentException();
            uid = Integer.parseInt(args[0]); token = args[1];
            if (uid < 10000 || uid % 100000 < 10000 || !PhoneActivationPolicy.validToken(token))
                throw new IllegalArgumentException();
            if (!command(null, "run-as", ManualAccessPolicy.PACKAGE, "--user", Integer.toString(uid / 100000), "id", "-u").trim().equals(Integer.toString(uid)))
                throw new IllegalStateException("Application UID mismatch");
            JSONObject request = request(uid, token);
            long started = request.getLong("startedElapsedMs");
            if (!PhoneActivationPolicy.isPending(started, SystemClock.elapsedRealtime()))
                throw new IllegalStateException("Activation request expired");
            boolean audio = request.getBoolean("audio"), screen = request.getBoolean("screen");
            writeStatus(uid, token, "PENDING", "Включаю доступ на телефоне…");
            List<String> options = new ArrayList<>();
            if (audio) options.add("--audio");
            if (screen) options.add("--screen");
            BoundedOutput captured = new BoundedOutput();
            PrintStream previousOut = System.out, previousErr = System.err;
            Timer timer = new Timer("headswap-phone-deadline", true);
            Thread main = Thread.currentThread();
            timer.schedule(new TimerTask() { @Override public void run() { main.interrupt(); } },
                Math.max(1, PhoneActivationPolicy.DEADLINE_MS - (SystemClock.elapsedRealtime() - started)));
            try {
                PrintStream sink = new PrintStream(captured, true, "UTF-8");
                System.setOut(sink); System.setErr(sink);
                exit = ManualAccessLauncher.run(options.toArray(new String[0]));
                sink.flush();
            } finally {
                timer.cancel();
                Thread.interrupted(); // Allow bounded status/Activity cleanup after a deadline interrupt.
                System.setOut(previousOut); System.setErr(previousErr);
            }
            String output = captured.text();
            String phase = PhoneActivationPolicy.resultPhase(exit, output, screen);
            boolean ready = !phase.equals("FAILED");
            writeStatus(uid, token, phase, !ready ? PhoneActivationPolicy.failureMessage(output)
                : phase.equals(PhoneActivationPolicy.READY_WITHOUT_PANEL) ? PhoneActivationPolicy.PANEL_MESSAGE : "Доступ включён на телефоне");
            if (!ready) openSetup(); // Failure must be visible even when instrumentation restarted the old UI.
            exit = ready ? 0 : 1;
        } catch (Exception failure) {
            if (uid >= 10000 && PhoneActivationPolicy.validToken(token)) {
                try { writeStatus(uid, token, "FAILED", "Не удалось завершить запуск на телефоне. Проверьте беспроводную отладку и повторите."); }
                catch (Exception ignored) { }
                openSetup();
            }
        }
        System.exit(exit);
    }

    private static JSONObject request(int uid, String token) throws Exception {
        JSONObject request = new JSONObject(command(null, "run-as", ManualAccessPolicy.PACKAGE, "--user",
            Integer.toString(uid / 100000), "cat", PhoneActivationPolicy.REQUEST));
        if (!PhoneActivationPolicy.sameBuild(request.optInt("versionCode"), com.headswap.desk.BuildConfig.VERSION_CODE)
                || request.getInt("protocol") != PhoneActivationPolicy.PROTOCOL || request.getInt("uid") != uid
                || !token.equals(request.getString("token"))) throw new IllegalStateException("Request changed");
        return request;
    }

    private static void writeStatus(int uid, String token, String phase, String message) throws Exception {
        request(uid, token); // Never overwrite a newer app-owned attempt.
        byte[] data = new JSONObject().put("protocol", PhoneActivationPolicy.PROTOCOL).put("token", token)
            .put("phase", phase).put("message", message).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .toString().getBytes(StandardCharsets.UTF_8);
        String status = PhoneActivationPolicy.statusPath(token);
        String temporary = status + ".tmp";
        command(data, "run-as", ManualAccessPolicy.PACKAGE, "--user", Integer.toString(uid / 100000),
            "/system/bin/sh", "-c", "umask 077; cat > " + temporary + " && mv -f " + temporary + " " + status);
        try { request(uid, token); }
        catch (Exception replaced) {
            // A late old result has a different path and cannot hide a successor's READY.
            // Remove only this launcher's own result if the app has already moved on.
            command(null, "run-as", ManualAccessPolicy.PACKAGE, "--user", Integer.toString(uid / 100000),
                "rm", "-f", status, temporary);
        }
    }

    private static void openSetup() {
        try { command(null, "am", "start", "--user", "current", "--display", "0", "-n", ManualAccessPolicy.ACTIVITY); }
        catch (Exception ignored) { }
    }

    private static String command(byte[] input, String... arguments) throws Exception {
        java.lang.Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        final Exception[] error = new Exception[1];
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] bytes = new byte[1024]; int count;
                while ((count = stream.read(bytes)) >= 0) {
                    if (output.size() + count > 8192) throw new IllegalStateException("Oversized response");
                    output.write(bytes, 0, count);
                }
            } catch (Exception failure) { error[0] = failure; }
        }, "headswap-phone-command");
        reader.setDaemon(true); reader.start();
        try {
            try (OutputStream stream = process.getOutputStream()) { if (input != null) stream.write(input); }
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IllegalStateException("Command timeout");
            reader.join(1000);
            if (reader.isAlive() || error[0] != null || process.exitValue() != 0)
                throw new IllegalStateException("Command failed");
            return output.toString("UTF-8");
        } finally {
            // Only the short ProcessBuilder child just created here; never an app/daemon by name.
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        @Override public synchronized void write(int value) { if (bytes.size() < 16_384) bytes.write(value); }
        String text() { return new String(bytes.toByteArray(), StandardCharsets.UTF_8); }
    }
}
