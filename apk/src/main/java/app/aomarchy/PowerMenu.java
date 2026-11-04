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
 * The console rail's power menu: restart or stop the Linux session without going through the desktop or the
 * notification, plus a way out of the console that leaves the session running. Drawn with the console's own design
 * system (a centred card) instead of a stock dialog, so the unavailable entries can be dimmed in place.
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
                12, !ready ? Ui.FAINT : running ? Ui.MINT : Ui.DIM, false);
        state.setPadding(0, Ui.dp(host, 2), 0, 0);
        panel.addView(state);
        panel.addView(Ui.gap(host, 8));
        panel.addView(Ui.divider(host));

        // Restart only makes sense with a system; without a running session the same entry simply starts it.
        if (!ready) {
            panel.addView(row(host, dialog, T.t("power.restart"), T.t("power.need_system"), false, null));
        } else if (running) {
            panel.addView(row(host, dialog, T.t("power.restart"), T.t("power.restart_hint"),
                    true, () -> restart(host)));
        } else {
            panel.addView(row(host, dialog, T.t("power.start"), T.t("power.start_hint"),
                    true, () -> start(host)));
        }
        panel.addView(row(host, dialog, T.t("power.stop"),
                !ready ? T.t("power.need_system") : T.t("power.stop_hint"),
                ready && running, () -> stop(host)));
        // Leaving the console keeps the session: the notification and the home-screen widget stay as they are.
        panel.addView(row(host, dialog, T.t("power.exit"),
                running ? T.t("power.exit_note") : T.t("power.exit_note_off"),
                true, () -> exit(host)));

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

    /** One 48dp row: an action label with a one-line explanation, dimmed and inert when it cannot run. */
    private static LinearLayout row(ShellActivity host, Dialog dialog, String label, String caption,
            boolean enabled, Runnable action) {
        LinearLayout row = Ui.row(host);
        row.setMinimumHeight(Ui.dp(host, 48));
        row.setPadding(0, Ui.dp(host, 9), 0, Ui.dp(host, 9));
        LinearLayout texts = Ui.column(host);
        TextView title = Ui.text(host, label, 14, Ui.TEXT, false);
        TextView hint = Ui.text(host, caption, 11, Ui.FAINT, false);
        texts.addView(title);
        texts.addView(hint);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // One TalkBack stop per row: it reads label and explanation, not the two child TextViews separately.
        row.setContentDescription(label + ": " + caption);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
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

    private static void restart(ShellActivity host) {
        if (!SessionService.rootfsReady(host))
            return;
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
        host.stopService(new Intent(host, SessionService.class));
        host.toast(T.t("power.toast_stopped"));
        refreshLater(host);
    }

    private static void exit(ShellActivity host) {
        // Only the console goes away: the session (and its notification) keeps running in the background.
        host.finishAffinity();
    }

    private static void refreshLater(ShellActivity host) {
        MAIN.postDelayed(host::sessionChanged, STATE_REFRESH_DELAY_MS);
    }
}
