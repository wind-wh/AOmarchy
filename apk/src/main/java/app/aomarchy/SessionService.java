package app.aomarchy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.aomarchy.i18n.T;

/**
 * Andromarchy session: keeps the Linux side alive in a foreground service (HyperOS kills background apps).
 *
 * Starts, once per process: the Andromarchy display daemon, the PipeWire audio stack and Hyprland, the last two inside
 * the Arch rootfs through lotfix-run (the native launcher: patched glibc loader + fakechroot, no proot).
 * Layout, all under getFilesDir() (short: Unix socket paths are limited to 108 bytes):
 *   rootfs/            the Arch Linux ARM system
 *   run/               XDG_RUNTIME_DIR (Wayland, PipeWire, Hyprland sockets), excluded from fakechroot
 *   run/andromarchy/        the display daemon's socket
 *   run/*.log          output of each process
 */
public class SessionService extends Service {
    private static final String TAG = "Andromarchy";
    private static final String CHANNEL = "session";
    private static final int NOTIFICATION_ID = 1;

    private final List<Process> processes = new ArrayList<>();
    private PowerManager.WakeLock wakeLock;

    public static File rootfs(Context ctx) {
        return new File(ctx.getFilesDir(), "rootfs");
    }

    /** The session runs in this process (false: anything left in run/ is from a previous one). */
    static volatile boolean started;
    /** Wall clock of the session start, for the overview's uptime. */
    static volatile long startedAt;

    public static File runDir(Context ctx) {
        return new File(ctx.getFilesDir(), "run");
    }

    public static File daemonSocket(Context ctx) {
        return new File(runDir(ctx), "andromarchy/display_daemon.sock");
    }

    /** True once the Arch rootfs (built by build/build-rootfs.sh) is in place. */
    public static boolean rootfsReady(Context ctx) {
        return new File(rootfs(ctx), "opt/lotfix/arch-glibc/ld-linux-aarch64.so.1").exists();
    }

