package app.aomarchy.data;

import android.content.Context;
import android.net.Uri;
import android.os.StatFs;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import app.aomarchy.SessionService;
import app.aomarchy.TarExtractor;

/**
 * Installs the Linux system: fetches the archive (release download or a file on the device), unpacks it with
 * {@link TarExtractor} into rootfs.partial and renames it into place. Same steps the old first-launch page ran,
 * now callable from the console's System page. One install at a time, cancellable, and a cancelled or failed
 * attempt leaves the installed system untouched.
 */
public final class Installer {
    public interface Callback {
        /** permille < 0 when the size is unknown (a cloud provider that does not report it). */
        void progress(int permille, String text);

        void done();

        void failed(String message);
    }

    public static final String ROOTFS_URL =
            "https://github.com/lotfibennour/andromarchy/releases/latest/download/lotfix-rootfs.tar.gz";

    /** Fresh install: the extracted tree plus room to work. */
    public static final long NEEDED_FRESH = 6L << 30;
    /** Reinstall: the new tree next to the old one, which is only deleted after the swap. */
    public static final long NEEDED_REPLACE = 11L << 30;

    private static final String LOCAL_NAME = "lotfix-rootfs.tar.gz";

    private static volatile boolean running;
    private static volatile boolean cancelled;

    private Installer() {
    }

    public static boolean running() {
        return running;
    }

    public static void cancel() {
        cancelled = true;
    }

    /** The archive already staged on the device (external files dir first, then internal), or null. */
    public static File staged(Context ctx) {
        File[] candidates = {
                new File(ctx.getExternalFilesDir(null), LOCAL_NAME),
                new File(ctx.getFilesDir(), LOCAL_NAME)
        };
        for (File f : candidates) {
            if (f.isFile())
                return f;
        }
        return null;
    }

    public static String humanSize(long bytes) {
        if (bytes < 0)
            return "大小未知";
        if (bytes >= 1L << 30)
            return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
        return String.format(Locale.US, "%.0f MB", bytes / 1048576.0);
    }

