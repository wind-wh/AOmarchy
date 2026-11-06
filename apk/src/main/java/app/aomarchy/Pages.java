package app.aomarchy;

import android.content.Intent;
import android.os.Build;
import android.os.StatFs;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

import app.aomarchy.data.Apps;
import app.aomarchy.data.Installer;
import app.aomarchy.data.Metrics;
import app.aomarchy.i18n.T;
import app.aomarchy.ui.Sparkline;
import app.aomarchy.ui.Ui;

/** One shell page: a scrolling column of cards. Subclasses fill it in {@link #build}. */
abstract class Page {
    final ShellActivity shell;
    final ScrollView root;
    final LinearLayout body;

    Page(ShellActivity shell) {
        this.shell = shell;
        root = new ScrollView(shell);
        root.setBackgroundColor(android.graphics.Color.TRANSPARENT); // the console's wallpaper shows through
        root.setFillViewport(true);
        body = Ui.column(shell);
        int pad = Ui.dp(shell, 18);
        body.setPadding(pad, pad, pad, Ui.dp(shell, 18));
        root.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        build(body);
    }

    abstract void build(LinearLayout body);

    /** Called when the page becomes the visible one: start timers / watchers here. */
    void onShown() {
    }

    /** Called when another page replaces this one: stop everything started in {@link #onShown()}. */
    void onHidden() {
    }

    /** Activity results (file pickers) land on the shell and are forwarded to the page that asked. */
    void onActivityResult(int request, int result, android.content.Intent data) {
    }

    void refresh() {
    }

    /** Called by the shell when a background check (e.g. for a newer release) found something to suggest. */
    void refreshInsights() {
    }

    TextView value(String text, int color) {
        return Ui.mono(shell, text, 15, color);
    }

    void toast(String text) {
        shell.toast(text);
    }

    /** A console button with the 48dp minimum touch target TalkBack needs. */
    TextView button(String text, boolean primary, View.OnClickListener onClick) {
        TextView b = Ui.button(shell, text, primary, onClick);
        b.setMinimumHeight(Ui.dp(shell, 48));
        return b;
    }

