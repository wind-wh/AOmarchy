package app.aomarchy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import app.aomarchy.i18n.T;
import app.aomarchy.ui.Ui;

/**
 * 设置 → 键盘与按键: the native replacement for the old WebView-free "键盘与按键" category page. Same preferences
 * (虚拟键盘 / 无障碍按键拦截 / 额外按键栏与布局 / 通知 / 返回键与音量键动作), rebuilt with the console's UI kit.
 *
 * Contract for the settings host (SettingsPage): {@link #build(Activity)} returns the page body only — a vertical
 * stack of {@code Ui.card(...)} cards, no ScrollView, no title bar. The host puts it inside a scrollable sheet with a
 * back button. Every change is saved immediately to the {@code Prefs.NAME} SharedPreferences; the host re-reads the
 * prefs when the sheet closes.
 */
public final class KeysSettingsView {
    // Same keys the old SettingsActivity category page and MainActivity use; do not rename.
    private static final String KEY_BOUND_KEYCODE = "bound_keycode";
    private static final String KEY_KEYBOARD_FLOATING = "keyboard_floating";
    private static final String KEY_ACCESSIBILITY = "accessibility_key_intercept";
    private static final String KEY_EXTRA_KEYS = "extra_keys_bar";
    private static final String KEY_AUTO_SHOW_EXTRA_KEYS = "auto_show_extra_keys";
    private static final String KEY_EXTRA_KEYS_LAYOUT = "extra_keys_layout";
    private static final String KEY_NOTIFICATION = "settings_notification";

    /** Matches SettingsActivity.UNBOUND: "no key bound". */
    private static final int UNBOUND = -1;

    private KeysSettingsView() {
    }

    /** Builds the page. Never returns null. */
    public static View build(Activity host) {
        LinearLayout body = Ui.column(host);
        body.addView(virtualKeyboardCard(host));
        body.addView(accessibilityCard(host));
        body.addView(extraKeysCard(host));
        body.addView(notificationCard(host));
        body.addView(userActionsCard(host));
        return body;
    }

    // ============================================================
    // 虚拟键盘
    // ============================================================

    private static LinearLayout virtualKeyboardCard(Activity host) {
        LinearLayout card = Ui.card(host, T.t("card.virtual_keyboard"));
        card.addView(Ui.text(host, T.t("keys.vk_desc"), 12, Ui.DIM, false));
        card.addView(Ui.gap(host, 4));

        Row bind = row(host, T.t("label.bind_key"), T.t("keys.bind_key_caption"));
        bind.setValue(boundKeyLabel(host));
        bind.view.setOnClickListener(v -> bindKeyDialog(host, () -> bind.setValue(boundKeyLabel(host))));
        card.addView(bind.view);

        card.addView(divider(host));

        final boolean floating = prefs(host).getBoolean(KEY_KEYBOARD_FLOATING, true);
        card.addView(toggleRow(host, T.t("label.floating_keyboard"),
                T.t("keys.floating_caption"), floating, on -> {
                    prefs(host).edit().putBoolean(KEY_KEYBOARD_FLOATING, on).apply();
                    toast(host, on ? T.t("toast.vk_floating") : T.t("toast.vk_resize"));
                }));
        return card;
    }

    private static String boundKeyLabel(Activity host) {
        int code = prefs(host).getInt(KEY_BOUND_KEYCODE, UNBOUND);
        return code == UNBOUND ? T.t("state.unbound") : keyCodeName(code);
    }

    private static String keyCodeName(int code) {
        if (code == UNBOUND) return T.t("state.unbound");
        String name = KeyEvent.keyCodeToString(code);
        if (name == null || name.isEmpty() || "KEYCODE_UNKNOWN".equals(name)) return T.t("state.key_n", code);
        return name;
    }

