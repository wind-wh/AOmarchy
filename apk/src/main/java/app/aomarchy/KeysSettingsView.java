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
        LinearLayout card = Ui.card(host, "虚拟键盘");
        card.addView(Ui.text(host,
                "给虚拟键盘绑一个物理按键：按一下呼出或收起。虚拟键盘也可以浮在会话桌面之上，不挤占桌面。",
                12, Ui.DIM, false));
        card.addView(Ui.gap(host, 4));

        Row bind = row(host, "绑定按键", "按一下要绑定的物理按键；按返回键取消");
        bind.setValue(boundKeyLabel(host));
        bind.view.setOnClickListener(v -> bindKeyDialog(host, () -> bind.setValue(boundKeyLabel(host))));
        card.addView(bind.view);

        card.addView(Ui.divider(host));

        final boolean floating = prefs(host).getBoolean(KEY_KEYBOARD_FLOATING, true);
        card.addView(Ui.toggleRow(host, "浮动虚拟键盘",
                "关闭时会话桌面缩小并上移，给软键盘让出空间", floating, on -> {
                    prefs(host).edit().putBoolean(KEY_KEYBOARD_FLOATING, on).apply();
                    toast(host, on ? "虚拟键盘将浮在桌面之上" : "已改为缩小桌面：软键盘不再遮挡内容");
                }));
        return card;
    }

    private static String boundKeyLabel(Activity host) {
        int code = prefs(host).getInt(KEY_BOUND_KEYCODE, UNBOUND);
        return code == UNBOUND ? "未绑定" : keyCodeName(code);
    }

    private static String keyCodeName(int code) {
        if (code == UNBOUND) return "未绑定";
        String name = KeyEvent.keyCodeToString(code);
        if (name == null || name.isEmpty() || "KEYCODE_UNKNOWN".equals(name)) return "按键 " + code;
        return name;
    }

    /** Waits for one physical key press and stores it as the virtual-keyboard toggle. Back cancels. */
    private static void bindKeyDialog(Activity host, Runnable onBound) {
        AlertDialog dialog = new AlertDialog.Builder(host)
                .setTitle("绑定虚拟键盘按键")
                .setMessage("按下要绑定的物理按键。绑定后按它就能呼出或收起虚拟键盘；按返回键取消。")
                .setNegativeButton("取消", null)
                .setNeutralButton("解除绑定", null)
                .create();
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK) return false; // let the dialog close
            if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return true; // placeholder of the virtual keyboard itself
            prefs(host).edit().putInt(KEY_BOUND_KEYCODE, keyCode).apply();
            d.dismiss();
            onBound.run();
            toast(host, "已绑定 " + keyCodeName(keyCode));
            return true;
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            prefs(host).edit().putInt(KEY_BOUND_KEYCODE, UNBOUND).apply();
            dialog.dismiss();
            onBound.run();
            toast(host, "已解除绑定");
        });
    }

    // ============================================================
    // 无障碍按键拦截
    // ============================================================

    private static LinearLayout accessibilityCard(Activity host) {
        LinearLayout card = Ui.card(host, "无障碍按键拦截");
        card.addView(Ui.text(host,
                "用无障碍服务拦截平板键盘的功能键，把 Fn、F1–F12 等转成桌面能识别的键码，并让返回键按 ESC 发给桌面。"
                        + "首次启用需要在系统设置里授予无障碍权限。",
                12, Ui.DIM, false));
        card.addView(Ui.gap(host, 4));

        final boolean enabled = prefs(host).getBoolean(KEY_ACCESSIBILITY, true);
        card.addView(Ui.toggleRow(host, "启用按键拦截",
                "关闭后功能键按系统默认处理，不再转换", enabled, on -> {
                    prefs(host).edit().putBoolean(KEY_ACCESSIBILITY, on).apply();
                    if (on) {
                        KeyInterceptor.launch(host);
                        toast(host, "已尝试启用；若没有生效，请到系统无障碍设置手动打开");
                    } else {
                        KeyInterceptor.shutdown(false);
                        toast(host, "已关闭按键拦截");
                    }
                }));

        card.addView(Ui.divider(host));
        card.addView(Ui.actionRow(host, "打开系统无障碍设置",
                "在列表里找到 AOmarchy 并开启，只需授权一次", null, () -> openAccessibilitySettings(host)));
        return card;
    }

    private static void openAccessibilitySettings(Activity host) {
        try {
            host.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            toast(host, "在列表里找到「AOmarchy」并开启");
        } catch (ActivityNotFoundException e) {
            toast(host, "系统没有无障碍设置页面");
        }
    }

    // ============================================================
    // 额外按键栏
    // ============================================================

    private static LinearLayout extraKeysCard(Activity host) {
        LinearLayout card = Ui.card(host, "额外按键栏");
        card.addView(Ui.text(host,
                "会话底部的特殊键栏：ESC / TAB / CTRL / ALT / 方向键 / PgUp 等，触屏上也能当键盘用。",
                12, Ui.DIM, false));
        card.addView(Ui.gap(host, 4));

        final boolean bar = prefs(host).getBoolean(KEY_EXTRA_KEYS, false);
        card.addView(Ui.toggleRow(host, "显示按键栏",
                "底部常驻一排特殊键；桌面显示区域会相应缩小", bar, on -> {
                    prefs(host).edit().putBoolean(KEY_EXTRA_KEYS, on).apply();
                    toast(host, "回到会话后生效");
                }));

        card.addView(Ui.divider(host));

        final boolean autoShow = prefs(host).getBoolean(KEY_AUTO_SHOW_EXTRA_KEYS, true);
        card.addView(Ui.toggleRow(host, "跟随软键盘",
                "开启后软键盘弹出时按键栏自动出现；关闭则只能手动切换", autoShow, on -> {
                    prefs(host).edit().putBoolean(KEY_AUTO_SHOW_EXTRA_KEYS, on).apply();
                    toast(host, "回到会话后生效");
                }));

        card.addView(Ui.divider(host));

        Row layout = row(host, "自定义布局", "JSON 的两行按键表；无效内容不会被保存");
        layout.setValue(extraLayoutState(host));
        layout.view.setOnClickListener(v -> editLayoutDialog(host, () -> layout.setValue(extraLayoutState(host))));
        card.addView(layout.view);

        card.addView(Ui.divider(host));
        card.addView(Ui.actionRow(host, "恢复默认布局", "重置为内置的两行模板", null,
                () -> resetLayoutDialog(host, () -> layout.setValue(extraLayoutState(host)))));
        return card;
    }

    /** "默认" when nothing (or exactly the built-in template) is stored, otherwise 自定义 / 自定义 · 无效. */
    private static String extraLayoutState(Activity host) {
        String saved = prefs(host).getString(KEY_EXTRA_KEYS_LAYOUT, "");
        String def = ExtraKeysBar.defaultLayoutJson();
        if (saved == null || saved.trim().isEmpty() || saved.trim().equals(def.trim())) return "默认";
        return ExtraKeysBar.validateLayout(saved) == null ? "自定义" : "自定义 · 无效";
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
                .setTitle("自定义按键布局")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .setNeutralButton("恢复默认", null)
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
            toast(host, "已保存，回到会话后生效");
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            prefs(host).edit().putString(KEY_EXTRA_KEYS_LAYOUT, "").apply();
            dialog.dismiss();
            onSaved.run();
            toast(host, "已恢复默认布局");
        });
    }

    private static void updateLayoutStatus(TextView status, String json) {
        String text = json == null ? "" : json.trim();
        if (text.isEmpty()) {
            status.setText("留空则使用内置默认布局");
            status.setTextColor(Ui.DIM);
            return;
        }
        String error = ExtraKeysBar.validateLayout(text);
        if (error == null) {
            status.setText("✓ 布局 JSON 有效，回到会话后生效");
            status.setTextColor(Ui.MINT);
        } else {
            status.setText("✗ " + error);
            status.setTextColor(Ui.WARN);
        }
    }

    private static void resetLayoutDialog(Activity host, Runnable onReset) {
        new AlertDialog.Builder(host)
                .setTitle("恢复默认布局")
                .setMessage("把额外按键栏重置为内置的两行模板？当前的自定义布局会被覆盖。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复默认", (d, w) -> {
                    prefs(host).edit().putString(KEY_EXTRA_KEYS_LAYOUT, "").apply();
                    onReset.run();
                    toast(host, "已恢复默认布局");
                })
                .show();
    }

    // ============================================================
    // 通知
    // ============================================================

    private static LinearLayout notificationCard(Activity host) {
        LinearLayout card = Ui.card(host, "通知");
        card.addView(Ui.text(host,
                "App 运行时在通知栏常驻一条状态通知；它的点按和两个按钮分别做什么，在下面「按键动作」里配置。",
                12, Ui.DIM, false));
        card.addView(Ui.gap(host, 4));

        final boolean show = prefs(host).getBoolean(KEY_NOTIFICATION, true);
        card.addView(Ui.toggleRow(host, "显示常驻通知",
                "关闭后不再显示；改动在下次回到 App 时生效", show, on -> {
                    prefs(host).edit().putBoolean(KEY_NOTIFICATION, on).apply();
                    toast(host, "回到 App 后生效");
                }));
        return card;
    }

    // ============================================================
    // 按键动作
    // ============================================================

    private static LinearLayout userActionsCard(Activity host) {
        LinearLayout card = Ui.card(host, "按键动作");
        card.addView(Ui.text(host, "这些按键或入口被触发时执行什么。改动立即生效。", 12, Ui.DIM, false));
        card.addView(Ui.gap(host, 4));

        String[][] actions = {
                {"返回键", UserActions.BACK_BUTTON},
                {"音量加键", UserActions.VOLUME_UP},
                {"音量减键", UserActions.VOLUME_DOWN},
                {"媒体键", UserActions.MEDIA_KEYS},
                {"通知点按", UserActions.NOTIFICATION_TAP},
                {"通知第一个按钮", UserActions.NOTIFICATION_FIRST_BUTTON},
                {"通知第二个按钮", UserActions.NOTIFICATION_SECOND_BUTTON},
        };
        for (int i = 0; i < actions.length; i++) {
            final String label = actions[i][0];
            final String key = actions[i][1];
            Row actionRow = row(host, label, null);
            actionRow.setValue(responseLabel(UserActions.getResponse(prefs(host), key)));
            actionRow.view.setOnClickListener(v -> pickAction(host, label, key, actionRow));
            card.addView(actionRow.view);
            if (i < actions.length - 1) card.addView(Ui.divider(host));
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
                .setNegativeButton("取消", null)
                .show();
    }

    /** Chinese label for one UserActions response value; unknown values from prefs are shown verbatim. */
    private static String responseLabel(String response) {
        if (response == null || response.isEmpty()) return "无动作";
        switch (response) {
            case UserActions.NO_ACTION:
                return "无动作";
            case UserActions.TOGGLE_SOFT_KEYBOARD:
                return "切换软键盘";
            case UserActions.TOGGLE_ADDITIONAL_KEY_BAR:
                return "切换额外按键栏";
            case UserActions.OPEN_PREFERENCES:
                return "打开设置";
            case UserActions.RELEASE_POINTER_AND_KEYBOARD_CAPTURE:
                return "释放指针与键盘捕获";
            case UserActions.RESTART_ACTIVITY:
                return "重启界面";
            case UserActions.EXIT:
                return "退出 App";
            case UserActions.TOGGLE_TOUCHPAD_MODE:
                return "切换触摸板模式";
            case UserActions.TOGGLE_SCREEN_ORIENTATION:
                return "切换屏幕方向";
            case UserActions.SEND_VOLUME_UP:
                return "发送音量加";
            case UserActions.SEND_VOLUME_DOWN:
                return "发送音量减";
            case UserActions.SEND_MEDIA_ACTION:
                return "发送媒体键";
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

        Row(LinearLayout view, TextView value) {
            this.view = view;
            this.value = value;
        }

        void setValue(String text) {
            value.setText(text == null ? "" : text);
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

        r.setClickable(true);
        return new Row(r, value);
    }

    private static SharedPreferences prefs(Activity host) {
        return host.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
    }

    private static void toast(Activity host, String text) {
        Toast.makeText(host, text, Toast.LENGTH_SHORT).show();
    }
}
