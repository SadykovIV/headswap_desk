package com.example.headswap.panel;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class StarterScreen {
    private final Activity activity;
    final LinearLayout root, dock;
    private final LinearLayout workspace, media, apps;
    final Button home, back, catalog, music, side, playback;
    final TextView status, track;
    private final ListView list;

    StarterScreen(Activity activity, Consumer<ComponentName> choose) {
        this.activity = activity;
        root = column(0xff101c24);
        workspace = new LinearLayout(activity);
        root.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));
        media = column(0xff173c3b);
        media.setGravity(Gravity.CENTER_VERTICAL);
        media.setPadding(dp(12), dp(8), dp(12), dp(8));
        media.addView(label("МУЗЫКА", 14, true));
        track = label("", 17, false);
        media.addView(track);
        playback = button("Пуск / пауза");
        media.addView(playback);
        apps = column(0xff102532);
        apps.setPadding(dp(12), dp(7), dp(12), dp(5));
        apps.addView(label("Приложения", 21, true));
        status = label("Выберите приложение для окна рядом с панелью.", 14, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        apps.addView(status);
        list = new ListView(activity);
        apps.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        list.setOnItemClickListener((parent, row, index, id) -> {
            Entry entry = (Entry) parent.getItemAtPosition(index);
            choose.accept(entry.component);
        });

        dock = new LinearLayout(activity);
        dock.setBackgroundColor(0xff1d303e);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(dock, new LinearLayout.LayoutParams(-1, dp(46)));
        root.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            int height = Math.min(dp(46), (b - t) / 5);
            if (height > 0 && dock.getLayoutParams().height != height)
                dock.setLayoutParams(new LinearLayout.LayoutParams(-1, height));
        });
        home = control("Домой"); back = control("Назад"); catalog = control("Каталог ГУ");
        music = control("Музыка"); side = control("Сторона");
    }

    private LinearLayout column(int color) {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackgroundColor(color);
        return view;
    }

    private TextView label(String text, int size, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(0xfff0f6f7);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String text) {
        Button view = new Button(activity);
        view.setAllCaps(false);
        view.setSingleLine();
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setTextSize(14);
        view.setText(text);
        view.setPadding(dp(3), 0, dp(3), 0);
        return view;
    }

    private Button control(String name) {
        Button button = button(name);
        dock.addView(button, new LinearLayout.LayoutParams(0, -1, 1));
        return button;
    }

    void arrange(boolean showMusic, boolean musicOnRight) {
        workspace.removeAllViews();
        if (showMusic && !musicOnRight) addColumn(media, 3);
        addColumn(apps, showMusic ? 7 : 10);
        if (showMusic && musicOnRight) addColumn(media, 3);
        music.setSelected(showMusic);
        side.setEnabled(showMusic);
    }

    private void addColumn(View view, int weight) {
        workspace.addView(view, new LinearLayout.LayoutParams(0, -1, weight));
    }

    void showApps(List<Entry> entries) {
        list.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, entries));
    }

    static final class Entry {
        final ComponentName component;
        final String title;
        Entry(ComponentName component, String title) { this.component = component; this.title = title; }
        @Override public String toString() { return title + "  ·  " + component.getPackageName(); }
    }

    static List<Entry> loadApps(Activity activity) {
        PackageManager pm = activity.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> matches = pm.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0));
        matches.sort(new ResolveInfo.DisplayNameComparator(pm));
        List<Entry> entries = new ArrayList<>();
        for (ResolveInfo match : matches) {
            var info = match.activityInfo;
            if (info == null || !info.exported || !info.enabled || !info.applicationInfo.enabled ||
                    !TextUtils.isEmpty(info.permission) || info.packageName.equals(activity.getPackageName()) ||
                    info.packageName.equals("com.headswap.desk") || info.packageName.equals("ru.drivedeck.headswap")) continue;
            entries.add(new Entry(new ComponentName(info.packageName, info.name), match.loadLabel(pm).toString()));
        }
        return entries;
    }

    private int dp(int size) { return Math.round(size * activity.getResources().getDisplayMetrics().density); }
}
