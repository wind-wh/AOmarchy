package app.aomarchy;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.aomarchy.i18n.T;
import app.aomarchy.ui.Ui;

/**
 * The AOmarchy shell: the app now opens on a console (rail + five pages) instead of straight into the Linux
 * desktop. The desktop itself is still {@link MainActivity}: this class only decides when to show it.
 */
public class ShellActivity extends Activity {
    /** Rail tab labels, by i18n key (see {@link T}): resolved per language when the rail is built. */
    private static final String[] TITLES = {
            "tab.overview", "tab.system", "tab.apps", "tab.controls", "tab.settings"};
    /** MainActivity asks for this when it was opened without an installed system: the console installs it now. */
    static final String EXTRA_NEED_SYSTEM = "app.aomarchy.NEED_SYSTEM";
    /** Which page to land on (0-based, see TITLES): the desktop's settings links use 4. */
    static final String EXTRA_TAB = "app.aomarchy.TAB";
    /** The static launcher shortcuts (res/xml/shortcuts.xml) can also ask for an action, not just a page. */
    static final String EXTRA_ACTION = "app.aomarchy.ACTION";
    /** {@link #EXTRA_ACTION} value of the 终端模式 shortcut: skip the console and open the terminal. */
    private static final String ACTION_TERMINAL = "terminal";

    /** Intent that opens the console on one page, from the desktop's settings entries. */
    static Intent intent(Context c, int tab) {
        Intent i = new Intent(c, ShellActivity.class);
        i.putExtra(EXTRA_TAB, tab);
        // Reuse the console instance that is already running (singleTop) so onNewIntent switches its page,
        // instead of stacking a second console in the desktop's task.
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return i;
    }

