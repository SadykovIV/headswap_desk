package com.example.headswap.displayinfo;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class DisplayInfoActivity extends Activity {
    private static final String TAPS = "tap_count";
    private int taps;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        taps = state == null ? 0 : state.getInt(TAPS);
        ScrollView viewport = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        viewport.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            content.setPadding(padding + bars.left, padding + bars.top,
                    padding + bars.right, padding + bars.bottom);
            return insets;
        });
        viewport.addView(content);
        setContentView(viewport);

        TextView heading = new TextView(this);
        heading.setText(R.string.heading);
        heading.setTextSize(24);
        content.addView(heading);
        TextView details = new TextView(this);
        content.addView(details);
        viewport.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> updateDetails(view, details));

        TextView counter = new TextView(this);
        counter.setText(getString(R.string.tap_count, taps));
        content.addView(counter);
        Button tap = new Button(this);
        tap.setText(R.string.count_button);
        tap.setAllCaps(false);
        tap.setOnClickListener(view -> counter.setText(getString(R.string.tap_count, ++taps)));
        content.addView(tap);
    }

    private void updateDetails(View view, TextView details) {
        Display display = view.getDisplay();
        if (display == null) return;
        int id = display.getDisplayId();
        String name = getString(id == Display.DEFAULT_DISPLAY
                ? R.string.phone_display : R.string.external_display);
        String value = getString(R.string.display_details, name, id,
                getResources().getConfiguration().densityDpi, view.getWidth(), view.getHeight());
        if (!value.contentEquals(details.getText())) details.setText(value);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt(TAPS, taps);
    }
}
