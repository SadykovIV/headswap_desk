package ru.drivedeck.headswap;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import com.headswap.desk.embed.IEmbeddedDisplayGateway;
import com.headswap.desk.shared.DesktopRenderPolicy;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Surface consumer only: privileged display ownership stays inside HeadSwap. */
public final class EmbeddedDisplayClient {
    private static final String TAG = "DriveDeckEmbed";
    private static final Handler MAIN = new Handler(android.os.Looper.getMainLooper());
    private static final HandlerThread THREAD = new HandlerThread("drivedeck-embedded-displays");
    private static final Handler WORK;
    private static final Object LOCK = new Object();
    private static final Map<View, Lease> LEASES = new IdentityHashMap<>();
    private static final ArrayList<Lease> RETIRED = new ArrayList<>();
    private static final EmbeddedTextureHandoff<SurfaceTexture,Lease,Runnable> HANDOFF = new EmbeddedTextureHandoff<>();
    private static final Map<Activity, EmbeddedResumeGate> RESUMES = new WeakHashMap<>(); // MAIN only.
    private static volatile IEmbeddedDisplayGateway gateway;
    private static volatile boolean binding;
    private static volatile boolean highResolutionSupported;
    private static volatile int gatewayCapabilities;
    private static final int HOST_RENDER_CONFIG = 0x10;
    private static Context appContext;
    static { THREAD.start(); WORK = new Handler(THREAD.getLooper()); }
    private EmbeddedDisplayClient() {}

