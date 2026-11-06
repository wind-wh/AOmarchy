package app.aomarchy;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.aomarchy.i18n.T;
import app.aomarchy.ui.Ui;

/**
 * The console rail's power menu, using the words people know from Android and their PCs: 挂起 (send the console to
 * the background), 后台运行 (keep-alive toggle), 重启 (restart Linux), 关机 (shut Linux down) and 退出 App (close
 * the console only). 挂起 and 后台运行 work without an installed system; 重启 and 关机 are dimmed until Linux is
 * installed and running.
 */
final class PowerMenu {
    /**
     * A dying SessionService needs a moment: a start() sent immediately can be handed to the old instance and
     * swallowed, so the restart waits inside the 400–600 ms window before starting the new one.
     */
    private static final int RESTART_DELAY_MS = 500;
    /** The service flips {@link SessionService#started} in onCreate/onDestroy, i.e. after these calls return. */
    private static final int STATE_REFRESH_DELAY_MS = 400;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PowerMenu() {
    }

    static void show(ShellActivity host) {
        boolean ready = SessionService.rootfsReady(host);
        boolean running = SessionService.started;

        final Dialog dialog = new Dialog(host);
        LinearLayout panel = Ui.column(host);
        panel.setBackground(Ui.surface(host, 20));
        int pad = Ui.dp(host, 16);
        panel.setPadding(pad, pad, pad, pad);

        panel.addView(Ui.text(host, T.t("power.title"), 16, Ui.TEXT, true));
        TextView state = Ui.text(host, !ready ? T.t("power.need_system")
                        : running ? T.t("power.state_running") : T.t("power.state_stopped"),
                12, !ready ? Ui.FAINT : running ? Ui.SIGNAL : Ui.DIM, false);
        state.setPadding(0, Ui.dp(host, 2), 0, 0);
        panel.addView(state);
        panel.addView(Ui.gap(host, 8));
        panel.addView(Ui.divider(host));

        // 挂起: the console leaves the screen like Home does, and the Linux session keeps running.
        panel.addView(row(host, dialog, T.t("power.suspend"), T.t("power.suspend_hint"),
                null, 0, true, () -> suspend(host)));
        // 后台运行: the keep-alive notification has nothing to do with Linux, so it works without a system too. The
        // hint also says that 关机/重启 switch it off (the menu below stops it before ending Linux).
        boolean keepAlive = keepAliveRunning(host);
        panel.addView(row(host, dialog, T.t("power.background"),
                T.t("power.background_hint") + "\n" + T.t("keepalive.auto_off"),
                T.t(keepAlive ? "power.background_on" : "power.background_off"),
                keepAlive ? Ui.SIGNAL : Ui.FAINT, true, () -> toggleBackground(host)));
        // 重启 / 关机 only make sense with a running Linux: dimmed with the reason in place otherwise.
        panel.addView(row(host, dialog, T.t("power.restart"),
                !ready ? T.t("power.need_system")
                        : !running ? T.t("power.state_stopped") : T.t("power.restart_hint"),
                null, 0, ready && running, () -> restart(host)));
        panel.addView(row(host, dialog, T.t("power.stop"),
                !ready ? T.t("power.need_system")
                        : !running ? T.t("power.state_stopped") : T.t("power.stop_hint"),
                null, 0, ready && running, () -> stop(host)));
        // 退出 App closes the console only: Linux (and the keep-alive notification) stay as they are.
        panel.addView(row(host, dialog, T.t("power.exit"),
                running ? T.t("power.exit_note") : T.t("power.exit_note_off"),
                null, 0, true, () -> exit(host)));

        panel.addView(Ui.gap(host, 12));
        TextView cancel = Ui.button(host, T.t("power.cancel"), false, v -> dialog.dismiss());
        cancel.setMinimumHeight(Ui.dp(host, 48));
        panel.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(true); // tapping the dimmed console behaves like 取消
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0)); // the card draws itself, no stock dialog chrome
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.55f);
            window.setGravity(Gravity.CENTER);
            window.setLayout(Math.min(Ui.dp(host, 340),
                            (int) (host.getResources().getDisplayMetrics().widthPixels * 0.9f)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    /**
     * One 48dp row: label, a one-line explanation and an optional right-hand state value. Dimmed and inert when the
     * action cannot run; TalkBack reads it as one node ("label：explanation：value").
     */
    private static LinearLayout row(ShellActivity host, Dialog dialog, String label, String hint,
            String value, int valueColor, boolean enabled, Runnable action) {
        LinearLayout row = Ui.row(host);
        row.setMinimumHeight(Ui.dp(host, 48));
        row.setPadding(0, Ui.dp(host, 9), 0, Ui.dp(host, 9));
        LinearLayout texts = Ui.column(host);
        TextView title = Ui.text(host, label, 14, Ui.TEXT, false);
        TextView hintView = Ui.text(host, hint, 11, Ui.FAINT, false);
        texts.addView(title);
        texts.addView(hintView);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView valueView = null;
        if (value != null) {
            valueView = Ui.text(host, value, 12, valueColor, false);
            row.addView(valueView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        // One TalkBack stop per row, so the state (e.g. 后台运行中) is read with the action that changes it.
        row.setContentDescription(value == null ? label + "：" + hint : label + "：" + hint + "：" + value);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        hintView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (valueView != null)
            valueView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (enabled) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                action.run();
            });
        } else {
            row.setAlpha(0.4f);
            row.setEnabled(false);
        }
        return row;
    }

    /** 挂起: same as Home — the console goes to the back, Linux (and its notification) keeps running. */
    private static void suspend(ShellActivity host) {
        host.moveTaskToBack(true);
    }

    /** 后台运行: toggles the keep-alive foreground service; independent of whether Linux was ever installed. */
    private static void toggleBackground(ShellActivity host) {
        try {
            if (KeepAliveService.isRunning(host))
                KeepAliveService.stop(host);
            else
                KeepAliveService.start(host);
        } catch (RuntimeException ignored) {
            // best-effort: a failed toggle must not take the console (or the System UI) down
        }
    }

    private static boolean keepAliveRunning(ShellActivity host) {
        try {
            return KeepAliveService.isRunning(host);
        } catch (RuntimeException e) {
            return false; // the service is best-effort: never stop the menu from opening
        }
    }

    /** 重启/关机 end Linux, so the 后台运行 keep-alive notification is switched off first (shutdown wins). */
    private static void stopKeepAlive(ShellActivity host) {
        try {
            KeepAliveService.stop(host);
        } catch (RuntimeException ignored) {
            // keep-alive is best-effort: a failure here must never block the power action
        }
    }

    private static void restart(ShellActivity host) {
        if (!SessionService.rootfsReady(host))
            return;
        stopKeepAlive(host);
        if (!SessionService.started) {
            start(host);
            return;
        }
        host.stopService(new Intent(host, SessionService.class));
        host.toast(T.t("power.toast_restarting"));
        MAIN.postDelayed(() -> start(host), RESTART_DELAY_MS);
    }

    private static void start(ShellActivity host) {
        try {
            SessionService.start(host);
            host.toast(T.t("power.toast_started"));
        } catch (RuntimeException e) {
            host.toast(T.t("power.toast_start_failed"));
        }
        refreshLater(host);
    }

    private static void stop(ShellActivity host) {
        stopKeepAlive(host);
        host.stopService(new Intent(host, SessionService.class));
        host.toast(T.t("power.toast_stopped"));
        refreshLater(host);
    }

    private static void exit(ShellActivity host) {
        // Only the console goes away: Linux (and the keep-alive notification) keep running in the background.
        host.finishAffinity();
    }

    private static void refreshLater(ShellActivity host) {
        MAIN.postDelayed(host::sessionChanged, STATE_REFRESH_DELAY_MS);
    }
}
