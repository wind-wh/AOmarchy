package app.aomarchy.data;

import android.content.Context;
import android.os.StatFs;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import app.aomarchy.SessionService;
import app.aomarchy.TarExtractor;
import app.aomarchy.TarWriter;
import app.aomarchy.i18n.T;

/**
 * System backups: the whole rootfs as one tar.gz in the app's external files dir, restorable in place, exportable
 * through the document picker. Restoring uses the same replace-by-rename dance as the installer, so a failure leaves
 * the current system usable.
 */
public final class Backup {
    private static final String TAG = "AOmarchyBackup";
    private static final String SUFFIX = ".tar.gz";
    /** Written by {@link #create} while the archive is being produced; never a complete backup. */
    private static final String PARTIAL_SUFFIX = ".tar.gz.partial";

    public interface Callback {
        void progress(int permille, String text);

        void done(String message);

        void failed(String message);
    }

    private static volatile boolean running;
    private static volatile boolean cancelled;

    private Backup() {
    }

    public static File dir(Context ctx) {
        File d = new File(ctx.getExternalFilesDir(null), "backups");
        d.mkdirs();
        return d;
    }

    public static boolean running() {
        return running;
    }

    public static void cancel() {
        if (running)
            cancelled = true;
    }

    public static List<File> list(Context ctx) {
        List<File> out = new ArrayList<>();
        File[] files = dir(ctx).listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".tar.gz"))
                    out.add(f);
            }
        }
        out.sort((a, b) -> b.getName().compareTo(a.getName()));
        return out;
    }

    /** Number of complete backups (aborted .partial files are not counted). */
    public static int count(Context ctx) {
        return list(ctx).size();
    }

    /** The most recently written complete backup, or null when there is none. */
    public static File newest(Context ctx) {
        File newest = null;
        for (File f : list(ctx)) {
            if (newest == null || f.lastModified() > newest.lastModified())
                newest = f;
        }
        return newest;
    }

    /** Disk usage of the backups folder: complete archives plus leftover .partial files. */
    public static long totalBytes(Context ctx) {
        File[] files = files(ctx);
        if (files == null)
            return 0;
        long total = 0;
        for (File f : files) {
            if (isBackup(f) || isPartial(f))
                total += f.length();
        }
        return total;
    }

    /**
     * Retention: keep the newest {@code keep} complete backups (by lastModified) and delete the older ones, then
     * delete .tar.gz.partial files left behind by aborted backups. {@code keep <= 0} keeps every complete backup and
     * only cleans the partial leftovers.
     *
     * <p>Does nothing while a backup is running, so the .partial file being written is never touched. File deletions
     * are best effort (a failed delete is logged, not thrown); returns the number of files actually deleted.
     */
    public static int prune(Context ctx, int keep) {
        if (running) {
            android.util.Log.w(TAG, "prune skipped: a backup is running");
            return 0;
        }
        File[] files = files(ctx);
        if (files == null)
            return 0;

        int deleted = 0;
        List<File> complete = new ArrayList<>();
        for (File f : files) {
            if (isPartial(f)) {
                if (f.delete()) {
                    deleted++;
                } else {
                    android.util.Log.w(TAG, "could not delete leftover partial " + f);
                }
            } else if (isBackup(f)) {
                complete.add(f);
            }
        }
        if (keep > 0 && complete.size() > keep) {
            complete.sort((a, b) -> {
                int byTime = Long.compare(b.lastModified(), a.lastModified());
                return byTime != 0 ? byTime : b.getName().compareTo(a.getName());
            });
            for (int i = keep; i < complete.size(); i++) {
                File old = complete.get(i);
                if (old.delete()) {
                    deleted++;
                } else {
                    android.util.Log.w(TAG, "could not delete old backup " + old);
                }
            }
        }
        return deleted;
    }

    private static boolean isBackup(File f) {
        return f.isFile() && f.getName().endsWith(SUFFIX);
    }

    private static boolean isPartial(File f) {
        return f.isFile() && f.getName().endsWith(PARTIAL_SUFFIX);
    }

    /** The backups folder contents; null (and logged) when it cannot be listed. */
    private static File[] files(Context ctx) {
        File dir = dir(ctx);
        File[] files = dir.listFiles();
        if (files == null) {
            android.util.Log.w(TAG, "cannot list " + dir);
            return null;
        }
        return files;
    }

    public static String name(long millis) {
        return "backup-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(millis)) + ".tar.gz";
    }

    /** yyyymmdd-HHmmss from a backup file name, for display. */
    public static String stamp(File backup) {
        String n = backup.getName();
        if (n.startsWith("backup-") && n.length() >= "backup-yyyyMMdd-HHmmss".length()) {
            String s = n.substring(7, 7 + 15);
            if (s.length() == 15)
                return s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8)
                        + " " + s.substring(9, 11) + ":" + s.substring(11, 13) + ":" + s.substring(13, 15);
        }
        return n;
    }

    public static void create(Context ctx, Callback cb) {
        if (running) {
            cb.failed(T.t("toast.busy"));
            return;
        }
        running = true;
        cancelled = false;
        new Thread(() -> {
            File tmp = new File(dir(ctx), "backup-" + System.currentTimeMillis() + ".tar.gz.partial");
            try {
                File rootfs = SessionService.rootfs(ctx);
                if (!SessionService.rootfsReady(ctx))
                    throw new IOException(T.t("backup.no_system"));
                long total = TarWriter.totalBytes(rootfs);
                if (total <= 0)
                    throw new IOException(T.t("backup.empty_system"));
                long available = available(dir(ctx));
                if (available < total / 2)
                    throw new IOException(T.t("backup.not_enough_space",
                            Installer.humanSize(total / 2), Installer.humanSize(available)));
                cb.progress(0, T.t("system.preparing_backup"));
                long[] shown = {-1};
                try (FileOutputStream fos = new FileOutputStream(tmp);
                        BufferedOutputStream bos = new BufferedOutputStream(fos, 1 << 20);
                        GZIPOutputStream gz = new GZIPOutputStream(bos, 1 << 20)) {
                    TarWriter.writeTree(rootfs, gz, (path, done, tot) -> {
                        if (cancelled)
                            return false;
                        int permille = (int) Math.min(1000, done * 1000 / Math.max(1, tot));
                        int bucket = permille / 25; // report at most 40 times a second-ish
                        if (bucket != shown[0]) {
                            shown[0] = bucket;
                            cb.progress(permille, T.t("backup.progress", permille / 10,
                                    Installer.humanSize(done), Installer.humanSize(tot)));
                        }
                        return true;
                    });
                }
                if (cancelled) {
                    tmp.delete();
                    cb.failed(T.t("backup.cancelled"));
                    return;
                }
                File out = new File(dir(ctx), name(System.currentTimeMillis()));
                if (!tmp.renameTo(out)) {
                    tmp.delete();
                    throw new IOException(T.t("backup.cannot_save"));
                }
                cb.progress(1000, T.t("backup.done"));
                cb.done(T.t("backup.created", out.getName(), Installer.humanSize(out.length())));
            } catch (TarWriter.Cancelled e) {
                tmp.delete();
                cb.failed(T.t("backup.cancelled"));
            } catch (IOException e) {
                android.util.Log.e("AOmarchyBackup", "backup failed", e);
                tmp.delete();
                cb.failed(e.getMessage() == null ? T.t("backup.failed") : e.getMessage());
            } catch (Exception e) {
                tmp.delete();
                cb.failed(String.valueOf(e));
            } finally {
                running = false;
                cancelled = false;
            }
        }, "backup").start();
    }

    public static void restore(Context ctx, File archive, Callback cb) {
        if (running) {
            cb.failed(T.t("toast.busy"));
            return;
        }
        running = true;
        cancelled = false;
        new Thread(() -> {
            File rootfs = SessionService.rootfs(ctx);
            File partial = new File(rootfs.getPath() + ".restore");
            try {
                if (!archive.isFile())
                    throw new IOException(T.t("backup.missing_archive"));
                long estimated = Math.max(archive.length() * 3, 1L << 30);
                long available = available(rootfs.getParentFile());
                if (available < estimated)
                    throw new IOException(T.t("backup.restore_not_enough_space",
                            Installer.humanSize(estimated), Installer.humanSize(available)));
                Installer.deleteTree(partial);
                cb.progress(0, T.t("system.unpacking_backup"));
                try (InputStream in = new GZIPInputStream(new FileInputStream(archive), 1 << 20)) {
                    TarExtractor.extract(in, partial, name -> {
                        // the extractor has no byte progress; the bar stays at 0 until the swap
                    });
                }
                if (cancelled) {
                    Installer.deleteTree(partial);
                    cb.failed(T.t("backup.cancelled"));
                    return;
                }
                if (!new File(partial, "opt/lotfix/arch-glibc/ld-linux-aarch64.so.1").exists()) {
                    Installer.deleteTree(partial);
                    throw new IOException(T.t("backup.not_aomarchy_image"));
                }
                cb.progress(500, T.t("backup.swapping"));
                File old = new File(rootfs.getPath() + ".old");
                Installer.deleteTree(old);
                boolean movedOld = rootfs.renameTo(old);
                if (!movedOld && !Installer.deleteTree(rootfs))
                    throw new IOException(T.t("backup.cannot_delete_current"));
                if (!partial.renameTo(rootfs)) {
                    if (movedOld)
                        old.renameTo(rootfs);
                    throw new IOException(T.t("backup.cannot_move"));
                }
                cb.progress(1000, T.t("backup.restored"));
                cb.done(T.t("backup.restored_message", stamp(archive)));
                Installer.deleteTree(old); // best effort, the restore already succeeded
            } catch (IOException e) {
                android.util.Log.e("AOmarchyBackup", "restore failed", e);
                Installer.deleteTree(partial);
                cb.failed(e.getMessage() == null ? T.t("backup.restore_failed") : e.getMessage());
            } catch (Exception e) {
                android.util.Log.e("AOmarchyBackup", "restore failed", e);
                Installer.deleteTree(partial);
                cb.failed(String.valueOf(e));
            } finally {
                running = false;
                cancelled = false;
            }
        }, "restore").start();
    }

    private static long available(File dir) {
        try {
            StatFs stat = new StatFs(dir.getAbsolutePath());
            return stat.getAvailableBytes();
        } catch (RuntimeException e) {
            return Long.MAX_VALUE;
        }
    }
}