    /**
     * Starts installing in the background. {@code local} null means the release download; {@code replace} deletes an
     * installed system only after the new one has been validated and is about to be moved into place.
     */
    public static void install(Context ctx, Uri local, boolean replace, Callback cb) {
        if (running)
            return;
        running = true;
        cancelled = false;
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                perform(app, local, replace, cb);
                cb.done();
            } catch (Cancelled e) {
                cb.failed("已取消安装");
            } catch (Throwable t) {
                cb.failed(String.valueOf(t.getMessage()));
            } finally {
                running = false;
            }
        }, "rootfs-install").start();
    }

    /** Thrown when the user cancels; the caller cleans up. */
    private static final class Cancelled extends IOException {
        Cancelled() {
            super("cancelled");
        }
    }

    private static void perform(Context ctx, Uri local, boolean replace, Callback cb) throws IOException {
        File rootfs = SessionService.rootfs(ctx);
        File partial = new File(rootfs.getPath() + ".partial");
        File files = ctx.getFilesDir();
        long available = new StatFs(files.getPath()).getAvailableBytes();
        long needed = replace && rootfs.exists() ? NEEDED_REPLACE : NEEDED_FRESH;
        if (available < needed)
            throw new IOException("空间不足：需要约 " + (needed >> 30) + " GB，当前可用 " + humanSize(available));

        deleteTree(partial);
        if (partial.exists())
            throw new IOException("上次安装的临时目录清理不掉（权限），请重启应用后重试");
        InputStream raw;
        long size;
        if (local != null) {
            raw = ctx.getContentResolver().openInputStream(local);
            if (raw == null)
                throw new IOException("打不开这个文件");
            size = -1;
            try {
                size = new File(local.getPath() == null ? "" : local.getPath()).length();
            } catch (RuntimeException ignored) {
                // content:// has no path: the progress then only counts up
            }
        } else {
            HttpURLConnection c = (HttpURLConnection) new URL(ROOTFS_URL).openConnection();
            c.setInstanceFollowRedirects(true); // GitHub release assets redirect to their storage host
            c.setConnectTimeout(30000);
            c.setReadTimeout(60000);
            c.connect();
            if (c.getResponseCode() != 200)
                throw new IOException("下载失败：HTTP " + c.getResponseCode());
            raw = c.getInputStream();
            size = c.getContentLengthLong();
        }

        long[] read = {0};
        long[] shown = {0};
        final String mode = local != null ? "安装中" : "下载并安装中";
        final long totalSize = size;
        try (InputStream stream = raw) { // closed even when the gzip header turns out to be corrupt
            InputStream counting = new FilterInputStream(new BufferedInputStream(stream, 1 << 20)) {
                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    checkCancelled();
                    int n = super.read(b, off, len);
                    if (n > 0)
                        read[0] += n;
                    return n;
                }
            };
            try (InputStream in = new GZIPInputStream(counting, 1 << 16)) {
                TarExtractor.extract(in, partial, entry -> {
                    if (cancelled)
                        throw new RuntimeException(new Cancelled());
                    long now = System.currentTimeMillis();
                    if (now - shown[0] < 200)
                        return;
                    shown[0] = now;
                    cb.progress(totalSize > 0 ? (int) (read[0] * 1000 / totalSize) : -1,
                            mode + "（" + (read[0] >> 20) + (totalSize > 0 ? " / " + (totalSize >> 20) : "")
                                    + " MB）\n" + entry);
                });
            }
        } catch (IOException e) {
            deleteTree(partial);
            if (cancelled)
                throw new Cancelled();
            throw e;
        } catch (RuntimeException e) {
            deleteTree(partial);
            if (e.getCause() instanceof Cancelled || cancelled)
                throw new Cancelled();
            throw e;
        }

        // Any .tar.gz can be picked: only an Andromarchy rootfs has the loader rootfsReady() looks for.
        if (!new File(partial, "opt/lotfix/arch-glibc/ld-linux-aarch64.so.1").exists()) {
            deleteTree(partial);
            throw new IOException("这个归档不是 AOmarchy 的系统镜像");
        }
        File setup = new File(partial, "root/.config/andromarchy/setup.conf");
        setup.getParentFile().mkdirs();
        if (!setup.getCanonicalPath().startsWith(partial.getCanonicalPath() + File.separator)) {
            deleteTree(partial);
            throw new IOException("归档里的 setup 路径越出了 rootfs");
        }
        try (FileWriter w = new FileWriter(setup)) {
            w.write("KB_LAYOUTS=" + defaultLayout() + " FULL_NAME= EMAIL=\n");
        }

        cb.progress(1000, "收尾中…");
        // Replace by renaming: the old system stays on disk (and can be put back) until the new one is in place,
        // and a slow or failed delete cannot break a finished install.
        File old = new File(rootfs.getPath() + ".old");
        boolean movedOld = false;
        if (replace && rootfs.exists()) {
            deleteTree(old);
            movedOld = rootfs.renameTo(old);
            if (!movedOld && !deleteTree(rootfs))
                throw new IOException("删不掉旧的系统（rootfs）");
        }
        if (!partial.renameTo(rootfs)) {
            if (movedOld)
                old.renameTo(rootfs); // put the old system back
            if (SessionService.rootfsReady(ctx))
                throw new IOException("无法把新系统移动到目标位置");
            deleteTree(rootfs);
            if (!partial.renameTo(rootfs))
                throw new IOException("无法把新系统移动到目标位置");
        }
        if (movedOld)
            deleteTree(old); // best effort: the install already succeeded, a leftover tree only costs space
    }

    private static void checkCancelled() throws IOException {
        if (cancelled)
            throw new Cancelled();
    }

    /** The on-screen keyboard's first layout: follow the device language, fall back to us. */
    private static String defaultLayout() {
        String lang = Locale.getDefault().getLanguage();
        switch (lang) {
            case "zh":
                return "cn";
            case "ja":
                return "jp";
            case "ko":
                return "kr";
            case "ar":
                return "ara";
            case "el":
                return "gr";
            case "he":
                return "il";
            case "fa":
                return "ir";
            case "uk":
                return "ua";
            case "fr":
            case "de":
            case "es":
            case "it":
            case "pt":
            case "ru":
            case "pl":
            case "tr":
            case "nl":
                return lang;
            default:
                return "us";
        }
    }

    /** Bytes used by a tree (the rootfs is ~100k files, so this runs off the UI thread). Symlinks are not followed. */
    public static long dirSize(File f) {
        if (f == null || !f.exists() || isSymlink(f))
            return 0;
        if (f.isFile())
            return f.length();
        long total = 0;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children)
                total += dirSize(c);
        }
        return total;
    }

    /** True for a symlink: File.isDirectory() follows it, and some rootfs links point outside (etc/mtab -> /proc/mounts). */
    private static boolean isSymlink(File f) {
        try {
            return android.system.OsConstants.S_ISLNK(android.system.Os.lstat(f.getPath()).st_mode);
        } catch (Exception e) {
            return false;
        }
    }

    /** Cleanup after an install: a leftover partial tree, and the old system a replace moved aside. */
    public static void cleanupAsync(Context ctx) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            File files = app.getFilesDir();
            for (String name : new String[] {"rootfs.partial", "rootfs.old"}) {
                File f = new File(files, name);
                if (!f.exists())
                    continue;
                for (int attempt = 0; attempt < 2 && f.exists(); attempt++) {
                    if (!deleteTree(f))
                        android.util.Log.w("Installer", "could not remove " + f + " (attempt " + (attempt + 1) + ")");
                }
            }
        }, "rootfs-cleanup").start();
    }

    /** Recursive delete; returns false when something could not be removed. Symlinks are deleted, never followed. */
    public static boolean deleteTree(File f) {
        if (f == null || !f.exists())
            return true;
        final boolean[] ok = {true};
        try {
            if (isSymlink(f)) {
                java.nio.file.Files.deleteIfExists(f.toPath());
                return true;
            }
            java.nio.file.Files.walkFileTree(f.toPath(), new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                    try {
                        file.toFile().setWritable(true);
                        java.nio.file.Files.deleteIfExists(file);
                    } catch (java.io.IOException e) {
                        ok[0] = false;
                        android.util.Log.w("Installer", "delete failed: " + file + ": " + e);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(java.nio.file.Path file,
                        java.io.IOException e) {
                    ok[0] = false;
                    android.util.Log.w("Installer", "visit failed: " + file + ": " + e);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path dir,
                        java.io.IOException e) throws java.io.IOException {
                    try {
                        dir.toFile().setWritable(true);
                        dir.toFile().setExecutable(true);
                        java.nio.file.Files.deleteIfExists(dir);
                    } catch (java.io.IOException de) {
                        ok[0] = false;
                        android.util.Log.w("Installer", "rmdir failed: " + dir + ": " + de);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (java.io.IOException e) {
            ok[0] = false;
            android.util.Log.w("Installer", "delete tree failed: " + f + ": " + e);
        }
        return ok[0] && !f.exists();
    }
}