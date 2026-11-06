package app.aomarchy.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import app.aomarchy.i18n.T;

/**
 * Exports and imports the console's own settings (the {@code Prefs.NAME} SharedPreferences) as one JSON document.
 * Nothing outside that preferences file is touched: no rootfs, no backups, no other prefs files.
 *
 * <p>Format (pretty-printed, so a bug report can be read):
 * <pre>
 * {"app":"AOmarchy","version":"1.2.3","exported_at":"2026-11-05T01:23:45Z","values":{ ... }}
 * </pre>
 * Value types round-trip: Boolean, Int, Long, Float, String and StringSet (a JSON array of strings). On import a key
 * whose stored type does not match the JSON value is skipped (counted in the log) instead of being coerced, so a
 * damaged or foreign file cannot silently change a setting's type.
 *
 * <p>{@code app.aomarchy.Prefs} is package-private and this class lives in {@code app.aomarchy.data}, so the
 * preferences file name is repeated here; it must stay equal to {@code Prefs.NAME}.
 */
public final class SettingsIO {
    /** Must match {@code app.aomarchy.Prefs.NAME}. */
    private static final String PREFS = "aomarchy_settings";
    private static final String APP = "AOmarchy";
    private static final String TAG = "SettingsIO";
    /** Guard against importing a huge file by accident: a settings document is a few KB. */
    private static final int MAX_JSON_CHARS = 1 << 20;
    private static final String JSON_MIME = "application/json";

    private SettingsIO() {
    }

