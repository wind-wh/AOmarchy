package app.aomarchy.data;

import android.content.Context;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.aomarchy.SessionService;

/**
 * Finds the Linux desktop's own wallpaper inside the installed rootfs, so the console can show the Omarchy / Hyprland
 * background even when the ROM refuses to hand Android's wallpaper over (Android 13+ / MIUI) and the user would rather
 * see the Linux side anyway.
 *
 * <p>Candidates, in priority order (all paths are relative to {@link SessionService#rootfs(Context)}). The Linux session
 * runs as user {@code android} with {@code HOME=/root} (see {@code native/launcher/lotfix-run.c}), so Omarchy's config
 * lives under {@code /root}; {@code /home/android} is searched too, in case a different installer used a regular user
 * home:
 * <ol>
 *   <li><b>Omarchy's active theme</b> — {@code ~/.config/omarchy/current/theme/background.{jpg,jpeg,png,webp}}, then the
 *       same directory's {@code wallpaper.*}, then {@code ~/.config/omarchy/current/background.*}. {@code current} is a
 *       symlink to the active theme directory, so its path components are followed on purpose.</li>
 *   <li><b>Image paths in the compositor config</b> — {@code ~/.config/hypr/hyprland.conf}, {@code hyprpaper.conf} and
 *       {@code ~/.config/swww/swww.conf}: every token ending in an image extension is extracted ({@code preload = ...},
 *       {@code wallpaper = ,...}, {@code exec-once = swww img ...}, …), {@code ~/} and {@code $HOME/} are expanded to
 *       the same Linux home and absolute paths are mapped into the rootfs.</li>
 *   <li><b>Any Omarchy theme</b> — {@code ~/.config/omarchy/themes/<name>/background.*}, first theme (by name) that has
 *       one.</li>
 *   <li><b>Bounded directory scan</b> — {@code ~/Pictures}, {@code ~/.local/share/wallpapers},
 *       {@code /usr/share/backgrounds}, {@code /usr/share/wallpapers}; only files whose name contains
 *       "wallpaper"/"background" with an image extension, at most {@value #MAX_SCAN_ENTRIES} entries inspected in total
 *       and at most {@value #MAX_SCAN_DEPTH} directory levels deep. Symlinked entries are skipped here (no escape from
 *       the rootfs by walking a link).</li>
 * </ol>
 *
 * <p>Every candidate is probed with {@code BitmapFactory} bounds first, so a corrupt or non-image file is never
 * returned. The first hit is remembered in prefs {@code linux_wallpaper_path} and tried before the search next time;
 * if that file disappeared the cache is dropped and the search runs again.
 *
 * <p>All IO here is synchronous: {@code ui/Wallpaper} calls {@link #find(Context)} from its already-off-main-thread
 * loader. Nothing does a full-filesystem walk.
 */
public final class LinuxWallpaper {
    /** Must match {@code app.aomarchy.Prefs.NAME}; that class is package-private, so the name is repeated here. */
    private static final String PREFS = "andromarchy_settings";
    /** Absolute path of the last hit, tried first on the next console build. */
    private static final String KEY_PATH = "linux_wallpaper_path";

    /** The Linux session's HOME inside the rootfs: the launcher runs with HOME=/root; the second entry is the fallback
     *  layout a regular-user installer would produce. */
    private static final String[] HOME_ROOTS = {"root", "home/android"};
    /** Image extensions, in the order they are preferred when several variants sit next to each other. */
    private static final String[] EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"};
    /** Total directory entries inspected by the bounded scan (all scan roots together). */
    private static final int MAX_SCAN_ENTRIES = 200;
    /** Directory levels entered below a scan root (root + one level = files two levels deep at most). */
    private static final int MAX_SCAN_DEPTH = 2;
    /** Any image path in a config line; punctuation used by hyprpaper/hyprland around paths is excluded. */
    private static final Pattern CONFIG_IMAGE = Pattern.compile(
            "[^\\s\"'=,;()\\[\\]]+\\.(?:jpe?g|png|webp)", Pattern.CASE_INSENSITIVE);
    /** Config files that can name a wallpaper: hyprpaper/Hyprland, plus the swww config when present. */
    private static final String[] CONFIG_FILES = {
            ".config/hypr/hyprpaper.conf",
            ".config/hypr/hyprland.conf",
            ".config/swww/swww.conf",
    };
    /** Wallpaper folders inside the Linux home, scanned as a last resort. */
    private static final String[] HOME_SCAN_DIRS = {
            "Pictures",
            ".local/share/wallpapers",
    };
    /** System wallpaper folders inside the rootfs, same last-resort scan. */
    private static final String[] ROOT_SCAN_DIRS = {
            "usr/share/backgrounds",
            "usr/share/wallpapers",
    };