    private static final class Lease {
        final View renderer;
        final SurfaceTexture texture;
        final Surface surface;
        final IBinder token = new Binder();
        final int parentId;
        volatile int handle = -1, layoutWidth, layoutHeight;
        volatile String selectedPackage;
        volatile EmbeddedRenderProfile.Geometry geometry, remoteGeometry, createGeometry;
        volatile ParentConfig config;
        volatile boolean closed, ownsTexture, released, geometryBlocked;
        String notifiedGeometryError; // MAIN only.
        final EmbeddedSurfaceRelease releaseState = new EmbeddedSurfaceRelease();
        final EmbeddedRetryGate<EmbeddedRenderProfile.Geometry> resizeGate =
                new EmbeddedRetryGate<>((first, second) -> first.sameAs(second), 250, 1000, 3000);
        final EmbeddedRetryGate<Boolean> releaseGate = new EmbeddedRetryGate<>(Boolean::equals, 500, 1500, 5000);
        Runnable resizeTask, releaseTask; // Guarded by the corresponding gate.
        volatile String pendingLaunch;
        EmbeddedResumeGate.Ticket pendingRestore; // Guarded by LOCK; null for explicit app selection.
        boolean launchQueued; // Guarded by LOCK.
        EmbeddedRenderProfile.Geometry notifiedResizeError; // WORK only.
        final long createdAt = SystemClock.uptimeMillis();
        final Map<String, TaskState> states = new HashMap<>();
        final HashSet<String> queries = new HashSet<>();
        boolean touchDown;
        EmbeddedTouchPacket lastTouch; // WORK only; copied before caller MotionEvent can be recycled.
        int capabilities;
        IBinder producerLifetime;
        IBinder.DeathRecipient producerDeath;
        int attempts;
        IEmbeddedDisplayGateway producer;
        Lease(View renderer, SurfaceTexture texture, int parentId, int width, int height, String packageName) {
            this.renderer = renderer; this.texture = texture; this.parentId = parentId;
            this.layoutWidth = width; this.layoutHeight = height;
            this.selectedPackage = packageName == null ? "" : packageName;
            // Initial local Surface only. No DriveDeck percentage or unverified capability is used
            // until scheduleCreate reads the actual host configuration from the owned parent.
            int provisionalDpi = parentDensity(renderer);
            this.geometry = new EmbeddedRenderProfile.Geometry(Math.max(128, width), Math.max(128, height),
                    provisionalDpi, provisionalDpi, 1, 1);
            texture.setDefaultBufferSize(geometry.width, geometry.height);
            this.surface = new Surface(texture);
        }
    }
    private static final class ParentConfig {
        final IEmbeddedDisplayGateway service;
        final int parentId, density;
        final boolean compactMaps;
        ParentConfig(IEmbeddedDisplayGateway service, int parentId, int[] values) {
            if (values == null || values.length != 2 || values[0] < 96 || values[0] > 640
                    || (values[1] != 0 && values[1] != 1)) throw new IllegalStateException("Invalid desktop render configuration");
            this.service = service;
            this.parentId = parentId;
            this.density = values[0];
            this.compactMaps = values[1] == 1;
        }
    }
    private static final class TaskState {
        final int value; final long at;
        TaskState(int value) { this.value = value; this.at = SystemClock.uptimeMillis(); }
    }

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            WORK.post(() -> {
                try {
                    IEmbeddedDisplayGateway connected = IEmbeddedDisplayGateway.Stub.asInterface(binder);
                    if (connected.version() != 1) throw new IllegalStateException("Unsupported embedded display protocol");
                    int capabilities = 0;
                    // Protocol 1 remains valid on older HeadSwap versions without this new method.
                    try { capabilities = connected.capabilities(); }
                    catch (RemoteException | RuntimeException unavailable) { Log.i(TAG, "Standard child resolution: gateway has no high-resolution capability"); }
                    gatewayCapabilities = capabilities;
                    highResolutionSupported = (capabilities & 1) != 0;
                    binder.linkToDeath(() -> disconnected(binder), 0);
                    gateway = connected;
                    for (Lease lease : leases()) if (!lease.closed && lease.handle < 0) scheduleCreate(lease, 0);
                    Log.i(TAG, "HeadSwap embedded gateway ready");
                } catch (Exception error) { Log.w(TAG, "Gateway connection rejected", error); }
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) { disconnected(null); }
        @Override public void onBindingDied(ComponentName name) {
            disconnected(null);
            MAIN.post(() -> { if (appContext != null) { try { appContext.unbindService(CONNECTION); } catch (RuntimeException ignored) {} binding = false; bind(appContext); } });
        }
        @Override public void onNullBinding(ComponentName name) { binding = false; }
    };
    private static void disconnected(IBinder dead) {
        WORK.post(() -> {
            IEmbeddedDisplayGateway old = gateway;
            if (dead != null && old != null && old.asBinder() != dead) return;
            gateway = null;
            highResolutionSupported = false;
            gatewayCapabilities = 0;
            // A bridge death alone does not prove its shell producer has stopped. Keep resources
            // until an explicit release acknowledgement, or process death releases this consumer.
            for (Lease lease : leases()) {
                if (old == null || lease.producer != old) continue;
                synchronized (LOCK) {
                    if (LEASES.get(lease.renderer) != lease) continue;
                    LEASES.remove(lease.renderer);
                    retireLocked(lease);
                }
                lease.closed = true;
                closeResizeQueue(lease);
                // Keep the old handle and exact gateway. A successor gateway may reuse
                // handle numbers and must never be asked to release this producer.
                retire(lease);
                requestRelease(lease);
                clearRetiredHandle(lease);
            }
            Log.w(TAG, "Gateway disconnected; detached consumers retained pending producer cleanup");
        });
    }
    public static void bind(Context context) {
        if (appContext == null) appContext = context.getApplicationContext();
        if (binding || gateway != null) return;
        MAIN.post(() -> {
            if (binding || gateway != null) return;
            try {
                Intent intent = new Intent().setComponent(new ComponentName(
                        "com.headswap.desk", "com.headswap.desk.embed.EmbeddedDisplayService"));
                binding = appContext.bindService(intent, CONNECTION, Context.BIND_AUTO_CREATE);
                if (!binding) Log.w(TAG, "HeadSwap 0.2.1 embedded service unavailable");
            } catch (RuntimeException error) { binding = false; Log.w(TAG, "Cannot bind embedded service", error); }
        });
    }
    public static void prepare(Context context) {
        bind(context);
        SharedPreferences prefs = context.getSharedPreferences("drive_deck", 0);
        SharedPreferences.Editor edit = prefs.edit();
        String map = prefs.getString("home_app", "");
        if (map == null || map.isEmpty()) {
            for (String candidate : new String[]{prefs.getString("startup_app_0", ""), prefs.getString("slot_0", ""), "ru.yandex.yandexnavi"}) {
                if (launchable(context, candidate)) { map = candidate; edit.putString("home_app", map); break; }
            }
        }
        if (prefs.getString("slot_0", "").isEmpty() && launchable(context, map)) edit.putString("slot_0", map);
        if ("home".equals(prefs.getString("startup_screen", "home")) && prefs.getString("startup_app_0", "").isEmpty() && launchable(context, map)) edit.putString("startup_app_0", map);
        String music = prefs.getString("music_app_package", "");
        if ((music == null || music.isEmpty()) && launchable(context, "ru.yandex.music")) { music = "ru.yandex.music"; edit.putString("music_app_package", music); }
        if (prefs.getString("slot_1", "").isEmpty() && launchable(context, music) && !music.equals(map)) edit.putString("slot_1", music);
        edit.apply();
    }
    private static boolean launchable(Context context, String name) {
        return name != null && !name.isEmpty() && !name.equals(context.getPackageName())
                && context.getPackageManager().getLaunchIntentForPackage(name) != null;
    }
    public static boolean capability(Context context) { return DisplayOnlyLaunch.allowed(context); }
    public static void openSplit(Activity activity) {
        if (!DisplayOnlyLaunch.allowed(activity)) return;
        SharedPreferences prefs = activity.getSharedPreferences("drive_deck", 0);
        if (!prefs.getBoolean("split_page_enabled", true)) return;
        try {
            int missing = prefs.getString("slot_0", "").isEmpty() ? 0 : prefs.getString("slot_1", "").isEmpty() ? 1 : -1;
            if (missing >= 0) activity.getClass().getMethod("i", int.class).invoke(activity, missing);
            else activity.getClass().getMethod("z").invoke(activity);
        } catch (ReflectiveOperationException error) { Log.e(TAG, "Cannot open internal split page", error); }
    }
    private static int parentDensity(View renderer) {
        int expected = DisplayOnlyLaunch.displayId(renderer.getContext());
        Activity host = DisplayOnlyLaunch.activity(renderer.getContext());
        Display display = renderer.getDisplay();
        if (display == null && host != null) display = host.getDisplay();
        if (expected <= 0 || display == null || !display.isValid() || display.getDisplayId() != expected) {
            throw new IllegalArgumentException("Рабочий стол недоступен. Подключите HeadSwap заново.");
        }
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        return metrics.densityDpi;
    }
    private static EmbeddedRenderProfile.Geometry legacyGeometry(View renderer, String selected, boolean extended, int width, int height) {
        int parent = parentDensity(renderer);
        int percent = integer(renderer, "L", 100);
        int pane = Math.round(parent * Math.max(50, Math.min(120, percent)) / 100f);
        return EmbeddedRenderProfile.geometry(selected, highResolutionSupported, extended, width, height, parent, pane);
    }
    public static int density(View renderer) {
        synchronized (LOCK) {
            Lease lease = LEASES.get(renderer);
            if (lease != null && !lease.closed) return lease.geometry.density;
        }
        try {
            if ((gatewayCapabilities & HOST_RENDER_CONFIG) != 0) return parentDensity(renderer);
            return legacyGeometry(renderer, selectedPackage(renderer), gateway == null || (gatewayCapabilities & 8) != 0,
                    Math.max(128, renderer.getWidth()), Math.max(128, renderer.getHeight())).density;
        } catch (IllegalArgumentException detachedOrUnsupported) {
            // Original Y7 uses this as a diagnostic hint before attach. Never create from a fallback DPI.
            return 0;
        }
    }

