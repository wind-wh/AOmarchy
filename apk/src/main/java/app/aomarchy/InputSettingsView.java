package app.aomarchy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

import app.aomarchy.i18n.T;
import app.aomarchy.ui.Ui;

/**
 * 设置 → 触摸板与鼠标: the native replacement for the old "触摸板与鼠标" category page — touchpad mode, pointer speed,
 * pointer capture and its transform, scroll speed/direction and the hardware touchpad thresholds.
 *
 * Contract for the settings host (SettingsPage): {@link #build(Activity)} returns the page body only — a vertical
 * stack of {@code Ui.card(...)} cards, no ScrollView, no title bar. Every change is saved immediately to the
 * {@code Prefs.NAME} SharedPreferences; the host re-reads the prefs when the sheet closes.
 *
 * Key names and value ranges are the ones SettingsActivity's old page wrote and MainActivity / Touchpad read:
 * {@code touchpad_mode} (boolean), {@code mouse_speed} (float 0.5–10, Touchpad.setAccelStrength),
 * {@code pointer_capture} / {@code transform_captured_pointer} / {@code captured_pointer_speed_factor} (MainActivity
 * .reloadPointerCapturePreferences), {@code scroll_speed}, {@code scroll_reverse},
 * {@code touchpad_scroll_threshold}, {@code touchpad_move_threshold}, {@code touchpad_gesture_scale}.
 */
public final class InputSettingsView {
    // Boolean: MainActivity.isTouchpadMode / the virtual touchpad routing gate.
    private static final String KEY_TOUCHPAD_MODE = "touchpad_mode";
    // Float: Touchpad.setAccelStrength (clamped 0.5–10.0). The name says "speed" but it is the acceleration strength.
    private static final String KEY_MOUSE_ACCEL = "mouse_speed";
    private static final String KEY_POINTER_CAPTURE = "pointer_capture";
    // String: no | c | cc | ud | at (see CAPTURED_POINTER_TRANSFORMS in SettingsActivity).
    private static final String KEY_TRANSFORM_CAPTURED_POINTER = "transform_captured_pointer";
    // Int percent 1–300.
    private static final String KEY_CAPTURED_POINTER_SPEED_FACTOR = "captured_pointer_speed_factor";
    // Float: Touchpad.setScrollSpeed (clamped 0.05–5.0).
    private static final String KEY_SCROLL_SPEED = "scroll_speed";
    private static final String KEY_SCROLL_REVERSE = "scroll_reverse";
    // Floats, multiples of the system touch slop: Touchpad.setGestureThresholds.
    private static final String KEY_SCROLL_THRESHOLD = "touchpad_scroll_threshold";
    private static final String KEY_MOVE_THRESHOLD = "touchpad_move_threshold";
    // Float pixels: Touchpad.setGestureScale (clamped 50–4000).
    private static final String KEY_GESTURE_SCALE = "touchpad_gesture_scale";

    private static final String[] TRANSFORM_VALUES = {"no", "c", "cc", "ud", "at"};
    /** Resolved per build: the language is decided by the activity, never by a class-load-time static. */
    private static String[] transformLabels() {
        return new String[] {
                T.t("transform.none"), T.t("transform.cw"), T.t("transform.ccw"), T.t("transform.flip"),
                T.t("transform.auto")
        };
    }

    private InputSettingsView() {
    }

