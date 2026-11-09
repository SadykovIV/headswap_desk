package com.headswap.desk.qa;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

/** Posts and opens only a harmless, same-package Activity notification on disposable AVDs. */
public final class NotificationTargetFixture extends Activity {
    private final android.window.OnBackInvokedCallback predictiveBack = () -> recordBack("predictive");
    private void recordBack(String route) {
        try (java.io.FileOutputStream out = openFileOutput("notification-back.txt", MODE_PRIVATE)) {
            out.write(("back display=" + getDisplay().getDisplayId() + " via=" + route).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    @Override public boolean onKeyDown(int code, android.view.KeyEvent event) {
        if (code == android.view.KeyEvent.KEYCODE_BACK) return true;
        return super.onKeyDown(code, event);
    }
    @Override public boolean onKeyUp(int code, android.view.KeyEvent event) {
        if (code == android.view.KeyEvent.KEYCODE_BACK) {
            if (!event.isCanceled()) recordBack("legacy");
            return true;
        }
        return super.onKeyUp(code, event);
    }
    @Override public void onDestroy() {
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(predictiveBack);
        super.onDestroy();
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, predictiveBack);
        render(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        render(intent);
    }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
            try (java.io.FileOutputStream out = openFileOutput("notification-touch.txt", MODE_PRIVATE)) {
                out.write(("touch display=" + getDisplay().getDisplayId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        }
        return super.dispatchTouchEvent(event);
    }
    private void render(Intent intent) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (intent.getBooleanExtra("post", false)) {
            manager.createNotificationChannel(new NotificationChannel("qa-headswap-open", "HeadSwap test", NotificationManager.IMPORTANCE_DEFAULT));
            Intent open = new Intent(this, NotificationTargetFixture.class).putExtra("opened", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent content = PendingIntent.getActivity(this, 51, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            manager.notify(51, new Notification.Builder(this, "qa-headswap-open")
                    .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("HeadSwapNotificationTarget")
                    .setContentText("Open the managed fullscreen test").setContentIntent(content).build());
            // Keep this ordinary Activity/task alive on the phone: the notification must
            // relocate it via the receiver's launch-display option, not rely on MULTIPLE_TASK.
        }
        if (intent.getBooleanExtra("cancel", false)) { manager.cancel(51); finish(); return; }
        TextView text = new TextView(this);
        text.setTextColor(0xffffffff); text.setBackgroundColor(0xff145431); text.setTextSize(32);
        text.setText("Notification Activity\ndisplay=" + getDisplay().getDisplayId());
        setContentView(text);
    }
}
