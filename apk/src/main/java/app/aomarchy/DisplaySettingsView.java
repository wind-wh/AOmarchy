package app.aomarchy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

import app.aomarchy.i18n.T;
import app.aomarchy.ui.Ui;

/**
 * 设置 → 显示: the native replacement for the old "显示（分辨率 / 方向 / 刘海）" category page — screen orientation, PiP
 * behaviour, display cutout handling, plus links to the live display controls (size, density, refresh rate) that
 * already live on the Controls page.
 *
 * Contract for the settings host (SettingsPage): {@link #build(Activity)} returns the page body only — a vertical
 * stack of {@code Ui.card(...)} cards, no ScrollView, no title bar. Every change is saved immediately to the
 * {@code Prefs.NAME} SharedPreferences; the host re-reads the prefs when the sheet closes.
 *
 * Orientation and cutout are window-level settings: MainActivity replays them in onResume (applyScreenOrientation /
 * DisplayCutoutMode.get), so they take effect when the desktop is next shown. Resolution, density and refresh rate are
 * live switches owned by the Controls page and are only linked from here, not re-implemented.
 */
public final class DisplaySettingsView {
    // String: matched against MainActivity.SCREEN_ORIENTATIONS and mapped to ActivityInfo there.
    private static final String KEY_SCREEN_ORIENTATION = "screen_orientation";
    private static final String KEY_PIP_MODE = "pip_mode";

    private static final String[] ORIENTATION_VALUES = {
            "auto", "portrait", "landscape", "reverse portrait", "reverse landscape"
    };
    /** Resolved per build: the language is decided by the activity, never by a class-load-time static. */
    private static String[] orientationLabels() {
        return new String[] {
                T.t("orient.auto"), T.t("orient.portrait"), T.t("orient.landscape"),
                T.t("orient.reverse_portrait"), T.t("orient.reverse_landscape")
        };
    }

    // Parallel to DisplayCutoutMode.VALUES: hide_all / rounded_corners_only / cutout_only / none.
    private static String[] cutoutLabels() {
        return new String[] {
                T.t("cutout.all"), T.t("cutout.rounded"), T.t("cutout.cutout"), T.t("cutout.none")
        };
    }

    private DisplaySettingsView() {
    }

    /** Builds the page. Never returns null. */
    public static View build(Activity host) {
        final SharedPreferences prefs = host.getSharedPreferences(Prefs.NAME, Activity.MODE_PRIVATE);
        LinearLayout body = Ui.column(host);

        // ===== 屏幕方向 =====
        LinearLayout orientation = Ui.card(host, T.t("card.orientation"));
        orientation.addView(choiceRow(host, T.t("label.window_orientation"),
                T.t("display.window_orientation_caption"),
                orientationLabels(),
                indexOf(ORIENTATION_VALUES,
                        prefs.getString(KEY_SCREEN_ORIENTATION, ORIENTATION_VALUES[0])),
                which -> prefs.edit()
                        .putString(KEY_SCREEN_ORIENTATION, ORIENTATION_VALUES[which]).apply()));
        body.addView(orientation);

        // ===== 刘海与圆角 =====
        LinearLayout cutout = Ui.card(host, T.t("card.cutout"));
        cutout.addView(choiceRow(host, T.t("label.display_area"),
                T.t("display.display_area_caption"),
                cutoutLabels(),
                indexOf(DisplayCutoutMode.VALUES, DisplayCutoutMode.get(prefs)),
                which -> prefs.edit()
                        .putString(DisplayCutoutMode.KEY, DisplayCutoutMode.VALUES[which]).apply()));
        body.addView(cutout);

        // ===== 画中画：桌面不可用（SurfaceView 在 PiP 小窗里不出画面），保留说明并清掉旧开关 =====
        LinearLayout pip = Ui.card(host, T.t("card.pip"));
        pip.addView(Ui.text(host, T.t("display.pip_note"), 12, Ui.DIM, false));
        if (prefs.getBoolean(KEY_PIP_MODE, false))
            prefs.edit().putBoolean(KEY_PIP_MODE, false).apply(); // Home must not drop the session into a black PiP
        body.addView(pip);

        // ===== 显示参数：只读现状 + 跳转，不重复实现 =====
        LinearLayout live = Ui.card(host, T.t("card.display_params"));
        android.util.DisplayMetrics dm = host.getResources().getDisplayMetrics();
        live.addView(Ui.kv2(host, T.t("label.resolution"),
                Ui.mono(host, dm.widthPixels + " × " + dm.heightPixels, 14, Ui.TEXT),
                T.t("display.resolution_caption")));
        live.addView(Ui.kv2(host, T.t("label.density"),
                Ui.mono(host, dm.densityDpi + " dpi", 14, Ui.TEXT),
                T.t("display.density_caption")));
        live.addView(Ui.kv2(host, T.t("label.refresh_rate"),
                Ui.mono(host, refreshRate(host), 14, Ui.TEXT),
                T.t("display.refresh_rate_caption")));
        live.addView(Ui.gap(host, 6));
        live.addView(actionRow(host, T.t("display.adjust"),
                T.t("display.adjust_caption"), T.t("display.adjust_value"),
                () -> host.startActivity(new Intent(host, ControlsActivity.class))));
        body.addView(live);

        LinearLayout note = Ui.card(host, T.t("card.notes"));
        note.addView(Ui.text(host, T.t("display.notes_text"), 11, Ui.FAINT, false));
        body.addView(note);

        return body;
    }

