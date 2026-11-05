package app.aomarchy.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayDeque;

import app.aomarchy.SessionService;

/**
 * Disk usage of the data AOmarchy owns, plus the two clean-ups the System page offers.
 *
 * <ul>
 *   <li><b>rootfs</b> — the Arch tree under {@link SessionService#rootfs(Context)}. A 5 GB tree takes a few seconds to
 *       walk, so the measurement always runs on a background thread; the aborted install tree lives next to it as
 *       {@code rootfs.partial} and is therefore never part of this number.</li>
 *   <li><b>backups</b> — {@link Backup#totalBytes(Context)} / {@link Backup#count(Context)}, the existing backup API.</li>
 *   <li><b>logs</b> — {@code *.log} directly under {@link SessionService#runDir(Context)} (one per session process).</li>
 *   <li><b>cache</b> — the app cache and the external cache when the latter exists ({@code null} on some devices).</li>
 * </ul>
 *
 * <p>Traversal safety: symbolic links are never followed — they are skipped while measuring and only the link itself is
 * deleted during a clean-up — and {@link #clearCache(Context)} only ever removes children of the two cache roots, never
 * the roots themselves and never anything they point at. Unreadable or vanishing entries are skipped, so a partial
 * measurement is preferred over a crash.
 */
public final class StorageStats {
    private static final String LOG_SUFFIX = ".log";
    /** The background thread's name, so a trace/log shows what was busy. */
    private static final String THREAD_NAME = "storage-stats";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Told on the main thread, exactly once per {@link #computeAsync(Context, Callback)} call. */
    public interface Callback {
        void onResult(Snapshot stats);

        void onError(String message);
    }

    /** One measurement pass; all sizes are bytes. */
    public static final class Snapshot {
        public long rootfsBytes;
        public long backupBytes;
        public int backupCount;
        public long logBytes;
        public int logCount;
        public long cacheBytes;
    }

    private StorageStats() {
    }

    /**
     * Measures everything on a background thread ({@value #THREAD_NAME}) and posts the result to the main thread. IO
     * errors are skipped so the numbers are best effort; anything unexpected is reported through
     * {@link Callback#onError(String)} instead of crashing the app.
     */
    public static void computeAsync(Context context, Callback callback) {
        if (context == null || callback == null)
            return;
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        Thread worker = new Thread(() -> {
            try {
                final Snapshot snapshot = measure(app);
                MAIN.post(() -> callback.onResult(snapshot));
            } catch (Throwable t) {
                final String message = describe(t);
                MAIN.post(() -> callback.onError(message));
            }
        }, THREAD_NAME);
        worker.start();
    }

    /**
     * Deletes the session logs ({@code run/*.log}) and returns how many files were removed. Symlinks are not followed;
     * a log that is already gone or cannot be deleted is simply not counted.
     */
    public static int clearLogs(Context context) {
        if (context == null)
            return 0;
        File[] logs;
        try {
            logs = SessionService.runDir(context).listFiles((dir, name) -> name.endsWith(LOG_SUFFIX));
        } catch (RuntimeException e) {
            return 0;
        }
        if (logs == null)
            return 0;
        int removed = 0;
        for (File log : logs) {
            try {
                if (Files.isSymbolicLink(log.toPath()) || !log.isFile())
                    continue; // only real log files; a link is never followed (or removed)
                if (log.delete())
                    removed++;
            } catch (RuntimeException ignored) {
                // vanished or unreadable while deleting: move on
            }
        }
        return removed;
    }

    /**
     * Deletes everything inside the app cache directories (the external one included when it exists) and returns the
     * bytes freed. The cache directories themselves are kept. Only children of those two roots are touched, and links
     * are removed without following them; a file that is in use is left behind.
     *
     * <p>The caller should run this off the UI thread when the cache can be large: the deletion itself is synchronous.
     */
    public static long clearCache(Context context) {
        if (context == null)
            return 0;
        long freed = 0;
        try {
            freed += clearDirectory(context.getCacheDir());
        } catch (RuntimeException ignored) {
            // cache root vanished/permission denied: nothing to free there
        }
        try {
            freed += clearDirectory(context.getExternalCacheDir());
        } catch (RuntimeException ignored) {
            // no external cache on this device
        }
        return freed;
    }

    // ---- measuring -----------------------------------------------------------------------------------------------

    private static Snapshot measure(Context context) {
        Snapshot snapshot = new Snapshot();
        try {
            snapshot.rootfsBytes = directorySize(SessionService.rootfs(context));
        } catch (RuntimeException ignored) {
            snapshot.rootfsBytes = 0;
        }
        try {
            snapshot.backupBytes = Backup.totalBytes(context);
            snapshot.backupCount = Backup.count(context);
        } catch (RuntimeException ignored) {
            snapshot.backupBytes = 0;
            snapshot.backupCount = 0;
        }
        try {
            File[] logs = SessionService.runDir(context).listFiles((dir, name) -> name.endsWith(LOG_SUFFIX));
            if (logs != null) {
                for (File log : logs) {
                    try {
                        if (Files.isSymbolicLink(log.toPath()) || !log.isFile())
                            continue;
                        snapshot.logBytes += log.length();
                        snapshot.logCount++;
                    } catch (RuntimeException ignored) {
                        // vanished/unreadable log: skip it
                    }
                }
            }
        } catch (RuntimeException ignored) {
            snapshot.logBytes = 0;
            snapshot.logCount = 0;
        }
        try {
            snapshot.cacheBytes = directorySize(context.getCacheDir())
                    + directorySize(context.getExternalCacheDir());
        } catch (RuntimeException ignored) {
            snapshot.cacheBytes = 0;
        }
        return snapshot;
    }

    /**
     * Total size of the regular files under {@code root}, iteratively (the rootfs is deep) and without following
     * symbolic links. A missing or unreadable directory contributes 0.
     */
    private static long directorySize(File root) {
        if (root == null || !isRealDirectory(root))
            return 0;
        long total = 0;
        ArrayDeque<File> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            File dir = pending.removeLast();
            File[] children = dir.listFiles();
            if (children == null)
                continue; // unreadable: count what we already have and keep walking the rest
            for (File child : children) {
                try {
                    if (Files.isSymbolicLink(child.toPath()))
                        continue; // never follow links (the Arch tree is full of them)
                    if (child.isDirectory())
                        pending.add(child);
                    else if (child.isFile())
                        total += child.length();
                } catch (RuntimeException ignored) {
                    // permission / vanished while walking: skip this entry
                }
            }
        }
        return total;
    }