    private final Page[] pages = new Page[TITLES.length];
    private final LinearLayout[] tabs = new LinearLayout[TITLES.length];
    private final RailIcon[] icons = new RailIcon[TITLES.length];
    private final TextView[] tabLabels = new TextView[TITLES.length];
    private FrameLayout host;
    private FrameLayout shellRoot;
    private TextView status;
    private TextView installHint;
    private int current = -1;
    private View indicator;
    private TextView entryButton;
    private PowerIcon powerIcon;

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    /** While there is no system, keep the top bar in step with the install the 系统 page runs off-thread. */
    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            updateEntryLabel();
            if (!SessionService.rootfsReady(ShellActivity.this))
                handler.postDelayed(this, 2000);
        }
    };

    /** ui_lang also decides the resource Locale (see {@link T#wrap(Context)}); auto keeps the system Locale. */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(T.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        T.init(this); // the saved ui_lang, before any view asks for a string
        build();
        Intent intent = getIntent();
        boolean terminal = isTerminalShortcut(intent);
        int startTab = readTab(intent);
        if (terminal) {
            // The 终端模式 launcher shortcut: the console is built behind the terminal first, so the terminal's
            // back-to-console button has somewhere to return to. No system: openTerminal() prompts on the 系统 page.
            if (SessionService.rootfsReady(this))
                show(0);
            openTerminal();
        } else if (SessionService.rootfsReady(this)) {
            show(startTab >= 0 && startTab < TITLES.length ? startTab : 0);
        } else {
            // No system yet: the 系统 page (tab 1) owns installing it now that the WebView installer is gone.
            promptInstall();
        }
        app.aomarchy.data.Installer.cleanupAsync(this); // leftover partial / old system from an interrupted install
        requestNotificationPermission();
        // A shortcut decides where to go itself; the open-desktop startup preference must not run on top of it.
        if (!terminal
                && getSharedPreferences(Prefs.NAME, MODE_PRIVATE).getBoolean("open_desktop_on_start", false)
                && SessionService.rootfsReady(this))
            openDesktop(); // the startup preference: skip the console and show the desktop
    }

    /**
     * The page the intent asks for, or -1. In-app intents (see {@link #intent(Context, int)}) put an int extra; a
     * static launcher shortcut can only carry a string, so both spellings are accepted.
     */
    private static int readTab(Intent intent) {
        if (intent == null || intent.getExtras() == null)
            return -1;
        Object raw = intent.getExtras().get(EXTRA_TAB);
        if (raw instanceof Integer)
            return (Integer) raw;
        if (raw instanceof String) {
            try {
                return Integer.parseInt(((String) raw).trim());
            } catch (NumberFormatException ignored) {
                // not a page: fall through to -1
            }
        }
        return -1;
    }

    /** True for the 终端模式 launcher shortcut (res/xml/shortcuts.xml). */
    private static boolean isTerminalShortcut(Intent intent) {
        return intent != null && ACTION_TERMINAL.equals(intent.getStringExtra(EXTRA_ACTION));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent == null)
            return;
        if (isTerminalShortcut(intent)) {
            openTerminal(); // the 终端模式 shortcut while the console is already running (singleTop)
            return;
        }
        if (intent.getBooleanExtra(EXTRA_NEED_SYSTEM, false)) {
            promptInstall(); // the desktop bounced back because there is nothing to start
            return;
        }
        // The desktop's settings entries ask for a page: honour it even when the console is already on top, where
        // a plain am start is delivered here (START_DELIVERED_TO_TOP) instead of going through onCreate.
        int tab = readTab(intent);
        if (tab >= 0 && tab < TITLES.length && SessionService.rootfsReady(this))
            show(tab);
    }

    /** Shows the 系统 page and says why: there is no Linux system to open yet, and that page installs it. */
    private void promptInstall() {
        showTab(1);
        toast(T.t("shell.need_system"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        updateEntryLabel();
        handler.removeCallbacks(statusTicker);
        if (!SessionService.rootfsReady(this))
            handler.postDelayed(statusTicker, 1000); // the install finishes in the background: follow it
        if (pages[current] != null) pages[current].refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statusTicker);
    }

    /** The session notification (with its 控制 action) only shows once notifications are allowed (Android 13+). */
    private void requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33)
            return;
        String permission = "android.permission.POST_NOTIFICATIONS";
        if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] {permission}, 11);
    }

    private void build() {
        LinearLayout root = Ui.row(this);
        root.setBackgroundColor(Ui.BG);

        FrameLayout railFrame = new FrameLayout(this);
        // Frosted-glass rail: the same sheen + inner rim as the cards (radius 0 keeps it a straight slab).
        railFrame.setBackground(new app.aomarchy.ui.CardBackground(this, Ui.GLASS_STRONG, Ui.GLASS_LINE, 1, 0));
        LinearLayout rail = Ui.column(this);
        rail.setPadding(Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 16));
        for (int i = 0; i < TITLES.length; i++) {
            final int index = i;
            LinearLayout tab = Ui.column(this);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
            tab.setOnClickListener(v -> show(index));
            RailIcon icon = new RailIcon(this, i);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24));
            tab.addView(icon, ilp);
            TextView label = Ui.text(this, T.t(TITLES[i]), 11, Ui.DIM, false);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, Ui.dp(this, 4), 0, 0);
            tab.addView(label);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.bottomMargin = Ui.dp(this, 6);
            rail.addView(tab, tlp);
            tabs[i] = tab;
            icons[i] = icon;
            tabLabels[i] = label;
        }
        // The rail ends with the power entry: the session's on/off switch is one tap away, at thumb height.
        rail.addView(new View(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        FrameLayout power = new FrameLayout(this);
        power.setBackground(Ui.surface(this, 14));
        power.setContentDescription(T.t("power.button"));
        power.setClickable(true);
        power.setFocusable(true);
        power.setOnClickListener(v -> PowerMenu.show(this));
        powerIcon = new PowerIcon(this);
        powerIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        power.addView(powerIcon, new FrameLayout.LayoutParams(
                Ui.dp(this, 24), Ui.dp(this, 24), Gravity.CENTER));
        rail.addView(power, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));
        // The indicator sits behind the tabs and glides to the selected one (Kinetik's liquid tab bar).
        indicator = new View(this);
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setColor(0x1F7C8CFF);
        pill.setCornerRadius(Ui.dp(this, 16));
        pill.setStroke(Math.max(1, Ui.dp(this, 1)), 0x337C8CFF);
        indicator.setBackground(pill);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 76));
        ilp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        railFrame.addView(indicator, ilp);
        railFrame.addView(rail, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(railFrame, new LinearLayout.LayoutParams(Ui.dp(this, 88), ViewGroup.LayoutParams.MATCH_PARENT));

        View divider = new View(this);
        divider.setBackgroundColor(Ui.GLASS_LINE_SOFT);
        root.addView(divider, new LinearLayout.LayoutParams(Ui.dp(this, 1), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = Ui.column(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        root.addView(content, clp);

        LinearLayout top = Ui.row(this);
        top.setPadding(Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 8));
        TextView name = Ui.text(this, "AOmarchy", 20, Ui.TEXT, true);
        top.addView(name);
        View spacer = new View(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        status = Ui.chip(this, "○ " + T.t("status.not_installed"), Ui.DIM);
        top.addView(status);
        top.addView(Ui.space(this, 10));
        entryButton = Ui.button(this, T.t("btn.enter_desktop"), true, v -> openDesktop());
        top.addView(entryButton);
        content.addView(top);

        // While there is no system, a warning line above the page points at the install card (tapping opens it).
        installHint = Ui.text(this, T.t("shell.need_system"), 12, Ui.WARN, false);
        installHint.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), Ui.dp(this, 8));
        installHint.setOnClickListener(v -> showTab(1));
        installHint.setVisibility(View.GONE);
        content.addView(installHint);

        host = new FrameLayout(this);
        content.addView(host, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // The console floats over the device wallpaper (blurred + dimmed), so it feels like the home screen behind it.
        root.setBackground(null);
        shellRoot = new FrameLayout(this);
        shellRoot.setBackgroundColor(Ui.BG);
        shellRoot.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(shellRoot);
        app.aomarchy.ui.Wallpaper.behind(this, shellRoot);
        updateStatus();
        updateEntryLabel();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The console is laid out for the current orientation (four stat cards in one row in landscape), so rebuild.
        T.applyLocale(this); // a system config change must not drop the ui_lang resource override
        int tab = Math.max(0, current);
        java.util.Arrays.fill(pages, null);
        build();
        show(tab);
    }

    /** Re-applies the console background after the user picked another image in 设置 → 控制台背景. */
    void refreshConsoleBackground() {
        // Wallpaper.behind is idempotent: it swaps its own backdrop layers and leaves the console views alone.
        if (shellRoot != null)
            app.aomarchy.ui.Wallpaper.behind(this, shellRoot);
    }

    /**
     * Rebuilds the whole console after the Settings page changed {@code ui_lang}: every page is recreated, so the new
     * language applies immediately and the rail/pages stay on the tab the user was on (no activity restart).
     */
    void relanguage() {
        T.applyLocale(this); // resource strings of the rebuilt views follow the new ui_lang as well
        T.init(this);
        SessionService.refreshNotification(this); // the shade copy was fixed when the service started
        SessionWidget.refresh(this); // and the home-screen widget says the same language
        KeepAliveService.refresh(this); // and the persistent background notification does too
        int tab = Math.max(0, current);
        for (Page page : pages)
            if (page != null)
                page.onHidden(); // stop the pollers of the pages that are about to be discarded
        java.util.Arrays.fill(pages, null);
        build();
        show(tab);
    }
    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (current >= 0 && pages[current] != null)
            pages[current].onActivityResult(request, result, data);
    }

    private void show(int index) {
        int previous = current;
        if (current >= 0 && pages[current] != null)
            pages[current].onHidden();
        current = index;
        if (pages[index] == null) pages[index] = newPage(index);
        host.removeAllViews();
        host.addView(pages[index].root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pages[index].refresh();
        pages[index].onShown();
        for (int i = 0; i < tabs.length; i++) {
            boolean on = i == index;
            tabs[i].setBackground(on ? Ui.surface(this, 16) : null);
            icons[i].setColor(on ? Ui.ACCENT : Ui.DIM);
            tabLabels[i].setTextColor(on ? Ui.TEXT : Ui.DIM);
            tabs[i].animate().scaleX(on ? 1.06f : 1f).scaleY(on ? 1.06f : 1f)
                    .setDuration(280).setInterpolator(app.aomarchy.ui.Motion.SPRING).start();
        }
        moveIndicator(index);
        // Pages slide in from the side they come from (left when going back down the rail), fading as they arrive.
        View pageRoot = pages[index].root;
        float shift = Ui.dp(this, 22);
        pageRoot.setTranslationX(previous >= 0 && index < previous ? -shift : shift);
        app.aomarchy.ui.Motion.appear(pageRoot, 0);
        pageRoot.animate().translationX(0f).setDuration(300)
                .setInterpolator(app.aomarchy.ui.Motion.OUT).start();
        updateStatus();
    }

    /** Glides the rail pill to the tab that was chosen; on the first show it is placed without an animation. */
    private void moveIndicator(int index) {
        if (indicator == null)
            return;
        final android.view.View tab = tabs[index];
        Runnable place = () -> {
            if (tab.getHeight() == 0)
                return;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) indicator.getLayoutParams();
            lp.height = (int) (tab.getHeight() * 0.88f);
            indicator.setLayoutParams(lp);
            indicator.animate().y(tab.getTop() + Ui.dp(this, 4)).setDuration(300)
                    .setInterpolator(app.aomarchy.ui.Motion.SPRING).start();
        };
        if (tab.getHeight() > 0) {
            place.run();
        } else {
            tab.post(() -> indicator.post(place));
        }
    }

    /** In-app toast (styled, stacking); pages and controls use this instead of the system one. */
    void toast(String text) {
        app.aomarchy.ui.Toasts.show(this, text);
    }

    private Page newPage(int index) {
        switch (index) {
            case 1:
                return new SystemPage(this);
            case 2:
                return new AppsPage(this);
            case 3:
                return new ControlsPage(this);
            case 4:
                return new SettingsPage(this);
            default:
                return new OverviewPage(this);
        }
    }

    /** The one action the whole shell is about: show the Linux side, installing it first if it is not there. */
    void openDesktop() {
        if (!SessionService.rootfsReady(this)) {
            promptInstall(); // no system: the 系统 page installs it natively now, no separate installer screen
            return;
        }
        boolean terminal = "terminal".equals(getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
                .getString("session_mode", "desktop"));
        if (terminal) {
            // Terminal mode runs one pty, not the desktop: no service, no compositor, a fifth of the memory.
            startActivity(new Intent(this, TerminalActivity.class));
            return;
        }
        SessionService.start(this);
        startActivity(new Intent(this, MainActivity.class));
    }

    /**
     * The 终端模式 launcher shortcut: one pty, no desktop, whatever the session_mode preference says. Without a
     * system the 系统 page installs it first — the same prompt the entry button uses.
     */
    private void openTerminal() {
        if (!SessionService.rootfsReady(this)) {
            promptInstall();
            return;
        }
        startActivity(new Intent(this, TerminalActivity.class));
    }

    /** The big button follows the startup mode chosen in Settings (and is an install button until there is a system). */
    void updateEntryLabel() {
        if (entryButton == null)
            return;
        if (!SessionService.rootfsReady(this)) {
            entryButton.setText(T.t("btn.install_system"));
            return;
        }
        boolean terminal = "terminal".equals(getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
                .getString("session_mode", "desktop"));
        entryButton.setText(T.t(terminal ? "btn.enter_terminal" : "btn.enter_desktop"));
    }

    /** Jump to a page by rail index (0 概览 … 4 设置). */
    void showTab(int index) {
        if (index >= 0 && index < TITLES.length)
            show(index);
    }

    void installSystem() {
        showTab(1); // installing lives in the System page now
    }

    private void updateStatus() {
        boolean ready = SessionService.rootfsReady(this);
        boolean running = SessionService.started;
        String text = !ready ? "○ " + T.t("status.not_installed")
                : running ? "● " + T.t("status.running") : "● " + T.t("status.ready");
        status.setText(text);
        status.setTextColor(!ready ? Ui.FAINT : running ? Ui.MINT : Ui.ACCENT);
        if (installHint != null)
            installHint.setVisibility(ready ? View.GONE : View.VISIBLE);
        // The rail's power glyph doubles as the session LED: mint while Linux runs, dim when only the rootfs is there.
        if (powerIcon != null)
            powerIcon.setColor(!ready ? Ui.FAINT : running ? Ui.MINT : Ui.DIM);
    }

    /** PowerMenu calls this once a session start/stop has settled, so the chip and the rail follow in place. */
    void sessionChanged() {
        if (isFinishing() || isDestroyed())
            return;
        updateStatus();
        updateEntryLabel();
    }

    /** Rail glyphs, drawn instead of shipping five drawables: same 1.8dp stroke everywhere. */
    static final class RailIcon extends View {
        private final int index;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color = Ui.DIM;

        RailIcon(android.content.Context c, int index) {
            super(c);
            this.index = index;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        void setColor(int c) {
            color = c;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float s = Math.min(w, h);
            paint.setColor(color);
            paint.setStrokeWidth(s * 0.075f);
            float left = (w - s) / 2f, top = (h - s) / 2f;
            float cell = s * 0.36f, gap = s * 0.12f;
            switch (index) {
                case 0: // overview: four tiles
                    for (int i = 0; i < 4; i++) {
                        float x = left + (i % 2) * (cell + gap) + s * 0.06f;
                        float y = top + (i / 2) * (cell + gap) + s * 0.06f;
                        canvas.drawRoundRect(new RectF(x, y, x + cell, y + cell), s * 0.09f, s * 0.09f, paint);
                    }
                    break;
                case 1: // system: a chip
                    canvas.drawRoundRect(new RectF(left + s * 0.2f, top + s * 0.2f, left + s * 0.8f, top + s * 0.8f),
                            s * 0.12f, s * 0.12f, paint);
                    canvas.drawRoundRect(new RectF(left + s * 0.38f, top + s * 0.38f, left + s * 0.62f, top + s * 0.62f),
                            s * 0.05f, s * 0.05f, paint);
                    for (int i = 0; i < 3; i++) {
                        float p = left + s * 0.3f + i * s * 0.2f;
                        canvas.drawLine(p, top + s * 0.06f, p, top + s * 0.2f, paint);
                        canvas.drawLine(p, top + s * 0.8f, p, top + s * 0.94f, paint);
                        canvas.drawLine(left + s * 0.06f, p - left + top, left + s * 0.2f, p - left + top, paint);
                        canvas.drawLine(left + s * 0.8f, p - left + top, left + s * 0.94f, p - left + top, paint);
                    }
                    break;
                case 2: // apps: nine dots
                    paint.setStyle(Paint.Style.FILL);
                    for (int i = 0; i < 9; i++) {
                        float x = left + s * 0.22f + (i % 3) * s * 0.28f;
                        float y = top + s * 0.22f + (i / 3) * s * 0.28f;
                        canvas.drawCircle(x, y, s * 0.055f, paint);
                    }
                    paint.setStyle(Paint.Style.STROKE);
                    break;
                case 3: // controls: sliders
                    for (int i = 0; i < 3; i++) {
                        float y = top + s * 0.28f + i * s * 0.22f;
                        canvas.drawLine(left + s * 0.12f, y, left + s * 0.88f, y, paint);
                        canvas.drawCircle(left + s * (0.3f + i * 0.2f), y, s * 0.085f, paint);
                    }
                    break;
                default: // settings: ring with spokes
                    canvas.drawCircle(left + s * 0.5f, top + s * 0.5f, s * 0.22f, paint);
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI / 4;
                        float x1 = (float) (left + s * 0.5f + Math.cos(a) * s * 0.28f);
                        float y1 = (float) (top + s * 0.5f + Math.sin(a) * s * 0.28f);
                        float x2 = (float) (left + s * 0.5f + Math.cos(a) * s * 0.42f);
                        float y2 = (float) (top + s * 0.5f + Math.sin(a) * s * 0.42f);
                        canvas.drawLine(x1, y1, x2, y2, paint);
                    }
                    break;
            }
        }
    }

    /** The rail's power glyph: the classic ring with a gap at the top and a stem through it (no drawable needed). */
    static final class PowerIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color = Ui.DIM;

        PowerIcon(android.content.Context c) {
            super(c);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        void setColor(int c) {
            color = c;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float s = Math.min(w, h);
            float cx = w / 2f, cy = h / 2f;
            paint.setColor(color);
            paint.setStrokeWidth(s * 0.09f);
            float r = s * 0.31f;
            canvas.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), -60, 300, false, paint);
            canvas.drawLine(cx, cy - s * 0.44f, cx, cy - s * 0.08f, paint);
        }
    }
}
