package com.headswap.desk.dex;

import android.content.AttributionSource;
import android.content.pm.ProviderInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import androidx.annotation.Keep;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Bounded shell-side bootstrap, shared by computer and paired phone activation. */
@Keep
public final class VdmHostBootstrap {
    private static final String AUTHORITY = ManualAccessPolicy.PACKAGE + ".vdmhost";
    private VdmHostBootstrap() { }

    @Keep public static void main(String[] args) {
        int result = 1;
        try {
            if (args.length != 4) throw new IllegalArgumentException("Invalid VDM bootstrap arguments");
            ensureReady(Integer.parseInt(args[0]), Integer.parseInt(args[1]), args[2], args[3]);
            System.out.println("HEADSWAP_VDM_READY"); result = 0;
        } catch (Exception error) {
            // Do not print tokens, provider Bundles, private files, or nested shell output.
            System.err.println("HEADSWAP_VDM_FAILED " + error.getClass().getSimpleName());
        }
        System.exit(result);
    }

    static void ensureReady(int uid, int pid, String token, String expectedNonce) throws Exception {
        if (Build.VERSION.SDK_INT < 37) return;
        FutureTask<Void> task = new FutureTask<>(() -> { prepareAndLaunch(uid, pid, token, expectedNonce); return null; });
        Thread worker = new Thread(task, "headswap-vdm-bootstrap"); worker.setDaemon(true); worker.start();
        try { task.get(VdmHostBootstrapPolicy.DEADLINE_MS, TimeUnit.MILLISECONDS); }
        catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception)cause;
            throw new IllegalStateException("VDM bootstrap unavailable", cause);
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("Не удалось запустить независимый экран Android 17 за 15 секунд. Повторите активацию при доступной отладке.");
        } finally { if (!task.isDone()) task.cancel(true); }
    }

    private static void prepareAndLaunch(int uid, int pid, String token, String expectedNonce) throws Exception {
        if (Process.myUid() != 2000 || !VdmHostBootstrapPolicy.validOwner(uid, pid)
                || !PhoneActivationPolicy.validToken(token)
                || (expectedNonce != null && !VdmHostBootstrapPolicy.validNonce(expectedNonce)))
            throw new SecurityException("Invalid VDM bootstrap owner");
        requireOwner(uid, pid);
        Bundle request = new Bundle(); request.putInt("appPid", pid);
        Bundle prepared = provider(uid, "prepare", token, request);
        if (prepared == null || prepared.getInt("protocol", -1) != 1 || prepared.getInt("appPid", -1) != pid)
            throw new IllegalStateException("Активированный процесс не подтвердил запуск независимого экрана");
        if (prepared.getBoolean("ready")) return;
        String nonce = prepared.getString("launchNonce");
        if (!VdmHostBootstrapPolicy.validNonce(nonce) || (expectedNonce != null && !expectedNonce.equals(nonce)))
            throw new IllegalStateException("Запрос независимого экрана уже изменился");
        String apk = installedApk();
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        ProcessBuilder host = new ProcessBuilder("/system/bin/setsid", "/system/bin/nohup", "/system/bin/app_process", "/",
            VdmHostBootstrapPolicy.HOST_ENTRY, "--package", ManualAccessPolicy.PACKAGE, "--app-pid", Integer.toString(pid), "--nonce", nonce);
        host.environment().put("CLASSPATH", apk);
        File devNull = new File("/dev/null");
        host.redirectInput(devNull).redirectOutput(devNull).redirectError(devNull).start();
        request.putString("launchNonce", nonce);
        long deadline = SystemClock.elapsedRealtime() + VdmHostBootstrapPolicy.DEADLINE_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            requireOwner(uid, pid);
            Bundle status = provider(uid, "status", token, request);
            if (status == null || status.getInt("appPid", -1) != pid || status.getInt("protocol", -1) != 1)
                throw new IllegalStateException("Сеанс независимого экрана завершён");
            if (status.getBoolean("ready")) return;
            String error = status.getString("error");
            if (error != null && !error.isEmpty()) throw new IllegalStateException("Хост независимого экрана не подтвердил готовность");
            Thread.sleep(100);
        }
        throw new IllegalStateException("Не удалось запустить независимый экран Android 17. Повторите активацию при доступной отладке.");
    }

    private static String installedApk() throws Exception {
        java.lang.Process command = new ProcessBuilder("pm", "path", ManualAccessPolicy.PACKAGE).start();
        try {
            if (!command.waitFor(3, TimeUnit.SECONDS) || command.exitValue() != 0) throw new IllegalStateException("APK path unavailable");
            String result;
            try (InputStream input = command.getInputStream()) { result = new String(read(input, 8192), StandardCharsets.UTF_8); }
            String apk = null;
            for (String line : result.split("\\r?\\n")) if (line.startsWith("package:")) {
                String value = line.substring(8).trim();
                if (VdmHostBootstrapPolicy.validApk(value)) {
                    if (apk != null) throw new IllegalStateException("Ambiguous APK");
                    apk = value;
                }
            }
            if (apk == null || !new File(apk).isFile()) throw new IllegalStateException("Installed APK unavailable");
            return apk;
        } finally { if (command.isAlive()) command.destroyForcibly(); }
    }

    private static void requireOwner(int uid, int pid) throws Exception {
        ManualAccessPolicy.Identity identity;
        try (InputStream status = new FileInputStream("/proc/" + pid + "/status");
             InputStream args = new FileInputStream("/proc/" + pid + "/cmdline");
             InputStream stat = new FileInputStream("/proc/" + pid + "/stat")) {
            identity = ManualAccessPolicy.parseIdentity(pid, new String(read(status, 16384), StandardCharsets.UTF_8),
                read(args, 8192), new String(read(stat, 8192), StandardCharsets.UTF_8));
        }
        if (identity.argv.isEmpty() || !ManualAccessPolicy.PACKAGE.equals(identity.argv.get(0))) throw new SecurityException("Owner process changed");
        for (long owner : identity.uids) if (owner != uid) throw new SecurityException("Owner UID changed");
    }

    private static Bundle provider(int uid, String method, String token, Bundle extras) throws Exception {
        IBinder activity = (IBinder)Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "activity");
        Object am = Class.forName("android.app.IActivityManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, activity);
        int user = uid / 100000;
        try {
            Object holder = am.getClass().getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
                .invoke(am, AUTHORITY, user, null, "headswap-desk-vdm-bootstrap");
            if (holder == null) throw new IllegalStateException("VDM provider unavailable");
            ProviderInfo info = (ProviderInfo)holder.getClass().getField("info").get(holder);
            if (!ManualAccessPolicy.PACKAGE.equals(info.packageName) || info.applicationInfo.uid != uid)
                throw new SecurityException("Foreign VDM provider");
            Object provider = holder.getClass().getField("provider").get(holder);
            return (Bundle)Class.forName("android.content.IContentProvider").getMethod("call", AttributionSource.class,
                String.class, String.class, String.class, Bundle.class).invoke(provider,
                new AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build(), AUTHORITY, method, token, extras);
        } finally {
            try { am.getClass().getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class).invoke(am, AUTHORITY, null, user); }
            catch (ReflectiveOperationException ignored) { }
        }
    }
    private static byte[] read(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int count;
        while ((count = input.read(buffer)) >= 0) { if (result.size() + count > limit) throw new IllegalStateException("Oversized local response"); result.write(buffer, 0, count); }
        return result.toByteArray();
    }
}