    /** Waits for one physical key press and stores it as the virtual-keyboard toggle. Back cancels. */
    private static void bindKeyDialog(Activity host, Runnable onBound) {
        AlertDialog dialog = new AlertDialog.Builder(host)
                .setTitle(T.t("dialog.bind_key_title"))
                .setMessage(T.t("dialog.bind_key_message"))
                .setNegativeButton(T.t("btn.cancel"), null)
                .setNeutralButton(T.t("btn.unbind"), null)
                .create();
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK) return false; // let the dialog close
            if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return true; // placeholder of the virtual keyboard itself
            prefs(host).edit().putInt(KEY_BOUND_KEYCODE, keyCode).apply();
            d.dismiss();
            onBound.run();
            toast(host, T.t("toast.bound", keyCodeName(keyCode)));
            return true;
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            prefs(host).edit().putInt(KEY_BOUND_KEYCODE, UNBOUND).apply();
            dialog.dismiss();
            onBound.run();
            toast(host, T.t("toast.unbound"));
        });
    }

    // ============================================================
    // 无障碍按键拦截
    // ============================================================

    private static LinearLayout accessibilityCard(Activity host) {
        LinearLayout card = Ui.card(host, T.t("card.accessibility"));
        card.addView(Ui.text(host, T.t("keys.accessibility_desc"), 12, Ui.DIM, false));
        card.addView(Ui.gap(host, 4));

        final boolean enabled = prefs(host).getBoolean(KEY_ACCESSIBILITY, true);
        card.addView(toggleRow(host, T.t("label.intercept_keys"),
                T.t("keys.intercept_caption"), enabled, on -> {
                    prefs(host).edit().putBoolean(KEY_ACCESSIBILITY, on).apply();
                    if (on) {
                        KeyInterceptor.launch(host);
                        toast(host, T.t("toast.intercept_on"));
                    } else {
                        KeyInterceptor.shutdown(false);
                        toast(host, T.t("toast.intercept_off"));
                    }
                }));

        card.addView(divider(host));
        card.addView(actionRow(host, T.t("keys.open_accessibility"),
                T.t("keys.open_accessibility_caption"), () -> openAccessibilitySettings(host)));
        return card;
    }

    private static void openAccessibilitySettings(Activity host) {
        try {
            host.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            toast(host, T.t("toast.find_ao"));
        } catch (ActivityNotFoundException e) {
            toast(host, T.t("toast.no_accessibility_settings"));
        }
    }

    // ============================================================
    // 额外按键栏
    // ============================================================

    private static LinearLayout extraKeysCard(Activity host) {
        LinearLayout card = Ui.card(host, T.t("card.extra_keys"));
        card.addView(Ui.text(host, T.t("keys.extra_desc"), 12, Ui.DIM, false));
        card.addView(Ui.gap(host, 4));

        final boolean bar = prefs(host).getBoolean(KEY_EXTRA_KEYS, false);
        card.addView(toggleRow(host, T.t("keys.show_bar"),
                T.t("keys.show_bar_caption"), bar, on -> {
                    prefs(host).edit().putBoolean(KEY_EXTRA_KEYS, on).apply();
                    toast(host, T.t("toast.takes_effect_in_session"));
                }));

        card.addView(divider(host));

        final boolean autoShow = prefs(host).getBoolean(KEY_AUTO_SHOW_EXTRA_KEYS, true);
        card.addView(toggleRow(host, T.t("keys.follow_ime"),
                T.t("keys.follow_ime_caption"), autoShow, on -> {
                    prefs(host).edit().putBoolean(KEY_AUTO_SHOW_EXTRA_KEYS, on).apply();
                    toast(host, T.t("toast.takes_effect_in_session"));
                }));

        card.addView(divider(host));

        Row layout = row(host, T.t("keys.custom_layout"), T.t("keys.custom_layout_caption"));
        layout.setValue(extraLayoutState(host));
        layout.view.setOnClickListener(v -> editLayoutDialog(host, () -> layout.setValue(extraLayoutState(host))));
        card.addView(layout.view);

        card.addView(divider(host));
        card.addView(actionRow(host, T.t("keys.reset_layout"), T.t("keys.reset_layout_caption"),
                () -> resetLayoutDialog(host, () -> layout.setValue(extraLayoutState(host)))));
        return card;
    }

    /** "默认" when nothing (or exactly the built-in template) is stored, otherwise 自定义 / 自定义 · 无效. */
    private static String extraLayoutState(Activity host) {
        String saved = prefs(host).getString(KEY_EXTRA_KEYS_LAYOUT, "");
        String def = ExtraKeysBar.defaultLayoutJson();
        if (saved == null || saved.trim().isEmpty() || saved.trim().equals(def.trim())) return T.t("state.default");
        return ExtraKeysBar.validateLayout(saved) == null ? T.t("state.custom") : T.t("state.custom_invalid");
    }

    private static void editLayoutDialog(Activity host, Runnable onSaved) {
        String stored = prefs(host).getString(KEY_EXTRA_KEYS_LAYOUT, "");
        String initial = stored == null || stored.trim().isEmpty() ? ExtraKeysBar.defaultLayoutJson() : stored;

        EditText input = new EditText(host);
        input.setText(initial);
        input.setSelection(input.getText().length());
        input.setTextSize(12);
        input.setTypeface(Typeface.MONOSPACE);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHorizontallyScrolling(false);
        input.setMinLines(6);
        input.setContentDescription(T.t("keys.custom_layout"));

        LinearLayout box = Ui.column(host);
        box.setPadding(Ui.dp(host, 18), Ui.dp(host, 6), Ui.dp(host, 18), 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView status = Ui.text(host, "", 12, Ui.DIM, false);
        status.setPadding(0, Ui.dp(host, 6), 0, 0);
        box.addView(status);
        updateLayoutStatus(status, initial);

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override public void afterTextChanged(Editable s) {
                updateLayoutStatus(status, s.toString());
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(host)
                .setTitle(T.t("dialog.custom_layout_title"))
                .setView(box)
                .setNegativeButton(T.t("btn.cancel"), null)
                .setPositiveButton(T.t("btn.save"), null)
                .setNeutralButton(T.t("btn.reset_default"), null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String json = input.getText().toString().trim();
            if (!json.isEmpty() && ExtraKeysBar.validateLayout(json) != null) {
                updateLayoutStatus(status, json); // keep the dialog open and show why
                return;
            }
            // Empty means "use the built-in default", which is what the bar already falls back to.
            prefs(host).edit().putString(KEY_EXTRA_KEYS_LAYOUT, json).apply();
            dialog.dismiss();
            onSaved.run();
            toast(host, T.t("toast.saved_session"));
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            prefs(host).edit().putString(KEY_EXTRA_KEYS_LAYOUT, "").apply();
            dialog.dismiss();
            onSaved.run();
            toast(host, T.t("toast.layout_reset"));
        });
    }

    private static void updateLayoutStatus(TextView status, String json) {
        String text = json == null ? "" : json.trim();
        if (text.isEmpty()) {
            status.setText(T.t("keys.layout_empty_hint"));
            status.setTextColor(Ui.DIM);
            return;
        }
        String error = ExtraKeysBar.validateLayout(text);
        if (error == null) {
            status.setText(T.t("keys.layout_valid"));
            status.setTextColor(Ui.MINT);
        } else {
            status.setText("✗ " + error);
            status.setTextColor(Ui.WARN);
        }
    }

    private static void resetLayoutDialog(Activity host, Runnable onReset) {
        new AlertDialog.Builder(host)
                .setTitle(T.t("dialog.reset_layout_title"))
                .setMessage(T.t("dialog.reset_layout_message"))
                .setNegativeButton(T.t("btn.cancel"), null)
                .setPositiveButton(T.t("btn.reset_default"), (d, w) -> {
                    prefs(host).edit().putString(KEY_EXTRA_KEYS_LAYOUT, "").apply();
                    onReset.run();
                    toast(host, T.t("toast.layout_reset"));
                })
                .show();
    }

    // ============================================================
    // 通知
    // ============================================================

    private static LinearLayout notificationCard(Activity host) {
        LinearLayout card = Ui.card(host, T.t("card.notifications"));
        card.addView(Ui.text(host, T.t("keys.notification_desc"), 12, Ui.DIM, false));
        card.addView(Ui.gap(host, 4));

        final boolean show = prefs(host).getBoolean(KEY_NOTIFICATION, true);
        card.addView(toggleRow(host, T.t("keys.show_notification"),
                T.t("keys.show_notification_caption"), show, on -> {
                    prefs(host).edit().putBoolean(KEY_NOTIFICATION, on).apply();
                    toast(host, T.t("toast.takes_effect_in_app"));
                }));
        return card;
    }

    // ============================================================
    // 按键动作
    // ============================================================

    private static LinearLayout userActionsCard(Activity host) {
        LinearLayout card = Ui.card(host, T.t("card.key_actions"));
        card.addView(Ui.text(host, T.t("keys.actions_desc"), 12, Ui.DIM, false));
        card.addView(Ui.gap(host, 4));

        String[][] actions = {
                {T.t("action.back_button"), UserActions.BACK_BUTTON},
                {T.t("action.volume_up"), UserActions.VOLUME_UP},
                {T.t("action.volume_down"), UserActions.VOLUME_DOWN},
                {T.t("action.media_keys"), UserActions.MEDIA_KEYS},
                {T.t("action.notification_tap"), UserActions.NOTIFICATION_TAP},
                {T.t("action.notification_first"), UserActions.NOTIFICATION_FIRST_BUTTON},
                {T.t("action.notification_second"), UserActions.NOTIFICATION_SECOND_BUTTON},
        };
        for (int i = 0; i < actions.length; i++) {
            final String label = actions[i][0];
            final String key = actions[i][1];
            Row actionRow = row(host, label, null);
            actionRow.setValue(responseLabel(UserActions.getResponse(prefs(host), key)));
            actionRow.view.setOnClickListener(v -> pickAction(host, label, key, actionRow));
            card.addView(actionRow.view);
            if (i < actions.length - 1) card.addView(divider(host));
        }
        return card;
    }

    private static void pickAction(Activity host, String label, String key, Row row) {
        final String[] responses = UserActions.responsesFor(key);
        final String[] names = new String[responses.length];
        for (int i = 0; i < responses.length; i++) names[i] = responseLabel(responses[i]);

        String current = UserActions.getResponse(prefs(host), key);
        int checked = 0;
        for (int i = 0; i < responses.length; i++) {
            if (responses[i].equals(current)) {
                checked = i;
                break;
            }
        }

        new AlertDialog.Builder(host)
                .setTitle(label)
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    prefs(host).edit().putString(key, responses[which]).apply();
                    row.setValue(names[which]);
                    d.dismiss();
                    toast(host, label + " → " + names[which]);
                })
                .setNegativeButton(T.t("btn.cancel"), null)
                .show();
    }

    /** Label for one UserActions response value; unknown values from prefs are shown verbatim. */
    private static String responseLabel(String response) {
        if (response == null || response.isEmpty()) return T.t("resp.none");
        switch (response) {
            case UserActions.NO_ACTION:
                return T.t("resp.none");
            case UserActions.TOGGLE_SOFT_KEYBOARD:
                return T.t("resp.toggle_keyboard");
            case UserActions.TOGGLE_ADDITIONAL_KEY_BAR:
                return T.t("resp.toggle_extra_bar");
            case UserActions.OPEN_PREFERENCES:
                return T.t("resp.open_preferences");
            case UserActions.RELEASE_POINTER_AND_KEYBOARD_CAPTURE:
                return T.t("resp.release_capture");
            case UserActions.RESTART_ACTIVITY:
                return T.t("resp.restart_ui");
            case UserActions.EXIT:
                return T.t("resp.exit");
            case UserActions.TOGGLE_TOUCHPAD_MODE:
                return T.t("resp.toggle_touchpad");
            case UserActions.TOGGLE_SCREEN_ORIENTATION:
                return T.t("resp.toggle_orientation");
            case UserActions.SEND_VOLUME_UP:
                return T.t("resp.volume_up");
            case UserActions.SEND_VOLUME_DOWN:
                return T.t("resp.volume_down");
            case UserActions.SEND_MEDIA_ACTION:
                return T.t("resp.media_action");
            default:
                return response;
        }
    }

    // ============================================================
    // Shared row / helpers
    // ============================================================

    /** A {@link Ui#actionRow} whose value can be rewritten after the user picks something in its dialog. */
    private static final class Row {
        final LinearLayout view;
        final TextView value;
        private final String label;
        private final String caption;

        Row(LinearLayout view, TextView value, String label, String caption) {
            this.view = view;
            this.value = value;
            this.label = label;
            this.caption = caption;
            describe();
        }

        void setValue(String text) {
            value.setText(text == null ? "" : text);
            describe();
        }

        /** The whole row is one TalkBack stop: label + caption + current value (children are hidden). */
        private void describe() {
            String base = caption == null || caption.isEmpty() ? label : label + "，" + caption;
            String current = value.getText() == null ? "" : value.getText().toString().trim();
            view.setContentDescription(current.isEmpty() ? base : base + "，" + current);
        }
    }

    private static Row row(Activity host, String label, String caption) {
        LinearLayout r = Ui.row(host);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        r.setPadding(0, Ui.dp(host, 7), 0, Ui.dp(host, 7));

        LinearLayout texts = Ui.column(host);
        texts.addView(Ui.text(host, label, 14, Ui.TEXT, false));
        if (caption != null && !caption.isEmpty())
            texts.addView(Ui.text(host, caption, 11, Ui.FAINT, false));
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = Ui.row(host);
        TextView value = Ui.text(host, "", 13, Ui.DIM, false);
        right.addView(value);
        right.addView(Ui.space(host, 6));
        right.addView(Ui.text(host, "›", 15, Ui.FAINT, false));
        r.addView(right);

        // TalkBack: one stop per row, 48dp minimum touch height; the description carries label/caption/value.
        r.setMinimumHeight(Ui.dp(host, 48));
        texts.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        right.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        r.setClickable(true);
        return new Row(r, value, label, caption);
    }

    /** Decorative separator: never a TalkBack stop. */
    private static View divider(Activity host) {
        View v = Ui.divider(host);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    /** A {@link Ui#actionRow} as one TalkBack stop, with the 48dp minimum touch height. */
    private static LinearLayout actionRow(Activity host, String label, String caption, Runnable onClick) {
        LinearLayout r = Ui.actionRow(host, label, caption, null, onClick);
        r.setMinimumHeight(Ui.dp(host, 48));
        for (int i = 0; i < r.getChildCount(); i++)
            r.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        r.setContentDescription(caption == null || caption.isEmpty() ? label : label + "，" + caption);
        return r;
    }

    private static SharedPreferences prefs(Activity host) {
        return host.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
    }

    /**
     * The {@code Ui.toggleRow} shape with a localized On/Off pill: {@code Ui.toggleRow} hardcodes the Chinese
     * 开启/关闭 labels and Ui.java is outside this task's write scope.
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

        // TalkBack: one stop per row announcing the switch and its state, at least 48dp tall; the visible texts
        // are decorative because the state lives in the description (and changes with it).
        final String base = caption == null || caption.isEmpty() ? label : label + "，" + caption;
        r.setMinimumHeight(Ui.dp(host, 48));
        texts.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        pill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        r.setContentDescription(base + "，" + stateLabel(on[0]));
        r.setOnClickListener(v -> {
            on[0] = !on[0];
            pill.setText(stateLabel(on[0]));
            pill.setTextColor(on[0] ? Ui.ACCENT : Ui.DIM);
            r.setContentDescription(base + "，" + stateLabel(on[0]));
            onChange.onToggle(on[0]);
        });
        r.setClickable(true);
        return r;
    }

    private static String stateLabel(boolean on) {
        return T.t(on ? "state.on" : "state.off");
    }

    private static void toast(Activity host, String text) {
        Toast.makeText(host, text, Toast.LENGTH_SHORT).show();
    }
}
