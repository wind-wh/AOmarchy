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
 * 设置 → 控制台背景: chooses where the console's backdrop comes from — the system wallpaper, a custom picture picked
 * through the system document picker, or the flat brand background.
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
 * <p>When the ROM refuses to hand the wallpaper over ({@link Wallpaper#systemWallpaperAvailable(Activity)}), the page
 * keeps the same three-way choice but warns under the "系统壁纸" row and offers the custom image as the working
 * alternative, so a user does not sit on a mode that can only show the brand gradient.
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
            Wallpaper.MODE_BUILTIN, Wallpaper.MODE_LINUX, Wallpaper.MODE_CUSTOM, Wallpaper.MODE_OFF
    };

    private final Activity host;
    private final LinearLayout root;
    private final String[] labels = new String[MODES.length];
    private final LinearLayout[] rows = new LinearLayout[MODES.length];
    private final TextView[] checks = new TextView[MODES.length];
    private final TextView hint;
    /** Warning under the "系统壁纸" row while the ROM refuses to hand the wallpaper over. */
    private TextView systemWarn;
    /** Top-of-page shortcut to a custom image, shown for the same reason. */
    private TextView customShortcut;
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
        // When the wallpaper cannot be read, the top description points straight at the working alternative: the
        // same "›" link affordance the rest of the console uses.
        customShortcut = Ui.text(host, T.t("bg.mode_custom") + " ›", 12, Ui.ACCENT, true);
        customShortcut.setPadding(0, Ui.dp(host, 6), 0, Ui.dp(host, 6));
        customShortcut.setMinimumHeight(Ui.dp(host, 44));
        customShortcut.setVisibility(View.GONE);
        customShortcut.setOnClickListener(v -> pickCustomFromShortcut());
        card.addView(customShortcut);
        card.addView(Ui.gap(host, 6));

        labels[0] = T.t("bg.mode_builtin");
        labels[1] = T.t("bg.mode_system");
        labels[2] = T.t("bg.mode_custom");
        labels[3] = T.t("bg.mode_off");
        for (int i = 0; i < MODES.length; i++) {
            rows[i] = modeRow(i);
            card.addView(rows[i]);
            if (i < MODES.length - 1)
                card.addView(Ui.divider(host));
        }

        hint = Ui.text(host, "", 11, Ui.FAINT, false);
        card.addView(Ui.gap(host, 4));
        card.addView(hint);

        card.addView(Ui.gap(host, 8));
        card.addView(Ui.button(host, T.t("bg.pick"), true, v -> pickImage()));
        card.addView(Ui.gap(host, 6));
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

    /** One radio-style row: the mode label on the left (plus the wallpaper warning for the system row), ✓ on the right. */
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
        if (index == 0) {
            // MIUI/Android 13 refuse the wallpaper: say whether the Linux desktop wallpaper is standing in for it.
            boolean fromLinux = app.aomarchy.ui.Wallpaper.lastSourceWasLinux(host);
            systemWarn = Ui.text(host, T.t(fromLinux ? "bg.linux_used" : "bg.system_unavailable"), 11,
                    fromLinux ? Ui.DIM : Ui.WARN, false);
            systemWarn.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            systemWarn.setVisibility(View.GONE);
            texts.addView(systemWarn);
        }
        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        checks[index] = Ui.text(host, "", 14, Ui.ACCENT, true);
        checks[index].setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(checks[index]);
        row.setOnClickListener(v -> choose(index));
        row.setClickable(true);
        return row;
    }

    /** Redraws the ✓ marks, the wallpaper warning and the mode hint from the stored prefs. */
    private void update() {
        String current = Wallpaper.mode(host);
        boolean wallpaperAvailable = Wallpaper.systemWallpaperAvailable(host);
        for (int i = 0; i < MODES.length; i++) {
            boolean selected = MODES[i].equals(current);
            checks[i].setText(selected ? "✓" : "");
            checks[i].setTextColor(selected ? Ui.ACCENT : Ui.FAINT);
            String description = selected ? labels[i] + "，" + T.t("state.on") : labels[i];
            if (i == 1 && !wallpaperAvailable)
                description += "，" + T.t(app.aomarchy.ui.Wallpaper.lastSourceWasLinux(host)
                        ? "bg.linux_used" : "bg.system_unavailable");
            rows[i].setContentDescription(description);
        }
        // The three-way choice keeps working as before; the warning only explains why "系统壁纸" may not show a photo.
        systemWarn.setVisibility(wallpaperAvailable ? View.GONE : View.VISIBLE);
        customShortcut.setVisibility(wallpaperAvailable ? View.GONE : View.VISIBLE);
        boolean system = Wallpaper.MODE_LINUX.equals(current);
        // The "falls back to the brand gradient" note only helps while the wallpaper can be read; when it cannot, the
        // warning below the system row says it, and the shortcut points at a custom image instead.
        hint.setText(system && wallpaperAvailable ? T.t("bg.restricted") : "");
        hint.setVisibility(system && wallpaperAvailable ? View.VISIBLE : View.GONE);
    }

    /** Saves the mode; picking "自定义图片" with no picture yet goes straight to the document picker. */
    private void choose(int index) {
        if (disposed)
            return;
        Wallpaper.setMode(host, MODES[index]);
        update();
        changed();
        if (index == 1 && !Wallpaper.hasCustom(host))
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

    /** Top-of-page shortcut for the "wallpaper unreadable" case: switch to a custom image and open the picker. */
    private void pickCustomFromShortcut() {
        if (disposed)
            return;
        Wallpaper.setMode(host, Wallpaper.MODE_CUSTOM);
        update();
        changed();
        pickImage();
    }

    /** 「恢复默认」: forget the picture (and its grant) and go back to the system wallpaper. */
    private void reset() {
        if (disposed)
            return;
        Wallpaper.clearCustom(host);
        Wallpaper.setMode(host, Wallpaper.MODE_SYSTEM);
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
