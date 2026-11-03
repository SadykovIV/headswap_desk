package com.example.headswap.panel;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Display;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.headswap.desk.component.api.ProfileSession;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Both panes belong to this Activity. HeadSwap owns the surrounding desktop and its input. */
public class PanelActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ProfileSession session;
    private Button stockCatalog;
    private TextView voiceStatus;
    private LinearLayout appGrid;
    private TextView launchStatus;
    private Button choose;
    private int taps;
    private boolean noteVisible = true;
    private boolean noteOnLeft;
    private LinearLayout panes;
    private LinearLayout mainPane;
    private LinearLayout notePane;
    private Button toggleNote;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            taps = state.getInt("taps");
            noteVisible = state.getBoolean("noteVisible", true);
            noteOnLeft = state.getBoolean("noteOnLeft");
        }

        ScrollView viewport = new ScrollView(this);
        panes = new LinearLayout(this);
        panes.setOrientation(LinearLayout.HORIZONTAL);
        mainPane = column();
        notePane = column();
        notePane.setBackgroundColor(Color.rgb(38, 60, 73));
        viewport.addView(panes);
        setContentView(viewport);

        TextView title = text(mainPane, R.string.heading);
        title.setTextSize(22);
        TextView metrics = new TextView(this);
        mainPane.addView(metrics);
        viewport.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            Display display = view.getDisplay();
            if (display != null) {
                String label = getString(R.string.dimensions, display.getDisplayId(),
                        getResources().getConfiguration().densityDpi, right - left, bottom - top);
                if (!label.contentEquals(metrics.getText())) metrics.setText(label);
            }
        });

        TextView count = new TextView(this);
        count.setText(getString(R.string.counter, taps));
        mainPane.addView(count);
        button(mainPane, R.string.tap_button).setOnClickListener(
                v -> count.setText(getString(R.string.counter, ++taps)));
        launchStatus = new TextView(this);
        mainPane.addView(launchStatus);
        choose = button(mainPane, R.string.app_picker);
        choose.setOnClickListener(v -> toggleAppGrid());
        appGrid = new LinearLayout(this);
        appGrid.setOrientation(LinearLayout.VERTICAL);
        appGrid.setVisibility(android.view.View.GONE);
        mainPane.addView(appGrid);
        stockCatalog = button(mainPane, R.string.stock_catalog);
        stockCatalog.setOnClickListener(v -> openStockCatalog());
        toggleNote = button(mainPane, R.string.hide_note);
        toggleNote.setOnClickListener(v -> { noteVisible = !noteVisible; renderPanes(); });
        text(notePane, R.string.note).setTextSize(18);
        text(notePane, R.string.note_text);
        voiceStatus = text(notePane, R.string.voice_scope);
        button(notePane, R.string.switch_side).setOnClickListener(v -> {
            noteOnLeft = !noteOnLeft;
            renderPanes();
        });
        readSession(getIntent());
        renderPanes();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readSession(intent);
    }

    private void readSession(Intent intent) {
        session = ProfileSession.from(intent);
        choose.setEnabled(session != null && session.canLaunchApps());
        stockCatalog.setEnabled(session != null && session.canOpenCatalog());
        launchStatus.setText(session == null ? R.string.status_missing : R.string.status_ready);
        voiceStatus.setText(session != null && (session.canUseMicrophone() || session.canUseVoiceChannel())
                ? R.string.voice_reserved : R.string.voice_scope);
    }

    private static final class App {
        final ComponentName component;
        final String label;
        App(ComponentName component, String label) {
            this.component = component;
            this.label = label;
        }
    }

    private List<App> apps() {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<App> result = new ArrayList<>();
        Set<ComponentName> seen = new HashSet<>();
        for (ResolveInfo item : getPackageManager().queryIntentActivities(query, 0)) {
            ActivityInfo info = item.activityInfo;
            if (info == null || !info.enabled || !info.exported || !info.applicationInfo.enabled
                    || info.permission != null || info.packageName.equals(getPackageName())) continue;
            ComponentName target = new ComponentName(info.packageName, info.name);
            if (!seen.add(target)) continue;
            CharSequence title;
            try {
                title = item.loadLabel(getPackageManager());
            } catch (android.content.res.Resources.NotFoundException removedResource) {
                title = null;
            }
            result.add(new App(target, title == null ? target.flattenToShortString() : title.toString()));
        }
        Collator collator = Collator.getInstance();
        result.sort((left, right) -> {
            int labels = collator.compare(left.label, right.label);
            return labels != 0 ? labels : left.component.compareTo(right.component);
        });
        return result;
    }

    private void toggleAppGrid() {
        if (appGrid.getVisibility() == android.view.View.VISIBLE) {
            appGrid.setVisibility(android.view.View.GONE);
            choose.setText(R.string.app_picker);
            return;
        }
        choose.setEnabled(false);
        worker.execute(() -> {
            List<App> installed = apps();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                choose.setEnabled(session != null && session.canLaunchApps());
                choose.setText(R.string.close_picker);
                appGrid.removeAllViews();
                text(appGrid, installed.isEmpty() ? R.string.no_apps : R.string.apps_title);
                LinearLayout row = null;
                for (int i = 0; i < installed.size(); i++) {
                    if (i % 2 == 0) {
                        row = new LinearLayout(this);
                        row.setWeightSum(2);
                        appGrid.addView(row);
                    }
                    App app = installed.get(i);
                    Button launch = new Button(this);
                    launch.setAllCaps(false);
                    launch.setText(app.label + "\n" + app.component.getPackageName());
                    launch.setOnClickListener(v -> requestLaunch(app.component));
                    row.addView(launch, new LinearLayout.LayoutParams(0, -2, 1));
                }
                appGrid.setVisibility(android.view.View.VISIBLE);
            });
        });
    }

    private void requestLaunch(ComponentName app) {
        ProfileSession active = session;
        if (active == null) {
            launchStatus.setText(R.string.status_missing);
            return;
        }
        worker.execute(() -> {
            int status = active.requestLaunch(app) ? R.string.status_accepted : R.string.status_rejected;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed() && session == active) launchStatus.setText(status);
            });
        });
    }

    private void openStockCatalog() {
        ProfileSession active = session;
        if (active == null) return;
        worker.execute(() -> {
            if (active.openCatalog()) return;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed() && session == active) launchStatus.setText(R.string.status_expired);
            });
        });
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(12 * getResources().getDisplayMetrics().density);
        column.setPadding(p, p, p, p);
        return column;
    }

    private TextView text(LinearLayout column, int label) {
        TextView text = new TextView(this);
        text.setText(label);
        column.addView(text);
        return text;
    }

    private Button button(LinearLayout column, int label) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        column.addView(button);
        return button;
    }

    private void renderPanes() {
        panes.removeAllViews();
        LinearLayout.LayoutParams noteSize = new LinearLayout.LayoutParams(0, -2, 3);
        if (noteVisible && noteOnLeft) panes.addView(notePane, noteSize);
        panes.addView(mainPane, new LinearLayout.LayoutParams(0, -2, 7));
        if (noteVisible && !noteOnLeft) panes.addView(notePane, noteSize);
        toggleNote.setText(noteVisible ? R.string.hide_note : R.string.show_note);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("taps", taps);
        state.putBoolean("noteVisible", noteVisible);
        state.putBoolean("noteOnLeft", noteOnLeft);
    }
}
