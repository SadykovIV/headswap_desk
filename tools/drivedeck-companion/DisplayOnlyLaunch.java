package ru.drivedeck.headswap;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.content.res.ColorStateList;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.headswap.desk.shared.DesktopRenderPolicy;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Iterator;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

/** Routes every activity start to the currently hosting secondary display. */
public final class DisplayOnlyLaunch {
    private static final String BACK = "headswap_fullscreen_back";
    private static final String HOME = "headswap_fullscreen_home";
    private static final ComponentName FULLSCREEN_HOST = new ComponentName("com.headswap.desk", "com.headswap.desk.desktop.DriveDeckFullscreenActivity");
    private DisplayOnlyLaunch() {}
    public static Activity activity(Context context) {
        for (int i = 0; i < 12 && context != null; i++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) return null;
            context = base;
        }
        return null;
    }
    public static int displayId(Context context) {
        try {
            Activity activity = activity(context);
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return -1;
            Display display = activity.getDisplay();
            return display == null || !display.isValid() || display.getDisplayId() <= 0 ? -1 : display.getDisplayId();
        } catch (RuntimeException e) { return -1; }
    }
    public static boolean allowed(Context context) { return displayId(context) > 0; }
    private static Bundle options(Context context, Intent intent) {
        int id = displayId(context);
        if (id <= 0) throw new SecurityException("DriveDeck HeadSwap requires an active secondary display");
        if (intent == null) throw new IllegalArgumentException("Missing intent");
        String action = intent.getAction();
        if ((action != null && (action.startsWith("android.settings.") || action.startsWith("android.app.role.") || action.equals(Intent.ACTION_INSTALL_PACKAGE)))
                || "application/vnd.android.package-archive".equals(intent.getType())
                || intent.hasCategory(Intent.CATEGORY_HOME)) {
            throw new SecurityException("Phone settings, role selection and installation are disabled in this companion");
        }
        return ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle();
    }
    public static boolean launch(Context context, String packageName) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent == null) return false;
            open(context, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) { failure(context, e); return false; }
    }
    public static void start(Context context, Intent intent) {
        try { open(context, intent); }
        catch (RuntimeException e) { failure(context, e); }
    }
    private static void open(Context context, Intent intent) {
        Bundle currentDisplay = options(context, intent);
        Activity host = activity(context);
        int parent = displayId(context);
        ComponentName target = intent.getComponent();
        SharedPreferences prefs = context.getSharedPreferences("drive_deck", Context.MODE_PRIVATE);
        boolean back = prefs.getBoolean(BACK, false);
        boolean home = prefs.getBoolean(HOME, false);
        if (host != null && target != null && (back || home || DesktopRenderPolicy.isMap(target.getPackageName()))
                && isLauncherRequest(context, intent, target) && hasFullscreenHost(context)) {
            Intent original = new Intent(intent);
            boolean scheduled = EmbeddedDisplayClient.checkFullscreenParent(host, parent, admitted -> {
                if (admitted) {
                    Intent routed = new Intent().setComponent(FULLSCREEN_HOST)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra("component", target.flattenToString()).putExtra("back", back).putExtra("home", home);
                    try { host.startActivity(routed, options(host, routed)); return; }
                    catch (RuntimeException unavailable) { Log.w("DriveDeckHeadSwap", "Fullscreen controls unavailable", unavailable); }
                }
                // The original launch still uses the same live secondary display.
                try { host.startActivity(original, options(host, original)); }
                catch (RuntimeException e) { failure(context, e); }
            });
            if (scheduled) return;
        }
        context.startActivity(intent, currentDisplay);
    }
    private static boolean isLauncherRequest(Context context, Intent intent, ComponentName target) {
        if (!Intent.ACTION_MAIN.equals(intent.getAction()) || !intent.hasCategory(Intent.CATEGORY_LAUNCHER)
                || intent.getCategories().size() != 1 || intent.getSelector() != null || intent.getData() != null
                || intent.getType() != null || intent.getClipData() != null || intent.getExtras() != null
                || (Build.VERSION.SDK_INT >= 29 && intent.getIdentifier() != null)
                || (intent.getFlags() & ~(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) != 0
                || (intent.getPackage() != null && !target.getPackageName().equals(intent.getPackage()))
                || target.getPackageName().equals(context.getPackageName()) || target.getPackageName().equals(FULLSCREEN_HOST.getPackageName())) return false;
        Intent launchers = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(target.getPackageName());
        for (ResolveInfo item : context.getPackageManager().queryIntentActivities(launchers, 0)) {
            ActivityInfo info = item.activityInfo;
            if (info != null && info.exported && info.enabled && info.applicationInfo.enabled && target.getClassName().equals(info.name)
                    && target.getPackageName().equals(info.packageName) && (info.permission == null
                    || context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)) return true;
        }
        return false;
    }
    private static boolean hasFullscreenHost(Context context) {
        PackageManager pm = context.getPackageManager();
        try {
            ActivityInfo info = pm.getActivityInfo(FULLSCREEN_HOST, 0);
            return info.exported && info.enabled && "com.headswap.desk.permission.EMBED_DISPLAY".equals(info.permission)
                    && pm.checkSignatures(context.getPackageName(), FULLSCREEN_HOST.getPackageName()) == PackageManager.SIGNATURE_MATCH;
        } catch (PackageManager.NameNotFoundException missing) { return false; }
    }
    public static void addFullscreenControls(LinearLayout panel, TextView example) {
        addOption(panel, example, BACK, "Назад в полноэкранных приложениях");
        addOption(panel, example, HOME, "Домой в полноэкранных приложениях");
    }
    private static void addOption(LinearLayout panel, TextView example, String key, String label) {
        Context context = panel.getContext();
        SharedPreferences prefs = context.getSharedPreferences("drive_deck", Context.MODE_PRIVATE);
        CheckBox option = new CheckBox(context);
        option.setText(label);
        option.setTextColor(example.getTextColors());
        option.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xff36c275, example.getCurrentTextColor()}));
        option.setMinHeight(Math.round(48 * context.getResources().getDisplayMetrics().density));
        option.setChecked(prefs.getBoolean(key, false));
        option.setOnCheckedChangeListener((button, enabled) -> prefs.edit().putBoolean(key, enabled).apply());
        panel.addView(option, new LinearLayout.LayoutParams(-1, -2));
    }
    public static void start(Context context, Intent intent, Bundle ignoredOptions) { start(context, intent); }
    public static void startForResult(Activity activity, Intent intent, int requestCode) {
        try { activity.startActivityForResult(intent, requestCode, options(activity, intent)); }
        catch (RuntimeException e) { failure(activity, e); }
    }
    /** Only exact old defaults are removed; every customized layout and other preference survives. */
    public static void migrateDefaultLayout(Context context, String originalDefault) {
        try {
            JSONObject oldLandscape = new JSONObject(originalDefault);
            JSONObject oldPortrait = new JSONObject(originalDefault);
            oldPortrait.put("gridRows", 72);
            JSONArray portraitWidgets = oldPortrait.getJSONArray("widgets");
            for (int i = 0; i < portraitWidgets.length(); i++) {
                JSONObject widget = portraitWidgets.getJSONObject(i);
                if ("WINDOW".equals(widget.optString("kind")) && !widget.optBoolean("hidden")) {
                    widget.put("rows", 16);
                }
            }
            SharedPreferences prefs = context.getSharedPreferences("drive_deck", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = null;
            String[] keys = {"dashboard_layout_v1", "dashboard_layout_portrait_v1"};
            JSONObject[] originals = {oldLandscape, oldPortrait};
            for (int i = 0; i < keys.length; i++) {
                Object value = prefs.getAll().get(keys[i]);
                if (value instanceof String && !((String) value).isEmpty()
                        && sameJson(new JSONObject((String) value), originals[i])) {
                    if (editor == null) editor = prefs.edit();
                    editor.remove(keys[i]);
                }
            }
            if (editor != null) {
                editor.apply();
                Log.i("DriveDeckHeadSwap", "Migrated exact old default dashboard layout");
            }
        } catch (JSONException | RuntimeException error) {
            Log.w("DriveDeckHeadSwap", "Kept existing layout: default migration did not apply", error);
        }
    }
    private static boolean sameJson(Object left, Object right) throws JSONException {
        if (left instanceof JSONObject && right instanceof JSONObject) {
            JSONObject a = (JSONObject) left, b = (JSONObject) right;
            if (a.length() != b.length()) return false;
            Iterator<String> keys = a.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!b.has(key) || !sameJson(a.get(key), b.get(key))) return false;
            }
            return true;
        }
        if (left instanceof JSONArray && right instanceof JSONArray) {
            JSONArray a = (JSONArray) left, b = (JSONArray) right;
            if (a.length() != b.length()) return false;
            for (int i = 0; i < a.length(); i++) if (!sameJson(a.get(i), b.get(i))) return false;
            return true;
        }
        return left == null ? right == null : left.equals(right);
    }
    private static void failure(Context context, RuntimeException error) {
        Log.w("DriveDeckHeadSwap", "Secondary-display operation rejected", error);
        Activity activity = activity(context);
        if (activity != null && displayId(activity) > 0) {
            Toast.makeText(activity, "Действие недоступно в режиме HeadSwap", Toast.LENGTH_SHORT).show();
        }
    }
}