    /** Builds the page. Never returns null. */
    public static View build(Activity host) {
        final SharedPreferences prefs = host.getSharedPreferences(Prefs.NAME, Activity.MODE_PRIVATE);
        LinearLayout body = Ui.column(host);

        // ===== 触摸板模式与光标灵敏度 =====
        LinearLayout touchpad = Ui.card(host, T.t("card.touchpad"));
        touchpad.addView(toggleRow(host, T.t("input.touchpad_mode"),
                T.t("input.touchpad_mode_caption"),
                prefs.getBoolean(KEY_TOUCHPAD_MODE, false),
                value -> prefs.edit().putBoolean(KEY_TOUCHPAD_MODE, value).apply()));
        touchpad.addView(divider(host));
        touchpad.addView(floatSlider(host, prefs, T.t("label.mouse_accel"),
                T.t("input.mouse_accel_caption"),
                KEY_MOUSE_ACCEL, 0.5f, 10.0f, 0.05f, 1.0f, "%.1fx").box);
        body.addView(touchpad);

        // ===== 指针捕获（硬件鼠标 / 触摸板）=====
        LinearLayout capture = Ui.card(host, T.t("card.capture"));
        final boolean captureOn = prefs.getBoolean(KEY_POINTER_CAPTURE, false);
        final LinearLayout transformRow = choiceRow(host, T.t("label.transform"),
                T.t("input.transform_caption"),
                transformLabels(),
                indexOf(TRANSFORM_VALUES,
                        prefs.getString(KEY_TRANSFORM_CAPTURED_POINTER, TRANSFORM_VALUES[0])),
                which -> prefs.edit()
                        .putString(KEY_TRANSFORM_CAPTURED_POINTER, TRANSFORM_VALUES[which]).apply());
        final SliderBox captureSpeed = intSlider(host, prefs, T.t("label.capture_speed"),
                T.t("input.capture_speed_caption"),
                KEY_CAPTURED_POINTER_SPEED_FACTOR, 1, 300, 100);
        capture.addView(toggleRow(host, T.t("input.capture_toggle"),
                T.t("input.capture_toggle_caption"),
                captureOn, value -> {
                    prefs.edit().putBoolean(KEY_POINTER_CAPTURE, value).apply();
                    setCaptureExtrasEnabled(transformRow, captureSpeed.bar, value);
                }));
        capture.addView(divider(host));
        capture.addView(transformRow);
        capture.addView(captureSpeed.box);
        setCaptureExtrasEnabled(transformRow, captureSpeed.bar, captureOn);
        body.addView(capture);

        // ===== 滚动方向与速度 =====
        LinearLayout scroll = Ui.card(host, T.t("card.scroll"));
        scroll.addView(toggleRow(host, T.t("input.reverse_scroll"),
                T.t("input.reverse_scroll_caption"),
                prefs.getBoolean(KEY_SCROLL_REVERSE, false),
                value -> prefs.edit().putBoolean(KEY_SCROLL_REVERSE, value).apply()));
        scroll.addView(divider(host));
        scroll.addView(floatSlider(host, prefs, T.t("label.scroll_speed"),
                T.t("input.scroll_speed_caption"),
                KEY_SCROLL_SPEED, 0.05f, 3.0f, 0.05f, Touchpad.DEFAULT_SCROLL_SPEED, "%.2f").box);
        body.addView(scroll);

        // ===== 硬件触摸板手势阈值 =====
        LinearLayout gestures = Ui.card(host, T.t("card.gestures"));
        gestures.addView(floatSlider(host, prefs, T.t("label.scroll_threshold"),
                T.t("input.scroll_threshold_caption"),
                KEY_SCROLL_THRESHOLD, 0.05f, 3.0f, 0.05f,
                Touchpad.DEFAULT_SCROLL_THRESHOLD_FACTOR, "%.2f×").box);
        gestures.addView(floatSlider(host, prefs, T.t("label.move_threshold"),
                T.t("input.move_threshold_caption"),
                KEY_MOVE_THRESHOLD, 0.1f, 8.0f, 0.05f,
                Touchpad.DEFAULT_MOVE_THRESHOLD_FACTOR, "%.2f×").box);
        gestures.addView(floatSlider(host, prefs, T.t("label.gesture_scale"),
                T.t("input.gesture_scale_caption"),
                KEY_GESTURE_SCALE, 100f, 3000f, 20f,
                Touchpad.DEFAULT_GESTURE_SCALE, "%.0f px").box);
        body.addView(gestures);

        return body;
    }

    // ==================== 控件 ====================

    /** A slider plus its label and the value it currently holds, so callers can disable both together. */
    private static final class SliderBox {
        final LinearLayout box;
        final SeekBar bar;

        SliderBox(LinearLayout box, SeekBar bar) {
            this.box = box;
            this.bar = bar;
        }
    }

