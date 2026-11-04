package app.aomarchy.data;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.aomarchy.SessionService;

/**
 * What the Apps page lists: Flatpak sandboxes, the desktop's own applications, and Android apps that can be opened
 * in a floating window. Everything is read from the rootfs files (no command runs), so the page opens instantly and
 * works even before a session starts.
 */
public final class Apps {
    /** One Linux application, from a .desktop entry. */
    public static final class LinuxApp {
        public final String id; // the .desktop file name, for gtk-launch
        public final String name;
        public final boolean flatpak;

        LinuxApp(String id, String name, boolean flatpak) {
            this.id = id;
            this.name = name;
            this.flatpak = flatpak;
        }
    }

    /** One Android app that can be shown in a floating window next to the desktop. */
    public static final class AndroidApp {
        public final String packageName;
        public final String label;
        public final Drawable icon;

        AndroidApp(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    private Apps() {
    }

    /** Flatpak apps: exported .desktop files carry X-Flatpak. */
    public static List<LinuxApp> flatpak(Context ctx) {
        List<LinuxApp> out = new ArrayList<>();
        for (LinuxApp a : desktop(ctx))
            if (a.flatpak)
                out.add(a);
        return out;
    }

    /** The rest of the desktop's applications (Omarchy, files, terminal…). */
    public static List<LinuxApp> nativeApps(Context ctx) {
        List<LinuxApp> out = new ArrayList<>();
        for (LinuxApp a : desktop(ctx))
            if (!a.flatpak)
                out.add(a);
        return out;
    }

    private static List<LinuxApp> desktop(Context ctx) {
        File rootfs = SessionService.rootfs(ctx);
        File[] dirs = {
                new File(rootfs, "usr/share/applications"),
                new File(rootfs, "root/.local/share/applications"),
                new File(rootfs, "var/lib/flatpak/exports/share/applications"),
                new File(rootfs, "root/.local/share/flatpak/exports/share/applications")
        };
        Map<String, LinuxApp> byId = new LinkedHashMap<>();
        for (File dir : dirs) {
            File[] files = dir.listFiles((d, n) -> n.endsWith(".desktop"));
            if (files == null)
                continue;
            for (File f : files) {
                LinuxApp app = parseDesktop(f);
                if (app == null || byId.containsKey(app.id))
                    continue;
                byId.put(app.id, app);
            }
        }
        List<LinuxApp> out = new ArrayList<>(byId.values());
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    private static LinuxApp parseDesktop(File f) {
        String name = null, exec = null, flatpakId = null;
        boolean hidden = false;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) {
                String l = line.trim();
                if (l.startsWith("Name=") && name == null) {
                    name = l.substring(5).trim();
                } else if (l.startsWith("Exec=") && exec == null) {
                    exec = l.substring(5).trim();
                } else if (l.startsWith("X-Flatpak=")) {
                    flatpakId = l.substring(10).trim();
                } else if (l.equals("NoDisplay=true") || l.equals("Hidden=true")) {
                    hidden = true;
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (hidden || name == null || exec == null || exec.isEmpty())
            return null;
        String id = f.getName().substring(0, f.getName().length() - ".desktop".length());
        return new LinuxApp(id, name, flatpakId != null && !flatpakId.isEmpty());
    }

    /** Android apps with a launcher activity, minus this app. */
    public static List<AndroidApp> android(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
        List<AndroidApp> out = new ArrayList<>();
        String self = ctx.getPackageName();
        Map<String, AndroidApp> byPackage = new LinkedHashMap<>();
        for (ResolveInfo info : infos) {
            if (info.activityInfo == null)
                continue;
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(self) || byPackage.containsKey(pkg))
                continue;
            CharSequence label = info.loadLabel(pm);
            Drawable icon = info.loadIcon(pm);
            byPackage.put(pkg, new AndroidApp(pkg, label == null ? pkg : label.toString(), icon));
        }
        out.addAll(byPackage.values());
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    /** Asks the session's Android bridge to open this app in a floating window (same request file as Linux uses). */
    public static void openFloating(Context ctx, String pkg) {
        File run = SessionService.runDir(ctx);
        run.mkdirs();
        File tmp = new File(run, "android-request.tmp");
        try {
            Files.write(tmp.toPath(), ("float " + pkg + "\n").getBytes());
            if (!tmp.renameTo(new File(run, "android-request")))
                throw new IOException("rename failed");
        } catch (IOException e) {
            android.util.Log.w("Apps", "float request failed for " + pkg, e);
        }
    }

    /** Launches a Linux application on the desktop (needs a running session). */
    public static void launchLinux(Context ctx, LinuxApp app) {
        SessionService.runInSession(ctx, "exec gtk-launch " + app.id);
    }

    public static String size(Context ctx, List<?> list) {
        return String.format(Locale.US, "%d", list.size());
    }
}