    public static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, SessionService.class));
    }

    /**
     * Android's CJK font, in the order the devices ship it. The Arch rootfs has no CJK font (noto-fonts-cjk is not
     * part of it), so Chinese text in the desktop would be drawn as empty boxes.
     */
    private static final String[] CJK_FONTS = {
            "NotoSansCJK-Regular.ttc", "NotoSansCJKsc-Regular.otf", "NotoSansSC-Regular.otf", "NotoSansCJK-Regular.otf"
    };

    private static final String CJK_DIR = "usr/share/fonts/noto-cjk";
    private static final String CJK_CONF = "etc/fonts/conf.d/99-andromarchy-cjk.conf";

    /**
     * Puts the CJK font Android ships into the rootfs, so Chinese (and Japanese/Korean) text is drawn. fontconfig
     * then hands the generic families to it for Chinese: the applications that ask fontconfig for their font get
     * the CJK one as the fallback for the characters the Latin fonts lack.
     */
    private void installCjkFonts() {
        File rootfs = rootfs(this);
        File dir = new File(rootfs, CJK_DIR);
        if (!hasCjkFont(dir)) {
            for (String name : CJK_FONTS) {
                File src = new File("/system/fonts", name);
                if (!src.isFile())
                    continue;
                File tmp = new File(dir, name + ".part");
                try {
                    dir.mkdirs();
                    copyFile(src, tmp);
                    if (!tmp.renameTo(new File(dir, name)))
                        throw new IOException("could not move " + tmp + " into place");
                    Log.i(TAG, "installed the Android CJK font " + src + " in the rootfs");
                    break;
                } catch (IOException e) {
                    Log.w(TAG, "could not install " + src, e); // the desktop starts anyway: Chinese text stays boxes
                }
            }
        }
        File conf = new File(rootfs, CJK_CONF);
        if (!conf.exists()) {
            conf.getParentFile().mkdirs();
            try (java.io.FileWriter w = new java.io.FileWriter(conf)) {
                w.write(CJK_CONF_TEXT);
            } catch (IOException e) {
                Log.w(TAG, "could not write " + conf, e);
            }
        }
    }

    /** True when the font directory already holds a CJK font (a rootfs built with one, or a previous run). */
    private static boolean hasCjkFont(File dir) {
        File[] files = dir.listFiles();
        if (files == null)
            return false;
        for (File f : files)
            if (f.length() > 1L << 20)
                return true;
        return false;
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; )
                out.write(buf, 0, n);
        }
    }

    /** Sends Chinese text to the copied font: Android's NotoSansCJK-Regular.ttc has the zh-* faces too. */
    private static final String CJK_CONF_TEXT = "<?xml version=\"1.0\"?>\n"
            + "<!DOCTYPE fontconfig SYSTEM \"urn:fontconfig:fonts.dtd\">\n"
            + "<fontconfig>\n"
            + "  <!-- Andromarchy: the Arch rootfs ships no CJK font; SessionService copies the one Android provides\n"
            + "       into /usr/share/fonts/noto-cjk. Generic families asked for Chinese get it. -->\n"
            + "  <match target=\"pattern\">\n"
            + "    <test name=\"lang\" compare=\"contains\"><string>zh</string></test>\n"
            + "    <test qual=\"any\" name=\"family\"><string>sans-serif</string></test>\n"
            + "    <edit name=\"family\" mode=\"prepend\" binding=\"strong\"><string>Noto Sans CJK SC</string></edit>\n"
            + "  </match>\n"
            + "  <match target=\"pattern\">\n"
            + "    <test name=\"lang\" compare=\"contains\"><string>zh</string></test>\n"
            + "    <test qual=\"any\" name=\"family\"><string>serif</string></test>\n"
            + "    <edit name=\"family\" mode=\"prepend\" binding=\"strong\"><string>Noto Sans CJK SC</string></edit>\n"
            + "  </match>\n"
            + "  <match target=\"pattern\">\n"
            + "    <test name=\"lang\" compare=\"contains\"><string>zh</string></test>\n"
            + "    <test qual=\"any\" name=\"family\"><string>monospace</string></test>\n"
            + "    <edit name=\"family\" mode=\"prepend\" binding=\"strong\"><string>Noto Sans CJK SC</string></edit>\n"
            + "  </match>\n"
            + "</fontconfig>\n";

    /**
     * Android has no logind, and the rootfs ships systemd's real {@code systemd-inhibit} in /usr/bin: it asks
     * logind for an inhibitor over D-Bus and fails. Omarchy's power menu wraps Shutdown in
     * {@code systemd-inhibit ... omarchy-system shutdown --inhibited}, so that failure stopped the shutdown
     * halfway: the desktop showed "shutdown failed" and stayed up, and the poweroff request never reached the app.
     * A shim earlier in PATH (/usr/local/bin) just runs the wrapped command. Rootfs builds install the same file
     * (native/systemd-inhibit-shim.sh); this covers the images that are already out there.
     */
    private void installSystemdInhibitShim() {
        File shim = new File(rootfs(this), "usr/local/bin/systemd-inhibit");
        byte[] want = SYSTEMD_INHIBIT_SHIM.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            boolean same = shim.isFile()
                    && java.util.Arrays.equals(want, java.nio.file.Files.readAllBytes(shim.toPath()));
            if (!same) {
                shim.getParentFile().mkdirs();
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(shim)) {
                    out.write(want);
                }
                android.system.Os.chmod(shim.getPath(), 0755); // a normal tool, not a private app file
                Log.i(TAG, "installed the systemd-inhibit shim in the rootfs");
            } else if (!shim.canExecute()) {
                android.system.Os.chmod(shim.getPath(), 0755);
            }
        } catch (Exception e) {
            Log.w(TAG, "could not install " + shim, e); // the desktop starts anyway: shutdown stays broken
        }
    }

    /** Runs the wrapped command instead of asking logind for an inhibitor (see installSystemdInhibitShim). */
    private static final String SYSTEMD_INHIBIT_SHIM = "#!/bin/sh\n"
            + "# Andromarchy: there is no logind, so there is nothing to ask for an inhibitor. Omarchy's shutdown is\n"
            + "# wrapped in systemd-inhibit, and the real binary failing stopped it before systemctl ran. Run it instead.\n"
            + "if [ \"$1\" = \"--list\" ] || [ \"$1\" = \"--list-details\" ]; then\n"
            + "  echo \"No inhibitors.\"\n"
            + "  exit 0\n"
            + "fi\n"
            + "while [ \"$#\" -gt 0 ]; do\n"
            + "  case \"$1\" in\n"
            + "    -*) shift ;;\n"
            + "    *) break ;;\n"
            + "  esac\n"
            + "done\n"
            + "[ \"$#\" -gt 0 ] || exit 0\n"
            + "exec \"$@\"\n";

    @Override
    public void onCreate() {
        super.onCreate();
        started = true;
        T.init(this); // the notification copy follows the console's ui_lang, even when the service starts first
        HostGestures.apply(this); // HyperOS 3-finger gestures off again after a restart, if allowed
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Session", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent controls = PendingIntent.getActivity(this, 1, new Intent(this, ControlsActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent console = PendingIntent.getActivity(this, 2, new Intent(this, ShellActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("AOmarchy")
                .setContentText(T.t("session.content"))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, T.t("session.action_controls"), controls).build())
                .addAction(new Notification.Action.Builder(null, T.t("session.action_console"), console).build())
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, n);

        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "andromarchy:session");
        wakeLock.acquire();

        // Battery for the session's UPower stand-in (android-upower): apps cannot read /sys/class/power_supply.
        registerReceiver(battery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        startLocation(this);
        // Active network for the NetworkManager stand-in (android-nm): apps cannot see the interfaces.
        getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(network);
        // Requests from Linux for Android-only actions (Wi-Fi panel, launching Android apps).
        requests = new AndroidRequests(this, runDir(this));
        requests.startWatching();
        // Android apps in Omarchy's menu (.desktop entries in the rootfs, launched through the requests above).
        apps = new AndroidApps(this);
        apps.register(this);

        try {
            startSession();
        } catch (IOException e) {
            Log.e(TAG, "session start failed", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // the processes belong to this service: started once, in onCreate
    }

    private void startSession() throws IOException {
        installCjkFonts(); // before anything fontconfig-based runs: Chinese text has a font to fall back to
        installSystemdInhibitShim(); // before Omarchy's power menu can be used: shutdown needs a fake inhibitor
        applySetup(); // name/email/keyboard chosen in the console's Settings, applied to the rootfs
        startedAt = System.currentTimeMillis();

        File run = runDir(this);
        File sock = daemonSocket(this);
        sock.getParentFile().mkdirs();
        sock.delete(); // a stale socket from a killed session would make the daemon's bind fail

        String libDir = getApplicationInfo().nativeLibraryDir;
        String launcher = libDir + "/liblotfixrun.so";

        // 0. First boot of this rootfs on this device (once; the locale part on each start): see first-boot.sh.
        try {
            ProcessBuilder pb = new ProcessBuilder(launcher, "/opt/lotfix/first-boot.sh");
            pb.environment().clear();
            pb.environment().putAll(archEnv(this, run, sock));
            Process fb = pb.redirectErrorStream(true)
                    .redirectOutput(new File(run, "first-boot.log")).start();
            fb.getOutputStream().close();
            fb.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 1. Display daemon: relays buffers and input between this app's display and Hyprland.
        spawn("andromarchy", List.of(libDir + "/libandromarchy.so", sock.getPath()), null);

        // 2. Session D-Bus (systemd provides it on a normal Linux login). Browsers, file dialogs, portals and tray
        //    apps need it; archEnv() gives every program its address.
        new File(run, "bus").delete();
        spawn("dbus", List.of(launcher, "-c", "exec dbus-daemon --session --nofork --nopidfile --address=unix:path="
                + new File(run, "bus").getPath()), archEnv(this, run, sock));

        // 2b. A "system" bus of our own (there is none for apps), for the services standing in for hardware ones.
        new File(run, "system_bus").delete();
        spawn("system-dbus", List.of(launcher, "-c", "exec dbus-daemon --config-file=/opt/lotfix/system-bus.conf"
                + " --nofork --nopidfile --address=unix:path=" + new File(run, "system_bus").getPath()), archEnv(this, run, sock));
        spawn("android-nm", List.of(launcher, "-c", "for i in $(seq 50); do [ -S \"$XDG_RUNTIME_DIR/system_bus\" ]"
                + " && break; sleep 0.1; done; exec android-nm"), archEnv(this, run, sock));
        spawn("android-upower", List.of(launcher, "-c", "for i in $(seq 50); do [ -S \"$XDG_RUNTIME_DIR/system_bus\" ]"
                + " && break; sleep 0.1; done; exec android-upower"), archEnv(this, run, sock));
        // The other system services a desktop expects (login1, hostname1, timedate1, locale1, PolicyKit, power
        // profiles, Accounts, RealtimeKit, GeoClue), as stand-ins: native/android-system-services.py.
        spawn("android-system-services", List.of(launcher, "-c", "for i in $(seq 50); do [ -S \"$XDG_RUNTIME_DIR/"
                + "system_bus\" ] && break; sleep 0.1; done; exec android-system-services"), archEnv(this, run, sock));
        // Session side: ScreenSaver inhibit and FileManager1 (native/android-session-services.py), and the secrets
        // keyring (passwords for browsers, VS Code, git). No login screen unlocks a keyring here: a password-less
        // "login" keyring is created once (gnome-keyring's plain format) and made the default.
        spawn("android-session-services", List.of(launcher, "-c", "for i in $(seq 50); do [ -S \"$XDG_RUNTIME_DIR/"
                + "bus\" ] && break; sleep 0.1; done; exec android-session-services"), archEnv(this, run, sock));
        spawn("gnome-keyring", List.of(launcher, "-c", "for i in $(seq 50); do [ -S \"$XDG_RUNTIME_DIR/bus\" ] && break;"
                + " sleep 0.1; done; K=$HOME/.local/share/keyrings; mkdir -p $K && chmod 700 $K;"
                + " [ -f $K/login.keyring ] || printf '[keyring]\\ndisplay-name=Login\\nctime=0\\nmtime=0\\n"
                + "lock-on-idle=false\\nlock-after=false\\n' > $K/login.keyring; [ -f $K/default ] || printf login > $K/default;"
                + " exec gnome-keyring-daemon --foreground --components=secrets < /dev/null"), archEnv(this, run, sock));

        // 2c. Hardware video (termux-va, third_party/): decodes and encodes with MediaCodec for the VA-API bridge.
        spawn("termux-va", List.of(libDir + "/libtermuxva.so", "--sock", new File(run, "termux-va.sock").getPath()),
                null);

        // 3. Sound: Hyprland's Andromarchy backend creates PipeWire nodes, so the stack must exist (it reconnects anyway).
        //    wireplumber and pipewire-pulse give up when PipeWire is not up yet (a cold first start): they wait for its
        //    socket.
        spawn("pipewire", List.of(launcher, "-c", "exec pipewire"), archEnv(this, run, sock));
        for (String p : new String[] {"wireplumber", "pipewire-pulse"})
            spawn(p, List.of(launcher, "-c", "for i in $(seq 100); do [ -S \"$XDG_RUNTIME_DIR/pipewire-0\" ] && break;"
                    + " sleep 0.1; done; exec " + p), archEnv(this, run, sock));

        // 4. Hyprland with Omarchy. It waits for this app's display to connect (MainActivity).
        Map<String, String> env = archEnv(this, run, sock);
        env.put("EGL_PLATFORM", "surfaceless");
        env.put("OMARCHY_PATH", "/usr/share/omarchy");
        spawn("hyprland", List.of(launcher, "-c", "export PATH=/usr/share/omarchy/bin:$PATH; exec /usr/bin/Hyprland"), env);

        // 5. desktop-ready (ends the splash, SplashScreen): Omarchy's shell runs, plus a second to draw the bar.
        spawn("desktop-ready", List.of(launcher, "-c", "for i in $(seq 600); do pgrep -x quickshell >/dev/null"
                + " || pgrep -x qs >/dev/null && break; sleep 0.2; done; sleep 1; touch \"$XDG_RUNTIME_DIR/desktop-ready\""),
                archEnv(this, run, sock));
    }

    /** Android's language as a glibc locale name (fr_FR.UTF-8); C.UTF-8 when it has no country. */
    static String androidLocale(Context ctx) {
        java.util.Locale l = ctx.getResources().getConfiguration().getLocales().get(0);
        return l.getCountry().isEmpty() ? "C.UTF-8" : l.getLanguage() + "_" + l.getCountry() + ".UTF-8";
    }

    /** Environment for programs of the rootfs; lotfix-run sets the rest (loader, fakechroot, GPU...). */
    private static Map<String, String> archEnv(Context app, File run, File sock) {
        Map<String, String> env = new java.util.HashMap<>(System.getenv());
        env.put("ARCH_ROOTFS", rootfs(app).getPath());
        env.put("ARCH_EXCLUDE_EXTRA", run.getPath()); // sockets: real paths, not rewritten into the rootfs
        env.put("XDG_RUNTIME_DIR", run.getPath());
        env.put("TZ", java.util.TimeZone.getDefault().getID()); // glibc only knows /etc/localtime otherwise: UTC
        env.put("LANG", androidLocale(app)); // Android's language (first-boot.sh compiles its locale)
        env.put("ANDROMARCHY_UID", String.valueOf(android.os.Process.myUid()));
        env.put("ANDROMARCHY_GID", String.valueOf(android.os.Process.myUid()));
        env.put("ANDROMARCHY_SOCKET", sock.getPath());
        // The display's physical size (landscape, mm) for the compositor's output: clients size themselves from it
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        ((android.view.WindowManager) app.getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        int wmm = Math.round(Math.max(dm.widthPixels / dm.xdpi, dm.heightPixels / dm.ydpi) * 25.4f);
        int hmm = Math.round(Math.min(dm.widthPixels / dm.xdpi, dm.heightPixels / dm.ydpi) * 25.4f);
        env.put("ANDROMARCHY_PHYSICAL_MM", wmm + "x" + hmm);
        // Flatpak apps' launchers (exported .desktop files, icons): a normal login gets these from
        // /etc/profile.d/flatpak.sh or systemd's environment generator; this session starts from the app.
        env.put("XDG_DATA_DIRS", "/root/.local/share/flatpak/exports/share:/var/lib/flatpak/exports/share"
                + ":/usr/local/share:/usr/share");
        env.put("ANDROMARCHY_VENDOR", android.os.Build.MANUFACTURER); // the machine's identity (DMI) for Linux tools
        env.put("ANDROMARCHY_MODEL", android.os.Build.MODEL);
        env.put("DBUS_SESSION_BUS_ADDRESS", "unix:path=" + new File(run, "bus").getPath());
        env.put("DBUS_SYSTEM_BUS_ADDRESS", "unix:path=" + new File(run, "system_bus").getPath());
        // Hardware video: VA-API through Mesa's termuxva bridge to the termux-va daemon (MediaCodec), when the
        // rootfs's Mesa has the bridge.
        if (new File(rootfs(app), "usr/lib/dri/termuxva_drv_video.so").exists()) {
            env.put("LIBVA_DRIVER_NAME", "termuxva");
            env.put("TERMUX_VA_BRIDGE", "1");
            env.put("TERMUX_VA_GPU_BACKEND", "kgsl");
            env.put("TERMUX_VA_SOCKET", new File(run, "termux-va.sock").getPath());
        }
        env.remove("WAYLAND_DISPLAY");
        env.remove("DISPLAY");
        return env;
    }

    /**
     * Applies the setup answers that used to be asked on first launch: name/email (git identity, XCompose) and the
     * keyboard layouts (the hyprland input config). Only values the user set are written; an untouched install keeps
     * what the installer derived from the device language.
     */
    private void applySetup() {
        File rootfs = rootfs(this);
        android.content.SharedPreferences sp = getSharedPreferences(Prefs.NAME, MODE_PRIVATE);
        String name = sp.getString("user_name", "").trim();
        String email = sp.getString("user_email", "").trim();
        String kb = sp.getString("kb_layouts", "").trim();
        try {
            if (!kb.isEmpty()) {
                File conf = new File(rootfs, "root/.config/andromarchy/setup.conf");
                conf.getParentFile().mkdirs();
                writeText(conf, "KB_LAYOUTS=" + kb + " FULL_NAME=" + shellQuote(name) + " EMAIL=" + shellQuote(email) + "\n");
                File input = new File(rootfs, "root/.config/hypr/input.lua");
                String text = input.isFile() ? readText(input) : "";
                StringBuilder kept = new StringBuilder();
                boolean skipping = false;
                for (String line : text.split("\n", -1)) {
                    if (line.startsWith("-- Android/Andromarchy: keyboard layouts")
                            || line.startsWith("-- Android/AOmarchy: keyboard layouts")) {
                        skipping = true;
                        continue;
                    }
                    if (skipping) {
                        if (line.contains("kb_layout = "))
                            skipping = false;
                        continue;
                    }
                    kept.append(line).append('\n');
                }
                kept.append("-- Android/AOmarchy: keyboard layouts (settings; the first one is the physical keyboard's, the\n")
                        .append("-- on-screen keyboard cycles through all)\n")
                        .append("hl.config({ input = { kb_layout = \"").append(kb).append("\" } })\n");
                input.getParentFile().mkdirs();
                writeText(input, kept.toString());
                Log.i(TAG, "applied keyboard layouts: " + kb);
            }
            if (!name.isEmpty() || !email.isEmpty()) {
                File gitconfig = new File(rootfs, "root/.gitconfig");
                String existing = gitconfig.isFile() ? readText(gitconfig) : "";
                if (!existing.contains("[user]")) {
                    writeText(gitconfig, existing
                            + "\n[user]\n\tname = " + name + "\n\temail = " + email + "\n");
                }
                File xcompose = new File(rootfs, "root/.XCompose");
                writeText(xcompose, "include \"/usr/share/omarchy/default/xcompose\"\n"
                        + "<Multi_key> <space> <n> : \"" + name + "\"\n"
                        + "<Multi_key> <space> <e> : \"" + email + "\"\n");
                Log.i(TAG, "applied identity: " + name);
            }
        } catch (IOException e) {
            Log.w(TAG, "applySetup failed", e);
        }
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String readText(File f) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void writeText(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        java.nio.file.Files.write(f.toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    /** argv for a pty login shell inside the rootfs: terminal mode reuses the same launcher the desktop session uses. */
    public static String[] ptyArgv(Context ctx, String command) {
        return new String[] {ctx.getApplicationInfo().nativeLibraryDir + "/liblotfixrun.so", "-c",
                "export PATH=/usr/share/omarchy/bin:/usr/local/bin:/usr/bin:/bin; " + command};
    }

    /** The session environment as a KEY=value array, for execve of the pty child. */
    public static String[] ptyEnv(Context ctx) {
        java.util.Map<String, String> env = archEnv(ctx, runDir(ctx), daemonSocket(ctx));
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> e : env.entrySet()) {
            if (e.getKey() != null && e.getValue() != null)
                out.add(e.getKey() + "=" + e.getValue());
        }
        return out.toArray(new String[0]);
    }

    /** Where the pty child starts: the rootfs itself (the launcher chroots and the shell lands in /root). */
    public static File ptyCwd(Context ctx) {
        return rootfs(ctx);
    }
    /** Runs one shell command inside the rootfs with the session environment: the console launching a Linux app. */
    public static void runInSession(Context ctx, String command) {
        String launcher = ctx.getApplicationInfo().nativeLibraryDir + "/liblotfixrun.so";
        // The launcher takes -c <command>; the session PATH is not inherited from Android, so set it here.
        java.util.List<String> full = java.util.Arrays.asList(launcher, "-c",
                "export PATH=/usr/share/omarchy/bin:/usr/local/bin:/usr/bin:/bin; " + command);
        ProcessBuilder pb = new ProcessBuilder(full);
        pb.environment().clear();
        pb.environment().putAll(archEnv(ctx, runDir(ctx), daemonSocket(ctx)));
        pb.environment().put("WAYLAND_DISPLAY", "wayland-1"); // the compositor the session started
        pb.environment().put("XDG_SESSION_TYPE", "wayland");
        pb.redirectErrorStream(true).redirectOutput(new File(runDir(ctx), "ui-run.log"));
        try {
            pb.start();
        } catch (IOException e) {
            Log.w(TAG, "runInSession failed", e);
        }
    }
    /** Android's default network -> run/network ("wifi <strength> <ssid>" | "wired" | "none"), read by android-nm. */
    private AndroidRequests requests;
    private AndroidApps apps;

    // FLAG_INCLUDE_LOCATION_INFO: the Wi-Fi name (SSID) instead of "<unknown ssid>", once location is granted.
    private final ConnectivityManager.NetworkCallback network = new ConnectivityManager.NetworkCallback(
            ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
        @Override
        public void onLinkPropertiesChanged(Network n, android.net.LinkProperties lp) {
            StringBuilder dns = new StringBuilder(); // for omarchy-dns "DHCP": the network's own DNS servers
            for (java.net.InetAddress a : lp.getDnsServers())
                dns.append(dns.length() > 0 ? " " : "").append(a.getHostAddress());
            writeState("dns", dns.toString());
        }

        @Override
        public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                int strength = 0;
                String ssid = "";
                if (caps.getTransportInfo() instanceof WifiInfo) {
                    WifiInfo w = (WifiInfo) caps.getTransportInfo();
                    strength = Math.max(0, Math.min(100, 2 * (w.getRssi() + 100))); // -100..-50 dBm -> 0..100
                    ssid = w.getSSID().replace("\"", ""); // redacted without the location permission
                    if (ssid.startsWith("<"))
                        ssid = "";
                }
                writeState("network", "wifi " + strength + " " + ssid);
            } else
                writeState("network", "wired"); // mobile data, USB, Ethernet: Quickshell only knows wired
        }

        @Override
        public void onLost(Network n) {
            writeState("network", "none");
        }
    };

    /** Atomic write of one line to run/<name>: the reader never sees half a line. */
    private void writeState(String name, String line) {
        File f = new File(runDir(this), name), tmp = new File(runDir(this), "." + name);
        try (java.io.FileWriter w = new java.io.FileWriter(tmp)) {
            w.write(line + "\n");
        } catch (IOException e) {
            return;
        }
        tmp.renameTo(f);
    }

    /** Android battery state -> run/battery ("<percent> <charging 0|1>"), read by android-upower. */
    /** Android's network location -> run/location ("lat lon accuracy unixtime"), served as GeoClue2 by
     *  android-system-services. Network provider: coarse, cheap on battery; every 10 minutes or 500 m. Called again
     *  by MainActivity once the permission is granted. */
    static void startLocation(Context ctx) {
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return;
        android.location.LocationManager lm = ctx.getSystemService(android.location.LocationManager.class);
        String provider = lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
                ? android.location.LocationManager.NETWORK_PROVIDER : android.location.LocationManager.PASSIVE_PROVIDER;
        android.location.LocationListener l = loc -> {
            File f = new File(runDir(ctx), "location"), tmp = new File(runDir(ctx), ".location");
            try (java.io.FileWriter w = new java.io.FileWriter(tmp)) {
                w.write(loc.getLatitude() + " " + loc.getLongitude() + " " + loc.getAccuracy() + " "
                        + loc.getTime() / 1000 + "\n");
            } catch (IOException e) {
                return;
            }
            tmp.renameTo(f);
        };
        android.location.Location last = lm.getLastKnownLocation(provider);
        if (last != null)
            l.onLocationChanged(last);
        lm.requestLocationUpdates(provider, 10 * 60 * 1000L, 500f, l, android.os.Looper.getMainLooper());
    }

    private final BroadcastReceiver battery = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
            if (level < 0)
                return;
            File f = new File(runDir(ctx), "battery"), tmp = new File(runDir(ctx), ".battery");
            try (java.io.FileWriter w = new java.io.FileWriter(tmp)) {
                w.write((level * 100 / scale) + " " + (charging ? 1 : 0) + "\n");
            } catch (IOException e) {
                return;
            }
            tmp.renameTo(f); // atomic: the reader never sees half a line
        }
    };

    private void spawn(String name, List<String> cmd, Map<String, String> env) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (env != null) {
            pb.environment().clear();
            pb.environment().putAll(env);
        }
        File log = new File(runDir(this), name + ".log");
        pb.redirectErrorStream(true).redirectOutput(log);
        processes.add(pb.start());
        Log.i(TAG, "started " + name + " (log: " + log + ")");
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(battery);
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(network);
        requests.stopWatching();
        unregisterReceiver(apps);
        for (Process p : processes)
            p.destroy();
        if (wakeLock != null && wakeLock.isHeld())
            wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