    /** Decorative separators, spacers and sparklines must not become TalkBack reading stops. */
    static void decorative(View v) {
        if (v != null)
            v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
}

/** 概览: is the session up, and the numbers that answer "is it healthy". */
class OverviewPage extends Page {
    private final Metrics metrics = new Metrics();
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            poll();
            handler.postDelayed(this, 2000);
        }
    };

    private TextView state;
    private TextView uptime;
    private TextView cpuValue;
    private TextView cpuSub;
    private TextView memValue;
    private TextView memSub;
    private TextView diskValue;
    private TextView diskSub;
    private TextView batteryValue;
    private TextView batterySub;
    private TextView networkValue;
    private TextView gpuValue;
    private Sparkline sparkline;
    private int lastCpu = -1, lastMem = -1, lastDisk = -1, lastBat = -1;
    private TextView heroEntry;
    private TextView backupHint;
    private TextView backupAction;
    private boolean backupRunning;
    private TextView backupDetail;

    OverviewPage(ShellActivity shell) {
        super(shell);
    }

    @Override
    void build(LinearLayout body) {
        LinearLayout hero = Ui.card(shell, null);
        LinearLayout head = Ui.row(shell);
        state = Ui.text(shell, T.t("overview.session"), 18, Ui.TEXT, true);
        head.addView(state);
        View headSpace = new View(shell);
        decorative(headSpace);
        head.addView(headSpace, new LinearLayout.LayoutParams(0, 1, 1f));
        // The top bar owns the single status capsule; the hero uses its title + subline instead of a second one.
        hero.addView(head);
        hero.addView(Ui.gap(shell, 6));
        uptime = Ui.text(shell, T.t("overview.tagline"), 13, Ui.DIM, false);
        hero.addView(uptime);
        hero.addView(Ui.gap(shell, 10));
        LinearLayout actions = Ui.row(shell);
        heroEntry = button(T.t("btn.enter_desktop"), true, v -> shell.openDesktop());
        actions.addView(heroEntry);
        actions.addView(Ui.space(shell, 10));
        actions.addView(button(T.t("btn.install_reinstall"), false, v -> shell.showTab(1)));
        View actionsSpace = new View(shell);
        decorative(actionsSpace);
        actions.addView(actionsSpace, new LinearLayout.LayoutParams(0, 1, 1f));
        actions.addView(Ui.text(shell, "Wayland · Hyprland · Arch Linux ARM", 11, Ui.FAINT, false));
        hero.addView(actions);
        body.addView(hero);

        // Cards: in landscape all four numbers share one row (denser, matches the wide screen); in portrait 2x2.
        LinearLayout cpuCard = card("CPU");
        cpuValue = big("—");
        cpuCard.addView(cpuValue);
        cpuSub = Ui.text(shell, "", 12, Ui.DIM, false);
        cpuCard.addView(cpuSub);
        sparkline = new Sparkline(shell);
        decorative(sparkline); // the CPU number above already says what the line shows
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(shell, 28));
        slp.topMargin = Ui.dp(shell, 3);
        cpuCard.addView(sparkline, slp);

        LinearLayout memCard = card(T.t("card.memory"));
        memValue = big("—");
        memCard.addView(memValue);
        memSub = Ui.text(shell, "", 12, Ui.DIM, false);
        memCard.addView(memSub);

        LinearLayout diskCard = card(T.t("card.disk_system"));
        diskValue = big("—");
        diskCard.addView(diskValue);
        diskSub = Ui.text(shell, "", 12, Ui.DIM, false);
        diskCard.addView(diskSub);

        LinearLayout batCard = card(T.t("card.battery"));
        batteryValue = big("—");
        batCard.addView(batteryValue);
        batterySub = Ui.text(shell, "", 12, Ui.DIM, false);
        batCard.addView(batterySub);

        boolean wide = shell.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout[] cards = {cpuCard, memCard, diskCard, batCard};
        if (wide) {
            LinearLayout row = Ui.row(shell);
            for (int i = 0; i < cards.length; i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(shell, 132), 1f);
                if (i < cards.length - 1)
                    lp.rightMargin = Ui.dp(shell, 9);
                row.addView(cards[i], lp);
            }
            body.addView(row);
        } else {
            for (int r = 0; r < 2; r++) {
                LinearLayout row = Ui.row(shell);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(shell, 132), 1f);
                lp.rightMargin = Ui.dp(shell, 9);
                row.addView(cards[r * 2], lp);
                row.addView(cards[r * 2 + 1], new LinearLayout.LayoutParams(0, Ui.dp(shell, 132), 1f));
                body.addView(row);
                body.addView(Ui.gap(shell, 9));
            }
        }

        // One wide card for the things that are two short lines each: less blank space than two half-empty cards.
        LinearLayout netGpu = card(T.t("card.net_gpu"));
        networkValue = value("—", Ui.TEXT);
        netGpu.addView(Ui.kv2(shell, T.t("label.network"), networkValue, T.t("overview.net_caption")));
        gpuValue = value("—", Ui.TEXT);
        netGpu.addView(Ui.kv2(shell, "GPU", gpuValue, "Mesa KGSL · Adreno"));
        netGpu.addView(Ui.text(shell, T.t("overview.net_gpu_note"), 11, Ui.FAINT, false));
        body.addView(netGpu);

        // Data safety at a glance: the newest backup's age, and one tap to make another one.
        LinearLayout backupStatus = card(T.t("card.backup_status"));
        backupHint = Ui.text(shell, T.t("overview.backup_never"), 12, Ui.DIM, false);
        backupStatus.addView(backupHint);
        backupDetail = Ui.text(shell, "", 11, Ui.FAINT, false);
        backupStatus.addView(backupDetail);
        backupStatus.addView(Ui.gap(shell, 8));
        LinearLayout backupRow = Ui.row(shell);
        backupAction = button(T.t("btn.backup_now"), false, v -> startQuickBackup());
        backupAction.setContentDescription(T.t("btn.backup_now"));
        backupRow.addView(backupAction);
        backupRow.addView(new View(shell), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView manage = Ui.text(shell, T.t("btn.backup_manage"), 13, Ui.ACCENT, true);
        manage.setPadding(Ui.dp(shell, 8), Ui.dp(shell, 8), Ui.dp(shell, 8), Ui.dp(shell, 8));
        manage.setMinimumHeight(Ui.dp(shell, 48));
        manage.setOnClickListener(v -> shell.showTab(1));
        backupRow.addView(manage);
        backupStatus.addView(backupRow);
        body.addView(backupStatus);

        // Smart suggestions: the two most useful next actions for this device, computed from the live metrics.
        LinearLayout insights = Ui.card(shell, T.t("card.insights"));
        insightsBox = Ui.column(shell);
        insights.addView(insightsBox);
        insightsEmpty = Ui.text(shell, T.t("insights.none"), 12, Ui.DIM, false);
        insights.addView(insightsEmpty);
        body.addView(insights);

        LinearLayout tips = Ui.card(shell, T.t("card.tips"));
        tips.addView(Ui.text(shell, T.t("overview.tips"), 12, Ui.DIM, false));
        body.addView(tips);
    }

    private LinearLayout insightsBox;
    private TextView insightsEmpty;
    private String insightSignature;

    /** One suggestion row: a colored dot, the text, and a tappable action at the end. */
    @Override
    void refreshInsights() {
        updateInsights();
    }

    private LinearLayout insightRow(String text, String action, Runnable onClick) {
        LinearLayout row = Ui.row(shell);
        row.setPadding(0, Ui.dp(shell, 6), 0, Ui.dp(shell, 6));
        row.setMinimumHeight(Ui.dp(shell, 48));
        TextView dot = Ui.text(shell, "•", 16, Ui.ACCENT, true);
        dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(dot);
        row.addView(Ui.space(shell, 8));
        TextView label = Ui.text(shell, text, 13, Ui.TEXT, false);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView chip = Ui.text(shell, action, 13, Ui.ACCENT, true);
        chip.setPadding(Ui.dp(shell, 10), Ui.dp(shell, 8), Ui.dp(shell, 10), Ui.dp(shell, 8));
        chip.setMinimumHeight(Ui.dp(shell, 48));
        chip.setContentDescription(action);
        row.addView(chip);
        row.setOnClickListener(v -> onClick.run());
        row.setClickable(true);
        row.setContentDescription(text + "，" + action);
        for (int c = 0; c < row.getChildCount(); c++)
            row.getChildAt(c).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return row;
    }

    /**
     * Recomputes the suggestion list (max two). Rebuilding the rows every poll would flicker, so it only touches the
     * views when the suggestion signature changes; the data comes from the poller's metrics.
     */
    private void updateInsights() {
        if (insightsBox == null)
            return;
        boolean ready = SessionService.rootfsReady(shell);
        java.util.List<String[]> found = new java.util.ArrayList<>(); // {signature, text, action, kind}
        if (ShellActivity.updateVersion != null)
            found.add(new String[]{"update", T.t("insight.update_available", ShellActivity.updateVersion),
                    T.t("insight.update_action"), "update"});
        if (!ready) {
            found.add(new String[]{"nosys", T.t("insight.no_system"), T.t("insight.install"), "install"});
        } else {
            long free = metrics.diskTotal - metrics.diskUsed;
            int diskPercent = metrics.diskPercent();
            if (diskPercent >= 85)
                found.add(new String[]{"disk", T.t("insight.disk_low",
                        app.aomarchy.data.Metrics.gb(free)), T.t("insight.clean"), "system"});
            if (metrics.memPercent() >= 85)
                found.add(new String[]{"mem", T.t("insight.mem_high", String.valueOf(metrics.memPercent())),
                        T.t("insight.to_settings"), "settings"});
            if (!SessionService.started && !"terminal".equals(shell.getSharedPreferences(Prefs.NAME,
                    android.content.Context.MODE_PRIVATE).getString("session_mode", "desktop")))
                found.add(new String[]{"idle", T.t("insight.session_idle"), T.t("insight.enter"), "desktop"});
            long backupBytes = app.aomarchy.data.Backup.totalBytes(shell);
            if (backupBytes > 8L * 1024 * 1024 * 1024)
                found.add(new String[]{"backup", T.t("insight.backup_big",
                        app.aomarchy.data.Installer.humanSize(backupBytes)), T.t("insight.manage"), "system"});
        }
        int count = Math.min(2, found.size());
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < count; i++)
            sig.append(found.get(i)[0]).append(',');
        String signature = sig.toString();
        if (signature.equals(insightSignature))
            return;
        insightSignature = signature;
        insightsBox.removeAllViews();
        insightsEmpty.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < count; i++) {
            final String kind = found.get(i)[3];
            insightsBox.addView(insightRow(found.get(i)[1], found.get(i)[2], () -> {
                switch (kind) {
                    case "install":
                    case "system":
                        shell.showTab(1);
                        break;
                    case "settings":
                        shell.showTab(4);
                        break;
                    case "desktop":
                        shell.openDesktop();
                        break;
                    case "update":
                        if (ShellActivity.updateUrl != null)
                            try {
                                shell.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(ShellActivity.updateUrl)));
                            } catch (RuntimeException ignored) {
                                // no browser installed
                            }
                        break;
                    default:
                        break;
                }
            }));
        }
    }
    private LinearLayout card(String title) {
        LinearLayout card = Ui.card(shell, title);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 0;
        card.setLayoutParams(lp);
        return card;
    }

    private TextView big(String text) {
        TextView v = value(text, Ui.TEXT);
        v.setTextSize(20);
        return v;
    }

    @Override
    void onShown() {
        handler.post(poller);
    }

    @Override
    void onHidden() {
        handler.removeCallbacks(poller);
    }

    @Override
    void refresh() {
        boolean ready = SessionService.rootfsReady(shell);
        boolean terminal = "terminal".equals(shell.getSharedPreferences(Prefs.NAME,
                android.content.Context.MODE_PRIVATE).getString("session_mode", "desktop"));
        // One status source: the shell's top-bar chip. Here the title stays put and the subline carries the
        // session type / uptime, so the hero no longer repeats the same state twice in one card.
        if (heroEntry != null)
            heroEntry.setText(T.t(terminal ? "btn.enter_terminal" : "btn.enter_desktop"));
        uptime.setText(!ready ? T.t("overview.tagline")
                : terminal ? T.t("overview.tagline_terminal")
                : SessionService.started
                        ? T.t("overview.running_for", duration(System.currentTimeMillis() - SessionService.startedAt))
                        : T.t("overview.tagline"));
        updateBackupHint();
        poll();
    }

    /** One tap: snapshot the system from the overview, with the progress in the card's own line. */
    private void startQuickBackup() {
        if (backupRunning || backupAction == null)
            return;
        backupRunning = true;
        backupAction.setEnabled(false);
        backupAction.setText(T.t("state.backing_up"));
        app.aomarchy.data.Backup.create(shell, new app.aomarchy.data.Backup.Callback() {
            @Override
            public void progress(int permille, String text) {
                shell.runOnUiThread(() -> {
                    if (backupHint != null && text != null)
                        backupHint.setText(text);
                });
            }

            @Override
            public void done(String message) {
                shell.runOnUiThread(() -> finishQuickBackup(message));
            }

            @Override
            public void failed(String message) {
                shell.runOnUiThread(() -> finishQuickBackup(message));
            }
        });
    }

    private void finishQuickBackup(String message) {
        backupRunning = false;
        if (backupAction != null) {
            backupAction.setEnabled(true);
            backupAction.setText(T.t("btn.backup_now"));
        }
        if (message != null && !message.isEmpty())
            shell.toast(message);
        updateBackupHint();
    }

    /** "Last backup: 3 h ago", or a nudge when there is none / it is older than a month. */
    private void updateBackupHint() {
        if (backupHint == null)
            return;
        long newest = 0;
        for (java.io.File f : app.aomarchy.data.Backup.list(shell))
            newest = Math.max(newest, f.lastModified());
        if (newest <= 0) {
            backupHint.setText(T.t("overview.backup_never"));
            backupHint.setTextColor(Ui.WARN);
            return;
        }
        String ago = ago(newest);
        boolean stale = System.currentTimeMillis() - newest > 30L * 24 * 60 * 60 * 1000;
        backupHint.setText(T.t(stale ? "overview.backup_stale" : "overview.backup_last", ago));
        backupHint.setTextColor(stale ? Ui.WARN : Ui.DIM);
        if (backupDetail != null)
            backupDetail.setText(T.t("system.backup_stats",
                    String.valueOf(app.aomarchy.data.Backup.count(shell)),
                    app.aomarchy.data.Installer.humanSize(app.aomarchy.data.Backup.totalBytes(shell))));
    }

    /** Human age for the backup line (the generic duration() is too verbose for "3 h ago"). */
    private static String ago(long millis) {
        long delta = Math.max(0, System.currentTimeMillis() - millis);
        long minutes = delta / 60000;
        if (minutes < 1)
            return T.t("time.just_now");
        if (minutes < 60)
            return T.t("time.minutes_ago", minutes);
        long hours = minutes / 60;
        if (hours < 24)
            return T.t("time.hours_ago", hours);
        return T.t("time.days_ago", hours / 24);
    }

    private void poll() {
        if (!isAdded())
            return;
        metrics.read(shell);
        roll(cpuValue, metrics.cpuPercent, lastCpu, "%");
        lastCpu = metrics.cpuPercent;
        cpuSub.setText("device".equals(metrics.cpuScope) ? T.t("overview.cpu_device_scope")
                : T.t("overview.cpu_process_scope", metrics.processCount));
        sparkline.push(metrics.cpuPercent);
        roll(memValue, metrics.memPercent(), lastMem, "%");
        lastMem = metrics.memPercent();
        long avail = metrics.memTotal > 0 ? metrics.memTotal - metrics.memUsed : -1;
        memSub.setText(T.t("overview.mem_used_avail", Metrics.gb(metrics.memUsed), Metrics.gb(avail)));
        roll(diskValue, metrics.diskPercent(), lastDisk, "%");
        lastDisk = metrics.diskPercent();
        long diskFree = metrics.diskTotal > 0 ? metrics.diskTotal - metrics.diskUsed : -1;
        diskSub.setText(T.t("overview.disk_used_free", Metrics.gb(metrics.diskUsed), Metrics.gb(diskFree)));
        roll(batteryValue, metrics.batteryPercent, lastBat, "%");
        lastBat = metrics.batteryPercent;
        batterySub.setText(metrics.charging ? T.t("overview.charging") : T.t("overview.on_battery"));
        networkValue.setText(T.networkLabel(metrics.network));
        gpuValue.setText(Metrics.gpu());
        updateInsights();
    }

    /** Numbers roll to their new value instead of jumping (Kinetik's Stat Card). */
    private void roll(TextView view, int value, int previous, String suffix) {
        if (value < 0) {
            view.setText("—");
        } else if (previous >= 0 && previous != value) {
            app.aomarchy.ui.Motion.number(view, previous, value, suffix);
        } else {
            view.setText(value + suffix);
        }
    }
    private boolean isAdded() {
        return root.getParent() != null;
    }

    private static String duration(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60;
        if (h > 0)
            return T.t("time.hours_minutes", h, m);
        return T.t("time.minutes_seconds", m, s % 60);
    }
}

/** 系统: the Linux system itself — install/reinstall from the release or a local archive, logs. */
class SystemPage extends Page {
    private static final int PICK_ARCHIVE = 41;

