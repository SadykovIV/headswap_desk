package com.headswap.desk.qa;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.view.KeyEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Harmless exported test activity, distinct UID, installed only on disposable emulators. */
public final class DesktopKnobTarget extends Activity {
    private final android.window.OnBackInvokedCallback predictiveBack = () -> recordBack("predictive");
    private void recordBack(String route) {
        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "knob-back.txt"))) {
            out.write(("back display=" + getDisplay().getDisplayId() + " via=" + route).getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) { throw new RuntimeException(error); }
    }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;
        return super.onKeyDown(keyCode, event);
    }
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            recordBack("legacy");
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setText("Hardware OK opened this application on display " + getDisplay().getDisplayId());
        label.setTextSize(32); label.setBackgroundColor(0xff075b30); label.setTextColor(0xffffffff);
        setContentView(label);
        if (android.os.Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, predictiveBack);
    }
    @Override public void onDestroy() {
        if (android.os.Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(predictiveBack);
        super.onDestroy();
    }
}
