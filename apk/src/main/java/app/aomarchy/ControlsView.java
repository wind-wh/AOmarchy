package app.aomarchy;

import android.content.Context;
import android.media.AudioManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import app.aomarchy.i18n.T;
import app.aomarchy.ui.Ui;

/**
 * The high-frequency switchboard: volume, display and clipboard passthrough. Used both as the Controls page of the
 * console and as the half-screen sheet over the desktop (ControlsActivity).
 * Session power actions live in the rail's PowerMenu; startup mode lives in the global top bar.
 */
final class ControlsView {
    final LinearLayout root;

    private final Context ctx;
    private final AudioManager audio;
    private final TextView clipboardToggle;
    private final String clipboardLabel;

    ControlsView(Context ctx) {
        this.ctx = ctx;
        T.init(ctx); // this view also opens standalone over the desktop (ControlsActivity), so resolve ui_lang here
        this.audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        root = Ui.column(ctx);
        root.setPadding(Ui.dp(ctx, 2), 0, Ui.dp(ctx, 2), 0);

        if (android.os.Build.VERSION.SDK_INT >= 33
                && ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            LinearLayout warn = Ui.card(ctx, T.t("card.notifications_off"));
            warn.addView(Ui.text(ctx, T.t("controls.warn_notifications"), 13, Ui.DIM, false));
            warn.addView(Ui.gap(ctx, 8));
            warn.addView(button(T.t("btn.enable_notifications"), true, v -> {
                android.content.Intent i = new android.content.Intent(
                        android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.getPackageName());
                ctx.startActivity(i);
            }));
            root.addView(warn);
        }

        LinearLayout sound = Ui.card(ctx, T.t("card.sound"));
        int max = audio != null ? audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) : 15;
        sound.addView(slider(T.t("label.media_volume"), AudioManager.STREAM_MUSIC, max));
        sound.addView(Ui.gap(ctx, 6));
        sound.addView(slider(T.t("label.call_volume"), AudioManager.STREAM_VOICE_CALL,
                audio != null ? audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) : 7));
        root.addView(sound);

        LinearLayout display = Ui.card(ctx, T.t("card.display_input"));
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        String screen = dm.widthPixels + " × " + dm.heightPixels + " · " + dm.densityDpi + " dpi";
        try {
            float hz = ((android.app.Activity) ctx).getWindowManager().getDefaultDisplay().getRefreshRate();
            if (hz > 0)
                screen += String.format(java.util.Locale.US, " · %.0f Hz", hz);
        } catch (RuntimeException ignored) {
            // refresh rate is device-specific
        }
        display.addView(Ui.kv2(ctx, T.t("label.screen"), Ui.mono(ctx, screen, 14, Ui.TEXT),
                T.t("controls.screen_caption")));
        display.addView(Ui.kv2(ctx, T.t("label.input"), Ui.mono(ctx, T.t("controls.input_value"), 14, Ui.TEXT),
                T.t("controls.input_caption")));
        TextView displayLink = Ui.text(ctx, T.t("controls.display_link"), 12, Ui.ACCENT, false);
        displayLink.setPadding(0, Ui.dp(ctx, 4), 0, 0);
        displayLink.setMinimumHeight(Ui.dp(ctx, 48));
        displayLink.setOnClickListener(v -> ctx.startActivity(ShellActivity.intent(ctx, 4)));
        display.addView(displayLink);
        root.addView(display);

        // Clipboard is one switch now: on = transparent two-way passthrough (default), off = no sync at all.
        LinearLayout clip = Ui.card(ctx, T.t("card.clipboard"));
        clip.addView(Ui.text(ctx, T.t("controls.clipboard_passthrough_hint"), 12, Ui.DIM, false));
        clip.addView(Ui.gap(ctx, 6));
        clipboardLabel = T.t("controls.clipboard_passthrough");
        LinearLayout clipRow = Ui.row(ctx);
        clipRow.setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8));
        clipRow.addView(Ui.text(ctx, clipboardLabel, 15, Ui.TEXT, false));
        clipRow.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));
        clipboardToggle = Ui.text(ctx, "", 14, Ui.DIM, true);
        clipboardToggle.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8));
        clipRow.addView(clipboardToggle);
        clipRow.setMinimumHeight(Ui.dp(ctx, 48));
        for (int i = 0; i < clipRow.getChildCount(); i++)
            clipRow.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        clipRow.setClickable(true);
        clipRow.setOnClickListener(v -> {
            boolean on = !Prefs.clipboardPassthrough(ctx);
            ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(Prefs.PREF_CLIPBOARD_PASSTHROUGH, on).apply();
            applyClipboardToggle(on);
            toast(clipboardLabel + "：" + T.t(on ? "state.on" : "state.off"));
        });
        clip.addView(clipRow);
        root.addView(clip);
        applyClipboardToggle(Prefs.clipboardPassthrough(ctx));
    }

    private LinearLayout slider(String label, int stream, int max) {
        LinearLayout box = Ui.column(ctx);
        LinearLayout head = Ui.row(ctx);
        head.addView(Ui.text(ctx, label, 14, Ui.TEXT, false));
        head.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView value = Ui.mono(ctx, "—", 14, Ui.DIM);
        head.addView(value);
        // The bar's contentDescription already carries the label and the current level: one TalkBack stop per slider.
        head.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(head);
        SeekBar bar = new SeekBar(ctx);
        bar.setMax(Math.max(1, max));
        android.content.res.ColorStateList accent = android.content.res.ColorStateList.valueOf(Ui.ACCENT);
        bar.setProgressTintList(accent);
        bar.setThumbTintList(accent);
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.LINE));
        int current = audio != null ? audio.getStreamVolume(stream) : 0;
        bar.setProgress(current);
        value.setText(String.valueOf(current));
        bar.setContentDescription(label + "，" + current);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(String.valueOf(progress));
                seekBar.setContentDescription(label + "，" + progress);
                if (fromUser && audio != null) {
                    try {
                        audio.setStreamVolume(stream, progress, 0);
                    } catch (RuntimeException ignored) {
                        // some streams are fixed by the device
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 34));
        box.addView(bar, lp);
        return box;
    }

    /** A console button with the 48dp minimum touch target TalkBack needs. */
    private TextView button(String label, boolean primary, View.OnClickListener onClick) {
        TextView b = Ui.button(ctx, label, primary, onClick);
        b.setMinimumHeight(Ui.dp(ctx, 48));
        return b;
    }

    /** Called when the page/sheet becomes visible: re-read the switch in case it changed elsewhere. */
    void refresh() {
        applyClipboardToggle(Prefs.clipboardPassthrough(ctx));
    }

    private void applyClipboardToggle(boolean on) {
        clipboardToggle.setText(T.t(on ? "state.on" : "state.off"));
        clipboardToggle.setTextColor(on ? Ui.ACCENT : Ui.DIM);
        View row = (View) clipboardToggle.getParent();
        if (row != null)
            row.setContentDescription(clipboardLabel + "，" + T.t(on ? "state.on" : "state.off"));
    }

    private void toast(String text) {
        if (ctx instanceof android.app.Activity)
            app.aomarchy.ui.Toasts.show((android.app.Activity) ctx, text);
        else
            Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
    }
}
