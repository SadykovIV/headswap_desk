package ru.drivedeck.headswap;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Binder;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.util.Map;
import java.util.WeakHashMap;

/** Native Canvas control inside DriveDeck's own sidebar. The geometry comes from its draw calls,
 * including layout rotations/scales. There is no window placed over another app's controls. */
public final class NotificationStatusBridge {
    private static final WeakHashMap<View, State> STATES = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Boolean> VISIBLE = new WeakHashMap<>();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final int OPEN_NOTIFICATIONS = 0x7f0f0042;
    private static final class State {
        final Binder token = new Binder();
        final RectF icons = new RectF();
        final Matrix base = new Matrix(), inverse = new Matrix();
        boolean enabled, polling, pressed, matrixValid;
        int count;
        long lastPoll;
    }
    private static Activity activity(Context context) {
        for (int i = 0; i < 12; i++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) return null;
            context = next;
        }
        return null;
    }
    private static boolean visible(View view) {
        Activity host = activity(view.getContext());
        return host != null && Boolean.TRUE.equals(VISIBLE.get(host)) && !host.isFinishing()
                && view.isAttachedToWindow() && view.isShown() && DisplayOnlyLaunch.displayId(host) > 0;
    }
    public static void resume(Activity host) { VISIBLE.put(host, true); }
    public static void pause(Activity host) {
        VISIBLE.remove(host);
        for (Map.Entry<View, State> entry : STATES.entrySet()) {
            if (activity(entry.getKey().getContext()) == host) {
                State state = entry.getValue();
                state.enabled = false; state.pressed = false;
                EmbeddedDisplayClient.notificationStatus(host, state.token, false, ignored -> {});
            }
        }
    }
    @SuppressWarnings("deprecation")
    public static void beginDraw(View view, Canvas canvas) {
        State state = STATES.get(view);
        if (state == null) { state = new State(); STATES.put(view, state); }
        state.icons.setEmpty();
        canvas.getMatrix(state.base);
        state.matrixValid = state.base.invert(state.inverse);
    }
    @SuppressWarnings("deprecation")
    public static void icon(View view, Canvas canvas, int resource, RectF bounds) {
        // Verified against the pinned 0.6.9 public.xml and q2.s() status drawing calls.
        if (!(resource >= 0x7f030025 && resource <= 0x7f030030) && resource != 0x7f030089) return;
        State state = STATES.get(view);
        if (state == null || !state.matrixValid) return;
        Matrix transform = new Matrix(); canvas.getMatrix(transform);
        RectF mapped = new RectF(bounds); transform.mapRect(mapped); state.inverse.mapRect(mapped);
        if (!mapped.intersect(0, 0, view.getWidth(), view.getHeight())) return;
        state.icons.union(mapped);
    }
    public static void endDraw(View view, Canvas canvas) {
        State state = STATES.get(view);
        if (state == null || !visible(view) || state.icons.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        if (!state.polling && now - state.lastPoll >= 1_000) {
            state.polling = true; state.lastPoll = now;
            EmbeddedDisplayClient.notificationStatus(view.getContext(), state.token, true, status -> {
                state.polling = false;
                state.enabled = visible(view) && status != null && status.getBoolean("enabled");
                state.count = status == null ? 0 : status.getInt("count");
                view.invalidate();
            });
        }
        if (state.enabled) {
            view.setContentDescription("Панель DriveDeck. Уведомления: " + state.count);
            int save = canvas.save();
            canvas.setMatrix(state.base);
            float density = view.getResources().getDisplayMetrics().density;
            float radius = Math.max(2f, 3f * density);
            PAINT.setColor(state.count > 0 ? 0xFF4FC3F7 : 0xFFB0BEC5);
            canvas.drawCircle(state.icons.right - radius, state.icons.top + radius, radius, PAINT);
            canvas.restoreToCount(save);
        }
        view.postInvalidateDelayed(1_000);
    }
    public static boolean touch(View view, MotionEvent event) {
        State state = STATES.get(view);
        if (state == null) return false;
        boolean inside = state.enabled && visible(view) && state.icons.contains(event.getX(), event.getY());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                state.pressed = inside;
                return inside;
            case MotionEvent.ACTION_MOVE:
                // Keep the accepted gesture consumed even if the finger leaves the native control.
                return state.pressed;
            case MotionEvent.ACTION_UP:
                boolean accepted = state.pressed; state.pressed = false;
                if (accepted && inside) { view.performClick(); EmbeddedDisplayClient.showNotifications(view.getContext()); }
                return accepted;
            case MotionEvent.ACTION_CANCEL:
                boolean cancelled = state.pressed; state.pressed = false; return cancelled;
            default: return state.pressed;
        }
    }
    public static void accessibility(View view, android.view.accessibility.AccessibilityNodeInfo info) {
        State state = STATES.get(view);
        if (state == null || !state.enabled || !visible(view) || state.icons.isEmpty()) return;
        info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                OPEN_NOTIFICATIONS, "Уведомления: " + state.count));
        // Geometry of this View's own control, also available to accessibility clients with
        // explicit user consent. No other app's window or notification content is inspected.
        android.graphics.Rect bounds = new android.graphics.Rect(); state.icons.roundOut(bounds);
        int[] location = new int[2]; view.getLocationOnScreen(location);
        bounds.offset(location[0], location[1]);
        info.getExtras().putParcelable("com.headswap.desk.notificationEntryBounds", bounds);
    }
    public static boolean accessibilityAction(View view, int action) {
        State state = STATES.get(view);
        if (action != OPEN_NOTIFICATIONS || state == null || !state.enabled || !visible(view)) return false;
        EmbeddedDisplayClient.showNotifications(view.getContext());
        return true;
    }
    private NotificationStatusBridge() {}
}