    /** Float preference slider: the value is quantised by {@code step} and written on every user drag. */
    private static SliderBox floatSlider(Activity host, SharedPreferences prefs, String label, String caption,
            String key, float min, float max, float step, float fallback, String format) {
        int maxProgress = Math.max(1, Math.round((max - min) / step));
        float current = Math.max(min, Math.min(max, prefs.getFloat(key, fallback)));
        int progress = Math.round((current - min) / step);
        final TextView value = Ui.mono(host,
                String.format(Locale.US, format, min + progress * step), 14, Ui.ACCENT);
        SeekBar bar = seekBar(host, maxProgress, progress);
        // TalkBack: the bar names what it changes and its current value; the headline label/value are hidden from it.
        bar.setContentDescription(label + "，" + value.getText());
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                float selected = min + p * step;
                value.setText(String.format(Locale.US, format, selected));
                seekBar.setContentDescription(label + "，" + value.getText());
                if (fromUser)
                    prefs.edit().putFloat(key, selected).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        return new SliderBox(sliderBox(host, label, value, bar, caption), bar);
    }

    /** Integer percent slider (1–300% for the captured pointer). */
    private static SliderBox intSlider(Activity host, SharedPreferences prefs, String label, String caption,
            String key, int min, int max, int fallback) {
        int current = Math.max(min, Math.min(max, prefs.getInt(key, fallback)));
        final TextView value = Ui.mono(host, current + "%", 14, Ui.ACCENT);
        SeekBar bar = seekBar(host, max - min, current - min);
        bar.setContentDescription(label + "，" + value.getText());
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                int selected = min + p;
                value.setText(selected + "%");
                seekBar.setContentDescription(label + "，" + value.getText());
                if (fromUser)
                    prefs.edit().putInt(key, selected).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        return new SliderBox(sliderBox(host, label, value, bar, caption), bar);
    }

    /** The console's accent-tinted bar: same tints ControlsView uses for the volume sliders. */
    private static SeekBar seekBar(Activity host, int max, int progress) {
        SeekBar bar = new SeekBar(host);
        bar.setMax(Math.max(1, max));
        ColorStateList accent = ColorStateList.valueOf(Ui.ACCENT);
        bar.setProgressTintList(accent);
        bar.setThumbTintList(accent);
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.LINE));
        bar.setProgress(Math.max(0, Math.min(Math.max(1, max), progress)));
        return bar;
    }

    /** Label and value on one line, the bar under it, an 11sp explanation under that. */
    private static LinearLayout sliderBox(Activity host, String label, TextView value, SeekBar bar,
            String caption) {
        LinearLayout box = Ui.column(host);
        box.setPadding(0, Ui.dp(host, 8), 0, Ui.dp(host, 6));
        LinearLayout head = Ui.row(host);
        head.addView(Ui.text(host, label, 14, Ui.TEXT, false));
        head.addView(new View(host), new LinearLayout.LayoutParams(0, 1, 1f));
        head.addView(value);
        // The bar below carries the label and value in its contentDescription: do not read the headline twice.
        head.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(head);
        box.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(host, 34)));
        TextView note = Ui.text(host, caption, 11, Ui.FAINT, false);
        note.setPadding(0, 0, 0, Ui.dp(host, 2));
        box.addView(note);
        return box;
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

    /** label + optional caption + current value, joined for one TalkBack announcement. */
    private static String describe(String label, String caption, String value) {
        String base = caption == null || caption.isEmpty() ? label : label + "，" + caption;
        return value == null || value.isEmpty() ? base : base + "，" + value;
    }

    private static String stateLabel(boolean on) {
        return T.t(on ? "state.on" : "state.off");
    }

    /** Decorative separator: never a TalkBack stop. */
    private static View divider(Activity host) {
        View v = Ui.divider(host);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    /** Capture-only controls are dimmed and inert while the capture switch is off, as in the old page. */
    private static void setCaptureExtrasEnabled(LinearLayout transformRow, SeekBar speedBar, boolean enabled) {
        transformRow.setEnabled(enabled);
        transformRow.setAlpha(enabled ? 1f : 0.45f);
        speedBar.setEnabled(enabled);
        speedBar.setAlpha(enabled ? 1f : 0.45f);
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
