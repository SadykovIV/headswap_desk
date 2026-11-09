package com.headswap.desk.qa;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Own disposable test window; records only synthetic key codes and its display ID. */
public final class HardwareInputFixture extends Activity {
    private final StringBuilder events = new StringBuilder();
    private TextView view;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        view = new TextView(this); view.setTextSize(32); view.setTextColor(0xffffffff); view.setBackgroundColor(0xff075b30);
        setContentView(view); record("created");
    }
    @Override public boolean onKeyDown(int code, KeyEvent event) { record("down="+code); return true; }
    @Override public boolean onKeyUp(int code, KeyEvent event) { record("up="+code); return true; }
    @Override public void onBackPressed() { record("back"); }
    private void record(String value) {
        events.append(value).append('\n');
        view.setText("display="+getDisplay().getDisplayId()+"\n"+events);
        try { Files.write(new File(getFilesDir(), "hardware-"+getDisplay().getDisplayId()+".txt").toPath(),
            events.toString().getBytes(StandardCharsets.UTF_8)); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
