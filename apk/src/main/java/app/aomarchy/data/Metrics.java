package app.aomarchy.data;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.StatFs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Locale;

import app.aomarchy.SessionService;

/**
 * The numbers the console shows. Android hides most of what the Linux side could tell us, so this is computed from
 * /proc (world-readable) plus the Android APIs the app already uses for the session. Cheap enough for the UI thread:
 * a few small file reads every two seconds while the overview is visible.
 */
public final class Metrics {
    public int cpuPercent = -1;
    public long memUsed = -1;
    public long memTotal = -1;
    public long diskUsed = -1;
    public long diskTotal = -1;
    public int batteryPercent = -1;
    public boolean charging;
    public String network = "—";
    public String device = "";

    public String cpuScope = "session";
    public int processCount = -1;

    private long lastCpuTotal = -1;
    private long lastSessionTicks = -1;
    private long lastSessionAt;
    private long lastCpuIdle = -1;

    public int memPercent() {
        return memTotal > 0 ? (int) Math.round(100.0 * memUsed / memTotal) : -1;
    }

    public int diskPercent() {
        return diskTotal > 0 ? (int) Math.round(100.0 * diskUsed / diskTotal) : -1;
    }

    /** Reads everything; call from the UI thread (the reads are tiny), twice a second at most. */
    public void read(Context ctx) {
        readCpu();
        readMem(ctx);
        readDisk(ctx);
        readBattery(ctx);
        readNetwork(ctx);
        device = Build.MANUFACTURER + " " + Build.MODEL;
    }

    private void readCpu() {
        // Android hides /proc/stat from apps (SELinux: app domain cannot read it), so the device-wide number is
        // only available where the ROM allows it. Fall back to the CPU of the session itself: every Linux process
        // runs under this app's uid, so their /proc/<pid>/stat entries are readable.
        if (readDeviceCpu())
            return;
        readSessionCpu();
    }

    /** Device-wide usage from /proc/stat; false when Android does not let the app read it. */
    private boolean readDeviceCpu() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/stat"));
            String line = r.readLine();
            if (line == null || !line.startsWith("cpu "))
                return false;
            String[] f = line.trim().split("\\s+");
            long total = 0, idle = 0;
            for (int i = 1; i < f.length && i <= 8; i++) {
                long v = Long.parseLong(f[i]);
                total += v;
                if (i == 4 || i == 5) // idle + iowait
                    idle += v;
            }
            if (lastCpuTotal > 0 && total > lastCpuTotal) {
                long dt = total - lastCpuTotal;
                long di = idle - lastCpuIdle;
                cpuPercent = (int) Math.max(0, Math.min(100, Math.round(100.0 * (dt - di) / dt)));
            }
            lastCpuTotal = total;
            lastCpuIdle = idle;
            cpuScope = "device";
            return true;
        } catch (IOException | SecurityException | NumberFormatException ignored) {
            return false;
        } finally {
            close(r);
        }
    }

    /** Sum of this app's processes (the whole Linux session) as a share of the device CPU capacity. */
    private void readSessionCpu() {
        long ticks = 0;
        int procs = 0;
        File[] entries = new File("/proc").listFiles();
        if (entries != null) {
            for (File dir : entries) {
                String name = dir.getName();
                if (name.isEmpty() || !Character.isDigit(name.charAt(0)))
                    continue;
                BufferedReader r = null;
                try {
                    r = new BufferedReader(new FileReader(new File(dir, "stat")));
                    String line = r.readLine();
                    if (line == null)
                        continue;
                    int close = line.lastIndexOf(')');
                    if (close < 0)
                        continue;
                    String[] f = line.substring(close + 2).split(" ");
                    if (f.length < 13)
                        continue;
                    ticks += Long.parseLong(f[11]) + Long.parseLong(f[12]); // utime, stime
                    procs++;
                } catch (IOException | SecurityException | NumberFormatException ignored) {
                    // a process can exit while we scan
                } finally {
                    close(r);
                }
            }
        }
        long now = System.currentTimeMillis();
        processCount = procs;
        if (lastSessionTicks > 0 && now > lastSessionAt) {
            long dt = now - lastSessionAt;
            long dticks = ticks - lastSessionTicks;
            long capacity = dt * 100 /* CLK_TCK */ * Runtime.getRuntime().availableProcessors() / 1000;
            if (capacity > 0)
                cpuPercent = (int) Math.max(0, Math.min(100, Math.round(100.0 * dticks / capacity)));
        }
        lastSessionTicks = ticks;
        lastSessionAt = now;
        cpuScope = "session";
    }
    private void readMem(Context ctx) {
        long total = -1, available = -1;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/meminfo"));
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("MemTotal:"))
                    total = parseKb(line);
                else if (line.startsWith("MemAvailable:"))
                    available = parseKb(line);
                if (total > 0 && available >= 0)
                    break;
            }
        } catch (IOException ignored) {
            // fall back to the Android API below
        } finally {
            close(r);
        }
        if (total > 0) {
            memTotal = total;
            memUsed = available >= 0 ? total - available : -1;
        } else {
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            ((android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
            memTotal = mi.totalMem;
            memUsed = mi.totalMem - mi.availMem;
        }
    }

    private void readDisk(Context ctx) {
        File rootfs = SessionService.rootfs(ctx);
        File dir = rootfs.exists() ? rootfs : ctx.getFilesDir();
        try {
            StatFs fs = new StatFs(dir.getAbsolutePath());
            long block = fs.getBlockSizeLong();
            long total = fs.getBlockCountLong() * block;
            long avail = fs.getAvailableBlocksLong() * block;
            diskTotal = total;
            diskUsed = total - avail;
        } catch (RuntimeException ignored) {
            diskTotal = -1;
            diskUsed = -1;
        }
    }

    private void readBattery(Context ctx) {
        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        batteryPercent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        Intent sticky = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) {
            int status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
        }
    }

    private void readNetwork(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        if (caps == null) {
            network = "无连接";
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            network = "Wi-Fi";
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            network = "移动网络";
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            network = "有线";
        } else {
            network = "已连接";
        }
    }

    private static long parseKb(String line) {
        int i = line.indexOf(':');
        if (i < 0)
            return -1;
        String v = line.substring(i + 1).trim().split("\\s+")[0];
        try {
            return Long.parseLong(v) * 1024L;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void close(BufferedReader r) {
        if (r != null) {
            try {
                r.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    public static String gb(long bytes) {
        if (bytes < 0)
            return "—";
        return String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024 / 1024);
    }

    public static String gpu() {
        if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != null && !Build.SOC_MODEL.equals(Build.UNKNOWN))
            return Build.SOC_MODEL;
        return Build.HARDWARE;
    }
}