    /** {@code isDirectory()} follows links; make sure the root itself is a real directory before walking it. */
    private static boolean isRealDirectory(File dir) {
        try {
            if (Files.isSymbolicLink(dir.toPath()))
                return false;
            return dir.isDirectory();
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- cleaning ------------------------------------------------------------------------------------------------

    /** Removes the children of one cache root, keeping the root itself; returns the bytes actually freed. */
    private static long clearDirectory(File dir) {
        if (dir == null || !isRealDirectory(dir))
            return 0;
        File[] children = dir.listFiles();
        if (children == null)
            return 0;
        long freed = 0;
        for (File child : children) {
            try {
                if (Files.isSymbolicLink(child.toPath())) {
                    child.delete(); // remove the link, never what it points at
                } else if (child.isDirectory()) {
                    freed += deleteTree(child);
                } else if (child.isFile()) {
                    long length = child.length();
                    if (child.delete())
                        freed += length;
                }
            } catch (RuntimeException ignored) {
                // in-use or vanished cache entry: leave it for the next clean-up
            }
        }
        return freed;
    }

    /** Deletes one directory tree below a cache root; links are removed, never followed. */
    private static long deleteTree(File dir) {
        long freed = 0;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                try {
                    if (Files.isSymbolicLink(child.toPath())) {
                        child.delete();
                    } else if (child.isDirectory()) {
                        freed += deleteTree(child);
                    } else if (child.isFile()) {
                        long length = child.length();
                        if (child.delete())
                            freed += length;
                    }
                } catch (RuntimeException ignored) {
                    // keep deleting the rest of the tree
                }
            }
        }
        dir.delete(); // best effort: a directory still in use stays
        return freed;
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.trim().isEmpty() ? t.toString() : message;
    }
}
