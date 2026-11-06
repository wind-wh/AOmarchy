package app.aomarchy;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.aomarchy.i18n.T;
import app.aomarchy.ui.Ui;
import app.aomarchy.ui.Wallpaper;

/**
 * 设置 → 控制台背景: chooses where the console's backdrop comes from — the built-in picture, a custom picture picked
 * through the system document picker, or the flat brand background; a "背景模糊" slider tunes the backdrop frosting.
 *
 * <p>The chosen mode is the {@code console_bg_mode} preference and the picture's URI is {@code console_bg_uri}; both are
 * read by {@link Wallpaper}, so this page never draws the backdrop itself. The picture grant is persisted with
 * {@code takePersistableUriPermission}, so it survives restarts.
 *
 * <p>Instance API for the settings host (SettingsPage), same contract as {@link SettingsExportView}:
 * <pre>
 *   BackgroundPickerView picker = BackgroundPickerView.create(shell);
 *   picker.setListener(shell::refreshBackground); // optional: re-apply the live console backdrop after a change
 *   openPage(T.t("card.console_bg"), picker.view(), picker::dispose);
 *   // SettingsPage.onActivityResult: if (picker != null &amp;&amp; picker.onActivityResult(request, result, data)) return;
 * </pre>
 * The listener fires on the UI thread after a mode switch, a picked image or a reset. The host either re-applies
 * {@link Wallpaper#behind(Activity, android.widget.FrameLayout)} on its own background frame (it replaces the previous
 * layers, so it is safe to call again) or rebuilds the console; without a listener the new choice simply shows up the
 * next time the console is built.
 *
 * <p>The "背景模糊" slider persists {@code console_bg_blur} (0..24dp) and, through the host listener, re-applies the
 * console backdrop live while dragged, so the frosting change is visible immediately.
 */
public final class BackgroundPickerView {
    /** Request code for {@link Intent#ACTION_OPEN_DOCUMENT}, kept unique among the settings pages' pickers. */
    public static final int REQ_PICK_IMAGE = 73;

    /** Told (on the UI thread) that the chosen backdrop may have changed. */
    public interface Listener {
        void onBackgroundChanged();
    }

    /** Parallel to the mode rows and to {@code Wallpaper.MODE_*}. */
    private static final String[] MODES = {
            Wallpaper.MODE_BUILTIN, Wallpaper.MODE_CUSTOM, Wallpaper.MODE_OFF
    };

    private final Activity host;
    private final LinearLayout root;
    private final String[] labels = new String[MODES.length];
    private final LinearLayout[] rows = new LinearLayout[MODES.length];
    private final TextView[] checks = new TextView[MODES.length];
    private final TextView hint;
    private Listener listener;
    private boolean disposed;

    /** Builds the page for one settings host. */
    public static BackgroundPickerView create(Activity host) {
        return new BackgroundPickerView(host);
    }

    private BackgroundPickerView(Activity host) {
        this.host = host;
        root = Ui.column(host);

        LinearLayout card = Ui.card(host, T.t("card.console_bg"));
        card.addView(Ui.text(host, T.t("bg.desc"), 12, Ui.DIM, false));
        card.addView(Ui.gap(host, 6));

        labels[0] = T.t("bg.mode_builtin");
        labels[1] = T.t("bg.mode_custom");
        labels[2] = T.t("bg.mode_off");
        for (int i = 0; i < MODES.length; i++) {
            rows[i] = modeRow(i);
            card.addView(rows[i]);
            if (i < MODES.length - 1)
                card.addView(Ui.divider(host));
        }

        hint = Ui.text(host, "", 11, Ui.FAINT, false);
        card.addView(Ui.gap(host, 4));
        card.addView(hint);

        // Live backdrop blur: dragging re-applies the console background (host listener) so the change shows at once.
        card.addView(Ui.slider(host, T.t("bg.blur"), Wallpaper.blurDp(host), 24, T.t("bg.dp"),
                dp -> {
                    Wallpaper.setBlur(host, dp);
                    changed();
                }));

        card.addView(Ui.gap(host, 4));
        card.addView(Ui.button(host, T.t("bg.reset"), false, v -> reset()));

        root.addView(card);
        update();
    }