    private LinuxWallpaper() {
    }

    /**
     * The best wallpaper the Linux side has to offer, or {@code null}. Tries the remembered path first (dropping it if
     * the file is gone or no longer decodes), then walks the priority list above. Runs synchronously — call it off the
     * main thread.
     */
    public static File find(Context context) {
        if (context == null)
            return null;
        File cached = cachedPath(context);
        if (cached != null)
            return cached;
        File found = search(context);
        if (found != null)
            remember(context, found);
        return found;
    }

    /** The remembered wallpaper, or {@code null} when it was never set or has gone stale. */
    public static File cachedPath(Context context) {
        if (context == null)
            return null;
        String value = prefs(context).getString(KEY_PATH, "");
        if (value == null || value.trim().isEmpty())
            return null;
        File file = new File(value.trim());
        if (isUsableImage(file))
            return file;
        clearCached(context); // deleted/replaced since the last console build: search again
        return null;
    }

    /** Forgets the remembered wallpaper (the next {@link #find(Context)} searches again). */
    public static void clearCached(Context context) {
        if (context == null)
            return;
        prefs(context).edit().remove(KEY_PATH).apply();
    }

    // ---- searching -----------------------------------------------------------------------------------------------

    private static File search(Context context) {
        File rootfs = SessionService.rootfs(context);
        if (rootfs == null || !rootfs.isDirectory())
            return null; // system not installed (yet)
        List<File> homes = new ArrayList<>(HOME_ROOTS.length);
        for (String relative : HOME_ROOTS)
            homes.add(new File(rootfs, relative));

        // 1) Omarchy's active theme: current is a symlink to themes/<active>, so these paths go through links on purpose.
        for (File home : homes) {
            File found = currentTheme(home);
            if (found != null)
                return found;
        }

        // 2) Whatever the compositor config points at (hyprpaper preload/wallpaper, swww/swaybg exec lines, …).
        for (File home : homes) {
            File found = fromConfigs(rootfs, home);
            if (found != null)
                return found;
        }

        // 3) Any Omarchy theme's background, in name order so the pick is stable between console builds.
        for (File home : homes) {
            File found = themeBackground(home);
            if (found != null)
                return found;
        }

        // 4) Bounded scan of the usual wallpaper directories (one shared entry budget across all of them).
        int[] budget = {MAX_SCAN_ENTRIES};
        for (File home : homes) {
            for (String relative : HOME_SCAN_DIRS) {
                File found = scanTree(new File(home, relative), budget);
                if (found != null)
                    return found;
            }
        }
        for (String relative : ROOT_SCAN_DIRS) {
            File found = scanTree(new File(rootfs, relative), budget);
            if (found != null)
                return found;
        }
        return null;
    }

    /** Omarchy's active theme for one home: current/theme/background, current/theme/wallpaper, then current/background. */
    private static File currentTheme(File home) {
        File current = new File(home, ".config/omarchy/current");
        File found = imageIn(new File(current, "theme"), "background", "wallpaper");
        return found != null ? found : imageIn(current, "background");
    }

    /** Any Omarchy theme's background for one home: the first theme by name that ships one. */
    private static File themeBackground(File home) {
        File[] themes = new File(home, ".config/omarchy/themes").listFiles();
        if (themes == null)
            return null;
        Arrays.sort(themes, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File theme : themes) {
            if (!theme.isDirectory())
                continue;
            File found = imageIn(theme, "background");
            if (found != null)
                return found;
        }
        return null;
    }

