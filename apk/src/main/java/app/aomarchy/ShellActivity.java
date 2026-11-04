package app.aomarchy;

import android.app.Activity;
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

import app.aomarchy.ui.Ui;

/**
 * The AOmarchy shell: the app now opens on a console (rail + five pages) instead of straight into the Linux
 * desktop. The desktop itself is still {@link MainActivity}: this class only decides when to show it.
 */
public class ShellActivity extends Activity {
    private static final String[] TITLES = {"概览", "系统", "应用", "控制", "设置"};

    private final Page[] pages = new Page[TITLES.length];
    private final LinearLayout[] tabs = new LinearLayout[TITLES.length];
    private final RailIcon[] icons = new RailIcon[TITLES.length];
    private final TextView[] tabLabels = new TextView[TITLES.length];
    private FrameLayout host;
    private TextView status;
    private int current = -1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        build();
        show(0);
        app.aomarchy.data.Installer.cleanupAsync(this); // leftover partial / old system from an interrupted install
        requestNotificationPermission();
        if (getSharedPreferences(Prefs.NAME, MODE_PRIVATE).getBoolean("open_desktop_on_start", false)
                && SessionService.rootfsReady(this))
            openDesktop(); // the startup preference: skip the console and show the desktop
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        if (pages[current] != null) pages[current].refresh();
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

        LinearLayout rail = Ui.column(this);
        rail.setBackgroundColor(Ui.SURFACE);
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
            TextView label = Ui.text(this, TITLES[i], 11, Ui.DIM, false);
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
        root.addView(rail, new LinearLayout.LayoutParams(Ui.dp(this, 88), ViewGroup.LayoutParams.MATCH_PARENT));

        View divider = new View(this);
        divider.setBackgroundColor(Ui.LINE);
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
        status = Ui.chip(this, "○ 未安装", Ui.DIM);
        top.addView(status);
        top.addView(Ui.space(this, 10));
        TextView desktop = Ui.button(this, "进入桌面", true, v -> openDesktop());
        top.addView(desktop);
        content.addView(top);

        host = new FrameLayout(this);
        content.addView(host, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        updateStatus();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (current >= 0 && pages[current] != null)
            pages[current].onActivityResult(request, result, data);
    }

    private void show(int index) {
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
        }
        updateStatus();
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

    /** The one action the whole shell is about: show the Linux desktop, installing it first if it is not there. */
    void openDesktop() {
        if (!SessionService.rootfsReady(this)) {
            startActivity(new Intent(this, InstallActivity.class));
            return;
        }
        SessionService.start(this);
        startActivity(new Intent(this, MainActivity.class));
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
        String text = !ready ? "○ 未安装" : running ? "● 运行中" : "● 就绪";
        status.setText(text);
        status.setTextColor(!ready ? Ui.FAINT : running ? Ui.MINT : Ui.ACCENT);
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
}