    private TextView stateValue;
    private TextView size;
    private long knownSize = -1;
    private boolean sizing;
    private TextView archiveInfo;
    private TextView progressText;
    private TextView tileOnline;
    private TextView tileLocal;
    private app.aomarchy.ui.SpringBar progress;
    private LinearLayout progressBox;
    private LinearLayout startBox;
    private LinearLayout backupList;
    private LinearLayout backupProgressBox;
    private TextView backupText;
    private TextView backupStats;
    private LinearLayout logsBox;
    private app.aomarchy.ui.SpringBar backupBar;
    private File pendingExport;
    private boolean importing;
    private android.net.Uri localUri;
    private String localName;
    private long localBytes = -1;
    private boolean sourceLocal;
    private boolean installing;

    SystemPage(ShellActivity shell) {
        super(shell);
    }

    @Override
    void build(LinearLayout body) {
        LinearLayout sys = Ui.card(shell, T.t("card.current_system"));
        stateValue = value("—", Ui.DIM);
        sys.addView(Ui.kv(shell, T.t("label.status"), stateValue));
        size = value("—", Ui.TEXT);
        sys.addView(Ui.kv(shell, T.t("label.usage"), size));
        sys.addView(Ui.kv(shell, T.t("label.path"), value("files/rootfs", Ui.DIM)));
        body.addView(sys);

        LinearLayout install = Ui.card(shell, T.t("card.install_system"));
        install.addView(Ui.text(shell, T.t("system.install_desc"), 13, Ui.DIM, false));
        install.addView(Ui.gap(shell, 12));

        tileOnline = sourceTile(T.t("source.online") + "\n" + T.t("source.online_sub"), false);
        tileLocal = sourceTile(T.t("source.local") + "\n" + T.t("source.local_sub"), true);
        LinearLayout srcRow = Ui.row(shell);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = Ui.dp(shell, 10);
        srcRow.addView(tileOnline, lp);
        srcRow.addView(tileLocal, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        install.addView(srcRow);

        sourceLocal = Installer.staged(shell) != null;
        archiveInfo = Ui.text(shell, "", 12, Ui.DIM, false);
        archiveInfo.setPadding(0, Ui.dp(shell, 10), 0, 0);
        install.addView(archiveInfo);

        LinearLayout buttons = Ui.row(shell);
        buttons.setPadding(0, Ui.dp(shell, 12), 0, 0);
        buttons.addView(button(T.t("btn.pick_local"), false, v -> pickArchive()));
        buttons.addView(Ui.space(shell, 10));
        startBox = Ui.row(shell);
        startBox.addView(button(T.t("btn.begin_install"), true, v -> confirmInstall()));
        buttons.addView(startBox);
        install.addView(buttons);

        progressBox = Ui.column(shell);
        progressBox.setVisibility(View.GONE);
        progressBox.setPadding(0, Ui.dp(shell, 14), 0, 0);
        progressText = Ui.text(shell, "", 12, Ui.DIM, false);
        progressBox.addView(progressText);
        progress = new app.aomarchy.ui.SpringBar(shell);
        decorative(progress); // the progress text above it already reports the percentage
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(shell, 10));
        plp.topMargin = Ui.dp(shell, 8);
        progressBox.addView(progress, plp);
        progressBox.addView(Ui.gap(shell, 8));
        progressBox.addView(button(T.t("btn.cancel"), false, v -> Installer.cancel()));
        install.addView(progressBox);
        body.addView(install);

        LinearLayout backup = Ui.card(shell, T.t("card.backup"));
        backup.addView(Ui.text(shell, T.t("system.backup_desc"), 12, Ui.DIM, false));
        backup.addView(Ui.gap(shell, 8));
        LinearLayout backupButtons = Ui.row(shell);
        backupButtons.addView(button(T.t("btn.create_backup"), true, v -> startBackup()));
        backupButtons.addView(Ui.space(shell, 8));
        TextView importBtn = Ui.text(shell, T.t("btn.import_backup"), 13, Ui.ACCENT, false);
        importBtn.setPadding(Ui.dp(shell, 4), Ui.dp(shell, 10), Ui.dp(shell, 4), Ui.dp(shell, 10));
        importBtn.setMinimumHeight(Ui.dp(shell, 48));
        importBtn.setOnClickListener(v -> importBackup());
        backupButtons.addView(importBtn);
        backup.addView(backupButtons);

        backupStats = Ui.text(shell, "", 12, Ui.DIM, false);
        backup.addView(Ui.gap(shell, 4));
        backup.addView(backupStats);
        backup.addView(Ui.text(shell, T.t("system.backup_keep_note"), 11, Ui.FAINT, false));
        LinearLayout backupTools = Ui.row(shell);
        TextView pruneBtn = Ui.text(shell, T.t("system.backup_prune"), 13, Ui.ACCENT, true);
        pruneBtn.setPadding(Ui.dp(shell, 4), Ui.dp(shell, 8), Ui.dp(shell, 4), Ui.dp(shell, 8));
        pruneBtn.setMinimumHeight(Ui.dp(shell, 48));
        pruneBtn.setContentDescription(T.t("system.backup_prune"));
        pruneBtn.setOnClickListener(v -> pruneBackups());
        backupTools.addView(pruneBtn);
        View toolsSpace = new View(shell);
        decorative(toolsSpace);
        backupTools.addView(toolsSpace, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView keepBtn = Ui.text(shell, T.t("system.backup_keep") + " ›", 13, Ui.ACCENT, true);
        keepBtn.setPadding(Ui.dp(shell, 4), Ui.dp(shell, 8), Ui.dp(shell, 4), Ui.dp(shell, 8));
        keepBtn.setMinimumHeight(Ui.dp(shell, 48));
        keepBtn.setContentDescription(T.t("system.backup_keep"));
        keepBtn.setOnClickListener(v -> pickBackupKeep());
        backupTools.addView(keepBtn);
        backup.addView(backupTools);

        backupProgressBox = Ui.column(shell);
        backupProgressBox.setVisibility(View.GONE);
        backupProgressBox.setPadding(0, Ui.dp(shell, 10), 0, 0);
        backupText = Ui.text(shell, "", 12, Ui.DIM, false);
        backupProgressBox.addView(backupText);
        backupBar = new app.aomarchy.ui.SpringBar(shell);
        decorative(backupBar); // the backup text above it already reports the percentage
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(shell, 10));
        blp.topMargin = Ui.dp(shell, 6);
        backupProgressBox.addView(backupBar, blp);
        backupProgressBox.addView(Ui.gap(shell, 6));
        TextView backupCancel = Ui.text(shell, T.t("btn.cancel"), 13, Ui.ACCENT, false);
        backupCancel.setMinimumHeight(Ui.dp(shell, 48));
        backupCancel.setOnClickListener(v -> {
            app.aomarchy.data.Backup.cancel();
            v.setEnabled(false);
        });
        backupProgressBox.addView(backupCancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        backup.addView(backupProgressBox);

        backupList = Ui.column(shell);
        backup.addView(backupList);
        body.addView(backup);
        paintBackups();
        LinearLayout logs = Ui.card(shell, T.t("card.logs"));
        LinearLayout logsHead = Ui.row(shell);
        logsHead.addView(Ui.text(shell, T.t("system.logs_desc"), 13, Ui.DIM, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clearLogsBtn = Ui.text(shell, T.t("btn.clear_logs"), 13, Ui.ACCENT, true);
        clearLogsBtn.setPadding(Ui.dp(shell, 6), Ui.dp(shell, 8), Ui.dp(shell, 6), Ui.dp(shell, 8));
        clearLogsBtn.setMinimumHeight(Ui.dp(shell, 48));
        clearLogsBtn.setContentDescription(T.t("btn.clear_logs"));
        clearLogsBtn.setOnClickListener(v -> clearLogs());
        logsHead.addView(clearLogsBtn);
        logs.addView(logsHead);
        logsBox = Ui.column(shell);
        logs.addView(logsBox);
        body.addView(logs);
        paintLogs();

        // Storage breakdown: the rootfs is the big one, backups next; logs and cache are what "清理" can reclaim.
        LinearLayout storage = Ui.card(shell, T.t("card.storage"));
        storage.addView(Ui.text(shell, T.t("storage.computing"), 13, Ui.DIM, false));
        LinearLayout storageRows = Ui.column(shell);
        storage.addView(storageRows);
        body.addView(storage);
        loadStorage(storageRows);
    }

    private void loadStorage(LinearLayout rows) {
        app.aomarchy.data.StorageStats.computeAsync(shell, new app.aomarchy.data.StorageStats.Callback() {
            @Override
            public void onResult(app.aomarchy.data.StorageStats.Snapshot s) {
                rows.removeAllViews();
                rows.addView(Ui.kv(shell, T.t("storage.rootfs"),
                        value(app.aomarchy.data.Installer.humanSize(s.rootfsBytes), Ui.TEXT)));
                rows.addView(Ui.kv(shell, T.t("storage.backups"),
                        value(s.backupCount + " · " + app.aomarchy.data.Installer.humanSize(s.backupBytes), Ui.DIM)));
                rows.addView(Ui.kv(shell, T.t("storage.logs"),
                        value(s.logCount + " · " + app.aomarchy.data.Installer.humanSize(s.logBytes), Ui.DIM)));
                rows.addView(Ui.kv(shell, T.t("storage.cache"),
                        value(app.aomarchy.data.Installer.humanSize(s.cacheBytes), Ui.DIM)));
                View line = new View(shell);
                line.setBackgroundColor(Ui.GLASS_LINE_SOFT);
                rows.addView(line, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(shell, 1))));
                LinearLayout clear = Ui.row(shell);
                clear.setMinimumHeight(Ui.dp(shell, 48));
                clear.setGravity(android.view.Gravity.CENTER_VERTICAL);
                clear.addView(Ui.text(shell, T.t("storage.clear_cache"), 14, Ui.ACCENT, true),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                clear.setContentDescription(T.t("storage.clear_cache"));
                clear.setOnClickListener(v -> clearCache());
                rows.addView(clear);
            }

            @Override
            public void onError(String message) {
                rows.removeAllViews();
                rows.addView(Ui.text(shell, T.t("storage.failed"), 13, Ui.WARN, false));
            }
        });
    }

    private void clearCache() {
        long freed = app.aomarchy.data.StorageStats.clearCache(shell);
        toast(freed > 0 ? T.t("storage.cache_cleared", app.aomarchy.data.Installer.humanSize(freed))
                : T.t("storage.cache_empty"));
        shell.relanguage(); // rebuilds the pages so the storage numbers are measured again
    }

    /** The run directory's logs, newest first; one row opens the last lines. */
    private void paintLogs() {
        if (logsBox == null)
            return;
        logsBox.removeAllViews();
        logsBox.addView(Ui.gap(shell, 6));
        File[] files = SessionService.runDir(shell).listFiles((d, n) -> n.endsWith(".log"));
        if (files == null || files.length == 0) {
            logsBox.addView(Ui.text(shell, T.t("system.no_logs"), 13, Ui.FAINT, false));
            return;
        }
        java.util.Arrays.sort(files, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        for (int i = 0; i < Math.min(6, files.length); i++) {
            File f = files[i];
            logsBox.addView(row(f.getName(), f.length(), () -> showLog(f)));
        }
    }

    private void clearLogs() {
        File[] files = SessionService.runDir(shell).listFiles((d, n) -> n.endsWith(".log"));
        int removed = 0;
        if (files != null)
            for (File f : files)
                if (f.delete())
                    removed++;
        toast(removed > 0 ? T.t("toast.logs_cleared", String.valueOf(removed)) : T.t("toast.logs_empty"));
        paintLogs();
    }

    private static final int EXPORT_BACKUP = 42;
    private static final int IMPORT_BACKUP = 43;

    private void paintBackups() {
        if (backupList == null)
            return;
        backupList.removeAllViews();
        if (backupStats != null) {
            int keep = backupKeep();
            String keepText = keep > 0 ? T.t("system.backup_keep_value", String.valueOf(keep))
                    : T.t("system.backup_keep_none");
            backupStats.setText(T.t("system.backup_stats",
                    String.valueOf(app.aomarchy.data.Backup.count(shell)),
                    smallSize(app.aomarchy.data.Backup.totalBytes(shell))) + " · " + keepText);
        }
        java.util.List<File> backups = app.aomarchy.data.Backup.list(shell);
        if (backups.isEmpty()) {
            backupList.addView(Ui.text(shell, T.t("system.no_backups"), 12, Ui.FAINT, false));
            return;
        }
        backupList.addView(Ui.gap(shell, 4));
        for (int i = 0; i < Math.min(5, backups.size()); i++) {
            File f = backups.get(i);
            LinearLayout r = Ui.row(shell);
            r.setPadding(0, Ui.dp(shell, 6), 0, Ui.dp(shell, 6));
            r.addView(Ui.text(shell, app.aomarchy.data.Backup.stamp(f), 13, Ui.TEXT, false));
            View space = new View(shell);
            decorative(space);
            r.addView(space, new LinearLayout.LayoutParams(0, 1, 1f));
            r.addView(Ui.text(shell, smallSize(f.length()), 12, Ui.DIM, false));
            // One TalkBack stop per backup ("2026-…，1.2 GB，双击可恢复/导出/删除") at least 48dp tall.
            r.setMinimumHeight(Ui.dp(shell, 48));
            for (int c = 0; c < r.getChildCount(); c++)
                r.getChildAt(c).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            r.setContentDescription(app.aomarchy.data.Backup.stamp(f) + "，" + smallSize(f.length()));
            r.setOnClickListener(v -> showBackup(f));
            backupList.addView(r);
        }
    }

    private void showBackup(File backup) {
        String[] actions = {T.t("backup.restore"), T.t("backup.export"), T.t("btn.delete")};
        new android.app.AlertDialog.Builder(shell)
                .setTitle(app.aomarchy.data.Backup.stamp(backup) + " · " + smallSize(backup.length()))
                .setItems(actions, (d, which) -> {
                    if (which == 0)
                        confirmRestore(backup);
                    else if (which == 1)
                        exportBackup(backup);
                    else
                        confirmDelete(backup);
                })
                .setNegativeButton(T.t("btn.close"), null)
                .show();
    }

    private void startBackup() {
        if (app.aomarchy.data.Backup.running() || installing) {
            toast(T.t("toast.busy"));
            return;
        }
        backupProgressBox.setVisibility(View.VISIBLE);
        backupList.setVisibility(View.GONE);
        backupBar.setProgress(0);
        backupBar.setRunning(true);
        backupText.setText(T.t("system.preparing_backup"));
        app.aomarchy.data.Backup.create(shell, backupCallback());
    }

    private app.aomarchy.data.Backup.Callback backupCallback() {
        return new app.aomarchy.data.Backup.Callback() {
            @Override
            public void progress(int permille, String text) {
                shell.runOnUiThread(() -> {
                    if (backupBar != null)
                        backupBar.setProgress(permille);
                    if (backupText != null)
                        backupText.setText(text);
                });
            }

            @Override
            public void done(String message) {
                shell.runOnUiThread(() -> {
                    finishBackup();
                    toast(message);
                });
            }

            @Override
            public void failed(String message) {
                shell.runOnUiThread(() -> {
                    finishBackup();
                    toast(message);
                });
            }
        };
    }

    private void finishBackup() {
        if (backupProgressBox != null) {
            backupProgressBox.setVisibility(View.GONE);
            backupBar.setRunning(false);
        }
        if (backupList != null)
            backupList.setVisibility(View.VISIBLE);
        paintBackups();
        refresh();
        autoPruneBackups();
    }

    /** Backup archives are ~2 GB each: keep only the newest N when the user picked a retention. */
    private void autoPruneBackups() {
        final int keep = backupKeep();
        if (keep <= 0)
            return;
        new Thread(() -> {
            // done() arrives just before Backup clears its running flag; let it finish before pruning.
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                return;
            }
            int removed = app.aomarchy.data.Backup.prune(shell, keep);
            if (removed > 0) {
                shell.runOnUiThread(() -> {
                    paintBackups();
                    toast(T.t("system.backup_pruned", String.valueOf(removed)));
                });
            }
        }, "backup-prune").start();
    }

    private int backupKeep() {
        return shell.getSharedPreferences(Prefs.NAME, android.content.Context.MODE_PRIVATE)
                .getInt("backup_keep", 3);
    }

    /** Retention picker: 0 means keep everything; the default is the newest 3. */
    private void pickBackupKeep() {
        final int[] choices = {3, 2, 5, 10, 0};
        String[] labels = new String[choices.length];
        int checked = 0;
        int current = backupKeep();
        for (int i = 0; i < choices.length; i++) {
            labels[i] = choices[i] == 0 ? T.t("system.backup_keep_none")
                    : T.t("system.backup_keep_value", String.valueOf(choices[i]));
            if (choices[i] == current)
                checked = i;
        }
        new android.app.AlertDialog.Builder(shell)
                .setTitle(T.t("dialog.backup_keep_title"))
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    shell.getSharedPreferences(Prefs.NAME, android.content.Context.MODE_PRIVATE)
                            .edit().putInt("backup_keep", choices[which]).apply();
                    d.dismiss();
                    paintBackups();
                    if (choices[which] > 0)
                        autoPruneBackups();
                })
                .setNegativeButton(T.t("btn.cancel"), null)
                .show();
    }

