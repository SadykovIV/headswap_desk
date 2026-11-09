package com.headswap.desk.qa;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Separate test UID; changing content and an exact touch marker, never showWhenLocked. */
public final class VirtualDeviceLockFixture extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private int ticks;
    private int touches;
    private View scene;
    private final Runnable tick = new Runnable() {
        @Override public void run() { ticks++; scene.invalidate(); main.postDelayed(this, 125); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE))) {
            finish(); return;
        }
        scene = new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(Color.rgb(10, 90 + ticks % 80, 40));
                paint.setColor(Color.WHITE); paint.setTextSize(28);
                canvas.drawText("HeadSwap VDM QA " + ticks, 28, 60, paint);
                canvas.drawText("display=" + getDisplay().getDisplayId() + " touch=" + touches, 28, 105, paint);
                for (int bit = 0; bit < 16; bit++) {
                    paint.setColor((ticks & (1 << bit)) != 0 ? Color.WHITE : Color.BLACK);
                    canvas.drawRect(bit * 24, 145, bit * 24 + 20, 185, paint);
                }
                paint.setColor(touches > 0 ? Color.MAGENTA : Color.CYAN);
                canvas.drawRect(20, 220, 200, 300, paint);
            }
            @Override public boolean onTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    touches++; invalidate(); writeMarker();
                }
                return true;
            }
        };
        setContentView(scene);
        writeMarker();
    }
    private void writeMarker() {
        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "vdm-fixture.txt"))) {
            out.write(("display=" + getDisplay().getDisplayId() + " touches=" + touches + " ticks=" + ticks)
                .getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) { throw new RuntimeException(error); }
    }
    @Override protected void onResume() {
        super.onResume(); if (scene != null) main.post(tick);
    }
    @Override protected void onPause() {
        main.removeCallbacks(tick); super.onPause();
    }
    @Override protected void onDestroy() {
        main.removeCallbacks(tick); super.onDestroy();
    }
}