    // Only called on WORK. A configuration belongs to this specific lease, gateway and parent.
    private static boolean readParentConfig(Lease lease, IEmbeddedDisplayGateway service) {
        if (lease.closed || service == null || service != gateway) return false;
        if ((gatewayCapabilities & HOST_RENDER_CONFIG) == 0) return true;
        try {
            ParentConfig config = new ParentConfig(service, lease.parentId, service.desktopRenderConfig(lease.parentId));
            if (lease.closed || service != gateway) return false;
            lease.config = config;
            return true;
        } catch (RemoteException | RuntimeException unavailable) {
            Log.w(TAG, "Desktop render configuration not ready on display " + lease.parentId, unavailable);
            return false;
        }
    }

    /** Only route a live DriveDeck Activity on the parent registered by the current HeadSwap.
     * A hub tap must not block the main thread on the display service. */
    static boolean checkFullscreenParent(Activity host, int parent, Consumer<Boolean> finish) {
        IEmbeddedDisplayGateway service = gateway;
        if (parent <= 0 || service == null || (gatewayCapabilities & HOST_RENDER_CONFIG) == 0) return false;
        EmbeddedResumeGate gate = RESUMES.get(host);
        EmbeddedResumeGate.Ticket ticket = gate == null ? null : gate.current(parent);
        WORK.post(() -> {
            boolean admitted;
            try {
                new ParentConfig(service, parent, service.desktopRenderConfig(parent));
                admitted = true;
            } catch (RemoteException | RuntimeException unavailable) {
                admitted = false;
            }
            final boolean active = admitted;
            MAIN.post(() -> {
                if (DisplayOnlyLaunch.displayId(host) != parent || (ticket != null && !ticket.isCurrent(parent))) return;
                finish.accept(active && service == gateway);
            });
        });
        return true;
    }

