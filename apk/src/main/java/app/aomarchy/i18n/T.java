package app.aomarchy.i18n;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The console's string table: Chinese and English live in code, plus the {@code ui_lang} preference that picks
 * between them. The handful of Android resource strings (session notification, desktop toasts, orientation labels)
 * follow the same choice through {@link #wrap(Context)} and {@link #applyLocale(Context)}, so the console and the
 * resources never speak different languages.
 *
 * <p>Language source: prefs key {@code ui_lang}, one of {@code auto} (follow the system Locale) / {@code zh} /
 * {@code en}; default {@code auto}. A missing key falls back to Chinese, then to the key name itself (so a typo is
 * visible instead of crashing).
 *
 * <p>Every new key must be added to both the zh and the en column in one edit: check-java only compiles, it does not
 * detect a key that exists in one language only — review is the only check.
 *
 * <p>Key sections (add new keys inside their section, do not renumber):
 * <pre>
 *   tab.* / status.* / shell.*        shell chrome: rail tabs, status chip, install hint
 *   btn.*                             button / action labels shared by several screens
 *   toast.* / dialog.* / error.*      toasts, confirmation dialogs, error text
 *   card.* / label.*                  card titles and row labels shared by several screens
 *   state.* / time.* / mode.* / lang.*  transient values (installed, on/off, durations, startup mode)
 *   overview.*                        page 1 (dashboard)
 *   system.* / source.* / backup.*    page 2 (install, backups, logs)
 *   install.*                         Installer progress/errors (shown by the System page)
 *   apps.*                            page 3 (app lists)
 *   controls.*                        ControlsView: page 4 and the half-screen sheet over the desktop
 *   terminal.* / term.*               TerminalActivity and TerminalView (terminal mode)
 *   session.*                         SessionService notification
 *   settings.* / page.*               page 5 (identity, keyboard, startup, language, about)
 *   keys.* / action.* / resp.*        KeysSettingsView
 *   input.* / transform.*             InputSettingsView
 *   display.* / orient.* / cutout.*   DisplaySettingsView
 *   audio.*                           AudioSettingsView
 * </pre>
 *
 * <p>Usage: {@code T.t("tab.overview")}, {@code T.t("toast.exporting", name)}. Call {@link #init(Context)} from an
 * activity before building its views so a saved choice is honoured in a fresh process, and pass the base context
 * through {@link #wrap(Context)} from {@code Activity.attachBaseContext} so resource strings follow the same Locale.
 */
public final class T {
    /** Must match {@code app.aomarchy.Prefs.NAME}; the class there is package-private, so it is repeated here. */
    private static final String PREFS = "andromarchy_settings";
    private static final String KEY_LANG = "ui_lang";

    public static final String LANG_AUTO = "auto";
    public static final String LANG_ZH = "zh";
    public static final String LANG_EN = "en";

    private static final Map<String, String> ZH = new HashMap<>();
    private static final Map<String, String> EN = new HashMap<>();

    /** key, Chinese, English — both languages always present, so a row never renders as a raw key. */
    private static final String[][] TABLE = {
        // ===== shell chrome ================================================================
        {"tab.overview", "概览", "Overview"},
        {"tab.system", "系统", "System"},
        {"tab.apps", "应用", "Apps"},
        {"tab.controls", "控制", "Controls"},
        {"tab.settings", "设置", "Settings"},
        {"status.not_installed", "未安装", "Not installed"},
        {"status.running", "运行中", "Running"},
        {"status.ready", "就绪", "Ready"},
        {"status.terminal_mode", "终端模式", "Terminal mode"},
        {"shell.need_system", "系统还没安装，先在「系统」页安装 Linux",
            "No system installed yet — install Linux on the System page first"},

        // ===== shared buttons ==============================================================
        {"btn.enter_desktop", "进入桌面", "Open desktop"},
        {"btn.enter_terminal", "进入终端", "Open terminal"},
        {"btn.install_system", "安装系统", "Install system"},
        {"btn.install_reinstall", "安装 / 重装系统", "Install / reinstall"},
        {"btn.pick_local", "选择本地文件", "Choose local file"},
        {"btn.begin_install", "开始安装", "Start install"},
        {"btn.cancel", "取消", "Cancel"},
        {"btn.close", "关闭", "Close"},
        {"btn.save", "保存", "Save"},
        {"btn.delete", "删除", "Delete"},
        {"btn.restore", "恢复", "Restore"},
        {"btn.start", "开始", "Start"},
        {"btn.delete_reinstall", "删除并重装", "Delete and reinstall"},
        {"btn.ok", "知道了", "OK"},
        {"btn.confirm", "确定", "Confirm"},
        {"btn.back", "‹ 返回", "‹ Back"},
        {"btn.back_console", "‹ 控制台", "‹ Console"},
        {"btn.copy", "复制", "Copy"},
        {"btn.paste", "粘贴", "Paste"},
        {"btn.keyboard", "键盘", "Keyboard"},
        {"btn.restart", "重启", "Restart"},
        {"btn.refresh", "刷新", "Refresh"},
        {"btn.open", "打开 ›", "Open ›"},
        {"btn.create_backup", "创建备份", "Create backup"},
        {"btn.import_backup", "导入备份…", "Import backup…"},
        {"btn.reset_default", "恢复默认", "Restore default"},
        {"btn.unbind", "解除绑定", "Unbind"},
        {"btn.enable_notifications", "去开启通知", "Enable notifications"},
        {"btn.restart_session", "重启会话", "Restart session"},
        {"btn.close_desktop", "关闭桌面", "Close desktop"},
        {"btn.send_to_desktop", "发送到桌面", "Send to desktop"},
        {"btn.fetch_from_desktop", "从桌面取回", "Fetch from desktop"},

        // ===== shared state / time / modes / language ======================================
        {"state.installed", "已安装", "Installed"},
        {"state.measuring", "统计中…", "Measuring…"},
        {"state.not_set", "未设置", "Not set"},
        {"state.on", "开启", "On"},
        {"state.off", "关闭", "Off"},
        {"state.default", "默认", "Default"},
        {"state.custom", "自定义", "Custom"},
        {"state.custom_invalid", "自定义 · 无效", "Custom · invalid"},
        {"state.unbound", "未绑定", "Not bound"},
        {"state.key_n", "按键 %s", "Key %s"},
        {"state.backing_up", "备份中…", "Backing up…"},
        {"btn.backup_now", "立即备份", "Back up now"},
        {"btn.backup_manage", "管理备份 ›", "Manage backups ›"},
        {"time.hours_minutes", "%s 小时 %s 分", "%s h %s m"},
        {"time.minutes_seconds", "%s 分 %s 秒", "%s m %s s"},
        {"time.just_now", "刚刚", "just now"},
        {"time.minutes_ago", "%s 分钟前", "%s min ago"},
        {"time.hours_ago", "%s 小时前", "%s h ago"},
        {"time.days_ago", "%s 天前", "%s days ago"},
        {"mode.desktop", "完整桌面", "Full desktop"},
        {"mode.terminal_short", "终端（省资源）", "Terminal (light)"},
        {"mode.desktop_full", "完整桌面（Hyprland）", "Full desktop (Hyprland)"},
        {"mode.terminal", "终端（省资源：不启动桌面）", "Terminal (light: no desktop)"},
        {"lang.auto", "跟随系统", "Follow system"},
        {"lang.zh", "中文", "Chinese"},
        {"lang.en", "English", "English"},

        // ===== page 1: overview ============================================================
        {"overview.session", "会话", "Session"},
        {"overview.tagline", "Arch Linux · Hyprland · 整个系统跑在 Android 上，不需要 root 和 proot。",
            "Arch Linux · Hyprland · the whole system runs on Android — no root, no proot."},
        {"overview.tagline_terminal", "终端模式：只跑一个 shell，不启动 Hyprland，实测比完整桌面省约 700 MB。",
            "Terminal mode: just a shell, no Hyprland. Measured to save about 700 MB over the full desktop."},
        {"overview.running_for", "已运行 %s", "Up for %s"},
        {"overview.cpu_device_scope", "设备整体占用", "Whole-device load"},
        {"overview.cpu_process_scope", "%s 个进程 · 会话整体", "%s processes · session total"},
        {"overview.mem_used_avail", "%s 已用 · 可用 %s", "%s used · %s available"},
        {"overview.disk_used_free", "%s 已用 · 剩余 %s", "%s used · %s free"},
        {"overview.charging", "充电中", "Charging"},
        {"overview.on_battery", "使用电池", "On battery"},
        {"overview.net_caption", "Android 网络栈提供", "From the Android network stack"},
        {"network.none", "无连接", "No connection"},
        {"network.cellular", "移动网络", "Mobile data"},
        {"network.ethernet", "有线", "Ethernet"},
        {"network.connected", "已连接", "Connected"},
        {"network.unknown", "—", "—"},
        {"overview.net_gpu_note", "两者都由 Android 侧透传，Linux 里看到的与系统一致。",
            "Both are passed through from Android, so Linux sees the same as the system."},
        {"overview.tips", "桌面里点左上角「A」回控制台；会话通知里也有「控制」「控制台」。第一次使用：先去「系统」装系统，再到「设置」填身份与键盘。",
            "Tap 'A' in the desktop's top-left corner to return to the console; the session notification also has 'Controls' and 'Console'. First time: install the system on the System page, then fill in your identity and keyboard in Settings."},

        // ===== page 2: system ==============================================================
        {"system.install_desc", "首次安装或重装 Linux 系统。安装包来自 GitHub Release 的在线下载，或设备上的 lotfix-rootfs.tar.gz。",
            "First install or reinstall of the Linux system. The archive comes from a GitHub Release download, or from lotfix-rootfs.tar.gz on the device."},
        {"system.selected", "已选择：%s", "Selected: %s"},
        {"system.selected_sized", "已选择：%s · %s", "Selected: %s · %s"},
        {"system.found_archive", "已发现设备上的归档：%s · %s", "Found an archive on the device: %s · %s"},
        {"system.no_archive", "还没有选择本地归档，点「选择本地文件」。",
            "No local archive chosen yet — tap 'Choose local file'."},
        {"system.online_note", "从 GitHub Release 下载（需要联网，约 1.9 GB）。",
            "Download from GitHub Release (needs network, about 1.9 GB)."},
        {"system.backup_desc", "把整个系统打包成一个 tar.gz（约 1.6 GB），恢复时整树换入。备份存在本机 Android/data/app.aomarchy/files/backups，也可以导出到别处。",
            "Pack the whole system into one tar.gz (about 1.6 GB) and swap the tree back on restore. Backups live in Android/data/app.aomarchy/files/backups and can be exported elsewhere."},
        {"system.preparing_backup", "准备备份…", "Preparing backup…"},
        {"system.unpacking_backup", "解包备份…", "Unpacking backup…"},
        {"system.preparing", "准备中…", "Preparing…"},
        {"system.logs_desc", "会话日志在 files/run/ 下，点开看最后几行。",
            "Session logs live under files/run/ — tap one to see its last lines."},
        {"system.no_logs", "还没有日志（系统未启动过）", "No logs yet (the system has never started)"},
        {"system.no_backups", "还没有备份", "No backups yet"},
        {"system.log_read_error", "读不到这个日志：%s", "Cannot read this log: %s"},
        {"system.empty", "(空)", "(empty)"},
        {"source.online", "在线下载", "Online download"},
        {"source.online_sub", "GitHub Release · 约 1.9 GB", "GitHub Release · about 1.9 GB"},
        {"source.local", "本地归档", "Local archive"},
        {"source.local_sub", "设备上的 lotfix-rootfs.tar.gz", "lotfix-rootfs.tar.gz on this device"},
        {"backup.restore", "恢复到这个备份", "Restore this backup"},
        {"backup.export", "导出到…", "Export to…"},
        // installer progress/errors: data/Installer.java reports them through its Callback, the System page shows them.
        {"install.size_unknown", "大小未知", "Size unknown"},
        {"install.cancelled", "已取消安装", "Install cancelled"},
        {"install.not_enough_space", "空间不足：需要约 %s GB，当前可用 %s",
            "Not enough space: about %s GB needed, %s available"},
        {"install.stale_partial", "上次安装的临时目录清理不掉（权限），请重启应用后重试",
            "Could not remove the leftover partial tree from a previous install (permissions) — restart the app and try again"},
        {"install.cannot_open_file", "打不开这个文件", "Could not open this file"},
        {"install.download_failed", "下载失败：HTTP %s", "Download failed: HTTP %s"},
        {"install.mode_local", "安装中", "Installing"},
        {"install.mode_download", "下载并安装中", "Downloading and installing"},
        {"install.progress_known", "%s（%s / %s MB）\n%s", "%s (%s / %s MB)\n%s"},
        {"install.progress_unknown", "%s（%s MB）\n%s", "%s (%s MB)\n%s"},
        {"install.not_aomarchy_image", "这个归档不是 AOmarchy 的系统镜像",
            "This archive is not an AOmarchy system image"},
        {"install.setup_path_escape", "归档里的 setup 路径越出了 rootfs",
            "The archive's setup path escapes the rootfs"},
        {"install.finishing", "收尾中…", "Finishing up…"},
        {"install.cannot_delete_old", "删不掉旧的系统（rootfs）", "Could not delete the old system (rootfs)"},
        {"install.cannot_move", "无法把新系统移动到目标位置", "Could not move the new system into place"},
        // backup progress/errors: data/Backup.java, same Callback contract as the installer.
        {"backup.no_system", "系统还没安装", "The system is not installed"},
        {"backup.empty_system", "系统目录是空的", "The system directory is empty"},
        {"backup.not_enough_space", "空间不足：备份约需要 %s，当前可用 %s",
            "Not enough space: the backup needs about %s, %s available"},
        {"backup.progress", "备份 %s% · %s / %s", "Backing up %s% · %s / %s"},
        {"backup.cancelled", "已取消", "Cancelled"},
        {"backup.cannot_save", "无法保存备份文件", "Could not save the backup file"},
        {"backup.done", "备份完成", "Backup complete"},
        {"backup.created", "已创建 %s（%s）", "Created %s (%s)"},
        {"backup.failed", "备份失败", "Backup failed"},
        {"backup.missing_archive", "备份文件不见了", "The backup file is gone"},
        {"backup.restore_not_enough_space", "空间不足：恢复约需要 %s，当前可用 %s",
            "Not enough space: the restore needs about %s, %s available"},
        {"backup.not_aomarchy_image", "这个备份不是 AOmarchy 的系统镜像",
            "This backup is not an AOmarchy system image"},
        {"backup.swapping", "替换系统…", "Swapping the system…"},
        {"backup.cannot_delete_current", "删不掉当前的系统（rootfs）",
            "Could not delete the current system (rootfs)"},
        {"backup.cannot_move", "无法把恢复的系统移动到目标位置",
            "Could not move the restored system into place"},
        {"backup.restored", "已恢复", "Restored"},
        {"backup.restored_message", "系统已恢复到 %s 的状态。进入桌面即用这份系统。",
            "The system was restored to its %s state. Open the desktop to use it."},
        {"backup.restore_failed", "恢复失败", "Restore failed"},

        // ===== page 3: apps ================================================================
        {"apps.loading", "读取中…", "Loading…"},
        {"apps.load_failed", "读取失败：%s", "Load failed: %s"},
        {"apps.counts_summary", "Flatpak %s 个 · 桌面应用 %s 个 · Android 应用 %s 个",
            "%s Flatpaks · %s desktop apps · %s Android apps"},
        {"apps.flatpak_hint", "已安装的 Flatpak（点名称在桌面打开）",
            "Installed Flatpaks (tap a name to open it on the desktop)"},
        {"apps.no_flatpak", "还没有 Flatpak 应用。到桌面里用「软件」商店安装。",
            "No Flatpak apps yet. Install them from the software store on the desktop."},
        {"apps.linux_hint", "桌面自带（Omarchy、终端、文件等）",
            "Shipped with the desktop (Omarchy, terminal, files, …)"},
        {"apps.no_linux", "系统未安装或没有桌面入口。", "The system is not installed, or it has no desktop entries."},
        {"apps.android_hint", "点按以浮动窗口打开，和桌面并排",
            "Tap to open it in a floating window next to the desktop"},
        {"apps.no_android", "没有可启动的 Android 应用。", "No Android apps available to launch."},

        // ===== page 4 / desktop sheet: controls ============================================
        {"controls.warn_notifications", "桌面运行时靠会话通知里的「控制」回到这里；通知权限没开就看不到它。",
            "While the desktop runs, the 'Controls' action in the session notification brings you back here; without the notification permission you will not see it."},
        {"controls.session_running", "会话运行中 · 点「进入桌面」回到 Linux",
            "Session running · tap 'Open desktop' to return to Linux"},
        {"controls.session_ready", "系统已安装，尚未启动", "System installed, not started yet"},
        {"controls.no_system", "还没有安装系统（到「系统」页安装）",
            "No system installed yet (install it on the System page)"},
        {"controls.screen_caption", "Android 显示", "Android display"},
        {"controls.input_value", "触摸板 · 虚拟键盘", "Touchpad · virtual keyboard"},
        {"controls.input_caption", "按键映射在设置里", "Key mapping is in Settings"},
        {"controls.display_link", "显示、方向与刘海设置 ›", "Display, orientation and cutout settings ›"},
        {"controls.clipboard_sync", "Android ↔ 桌面双向同步", "Two-way sync between Android and the desktop"},

        // ===== terminal mode ===============================================================
        {"terminal.bottom", "↓ 底部", "↓ Bottom"},
        {"terminal.no_system", "系统还没安装。回到控制台，在「系统」里装好 AOmarchy 再进终端。",
            "No system installed. Go back to the console, install AOmarchy on the System page, then open the terminal."},
        {"terminal.key_desc", "%s 键", "%s key"},
        {"terminal.clip_label", "终端", "Terminal"},
        // TerminalView writes these into its own grid (local notices, not shell output).
        {"term.font_size", "[字号 %ssp]", "[font %ssp]"},
        {"term.start_failed", "[无法启动终端]", "[Could not start the terminal]"},
        {"term.pty_failed", "[无法连接 pty: %s]", "[Could not connect to the pty: %s]"},
        {"term.session_ended", "[会话已结束]", "[Session ended]"},

        // ===== session notification ========================================================
        {"session.content", "Linux 桌面运行中", "Linux desktop running"},
        {"session.action_controls", "控制", "Controls"},
        {"session.action_console", "控制台", "Console"},

        // ===== page 5: settings ============================================================
        {"settings.identity_desc", "写入 Linux 的 git 身份与 XCompose，下次进桌面生效。",
            "Writes the git identity and XCompose into Linux; takes effect next time you open the desktop."},
        {"settings.keyboard_desc", "第一个是物理键盘，屏幕键盘在其间循环。",
            "The first is the physical keyboard; the on-screen keyboard cycles through them."},
        {"settings.open_desktop_toggle", "打开 App 后直接进入桌面",
            "Open the desktop directly when the app starts"},
        {"settings.detailed_desc", "这些页已按控制台风格重做，改动立即生效。",
            "These pages are rebuilt in the console style; changes apply immediately."},
        {"settings.language_desc", "控制台与终端共用一种界面语言，切换后立即重建界面。",
            "The console and the terminal share one interface language; the UI is rebuilt right after switching."},

        // ===== keys settings ==============================================================
        {"keys.vk_desc", "给虚拟键盘绑一个物理按键：按一下呼出或收起。虚拟键盘也可以浮在会话桌面之上，不挤占桌面。",
            "Bind a physical key to the on-screen keyboard: press it to show or hide. The keyboard can also float above the desktop instead of resizing it."},
        {"keys.bind_key_caption", "按一下要绑定的物理按键；按返回键取消",
            "Press the physical key to bind; press Back to cancel"},
        {"keys.floating_caption", "关闭时会话桌面缩小并上移，给软键盘让出空间",
            "When off, the session desktop shrinks and moves up to make room for the software keyboard"},
        {"keys.accessibility_desc", "用无障碍服务拦截平板键盘的功能键，把 Fn、F1–F12 等转成桌面能识别的键码，并让返回键按 ESC 发给桌面。首次启用需要在系统设置里授予无障碍权限。",
            "Uses the accessibility service to intercept the tablet keyboard's function keys, turning Fn, F1–F12 and friends into key codes the desktop understands, and sending Back as ESC to the desktop. The first enable needs the accessibility permission in system settings."},
        {"keys.intercept_caption", "关闭后功能键按系统默认处理，不再转换",
            "When off, function keys are handled by the system default with no translation"},
        {"keys.open_accessibility", "打开系统无障碍设置", "Open system accessibility settings"},
        {"keys.open_accessibility_caption", "在列表里找到 AOmarchy 并开启，只需授权一次",
            "Find AOmarchy in the list and enable it — you only authorize once"},
        {"keys.extra_desc", "会话底部的特殊键栏：ESC / TAB / CTRL / ALT / 方向键 / PgUp 等，触屏上也能当键盘用。",
            "The special-key strip at the bottom of the session: ESC / TAB / CTRL / ALT / arrows / PgUp and more, usable as a keyboard on touch."},
        {"keys.show_bar", "显示按键栏", "Show the key bar"},
        {"keys.show_bar_caption", "底部常驻一排特殊键；桌面显示区域会相应缩小",
            "A row of special keys stays at the bottom; the desktop area shrinks accordingly"},
        {"keys.follow_ime", "跟随软键盘", "Follow the software keyboard"},
        {"keys.follow_ime_caption", "开启后软键盘弹出时按键栏自动出现；关闭则只能手动切换",
            "When on, the key bar appears with the software keyboard; when off, you toggle it manually"},
        {"keys.custom_layout", "自定义布局", "Custom layout"},
        {"keys.custom_layout_caption", "JSON 的两行按键表；无效内容不会被保存",
            "Two rows of keys as JSON; invalid content is not saved"},
        {"keys.reset_layout", "恢复默认布局", "Restore default layout"},
        {"keys.reset_layout_caption", "重置为内置的两行模板", "Reset to the built-in two-row template"},
        {"keys.layout_empty_hint", "留空则使用内置默认布局", "Leave empty to use the built-in default layout"},
        {"keys.layout_valid", "✓ 布局 JSON 有效，回到会话后生效",
            "✓ Valid layout JSON; takes effect when you return to the session"},
        {"keys.notification_desc", "App 运行时在通知栏常驻一条状态通知；它的点按和两个按钮分别做什么，在下面「按键动作」里配置。",
            "While the app runs, a status notification stays in the shade; configure what its tap and two buttons do under 'Key actions' below."},
        {"keys.show_notification", "显示常驻通知", "Show the persistent notification"},
        {"keys.show_notification_caption", "关闭后不再显示；改动在下次回到 App 时生效",
            "When off it is hidden; the change applies the next time you return to the app"},
        {"keys.actions_desc", "这些按键或入口被触发时执行什么。改动立即生效。",
            "What each key or entry point does when triggered. Changes apply immediately."},
        {"action.back_button", "返回键", "Back button"},
        {"action.volume_up", "音量加键", "Volume up"},
        {"action.volume_down", "音量减键", "Volume down"},
        {"action.media_keys", "媒体键", "Media keys"},
        {"action.notification_tap", "通知点按", "Notification tap"},
        {"action.notification_first", "通知第一个按钮", "Notification first button"},
        {"action.notification_second", "通知第二个按钮", "Notification second button"},
        {"resp.none", "无动作", "No action"},
        {"resp.toggle_keyboard", "切换软键盘", "Toggle software keyboard"},
        {"resp.toggle_extra_bar", "切换额外按键栏", "Toggle extra key bar"},
        {"resp.open_preferences", "打开设置", "Open settings"},
        {"resp.release_capture", "释放指针与键盘捕获", "Release pointer & keyboard capture"},
        {"resp.restart_ui", "重启界面", "Restart UI"},
        {"resp.exit", "退出 App", "Exit app"},
        {"resp.toggle_touchpad", "切换触摸板模式", "Toggle touchpad mode"},
        {"resp.toggle_orientation", "切换屏幕方向", "Toggle screen orientation"},
        {"resp.volume_up", "发送音量加", "Send volume up"},
        {"resp.volume_down", "发送音量减", "Send volume down"},
        {"resp.media_action", "发送媒体键", "Send media key"},

        // ===== input settings ==============================================================
        {"input.touchpad_mode", "触摸板模式", "Touchpad mode"},
        {"input.touchpad_mode_caption", "开启：手指滑动移动光标（相对）；关闭：触摸直接落到屏幕（绝对）",
            "On: finger movement moves the cursor (relative); off: touches land on the screen directly (absolute)"},
        {"input.mouse_accel_caption", "屏幕触摸板的光标加速度，越大同样的滑动走得越远",
            "Cursor acceleration of the on-screen touchpad — the higher it is, the further the same swipe travels"},
        {"input.transform_caption", "捕获到的指针位移按这个方向旋转；触摸板装反或方向不对时选「自动」",
            "Captured pointer movement is rotated by this; if the touchpad is mounted upside down or the direction is off, choose 'Auto'"},
        {"input.capture_speed_caption", "相对默认速度的百分比，只在指针捕获生效时使用",
            "Percentage of the default speed; only used while pointer capture is active"},
        {"input.capture_toggle", "捕获外部指针", "Capture external pointer"},
        {"input.capture_toggle_caption", "点按桌面后接管硬件鼠标与触摸板；按 Esc 交还 Android",
            "After tapping the desktop, take over the hardware mouse and touchpad; press Esc to hand them back to Android"},
        {"input.reverse_scroll", "反向滚动", "Reverse scrolling"},
        {"input.reverse_scroll_caption", "开启后内容跟随手指（自然滚动），只作用于捕获到的硬件触摸板",
            "When on, content follows your fingers (natural scrolling), only for the captured hardware touchpad"},
        {"input.scroll_speed_caption", "每像素手指位移对应的滚动量，越大滚动越快",
            "Scroll amount per pixel of finger movement — higher scrolls faster"},
        {"input.scroll_threshold_caption", "手指移动多远才算滚动；捏合被误判成滚动时调低",
            "How far the finger moves before it counts as scrolling; lower it if pinches are misread as scrolling"},
        {"input.move_threshold_caption", "两指手势变成滚动前，主导手指要移动的距离；滚动迟迟不触发时调低",
            "How far the dominant finger must move before a two-finger gesture becomes a scroll; lower it if scrolling does not trigger"},
        {"input.gesture_scale_caption", "捏合等手势转发为触摸时，映射到光标周围方块的边长，越大手势越夸张",
            "When gestures such as pinch are forwarded as touches, the side length of the box around the cursor; higher makes gestures more pronounced"},
        {"transform.none", "不旋转", "No rotation"},
        {"transform.cw", "顺时针 90°", "90° clockwise"},
        {"transform.ccw", "逆时针 90°", "90° counterclockwise"},
        {"transform.flip", "翻转 180°", "180° flip"},
        {"transform.auto", "自动（跟随屏幕旋转）", "Auto (follow screen rotation)"},

        // ===== display settings ============================================================
        {"display.window_orientation_caption", "对桌面窗口生效，返回桌面时应用",
            "Applies to the desktop window when you return to it"},
        {"display.display_area_caption", "决定窗口能否画到刘海和圆角下面，返回桌面时应用",
            "Whether the window may draw under the cutout and rounded corners; applies when you return to the desktop"},
        {"display.pip_note",
            "桌面暂不支持画中画：Android 小窗里桌面画面会黑，所以默认关闭。回控制台用桌面左上角的「A」手柄或会话通知。",
            "Picture-in-picture is not supported for the desktop yet: its small window stays black, so it is off. Use the A handle in the desktop's corner or the session notification to get back to the console."},
        {"display.resolution_caption", "面板当前像素", "Current panel pixels"},
        {"display.density_caption", "逻辑密度，界面缩放按它算", "Logical density; UI scaling is based on it"},
        {"display.refresh_rate_caption", "面板当前刷新率", "Current panel refresh rate"},
        {"display.adjust", "显示参数调节", "Adjust display parameters"},
        {"display.adjust_caption", "分辨率、密度、刷新率的调节入口在「控制」页，本页不重复实现",
            "Resolution, density and refresh rate are adjusted on the Controls page; this page does not duplicate it"},
        {"display.adjust_value", "控制页", "Controls page"},
        {"display.notes_text", "分辨率、密度、刷新率属于实时显示参数，入口在「控制」页；方向、刘海与 PIP 是窗口级设置，桌面会话启动或返回时读取。",
            "Resolution, density and refresh rate are live display parameters on the Controls page; orientation, cutout and PIP are window-level settings read when the desktop session starts or returns."},
        {"orient.auto", "自动旋转", "Auto rotate"},
        {"orient.portrait", "竖屏", "Portrait"},
        {"orient.landscape", "横屏", "Landscape"},
        {"orient.reverse_portrait", "反向竖屏", "Reverse portrait"},
        {"orient.reverse_landscape", "反向横屏", "Reverse landscape"},
        {"cutout.all", "画到所有刘海与圆角下", "Draw under all cutouts and corners"},
        {"cutout.rounded", "只画到圆角下", "Draw under rounded corners only"},
        {"cutout.cutout", "只画到刘海下", "Draw under the cutout only"},
        {"cutout.none", "全部避开", "Avoid all of them"},

        // ===== audio settings ==============================================================
        {"audio.keepalive_desc", "桌面会话在后台时不让音频通道休眠，外放和麦克风不会每次重新起振。",
            "Keeps the audio path awake while the desktop session is in the background, so the speaker and microphone do not restart every time."},
        {"audio.keepalive_toggle", "保持音频通道", "Keep the audio path open"},
        {"audio.keepalive_caption", "关闭可省一点电；通话或录音时建议开启",
            "Turning it off saves a little power; keep it on for calls or recording"},
        {"audio.latency_desc", "越低越跟手，太高会明显对不上画面；改完下次进桌面生效。",
            "Lower is more responsive; too high visibly desyncs from the picture; takes effect next time you open the desktop."},
        {"audio.latency_unit", "毫秒；0 = 系统默认", "milliseconds; 0 = system default"},
        {"audio.latency_title", "%s延迟", "%s latency"},
        {"audio.system_default", "系统默认", "System default"},

        // ===== shared card titles / row labels =============================================
        {"card.current_system", "当前系统", "Current system"},
        {"card.install_system", "安装系统", "Install system"},
        {"card.backup", "备份与回滚", "Backup & rollback"},
        {"card.logs", "日志", "Logs"},
        {"card.memory", "内存", "Memory"},
        {"card.disk_system", "磁盘 · 系统", "Disk · System"},
        {"card.battery", "电量", "Battery"},
        {"card.net_gpu", "网络与图形", "Network & graphics"},
        {"card.tips", "提示", "Tips"},
        {"card.insights", "智能建议", "Suggestions"},
        {"insights.none", "没有需要处理的建议，一切正常。", "Nothing needs attention right now."},
        {"insight.no_system", "尚未安装 Linux 系统", "Linux is not installed yet"},
        {"insight.install", "去安装", "Install"},
        {"insight.disk_low", "系统盘只剩 %s", "Only %s free on the system disk"},
        {"insight.clean", "清理备份", "Clean backups"},
        {"insight.mem_high", "内存占用 %s%%，终端模式更省", "Memory at %s%%; terminal mode is lighter"},
        {"insight.to_settings", "去设置", "Settings"},
        {"insight.session_idle", "桌面会话未运行", "The desktop session is not running"},
        {"insight.enter", "进入桌面", "Open desktop"},
        {"insight.backup_big", "备份占用 %s", "Backups take %s"},
        {"insight.manage", "去管理", "Manage"},
        {"card.backup_status", "备份", "Backup"},
        {"system.backup_stats", "共 %s 份 · 占用 %s", "%s backups · %s"},
        {"system.backup_keep", "保留最近", "Keep the newest"},
        {"system.backup_keep_none", "不自动清理", "Keep everything"},
        {"system.backup_keep_value", "%s 份", "%s backups"},
        {"system.backup_keep_note", "超出份数的旧备份会在每次备份完成后自动删除；也可以手动清理。",
            "Older archives beyond that count are deleted automatically after each backup; you can also prune manually."},
        {"system.backup_prune", "清理旧备份", "Prune old backups"},
        {"system.backup_pruned", "已清理 %s 个文件", "Removed %s files"},
        {"system.backup_pruned_none", "没有需要清理的旧备份", "Nothing to prune"},
        {"dialog.backup_keep_title", "保留最近几份备份？", "How many backups to keep?"},
        {"overview.backup_never", "从未备份 — 建议在「系统」页创建一份（约 2 GB，几分钟）",
            "Never backed up — create one on the System page (about 2 GB, a few minutes)"},
        {"overview.backup_last", "上次备份：%s", "Last backup: %s"},
        {"overview.backup_stale", "上次备份：%s · 已超过 30 天，建议重新备份",
            "Last backup: %s · older than 30 days, back up again"},
        {"card.flatpak", "Flatpak 沙箱", "Flatpak sandboxes"},
        {"card.omarchy_apps", "Omarchy 应用", "Omarchy apps"},
        {"card.android_apps", "Android 应用（浮动窗口）", "Android apps (floating windows)"},
        {"card.notifications_off", "通知未开启", "Notifications are off"},
        {"card.session", "会话", "Session"},
        {"card.sound", "声音", "Sound"},
        {"card.display_input", "显示与输入", "Display & input"},
        {"card.clipboard", "剪贴板", "Clipboard"},
        {"card.identity", "身份 · Linux 用户与 git", "Identity · Linux user & git"},
        {"card.keyboard", "键盘", "Keyboard"},
        {"card.startup", "启动", "Startup"},
        {"card.detailed", "详细设置", "Detailed settings"},
        {"card.about", "关于", "About"},
        {"card.language", "语言", "Language"},
        {"card.virtual_keyboard", "虚拟键盘", "On-screen keyboard"},
        {"card.accessibility", "无障碍按键拦截", "Accessibility key interception"},
        {"card.extra_keys", "额外按键栏", "Extra key bar"},
        {"card.notifications", "通知", "Notifications"},
        {"card.key_actions", "按键动作", "Key actions"},
        {"card.touchpad", "触摸板", "Touchpad"},
        {"card.capture", "指针捕获", "Pointer capture"},
        {"card.scroll", "滚动", "Scrolling"},
        {"card.gestures", "手势与阈值", "Gestures & thresholds"},
        {"card.orientation", "屏幕方向", "Screen orientation"},
        {"card.cutout", "刘海与圆角", "Cutout & rounded corners"},
        {"card.pip", "画中画", "Picture-in-picture"},
        {"card.display_params", "显示参数", "Display parameters"},
        {"card.notes", "说明", "Notes"},
        {"card.audio_keepalive", "音频保活", "Audio keep-alive"},
        {"card.audio_latency", "音频延迟", "Audio latency"},
        {"label.status", "状态", "Status"},
        {"label.usage", "占用", "Size"},
        {"label.path", "路径", "Path"},
        {"label.network", "网络", "Network"},
        {"label.name", "姓名", "Name"},
        {"label.email", "邮箱", "Email"},
        {"label.kb_layout", "系统布局", "System layout"},
        {"label.startup_mode", "启动方式", "Startup mode"},
        {"label.version", "版本", "Version"},
        {"label.package", "包名", "Package"},
        {"label.ui_lang", "界面语言", "Interface language"},
        {"label.bind_key", "绑定按键", "Bound key"},
        {"label.floating_keyboard", "浮动虚拟键盘", "Floating keyboard"},
        {"label.intercept_keys", "启用按键拦截", "Enable key interception"},
        {"label.media_volume", "媒体音量", "Media volume"},
        {"label.call_volume", "通话音量", "Call volume"},
        {"label.screen", "屏幕", "Screen"},
        {"label.input", "输入", "Input"},
        {"label.mouse_accel", "鼠标灵敏度", "Pointer sensitivity"},
        {"label.transform", "指针方向变换", "Pointer orientation transform"},
        {"label.capture_speed", "捕获指针速度", "Captured pointer speed"},
        {"label.scroll_speed", "双指滚动速度", "Two-finger scroll speed"},
        {"label.scroll_threshold", "滚动识别阈值", "Scroll recognition threshold"},
        {"label.move_threshold", "手势判定阈值", "Gesture detection threshold"},
        {"label.gesture_scale", "手势幅度", "Gesture size"},
        {"label.window_orientation", "窗口方向", "Window orientation"},
        {"label.display_area", "显示区域", "Display area"},
        {"label.resolution", "分辨率", "Resolution"},
        {"label.density", "密度", "Density"},
        {"label.refresh_rate", "刷新率", "Refresh rate"},
        {"label.speaker", "扬声器", "Speaker"},
        {"label.microphone", "麦克风", "Microphone"},
        {"page.keys", "键盘与按键", "Keyboard & keys"},
        {"page.input", "触摸板与鼠标", "Touchpad & mouse"},
        {"page.display", "显示", "Display"},
        {"page.audio", "音频", "Audio"},

        // ===== toasts / dialogs / errors ===================================================
        {"toast.busy", "已有任务在进行", "Another task is already running"},
        {"toast.no_file", "没有选择文件", "No file selected"},
        {"toast.exporting", "正在导出 %s…", "Exporting %s…"},
        {"toast.exported", "已导出", "Exported"},
        {"toast.export_failed", "导出失败：%s", "Export failed: %s"},
        {"toast.reading_backup", "正在读取备份…", "Reading backup…"},
        {"toast.read_failed", "读取失败：%s", "Read failed: %s"},
        {"toast.deleted", "已删除", "Deleted"},
        {"toast.delete_failed", "删不掉这个备份", "Could not delete this backup"},
        {"toast.pick_archive_first", "先选择一个本地归档，或改用在线下载",
            "Choose a local archive first, or switch to the online download"},
        {"toast.cannot_read_file", "读不到这个文件", "Cannot read this file"},
        {"toast.installed", "系统安装完成", "System installed"},
        {"toast.system_missing", "系统还没装好", "The system is not installed yet"},
        {"toast.start_session_first", "先进桌面启动会话", "Open the desktop first to start a session"},
        {"toast.opened_on_desktop", "已在桌面打开 %s", "Opened %s on the desktop"},
        {"toast.floating_open", "浮动打开 %s", "Opening %s in a floating window"},
        {"toast.next_launch_desktop", "下次打开 App 直接进桌面",
            "Next launch goes straight to the desktop"},
        {"toast.next_launch_console", "下次打开 App 先进控制台", "Next launch opens the console first"},
        {"toast.saved_next_desktop", "已保存，下次进入桌面生效",
            "Saved; takes effect next time you open the desktop"},
        {"toast.next_terminal_mode", "下次进入终端：不起桌面，实测省约 700 MB",
            "Next launch opens the terminal — no desktop, measured to save about 700 MB"},
        {"toast.next_desktop_mode", "下次进入完整桌面", "Next launch opens the full desktop"},
        {"toast.layout_saved", "已选 %s，下次进入桌面生效",
            "Selected %s; takes effect next time you open the desktop"},
        {"toast.language_changed", "界面语言已切换", "Interface language changed"},
        {"toast.vk_floating", "虚拟键盘将浮在桌面之上", "The keyboard will float above the desktop"},
        {"toast.vk_resize", "已改为缩小桌面：软键盘不再遮挡内容",
            "Now shrinks the desktop, so the keyboard no longer covers content"},
        {"toast.bound", "已绑定 %s", "Bound %s"},
        {"toast.unbound", "已解除绑定", "Unbound"},
        {"toast.intercept_on", "已尝试启用；若没有生效，请到系统无障碍设置手动打开",
            "Tried to enable it; if it does not take effect, turn it on manually in the system accessibility settings"},
        {"toast.intercept_off", "已关闭按键拦截", "Key interception turned off"},
        {"toast.find_ao", "在列表里找到「AOmarchy」并开启", "Find 'AOmarchy' in the list and enable it"},
        {"toast.no_accessibility_settings", "系统没有无障碍设置页面",
            "This system has no accessibility settings page"},
        {"toast.takes_effect_in_session", "回到会话后生效", "Takes effect when you return to the session"},
        {"toast.saved_session", "已保存，回到会话后生效",
            "Saved; takes effect when you return to the session"},
        {"toast.layout_reset", "已恢复默认布局", "Default layout restored"},
        {"toast.takes_effect_in_app", "回到 App 后生效", "Takes effect when you return to the app"},
        {"toast.clipboard_empty", "剪贴板是空的", "The clipboard is empty"},
        {"toast.sent_to_desktop", "已发送 %s 个字符到桌面", "Sent %s characters to the desktop"},
        {"toast.nothing_to_fetch", "桌面上没有新的剪贴板内容", "Nothing new on the desktop clipboard"},
        {"toast.fetched", "已取回 %s 个字符", "Fetched %s characters"},
        {"toast.read_clipboard_failed", "读取桌面剪贴板失败：%s",
            "Failed to read the desktop clipboard: %s"},
        {"toast.nothing_to_copy", "没有可复制的内容", "Nothing to copy"},
        {"toast.copied_screen", "已复制屏幕 %s 行", "Copied %s lines from the screen"},
        {"toast.copied_selection", "已复制选中 %s 行", "Copied %s selected lines"},
        {"toast.pasted", "已粘贴 %s 个字符", "Pasted %s characters"},
        {"dialog.restore_title", "恢复系统？", "Restore the system?"},
        {"dialog.restore_message", "当前的系统会被这份备份整体替换（换装失败会自动换回）。桌面若在运行，恢复后请重启会话。",
            "The current system will be replaced by this backup (a failed swap is rolled back automatically). If the desktop is running, restart the session afterwards."},
        {"dialog.delete_backup_title", "删除备份？", "Delete this backup?"},
        {"dialog.delete_backup_message", "%s 会被删除。", "%s will be deleted."},
        {"dialog.reinstall_title", "重装并删除现有系统？", "Reinstall and delete the current system?"},
        {"dialog.install_title", "开始安装系统？", "Start installing the system?"},
        {"dialog.reinstall_message", "现有的 Linux 系统（约 5 GB）会被删除后重新安装。数据会丢失，确定继续？",
            "The existing Linux system (about 5 GB) will be deleted and reinstalled. Data will be lost — continue?"},
        {"dialog.install_local_message", "从本地归档安装，可能需要几分钟。",
            "Installing from the local archive; this can take a few minutes."},
        {"dialog.install_online_message", "在线下载约 1.9 GB，然后解压，可能需要几分钟。",
            "Downloads about 1.9 GB, then unpacks; this can take a few minutes."},
        {"dialog.install_failed", "安装失败", "Install failed"},
        {"dialog.restart_session_title", "重启会话？", "Restart the session?"},
        {"dialog.shutdown_title", "关闭桌面并回到 Android？", "Close the desktop and return to Android?"},
        {"dialog.bind_key_title", "绑定虚拟键盘按键", "Bind an on-screen keyboard key"},
        {"dialog.bind_key_message", "按下要绑定的物理按键。绑定后按它就能呼出或收起虚拟键盘；按返回键取消。",
            "Press the physical key to bind. After that, it shows or hides the on-screen keyboard; press Back to cancel."},
        {"dialog.custom_layout_title", "自定义按键布局", "Custom key layout"},
        {"dialog.reset_layout_title", "恢复默认布局", "Restore default layout"},
        {"dialog.reset_layout_message", "把额外按键栏重置为内置的两行模板？当前的自定义布局会被覆盖。",
            "Reset the extra key bar to the built-in two-row template? The current custom layout will be overwritten."},
        {"dialog.kb_layout_title", "系统键盘布局", "System keyboard layout"},
        {"error.cannot_write_target", "无法写入目标文件", "cannot write the target file"},
        // ===== settings export / import =====
        {"page.settings_io", "配置备份", "Settings backup"},
        {"card.export_settings", "导出配置", "Export settings"},
        {"card.import_settings", "导入配置", "Import settings"},
        {"btn.export_settings", "导出配置…", "Export settings…"},
        {"btn.import_settings", "导入配置…", "Import settings…"},
        {"settings.export_desc", "把控制台的全部设置写成 JSON 文件。不包含 Linux 系统、应用或备份文件。",
            "Saves every console setting to a JSON file. The Linux system, apps and backup files are not included."},
        {"settings.export_privacy", "导出的是明文 JSON，包含姓名、邮箱等设置，请保存到你信任的位置。",
            "The file is plain JSON and includes your name, email and other settings — save it somewhere you trust."},
        {"settings.import_desc", "从导出的 JSON 文件恢复设置；同名键会被覆盖，部分设置需重新进入桌面或重启 App 生效。",
            "Restores settings from an exported JSON file; same-name keys are overwritten and some settings need re-entering the desktop or an app restart."},
        {"settings.exported", "已导出 %s", "Exported %s"},
        {"settings.importing", "正在导入…", "Importing…"},
        {"settings.imported", "已导入 %s 项设置；部分设置需重新进入桌面或重启 App 生效",
            "Imported %s settings; re-enter the desktop or restart the app for some of them"},
        {"settings.import_failed", "导入失败：%s", "Import failed: %s"},
        {"settings_io.bad_file", "不是 AOmarchy 的配置文件", "Not an AOmarchy settings file"},
        {"settings_io.bad_json", "JSON 解析失败：%s", "Could not parse the JSON: %s"},
        {"settings_io.no_values", "配置文件里没有 values 对象", "The settings file has no \"values\" object"},
        {"settings_io.write_failed", "无法写入目标文件", "Could not write the target file"},
        {"settings_io.save_failed", "保存设置失败", "Could not save the settings"},
        {"settings_io.read_failed", "无法读取文件", "Could not read the file"},
        {"settings_io.too_large", "文件过大（超过 1 MB）", "File is too large (over 1 MB)"},
        {"settings_io.export_failed", "无法序列化设置：%s", "Could not serialize the settings: %s"},
    };

    static {
        for (String[] row : TABLE) {
            ZH.put(row[0], row[1]);
            EN.put(row[0], row[2]);
        }
    }

    /** Resolved language ("zh" / "en"); null until {@link #init(Context)} or {@link #setLang} runs. */
    private static String current;

    private T() {
    }

    /** Resolves the saved language once, before an activity builds its views. */
    public static void init(Context c) {
        current = resolve(c);
    }

    /** The language in use right now: "zh" or "en". Also refreshes the cached choice from prefs. */
    public static String lang(Context c) {
        current = resolve(c);
        return current;
    }

    /** The raw {@code ui_lang} preference: "auto", "zh" or "en". */
    public static String savedLang(Context c) {
        String v = prefs(c).getString(KEY_LANG, LANG_AUTO);
        return LANG_ZH.equals(v) || LANG_EN.equals(v) ? v : LANG_AUTO;
    }

    /** Stores {@code ui_lang}; accepts "auto" / "zh" / "en", anything else becomes "auto". */
    public static void setLang(Context c, String value) {
        String v = LANG_ZH.equals(value) || LANG_EN.equals(value) ? value : LANG_AUTO;
        prefs(c).edit().putString(KEY_LANG, v).apply();
        current = resolve(c);
    }

    /**
     * Wraps an activity's base Context so the Android resource strings follow the same {@code ui_lang} as
     * {@link #t(String)}. Call it from every Activity's {@code attachBaseContext}:
     * {@code super.attachBaseContext(T.wrap(base))}.
     *
     * <p>{@code auto} returns {@code base} untouched, so the system Locale applies exactly as before; {@code zh} /
     * {@code en} override this activity's configuration only, which makes its resource lookups read res/values-zh or
     * res/values. The system and application Locale are never changed.
     */
    public static Context wrap(Context base) {
        Locale locale = forcedLocale(base);
        if (locale == null)
            return base;
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(locale);
        return base.createConfigurationContext(cfg);
    }

    /**
     * Re-applies the saved {@code ui_lang} to a live activity's own resources: {@link #wrap(Context)} only helps a
     * context created after the switch. {@code ShellActivity} calls this from {@code relanguage()} so the resource
     * strings of the views it rebuilds match the code strings, without restarting the activity; {@code auto} puts the
     * system Locale back.
     */
    public static void applyLocale(Context context) {
        Resources res = context.getResources();
        Configuration cfg = new Configuration(res.getConfiguration());
        Locale locale = forcedLocale(context);
        cfg.setLocale(locale != null ? locale : Locale.getDefault());
        res.updateConfiguration(cfg, res.getDisplayMetrics());
    }

    /** The Locale {@code ui_lang} asks for, or null for {@code auto} (follow the system). */
    private static Locale forcedLocale(Context c) {
        String v = savedLang(c);
        if (LANG_ZH.equals(v))
            return Locale.SIMPLIFIED_CHINESE;
        if (LANG_EN.equals(v))
            return Locale.ENGLISH;
        return null;
    }

    /** One string in the current language. Missing English falls back to Chinese, then to the key itself. */
    public static String t(String key) {
        String lang = current != null ? current : systemLang();
        String s = LANG_EN.equals(lang) ? EN.get(key) : ZH.get(key);
        if (s == null)
            s = ZH.get(key);
        return s != null ? s : key;
    }

    /** {@code t(key)} with {@code %s} placeholders filled from {@code args}, in order. */
    public static String t(String key, Object... args) {
        String text = t(key);
        for (Object arg : args) {
            int i = text.indexOf("%s");
            if (i < 0)
                break;
            text = text.substring(0, i) + arg + text.substring(i + 2);
        }
        return text;
    }

    /**
     * The overview's network value. {@code data/Metrics.java} reports stable codes ({@code wifi}, {@code none}, …)
     * and has no language layer, so they are mapped here; the old Chinese display strings are still accepted so a
     * value captured by an older build does not leak through, and anything else (a vendor string) is passed through.
     */
    public static String networkLabel(String value) {
        if (value == null || value.isEmpty())
            return value;
        switch (value) {
            case "none":
            case "无连接":
                return t("network.none");
            case "cellular":
            case "移动网络":
                return t("network.cellular");
            case "ethernet":
            case "有线":
                return t("network.ethernet");
            case "connected":
            case "已连接":
                return t("network.connected");
            case "unknown":
                return t("network.unknown");
            default:
                return value; // "wifi" and a Wi-Fi name need no translation
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String resolve(Context c) {
        String v = prefs(c).getString(KEY_LANG, LANG_AUTO);
        if (LANG_ZH.equals(v))
            return LANG_ZH;
        if (LANG_EN.equals(v))
            return LANG_EN;
        return systemLang();
    }

    private static String systemLang() {
        Locale locale = Locale.getDefault();
        String code = locale != null ? locale.getLanguage() : "";
        return code != null && code.toLowerCase(Locale.ROOT).startsWith("zh") ? LANG_ZH : LANG_EN;
    }
}
