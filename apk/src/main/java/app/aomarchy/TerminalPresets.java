package app.aomarchy;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import app.aomarchy.i18n.T;

/**
 * The terminal's quick-command presets: read/write of the {@code terminal_presets} preference plus the built-in
 * defaults.
 *
 * <p>Stored as a JSON array in {@code Prefs.NAME}:
 * <pre>
 * [{"name":"列出文件","cmd":"ls -la"}, ...]
 * </pre>
 * A missing or broken value falls back to {@link #defaults()}; an empty array is a valid "user deleted everything"
 * state and stays empty. Names are localized for built-in entries only — once the user edits the list, the names are
 * saved verbatim, exactly what the user typed.
 */
public final class TerminalPresets {
    /** Preferences key holding the JSON array. */
    public static final String KEY = "terminal_presets";
    private static final String TAG = "TerminalPresets";

    /** One entry: a label shown on the chip and the shell command it sends (without the trailing Enter). */
    public static final class Preset {
        public final String name;
        public final String cmd;

        public Preset(String name, String cmd) {
            this.name = name;
            this.cmd = cmd;
        }
    }

    private TerminalPresets() {
    }

    /** The user's presets, or the built-in defaults when nothing is stored / the JSON cannot be parsed. */
    public static List<Preset> load(Context ctx) {
        SharedPreferences prefs = prefs(ctx);
        String json = prefs.getString(KEY, null);
        if (json == null || json.trim().isEmpty())
            return defaults();
        try {
            JSONArray array = new JSONArray(json);
            List<Preset> out = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.optJSONObject(i);
                if (entry == null)
                    continue;
                String name = entry.optString("name", "").trim();
                String cmd = entry.optString("cmd", "").trim();
                if (!name.isEmpty() && !cmd.isEmpty())
                    out.add(new Preset(name, cmd));
            }
            if (out.isEmpty() && array.length() > 0) {
                android.util.Log.w(TAG, KEY + " had no usable entry, falling back to the built-in presets");
                return defaults(); // an empty array means "user removed everything" and stays empty; junk does not
            }
            return out;
        } catch (JSONException e) {
            android.util.Log.w(TAG, "broken " + KEY + " JSON, falling back to the built-in presets", e);
            return defaults();
        }
    }

    /** Writes the list with {@code apply()}; an empty list is stored as an empty array. */
    public static void save(Context ctx, List<Preset> presets) {
        JSONArray array = new JSONArray();
        if (presets != null) {
            for (Preset preset : presets) {
                if (preset == null || preset.name == null || preset.cmd == null)
                    continue;
                try {
                    JSONObject entry = new JSONObject();
                    entry.put("name", preset.name);
                    entry.put("cmd", preset.cmd);
                    array.put(entry);
                } catch (JSONException e) {
                    android.util.Log.w(TAG, "could not write preset " + preset.name, e);
                }
            }
        }
        prefs(ctx).edit().putString(KEY, array.toString()).apply();
    }

    /** The 11 built-in commands: Arch Linux / Hyprland terms, names localized through {@link #text}. */
    public static List<Preset> defaults() {
        List<Preset> out = new ArrayList<>();
        out.add(new Preset(text("preset.list", "列出文件"), "ls -la"));
        out.add(new Preset(text("preset.disk", "磁盘占用"), "df -h"));
        out.add(new Preset(text("preset.memory", "内存占用"), "free -h"));
        out.add(new Preset(text("preset.processes", "查看进程"), "top"));
        out.add(new Preset(text("preset.update", "系统更新"), "sudo pacman -Syu"));
        out.add(new Preset(text("preset.packages", "安装包"), "pacman -Qe"));
        out.add(new Preset(text("preset.services", "服务状态"), "systemctl --user status"));
        out.add(new Preset(text("preset.network", "网络"), "ip a"));
        out.add(new Preset(text("preset.logs", "最近日志"), "journalctl --user -n 50 --no-pager"));
        out.add(new Preset(text("preset.clear", "清屏"), "clear"));
        out.add(new Preset(text("preset.exit", "退出终端"), "exit"));
        return out;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
    }

    /**
     * A T key that T.java may not have yet: the Lead adds new keys centrally, so until then the Chinese fallback keeps
     * the UI readable and the key starts working as soon as it lands (no edit needed here).
     */
    static String text(String key, String fallback, Object... args) {
        String value = T.t(key, args);
        if (!value.equals(key))
            return value;
        value = fallback;
        for (Object arg : args) {
            int at = value.indexOf("%s");
            if (at < 0)
                break;
            value = value.substring(0, at) + arg + value.substring(at + 2);
        }
        return value;
    }
}
