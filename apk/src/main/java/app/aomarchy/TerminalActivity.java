package app.aomarchy;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.aomarchy.i18n.T;
import app.aomarchy.ui.TerminalView;
import app.aomarchy.ui.Toasts;
import app.aomarchy.ui.Ui;

/**
 * Terminal mode: one pty with a login shell inside the rootfs, no compositor, no desktop. This is the low-resource way
 * to use the Linux side: measured on the tablet, a desktop session adds about 750 MB of system memory while the
 * terminal adds about 80 MB, and it starts in a couple of seconds instead of a dozen.
 */
public class TerminalActivity extends Activity implements TerminalView.Listener {
    /** True while this pty session is alive; SessionService.runningMode() uses it to tell terminal from desktop. */
    static volatile boolean sRunning;
    static {
        System.loadLibrary("aomarchy_consumer"); // the pty natives live in the same .so as the display consumer
    }

    private static final float MIN_FONT = 9f, MAX_FONT = 24f;

    /** Optional command to type automatically, e.g. the install line from the apps catalog. */
    public static final String EXTRA_COMMAND = "app.aomarchy.extra.COMMAND";
    private TerminalView terminal;
    private TerminalCommandsView commands;
    private TextView copyButton, scrollHint, panelButton;
    private boolean panelVisible = true;

    /** ui_lang also decides the resource Locale (see {@link T#wrap(Context)}); auto keeps the system Locale. */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(T.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        sRunning = true;
        Prefs.init(this);
        T.init(this); // the console's ui_lang choice applies to the terminal too
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Do not let the editor focus pull up the IME over the terminal: the 键盘 button or a tap shows it.
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        // Terminal 55% over the quick-command panel 45%. The top action bar and the bottom key strip stay overlays;
        // their height is reserved as padding here, so neither covers the terminal grid or the panel. Hiding the
        // panel leaves the terminal as the only weighted child, so it takes the whole height and the pty resizes
        // through TerminalView.onSizeChanged.
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, Ui.dp(this, 58), 0, 0);

