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
    private static final String[] ORIENTATION_LABELS = {
            "自动旋转", "竖屏", "横屏", "反向竖屏", "反向横屏"
    };

    // Parallel to DisplayCutoutMode.VALUES: hide_all / rounded_corners_only / cutout_only / none.
    private static final String[] CUTOUT_LABELS = {
            "画到所有刘海与圆角下", "只画到圆角下", "只画到刘海下", "全部避开"
    };

    private DisplaySettingsView() {
    }

    /** Builds the page. Never returns null. */
    public static View build(Activity host) {
        final SharedPreferences prefs = host.getSharedPreferences(Prefs.NAME, Activity.MODE_PRIVATE);
        LinearLayout body = Ui.column(host);

        // ===== 屏幕方向 =====
        LinearLayout orientation = Ui.card(host, "屏幕方向");
        orientation.addView(choiceRow(host, "窗口方向",
                "对桌面窗口生效，返回桌面时应用",
                ORIENTATION_LABELS,
                indexOf(ORIENTATION_VALUES,
                        prefs.getString(KEY_SCREEN_ORIENTATION, ORIENTATION_VALUES[0])),
                which -> prefs.edit()
                        .putString(KEY_SCREEN_ORIENTATION, ORIENTATION_VALUES[which]).apply()));
        body.addView(orientation);

        // ===== 刘海与圆角 =====
        LinearLayout cutout = Ui.card(host, "刘海与圆角");
        cutout.addView(choiceRow(host, "显示区域",
                "决定窗口能否画到刘海和圆角下面，返回桌面时应用",
                CUTOUT_LABELS,
                indexOf(DisplayCutoutMode.VALUES, DisplayCutoutMode.get(prefs)),
                which -> prefs.edit()
                        .putString(DisplayCutoutMode.KEY, DisplayCutoutMode.VALUES[which]).apply()));
        body.addView(cutout);

        // ===== 画中画 =====
        LinearLayout pip = Ui.card(host, "画中画");
        pip.addView(Ui.toggleRow(host, "PIP 小窗",
                "按 Home / 最近任务时缩成小窗；可能让桌面拿到奇怪的尺寸，谨慎开启",
                prefs.getBoolean(KEY_PIP_MODE, false),
                value -> prefs.edit().putBoolean(KEY_PIP_MODE, value).apply()));
        body.addView(pip);

        // ===== 显示参数：只读现状 + 跳转，不重复实现 =====
        LinearLayout live = Ui.card(host, "显示参数");
        android.util.DisplayMetrics dm = host.getResources().getDisplayMetrics();
        live.addView(Ui.kv2(host, "分辨率",
                Ui.mono(host, dm.widthPixels + " × " + dm.heightPixels, 14, Ui.TEXT),
                "面板当前像素"));
        live.addView(Ui.kv2(host, "密度",
                Ui.mono(host, dm.densityDpi + " dpi", 14, Ui.TEXT),
                "逻辑密度，界面缩放按它算"));
        live.addView(Ui.kv2(host, "刷新率",
                Ui.mono(host, refreshRate(host), 14, Ui.TEXT),
                "面板当前刷新率"));
        live.addView(Ui.gap(host, 6));
        live.addView(Ui.actionRow(host, "显示参数调节",
                "分辨率、密度、刷新率的调节入口在「控制」页，本页不重复实现", "控制页",
                () -> host.startActivity(new Intent(host, ControlsActivity.class))));
        body.addView(live);

        LinearLayout note = Ui.card(host, "说明");
        note.addView(Ui.text(host, "分辨率、密度、刷新率属于实时显示参数，入口在「控制」页；方向、刘海与 PIP 是窗口级设置，桌面会话启动或返回时读取。",
                11, Ui.FAINT, false));
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

        final int[] selected = {checked};
        row.setOnClickListener(v -> new AlertDialog.Builder(host)
                .setTitle(label)
                .setSingleChoiceItems(labels, selected[0], (dialog, which) -> {
                    selected[0] = which;
                    value.setText(labels[which]);
                    onPick.onPick(which);
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show());
        row.setClickable(true);
        return row;
    }

    private static int indexOf(String[] values, String target) {
        for (int i = 0; i < values.length; i++)
            if (values[i].equals(target))
                return i;
        return 0;
    }
}
