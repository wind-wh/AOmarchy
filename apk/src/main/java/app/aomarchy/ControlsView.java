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
        this.audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        root = Ui.column(ctx);
        root.setPadding(Ui.dp(ctx, 2), 0, Ui.dp(ctx, 2), 0);

        if (android.os.Build.VERSION.SDK_INT >= 33
                && ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            LinearLayout warn = Ui.card(ctx, "通知未开启");
            warn.addView(Ui.text(ctx, "桌面运行时靠会话通知里的「控制」回到这里；通知权限没开就看不到它。",
                    13, Ui.DIM, false));
            warn.addView(Ui.gap(ctx, 8));
            warn.addView(Ui.button(ctx, "去开启通知", true, v -> {
                android.content.Intent i = new android.content.Intent(
                        android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.getPackageName());
                ctx.startActivity(i);
            }));
            root.addView(warn);
        }
        LinearLayout session = Ui.card(ctx, "会话");
        sessionState = Ui.text(ctx, "—", 13, Ui.DIM, false);
        session.addView(sessionState);
        session.addView(Ui.gap(ctx, 10));
        LinearLayout sessionButtons = Ui.row(ctx);
        sessionButtons.addView(Ui.button(ctx, "进入桌面", true, v -> openDesktop()));
        sessionButtons.addView(Ui.space(ctx, 8));
        sessionButtons.addView(Ui.button(ctx, "重启会话", false, v -> confirmPower("reboot", "重启会话？")));
        sessionButtons.addView(Ui.space(ctx, 8));
        sessionButtons.addView(Ui.button(ctx, "关闭桌面", false, v -> confirmPower("shutdown", "关闭桌面并回到 Android？")));
        session.addView(sessionButtons);
        root.addView(session);

        LinearLayout sound = Ui.card(ctx, "声音");
        int max = audio != null ? audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) : 15;
        sound.addView(slider("媒体音量", AudioManager.STREAM_MUSIC, max));
        sound.addView(Ui.gap(ctx, 6));
        sound.addView(slider("通话音量", AudioManager.STREAM_VOICE_CALL,
                audio != null ? audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) : 7));
        root.addView(sound);

        LinearLayout display = Ui.card(ctx, "显示与输入");
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        String screen = dm.widthPixels + " × " + dm.heightPixels + " · " + dm.densityDpi + " dpi";
        try {
            float hz = ((android.app.Activity) ctx).getWindowManager().getDefaultDisplay().getRefreshRate();
            if (hz > 0)
                screen += String.format(java.util.Locale.US, " · %.0f Hz", hz);
        } catch (RuntimeException ignored) {
            // refresh rate is device-specific
        }
        display.addView(Ui.kv2(ctx, "屏幕", Ui.mono(ctx, screen, 14, Ui.TEXT), "Android 显示"));
        display.addView(Ui.kv2(ctx, "输入", Ui.mono(ctx, "触摸板 · 虚拟键盘", 14, Ui.TEXT), "按键映射在设置里"));
        TextView displayLink = Ui.text(ctx, "显示、方向与刘海设置 ›", 12, Ui.ACCENT, false);
        displayLink.setPadding(0, Ui.dp(ctx, 4), 0, 0);
        displayLink.setOnClickListener(v -> ctx.startActivity(new Intent(ctx, SettingsActivity.class)));
        display.addView(displayLink);
        root.addView(display);

        LinearLayout clip = Ui.card(ctx, "剪贴板");
        clipboardState = Ui.text(ctx, "Android ↔ 桌面双向同步", 12, Ui.DIM, false);
        clip.addView(clipboardState);
        clip.addView(Ui.gap(ctx, 6));
        LinearLayout clipButtons = Ui.row(ctx);
        clipButtons.addView(Ui.button(ctx, "发送到桌面", false, v -> pushClipboard()));
        clipButtons.addView(Ui.space(ctx, 8));
        clipButtons.addView(Ui.button(ctx, "从桌面取回", false, v -> pullClipboard()));
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
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(String.valueOf(progress));
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

    /** Called when the page/sheet becomes visible. */
    void refresh() {
        boolean ready = SessionService.rootfsReady(ctx);
        sessionState.setText(ready
                ? (SessionService.started ? "会话运行中 · 点「进入桌面」回到 Linux" : "系统已安装，尚未启动")
                : "还没有安装系统（到「系统」页安装）");
        sessionState.setTextColor(ready ? Ui.DIM : Ui.FAINT);
    }

    private void openDesktop() {
        if (!SessionService.rootfsReady(ctx)) {
            toast("系统还没装好");
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
            toast("系统还没装好");
            return;
        }
        new android.app.AlertDialog.Builder(ctx)
                .setTitle(title)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> Restarter.power(ctx, action))
                .show();
    }

    private void pushClipboard() {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        CharSequence text = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).coerceToText(ctx);
        if (text == null || text.length() == 0) {
            toast("Android 剪贴板是空的");
            return;
        }
        write(SessionService.runDir(ctx), "clipboard-from-android", text.toString());
        clipboardState.setText("已发送 " + text.length() + " 个字符到桌面");
    }

    private void pullClipboard() {
        File f = new File(SessionService.runDir(ctx), "clipboard-to-android");
        try {
            String text = Files.exists(f.toPath()) ? new String(Files.readAllBytes(f.toPath())) : "";
            if (text.isEmpty()) {
                toast("桌面上没有新的剪贴板内容");
                return;
            }
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("AOmarchy", text));
            clipboardState.setText("已取回 " + text.length() + " 个字符");
        } catch (Exception e) {
            toast("读取桌面剪贴板失败：" + e.getMessage());
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
