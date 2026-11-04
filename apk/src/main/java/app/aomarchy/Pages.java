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
        root.setBackgroundColor(Ui.BG);
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

    TextView value(String text, int color) {
        return Ui.mono(shell, text, 15, color);
    }

    void toast(String text) {
        shell.toast(text);
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
    private TextView heroChip;
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

    OverviewPage(ShellActivity shell) {
        super(shell);
    }

    @Override
    void build(LinearLayout body) {
        LinearLayout hero = Ui.card(shell, null);
        LinearLayout head = Ui.row(shell);
        state = Ui.text(shell, "会话", 18, Ui.TEXT, true);
        head.addView(state);
        head.addView(new View(shell), new LinearLayout.LayoutParams(0, 1, 1f));
        heroChip = Ui.chip(shell, "● 就绪", Ui.MINT);
        head.addView(heroChip);
        hero.addView(head);
        hero.addView(Ui.gap(shell, 6));
        uptime = Ui.text(shell, "Arch Linux · Hyprland · 整个系统跑在 Android 上，不需要 root 和 proot。",
                13, Ui.DIM, false);
        hero.addView(uptime);
        hero.addView(Ui.gap(shell, 10));
        LinearLayout actions = Ui.row(shell);
        heroEntry = Ui.button(shell, "进入桌面", true, v -> shell.openDesktop());
        actions.addView(heroEntry);
        actions.addView(Ui.space(shell, 10));
        actions.addView(Ui.button(shell, "安装 / 重装系统", false, v -> shell.showTab(1)));
        actions.addView(new View(shell), new LinearLayout.LayoutParams(0, 1, 1f));
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
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(shell, 28));
        slp.topMargin = Ui.dp(shell, 3);
        cpuCard.addView(sparkline, slp);

        LinearLayout memCard = card("内存");
        memValue = big("—");
        memCard.addView(memValue);
        memSub = Ui.text(shell, "", 12, Ui.DIM, false);
        memCard.addView(memSub);

        LinearLayout diskCard = card("磁盘 · 系统");
        diskValue = big("—");
        diskCard.addView(diskValue);
        diskSub = Ui.text(shell, "", 12, Ui.DIM, false);
        diskCard.addView(diskSub);

        LinearLayout batCard = card("电量");
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
        LinearLayout netGpu = card("网络与图形");
        networkValue = value("—", Ui.TEXT);
        netGpu.addView(Ui.kv2(shell, "网络", networkValue, "Android 网络栈提供"));
        gpuValue = value("—", Ui.TEXT);
        netGpu.addView(Ui.kv2(shell, "GPU", gpuValue, "Mesa KGSL · Adreno"));
        netGpu.addView(Ui.text(shell, "两者都由 Android 侧透传，Linux 里看到的与系统一致。", 11, Ui.FAINT, false));
        body.addView(netGpu);

        LinearLayout tips = Ui.card(shell, "提示");
        tips.addView(Ui.text(shell, "桌面里点左上角「A」回控制台；会话通知里也有「控制」「控制台」。第一次使用：先去「系统」装系统，再到「设置」填身份与键盘。",
                12, Ui.DIM, false));
        body.addView(tips);
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
        state.setText(!ready ? "尚未安装系统" : terminal ? "终端模式" : (SessionService.started ? "会话运行中" : "会话已就绪"));
        state.setTextColor(ready ? Ui.TEXT : Ui.DIM);
        heroChip.setText(!ready ? "○ 未安装" : terminal ? "● 终端模式" : (SessionService.started ? "● 运行中" : "● 就绪"));
        heroChip.setTextColor(ready ? Ui.MINT : Ui.FAINT);
        if (heroEntry != null)
            heroEntry.setText(terminal ? "进入终端" : "进入桌面");
        uptime.setText(!ready ? "Arch Linux · Hyprland · 整个系统跑在 Android 上，不需要 root 和 proot。"
                : terminal ? "终端模式：只跑一个 shell，不启动 Hyprland，实测比完整桌面省约 700 MB。"
                : SessionService.started ? "已运行 " + duration(System.currentTimeMillis() - SessionService.startedAt)
                : "Arch Linux · Hyprland · 整个系统跑在 Android 上，不需要 root 和 proot。");
        poll();
    }

    private void poll() {
        if (!isAdded())
            return;
        metrics.read(shell);
        roll(cpuValue, metrics.cpuPercent, lastCpu, "%");
        lastCpu = metrics.cpuPercent;
        cpuSub.setText("device".equals(metrics.cpuScope) ? "设备整体占用" : metrics.processCount + " 个进程 · 会话整体");
        sparkline.push(metrics.cpuPercent);
        roll(memValue, metrics.memPercent(), lastMem, "%");
        lastMem = metrics.memPercent();
        long avail = metrics.memTotal > 0 ? metrics.memTotal - metrics.memUsed : -1;
        memSub.setText(Metrics.gb(metrics.memUsed) + " 已用 · 可用 " + Metrics.gb(avail));
        roll(diskValue, metrics.diskPercent(), lastDisk, "%");
        lastDisk = metrics.diskPercent();
        long diskFree = metrics.diskTotal > 0 ? metrics.diskTotal - metrics.diskUsed : -1;
        diskSub.setText(Metrics.gb(metrics.diskUsed) + " 已用 · 剩余 " + Metrics.gb(diskFree));
        roll(batteryValue, metrics.batteryPercent, lastBat, "%");
        lastBat = metrics.batteryPercent;
        batterySub.setText(metrics.charging ? "充电中" : "使用电池");
        networkValue.setText(metrics.network);
        gpuValue.setText(Metrics.gpu());
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
            return h + " 小时 " + m + " 分";
        return m + " 分 " + (s % 60) + " 秒";
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
        LinearLayout sys = Ui.card(shell, "当前系统");
        stateValue = value("—", Ui.DIM);
        sys.addView(Ui.kv(shell, "状态", stateValue));
        size = value("—", Ui.TEXT);
        sys.addView(Ui.kv(shell, "占用", size));
        sys.addView(Ui.kv(shell, "路径", value("files/rootfs", Ui.DIM)));
        body.addView(sys);

        LinearLayout install = Ui.card(shell, "安装系统");
        install.addView(Ui.text(shell, "首次安装或重装 Linux 系统。安装包来自 GitHub Release 的在线下载，或设备上的 lotfix-rootfs.tar.gz。",
                13, Ui.DIM, false));
        install.addView(Ui.gap(shell, 12));

        tileOnline = sourceTile("在线下载", "GitHub Release · 约 1.9 GB");
        tileLocal = sourceTile("本地归档", "设备上的 lotfix-rootfs.tar.gz");
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
        buttons.addView(Ui.button(shell, "选择本地文件", false, v -> pickArchive()));
        buttons.addView(Ui.space(shell, 10));
        startBox = Ui.row(shell);
        startBox.addView(Ui.button(shell, "开始安装", true, v -> confirmInstall()));
        buttons.addView(startBox);
        install.addView(buttons);

        progressBox = Ui.column(shell);
        progressBox.setVisibility(View.GONE);
        progressBox.setPadding(0, Ui.dp(shell, 14), 0, 0);
        progressText = Ui.text(shell, "", 12, Ui.DIM, false);
        progressBox.addView(progressText);
        progress = new app.aomarchy.ui.SpringBar(shell);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(shell, 10));
        plp.topMargin = Ui.dp(shell, 8);
        progressBox.addView(progress, plp);
        progressBox.addView(Ui.gap(shell, 8));
        progressBox.addView(Ui.button(shell, "取消", false, v -> Installer.cancel()));
        install.addView(progressBox);
        body.addView(install);

        LinearLayout backup = Ui.card(shell, "备份与回滚");
        backup.addView(Ui.text(shell, "把整个系统打包成一个 tar.gz（约 1.6 GB），恢复时整树换入。备份存在本机 Android/data/app.aomarchy/files/backups，也可以导出到别处。",
                12, Ui.DIM, false));
        backup.addView(Ui.gap(shell, 8));
        LinearLayout backupButtons = Ui.row(shell);
        backupButtons.addView(Ui.button(shell, "创建备份", true, v -> startBackup()));
        backupButtons.addView(Ui.space(shell, 8));
        TextView importBtn = Ui.text(shell, "导入备份…", 13, Ui.ACCENT, false);
        importBtn.setPadding(Ui.dp(shell, 4), Ui.dp(shell, 10), Ui.dp(shell, 4), Ui.dp(shell, 10));
        importBtn.setOnClickListener(v -> importBackup());
        backupButtons.addView(importBtn);
        backup.addView(backupButtons);

        backupProgressBox = Ui.column(shell);
        backupProgressBox.setVisibility(View.GONE);
        backupProgressBox.setPadding(0, Ui.dp(shell, 10), 0, 0);
        backupText = Ui.text(shell, "", 12, Ui.DIM, false);
        backupProgressBox.addView(backupText);
        backupBar = new app.aomarchy.ui.SpringBar(shell);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(shell, 10));
        blp.topMargin = Ui.dp(shell, 6);
        backupProgressBox.addView(backupBar, blp);
        backupProgressBox.addView(Ui.gap(shell, 6));
        backupProgressBox.addView(Ui.text(shell, "取消", 13, Ui.ACCENT, false), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        backupProgressBox.getChildAt(backupProgressBox.getChildCount() - 1).setOnClickListener(v -> {
            app.aomarchy.data.Backup.cancel();
            v.setEnabled(false);
        });
        backup.addView(backupProgressBox);

        backupList = Ui.column(shell);
        backup.addView(backupList);
        body.addView(backup);
        paintBackups();
        LinearLayout logs = Ui.card(shell, "日志");
        logs.addView(Ui.text(shell, "会话日志在 files/run/ 下，点开看最后几行。", 13, Ui.DIM, false));
        logs.addView(Ui.gap(shell, 6));
        File[] files = SessionService.runDir(shell).listFiles((d, n) -> n.endsWith(".log"));
        if (files == null || files.length == 0) {
            logs.addView(Ui.text(shell, "还没有日志（系统未启动过）", 13, Ui.FAINT, false));
        } else {
            java.util.Arrays.sort(files, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
            for (int i = 0; i < Math.min(6, files.length); i++) {
                File f = files[i];
                logs.addView(row(f.getName(), f.length(), () -> showLog(f)));
            }
        }
        body.addView(logs);
    }

    private static final int EXPORT_BACKUP = 42;
    private static final int IMPORT_BACKUP = 43;

    private void paintBackups() {
        if (backupList == null)
            return;
        backupList.removeAllViews();
        java.util.List<File> backups = app.aomarchy.data.Backup.list(shell);
        if (backups.isEmpty()) {
            backupList.addView(Ui.text(shell, "还没有备份", 12, Ui.FAINT, false));
            return;
        }
        backupList.addView(Ui.gap(shell, 4));
        for (int i = 0; i < Math.min(5, backups.size()); i++) {
            File f = backups.get(i);
            LinearLayout r = Ui.row(shell);
            r.setPadding(0, Ui.dp(shell, 6), 0, Ui.dp(shell, 6));
            r.addView(Ui.text(shell, app.aomarchy.data.Backup.stamp(f), 13, Ui.TEXT, false));
            r.addView(new View(shell), new LinearLayout.LayoutParams(0, 1, 1f));
            r.addView(Ui.text(shell, smallSize(f.length()), 12, Ui.DIM, false));
            r.setOnClickListener(v -> showBackup(f));
            backupList.addView(r);
        }
    }

    private void showBackup(File backup) {
        String[] actions = {"恢复到这个备份", "导出到…", "删除"};
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
                .setNegativeButton("关闭", null)
                .show();
    }

    private void startBackup() {
        if (app.aomarchy.data.Backup.running() || installing) {
            toast("已有任务在进行");
            return;
        }
        backupProgressBox.setVisibility(View.VISIBLE);
        backupList.setVisibility(View.GONE);
        backupBar.setProgress(0);
        backupBar.setRunning(true);
        backupText.setText("准备备份…");
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
    }

    private void confirmRestore(File backup) {
        new android.app.AlertDialog.Builder(shell)
                .setTitle("恢复系统？")
                .setMessage("当前的系统会被这份备份整体替换（换装失败会自动换回）。桌面若在运行，恢复后请重启会话。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复", (d, w) -> {
                    backupProgressBox.setVisibility(View.VISIBLE);
                    backupList.setVisibility(View.GONE);
                    backupBar.setProgress(0);
                    backupBar.setRunning(true);
                    backupText.setText("解包备份…");
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
        toast("正在导出 " + src.getName() + "…");
        new Thread(() -> {
            try (java.io.InputStream in = new java.io.FileInputStream(src);
                    java.io.OutputStream out = shell.getContentResolver().openOutputStream(target)) {
                if (out == null)
                    throw new java.io.IOException("无法写入目标文件");
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0)
                    out.write(buf, 0, n);
                shell.runOnUiThread(() -> toast("已导出"));
            } catch (Exception e) {
                shell.runOnUiThread(() -> toast("导出失败：" + e.getMessage()));
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
        toast("正在读取备份…");
        new Thread(() -> {
            try (java.io.InputStream in = shell.getContentResolver().openInputStream(source);
                    java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
                if (in == null)
                    throw new java.io.IOException("无法读取文件");
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0)
                    out.write(buf, 0, n);
                shell.runOnUiThread(() -> confirmRestore(dest));
            } catch (Exception e) {
                shell.runOnUiThread(() -> toast("读取失败：" + e.getMessage()));
            }
        }, "backup-import").start();
    }
    private void confirmDelete(File backup) {
        new android.app.AlertDialog.Builder(shell)
                .setTitle("删除备份？")
                .setMessage(app.aomarchy.data.Backup.stamp(backup) + " 会被删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    if (backup.delete()) {
                        paintBackups();
                        toast("已删除");
                    } else {
                        toast("删不掉这个备份");
                    }
                })
                .show();
    }
    private TextView sourceTile(String title, String sub) {
        TextView t = Ui.text(shell, title + "\n" + sub, 12, Ui.DIM, false);
        t.setPadding(Ui.dp(shell, 12), Ui.dp(shell, 10), Ui.dp(shell, 12), Ui.dp(shell, 10));
        t.setOnClickListener(v -> {
            if (installing)
                return;
            sourceLocal = title.startsWith("本地");
            paintSources();
        });
        return t;
    }

    private View row(String name, long bytes, Runnable onClick) {
        LinearLayout r = Ui.row(shell);
        r.setPadding(0, Ui.dp(shell, 6), 0, Ui.dp(shell, 6));
        r.addView(Ui.text(shell, name, 13, Ui.TEXT, false));
        r.addView(new View(shell), new LinearLayout.LayoutParams(0, 1, 1f));
        r.addView(Ui.text(shell, smallSize(bytes), 12, Ui.DIM, false));
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
        File staged = Installer.staged(shell);
        boolean haveLocal = localUri != null || staged != null;
        if (local) {
            if (localUri != null) {
                archiveInfo.setText("已选择：" + localName + (localBytes > 0 ? " · " + Installer.humanSize(localBytes) : ""));
            } else if (staged != null) {
                archiveInfo.setText("已发现设备上的归档：" + staged.getName() + " · " + Installer.humanSize(staged.length()));
            } else {
                archiveInfo.setText("还没有选择本地归档，点「选择本地文件」。");
            }
        } else {
            archiveInfo.setText("从 GitHub Release 下载（需要联网，约 1.9 GB）。");
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
                toast("没有选择文件");
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
            toast("没有选择文件");
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
        b.setTitle(replace ? "重装并删除现有系统？" : "开始安装系统？");
        b.setMessage(replace
                ? "现有的 Linux 系统（约 5 GB）会被删除后重新安装。数据会丢失，确定继续？"
                : (sourceLocal ? "从本地归档安装，可能需要几分钟。" : "在线下载约 1.9 GB，然后解压，可能需要几分钟。"));
        b.setNegativeButton("取消", null);
        b.setPositiveButton(replace ? "删除并重装" : "开始", (d, w) -> startInstall(replace));
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
                toast("先选择一个本地归档，或改用在线下载");
                return;
            }
        }
        if (uri != null && "file".equals(uri.getScheme()) && !new File(uri.getPath()).canRead()) {
            toast("读不到这个文件");
            return;
        }
        installing = true;
        progressBox.setVisibility(View.VISIBLE);
        startBox.setVisibility(View.GONE);
        progress.setProgress(0);
        progress.setRunning(true);
        progressText.setText("准备中…");
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
                    toast("系统安装完成");
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
                            .setTitle("安装失败")
                            .setMessage(message)
                            .setPositiveButton("知道了", null)
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
            sb.append("读不到这个日志：").append(e);
        }
        new android.app.AlertDialog.Builder(shell)
                .setTitle(f.getName())
                .setMessage(sb.length() == 0 ? "(空)" : sb.toString())
                .setPositiveButton("关闭", null)
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
        stateValue.setText(ready ? "已安装" : "未安装");
        stateValue.setTextColor(ready ? Ui.MINT : Ui.FAINT);
        if (!ready) {
            knownSize = -1;
            size.setText("—");
            size.setTextColor(Ui.FAINT);
        } else if (knownSize > 0) {
            size.setText(Installer.humanSize(knownSize));
            size.setTextColor(Ui.TEXT);
        } else {
            size.setText("统计中…");
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
        row.addView(Ui.text(shell, "应用", 15, Ui.TEXT, true));
        row.addView(new View(shell), new LinearLayout.LayoutParams(0, 1, 1f));
        status = Ui.text(shell, "读取中…", 12, Ui.DIM, false);
        row.addView(status);
        TextView refresh = Ui.text(shell, "刷新", 13, Ui.ACCENT, false);
        refresh.setPadding(Ui.dp(shell, 12), 0, 0, 0);
        refresh.setOnClickListener(v -> load());
        row.addView(refresh);
        head.addView(row);
        body.addView(head);

        flatBox = Ui.card(shell, "Flatpak 沙箱");
        body.addView(flatBox);

        linuxBox = Ui.card(shell, "Omarchy 应用");
        body.addView(linuxBox);

        androidBox = Ui.card(shell, "Android 应用（浮动窗口）");
        body.addView(androidBox);
    }

    @Override
    void onShown() {
        if (!loaded)
            load();
    }

    private void load() {
        loaded = true;
        status.setText("读取中…");
        new Thread(() -> {
            java.util.List<Apps.LinuxApp> flat;
            java.util.List<Apps.LinuxApp> linux;
            java.util.List<Apps.AndroidApp> android;
            try {
                flat = Apps.flatpak(shell);
                linux = Apps.nativeApps(shell);
                android = Apps.android(shell);
            } catch (RuntimeException e) {
                shell.runOnUiThread(() -> status.setText("读取失败：" + e.getMessage()));
                return;
            }
            shell.runOnUiThread(() -> {
                fillFlatpak(flat);
                fillLinux(linux);
                fillAndroid(android);
                status.setText("Flatpak " + flat.size() + " 个 · 桌面应用 " + linux.size() + " 个 · Android 应用 " + android.size() + " 个");
            });
        }, "apps-list").start();
    }

    private void fillFlatpak(java.util.List<Apps.LinuxApp> apps) {
        flatBox.removeAllViews();
        flatBox.addView(Ui.text(shell, "已安装的 Flatpak（点名称在桌面打开）", 12, Ui.DIM, false));
        if (apps.isEmpty()) {
            flatBox.addView(Ui.text(shell, "还没有 Flatpak 应用。到桌面里用「软件」商店安装。", 13, Ui.FAINT, false));
            return;
        }
        for (int i = 0; i < Math.min(apps.size(), 24); i++)
            flatBox.addView(appRow(apps.get(i)));
    }

    private void fillLinux(java.util.List<Apps.LinuxApp> apps) {
        linuxBox.removeAllViews();
        linuxBox.addView(Ui.text(shell, "桌面自带（Omarchy、终端、文件等）", 12, Ui.DIM, false));
        for (int i = 0; i < Math.min(apps.size(), 24); i++)
            linuxBox.addView(appRow(apps.get(i)));
        if (apps.isEmpty())
            linuxBox.addView(Ui.text(shell, "系统未安装或没有桌面入口。", 13, Ui.FAINT, false));
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
        TextView open = Ui.text(shell, "打开 ›", 13, Ui.ACCENT, false);
        open.setPadding(Ui.dp(shell, 10), 0, 0, 0);
        open.setOnClickListener(v -> {
            if (!SessionService.rootfsReady(shell)) {
                toast("系统还没装好");
            } else if (!SessionService.started) {
                toast("先进桌面启动会话");
            } else {
                Apps.launchLinux(shell, app);
                toast("已在桌面打开 " + app.name);
            }
        });
        r.addView(open);
        return r;
    }

    private void fillAndroid(java.util.List<Apps.AndroidApp> apps) {
        androidBox.removeAllViews();
        androidBox.addView(Ui.text(shell, "点按以浮动窗口打开，和桌面并排", 12, Ui.DIM, false));
        if (apps.isEmpty()) {
            androidBox.addView(Ui.text(shell, "没有可启动的 Android 应用。", 13, Ui.FAINT, false));
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
        cell.addView(icon, new LinearLayout.LayoutParams(Ui.dp(shell, 36), Ui.dp(shell, 36)));
        TextView label = Ui.text(shell, app.label, 11, Ui.DIM, false);
        label.setGravity(android.view.Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setPadding(0, Ui.dp(shell, 4), 0, 0);
        cell.addView(label, new LinearLayout.LayoutParams(Ui.dp(shell, 70), ViewGroup.LayoutParams.WRAP_CONTENT));
        cell.setOnClickListener(v -> {
            Apps.openFloating(shell, app.packageName);
            toast("浮动打开 " + app.label);
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
    private TextView modeValue;

    SettingsPage(ShellActivity shell) {
        super(shell);
    }

    private android.content.SharedPreferences prefs() {
        return shell.getSharedPreferences(Prefs.NAME, android.content.Context.MODE_PRIVATE);
    }

    @Override
    void build(LinearLayout body) {
        LinearLayout identity = Ui.card(shell, "身份 · Linux 用户与 git");
        identity.addView(Ui.text(shell, "写入 Linux 的 git 身份与 XCompose，下次进桌面生效。", 12, Ui.DIM, false));
        identity.addView(Ui.gap(shell, 8));
        nameValue = value("—", Ui.TEXT);
        identity.addView(row("姓名", nameValue, () -> editText("姓名", "user_name", nameValue)));
        identity.addView(Ui.divider(shell));
        emailValue = value("—", Ui.TEXT);
        identity.addView(row("邮箱", emailValue, () -> editText("邮箱", "user_email", emailValue)));
        body.addView(identity);

        LinearLayout keyboard = Ui.card(shell, "键盘");
        keyboard.addView(Ui.text(shell, "第一个是物理键盘，屏幕键盘在其间循环。", 12, Ui.DIM, false));
        keyboard.addView(Ui.gap(shell, 8));
        kbValue = value("—", Ui.TEXT);
        keyboard.addView(row("系统布局", kbValue, this::pickLayout));
        body.addView(keyboard);

        LinearLayout startup = Ui.card(shell, "启动");
        modeValue = value("—", Ui.TEXT);
        startup.addView(row("启动方式", modeValue, this::pickMode));
        startup.addView(Ui.divider(shell));
        LinearLayout row = Ui.row(shell);
        row.setPadding(0, Ui.dp(shell, 8), 0, Ui.dp(shell, 8));
        row.addView(Ui.text(shell, "打开 App 后直接进入桌面", 15, Ui.TEXT, false));
        row.addView(new View(shell), new LinearLayout.LayoutParams(0, 1, 1f));
        final boolean[] on = {prefs().getBoolean("open_desktop_on_start", false)};
        TextView toggle = Ui.text(shell, on[0] ? "开启" : "关闭", 14, on[0] ? Ui.ACCENT : Ui.DIM, true);
        row.addView(toggle);
        row.setOnClickListener(v -> {
            on[0] = !on[0];
            prefs().edit().putBoolean("open_desktop_on_start", on[0]).apply();
            toggle.setText(on[0] ? "开启" : "关闭");
            toggle.setTextColor(on[0] ? Ui.ACCENT : Ui.DIM);
            toast(on[0] ? "下次打开 App 直接进桌面" : "下次打开 App 先进控制台");
        });
        row.setClickable(true);
        startup.addView(row);
        body.addView(startup);

        LinearLayout detailed = Ui.card(shell, "详细设置");
        detailed.addView(Ui.text(shell, "这些页已按控制台风格重做，改动立即生效。", 12, Ui.DIM, false));
        detailed.addView(Ui.gap(shell, 6));
        detailed.addView(row("键盘与按键", value("›", Ui.FAINT),
                () -> openPage("键盘与按键", KeysSettingsView.build(shell))));
        detailed.addView(Ui.divider(shell));
        detailed.addView(row("触摸板与鼠标", value("›", Ui.FAINT),
                () -> openPage("触摸板与鼠标", InputSettingsView.build(shell))));
        detailed.addView(Ui.divider(shell));
        detailed.addView(row("显示", value("›", Ui.FAINT),
                () -> openPage("显示", DisplaySettingsView.build(shell))));
        detailed.addView(Ui.divider(shell));
        detailed.addView(row("音频", value("›", Ui.FAINT),
                () -> openPage("音频", AudioSettingsView.build(shell))));
        body.addView(detailed);

        LinearLayout about = Ui.card(shell, "关于");
        about.addView(Ui.kv(shell, "版本", value(versionName(), Ui.TEXT)));
        about.addView(Ui.kv(shell, "包名", value(shell.getPackageName(), Ui.DIM)));
        about.addView(Ui.kv(shell, "Android", value(android.os.Build.VERSION.RELEASE + " (API "
                + android.os.Build.VERSION.SDK_INT + ")", Ui.DIM)));
        body.addView(about);
    }

    private LinearLayout row(String label, TextView valueView, Runnable onClick) {
        LinearLayout r = Ui.row(shell);
        r.setPadding(0, Ui.dp(shell, 8), 0, Ui.dp(shell, 8));
        r.addView(Ui.text(shell, label, 15, Ui.TEXT, false));
        r.addView(new View(shell), new LinearLayout.LayoutParams(0, 1, 1f));
        r.addView(valueView);
        r.setOnClickListener(v -> onClick.run());
        return r;
    }

    private void editText(String title, String key, TextView target) {
        android.widget.EditText input = new android.widget.EditText(shell);
        input.setText(prefs().getString(key, ""));
        input.setSingleLine(true);
        new android.app.AlertDialog.Builder(shell)
                .setTitle(title)
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    prefs().edit().putString(key, input.getText().toString().trim()).apply();
                    refresh();
                    toast("已保存，下次进入桌面生效");
                })
                .show();
    }

    private void pickMode() {
        String[] names = {"完整桌面（Hyprland）", "终端（省资源：不启动桌面）"};
        int checked = "terminal".equals(prefs().getString("session_mode", "desktop")) ? 1 : 0;
        new android.app.AlertDialog.Builder(shell)
                .setTitle("启动方式")
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    prefs().edit().putString("session_mode", which == 1 ? "terminal" : "desktop").apply();
                    d.dismiss();
                    refresh();
                    toast(which == 1 ? "下次进入终端：不起桌面，实测省约 700 MB" : "下次进入完整桌面");
                    shell.updateEntryLabel();
                })
                .setNegativeButton("取消", null)
                .show();
    }
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
                .setTitle("系统键盘布局")
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    prefs().edit().putString("kb_layouts", LAYOUTS[which]).apply();
                    d.dismiss();
                    refresh();
                    toast("已选 " + LAYOUT_NAMES[which] + "，下次进入桌面生效");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** Opens a native detail page (the rebuilt 详细设置 sub-pages) as a full-screen sheet over the console. */
    private void openPage(String title, View content) {
        android.app.Dialog dialog = new android.app.Dialog(shell,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen);

        LinearLayout wrap = Ui.column(shell);
        wrap.setBackgroundColor(Ui.BG);

        LinearLayout bar = Ui.row(shell);
        bar.setPadding(Ui.dp(shell, 12), Ui.dp(shell, 10), Ui.dp(shell, 12), Ui.dp(shell, 10));
        TextView back = Ui.text(shell, "‹ 返回", 14, Ui.ACCENT, true);
        back.setPadding(Ui.dp(shell, 8), Ui.dp(shell, 4), Ui.dp(shell, 8), Ui.dp(shell, 4));
        back.setOnClickListener(v -> dialog.dismiss());
        bar.addView(back);
        bar.addView(new View(shell), new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(Ui.text(shell, title, 16, Ui.TEXT, true));
        wrap.addView(bar);

        ScrollView scroll = new ScrollView(shell);
        scroll.setFillViewport(true);
        content.setPadding(Ui.dp(shell, 16), Ui.dp(shell, 6), Ui.dp(shell, 16), Ui.dp(shell, 24));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dialog.setContentView(wrap);
        dialog.setOnDismissListener(d -> refresh());
        dialog.show();
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.BG));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    @Override
    void refresh() {
        nameValue.setText(orDash(prefs().getString("user_name", "")));
        emailValue.setText(orDash(prefs().getString("user_email", "")));
        String kb = prefs().getString("kb_layouts", "");
        if (kb.isEmpty())
            kb = installedLayout(); // what the installer picked from the device language
        kbValue.setText(orDash(kb));
        String mode = prefs().getString("session_mode", "desktop");
        modeValue.setText("terminal".equals(mode) ? "终端（省资源）" : "完整桌面");
    }

    private static String orDash(String s) {
        return s == null || s.trim().isEmpty() ? "未设置" : s;
    }

    /** KB_LAYOUTS from the rootfs's setup.conf: the layout the install chose. */
    private String installedLayout() {
        try {
            File f = new File(SessionService.rootfs(shell), "root/.config/andromarchy/setup.conf");
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