    private void pruneBackups() {
        if (app.aomarchy.data.Backup.running()) {
            toast(T.t("toast.busy"));
            return;
        }
        final int keep = backupKeep();
        new Thread(() -> {
            int removed = app.aomarchy.data.Backup.prune(shell, keep);
            shell.runOnUiThread(() -> {
                paintBackups();
                toast(T.t("system.backup_pruned", String.valueOf(removed)));
            });
        }, "backup-prune-manual").start();
    }

    private void confirmRestore(File backup) {
        new android.app.AlertDialog.Builder(shell)
                .setTitle(T.t("dialog.restore_title"))
                .setMessage(T.t("dialog.restore_message"))
                .setNegativeButton(T.t("btn.cancel"), null)
                .setPositiveButton(T.t("btn.restore"), (d, w) -> {
                    backupProgressBox.setVisibility(View.VISIBLE);
                    backupList.setVisibility(View.GONE);
                    backupBar.setProgress(0);
                    backupBar.setRunning(true);
                    backupText.setText(T.t("system.unpacking_backup"));
                    app.aomarchy.data.Backup.restore(shell, backup, backupCallback());
                })
                .show();
    }

    private void exportBackup(File backup) {
        pendingExport = backup;
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(android.content.Intent.CATEGORY_OPENABLE)
                .setType("application/gzip")
                .putExtra(android.content.Intent.EXTRA_TITLE, backup.getName());
        shell.startActivityForResult(i, EXPORT_BACKUP);
    }