        terminal = new TerminalView(this);
        terminal.setFontSp(getSharedPreferences(Prefs.NAME, MODE_PRIVATE).getFloat("term_font", 13f));
        terminal.setListener(this);
        content.addView(terminal, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.55f));

        commands = new TerminalCommandsView(this, this::sendCommand);
        content.addView(commands, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.45f));

        // content is added below together with the key strip, so the strip takes real space instead of covering the
        // panel (the strip grows with the system font size, so a fixed padding would overlap at large scales).

        // Keys a soft keyboard does not offer but a shell needs: Esc/Tab, the control letters, and the arrows.
        LinearLayout keys = new LinearLayout(this);
        keys.setOrientation(LinearLayout.HORIZONTAL);
        keys.setGravity(Gravity.CENTER_VERTICAL);
        keys.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        GradientDrawable keysBg = new GradientDrawable();
        keysBg.setColor(0x8C14171F);
        keysBg.setStroke(Ui.dp(this, 1), 0x4DFFFFFF);
        keysBg.setCornerRadius(Ui.dp(this, 12));
        keys.setBackground(keysBg);
        addKey(keys, "Esc", "\u001b");
        addKey(keys, "Tab", "\t");
        addKey(keys, "^C", "\u0003");
        addKey(keys, "^D", "\u0004");
        addKey(keys, "^Z", "\u001a");
        addKey(keys, "←", "\u001b[D");
        addKey(keys, "↑", "\u001b[A");
        addKey(keys, "↓", "\u001b[B");
        addKey(keys, "→", "\u001b[C");
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, 0, 0, Ui.dp(this, 6));
        column.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        klp.leftMargin = Ui.dp(this, 6);
        klp.rightMargin = Ui.dp(this, 6);
        column.addView(keys, klp);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(0x8C14171F);
        barBg.setStroke(Ui.dp(this, 1), 0x4DFFFFFF);
        barBg.setCornerRadius(Ui.dp(this, 14));
        bar.setBackground(barBg);

        TextView back = action(T.t("btn.back_console"), Ui.ACCENT, v -> finish());
        bar.addView(back);
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(action(T.t("btn.copy"), Ui.DIM, v -> copy()));
        bar.addView(action(T.t("btn.paste"), Ui.DIM, v -> paste()));
        bar.addView(action("A−", Ui.DIM, v -> changeFont(-1)));
        bar.addView(action("A+", Ui.DIM, v -> changeFont(1)));
        bar.addView(action(T.t("btn.keyboard"), Ui.DIM, v -> toggleKeyboard()));
        panelButton = action(TerminalPresets.text("btn.panel", "面板"), Ui.ACCENT, v -> togglePanel());
        bar.addView(panelButton);
        bar.addView(action(T.t("btn.restart"), Ui.DIM, v -> recreate()));
        copyButton = (TextView) bar.getChildAt(2); // the copy label, dimmed until something is selected

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 8);
        blp.leftMargin = Ui.dp(this, 8);
        blp.rightMargin = Ui.dp(this, 8);
        blp.gravity = Gravity.TOP;
        root.addView(bar, blp);

        // A pill that appears while the view is scrolled back: one tap returns to the live output.
        scrollHint = Ui.text(this, T.t("terminal.bottom"), 12, Ui.BG, true);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Ui.MINT);
        pill.setCornerRadius(Ui.dp(this, 12));
        scrollHint.setBackground(pill);
        scrollHint.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
        scrollHint.setOnClickListener(v -> terminal.scrollToBottom());
        scrollHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        hlp.bottomMargin = Ui.dp(this, 16);
        hlp.rightMargin = Ui.dp(this, 16);
        root.addView(scrollHint, hlp);

        setContentView(root);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }

        if (!SessionService.rootfsReady(this)) {
            terminal.writeLocal(T.t("terminal.no_system") + "\r\n");
        } else {
            terminal.start(SessionService.ptyArgv(this, "exec bash -l"), SessionService.ptyEnv(this),
                    SessionService.ptyCwd(this).getPath());
            String initial = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_COMMAND);
            if (initial != null && !initial.isEmpty())
                terminal.postDelayed(() -> terminal.write(initial + "\r"), 900); // let bash draw its prompt first
        }
        terminal.requestFocus();
        terminal.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null)
                imm.hideSoftInputFromWindow(terminal.getWindowToken(), 0);
        }, 300);
        // MIUI can keep its launch animation (a black frame) until the window reports a second frame: nudge it.
        terminal.postDelayed(() -> {
            terminal.invalidate();
            getWindow().getDecorView().invalidate();
            reportFullyDrawn();
        }, 400);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The activity keeps its views on rotation: re-apply the panel's 2-column / 3-column layout.
        if (commands != null)
            commands.updateColumns();
    }

    /** Shows or hides the quick-command panel; hidden, the terminal takes the whole height (pty resize follows). */
    private void togglePanel() {
        panelVisible = !panelVisible;
        if (commands != null)
            commands.setVisibility(panelVisible ? View.VISIBLE : View.GONE);
        if (panelButton != null)
            panelButton.setTextColor(panelVisible ? Ui.ACCENT : Ui.DIM);
    }

    /** A chip tap: append Enter and write it to the pty, or say that there is no live terminal to write to. */
    private void sendCommand(String command) {
        if (command == null || command.isEmpty())
            return;
        if (terminal == null || !terminal.isRunning()) {
            Toasts.show(this, TerminalPresets.text("panel.not_running", "终端没有运行"));
            return;
        }
        terminal.write(command + "\r");
    }

    private TextView action(String label, int color, View.OnClickListener onClick) {
        TextView t = Ui.text(this, label, 13, color, false);
        t.setPadding(Ui.dp(this, 7), Ui.dp(this, 6), Ui.dp(this, 7), Ui.dp(this, 6));
        t.setOnClickListener(onClick);
        return t;
    }

    /** One key of the bottom strip: it sends a raw byte sequence to the shell, exactly like a real key would. */
    private void addKey(LinearLayout row, String label, String sequence) {
        TextView key = Ui.text(this, label, 12, Ui.TEXT, false);
        key.setGravity(Gravity.CENTER);
        key.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        key.setContentDescription(T.t("terminal.key_desc", label));
        key.setOnClickListener(v -> terminal.write(sequence));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
        row.addView(key, lp);
    }

    private void changeFont(int delta) {
        float next = Math.max(MIN_FONT, Math.min(MAX_FONT, terminal.getFontSp() + delta));
        if (Math.abs(next - terminal.getFontSp()) < 0.01f)
            return;
        terminal.setFontSp(next);
        getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit().putFloat("term_font", next).apply();
    }

    private void copy() {
        String text = terminal.selectedText();
        boolean whole = text == null;
        if (whole)
            text = terminal.screenText();
        if (text == null || text.isEmpty()) {
            Toasts.show(this, T.t("toast.nothing_to_copy"));
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null)
            return;
        cm.setPrimaryClip(ClipData.newPlainText(T.t("terminal.clip_label"), text));
        int lines = text.split("\n", -1).length;
        Toasts.show(this, whole ? T.t("toast.copied_screen", lines) : T.t("toast.copied_selection", lines));
    }

    private void paste() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) {
            Toasts.show(this, T.t("toast.clipboard_empty"));
            return;
        }
        CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (text == null || text.length() == 0)
            return;
        // A terminal expects CR for "run it"; a clipboard full of pasted lines must not sit at a bare prompt.
        terminal.write(text.toString().replace("\r\n", "\r").replace('\n', '\r'));
        Toasts.show(this, T.t("toast.pasted", text.length()));
    }

    private void toggleKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null)
            return;
        terminal.requestFocus();
        imm.toggleSoftInput(android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT, 0);
    }

    // ---- TerminalView.Listener -------------------------------------------------------------------------------

    @Override
    public void onSelectionChanged(boolean hasSelection) {
        if (copyButton != null)
            copyButton.setAlpha(hasSelection ? 1f : 0.55f);
    }

    @Override
    public void onTapTerminal() {
        toggleKeyboard();
    }

    @Override
    public void onScrolledChanged(boolean scrolled) {
        if (scrollHint != null)
            scrollHint.setVisibility(scrolled ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onDestroy() {
        sRunning = false;
        if (terminal != null)
            terminal.stop();
        super.onDestroy();
    }
}
