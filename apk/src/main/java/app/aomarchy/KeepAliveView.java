package app.aomarchy;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.aomarchy.i18n.T;
import app.aomarchy.ui.Toasts;
import app.aomarchy.ui.Ui;

/**
 * 设置 → 后台运行: the keep-alive switch, the battery-optimization hint and the ADB allow-list command.
 *
 * <p>Instance API like the other rebuilt settings pages, for the settings host (SettingsPage):
 * <pre>
 *   KeepAliveView keepAlive = KeepAliveView.create(activity);
 *   // put keepAlive.view() into the scrollable sheet (cards only, no title bar)
 *   // when the sheet is dismissed: keepAlive.dispose();
 * </pre>
 * The page only reflects {@link KeepAliveService#isRunning(Context)}; the service itself owns the notification.
 */
public final class KeepAliveView {
    private final Activity host;
    private final LinearLayout root;
    private TextView pill;
    private boolean disposed;

    /** Builds the page for one settings host. */
    public static KeepAliveView create(Activity host) {
        return new KeepAliveView(host);
    }

    private KeepAliveView(Activity host) {
        this.host = host;
        root = Ui.column(host);
        root.addView(keepAliveCard());
        root.addView(batteryCard());
        refresh();
    }

    /** The page body: a vertical stack of cards, ready for the host's scrollable sheet. */
    public View view() {
        return root;
    }

    /** Re-reads the service state (the host can call this whenever the page is shown again). */
    public void refresh() {
        if (disposed || pill == null)
            return;
        setPill(KeepAliveService.isRunning(host));
    }

    /** No document pickers here; kept for API symmetry with the other settings pages. */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        return false;
    }

    /** The settings sheet is closing: stop touching the host from late callbacks. */
    public void dispose() {
        disposed = true;
    }

    // ---- 后台运行 -------------------------------------------------------------------------------------------------

    private LinearLayout keepAliveCard() {
        LinearLayout card = Ui.card(host, T.t("keepalive.title"));
        card.addView(Ui.text(host, T.t("keepalive.desc"), 12, Ui.DIM, false));
        card.addView(Ui.gap(host, 8));

        LinearLayout row = Ui.row(host);
        row.setPadding(0, Ui.dp(host, 7), 0, Ui.dp(host, 7));
        row.addView(Ui.text(host, T.t("keepalive.toggle"), 14, Ui.TEXT, false));
        row.addView(new View(host), new LinearLayout.LayoutParams(0, 1, 1f));

        pill = Ui.text(host, T.t("state.off"), 13, Ui.DIM, true);
        pill.setPadding(Ui.dp(host, 10), Ui.dp(host, 5), Ui.dp(host, 10), Ui.dp(host, 5));
        pill.setBackground(Ui.surface(host, 30));
        row.addView(pill);

        row.setClickable(true);
        row.setOnClickListener(v -> toggle());
        card.addView(row);
        return card;
    }

    private void toggle() {
        boolean on = KeepAliveService.isRunning(host);
        if (on) {
            KeepAliveService.stop(host);
            setPill(false);
            toast(T.t("keepalive.stopped"));
        } else {
            KeepAliveService.start(host);
            setPill(true); // optimistic: the service reports running from its onCreate, one main-loop turn later
            toast(T.t("keepalive.started"));
            // Re-sync once the service had a chance to start (or failed to, e.g. a background-start restriction).
            if (host.getWindow() != null) {
                host.getWindow().getDecorView().postDelayed(() -> {
                    if (!disposed)
                        refresh();
                }, 800);
            }
        }
    }

    private void setPill(boolean on) {
        if (pill == null)
            return;
        pill.setText(T.t(on ? "state.on" : "state.off"));
        pill.setTextColor(on ? Ui.ACCENT : Ui.DIM);
    }

    // ---- 电池与 ADB 保活 -------------------------------------------------------------------------------------------

    private LinearLayout batteryCard() {
        LinearLayout card = Ui.card(host, keyOr("keepalive.battery_card", "电池与 ADB 保活"));
        card.addView(Ui.text(host, T.t("keepalive.battery_prompt"), 12, Ui.DIM, false));
        card.addView(Ui.gap(host, 8));
        card.addView(Ui.button(host, keyOr("keepalive.battery_settings", "电池设置"), false,
                v -> openBatterySettings()));

        card.addView(Ui.divider(host));
        card.addView(Ui.gap(host, 8));
        card.addView(Ui.text(host, T.t("keepalive.adb_hint"), 12, Ui.DIM, false));
        card.addView(Ui.gap(host, 6));

        TextView command = Ui.mono(host, T.t("keepalive.adb_command"), 12, Ui.TEXT);
        command.setBackground(Ui.surface(host, 10));
        command.setPadding(Ui.dp(host, 10), Ui.dp(host, 8), Ui.dp(host, 10), Ui.dp(host, 8));
        command.setTextIsSelectable(true); // also copyable with a long press, the button below is the short path
        card.addView(command);

        card.addView(Ui.gap(host, 8));
        card.addView(Ui.button(host, T.t("keepalive.copy"), false, v -> copyCommand()));
        return card;
    }

    private void openBatterySettings() {
        try {
            host.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (RuntimeException e) {
            // some builds have no such settings screen: leave the ADB path as the fallback
            android.util.Log.w("KeepAliveView", "could not open the battery optimization settings", e);
        }
    }

    private void copyCommand() {
        try {
            ClipboardManager manager = (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null)
                return;
            manager.setPrimaryClip(ClipData.newPlainText("AOmarchy", T.t("keepalive.adb_command")));
            toast(T.t("keepalive.copied"));
        } catch (RuntimeException e) {
            android.util.Log.w("KeepAliveView", "could not copy the ADB command", e);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private void toast(String text) {
        if (!disposed)
            Toasts.show(host, text);
    }

    /**
     * A T key that T.java may not have yet: the Lead adds new keys centrally, so until then the Chinese fallback keeps
     * the page readable, and the key starts working as soon as it lands (no edit needed here).
     */
    private static String keyOr(String key, String fallback) {
        String value = T.t(key);
        return value.equals(key) ? fallback : value;
    }
}