    /** The page body: a vertical stack of cards, ready to drop into the host's scrollable sheet. */
    public View view() {
        return root;
    }

    /** Optional: told after every change so the host can refresh the console backdrop immediately. */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Call from the host's onActivityResult first; true means this view consumed the result. */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK_IMAGE)
            return false;
        if (disposed)
            return true;
        Uri uri = resultCode == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (uri == null)
            return true; // cancelled, or a provider that returned no document: keep the current backdrop
        takeGrant(uri, data);
        Wallpaper.setCustom(host, uri);
        Wallpaper.setMode(host, Wallpaper.MODE_CUSTOM);
        update();
        changed();
        return true;
    }

    /** The settings sheet is closing: stop touching the host (a late document result is dropped). */
    public void dispose() {
        disposed = true;
        listener = null;
    }

    // ---- rows ----------------------------------------------------------------------------------------------------

    /** One radio-style row: the mode label on the left, ✓ on the right. The custom row also keeps its "选择图片…" action. */
    private LinearLayout modeRow(int index) {
        LinearLayout row = Ui.row(host);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setPadding(0, Ui.dp(host, 7), 0, Ui.dp(host, 7));
        row.setMinimumHeight(Ui.dp(host, 48));

        LinearLayout texts = Ui.column(host);
        TextView label = Ui.text(host, labels[index], 14, Ui.TEXT, false);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        texts.addView(label);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // The custom-image row keeps its "选择图片…" action on the same line as the radio.
        if (index == customIndex()) {
            TextView pick = Ui.button(host, T.t("bg.pick"), false, v -> pickImage());
            pick.setTextSize(13);
            row.addView(pick);
            row.addView(Ui.space(host, 8));
        }

        checks[index] = Ui.text(host, "", 14, Ui.ACCENT, true);
        checks[index].setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(checks[index]);
        row.setOnClickListener(v -> choose(index));
        row.setClickable(true);
        return row;
    }

    /** Index of the custom-image mode in {@link #MODES} ({@code BUILTIN, CUSTOM, OFF}). */
    private static int customIndex() {
        return 1;
    }

    /** Redraws the ✓ marks from the stored prefs. */
    private void update() {
        String current = Wallpaper.mode(host);
        for (int i = 0; i < MODES.length; i++) {
            boolean selected = MODES[i].equals(current);
            checks[i].setText(selected ? "✓" : "");
            checks[i].setTextColor(selected ? Ui.ACCENT : Ui.FAINT);
            String description = selected ? labels[i] + "，" + T.t("state.on") : labels[i];
            rows[i].setContentDescription(description);
        }
        hint.setText("");
        hint.setVisibility(View.GONE);
    }

    /** Saves the mode; picking "自定义图片" with no picture yet goes straight to the document picker. */
    private void choose(int index) {
        if (disposed)
            return;
        Wallpaper.setMode(host, MODES[index]);
        update();
        changed();
        if (Wallpaper.MODE_CUSTOM.equals(MODES[index]) && !Wallpaper.hasCustom(host))
            pickImage();
    }

    // ---- actions -------------------------------------------------------------------------------------------------

    private void pickImage() {
        if (disposed)
            return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*");
        try {
            host.startActivityForResult(intent, REQ_PICK_IMAGE);
        } catch (RuntimeException ignored) {
            // no document picker on this ROM: the wallpaper and solid modes still work
        }
    }

    /** 「恢复默认」: forget the picture (and its grant) and go back to the built-in backdrop. */
    private void reset() {
        if (disposed)
            return;
        Wallpaper.clearCustom(host);
        Wallpaper.setMode(host, Wallpaper.MODE_BUILTIN);
        update();
        changed();
    }

    /** Persists the read grant so the picture survives restarts; falls back to the session grant if the provider can. */
    private void takeGrant(Uri uri, Intent data) {
        try {
            host.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException first) {
            int granted = data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (granted == 0)
                return; // only a session grant: the picture still works until the app restarts
            try {
                host.getContentResolver().takePersistableUriPermission(uri, granted);
            } catch (RuntimeException ignored) {
                // provider without persistable grants: keep the session grant
            }
        }
    }

    private void changed() {
        if (!disposed && listener != null)
            listener.onBackgroundChanged();
    }
}
