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

/**
 * System backups: the whole rootfs as one tar.gz in the app's external files dir, restorable in place, exportable
 * through the document picker. Restoring uses the same replace-by-rename dance as the installer, so a failure leaves
 * the current system usable.
 */
public final class Backup {
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
            cb.failed("已有任务在进行");
            return;
        }
        running = true;
        cancelled = false;
        new Thread(() -> {
            File tmp = new File(dir(ctx), "backup-" + System.currentTimeMillis() + ".tar.gz.partial");
            try {
                File rootfs = SessionService.rootfs(ctx);
                if (!SessionService.rootfsReady(ctx))
                    throw new IOException("系统还没安装");
                long total = TarWriter.totalBytes(rootfs);
                if (total <= 0)
                    throw new IOException("系统目录是空的");
                long available = available(dir(ctx));
                if (available < total / 2)
                    throw new IOException("空间不足：备份约需要 " + Installer.humanSize(total / 2)
                            + "，当前可用 " + Installer.humanSize(available));
                cb.progress(0, "准备备份…");
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
                            cb.progress(permille, "备份 " + (permille / 10) + "% · "
                                    + Installer.humanSize(done) + " / " + Installer.humanSize(tot));
                        }
                        return true;
                    });
                }
                if (cancelled) {
                    tmp.delete();
                    cb.failed("已取消");
                    return;
                }
                File out = new File(dir(ctx), name(System.currentTimeMillis()));
                if (!tmp.renameTo(out)) {
                    tmp.delete();
                    throw new IOException("无法保存备份文件");
                }
                cb.progress(1000, "备份完成");
                cb.done("已创建 " + out.getName() + "（" + Installer.humanSize(out.length()) + "）");
            } catch (TarWriter.Cancelled e) {
                tmp.delete();
                cb.failed("已取消");
            } catch (IOException e) {
                android.util.Log.e("AOmarchyBackup", "backup failed", e);
                tmp.delete();
                cb.failed(e.getMessage() == null ? "备份失败" : e.getMessage());
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
            cb.failed("已有任务在进行");
            return;
        }
        running = true;
        cancelled = false;
        new Thread(() -> {
            File rootfs = SessionService.rootfs(ctx);
            File partial = new File(rootfs.getPath() + ".restore");
            try {
                if (!archive.isFile())
                    throw new IOException("备份文件不见了");
                long estimated = Math.max(archive.length() * 3, 1L << 30);
                long available = available(rootfs.getParentFile());
                if (available < estimated)
                    throw new IOException("空间不足：恢复约需要 " + Installer.humanSize(estimated)
                            + "，当前可用 " + Installer.humanSize(available));
                Installer.deleteTree(partial);
                cb.progress(0, "解包备份…");
                try (InputStream in = new GZIPInputStream(new FileInputStream(archive), 1 << 20)) {
                    TarExtractor.extract(in, partial, name -> {
                        // the extractor has no byte progress; the bar stays at 0 until the swap
                    });
                }
                if (cancelled) {
                    Installer.deleteTree(partial);
                    cb.failed("已取消");
                    return;
                }
                if (!new File(partial, "opt/lotfix/arch-glibc/ld-linux-aarch64.so.1").exists()) {
                    Installer.deleteTree(partial);
                    throw new IOException("这个备份不是 AOmarchy 的系统镜像");
                }
                cb.progress(500, "替换系统…");
                File old = new File(rootfs.getPath() + ".old");
                Installer.deleteTree(old);
                boolean movedOld = rootfs.renameTo(old);
                if (!movedOld && !Installer.deleteTree(rootfs))
                    throw new IOException("删不掉当前的系统（rootfs）");
                if (!partial.renameTo(rootfs)) {
                    if (movedOld)
                        old.renameTo(rootfs);
                    throw new IOException("无法把恢复的系统移动到目标位置");
                }
                cb.progress(1000, "已恢复");
                cb.done("系统已恢复到 " + stamp(archive) + " 的状态。进入桌面即用这份系统。");
                Installer.deleteTree(old); // best effort, the restore already succeeded
            } catch (IOException e) {
                android.util.Log.e("AOmarchyBackup", "restore failed", e);
                Installer.deleteTree(partial);
                cb.failed(e.getMessage() == null ? "恢复失败" : e.getMessage());
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
