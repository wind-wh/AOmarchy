package app.aomarchy;

import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a GNU tar stream of a directory tree: what {@link TarExtractor} reads back. Regular files, directories and
 * symlinks, with their permission bits, long names via GNU {@code L}/{@code K} records, 512-byte blocks.
 * Used for the system backups (the 4.7 GB rootfs is about 1.6 GB compressed).
 */
public final class TarWriter {
    public interface Progress {
        /** @return false to cancel. */
        boolean onEntry(String path, long done, long total);
    }

    private static final int BLOCK = 512;
    private static final byte[] ZEROS = new byte[BLOCK * 2];

    private TarWriter() {
    }

    public static void writeTree(File root, OutputStream target, Progress progress) throws IOException {
        long total = totalBytes(root);
        BufferedOutputStream out = new BufferedOutputStream(target, 1 << 20);
        write(out, root, "", total, new long[] {0}, progress);
        out.write(ZEROS); // end of archive: two zero blocks
        out.flush();
    }

    /** Sum of regular file sizes: the uncompressed progress denominator (symlinks and dirs add a block each, ignored). */
    public static long totalBytes(File root) {
        try {
            StructStat st = Os.lstat(root.getPath());
            if (OsConstants.S_ISLNK(st.st_mode))
                return 0;
            if (OsConstants.S_ISREG(st.st_mode))
                return st.st_size;
            long total = 0;
            File[] children = root.listFiles();
            if (children != null) {
                for (File c : children)
                    total += totalBytes(c);
            }
            return total;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void write(OutputStream out, File file, String prefix, long total, long[] done, Progress progress)
            throws IOException {
        String name = prefix.isEmpty() ? file.getName() : prefix + "/" + file.getName();
        StructStat st;
        try {
            st = Os.lstat(file.getPath());
        } catch (Exception e) {
            return; // vanished while walking
        }
        int mode = (int) (st.st_mode & 07777);
        long mtime = st.st_mtime;

        if (OsConstants.S_ISLNK(st.st_mode)) {
            String target;
            try {
                target = Os.readlink(file.getPath());
            } catch (Exception e) {
                return;
            }
            if (!emit(out, name, mode, 0, mtime, (byte) '2', target, done, total, progress))
                throw new Cancelled();
            return;
        }
        if (OsConstants.S_ISDIR(st.st_mode)) {
            if (!prefix.isEmpty()) { // the archive root itself is not an entry: its children are
                if (!emit(out, name + "/", mode, 0, mtime, (byte) '5', null, done, total, progress))
                    throw new Cancelled();
            }
            File[] children = file.listFiles();
            if (children != null) {
                List<File> sorted = new ArrayList<>();
                for (File c : children)
                    sorted.add(c);
                sorted.sort((a, b) -> a.getName().compareTo(b.getName()));
                for (File c : sorted)
                    write(out, c, prefix.isEmpty() ? "" : name, total, done, progress);
            }
            return;
        }
        if (!OsConstants.S_ISREG(st.st_mode))
            return; // sockets, fifos: runtime objects, not part of a backup

        if (!emit(out, name, mode, st.st_size, mtime, (byte) '0', null, done, total, progress))
            throw new Cancelled();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            long left = st.st_size;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0)
                    throw new IOException("short read: " + file);
                out.write(buf, 0, n);
                left -= n;
                done[0] += n;
            }
        }
        pad(out, st.st_size);
    }

    private static boolean emit(OutputStream out, String name, int mode, long size, long mtime, byte type,
            String link, long[] done, long total, Progress progress) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 100) {
            writeBlock(out, header("././@LongLink", 0, nameBytes.length + 1, 0, (byte) 'L', null));
            out.write(nameBytes);
            out.write(0);
            pad(out, nameBytes.length + 1);
        }
        if (link != null) {
            byte[] linkBytes = link.getBytes(StandardCharsets.UTF_8);
            if (linkBytes.length > 100) {
                writeBlock(out, header("././@LongLink", 0, linkBytes.length + 1, 0, (byte) 'K', null));
                out.write(linkBytes);
                out.write(0);
                pad(out, linkBytes.length + 1);
            }
        }
        writeBlock(out, header(name, mode, size, mtime, type, link));
        if (progress != null && !progress.onEntry(name, done[0], Math.max(1, total)))
            return false;
        return true;
    }

    private static void writeBlock(OutputStream out, byte[] block) throws IOException {
        out.write(block);
    }

    private static byte[] header(String name, int mode, long size, long mtime, byte type, String link) {
        byte[] h = new byte[BLOCK];
        putString(h, 0, 100, name);
        putOctal(h, 100, 8, mode);
        putOctal(h, 108, 8, 0); // uid: the app is the only user
        putOctal(h, 116, 8, 0); // gid
        putOctal(h, 124, 12, size);
        putOctal(h, 136, 12, mtime);
        for (int i = 148; i < 156; i++)
            h[i] = ' '; // checksum placeholder
        h[156] = type;
        if (link != null)
            putString(h, 157, 100, link);
        byte[] magic = "ustar".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, h, 257, 5);
        h[262] = 0;
        h[263] = '0';
        h[264] = '0';
        int sum = 0;
        for (byte b : h)
            sum += b & 0xff;
        putOctal(h, 148, 7, sum);
        h[154] = 0;
        h[155] = ' ';
        return h;
    }

    private static void putString(byte[] h, int off, int len, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, h, off, Math.min(len - 1, bytes.length));
    }

    private static void putOctal(byte[] h, int off, int len, long value) {
        String s = Long.toOctalString(Math.max(0, value));
        int digits = len - 1;
        while (s.length() < digits)
            s = "0" + s;
        if (s.length() > digits)
            s = s.substring(s.length() - digits);
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, h, off, bytes.length);
        h[off + len - 1] = 0;
    }

    private static void pad(OutputStream out, long size) throws IOException {
        int rest = (int) (size % BLOCK);
        if (rest != 0) {
            byte[] pad = new byte[BLOCK - rest];
            out.write(pad);
        }
    }

    static final class Cancelled extends IOException {
        Cancelled() {
            super("cancelled");
        }
    }
}