    /** The whole preferences file as a JSON document. Never null; throws {@link IllegalStateException} on failure. */
    public static String exportJson(Context ctx) {
        SharedPreferences prefs = prefs(ctx);
        JSONObject values = new JSONObject();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            try {
                if (value instanceof Boolean) {
                    values.put(key, (Boolean) value);
                } else if (value instanceof Integer) {
                    values.put(key, (Integer) value);
                } else if (value instanceof Long) {
                    values.put(key, (Long) value);
                } else if (value instanceof Float) {
                    values.put(key, (Float) value);
                } else if (value instanceof String) {
                    values.put(key, (String) value);
                } else if (value instanceof Set) {
                    JSONArray array = new JSONArray();
                    for (Object item : (Set<?>) value)
                        array.put(String.valueOf(item));
                    values.put(key, array);
                } else if (value != null) {
                    // Not produced by Prefs today; exporting a string form is better than dropping the key silently.
                    android.util.Log.w(TAG, "exporting unsupported " + key + " as text: "
                            + value.getClass().getName());
                    values.put(key, String.valueOf(value));
                }
            } catch (JSONException e) {
                throw new IllegalStateException(
                        tr("settings_io.export_failed", "无法序列化设置：%s", e.getMessage()), e);
            }
        }
        try {
            JSONObject root = new JSONObject();
            root.put("app", APP);
            root.put("version", versionName(ctx));
            root.put("exported_at", isoNow());
            root.put("values", values);
            return root.toString(2);
        } catch (JSONException e) {
            throw new IllegalStateException(
                    tr("settings_io.export_failed", "无法序列化设置：%s", e.getMessage()), e);
        }
    }

    /**
     * Parses a document produced by {@link #exportJson(Context)} and writes its values into the preferences file.
     * Returns the number of keys written; keys with a type that does not match the stored value are skipped.
     *
     * @throws IllegalArgumentException when the text is not a valid AOmarchy settings document
     * @throws IllegalStateException    when the preferences could not be saved
     */
    public static int importJson(Context ctx, String json) {
        String text = json == null ? "" : json;
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF')
            text = text.substring(1); // tolerate a UTF-8 BOM from some editors
        if (text.trim().isEmpty())
            throw new IllegalArgumentException(tr("settings_io.bad_file", "不是 AOmarchy 的配置文件"));
        if (text.length() > MAX_JSON_CHARS)
            throw new IllegalArgumentException(tr("settings_io.too_large", "文件过大（超过 1 MB）"));
        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (JSONException e) {
            throw new IllegalArgumentException(tr("settings_io.bad_json", "JSON 解析失败：%s", e.getMessage()), e);
        }
        if (!APP.equals(root.optString("app")))
            throw new IllegalArgumentException(tr("settings_io.bad_file", "不是 AOmarchy 的配置文件"));
        JSONObject values = root.optJSONObject("values");
        if (values == null)
            throw new IllegalArgumentException(tr("settings_io.no_values", "配置文件里没有 values 对象"));

        SharedPreferences prefs = prefs(ctx);
        Map<String, ?> current = prefs.getAll();
        SharedPreferences.Editor editor = prefs.edit();
        int written = 0;
        int skipped = 0;
        for (Iterator<String> keys = values.keys(); keys.hasNext(); ) {
            String key = keys.next();
            Object raw = values.opt(key);
            if (raw == null || raw == JSONObject.NULL)
                continue;
            if (write(editor, key, raw, current.get(key)))
                written++;
            else
                skipped++;
        }
        try {
            if (!editor.commit())
                throw new IllegalStateException(tr("settings_io.save_failed", "保存设置失败"));
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException(tr("settings_io.save_failed", "保存设置失败"), e);
        }
        if (skipped > 0)
            android.util.Log.w(TAG, skipped + " setting(s) skipped: the stored type does not match the file");
        return written;
    }

    /** {@code AOmarchy-settings-YYYYMMDD-HHmm.json}, for the document picker's suggested name. */
    public static String suggestedName() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US);
        return "AOmarchy-settings-" + format.format(new Date()) + ".json";
    }

    /** True when the value was written; false for a type mismatch (the caller counts it). */
    private static boolean write(SharedPreferences.Editor editor, String key, Object raw, Object existing) {
        if (existing instanceof Boolean)
            return raw instanceof Boolean && putBoolean(editor, key, (Boolean) raw);
        if (existing instanceof Integer) {
            if (raw instanceof Integer)
                return putInt(editor, key, (Integer) raw);
            if (raw instanceof Long) {
                long v = (Long) raw;
                if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE)
                    return putInt(editor, key, (int) v);
            }
            return false;
        }
        if (existing instanceof Long) {
            if (raw instanceof Long)
                return putLong(editor, key, (Long) raw);
            if (raw instanceof Integer)
                return putLong(editor, key, ((Integer) raw).longValue());
            return false;
        }
        if (existing instanceof Float)
            return raw instanceof Number && putFloat(editor, key, ((Number) raw).floatValue());
        if (existing instanceof String)
            return raw instanceof String && putString(editor, key, (String) raw);
        if (existing instanceof Set)
            return writeStringSet(editor, key, raw);

        // No stored value (first install, or a key added by a newer version): infer the type from the JSON value.
        if (raw instanceof Boolean)
            return putBoolean(editor, key, (Boolean) raw);
        if (raw instanceof Integer)
            return putInt(editor, key, (Integer) raw);
        if (raw instanceof Long)
            return putLong(editor, key, (Long) raw);
        if (raw instanceof Double)
            return putFloat(editor, key, ((Double) raw).floatValue()); // Prefs' only fractional type is Float
        if (raw instanceof String)
            return putString(editor, key, (String) raw);
        if (raw instanceof JSONArray)
            return writeStringSet(editor, key, raw);
        return false;
    }

    private static boolean writeStringSet(SharedPreferences.Editor editor, String key, Object raw) {
        if (!(raw instanceof JSONArray))
            return false;
        JSONArray array = (JSONArray) raw;
        Set<String> items = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (!(item instanceof String))
                return false;
            items.add((String) item);
        }
        editor.putStringSet(key, items);
        return true;
    }

    private static boolean putBoolean(SharedPreferences.Editor editor, String key, boolean value) {
        editor.putBoolean(key, value);
        return true;
    }

    private static boolean putInt(SharedPreferences.Editor editor, String key, int value) {
        editor.putInt(key, value);
        return true;
    }

    private static boolean putLong(SharedPreferences.Editor editor, String key, long value) {
        editor.putLong(key, value);
        return true;
    }

    private static boolean putFloat(SharedPreferences.Editor editor, String key, float value) {
        editor.putFloat(key, value);
        return true;
    }

    private static boolean putString(SharedPreferences.Editor editor, String key, String value) {
        editor.putString(key, value);
        return true;
    }

    /** The MIME type the settings document uses; the export/import buttons pass it to the document picker. */
    public static String mimeType() {
        return JSON_MIME;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String versionName(Context ctx) {
        try {
            String name = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            return name == null ? "" : name;
        } catch (Exception e) {
            return "";
        }
    }

    private static String isoNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    /**
     * T key that T.java may not have yet: the Lead adds new keys centrally, so until then the Chinese fallback keeps
     * the UI readable and works in English too once the key lands (no edit needed here).
     */
    private static String tr(String key, String fallback, Object... args) {
        String text = T.t(key, args);
        if (!text.equals(key))
            return text;
        text = fallback;
        for (Object arg : args) {
            int at = text.indexOf("%s");
            if (at < 0)
                break;
            text = text.substring(0, at) + arg + text.substring(at + 2);
        }
        return text;
    }
}
