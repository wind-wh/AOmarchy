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
        int pad = Ui.dp(shell, 24);
        body.setPadding(pad, pad, pad, Ui.dp(shell, 24));
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
        Toast.makeText(shell, text, Toast.LENGTH_SHORT).show();
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
        hero.addView(Ui.gap(shell, 16));
        LinearLayout actions = Ui.row(shell);
        actions.addView(Ui.button(shell, "进入桌面", true, v -> shell.openDesktop()));
        actions.addView(Ui.space(shell, 10));
        actions.addView(Ui.button(shell, "安装 / 重装系统", false, v -> shell.showTab(1)));
        hero.addView(actions);
        body.addView(hero);

        // CPU with its little history line, then the plain numbers.
        LinearLayout cpuCard = card("CPU");
        cpuValue = big("—");
        cpuCard.addView(cpuValue);
        cpuSub = Ui.text(shell, "", 12, Ui.DIM, false);
        cpuCard.addView(cpuSub);
        sparkline = new Sparkline(shell);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(shell, 34));
        slp.topMargin = Ui.dp(shell, 8);
        cpuCard.addView(sparkline, slp);
        LinearLayout row1 = Ui.row(shell);
        row1.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        l1.rightMargin = Ui.dp(shell, 12);
        row1.addView(cpuCard, l1);
        LinearLayout memCard = card("内存");
        memValue = big("—");
        memCard.addView(memValue);
        memSub = Ui.text(shell, "", 12, Ui.DIM, false);
        memCard.addView(memSub);
        row1.addView(memCard, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(row1);
        body.addView(Ui.gap(shell, 12));

        LinearLayout row2 = Ui.row(shell);
        row2.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout diskCard = card("磁盘");
        diskValue = big("—");
        diskCard.addView(diskValue);
        diskSub = Ui.text(shell, "", 12, Ui.DIM, false);
        diskCard.addView(diskSub);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        l2.rightMargin = Ui.dp(shell, 12);
        row2.addView(diskCard, l2);
        LinearLayout batCard = card("电量");
        batteryValue = big("—");
        batCard.addView(batteryValue);
        batterySub = Ui.text(shell, "", 12, Ui.DIM, false);
        batCard.addView(batterySub);
        row2.addView(batCard, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(row2);
        body.addView(Ui.gap(shell, 12));

        LinearLayout row3 = Ui.row(shell);
        row3.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout netCard = card("网络");
        networkValue = big("—");
        netCard.addView(networkValue);
        LinearLayout.LayoutParams l3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        l3.rightMargin = Ui.dp(shell, 12);
        row3.addView(netCard, l3);
        LinearLayout gpuCard = card("GPU");
        gpuValue = big("—");
        gpuCard.addView(gpuValue);
        row3.addView(gpuCard, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(row3);
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
        v.setTextSize(22);
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
        state.setText(ready ? (SessionService.started ? "会话运行中" : "会话已就绪") : "尚未安装系统");
        state.setTextColor(ready ? Ui.TEXT : Ui.DIM);
        heroChip.setText(ready ? (SessionService.started ? "● 运行中" : "● 就绪") : "○ 未安装");
        heroChip.setTextColor(ready ? Ui.MINT : Ui.FAINT);
        uptime.setText(ready && SessionService.started
                ? "已运行 " + duration(System.currentTimeMillis() - SessionService.startedAt)
                : "Arch Linux · Hyprland · 整个系统跑在 Android 上，不需要 root 和 proot。");
        poll();
    }

    private void poll() {
        if (!isAdded())
            return;
        metrics.read(shell);
        cpuValue.setText(metrics.cpuPercent < 0 ? "—" : metrics.cpuPercent + "%");
        cpuSub.setText("device".equals(metrics.cpuScope) ? "设备整体" : metrics.processCount + " 个进程（会话）");
        sparkline.push(metrics.cpuPercent);
        memValue.setText(metrics.memPercent() < 0 ? "—" : metrics.memPercent() + "%");
        memSub.setText(Metrics.gb(metrics.memUsed) + " / " + Metrics.gb(metrics.memTotal));
        diskValue.setText(metrics.diskPercent() < 0 ? "—" : metrics.diskPercent() + "%");
        diskSub.setText(Metrics.gb(metrics.diskUsed) + " / " + Metrics.gb(metrics.diskTotal));
        batteryValue.setText(metrics.batteryPercent < 0 ? "—" : metrics.batteryPercent + "%");
        batterySub.setText(metrics.charging ? "充电中" : "使用电池");
        networkValue.setText(metrics.network);
        networkValue.setTextSize(18);
        gpuValue.setText(Metrics.gpu());
        gpuValue.setTextSize(18);
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
    private android.widget.ProgressBar progress;
    private LinearLayout progressBox;
    private LinearLayout startBox;
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
        progress = new android.widget.ProgressBar(shell, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(shell, 6));
        plp.topMargin = Ui.dp(shell, 8);
        progressBox.addView(progress, plp);
        progressBox.addView(Ui.gap(shell, 8));
        progressBox.addView(Ui.button(shell, "取消", false, v -> Installer.cancel()));
        install.addView(progressBox);
        body.addView(install);

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
        r.setPadding(0, Ui.dp(shell, 8), 0, Ui.dp(shell, 8));
        r.addView(Ui.text(shell, name, 13, Ui.TEXT, false));
        r.addView(new View(shell), new LinearLayout.LayoutParams(0, 1, 1f));
        r.addView(Ui.text(shell, Installer.humanSize(bytes), 12, Ui.DIM, false));
        r.setOnClickListener(v -> onClick.run());
        return r;
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
        LinearLayout head = Ui.card(shell, "应用");
        status = Ui.text(shell, "读取中…", 13, Ui.DIM, false);
        head.addView(status);
        head.addView(Ui.gap(shell, 10));
        LinearLayout buttons = Ui.row(shell);
        buttons.addView(Ui.button(shell, "刷新", false, v -> load()));
        buttons.addView(Ui.space(shell, 10));
        buttons.addView(Ui.button(shell, "进入桌面", true, v -> shell.openDesktop()));
        head.addView(buttons);
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
        flatBox.addView(Ui.text(shell, "已安装的 Flatpak 应用（点名称在桌面里打开）。", 13, Ui.DIM, false));
        flatBox.addView(Ui.gap(shell, 6));
        if (apps.isEmpty()) {
            flatBox.addView(Ui.text(shell, "还没有 Flatpak 应用。到桌面里用「软件」商店安装。", 13, Ui.FAINT, false));
            return;
        }
        for (int i = 0; i < Math.min(apps.size(), 24); i++)
            flatBox.addView(appRow(apps.get(i)));
    }

    private void fillLinux(java.util.List<Apps.LinuxApp> apps) {
        linuxBox.removeAllViews();
        linuxBox.addView(Ui.text(shell, "桌面自带的应用（Omarchy、终端、文件等）。", 13, Ui.DIM, false));
        linuxBox.addView(Ui.gap(shell, 6));
        for (int i = 0; i < Math.min(apps.size(), 24); i++)
            linuxBox.addView(appRow(apps.get(i)));
        if (apps.isEmpty())
            linuxBox.addView(Ui.text(shell, "系统未安装或没有桌面入口。", 13, Ui.FAINT, false));
    }

    private View appRow(Apps.LinuxApp app) {
        LinearLayout r = Ui.row(shell);
        r.setPadding(0, Ui.dp(shell, 10), 0, Ui.dp(shell, 10));
        LinearLayout texts = Ui.column(shell);
        texts.addView(Ui.text(shell, app.name, 15, Ui.TEXT, false));
        texts.addView(Ui.text(shell, app.id, 11, Ui.FAINT, false));
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
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
        androidBox.addView(Ui.text(shell, "点一个应用，它以浮动窗口打开，和桌面并排使用。", 13, Ui.DIM, false));
        androidBox.addView(Ui.gap(shell, 8));
        if (apps.isEmpty()) {
            androidBox.addView(Ui.text(shell, "没有可启动的 Android 应用。", 13, Ui.FAINT, false));
            return;
        }
        LinearLayout row = null;
        int perRow = 4;
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
        cell.setPadding(0, Ui.dp(shell, 6), 0, Ui.dp(shell, 6));
        android.widget.ImageView icon = new android.widget.ImageView(shell);
        icon.setImageDrawable(app.icon);
        cell.addView(icon, new LinearLayout.LayoutParams(Ui.dp(shell, 40), Ui.dp(shell, 40)));
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
/** 控制: high-frequency switches — display, input, sound, power (M4). */
class ControlsPage extends Page {
    private TextView resolution;

    ControlsPage(ShellActivity shell) {
        super(shell);
    }

    @Override
    void build(LinearLayout body) {
        LinearLayout display = Ui.card(shell, "显示");
        resolution = value("—", Ui.TEXT);
        display.addView(Ui.kv(shell, "分辨率", resolution));
        body.addView(display);

        LinearLayout input = Ui.card(shell, "输入");
        input.addView(Ui.text(shell, "虚拟键盘、触摸板模式、指针捕获、键盘布局（M4）。", 13, Ui.DIM, false));
        body.addView(input);

        LinearLayout sound = Ui.card(shell, "声音");
        sound.addView(Ui.text(shell, "输出 / 输入音量、音频延迟预设（M4）。", 13, Ui.DIM, false));
        body.addView(sound);

        LinearLayout power = Ui.card(shell, "电源");
        power.addView(Ui.text(shell, "重启会话 / 关闭桌面回到 Android（会话没装好时不可用）。", 13, Ui.DIM, false));
        power.addView(Ui.gap(shell, 12));
        LinearLayout prow = Ui.row(shell);
        prow.addView(Ui.button(shell, "重启会话", false, v -> power("reboot")));
        prow.addView(Ui.space(shell, 10));
        prow.addView(Ui.button(shell, "关闭桌面", false, v -> power("shutdown")));
        power.addView(prow);
        body.addView(power);
    }

    private void power(String action) {
        if (!SessionService.rootfsReady(shell)) {
            toast("系统还没装好");
            return;
        }
        Restarter.power(shell, action);
    }

    @Override
    void refresh() {
        android.util.DisplayMetrics m = shell.getResources().getDisplayMetrics();
        resolution.setText(m.widthPixels + " × " + m.heightPixels);
    }
}

/** 设置: low-frequency configuration; the existing SettingsActivity still owns the detailed pages. */
class SettingsPage extends Page {
    SettingsPage(ShellActivity shell) {
        super(shell);
    }

    @Override
    void build(LinearLayout body) {
        LinearLayout look = Ui.card(shell, "外观");
        look.addView(Ui.kv(shell, "主题", value("固定品牌深色", Ui.TEXT)));
        look.addView(Ui.kv(shell, "字体", value("Noto Sans CJK", Ui.DIM)));
        body.addView(look);

        LinearLayout session = Ui.card(shell, "会话与输入");
        session.addView(row("键盘与按键", () -> open(SettingsActivity.class)));
        session.addView(Ui.divider(shell));
        session.addView(row("触摸板与鼠标", () -> open(SettingsActivity.class)));
        session.addView(Ui.divider(shell));
        session.addView(row("连接、显示与音频", () -> open(SettingsActivity.class)));
        body.addView(session);

        LinearLayout about = Ui.card(shell, "关于");
        about.addView(Ui.kv(shell, "版本", value(versionName(), Ui.TEXT)));
        about.addView(Ui.kv(shell, "包名", value(shell.getPackageName(), Ui.DIM)));
        about.addView(Ui.kv(shell, "Android", value(Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")", Ui.DIM)));
        body.addView(about);
    }

    private android.view.View row(String title, Runnable onClick) {
        LinearLayout r = Ui.row(shell);
        r.setPadding(0, Ui.dp(shell, 12), 0, Ui.dp(shell, 12));
        r.addView(Ui.text(shell, title, 15, Ui.TEXT, false));
        r.addView(new View(shell), new LinearLayout.LayoutParams(0, 1, 1f));
        r.addView(Ui.text(shell, "›", 18, Ui.FAINT, false));
        r.setOnClickListener(v -> onClick.run());
        return r;
    }

    private void open(Class<?> activity) {
        shell.startActivity(new Intent(shell, activity));
    }

    private String versionName() {
        try {
            return shell.getPackageManager().getPackageInfo(shell.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
