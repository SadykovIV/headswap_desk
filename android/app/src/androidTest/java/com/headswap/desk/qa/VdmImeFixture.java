package com.headswap.desk.qa;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Public test-UID text field, never packaged in the application. Records only synthetic QA input. */
public final class VdmImeFixture extends Activity {
    private EditText field;
    private boolean ime;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setTitle("HeadSwap QA IME");
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(8, 8, 8, 8); content.setBackgroundColor(0xff164934);
        field = new EditText(this); field.setSingleLine(true); field.setTextColor(0xffffffff);
        field.setHintTextColor(0xffeeeeee); field.setHint("Synthetic QA input");
        content.addView(field, new LinearLayout.LayoutParams(-1, 80));
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            ime = insets.isVisible(WindowInsets.Type.ime()); record(); return insets;
        });
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) { record(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        setContentView(content);
        content.post(() -> { content.requestApplyInsets(); record(); });
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus); if (field != null) record();
    }
    private void record() {
        if (field == null || getDisplay() == null) return;
        int[] point = new int[2]; field.getLocationOnScreen(point);
        String value = "display=" + getDisplay().getDisplayId() + " ime=" + ime +
            " text=" + field.getText() + " x=" + (point[0]+Math.max(1,field.getWidth()/2)) +
            " y=" + (point[1]+Math.max(1,field.getHeight()/2)) + " width=" + field.getWidth();
        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "vdm-ime.txt"))) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