    /** Called after the original Activity's resume guards. No producer is stopped/recreated here. */
    public static void resume(Activity host) {
        int parent = DisplayOnlyLaunch.displayId(host);
        if (parent <= 0) return;
        EmbeddedResumeGate gate = RESUMES.get(host);
        if (gate == null) { gate = new EmbeddedResumeGate(); RESUMES.put(host, gate); }
        final EmbeddedResumeGate.Ticket ticket = gate.resume(parent);
        host.getWindow().getDecorView().post(() -> {
            if (!ticket.isCurrent(DisplayOnlyLaunch.displayId(host))) return;
            ArrayDeque<View> views = new ArrayDeque<>();
            views.add(host.getWindow().getDecorView());
            while (!views.isEmpty()) {
                View view = views.removeFirst();
                if ("c.Y7".equals(view.getClass().getName())) restoreRenderer(view, ticket);
                else if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) views.addLast(group.getChildAt(i));
                }
            }
        });
    }
    public static void pause(Activity host) {
        EmbeddedResumeGate gate = RESUMES.get(host);
        if (gate != null) gate.pause();
    }
    private static void restoreRenderer(View renderer, EmbeddedResumeGate.Ticket ticket) {
        int parent = DisplayOnlyLaunch.displayId(renderer.getContext());
        if (!ticket.isCurrent(parent) || !renderer.isAttachedToWindow() || !renderer.isShown()
                || bool(renderer, "A") || bool(renderer, "B") || !bool(renderer, "c0")) return;
        String selected = selectedPackage(renderer);
        if (selected == null || selected.isEmpty()) return;
        boolean hasHostConfig = (gatewayCapabilities & HOST_RENDER_CONFIG) != 0;
        if (!hasHostConfig && !EmbeddedRenderProfile.NAVIGATOR_PACKAGE.equals(selected)) return;
        Object child = field(renderer, "t");
        if (!(child instanceof TextureView)) return;
        TextureView view = (TextureView) child;
        if (!view.isAvailable() || view.getWidth() <= 0 || view.getHeight() <= 0) return;
        SurfaceTexture texture = view.getSurfaceTexture();
        if (texture == null) return;
        // Reuse the existing lease; if the original hide path closed it, attach recreates only
        // that renderer. Existing serialized release/create and producer ACK rules remain intact.
        attach(renderer, texture, view.getWidth(), view.getHeight(), ticket);
        Lease lease;
        synchronized (LOCK) { lease = LEASES.get(renderer); }
        if (lease == null || lease.closed || lease.parentId != parent || lease.texture != texture) return;
        WORK.post(() -> resumeGeometry(lease, selected, ticket, 0));
    }
    private static void resumeGeometry(Lease lease, String selected, EmbeddedResumeGate.Ticket ticket, int attempt) {
        if (lease.closed || !ticket.isCurrent(lease.parentId) || !selected.equals(lease.selectedPackage)) return;
        if (!readParentConfig(lease, gateway)) {
            if (attempt < 4) WORK.postDelayed(() -> resumeGeometry(lease, selected, ticket, attempt+1), 500);
            return;
        }
        MAIN.post(() -> {
            if (lease.closed || !ticket.isCurrent(lease.parentId) || !selected.equals(selectedPackage(lease.renderer))) return;
            if (!updateGeometry(lease, lease.layoutWidth, lease.layoutHeight, selected)) return;
            WORK.post(() -> {
                if (lease.closed || !ticket.isCurrent(lease.parentId) || !selected.equals(lease.selectedPackage)) return;
                lease.remoteGeometry = null;
                // The map restore may need to bring its existing task back. Other apps just resize;
                // navigating within them (including to external activities) must retain their stack.
                if (DesktopRenderPolicy.isMap(selected)) {
                    synchronized (LOCK) {
                        lease.states.remove(selected);
                        lease.pendingLaunch = selected;
                        lease.pendingRestore = ticket;
                    }
                }
                requestResize(lease, true);
            });
        });
    }
    private static String selectedPackage(View renderer) {
        // Exact supplied 0.6.8 graph: Y7.a is the renderer callback, d9.b its owning
        // H2 pane, and H2.w the selected package. Never infer an app from a slot number.
        Object pane = field(field(renderer, "a"), "b");
        Object selected = field(pane, "w");
        return selected instanceof String ? (String) selected : null;
    }
    private static boolean updateGeometry(Lease lease, int width, int height, String packageName) {
        if (lease.closed) return false;
        lease.layoutWidth = width; lease.layoutHeight = height;
        if (packageName != null) lease.selectedPackage = packageName;
        final EmbeddedRenderProfile.Geometry wanted;
        try {
            // Before binding only a local buffer is prepared. Strict advertised limits are checked
            // again on main after binding, before create/resize can reach a producer.
            if ((gatewayCapabilities & HOST_RENDER_CONFIG) != 0) {
                ParentConfig config = lease.config;
                if (config == null || config.service != gateway || config.parentId != lease.parentId) return false;
                wanted = EmbeddedRenderProfile.configured(lease.selectedPackage, width, height, config.density, config.compactMaps);
            } else {
                wanted = legacyGeometry(lease.renderer, lease.selectedPackage, gateway == null || (gatewayCapabilities & 8) != 0,
                        width, height);
            }
        } catch (IllegalArgumentException error) {
            lease.geometryBlocked = true;
            WORK.post(() -> { try { cancelActiveTouch(lease); } catch (RemoteException errorOnCancel) { Log.w(TAG, "Cannot cancel touch after rejected geometry", errorOnCancel); } });
            if (!error.getMessage().equals(lease.notifiedGeometryError)) {
                lease.notifiedGeometryError = error.getMessage();
                message(lease.renderer, error.getMessage());
            }
            return false;
        }
        lease.geometryBlocked = false;
        lease.notifiedGeometryError = null;
        boolean changed = !wanted.sameAs(lease.geometry);
        lease.geometry = wanted;
        // A retained TextureView can have its default buffer reset on reattachment/size changes.
        // Reapply the consumer and coordinate mapping even when the producer already has this size.
        lease.texture.setDefaultBufferSize(wanted.width, wanted.height);
        // Original Y7 touch mapping reads H/I and already multiplies by render/view.
        // Keep these in buffer pixels; input() must not apply the scale a second time.
        setSize(lease.renderer, wanted.width, wanted.height, wanted.density);
        if (changed) {
            Log.i(TAG, "App geometry " + lease.selectedPackage + " parentDpi=" + wanted.parentDensity
                    + " requestedScale=" + wanted.requestedScale + " effectiveScale=" + wanted.effectiveScale
                    + " render=" + wanted.width + "x" + wanted.height + " dpi=" + wanted.density);
        }
        return true;
    }
    private static boolean syncRemoteGeometry(Lease lease, EmbeddedRenderProfile.Geometry wanted) {
        if (wanted.sameAs(lease.remoteGeometry)) return true;
        try {
            cancelActiveTouch(lease);
            lease.producer.resize(lease.handle, wanted.width, wanted.height, wanted.density);
            lease.remoteGeometry = wanted;
            return true;
        } catch (Exception error) {
            Log.w(TAG, "Embedded resize rejected", error);
            // An RPC failure can follow a successful remote resize with a lost reply.
            // Do not restore cached geometry or permit input until a fresh resize ACK.
            lease.remoteGeometry = null;
            if (!wanted.sameAs(lease.notifiedResizeError)) {
                lease.notifiedResizeError = wanted;
                message(lease.renderer, "Не удалось изменить размер окна. Выполняется повторная попытка.");
            }
            return false;
        }
    }
    private static void requestResize(Lease lease) {
        requestResize(lease, false);
    }
    private static void requestResize(Lease lease, boolean refresh) {
        if (lease.closed || lease.geometryBlocked || lease.handle <= 0 || lease.producer == null) return;
        synchronized (lease.resizeGate) {
            EmbeddedRetryGate.Ticket ticket = refresh ? lease.resizeGate.refresh(lease.geometry) : lease.resizeGate.offer(lease.geometry);
            scheduleResizeLocked(lease, ticket);
        }
    }
    private static void scheduleResizeLocked(Lease lease, EmbeddedRetryGate.Ticket ticket) {
        if (ticket == null) return;
        if (lease.resizeTask != null) WORK.removeCallbacks(lease.resizeTask);
        lease.resizeTask = () -> {
            EmbeddedRetryGate.Attempt<EmbeddedRenderProfile.Geometry> attempt = lease.resizeGate.begin(ticket);
            if (attempt == null) return;
            boolean success = !lease.closed && !lease.geometryBlocked && syncRemoteGeometry(lease, attempt.value);
            synchronized (lease.resizeGate) {
                scheduleResizeLocked(lease, lease.resizeGate.complete(attempt, success));
            }
            if (success) { lease.notifiedResizeError = null; queueLaunch(lease); }
        };
        WORK.postDelayed(lease.resizeTask, ticket.delayMillis);
    }
    private static void closeResizeQueue(Lease lease) {
        synchronized (lease.resizeGate) {
            lease.resizeGate.close();
            if (lease.resizeTask != null) WORK.removeCallbacks(lease.resizeTask);
            lease.resizeTask = null;
        }
        synchronized (LOCK) { lease.pendingLaunch = null; lease.pendingRestore = null; }
    }
    private static void queueLaunch(Lease lease) {
        synchronized (LOCK) {
            if (lease.closed || lease.pendingLaunch == null || lease.launchQueued) return;
            lease.launchQueued = true;
        }
        WORK.post(() -> {
            String selected;
            EmbeddedResumeGate.Ticket restore;
            synchronized (LOCK) {
                lease.launchQueued = false;
                selected = lease.pendingLaunch;
                restore = lease.pendingRestore;
                if (lease.closed || lease.geometryBlocked || selected == null) return;
                if (lease.pendingRestore != null && !lease.pendingRestore.isCurrent(lease.parentId)) {
                    lease.pendingLaunch = null; lease.pendingRestore = null; return;
                }
                if (!selected.equals(lease.selectedPackage)) { lease.pendingLaunch = null; lease.pendingRestore = null; return; }
                if (!lease.geometry.sameAs(lease.remoteGeometry)) return; // A future resize ACK resumes this launch.
                lease.pendingLaunch = null;
                lease.pendingRestore = null;
            }
            try {
                if (restore != null) {
                    int state = lease.producer.taskState(lease.handle, selected);
                    // Keep an already foreground Navigator's route/activity intact. An unknown
                    // placement is not evidence that the user's app should be started again.
                    if (state < 0 || state == 2 || !restore.isCurrent(lease.parentId) || lease.closed
                            || !selected.equals(lease.selectedPackage)) return;
                }
                if (!lease.producer.launch(lease.handle, selected)) message(lease.renderer, "Не удалось открыть приложение в окне");
            }
            catch (Exception error) { Log.w(TAG, "Embedded launch rejected", error); message(lease.renderer, "Приложение не открылось в окне"); }
            requestState(lease, selected);
        });
    }
    public static void attach(View renderer, SurfaceTexture texture, int width, int height) {
        attach(renderer, texture, width, height, null);
    }
    private static void attach(View renderer, SurfaceTexture texture, int width, int height,
                               EmbeddedResumeGate.Ticket restore) {
        if (texture == null || texture.isReleased() || width <= 0 || height <= 0 || !renderer.isShown()
                || bool(renderer, "A") || bool(renderer, "B") || !bool(renderer, "c0")) return;
        int parent = DisplayOnlyLaunch.displayId(renderer.getContext());
        if (parent <= 0) return;
        bind(renderer.getContext());
        Lease previous;
        synchronized (LOCK) { previous = LEASES.get(renderer); }
        if (previous != null && previous.texture == texture && !previous.closed) {
            if (previous.parentId != parent) return;
            resize(renderer, width, height);
            return;
        }
        if (previous != null) close(renderer, false);
        Activity host = DisplayOnlyLaunch.activity(renderer.getContext());
        EmbeddedResumeGate gate = RESUMES.get(host);
        EmbeddedResumeGate.Ticket active = restore != null ? restore : gate == null ? null : gate.current(parent);
        synchronized (LOCK) {
            // A failed/ambiguous release stays in RETIRED even though the gateway handle
            // disappeared from LEASES. Never create a second producer for the same texture.
            if (HANDOFF.deferIfRetired(texture, active == null ? null : () -> {
                if (!active.isCurrent(DisplayOnlyLaunch.displayId(renderer.getContext())) || texture.isReleased()
                        || !renderer.isAttachedToWindow() || !renderer.isShown()) return;
                Object child = field(renderer, "t");
                if (!(child instanceof TextureView)) return;
                TextureView view = (TextureView) child;
                if (!view.isAvailable() || view.getSurfaceTexture() != texture) return;
                // Re-read selection/geometry now, not the values from a former foreground generation.
                if (restore != null) restoreRenderer(renderer, active);
                else attach(renderer, texture, view.getWidth(), view.getHeight(), null);
            })) return;
            for (Lease live : LEASES.values()) if (live.texture == texture) return;
        }
        final Lease lease;
        try { lease = new Lease(renderer, texture, parent, width, height, selectedPackage(renderer)); }
        catch (IllegalArgumentException error) { message(renderer, error.getMessage()); return; }
        synchronized (LOCK) { LEASES.put(renderer, lease); }
        setSize(renderer, lease.geometry.width, lease.geometry.height, lease.geometry.density);
        scheduleCreate(lease, 0);
    }
    private static void scheduleCreate(Lease lease, long delay) {
        WORK.postDelayed(() -> {
            if (lease.closed || lease.handle > 0) return;
            IEmbeddedDisplayGateway service = gateway;
            if (!readParentConfig(lease, service)) {
                if (retryCreate(lease)) scheduleCreate(lease, 500);
                else message(lease.renderer, "Не удалось получить параметры рабочего стола. Проверьте подключение к HeadSwap.");
                return;
            }
            // Capability and package can become known after attach. Configure buffer and
            // touch transform on main before the producer acquires this Surface.
            MAIN.post(() -> {
                if (lease.closed || lease.handle > 0 || service != gateway) return;
                if (!updateGeometry(lease, lease.layoutWidth, lease.layoutHeight, selectedPackage(lease.renderer))) return;
                WORK.post(() -> create(lease, service));
            });
        }, delay);
    }
    private static void create(Lease lease, IEmbeddedDisplayGateway service) {
        if (lease.closed || lease.geometryBlocked || lease.handle > 0 || service != gateway) return;
        try {
            lease.producer = service;
            lease.capabilities = gatewayCapabilities;
            // Retrying an ambiguous create must use its original geometry, even if the
            // divider/package changed meanwhile; apply the latest resize after its ACK.
            if (lease.createGeometry == null) lease.createGeometry = lease.geometry;
            EmbeddedRenderProfile.Geometry requested = lease.createGeometry;
            lease.releaseState.beforeCreate();
            int handle = service.create(lease.surface, lease.token, lease.parentId,
                    requested.width, requested.height, requested.density);
            if (handle <= 0) throw new IllegalStateException("HeadSwap embedded display NOT_READY");
            lease.handle = handle;
            lease.remoteGeometry = requested;
            watchProducerLifetime(lease);
            if (lease.closed) { requestRelease(lease); return; }
            requestResize(lease);
            MAIN.post(() -> {
                if (lease.closed) return;
                setHandle(lease.renderer, handle);
                notifyReady(lease);
                EmbeddedRenderProfile.Geometry size = lease.geometry;
                Log.i(TAG, "Embedded renderer ready handle=" + handle + " parent=" + lease.parentId
                        + " render=" + size.width + "x" + size.height + " dpi=" + size.density);
            });
        } catch (Exception error) {
            if (lease.attempts == 0) Log.w(TAG, "First embedded create attempt failed; waiting for active HeadSwap session", error);
            if (!lease.closed && retryCreate(lease)) scheduleCreate(lease, 500);
            else { Log.w(TAG, "Embedded display unavailable", error); message(lease.renderer, "Не удалось открыть окно. Проверьте подключение HeadSwap."); }
        }
    }
    private static boolean retryCreate(Lease lease) {
        return ++lease.attempts <= 40 && SystemClock.uptimeMillis() - lease.createdAt < 20000;
    }
    private static void notifyReady(Lease lease) {
        MAIN.post(() -> {
            if (lease.closed) return;
            Object ready = field(lease.renderer, "S");
            if (ready instanceof Runnable) lease.renderer.post((Runnable) ready);
        });
    }
    public static int handle(View renderer) {
        synchronized (LOCK) { Lease lease = LEASES.get(renderer); return lease == null || lease.closed ? -1 : lease.handle; }
    }
    public static void resize(View renderer, int width, int height) {
        Lease lease;
        synchronized (LOCK) { lease = LEASES.get(renderer); }
        if (lease == null || lease.closed || width <= 0 || height <= 0) return;
        if (!updateGeometry(lease, width, height, selectedPackage(renderer))) return;
        if (lease.handle <= 0) scheduleCreate(lease, 0); else requestResize(lease);
    }
    public static void scale(View renderer, int percent) {
        set(renderer, "L", Math.max(50, Math.min(120, percent)));
        if ((gatewayCapabilities & HOST_RENDER_CONFIG) != 0) return;
        resize(renderer, renderer.getWidth(), renderer.getHeight());
    }
    public static void scaleSettings(ViewGroup controls, TextView hint) {
        if (gateway != null && (gatewayCapabilities & HOST_RENDER_CONFIG) == 0) return;
        for (int i = 0; i < controls.getChildCount(); i++) {
            View child = controls.getChildAt(i);
            if (child != hint) child.setVisibility(View.GONE);
        }
        hint.setText(gateway != null ? "Размер интерфейса выбирается в HeadSwap → Дополнительно → Размер интерфейса."
                : "Параметры окна будут доступны после подключения HeadSwap.");
    }
    public static boolean launch(int handle, String packageName) {
        Lease lease = find(handle);
        if (lease == null || lease.producer == null || packageName == null || packageName.isEmpty()) return false;
        synchronized (LOCK) { lease.states.remove(packageName); }
        // A reused pane must shrink back for music/other apps before their launch.
        MAIN.post(() -> {
            if (lease.closed) return;
            if (!updateGeometry(lease, lease.layoutWidth, lease.layoutHeight, packageName)) return;
            synchronized (LOCK) { lease.pendingLaunch = packageName; lease.pendingRestore = null; }
            requestResize(lease);
            queueLaunch(lease);
        });
        return true;
    }
    public static int taskState(int handle, String packageName, boolean topOnly) {
        Lease lease = find(handle);
        if (lease == null || lease.producer == null) return -1;
        TaskState cached;
        synchronized (LOCK) { cached = lease.states.get(packageName); }
        if (cached == null || SystemClock.uptimeMillis() - cached.at > 350) requestState(lease, packageName);
        if (cached == null || SystemClock.uptimeMillis() - cached.at > 2000) return -1;
        int state = cached.value;
        return state < 0 ? -1 : (topOnly ? state == 2 : state > 0) ? 1 : 0;
    }
    private static void requestState(Lease lease, String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        synchronized (LOCK) { if (lease.closed || !lease.queries.add(packageName)) return; }
        WORK.post(() -> {
            int result = -1;
            if (!lease.closed && lease.producer != null) {
                try { result = lease.producer.taskState(lease.handle, packageName); }
                catch (Exception error) { Log.w(TAG, "Embedded placement query failed", error); }
            }
            TaskState previous;
            synchronized (LOCK) {
                lease.queries.remove(packageName);
                previous = lease.states.put(packageName, new TaskState(result));
            }
            // The first renderer-ready check can precede launch. Re-run it after real evidence
            // arrives, rather than claiming a launch succeeded or blocking the main thread.
            if (!lease.closed && (previous == null || previous.value != result)) notifyReady(lease);
        });
    }
    public static boolean input(int handle, InputEvent event) {
        Lease lease = find(handle);
        if (lease == null || lease.producer == null) return false;
        if (event instanceof KeyEvent) {
            KeyEvent key = (KeyEvent) event;
            if (key.getKeyCode() != KeyEvent.KEYCODE_BACK) return false;
            if (key.getAction() == KeyEvent.ACTION_UP) WORK.post(() -> {
                if (!lease.closed) try { lease.producer.back(handle); } catch (Exception error) { Log.w(TAG, "Embedded back rejected", error); }
            });
            return true;
        }
        if (!(event instanceof MotionEvent)) return false;
        MotionEvent motion = (MotionEvent) event;
        final EmbeddedRenderProfile.Geometry coordinates = lease.geometry;
        if (lease.geometryBlocked || !coordinates.sameAs(lease.remoteGeometry)) return true;
        final boolean multi = (lease.capabilities & 2) != 0;
        int action = motion.getActionMasked();
        final EmbeddedTouchPacket packet;
        if (multi) {
            int count = motion.getPointerCount();
            if (count < 1 || count > 2) return false;
            int[] ids = new int[count];
            float[] xs = new float[count], ys = new float[count];
            for (int i = 0; i < count; i++) {
                ids[i] = motion.getPointerId(i);
                // v7 already transformed every coordinate once into actual buffer pixels.
                xs[i] = motion.getX(i); ys[i] = motion.getY(i);
            }
            packet = EmbeddedTouchPacket.snapshot(action, motion.getActionIndex(), ids, xs, ys,
                    coordinates.width, coordinates.height);
        } else {
            // Preserve protocol-1 primary-touch behavior when the capability is absent.
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP &&
                    action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_CANCEL) return true;
            packet = EmbeddedTouchPacket.snapshot(action, 0, new int[]{0},
                    new float[]{motion.getX()}, new float[]{motion.getY()}, coordinates.width, coordinates.height);
        }
        if (packet == null) return false;
        WORK.post(() -> {
            if (lease.closed || lease.geometryBlocked || !coordinates.sameAs(lease.remoteGeometry)) return;
            if (packet.action == MotionEvent.ACTION_DOWN) lease.touchDown = true;
            if (!lease.touchDown && packet.action != MotionEvent.ACTION_DOWN) return;
            lease.lastTouch = packet;
            try {
                if (multi) lease.producer.touchPoints(handle, packet.action, packet.actionIndex, packet.ids, packet.xs, packet.ys);
                else lease.producer.touch(handle, packet.action, packet.xs[0], packet.ys[0]);
                if (packet.action == MotionEvent.ACTION_UP || packet.action == MotionEvent.ACTION_CANCEL) {
                    lease.touchDown = false; lease.lastTouch = null;
                }
            } catch (Exception error) { Log.w(TAG, "Embedded touch rejected", error); }
        });
        return true;
    }
    private static void cancelActiveTouch(Lease lease) throws RemoteException {
        if (!lease.touchDown) return;
        if ((lease.capabilities & 2) != 0 && lease.lastTouch != null) {
            EmbeddedTouchPacket cancel = lease.lastTouch.cancel();
            lease.producer.touchPoints(lease.handle, cancel.action, cancel.actionIndex, cancel.ids, cancel.xs, cancel.ys);
        } else lease.producer.touch(lease.handle, MotionEvent.ACTION_CANCEL, 0, 0);
        lease.touchDown = false; lease.lastTouch = null;
    }
    private static void watchProducerLifetime(Lease lease) {
        if ((lease.capabilities & 4) == 0) return;
        try {
            IBinder lifetime = lease.producer.producerLifetime(lease.handle);
            if (lifetime == null) return;
            IBinder.DeathRecipient death = () -> producerDied(lease);
            synchronized (lease.releaseState) {
                lease.producerLifetime = lifetime;
                lease.producerDeath = death;
            }
            try { lifetime.linkToDeath(death, 0); }
            catch (RemoteException alreadyDead) {
                if (!lifetime.isBinderAlive()) producerDied(lease);
                else throw alreadyDead;
            }
        } catch (Exception error) {
            // The optional empty Binder carries no methods or privileges. Without its
            // death proof keep requiring the exact original gateway's release ACK.
            Log.w(TAG, "Producer lifetime unavailable; explicit release ACK remains required", error);
        }
    }
    private static void producerDied(Lease lease) {
        lease.releaseState.acknowledgeProducerRelease();
        lease.closed = true;
        closeResizeQueue(lease);
        WORK.post(() -> {
            boolean current;
            synchronized (LOCK) {
                current = LEASES.get(lease.renderer) == lease;
                if (current) LEASES.remove(lease.renderer);
                retireLocked(lease);
            }
            retire(lease);
            if (current) clearRetiredHandle(lease);
            synchronized (lease.releaseGate) {
                lease.releaseGate.close();
                if (lease.releaseTask != null) WORK.removeCallbacks(lease.releaseTask);
                lease.releaseTask = null;
            }
            releaseConsumerIfSafe(lease);
        });
    }
    private static void unlinkProducerLifetime(Lease lease) {
        synchronized (lease.releaseState) {
            if (lease.producerLifetime != null && lease.producerDeath != null) {
                try { lease.producerLifetime.unlinkToDeath(lease.producerDeath, 0); }
                catch (RuntimeException ignored) { }
            }
            lease.producerLifetime = null; lease.producerDeath = null;
        }
    }
    public static void hidePane(Object pane, boolean visible) {
        if (visible) return;
        for (String key : new String[]{"f", "j", "k", "R"}) {
            Object renderer = field(pane, key);
            if (renderer instanceof View) close((View) renderer, false);
        }
    }
    public static boolean destroyed(View renderer, SurfaceTexture texture) {
        Lease lease;
        synchronized (LOCK) { lease = LEASES.get(renderer); }
        if (lease != null && lease.texture == texture) {
            lease.ownsTexture = true;
            close(renderer, true);
        } else {
            Lease retained = null;
            synchronized (LOCK) { for (Lease old : RETIRED) if (old.texture == texture) { retained = old; break; } }
            if (retained != null) { retained.ownsTexture = true; releaseConsumerIfSafe(retained); }
            else texture.release();
        }
        HANDOFF.destroyed(texture);
        return false;
    }
    public static void close(View renderer, boolean ownsTexture) {
        Lease lease;
        synchronized (LOCK) {
            lease = LEASES.remove(renderer);
            if (lease != null) retireLocked(lease);
        }
        if (lease == null) return;
        lease.closed = true;
        closeResizeQueue(lease);
        lease.ownsTexture |= ownsTexture;
        setHandle(renderer, -1);
        retire(lease);
        requestRelease(lease);
    }
    private static void requestRelease(Lease lease) {
        synchronized (lease.releaseGate) {
            scheduleReleaseLocked(lease, lease.releaseGate.offer(Boolean.TRUE));
        }
    }
    private static void scheduleReleaseLocked(Lease lease, EmbeddedRetryGate.Ticket ticket) {
        if (ticket == null) return;
        if (lease.releaseTask != null) WORK.removeCallbacks(lease.releaseTask);
        lease.releaseTask = () -> {
            EmbeddedRetryGate.Attempt<Boolean> attempt = lease.releaseGate.begin(ticket);
            if (attempt == null) return;
            boolean success = releaseRemote(lease);
            synchronized (lease.releaseGate) {
                scheduleReleaseLocked(lease, lease.releaseGate.complete(attempt, success));
            }
        };
        WORK.postDelayed(lease.releaseTask, ticket.delayMillis);
    }
    private static boolean releaseRemote(Lease lease) {
        try {
            if (!lease.releaseState.mayReleaseConsumer() && lease.handle > 0 && lease.producer != null) {
                // Only this exact old gateway can acknowledge this handle. Its death,
                // unbind, a new gateway or a timeout never establishes producer release.
                lease.producer.release(lease.handle);
                lease.releaseState.acknowledgeProducerRelease();
            }
            if (!lease.releaseState.mayReleaseConsumer()) return false;
            // The serial queue and closed flag ensure no later create acquires this Surface.
            releaseConsumerIfSafe(lease);
            return true;
        } catch (Exception error) {
            Log.w(TAG, "Retaining Surface until producer release is confirmed", error);
            return false;
        }
    }
    private static void releaseConsumerIfSafe(Lease lease) {
        if (!lease.releaseState.mayReleaseConsumer()) return;
        MAIN.post(() -> {
            unlinkProducerLifetime(lease);
            if (!lease.released) { lease.surface.release(); lease.released = true; }
            // Without ownership the TextureView still owns the texture; its later destroyed()
            // callback can release it directly because the producer and our Surface are gone.
            EmbeddedTextureHandoff.Completion<Runnable> completed;
            synchronized (LOCK) {
                RETIRED.remove(lease);
                if (lease.ownsTexture) HANDOFF.destroyed(lease.texture);
                completed = HANDOFF.released(lease.texture, lease);
            }
            if (completed == null) return;
            if (completed.releaseTexture) lease.texture.release();
            else if (completed.retry != null) completed.retry.run();
        });
    }
    private static void retireLocked(Lease lease) {
        if (!lease.released && !RETIRED.contains(lease)) {
            RETIRED.add(lease);
            HANDOFF.retire(lease.texture, lease);
        }
    }
    private static void retire(Lease lease) { synchronized (LOCK) { retireLocked(lease); } }
    private static Lease find(int handle) {
        if (handle <= 0) return null;
        synchronized (LOCK) { for (Lease lease : LEASES.values()) if (!lease.closed && lease.handle == handle) return lease; }
        return null;
    }
    private static ArrayList<Lease> leases() { synchronized (LOCK) { return new ArrayList<>(LEASES.values()); } }
    private static void setHandle(View renderer, int handle) {
        try { renderer.getClass().getMethod("o", int.class).invoke(renderer, handle); }
        catch (ReflectiveOperationException error) { Log.e(TAG, "Renderer handle update failed", error); }
    }
    private static void clearRetiredHandle(Lease lease) {
        MAIN.post(() -> {
            synchronized (LOCK) {
                // A late disconnect/death callback must not erase a successor lease.
                if (LEASES.containsKey(lease.renderer)) return;
            }
            setHandle(lease.renderer, -1);
        });
    }
    private static void setSize(View renderer, int width, int height, int dpi) { set(renderer, "H", width); set(renderer, "I", height); set(renderer, "M", dpi); }
    private static Object field(Object object, String name) {
        try { return object.getClass().getField(name).get(object); }
        catch (ReflectiveOperationException | NullPointerException error) { return null; }
    }
    private static int integer(Object object, String name, int fallback) { Object value = field(object, name); return value instanceof Integer ? (Integer) value : fallback; }
    private static boolean bool(Object object, String name) { return Boolean.TRUE.equals(field(object, name)); }
    private static void set(Object object, String name, Object value) {
        try { Field field = object.getClass().getField(name); field.set(object, value); }
        catch (ReflectiveOperationException error) { Log.e(TAG, "Renderer field update failed: " + name, error); }
    }
    private static void message(View renderer, String message) { MAIN.post(() -> { if (renderer.isShown()) Toast.makeText(renderer.getContext(), message, Toast.LENGTH_SHORT).show(); }); }
}
