package ru.drivedeck.headswap;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
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
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options(context, intent));
            return true;
        } catch (RuntimeException e) { failure(context, e); return false; }
    }
    public static void start(Context context, Intent intent) {
        try { context.startActivity(intent, options(context, intent)); }
        catch (RuntimeException e) { failure(context, e); }
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