    private void exportTo(android.net.Uri target) {
        File src = pendingExport;
        pendingExport = null;
        if (src == null)
            return;
        toast(T.t("toast.exporting", src.getName()));
        new Thread(() -> {
            try (java.io.InputStream in = new java.io.FileInputStream(src);
                    java.io.OutputStream out = shell.getContentResolver().openOutputStream(target)) {
                if (out == null)
                    throw new java.io.IOException(T.t("error.cannot_write_target"));
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0)
                    out.write(buf, 0, n);
                shell.runOnUiThread(() -> toast(T.t("toast.exported")));
            } catch (Exception e) {
                shell.runOnUiThread(() -> toast(T.t("toast.export_failed", String.valueOf(e.getMessage()))));
            }
        }, "backup-export").start();
    }

    private void importBackup() {
        importing = true;
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(android.content.Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(android.content.Intent.EXTRA_MIME_TYPES, new String[] {
                        "application/gzip", "application/x-gzip", "application/x-tar", "application/octet-stream"});
        shell.startActivityForResult(i, IMPORT_BACKUP);
    }

    private void importFrom(android.net.Uri source) {
        importing = false;
        File dest = new File(shell.getCacheDir(), "import-backup.tar.gz");
        toast(T.t("toast.reading_backup"));
        new Thread(() -> {
            try (java.io.InputStream in = shell.getContentResolver().openInputStream(source);
                    java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
                if (in == null)
                    throw new java.io.IOException(T.t("error.cannot_read_file"));
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0)
                    out.write(buf, 0, n);
                shell.runOnUiThread(() -> confirmRestore(dest));
            } catch (Exception e) {
                shell.runOnUiThread(() -> toast(T.t("toast.read_failed", String.valueOf(e.getMessage()))));
            }
        }, "backup-import").start();
    }
    private void confirmDelete(File backup) {
        new android.app.AlertDialog.Builder(shell)
                .setTitle(T.t("dialog.delete_backup_title"))
                .setMessage(T.t("dialog.delete_backup_message", app.aomarchy.data.Backup.stamp(backup)))
                .setNegativeButton(T.t("btn.cancel"), null)
                .setPositiveButton(T.t("btn.delete"), (d, w) -> {
                    if (backup.delete()) {
                        paintBackups();
                        toast(T.t("toast.deleted"));
                    } else {
                        toast(T.t("toast.delete_failed"));
                    }
                })
                .show();
    }
    /** One of the two install sources; {@code local} is the selection flag (never derived from the label text). */
    private TextView sourceTile(String title, boolean local) {
        TextView t = Ui.text(shell, title, 12, Ui.DIM, false);
        t.setPadding(Ui.dp(shell, 12), Ui.dp(shell, 10), Ui.dp(shell, 12), Ui.dp(shell, 10));
        // Two-line tile: one TalkBack stop ("在线下载，从网络获取安装包"), and the selected state comes from setSelected.
        t.setContentDescription(title.replace('\n', '，'));
        t.setMinimumHeight(Ui.dp(shell, 48));
        t.setOnClickListener(v -> {
            if (installing)
                return;
            sourceLocal = local;
            paintSources();
        });
        return t;
    }

    private View row(String name, long bytes, Runnable onClick) {
        LinearLayout r = Ui.row(shell);
        r.setPadding(0, Ui.dp(shell, 6), 0, Ui.dp(shell, 6));
        r.addView(Ui.text(shell, name, 13, Ui.TEXT, false));
        View space = new View(shell);
        decorative(space);
        r.addView(space, new LinearLayout.LayoutParams(0, 1, 1f));
        r.addView(Ui.text(shell, smallSize(bytes), 12, Ui.DIM, false));
        // One TalkBack stop per log file, with the 48dp minimum touch height.
        r.setMinimumHeight(Ui.dp(shell, 48));
        for (int c = 0; c < r.getChildCount(); c++)
            r.getChildAt(c).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        r.setContentDescription(name + "，" + smallSize(bytes));
        r.setOnClickListener(v -> onClick.run());
        return r;
    }

    private static String smallSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0);
        return Installer.humanSize(bytes);
    }

    private void paintSources() {
        boolean local = sourceLocal;
        tileOnline.setBackground(Ui.surface(shell, 14));
        tileOnline.setTextColor(local ? Ui.DIM : Ui.TEXT);
        tileLocal.setBackground(Ui.surface(shell, 14));
        tileLocal.setTextColor(local ? Ui.TEXT : Ui.DIM);
        // TalkBack: setSelected makes it announce which install source is the current one.
        tileOnline.setSelected(!local);
        tileLocal.setSelected(local);
        File staged = Installer.staged(shell);
        boolean haveLocal = localUri != null || staged != null;
        if (local) {
            if (localUri != null) {
                archiveInfo.setText(localBytes > 0
                        ? T.t("system.selected_sized", localName, Installer.humanSize(localBytes))
                        : T.t("system.selected", localName));
            } else if (staged != null) {
                archiveInfo.setText(T.t("system.found_archive", staged.getName(), Installer.humanSize(staged.length())));
            } else {
                archiveInfo.setText(T.t("system.no_archive"));
            }
        } else {
            archiveInfo.setText(T.t("system.online_note"));
        }
        archiveInfo.setTextColor(haveLocal || !local ? Ui.DIM : Ui.WARN);
    }

    private void pickArchive() {
        android.content.Intent pick = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        pick.putExtra(android.content.Intent.EXTRA_MIME_TYPES, new String[] {
                "application/gzip", "application/x-gzip", "application/x-tar", "application/octet-stream"});
        shell.startActivityForResult(pick, PICK_ARCHIVE);
    }

    @Override
    void onActivityResult(int request, int result, android.content.Intent data) {
        if (request == EXPORT_BACKUP || request == IMPORT_BACKUP) {
            android.net.Uri uri = result == android.app.Activity.RESULT_OK && data != null ? data.getData() : null;
            if (uri == null) {
                toast(T.t("toast.no_file"));
                pendingExport = null;
                return;
            }
            if (request == EXPORT_BACKUP)
                exportTo(uri);
            else
                importFrom(uri);
            return;
        }
        if (request != PICK_ARCHIVE)
            return;
        android.net.Uri picked = result == android.app.Activity.RESULT_OK && data != null ? data.getData() : null;
        if (picked == null) {
            toast(T.t("toast.no_file"));
            return;
        }
        try {
            shell.getContentResolver().takePersistableUriPermission(
                    picked, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // provider that only grants for this session
        }
        new Thread(() -> {
            String name = "lotfix-rootfs.tar.gz";
            long bytes = -1;
            try (android.database.Cursor c = shell.getContentResolver().query(picked,
                    new String[] {android.provider.OpenableColumns.DISPLAY_NAME,
                            android.provider.OpenableColumns.SIZE}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int nc = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nc >= 0 && !c.isNull(nc))
                        name = c.getString(nc);
                    int sc = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                    if (sc >= 0 && !c.isNull(sc))
                        bytes = c.getLong(sc);
                }
            } catch (RuntimeException ignored) {
                // keep the defaults
            }
            final String fileName = name;
            final long fileBytes = bytes;
            shell.runOnUiThread(() -> {
                localUri = picked;
                localName = fileName;
                localBytes = fileBytes;
                sourceLocal = true;
                paintSources();
            });
        }).start();
    }

    private void confirmInstall() {
        if (installing)
            return;
        boolean replace = SessionService.rootfsReady(shell);
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(shell);
        b.setTitle(replace ? T.t("dialog.reinstall_title") : T.t("dialog.install_title"));
        b.setMessage(replace
                ? T.t("dialog.reinstall_message")
                : (sourceLocal ? T.t("dialog.install_local_message") : T.t("dialog.install_online_message")));
        b.setNegativeButton(T.t("btn.cancel"), null);
        b.setPositiveButton(replace ? T.t("btn.delete_reinstall") : T.t("btn.start"), (d, w) -> startInstall(replace));
        b.show();
    }

    private void startInstall(boolean replace) {
        android.net.Uri uri = null;
        File staged = null;
        if (sourceLocal) {
            if (localUri != null) {
                uri = localUri;
            } else if ((staged = Installer.staged(shell)) != null) {
                uri = android.net.Uri.fromFile(staged);
            } else {
                toast(T.t("toast.pick_archive_first"));
                return;
            }
        }
        if (uri != null && "file".equals(uri.getScheme()) && !new File(uri.getPath()).canRead()) {
            toast(T.t("toast.cannot_read_file"));
            return;
        }
        installing = true;
        progressBox.setVisibility(View.VISIBLE);
        startBox.setVisibility(View.GONE);
        progress.setProgress(0);
        progress.setRunning(true);
        progressText.setText(T.t("system.preparing"));
        Installer.install(shell, uri, replace, new Installer.Callback() {
            @Override
            public void progress(int permille, String text) {
                shell.runOnUiThread(() -> {
                    if (permille >= 0)
                        progress.setProgress(permille);
                    progressText.setText(text);
                });
            }

            @Override
            public void done() {
                shell.runOnUiThread(() -> {
                    installing = false;
                    progress.setRunning(false);
                    progressBox.setVisibility(View.GONE);
                    startBox.setVisibility(View.VISIBLE);
                    refresh();
                    toast(T.t("toast.installed"));
                });
            }

            @Override
            public void failed(String message) {
                shell.runOnUiThread(() -> {
                    installing = false;
                    progress.setRunning(false);
                    progressBox.setVisibility(View.GONE);
                    startBox.setVisibility(View.VISIBLE);
                    progressText.setText("");
                    refresh();
                    new android.app.AlertDialog.Builder(shell)
                            .setTitle(T.t("dialog.install_failed"))
                            .setMessage(message)
                            .setPositiveButton(T.t("btn.ok"), null)
                            .show();
                });
            }
        });
    }

    private void showLog(File f) {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
            for (String line; (line = r.readLine()) != null; ) {
                tail.addLast(line);
                if (tail.size() > 60)
                    tail.removeFirst();
            }
            for (String line : tail)
                sb.append(line).append('\n');
        } catch (java.io.IOException e) {
            sb.append(T.t("system.log_read_error", String.valueOf(e)));
        }
        new android.app.AlertDialog.Builder(shell)
                .setTitle(f.getName())
                .setMessage(sb.length() == 0 ? T.t("system.empty") : sb.toString())
                .setPositiveButton(T.t("btn.close"), null)
                .show();
    }

    /** Rootfs size: read the cache from the last run, else walk the tree once in the background and cache it. */
    private void measureRootfs() {
        if (sizing)
            return;
        File rootfs = SessionService.rootfs(shell);
        File cache = new File(shell.getFilesDir(), ".rootfs-size");
        try {
            if (cache.isFile() && cache.lastModified() >= rootfs.lastModified()) {
                long v = Long.parseLong(new String(java.nio.file.Files.readAllBytes(cache.toPath())).trim());
                if (v > 0) {
                    knownSize = v;
                    refresh();
                    return;
                }
            }
        } catch (Exception ignored) {
            // no usable cache: measure below
        }
        sizing = true;
        new Thread(() -> {
            long bytes = Installer.dirSize(rootfs);
            try {
                java.nio.file.Files.write(cache.toPath(), String.valueOf(bytes).getBytes());
                cache.setLastModified(rootfs.lastModified());
            } catch (Exception ignored) {
                // measuring again next time is fine
            }
            shell.runOnUiThread(() -> {
                sizing = false;
                knownSize = bytes;
                refresh();
            });
        }, "rootfs-size").start();
    }

    @Override
    void refresh() {
        boolean ready = SessionService.rootfsReady(shell);
        stateValue.setText(ready ? T.t("state.installed") : T.t("status.not_installed"));
        stateValue.setTextColor(ready ? Ui.SIGNAL : Ui.FAINT);
        if (!ready) {
            knownSize = -1;
            size.setText("—");
            size.setTextColor(Ui.FAINT);
        } else if (knownSize > 0) {
            size.setText(Installer.humanSize(knownSize));
            size.setTextColor(Ui.TEXT);
        } else {
            size.setText(T.t("state.measuring"));
            size.setTextColor(Ui.DIM);
            measureRootfs();
        }
        if (!installing)
            paintSources();
    }
}
/** 应用: Flatpak sandboxes, the desktop's own apps, and Android apps in floating windows. */
class AppsPage extends Page {
    private LinearLayout flatBox;
    private LinearLayout linuxBox;
    private LinearLayout androidBox;
    private TextView status;
    private boolean loaded;

