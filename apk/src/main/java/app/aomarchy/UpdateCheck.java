package app.aomarchy;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * "检查更新": asks the GitHub Releases API for the latest release and compares its tag with this build's versionName.
 *
 * <p>Everything runs off the UI thread; both callbacks are delivered on the main thread. The class carries no
 * user-facing copy: the UI decides how to phrase "you are on 2.6.0, 2.9.1 is out" (T keys live there).
 *
 * <p>Version comparison ({@link #isNewer(String, String)}): a leading {@code v} is ignored, versions are split on
 * {@code .} and compared segment by segment as numbers ({@code 2.9} vs {@code 2.10}: 10 > 9), missing segments count as
 * 0 ({@code 2.6} == {@code 2.6.0}) and a non-numeric suffix is cut at its first non-digit ({@code 2.9.1-beta} → 2.9.1).
 * GitHub's {@code /releases/latest} already excludes drafts and prereleases.
 *
 * <p>The last successful result is cached in the app preferences ({@code update_last_check}, {@code update_latest},
 * {@code update_latest_url}) so {@link #maybeAutoCheck(Context, long, Callback)} can answer background checks from the
 * cache instead of hitting the API on every launch; a failed check never updates the timestamp, so it is retried.
 */
public final class UpdateCheck {
    /** Main-thread callbacks. */
    public interface Callback {
        /** {@code version} is the tag without its leading "v" (e.g. "2.9.1"); {@code url} is the release page. */
        void onResult(boolean newer, String version, String url);

        /** Network, HTTP or JSON failure; {@code message} is technical and safe to show after a local prefix. */
        void onError(String message);
    }

    private static final String LATEST_URL =
            "https://api.github.com/repos/wind-wh/AOmarchy/releases/latest";
    private static final String ACCEPT = "application/vnd.github+json";
    private static final String USER_AGENT = "AOmarchy";
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_RESPONSE_CHARS = 1 << 20; // a release JSON is a few KB: guard against a wild response
    private static final String TAG = "UpdateCheck";

    // Cache of the last successful check (same preferences file as the console's settings).
    private static final String PREF_LAST_CHECK = "update_last_check";
    private static final String PREF_LATEST = "update_latest";
    private static final String PREF_LATEST_URL = "update_latest_url";

    private UpdateCheck() {
    }

    /**
     * Fetches the newest release in the background and reports it on the main thread. Never throws at the caller:
     * every failure becomes {@link Callback#onError(String)}.
     */
    public static void check(Context ctx, final Callback cb) {
        if (ctx == null || cb == null)
            return;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                String current = currentVersion(app);
                String json = fetch(LATEST_URL);
                JSONObject release = new JSONObject(json);
                String version = normalize(release.optString("tag_name", ""));
                String url = release.optString("html_url", "").trim();
                if (version.isEmpty())
                    throw new IOException("the release has no tag_name");
                final boolean newer = isNewer(current, version);
                rememberResult(app, version, url); // a manual check also feeds the auto-check cache
                post(() -> cb.onResult(newer, version, url));
            } catch (Exception e) {
                final String message = describe(e);
                post(() -> cb.onError(message));
            }
        }, "update-check").start();
    }

    /**
     * Background-friendly check with caching and throttling (for the console's overview suggestion):
     * <ul>
     *   <li>If a successful check is younger than {@code minIntervalMs}, the cached release is reported immediately on
     *       the main thread — no network. {@code minIntervalMs <= 0} disables the cache and always checks.</li>
     *   <li>Otherwise it runs the normal network check: a success stores the cache (and moves the timestamp), a failure
     *       only calls {@link Callback#onError(String)} and leaves the timestamp alone, so the next call retries.</li>
     * </ul>
     */
    public static void maybeAutoCheck(Context ctx, long minIntervalMs, final Callback cb) {
        if (ctx == null || cb == null)
            return;
        final Context app = ctx.getApplicationContext();
        final long last = lastCheckMillis(app);
        final String cachedVersion = normalize(prefs(app).getString(PREF_LATEST, ""));
        final String cachedUrl = prefs(app).getString(PREF_LATEST_URL, "");
        final long elapsed = System.currentTimeMillis() - last;
        boolean fresh = hasCache(cachedVersion) && last > 0 && minIntervalMs > 0
                && elapsed >= 0 && elapsed < minIntervalMs;
        if (fresh) {
            final boolean newer = isNewer(currentVersion(app), cachedVersion);
            post(() -> cb.onResult(newer, cachedVersion, cachedUrl == null ? "" : cachedUrl));
            return;
        }
        // Stale or no cache: the normal check writes the cache on success (and leaves it untouched on failure).
        check(app, cb);
    }

    /** When the last successful check happened (0 when there has never been one). */
    public static long lastCheckMillis(Context ctx) {
        if (ctx == null)
            return 0L;
        return prefs(ctx).getLong(PREF_LAST_CHECK, 0L);
    }

    /**
     * Stores the last successful release (manual checks do this themselves; exposed for callers that get the data from
     * elsewhere). {@code tag} may carry a leading "v": it is normalized before being cached.
     */
    public static void rememberResult(Context ctx, String tag, String url) {
        if (ctx == null)
            return;
        String version = normalize(tag);
        if (version.isEmpty())
            return; // never cache an empty release version: it would make maybeAutoCheck answer nonsense
        prefs(ctx).edit()
                .putLong(PREF_LAST_CHECK, System.currentTimeMillis())
                .putString(PREF_LATEST, version)
                .putString(PREF_LATEST_URL, url == null ? "" : url.trim())
                .apply();
    }

    /** The installed versionName (without a leading "v"), or "" when the package manager cannot report it. */
    public static String currentVersion(Context ctx) {
        if (ctx == null)
            return "";
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return normalize(info.versionName);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * True when {@code remote} is strictly newer than {@code current}. Both may carry a leading "v"; a missing/empty
     * remote version is never "newer".
     */
    public static boolean isNewer(String current, String remote) {
        int[] local = segments(current);
        int[] latest = segments(remote);
        if (latest.length == 0)
            return false;
        int length = Math.max(local.length, latest.length);
        for (int i = 0; i < length; i++) {
            int left = i < local.length ? local[i] : 0;
            int right = i < latest.length ? latest[i] : 0;
            if (right != left)
                return right > left;
        }
        return false; // equal versions are not an update
    }

    // ---- internals -------------------------------------------------------------------------------------------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
    }

    private static boolean hasCache(String version) {
        return version != null && !version.isEmpty();
    }

    /** "v2.9.1" → "2.9.1"; null/blank → "". */
    private static String normalize(String version) {
        if (version == null)
            return "";
        String text = version.trim();
        if (text.startsWith("v") || text.startsWith("V"))
            text = text.substring(1).trim();
        return text;
    }

    /** "2.9.1" → [2, 9, 1]; unexpected pieces become 0, huge numbers saturate at Integer.MAX_VALUE. */
    private static int[] segments(String version) {
        String text = normalize(version);
        if (text.isEmpty())
            return new int[0];
        String[] parts = text.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            int end = 0;
            while (end < part.length() && Character.isDigit(part.charAt(end)))
                end++;
            if (end == 0) {
                out[i] = 0;
                continue;
            }
            try {
                out[i] = Integer.parseInt(part.substring(0, end));
            } catch (NumberFormatException e) {
                out[i] = Integer.MAX_VALUE; // numerically absurd version: treat it as the newest possible segment
            }
        }
        return out;
    }

    private static String fetch(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", ACCEPT);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK)
                throw new IOException("HTTP " + code);
            try (InputStream in = connection.getInputStream()) {
                return readAll(in);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                out.append(buffer, 0, read);
                if (out.length() > MAX_RESPONSE_CHARS)
                    throw new IOException("response too large");
            }
        }
        return out.toString();
    }

    /** All UI work happens on the main thread; a callback that throws only gets logged, never takes the app down. */
    private static void post(Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                runnable.run();
            } catch (RuntimeException e) {
                android.util.Log.w(TAG, "update callback failed", e);
            }
        });
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        if (message != null && !message.trim().isEmpty())
            return message;
        return e.getClass().getSimpleName();
    }
}