    /** {@code dir/<base>.<ext>} for each base and extension, in order; the first decodable image wins. */
    private static File imageIn(File dir, String... bases) {
        if (dir == null || !dir.isDirectory())
            return null;
        for (String base : bases) {
            for (String extension : EXTENSIONS) {
                File candidate = new File(dir, base + extension);
                if (isUsableImage(candidate))
                    return candidate;
            }
        }
        return null;
    }

    /** Extracts image paths from the compositor config files and resolves them inside the rootfs. */
    private static File fromConfigs(File rootfs, File home) {
        for (String relative : CONFIG_FILES) {
            File config = new File(home, relative);
            if (!config.isFile())
                continue;
            List<String> lines;
            try {
                lines = Files.readAllLines(config.toPath(), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                continue; // unreadable config: try the next one
            }
            for (String line : lines) {
                Matcher matcher = CONFIG_IMAGE.matcher(line);
                while (matcher.find()) {
                    File resolved = resolve(rootfs, home, matcher.group());
                    if (resolved != null && isUsableImage(resolved))
                        return resolved;
                }
            }
        }
        return null;
    }

    /** Maps a path from a Linux config to a file inside the rootfs: {@code ~/}, {@code $HOME/} or an absolute path. */
    private static File resolve(File rootfs, File home, String token) {
        String path = token == null ? "" : token.trim();
        while (path.startsWith("\"") || path.startsWith("'"))
            path = path.substring(1);
        while (path.endsWith("\"") || path.endsWith("'"))
            path = path.substring(0, path.length() - 1);
        if (path.startsWith("$HOME/"))
            path = "~/" + path.substring("$HOME/".length());
        if (path.startsWith("~/"))
            return new File(home, path.substring(2));
        if (path.startsWith("/"))
            return new File(rootfs, path.substring(1));
        return null; // relative to an unknown cwd: ignore
    }

    /**
     * Breadth-first bounded scan of one wallpaper root. At most {@code budget[0]} entries are inspected across every
     * scan of one find() call; symlinked entries are skipped so a link can never pull the walk out of the rootfs.
     */
    private static File scanTree(File root, int[] budget) {
        if (root == null || !root.isDirectory())
            return null;
        ArrayDeque<Dir> pending = new ArrayDeque<>();
        pending.addLast(new Dir(root, 0));
        while (!pending.isEmpty()) {
            Dir current = pending.removeFirst();
            File[] children;
            try {
                children = current.dir.listFiles();
            } catch (RuntimeException e) {
                continue; // unreadable directory: keep scanning the rest
            }
            if (children == null)
                continue;
            Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File child : children) {
                if (budget[0] <= 0)
                    return null; // scan budget spent: stop looking
                budget[0]--;
                try {
                    if (Files.isSymbolicLink(child.toPath()))
                        continue;
                    if (child.isDirectory()) {
                        if (current.depth + 1 < MAX_SCAN_DEPTH)
                            pending.addLast(new Dir(child, current.depth + 1));
                    } else if (looksLikeWallpaper(child.getName()) && isUsableImage(child)) {
                        return child;
                    }
                } catch (RuntimeException ignored) {
                    // vanished/unreadable entry: skip it
                }
            }
        }
        return null;
    }

    private static boolean looksLikeWallpaper(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.contains("wallpaper") && !lower.contains("background"))
            return false;
        for (String extension : EXTENSIONS)
            if (lower.endsWith(extension))
                return true;
        return false;
    }

    /** True when the file exists and {@code BitmapFactory} can read its bounds (i.e. it really is an image). */
    private static boolean isUsableImage(File file) {
        if (file == null || !file.isFile() || file.length() <= 0)
            return false;
        try (FileInputStream in = new FileInputStream(file)) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, bounds);
            return bounds.outWidth > 0 && bounds.outHeight > 0;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // ---- prefs ---------------------------------------------------------------------------------------------------

    private static void remember(Context context, File file) {
        prefs(context).edit().putString(KEY_PATH, file.getAbsolutePath()).apply();
    }

    private static android.content.SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** A directory plus how many levels below a scan root it sits. */
    private static final class Dir {
        final File dir;
        final int depth;

        Dir(File dir, int depth) {
            this.dir = dir;
            this.depth = depth;
        }
    }
}