    AppsPage(ShellActivity shell) {
        super(shell);
    }

    @Override
    void build(LinearLayout body) {
        LinearLayout head = Ui.card(shell, null);
        LinearLayout row = Ui.row(shell);
        row.addView(Ui.text(shell, T.t("tab.apps"), 15, Ui.TEXT, true));
        row.addView(new View(shell), new LinearLayout.LayoutParams(0, 1, 1f));
        status = Ui.text(shell, T.t("apps.loading"), 12, Ui.DIM, false);
        row.addView(status);
        TextView refresh = Ui.text(shell, T.t("btn.refresh"), 13, Ui.ACCENT, false);
        refresh.setPadding(Ui.dp(shell, 12), 0, 0, 0);
        refresh.setMinimumHeight(Ui.dp(shell, 48));
        refresh.setOnClickListener(v -> load());
        row.addView(refresh);
        head.addView(row);
        body.addView(head);

        // Curated catalog: the install command is typed into the terminal, so nothing runs behind the user's back.
        LinearLayout catalog = Ui.card(shell, T.t("catalog.title"));
        catalog.addView(Ui.text(shell, T.t("catalog.desc"), 12, Ui.DIM, false));
        String catalogGroup = null;
        for (app.aomarchy.data.AppCatalog.Entry entry : app.aomarchy.data.AppCatalog.all()) {
            if (!entry.groupKey.equals(catalogGroup)) {
                catalogGroup = entry.groupKey;
                catalog.addView(Ui.gap(shell, 6));
                catalog.addView(Ui.text(shell, T.t(entry.groupKey), 12, Ui.FAINT, true));
            }
            catalog.addView(catalogRow(entry));
        }
        body.addView(catalog);

        flatBox = Ui.card(shell, T.t("card.flatpak"));
        body.addView(flatBox);

        linuxBox = Ui.card(shell, T.t("card.omarchy_apps"));
        body.addView(linuxBox);

        androidBox = Ui.card(shell, T.t("card.android_apps"));
        body.addView(androidBox);
    }

    @Override
    void onShown() {
        if (!loaded)
            load();
    }

    private void load() {
        loaded = true;
        status.setText(T.t("apps.loading"));
        new Thread(() -> {
            java.util.List<Apps.LinuxApp> flat;
            java.util.List<Apps.LinuxApp> linux;
            java.util.List<Apps.AndroidApp> android;
            try {
                flat = Apps.flatpak(shell);
                linux = Apps.nativeApps(shell);
                android = Apps.android(shell);
            } catch (RuntimeException e) {
                shell.runOnUiThread(() -> status.setText(T.t("apps.load_failed", String.valueOf(e.getMessage()))));
                return;
            }
            shell.runOnUiThread(() -> {
                fillFlatpak(flat);
                fillLinux(linux);
                fillAndroid(android);
                status.setText(T.t("apps.counts_summary", flat.size(), linux.size(), android.size()));
            });
        }, "apps-list").start();
    }

    private void fillFlatpak(java.util.List<Apps.LinuxApp> apps) {
        flatBox.removeAllViews();
        flatBox.addView(Ui.text(shell, T.t("apps.flatpak_hint"), 12, Ui.DIM, false));
        if (apps.isEmpty()) {
            flatBox.addView(Ui.text(shell, T.t("apps.no_flatpak"), 13, Ui.FAINT, false));
            return;
        }
        for (int i = 0; i < Math.min(apps.size(), 24); i++)
            flatBox.addView(appRow(apps.get(i)));
    }

    private void fillLinux(java.util.List<Apps.LinuxApp> apps) {
        linuxBox.removeAllViews();
        linuxBox.addView(Ui.text(shell, T.t("apps.linux_hint"), 12, Ui.DIM, false));
        for (int i = 0; i < Math.min(apps.size(), 24); i++)
            linuxBox.addView(appRow(apps.get(i)));
        if (apps.isEmpty())
            linuxBox.addView(Ui.text(shell, T.t("apps.no_linux"), 13, Ui.FAINT, false));
    }

    private View catalogRow(app.aomarchy.data.AppCatalog.Entry entry) {
        LinearLayout r = Ui.row(shell);
        r.setPadding(0, Ui.dp(shell, 6), 0, Ui.dp(shell, 6));
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout texts = Ui.column(shell);
        texts.addView(Ui.text(shell, T.t(entry.labelKey), 15, Ui.TEXT, false));
        texts.addView(Ui.text(shell, T.t(entry.hintKey), 11, Ui.DIM, false));
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView install = Ui.text(shell, T.t("catalog.install"), 13, Ui.ACCENT, true);
        install.setPadding(Ui.dp(shell, 12), 0, 0, 0);
        install.setMinimumHeight(Ui.dp(shell, 48));
        install.setContentDescription(T.t("catalog.install") + " " + T.t(entry.labelKey));
        install.setOnClickListener(v -> shell.openTerminalWith(entry.command));
        r.addView(install);
        return r;
    }

