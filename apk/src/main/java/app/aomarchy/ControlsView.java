package app.aomarchy;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.nio.file.Files;

import app.aomarchy.i18n.T;
import app.aomarchy.ui.Ui;

/**
 * The high-frequency switchboard: session, volume, display, clipboard, input. Used both as the Controls page of the
 * console and as the half-screen sheet that opens over the desktop (ControlsActivity).
 */
final class ControlsView {
    final LinearLayout root;

    private final Context ctx;
    private final AudioManager audio;
    private final TextView sessionState;
    private final TextView clipboardState;

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
            warn.addView(Ui.text(ctx, T.t("controls.warn_notifications"),
                    13, Ui.DIM, false));
            warn.addView(Ui.gap(ctx, 8));
            warn.addView(button(T.t("btn.enable_notifications"), true, v -> {
                android.content.Intent i = new android.content.Intent(
                        android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.getPackageName());
                ctx.startActivity(i);
            }));
            root.addView(warn);
        }
        LinearLayout session = Ui.card(ctx, T.t("card.session"));
        sessionState = Ui.text(ctx, "—", 13, Ui.DIM, false);
        session.addView(sessionState);
        session.addView(Ui.gap(ctx, 10));
        LinearLayout sessionButtons = Ui.row(ctx);
        sessionButtons.addView(button(T.t("btn.enter_desktop"), true, v -> openDesktop()));
        sessionButtons.addView(Ui.space(ctx, 8));
        sessionButtons.addView(button(T.t("btn.restart_session"), false,
                v -> confirmPower("reboot", T.t("dialog.restart_session_title"))));
        sessionButtons.addView(Ui.space(ctx, 8));
        sessionButtons.addView(button(T.t("btn.close_desktop"), false,
                v -> confirmPower("shutdown", T.t("dialog.shutdown_title"))));
        session.addView(sessionButtons);
        root.addView(session);

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

        LinearLayout clip = Ui.card(ctx, T.t("card.clipboard"));
        clipboardState = Ui.text(ctx, T.t("controls.clipboard_sync"), 12, Ui.DIM, false);
        clip.addView(clipboardState);
        clip.addView(Ui.gap(ctx, 6));
        LinearLayout clipButtons = Ui.row(ctx);
        clipButtons.addView(button(T.t("btn.send_to_desktop"), false, v -> pushClipboard()));
        clipButtons.addView(Ui.space(ctx, 8));
        clipButtons.addView(button(T.t("btn.fetch_from_desktop"), false, v -> pullClipboard()));
        clip.addView(clipButtons);
        root.addView(clip);


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

    /** Called when the page/sheet becomes visible. */
    void refresh() {
        boolean ready = SessionService.rootfsReady(ctx);
        sessionState.setText(ready
                ? (SessionService.started ? T.t("controls.session_running") : T.t("controls.session_ready"))
                : T.t("controls.no_system"));
        sessionState.setTextColor(ready ? Ui.DIM : Ui.FAINT);
    }

    private void openDesktop() {
        if (!SessionService.rootfsReady(ctx)) {
            toast(T.t("toast.system_missing"));
            return;
        }
        if (ctx instanceof android.app.Activity)
            ((android.app.Activity) ctx).finish(); // leave the sheet so the session is visible
        boolean terminal = "terminal".equals(ctx.getSharedPreferences(Prefs.NAME,
                android.content.Context.MODE_PRIVATE).getString("session_mode", "desktop"));
        if (!terminal)
            SessionService.start(ctx);
        Intent next = new Intent(ctx, terminal ? TerminalActivity.class : MainActivity.class);
        next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(next);
    }

    private void confirmPower(String action, String title) {
        if (!SessionService.rootfsReady(ctx)) {
            toast(T.t("toast.system_missing"));
            return;
        }
        new android.app.AlertDialog.Builder(ctx)
                .setTitle(title)
                .setNegativeButton(T.t("btn.cancel"), null)
                .setPositiveButton(T.t("btn.confirm"), (d, w) -> Restarter.power(ctx, action))
                .show();
    }

    private void pushClipboard() {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        CharSequence text = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).coerceToText(ctx);
        if (text == null || text.length() == 0) {
            toast(T.t("toast.clipboard_empty"));
            return;
        }
        write(SessionService.runDir(ctx), "clipboard-from-android", text.toString());
        clipboardState.setText(T.t("toast.sent_to_desktop", text.length()));
    }

    private void pullClipboard() {
        File f = new File(SessionService.runDir(ctx), "clipboard-to-android");
        try {
            String text = Files.exists(f.toPath()) ? new String(Files.readAllBytes(f.toPath())) : "";
            if (text.isEmpty()) {
                toast(T.t("toast.nothing_to_fetch"));
                return;
            }
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("AOmarchy", text));
            clipboardState.setText(T.t("toast.fetched", text.length()));
        } catch (Exception e) {
            toast(T.t("toast.read_clipboard_failed", String.valueOf(e.getMessage())));
        }
    }

    private static void write(File run, String name, String text) {
        run.mkdirs();
        File tmp = new File(run, name + ".tmp");
        try {
            Files.write(tmp.toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!tmp.renameTo(new File(run, name)))
                throw new java.io.IOException("rename failed");
        } catch (java.io.IOException e) {
            android.util.Log.w("Controls", "write " + name + " failed", e);
        }
    }

    private void toast(String text) {
        if (ctx instanceof android.app.Activity)
            app.aomarchy.ui.Toasts.show((android.app.Activity) ctx, text);
        else
            Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
    }
}