    /** The panel's current refresh rate, or a dash when the display cannot report one. */
    private static String refreshRate(Activity host) {
        try {
            float hz = host.getWindowManager().getDefaultDisplay().getRefreshRate();
            if (hz > 0)
                return String.format(Locale.US, "%.0f Hz", hz);
        } catch (RuntimeException ignored) {
            // some panels report nothing usable: leave the dash
        }
        return "—";
    }

    /** What a {@link #choiceRow} does with the picked index: the row updates its own value, the caller saves. */
    private interface ChoiceSink {
        void onPick(int index);
    }

    /**
     * The {@code Ui.actionRow} shape, but with a value that follows the picked item: {@code Ui.actionRow} keeps its
     * value TextView private, so a picker that shows the current choice needs its own row.
     *
     * <p>TalkBack: the whole row is one stop ("label，caption，current value") and at least 48dp tall.
     */
    private static LinearLayout choiceRow(Activity host, String label, String caption, String[] labels,
            int checked, ChoiceSink onPick) {
        LinearLayout row = Ui.row(host);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setPadding(0, Ui.dp(host, 7), 0, Ui.dp(host, 7));

        LinearLayout texts = Ui.column(host);
        texts.addView(Ui.text(host, label, 14, Ui.TEXT, false));
        if (caption != null && !caption.isEmpty())
            texts.addView(Ui.text(host, caption, 11, Ui.FAINT, false));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = Ui.row(host);
        final TextView value = Ui.text(host, labels[checked], 13, Ui.DIM, false);
        right.addView(value);
        right.addView(Ui.space(host, 6));
        right.addView(Ui.text(host, "›", 15, Ui.FAINT, false));
        row.addView(right);

        row.setMinimumHeight(Ui.dp(host, 48));
        texts.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        right.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.setContentDescription(describe(label, caption, labels[checked]));

        final int[] selected = {checked};
        row.setOnClickListener(v -> new AlertDialog.Builder(host)
                .setTitle(label)
                .setSingleChoiceItems(labels, selected[0], (dialog, which) -> {
                    selected[0] = which;
                    value.setText(labels[which]);
                    onPick.onPick(which);
                    row.setContentDescription(describe(label, caption, labels[which]));
                    dialog.dismiss();
                })
                .setNegativeButton(T.t("btn.cancel"), null)
                .show());
        row.setClickable(true);
        return row;
    }

    /** A {@link Ui#actionRow} as one TalkBack stop, with the 48dp minimum touch height. */
    private static LinearLayout actionRow(Activity host, String label, String caption, String value,
            Runnable onClick) {
        LinearLayout r = Ui.actionRow(host, label, caption, value, onClick);
        r.setMinimumHeight(Ui.dp(host, 48));
        for (int i = 0; i < r.getChildCount(); i++)
            r.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        r.setContentDescription(describe(label, caption, value));
        return r;
    }

    /** label + optional caption + current value, joined for one TalkBack announcement. */
    private static String describe(String label, String caption, String value) {
        String base = caption == null || caption.isEmpty() ? label : label + "，" + caption;
        return value == null || value.isEmpty() ? base : base + "，" + value;
    }

    private static String stateLabel(boolean on) {
        return T.t(on ? "state.on" : "state.off");
    }

    private static int indexOf(String[] values, String target) {
        for (int i = 0; i < values.length; i++)
            if (values[i].equals(target))
                return i;
        return 0;
    }

    /**
     * The {@code Ui.toggleRow} shape with a localized On/Off pill: {@code Ui.toggleRow} hardcodes the Chinese
     * 开启/关闭 labels and Ui.java is outside this task's write scope.
     *
     * <p>TalkBack: the row is one stop announcing label, caption and the current state; 48dp minimum height.
     */
    private static LinearLayout toggleRow(Activity host, String label, String caption, boolean value,
            Ui.OnToggle onChange) {
        LinearLayout r = Ui.row(host);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        r.setPadding(0, Ui.dp(host, 7), 0, Ui.dp(host, 7));

        LinearLayout texts = Ui.column(host);
        texts.addView(Ui.text(host, label, 14, Ui.TEXT, false));
        if (caption != null && !caption.isEmpty())
            texts.addView(Ui.text(host, caption, 11, Ui.FAINT, false));
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final boolean[] on = {value};
        TextView pill = Ui.text(host, stateLabel(on[0]), 13, on[0] ? Ui.ACCENT : Ui.DIM, true);
        pill.setPadding(Ui.dp(host, 10), Ui.dp(host, 5), Ui.dp(host, 10), Ui.dp(host, 5));
        pill.setBackground(Ui.surface(host, 30));
        r.addView(pill);

        r.setMinimumHeight(Ui.dp(host, 48));
        texts.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        pill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        r.setContentDescription(describe(label, caption, stateLabel(on[0])));
        r.setOnClickListener(v -> {
            on[0] = !on[0];
            pill.setText(stateLabel(on[0]));
            pill.setTextColor(on[0] ? Ui.ACCENT : Ui.DIM);
            r.setContentDescription(describe(label, caption, stateLabel(on[0])));
            onChange.onToggle(on[0]);
        });
        r.setClickable(true);
        return r;
    }
}