    private View appRow(Apps.LinuxApp app) {
        LinearLayout r = Ui.row(shell);
        r.setPadding(0, Ui.dp(shell, 6), 0, Ui.dp(shell, 6));
        r.addView(Ui.text(shell, app.name, 15, Ui.TEXT, false),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView id = Ui.text(shell, app.id, 11, Ui.FAINT, false);
        id.setSingleLine(true);
        id.setEllipsize(android.text.TextUtils.TruncateAt.END);
        id.setPadding(Ui.dp(shell, 10), 0, Ui.dp(shell, 8), 0);
        r.addView(id, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView open = Ui.text(shell, T.t("btn.open"), 13, Ui.ACCENT, false);
        open.setPadding(Ui.dp(shell, 10), 0, 0, 0);
        open.setMinimumHeight(Ui.dp(shell, 48));
        // "打开" alone says nothing about which app: name it, with the 48dp touch target raised above.
        open.setContentDescription(app.name + "，" + T.t("btn.open").replace("›", "").trim());
        open.setOnClickListener(v -> {
            if (!SessionService.rootfsReady(shell)) {
                toast(T.t("toast.system_missing"));
            } else if (!SessionService.started) {
                toast(T.t("toast.start_session_first"));
            } else {
                Apps.launchLinux(shell, app);
                toast(T.t("toast.opened_on_desktop", app.name));
            }
        });
        r.addView(open);
        return r;
    }

    private void fillAndroid(java.util.List<Apps.AndroidApp> apps) {
        androidBox.removeAllViews();
        androidBox.addView(Ui.text(shell, T.t("apps.android_hint"), 12, Ui.DIM, false));
        if (apps.isEmpty()) {
            androidBox.addView(Ui.text(shell, T.t("apps.no_android"), 13, Ui.FAINT, false));
            return;
        }
        LinearLayout row = null;
        int perRow = shell.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 8 : 4;
        for (int i = 0; i < Math.min(apps.size(), 32); i++) {
            if (i % perRow == 0) {
                row = Ui.row(shell);
                row.setPadding(0, Ui.dp(shell, 4), 0, Ui.dp(shell, 4));
                androidBox.addView(row);
            }
            row.addView(androidTile(apps.get(i)));
        }
    }

    private View androidTile(Apps.AndroidApp app) {
        LinearLayout cell = Ui.column(shell);
        cell.setGravity(android.view.Gravity.CENTER);
        cell.setPadding(0, Ui.dp(shell, 4), 0, Ui.dp(shell, 4));
        android.widget.ImageView icon = new android.widget.ImageView(shell);
        icon.setImageDrawable(app.icon);
        decorative(icon); // the label below names the app
        cell.addView(icon, new LinearLayout.LayoutParams(Ui.dp(shell, 36), Ui.dp(shell, 36)));
        TextView label = Ui.text(shell, app.label, 11, Ui.DIM, false);
        label.setGravity(android.view.Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setPadding(0, Ui.dp(shell, 4), 0, 0);
        cell.addView(label, new LinearLayout.LayoutParams(Ui.dp(shell, 70), ViewGroup.LayoutParams.WRAP_CONTENT));
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); // the cell announces the name
        cell.setContentDescription(app.label);
        cell.setMinimumHeight(Ui.dp(shell, 48));
        cell.setOnClickListener(v -> {
            Apps.openFloating(shell, app.packageName);
            toast(T.t("toast.floating_open", app.label));
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cell.setLayoutParams(lp);
        return cell;
    }
}
/** 控制: the high-frequency switchboard — same view as the half-screen sheet over the desktop. */
class ControlsPage extends Page {
    private ControlsView controls;

    ControlsPage(ShellActivity shell) {
        super(shell);
    }

    @Override
    void build(LinearLayout body) {
        controls = new ControlsView(shell);
        body.addView(controls.root);
    }

    @Override
    void onShown() {
        controls.refresh();
    }

    @Override
    void refresh() {
        if (controls != null)
            controls.refresh();
    }
}
/** 设置: the setup answers (identity, keyboard), startup behaviour, the detailed pages, and about. */
class SettingsPage extends Page {
    private static final String[] LAYOUTS = {"us", "cn", "gb", "fr", "de", "es", "it", "pt", "ru", "ua", "pl", "tr",
            "nl", "be", "ch", "ca", "jp", "kr", "ara", "gr", "il", "ir", "in"};
    private static final String[] LAYOUT_NAMES = {"English (US)", "中文", "English (UK)", "Français", "Deutsch",
            "Español", "Italiano", "Português", "Русский", "Українська", "Polski", "Türkçe", "Nederlands",
            "Belgique", "Suisse", "Canada (FR)", "日本語", "한국어", "العربية", "Ελληνικά", "עברית", "فارسی", "हिन्दी"};

    private TextView nameValue;
    private TextView emailValue;
    private TextView kbValue;
    private SettingsExportView exportView; // the 配置备份 sheet, while it is open
    private KeepAliveView keepAliveView; // the 后台运行 sheet
    private BackgroundPickerView backgroundView; // the 控制台背景 sheet
    private TextView langValue;
    /** The rows whose value changes after a pick: their TalkBack description is rebuilt in {@link #refresh()}. */
    private LinearLayout nameRow;
    private LinearLayout emailRow;
    private LinearLayout kbRow;
    private LinearLayout langRow;

    SettingsPage(ShellActivity shell) {
        super(shell);
    }

    private android.content.SharedPreferences prefs() {
        return shell.getSharedPreferences(Prefs.NAME, android.content.Context.MODE_PRIVATE);
    }

    @Override
    void build(LinearLayout body) {
        LinearLayout identity = Ui.card(shell, T.t("card.identity"));
        identity.addView(Ui.text(shell, T.t("settings.identity_desc"), 12, Ui.DIM, false));
        identity.addView(Ui.gap(shell, 8));
        nameValue = value("—", Ui.TEXT);
        nameRow = row(T.t("label.name"), nameValue, () -> editText(T.t("label.name"), "user_name", nameValue));
        identity.addView(nameRow);
        identity.addView(divider());
        emailValue = value("—", Ui.TEXT);
        emailRow = row(T.t("label.email"), emailValue, () -> editText(T.t("label.email"), "user_email", emailValue));
        identity.addView(emailRow);
        body.addView(identity);

        LinearLayout keyboard = Ui.card(shell, T.t("card.keyboard"));
        keyboard.addView(Ui.text(shell, T.t("settings.keyboard_desc"), 12, Ui.DIM, false));
        keyboard.addView(Ui.gap(shell, 8));
        kbValue = value("—", Ui.TEXT);
        kbRow = row(T.t("label.kb_layout"), kbValue, this::pickLayout);
        keyboard.addView(kbRow);
        body.addView(keyboard);

        LinearLayout startup = Ui.card(shell, T.t("card.startup"));
        // Startup mode now lives in the global top bar (ShellActivity); this card keeps only the launch shortcut.
        LinearLayout row = Ui.row(shell);
        row.setPadding(0, Ui.dp(shell, 8), 0, Ui.dp(shell, 8));
        row.addView(Ui.text(shell, T.t("settings.open_desktop_toggle"), 15, Ui.TEXT, false));
        row.addView(new View(shell), new LinearLayout.LayoutParams(0, 1, 1f));
        final boolean[] on = {prefs().getBoolean("open_desktop_on_start", false)};
        TextView toggle = Ui.text(shell, on[0] ? T.t("state.on") : T.t("state.off"), 14,
                on[0] ? Ui.ACCENT : Ui.DIM, true);
        row.addView(toggle);
        // TalkBack: one stop announcing the startup switch and its state, at least 48dp tall.
        final String startupLabel = T.t("settings.open_desktop_toggle");
        row.setMinimumHeight(Ui.dp(shell, 48));
        for (int i = 0; i < row.getChildCount(); i++)
            row.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.setContentDescription(startupLabel + "，" + T.t(on[0] ? "state.on" : "state.off"));
        row.setOnClickListener(v -> {
            on[0] = !on[0];
            prefs().edit().putBoolean("open_desktop_on_start", on[0]).apply();
            toggle.setText(on[0] ? T.t("state.on") : T.t("state.off"));
            toggle.setTextColor(on[0] ? Ui.ACCENT : Ui.DIM);
            row.setContentDescription(startupLabel + "，" + T.t(on[0] ? "state.on" : "state.off"));
            toast(on[0] ? T.t("toast.next_launch_desktop") : T.t("toast.next_launch_console"));
        });
        row.setClickable(true);
        startup.addView(row);
        body.addView(startup);

        LinearLayout language = Ui.card(shell, T.t("card.language"));
        language.addView(Ui.text(shell, T.t("settings.language_desc"), 12, Ui.DIM, false));
        language.addView(Ui.gap(shell, 4));
        langValue = value("—", Ui.TEXT);
        langRow = row(T.t("label.ui_lang"), langValue, this::pickLanguage);
        language.addView(langRow);
        body.addView(language);

        LinearLayout detailed = Ui.card(shell, T.t("card.detailed"));
        detailed.addView(Ui.text(shell, T.t("settings.detailed_desc"), 12, Ui.DIM, false));
        detailed.addView(Ui.gap(shell, 6));
        detailed.addView(row(T.t("page.keys"), value("›", Ui.FAINT),
                () -> openPage(T.t("page.keys"), KeysSettingsView.build(shell))));
        detailed.addView(divider());
        detailed.addView(row(T.t("page.input"), value("›", Ui.FAINT),
                () -> openPage(T.t("page.input"), InputSettingsView.build(shell))));
        detailed.addView(divider());
        detailed.addView(row(T.t("page.display"), value("›", Ui.FAINT),
                () -> openPage(T.t("page.display"), DisplaySettingsView.build(shell))));
        detailed.addView(divider());
        detailed.addView(row(T.t("page.audio"), value("›", Ui.FAINT),
                () -> openPage(T.t("page.audio"), AudioSettingsView.build(shell))));
        detailed.addView(divider());
        detailed.addView(row(T.t("page.settings_io"), value("›", Ui.FAINT), () -> {
            exportView = SettingsExportView.create(shell);
            openPage(T.t("page.settings_io"), exportView.view(), this::dropExportView);
        }));
        detailed.addView(divider());
        detailed.addView(row(T.t("keepalive.title"), value("›", Ui.FAINT), () -> {
            keepAliveView = KeepAliveView.create(shell);
            openPage(T.t("keepalive.title"), keepAliveView.view(), () -> {
                if (keepAliveView != null) {
                    keepAliveView.dispose();
                    keepAliveView = null;
                }
            });
        }));
        detailed.addView(divider());
        detailed.addView(row(T.t("card.console_bg"), value("›", Ui.FAINT), () -> {
            backgroundView = BackgroundPickerView.create(shell);
            backgroundView.setListener(shell::refreshConsoleBackground); // live preview while dragging blur / switching mode
            openPage(T.t("card.console_bg"), backgroundView.view(), () -> {
                if (backgroundView != null) {
                    backgroundView.dispose();
                    backgroundView = null;
                }
                shell.refreshConsoleBackground(); // a picked image shows as soon as the sheet closes
            });
        }));
        body.addView(detailed);

        LinearLayout about = Ui.card(shell, T.t("card.about"));
        about.addView(Ui.kv(shell, T.t("label.version"), value(versionName(), Ui.TEXT)));
        about.addView(Ui.kv(shell, T.t("label.package"), value(shell.getPackageName(), Ui.DIM)));
        about.addView(Ui.kv(shell, "Android", value(android.os.Build.VERSION.RELEASE + " (API "
                + android.os.Build.VERSION.SDK_INT + ")", Ui.DIM)));
        about.addView(divider());
        about.addView(row(T.t("btn.check_update"), value("", Ui.FAINT), this::checkForUpdates));
        about.addView(Ui.kv(shell, T.t("label.last_check"), value(lastCheckText(), Ui.DIM)));
        body.addView(about);
    }

    /** How long ago the release feed was last read, in the same relative wording as the rest of the console. */
    private String lastCheckText() {
        long ms = app.aomarchy.UpdateCheck.lastCheckMillis(shell);
        if (ms <= 0)
            return T.t("label.never");
        long minutes = Math.max(0, (System.currentTimeMillis() - ms) / 60000L);
        if (minutes < 1)
            return T.t("time.just_now");
        if (minutes < 60)
            return T.t("time.minutes_ago", String.valueOf(minutes));
        long hours = minutes / 60;
        if (hours < 24)
            return T.t("time.hours_ago", String.valueOf(hours));
        return T.t("time.days_ago", String.valueOf(hours / 24));
    }

    /** Asks GitHub for the newest release and offers the download page when this build is older. */
    private void checkForUpdates() {
        toast(T.t("btn.checking_update"));
        app.aomarchy.UpdateCheck.check(shell, new app.aomarchy.UpdateCheck.Callback() {
            @Override
            public void onResult(boolean newer, String version, String url) {
                app.aomarchy.UpdateCheck.rememberResult(shell, version, url);
                if (!newer) {
                    toast(T.t("toast.up_to_date", versionName()));
                    return;
                }
                new android.app.AlertDialog.Builder(shell)
                        .setTitle(T.t("dialog.update_available_title", version))
                        .setMessage(T.t("dialog.update_available_message", versionName()))
                        .setPositiveButton(T.t("btn.open_download"), (d, w) -> {
                            try {
                                shell.startActivity(new android.content.Intent(
                                        android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                            } catch (RuntimeException ignored) {
                                // no browser: nothing else to do
                            }
                        })
                        .setNegativeButton(T.t("btn.cancel"), null)
                        .show();
            }

            @Override
            public void onError(String message) {
                toast(T.t("toast.update_failed", message));
            }
        });
    }

    /** 界面语言: one of 跟随系统 / 中文 / English, saved as {@code ui_lang}, then the shell rebuilds itself. */
    private void pickLanguage() {
        final String[] values = {T.LANG_AUTO, T.LANG_ZH, T.LANG_EN};
        String saved = T.savedLang(shell);
        String[] labels = {T.t("lang.auto"), T.t("lang.zh"), T.t("lang.en")};
        int checked = 0;
        for (int i = 0; i < values.length; i++)
            if (values[i].equals(saved))
                checked = i;
        new android.app.AlertDialog.Builder(shell)
                .setTitle(T.t("label.ui_lang"))
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    T.setLang(shell, values[which]);
                    d.dismiss();
                    shell.relanguage(); // rebuild every page in place, staying on the Settings tab
                    toast(T.t("toast.language_changed"));
                })
                .setNegativeButton(T.t("btn.cancel"), null)
                .show();
    }

    private LinearLayout row(String label, TextView valueView, Runnable onClick) {
        LinearLayout r = Ui.row(shell);
        r.setPadding(0, Ui.dp(shell, 8), 0, Ui.dp(shell, 8));
        r.addView(Ui.text(shell, label, 15, Ui.TEXT, false));
        View space = new View(shell);
        decorative(space);
        r.addView(space, new LinearLayout.LayoutParams(0, 1, 1f));
        r.addView(valueView);
        // TalkBack: the whole row is one stop ("姓名，Ada") at least 48dp tall; the label/value texts are hidden.
        r.setMinimumHeight(Ui.dp(shell, 48));
        for (int i = 0; i < r.getChildCount(); i++)
            r.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        r.setContentDescription(describeRow(label, valueView));
        r.setOnClickListener(v -> onClick.run());
        return r;
    }

    /** "label，value"; a "›" value means the row opens something, so say "打开" instead of reading the chevron. */
    private static String describeRow(String label, TextView valueView) {
        CharSequence text = valueView.getText();
        String value = text == null ? "" : text.toString().trim();
        if (value.isEmpty())
            return label;
        if ("›".equals(value))
            return label + "，" + T.t("btn.open").replace("›", "").trim();
        return label + "，" + value;
    }

    /** Decorative separator: never a TalkBack stop. */
    private View divider() {
        View v = Ui.divider(shell);
        decorative(v);
        return v;
    }

    private void editText(String title, String key, TextView target) {
        android.widget.EditText input = new android.widget.EditText(shell);
        input.setText(prefs().getString(key, ""));
        input.setSingleLine(true);
        new android.app.AlertDialog.Builder(shell)
                .setTitle(title)
                .setView(input)
                .setNegativeButton(T.t("btn.cancel"), null)
                .setPositiveButton(T.t("btn.save"), (d, w) -> {
                    prefs().edit().putString(key, input.getText().toString().trim()).apply();
                    refresh();
                    toast(T.t("toast.saved_next_desktop"));
                })
                .show();
    }

    /** Startup mode is chosen from the global top bar now (ShellActivity.pickStartupMode). */
    private void pickLayout() {
        String current = prefs().getString("kb_layouts", "");
        int checked = 0;
        for (int i = 0; i < LAYOUTS.length; i++)
            if (LAYOUTS[i].equals(current))
                checked = i;
        String[] names = new String[LAYOUT_NAMES.length];
        for (int i = 0; i < names.length; i++)
            names[i] = LAYOUT_NAMES[i] + "  ·  " + LAYOUTS[i];
        new android.app.AlertDialog.Builder(shell)
                .setTitle(T.t("dialog.kb_layout_title"))
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    prefs().edit().putString("kb_layouts", LAYOUTS[which]).apply();
                    d.dismiss();
                    refresh();
                    toast(T.t("toast.layout_saved", LAYOUT_NAMES[which]));
                })
                .setNegativeButton(T.t("btn.cancel"), null)
                .show();
    }

    /** Opens a native detail page (the rebuilt 详细设置 sub-pages) as a full-screen sheet over the console. */
    private void openPage(String title, View content) {
        openPage(title, content, null);
    }

    /** The same sheet, with something to release when it closes (a page holding a file-picker instance). */
    private void openPage(String title, View content, Runnable onDismiss) {
        android.app.Dialog dialog = new android.app.Dialog(shell,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen);

        LinearLayout wrap = Ui.column(shell);
        wrap.setBackgroundColor(android.graphics.Color.TRANSPARENT); // the dimmed console stays visible behind

        LinearLayout bar = Ui.row(shell);
        bar.setPadding(Ui.dp(shell, 12), Ui.dp(shell, 10), Ui.dp(shell, 12), Ui.dp(shell, 10));
        TextView back = Ui.text(shell, T.t("btn.back"), 14, Ui.ACCENT, true);
        back.setPadding(Ui.dp(shell, 8), Ui.dp(shell, 4), Ui.dp(shell, 8), Ui.dp(shell, 4));
        back.setMinimumHeight(Ui.dp(shell, 48));
        back.setOnClickListener(v -> dialog.dismiss());
        bar.addView(back);
        View barSpace = new View(shell);
        decorative(barSpace);
        bar.addView(barSpace, new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(Ui.text(shell, title, 16, Ui.TEXT, true));
        wrap.addView(bar);

        ScrollView scroll = new ScrollView(shell);
        scroll.setFillViewport(true);
        content.setPadding(Ui.dp(shell, 16), Ui.dp(shell, 6), Ui.dp(shell, 16), Ui.dp(shell, 24));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dialog.setContentView(wrap);
        dialog.setOnDismissListener(d -> {
            app.aomarchy.ui.Toasts.clearSheet();
            if (onDismiss != null)
                onDismiss.run();
            refresh();
        });
        dialog.show();
        // Toasts belong in the sheet's window: the activity's toast view would be hidden behind this Dialog.
        if (dialog.getWindow() != null)
            app.aomarchy.ui.Toasts.useSheet((ViewGroup) dialog.getWindow().getDecorView());
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x990A0C10));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    /** Releases the file-picker page when its sheet closes, so a late result cannot touch a dead view. */
    private void dropExportView() {
        if (exportView != null) {
            exportView.dispose();
            exportView = null;
        }
    }

    @Override
    void onActivityResult(int request, int result, android.content.Intent data) {
        if (exportView != null && exportView.onActivityResult(request, result, data))
            return;
        if (backgroundView != null && backgroundView.onActivityResult(request, result, data))
            return;
        super.onActivityResult(request, result, data);
    }

    @Override
    void refresh() {
        nameValue.setText(orDash(prefs().getString("user_name", "")));
        refreshRow(nameRow, T.t("label.name"), nameValue);
        emailValue.setText(orDash(prefs().getString("user_email", "")));
        refreshRow(emailRow, T.t("label.email"), emailValue);
        String kb = prefs().getString("kb_layouts", "");
        if (kb.isEmpty())
            kb = installedLayout(); // what the installer picked from the device language
        kbValue.setText(orDash(kb));
        refreshRow(kbRow, T.t("label.kb_layout"), kbValue);
        langValue.setText(languageLabel(T.savedLang(shell)));
        refreshRow(langRow, T.t("label.ui_lang"), langValue);
    }

    /** Keeps the TalkBack description of a value row in step with the value currently on screen. */
    private static void refreshRow(LinearLayout row, String label, TextView valueView) {
        if (row != null)
            row.setContentDescription(describeRow(label, valueView));
    }

    /** The label for the stored {@code ui_lang} value. */
    private static String languageLabel(String value) {
        if (T.LANG_ZH.equals(value))
            return T.t("lang.zh");
        if (T.LANG_EN.equals(value))
            return T.t("lang.en");
        return T.t("lang.auto");
    }

    private static String orDash(String s) {
        return s == null || s.trim().isEmpty() ? T.t("state.not_set") : s;
    }

    /** KB_LAYOUTS from the rootfs's setup.conf: the layout the install chose. */
    private String installedLayout() {
        try {
            File f = new File(SessionService.rootfs(shell), "root/.config/aomarchy/setup.conf");
            if (!f.isFile())
                return "";
            for (String line : java.nio.file.Files.readAllLines(f.toPath())) {
                if (line.startsWith("KB_LAYOUTS=")) {
                    String v = line.substring("KB_LAYOUTS=".length()).trim().split("\\s+")[0];
                    if (v.startsWith("'") && v.endsWith("'"))
                        v = v.substring(1, v.length() - 1);
                    return v;
                }
            }
        } catch (Exception ignored) {
            // no system installed yet
        }
        return "";
    }

    private String versionName() {
        try {
            return shell.getPackageManager().getPackageInfo(shell.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}