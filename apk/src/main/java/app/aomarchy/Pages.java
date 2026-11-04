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
        actions.addView(Ui.button(shell, "安装 / 重装系统", false, v -> shell.installSystem()));
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

/** 系统: the Linux system itself — install, size, backups (M2), logs. */
class SystemPage extends Page {
    private TextView size;
    private TextView stateValue;

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
        sys.addView(Ui.gap(shell, 14));
        LinearLayout actions = Ui.row(shell);
        actions.addView(Ui.button(shell, "安装系统", true, v -> shell.installSystem()));
        actions.addView(Ui.space(shell, 10));
        actions.addView(Ui.button(shell, "重装", false, v -> shell.installSystem()));
        sys.addView(actions);
        body.addView(sys);

        LinearLayout backup = Ui.card(shell, "备份与回滚");
        backup.addView(Ui.text(shell, "把整个 Linux 系统打成归档（约 5 GB，需要外部存储空间），可回滚、可导出给别的设备。",
                13, Ui.DIM, false));
        backup.addView(Ui.gap(shell, 12));
        LinearLayout brow = Ui.row(shell);
        brow.addView(Ui.button(shell, "备份", false, v -> toast("M2 接入")));
        brow.addView(Ui.space(shell, 10));
        brow.addView(Ui.button(shell, "回滚", false, v -> toast("M2 接入")));
        brow.addView(Ui.space(shell, 10));
        brow.addView(Ui.button(shell, "导出", false, v -> toast("M2 接入")));
        backup.addView(brow);
        body.addView(backup);

        LinearLayout logs = Ui.card(shell, "日志与自检");
        logs.addView(Ui.text(shell, "andromarchy-selftest 的摘要、Hyprland / PipeWire / Xwayland 日志会在这里汇总（M2）。",
                13, Ui.DIM, false));
        body.addView(logs);
    }

    @Override
    void refresh() {
        boolean ready = SessionService.rootfsReady(shell);
        stateValue.setText(ready ? "已安装" : "未安装");
        stateValue.setTextColor(ready ? Ui.MINT : Ui.FAINT);
        if (!ready) {
            size.setText("—");
            size.setTextColor(Ui.FAINT);
            return;
        }
        try {
            StatFs fs = new StatFs(SessionService.rootfs(shell).getAbsolutePath());
            long used = fs.getBlockCountLong() * fs.getBlockSizeLong();
            size.setText(String.format(java.util.Locale.US, "%.1f GB", used / 1024.0 / 1024 / 1024));
            size.setTextColor(Ui.TEXT);
        } catch (RuntimeException e) {
            size.setText("读取失败");
            size.setTextColor(Ui.WARN);
        }
    }
}

/** 应用: flatpak sandboxes (M3), Android apps in floating windows, Omarchy entries. */
class AppsPage extends Page {
    AppsPage(ShellActivity shell) {
        super(shell);
    }

    @Override
    void build(LinearLayout body) {
        LinearLayout flat = Ui.card(shell, "Flatpak 应用");
        flat.addView(Ui.text(shell, "已安装的 Flatpak 应用、运行时和沙箱权限（M3）。", 13, Ui.DIM, false));
        body.addView(flat);

        LinearLayout android = Ui.card(shell, "Android 应用");
        android.addView(Ui.text(shell, "把 Android 应用以浮动窗口打开、钉到控制面板（M3）。", 13, Ui.DIM, false));
        body.addView(android);

        LinearLayout omarchy = Ui.card(shell, "Omarchy 应用");
        omarchy.addView(Ui.text(shell, "Linux 桌面里的 .desktop 入口（M3）。", 13, Ui.DIM, false));
        body.addView(omarchy);
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
