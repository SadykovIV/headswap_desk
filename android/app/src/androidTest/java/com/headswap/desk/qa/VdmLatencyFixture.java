package com.headswap.desk.qa;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/** Encodes only synthetic event sequence and dispatch age into pixels, without per-event disk I/O. */
public final class VdmLatencyFixture extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new View(this) {
            final Paint paint = new Paint(); int sequence; int dispatchMs;
            @Override public boolean onTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    sequence++; dispatchMs = (int)Math.min(0xffffff, Math.max(0, SystemClock.uptimeMillis()-event.getEventTime()));
                    invalidate();
                }
                return true;
            }
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(0xff164934);
                paint.setColor(0xff000000 | sequence); canvas.drawRect(0,0,64,64,paint);
                paint.setColor(0xff000000 | dispatchMs); canvas.drawRect(64,0,128,64,paint);
                paint.setColor(0xffffffff); paint.setTextSize(30);
                canvas.drawText("Synthetic input " + sequence + " / dispatch " + dispatchMs + "ms",16,128,paint);
            }
        });
    }
}
