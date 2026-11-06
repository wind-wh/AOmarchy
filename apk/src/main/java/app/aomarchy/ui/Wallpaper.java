package app.aomarchy.ui;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;

import app.aomarchy.data.LinuxWallpaper;
import app.aomarchy.i18n.T;

/**
 * The console's backdrop, in four levels of reliability.
 *
 * <ol>
 *   <li>A custom picture the user picked in Settings → 控制台背景 (prefs {@code console_bg_uri}, a content URI whose
 *       read grant is persisted by SAF).</li>
 *   <li>The device wallpaper. {@link WallpaperManager#getDrawable()} is tried first; several ROMs (Android 13+ and
 *       MIUI in particular) refuse it, so {@link WallpaperManager#getWallpaperFile(int)} is tried as a second chance.</li>
 *   <li>The Linux desktop's own wallpaper inside the rootfs ({@link LinuxWallpaper}: Omarchy's active theme background,
 *       the path the Hyprland/Hyprpaper/swww config points at, any theme background, then a bounded directory scan).
 *       It reuses the same ImageView/blur/scrim as the Android wallpaper, so nothing about the rendering changes.</li>
 *   <li>The brand gradient (not a flat fill): {@code #0A0C10 → #14171F} with a diagonal {@code #1F7C8CFF} highlight,
 *       so the console still has depth when no picture can be read.</li>
 * </ol>
 *
 * <p>The picture is drawn CENTER_CROP, blurred on API 31+ (20dp), and covered by a light 35% brand scrim, so the
 * console keeps its fixed dark look and white-on-dark text stays readable over any photo. Decoding runs off the UI
 * thread (downsampled to {@link #MAX_IMAGE_SIDE} px, memory friendly) and the layers never intercept touches.
 *
 * <p>Call {@link #behind(Activity, FrameLayout)} (or {@link #apply(Activity, FrameLayout)}) after the console's views
 * are built; calling it again on the same host replaces the previous layers, so the host can refresh the backdrop
 * after the user changes the setting without rebuilding the console.
 */
public final class Wallpaper {
    /**
     * Brand scrim at 35%: the backdrop is blurred (20dp) before this layer, so detail has already melted into colour.
     * The glass panels then add their own translucent fill on top; body text stays readable because the blur, not a
     * near-opaque scrim, is what removes the picture's high-frequency detail.
     */
    private static final int SCRIM = 0x590A0C10; // 35%: blurred backdrop stays visible as colour and light, not a black void
    /** Longest decoded side of a picture: enough for a tablet panel at 2x, small enough to stay cheap. */
    private static final int MAX_IMAGE_SIDE = 2000;
    /** Brand gradient endpoints (keep in sync with {@link Ui}). */
    private static final int GRADIENT_FROM = 0xFF0A0C10;
    private static final int GRADIENT_TO = 0xFF14171F;
    /** The diagonal accent wash drawn over the gradient. */
    private static final int GRADIENT_GLOW = 0x1F7C8CFF;

    /** Must match {@code app.aomarchy.Prefs.NAME}; that class is package-private, so the name is repeated here. */
    private static final String PREFS = "andromarchy_settings";
    private static final String KEY_MODE = "console_bg_mode";
    private static final String KEY_URI = "console_bg_uri";
    /** User-tunable backdrop blur radius in dp (0 = sharp .. 24 = heavy frosting). */
    private static final String KEY_BLUR = "console_bg_blur";
    /** Default blur when the preference is unset: a barely-there frosting, the backdrop stays essentially sharp. */
    private static final int DEFAULT_BLUR_DP = 4;

    /** The saved {@code console_bg_mode} values. */
    public static final String MODE_BUILTIN = "builtin"; // the shipped backdrop picture (default)
    public static final String MODE_LINUX = "linux"; // the Linux desktop wallpaper inside the rootfs
    public static final String MODE_CUSTOM = "custom";
    public static final String MODE_OFF = "off";
    /** Older builds stored this for "the desktop wallpaper"; it now means the built-in picture. */
    public static final String MODE_SYSTEM = MODE_BUILTIN;

    /** Marks the backdrop layers so a repeated apply can replace them instead of stacking another set. */
    private static final Object BACKDROP_TAG = new Object();

    /** The settings page asks whether the wallpaper is readable; probe once and reuse the answer for a short while. */
    private static final long AVAILABLE_TTL_MS = 30_000L;
    private static final Object AVAILABLE_LOCK = new Object();
    private static boolean wallpaperAvailable;
    private static long wallpaperAvailableCheckedAt;

    /** Generation of the latest apply; a stale loader must not overwrite {@link #lastSourceWasLinux}. */
    private static int backdropGeneration;
    /** True when the picture the latest apply put behind the console came from the Linux desktop, not Android. */
    private static volatile boolean lastSourceWasLinux;

    private Wallpaper() {
    }

    /**
     * Adds the backdrop behind everything already in {@code host}. Kept for the existing call site; {@link #apply} does
     * the work and can be called again to refresh.
     */
    public static void behind(Activity activity, FrameLayout host) {
        apply(activity, host);
    }

    /**
     * Replaces the backdrop of {@code host} (layers added by an earlier call are removed first, so this is safe to call
     * on every refresh). {@code MODE_OFF} leaves the host's own solid brand colour showing; the other modes draw the
     * brand gradient immediately and the picture as soon as it has been decoded.
     */
    public static void apply(Activity activity, FrameLayout host) {
        if (activity == null || host == null)
            return;
        removeBackdrop(host);
        final int generation = ++backdropGeneration;
        lastSourceWasLinux = false; // set again when this apply's picture actually lands
        GlassTint.reset(); // panels start from the brand colour; a decoded picture re-lights them below

        String mode = mode(activity);
        if (MODE_OFF.equals(mode))
            return; // 纯色: the host's own brand background is exactly what the user asked for

        View gradient = new View(activity);
        gradient.setBackground(brandGradient());
        decorate(gradient);
        host.addView(gradient, 0, fullscreen());

        ImageView picture = new ImageView(activity);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.setVisibility(View.GONE);
        picture.setAlpha(0f);
        decorate(picture);
        blur(activity, picture);
        host.addView(picture, 1, fullscreen());

        View scrim = new View(activity);
        scrim.setBackground(new ColorDrawable(SCRIM));
        scrim.setVisibility(View.GONE);
        scrim.setAlpha(0f);
        decorate(scrim);
        host.addView(scrim, 2, fullscreen());

        load(activity, picture, scrim, mode, generation);
    }

    /**
     * True when the picture behind the console (from the latest {@link #apply}) came from the Linux desktop's own
     * wallpaper inside the rootfs instead of from Android. The settings page uses it to word the "system wallpaper"
     * explanation: a ROM can refuse Android's wallpaper while the Linux side still has a usable background.
     *
     * <p>The flag is process-wide; the context argument only mirrors the other accessors.
     */
    public static boolean lastSourceWasLinux(Context context) {
        return lastSourceWasLinux;
    }

    /** True when a custom image is configured (whatever the current mode: the URI is remembered when switching away). */
    public static boolean hasCustom(Activity activity) {
        return customUri(activity) != null;
    }

    /**
     * Whether the system wallpaper can actually be read on this device. Several ROMs (Android 13+ / MIUI) refuse
     * {@link WallpaperManager#getDrawable()} and may also refuse {@link WallpaperManager#getWallpaperFile(int)}; then
     * the console shows the brand gradient instead and the settings page should say so.
     *
     * <p>Either probe succeeding counts as available. The answer is cached for {@value #AVAILABLE_TTL_MS} ms so
     * opening the settings sheet repeatedly does not hit the wallpaper service every time; the probe itself does no
     * bitmap decode beyond what {@code getDrawable()} already does.
     */
    public static boolean systemWallpaperAvailable(Activity activity) {
        if (activity == null)
            return false;
        long now = SystemClock.elapsedRealtime();
        synchronized (AVAILABLE_LOCK) {
            if (wallpaperAvailableCheckedAt != 0 && now - wallpaperAvailableCheckedAt < AVAILABLE_TTL_MS)
                return wallpaperAvailable;
        }
        boolean available = probeSystemWallpaper(activity);
        synchronized (AVAILABLE_LOCK) {
            wallpaperAvailable = available;
            wallpaperAvailableCheckedAt = SystemClock.elapsedRealtime();
        }
        return available;
    }

    /** The saved backdrop blur radius in dp (0..24); defaults to {@link #DEFAULT_BLUR_DP}. */
    public static int blurDp(Activity activity) {
        if (activity == null)
            return DEFAULT_BLUR_DP;
        return prefs(activity).getInt(KEY_BLUR, DEFAULT_BLUR_DP);
    }

    /** Stores the backdrop blur radius in dp, clamped to 0..24. */
    public static void setBlur(Activity activity, int dp) {
        if (activity == null)
            return;
        int v = Math.max(0, Math.min(24, dp));
        prefs(activity).edit().putInt(KEY_BLUR, v).apply();
    }

    /** The stored {@code console_bg_mode}: {@link #MODE_BUILTIN} when unset or unrecognised. */
    public static String mode(Activity activity) {
        if (activity == null)
            return MODE_BUILTIN;
        String value = prefs(activity).getString(KEY_MODE, MODE_BUILTIN);
        if (MODE_CUSTOM.equals(value) || MODE_OFF.equals(value) || MODE_LINUX.equals(value)
                || MODE_BUILTIN.equals(value))
            return value;
        return MODE_BUILTIN; // an old "system" value (or anything unknown) now means the shipped backdrop
    }

    /** Stores {@code console_bg_mode}; anything unknown means {@link #MODE_BUILTIN}. */
    public static void setMode(Activity activity, String mode) {
        if (activity == null)
            return;
        String value = MODE_CUSTOM.equals(mode) || MODE_OFF.equals(mode) || MODE_LINUX.equals(mode)
                ? mode : MODE_BUILTIN;
        prefs(activity).edit().putString(KEY_MODE, value).apply();
    }

    /** The user's custom picture, or null when none is stored. */
    public static Uri customUri(Activity activity) {
        if (activity == null)
            return null;
        String value = prefs(activity).getString(KEY_URI, "");
        if (value == null || value.trim().isEmpty())
            return null;
        try {
            return Uri.parse(value.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Remembers a custom picture. The caller (the settings picker) has already taken the persistable read grant; an
     * earlier picture's grant is released so switching images does not leak permissions.
     */
    public static void setCustom(Activity activity, Uri uri) {
        if (activity == null)
            return;
        if (uri == null) {
            clearCustom(activity);
            return;
        }
        Uri previous = customUri(activity);
        if (previous != null && !previous.equals(uri))
            release(activity, previous);
        prefs(activity).edit().putString(KEY_URI, uri.toString()).apply();
    }

    /** Forgets the custom picture and releases its persisted SAF read grant; the mode is left to the caller. */
    public static void clearCustom(Activity activity) {
        if (activity == null)
            return;
        Uri previous = customUri(activity);
        prefs(activity).edit().remove(KEY_URI).apply();
        if (previous != null)
            release(activity, previous);
    }

    // ---- loading -------------------------------------------------------------------------------------------------

    /**
     * Loads the best available picture off the UI thread: custom image first (in custom mode), then the Android
     * wallpaper, then the Linux desktop's wallpaper inside the rootfs, otherwise the gradient stays. A custom image that
     * cannot be read reports {@code bg.failed} and then still falls back, so a revoked permission never leaves a blank
     * background.
     */
    private static void load(Activity activity, ImageView picture, View scrim, String mode, int generation) {
        if (activity.isFinishing())
            return;
        Uri custom = MODE_CUSTOM.equals(mode) ? customUri(activity) : null;
        boolean allowSystem = !MODE_OFF.equals(mode);
        new Thread(() -> {
            Object source = null;
            boolean customFailed = false;
            boolean fromLinux = false;
            if (custom != null) {
                try {
                    source = decodeCustom(activity, custom);
                } catch (Throwable t) {
                    customFailed = true;
                }
            }
            if (source == null) {
                if (MODE_LINUX.equals(mode)) {
                    // Only the Linux desktop's own wallpaper: the console belongs to that desktop, and Android's
                    // wallpaper is both hard to read (13+/MIUI) and not what the user asked for.
                    source = linuxWallpaper(activity);
                    fromLinux = source != null;
                }
                if (source == null)
                    source = builtinImage(activity); // the shipped backdrop: always available, no permissions
                android.util.Log.i("ConsoleBg", "source=" + (fromLinux ? "linux" : source != null ? "builtin" : "none")
                        + " mode=" + mode);
            }

            Object loaded = source;
            boolean reportFailure = customFailed;
            boolean loadedFromLinux = fromLinux;
            int ambient = loaded instanceof Bitmap ? sampleAmbient((Bitmap) loaded) : 0;
            activity.runOnUiThread(() -> {
                if (picture.getParent() == null || activity.isFinishing() || activity.isDestroyed())
                    return; // the console was rebuilt or closed while decoding
                if (generation == backdropGeneration)
                    lastSourceWasLinux = loadedFromLinux;
                if (reportFailure)
                    Toasts.show(activity, T.t("bg.failed"));
                if (loaded == null)
                    return; // the brand gradient is the fallback and is already visible
                if (loaded instanceof Bitmap) {
                    picture.setImageBitmap((Bitmap) loaded);
                    if (ambient != 0)
                        GlassTint.dispatch(ambient); // the glass panels glow with the picture's own colour
                } else {
                    picture.setImageDrawable((Drawable) loaded);
                    GlassTint.reset();
                }
                picture.setVisibility(View.VISIBLE);
                scrim.setVisibility(View.VISIBLE);
                picture.animate().alpha(1f).setDuration(260).start();
                scrim.animate().alpha(1f).setDuration(260).start();
            });
        }, "console-background").start();
    }

    /** The backdrop shipped inside the APK, decoded with the same downsampling as any other picture. */
    private static Object builtinImage(Activity activity) {
        try {
            // Looked up by name: the harness R used by the compile gate does not know resources added since it was
            // generated, and the shipped APK resolves it exactly the same way.
            int id = activity.getResources().getIdentifier("console_default", "drawable", activity.getPackageName());
            if (id == 0)
                return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(activity.getResources(), id, bounds);
            int sample = 1;
            while (bounds.outWidth > 0 && Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE)
                sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeResource(activity.getResources(), id, options);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The one colour the picture contributes to the glass: a saturation-weighted average of a tiny downscale, so a photo
     * glows with its dominant hue instead of grey. Runs on the loading thread, never the UI thread.
     */
    private static int sampleAmbient(Bitmap bitmap) {
        try {
            Bitmap small = Bitmap.createScaledBitmap(bitmap, 24, 14, true);
            double r = 0, g = 0, b = 0, weight = 0;
            for (int y = 0; y < small.getHeight(); y++)
                for (int x = 0; x < small.getWidth(); x++) {
                    int px = small.getPixel(x, y);
                    int pr = (px >> 16) & 0xFF, pg = (px >> 8) & 0xFF, pb = px & 0xFF;
                    int max = Math.max(pr, Math.max(pg, pb));
                    int min = Math.min(pr, Math.min(pg, pb));
                    double saturation = max == 0 ? 0 : (max - min) / (double) max;
                    double w = 0.2 + saturation * 1.6; // colourful pixels glow, grey ones stay quiet
                    r += pr * w;
                    g += pg * w;
                    b += pb * w;
                    weight += w;
                }
            if (weight <= 0)
                return 0;
            return 0xFF000000 | ((int) (r / weight) << 16) | ((int) (g / weight) << 8) | (int) (b / weight);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** The Linux desktop's wallpaper from inside the rootfs, decoded like any other picture on the same ImageView. */
    private static Object linuxWallpaper(Activity activity) {
        try {
            File file = LinuxWallpaper.find(activity);
            if (file == null)
                return null;
            return decodeFile(file);
        } catch (Throwable ignored) {
            return null; // an unreadable theme file simply leaves the gradient
        }
    }

    /** Decodes a picture on disk, downsampled so neither side exceeds {@link #MAX_IMAGE_SIDE}. */
    private static Bitmap decodeFile(File file) throws IOException {
        String path = file.getAbsolutePath();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            throw new IOException("not a bitmap: " + path);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
        if (bitmap == null)
            throw new IOException("could not decode " + path);
        return bitmap;
    }

    /** The custom picture, downsampled so neither side exceeds {@link #MAX_IMAGE_SIDE}. */
    private static Bitmap decodeCustom(Activity activity, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null)
                throw new IOException("no stream for " + uri);
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            throw new IOException("not a bitmap: " + uri);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        Bitmap bitmap;
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null)
                throw new IOException("no stream for " + uri);
            bitmap = BitmapFactory.decodeStream(in, null, options);
        }
        if (bitmap == null)
            throw new IOException("could not decode " + uri);
        return bitmap;
    }

    /** Availability probe with the same two public paths as {@link #systemWallpaper}, keeping no bitmap. */
    private static boolean probeSystemWallpaper(Activity activity) {
        WallpaperManager manager;
        try {
            manager = WallpaperManager.getInstance(activity);
        } catch (Throwable ignored) {
            return false;
        }
        try {
            if (manager.getDrawable() != null)
                return true;
        } catch (Throwable ignored) {
            // refused on Android 13+/MIUI: try the wallpaper file below
        }
        ParcelFileDescriptor descriptor = null;
        try {
            descriptor = manager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM);
            return descriptor != null;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (IOException ignored) {
                    // nothing to do at this point
                }
            }
        }
    }

    /** The device wallpaper: public Drawable first, then the wallpaper file (some ROMs only block the first path). */
    private static Object systemWallpaper(Activity activity) {
        try {
            Drawable drawable = WallpaperManager.getInstance(activity).getDrawable();
            if (drawable != null)
                return drawable;
        } catch (Throwable ignored) {
            // refused on Android 13+/MIUI: try the wallpaper file below
        }
        ParcelFileDescriptor descriptor = null;
        try {
            descriptor = WallpaperManager.getInstance(activity).getWallpaperFile(WallpaperManager.FLAG_SYSTEM);
            if (descriptor == null)
                return null;
            FileDescriptor file = descriptor.getFileDescriptor();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(file, null, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
                return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
            // The bounds pass may leave the descriptor at the end of the image: rewind before decoding for real.
            try {
                android.system.Os.lseek(file, 0, android.system.OsConstants.SEEK_SET);
            } catch (Throwable ignored) {
                // not seekable (a pipe on some ROMs): the decode below simply reports failure
            }
            return BitmapFactory.decodeFileDescriptor(file, null, options);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (IOException ignored) {
                    // nothing to do at this point
                }
            }
        }
    }

    /** Power-of-two downsample so neither side of the decoded bitmap exceeds {@link #MAX_IMAGE_SIDE}. */
    private static int sampleSize(int width, int height) {
        int sample = 1;
        while (width / sample > MAX_IMAGE_SIDE || height / sample > MAX_IMAGE_SIDE)
            sample <<= 1;
        return sample;
    }

    // ---- layers --------------------------------------------------------------------------------------------------

    /**
     * The brand gradient fallback: a diagonal {@code #0A0C10 → #14171F} base with the same {@code #1F7C8CFF} accent
     * wash on top, so the backdrop has depth instead of reading as one dead flat colour.
     */
    private static Drawable brandGradient() {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {GRADIENT_FROM, GRADIENT_TO});
        GradientDrawable glow = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {GRADIENT_GLOW, 0x007C8CFF});
        return new LayerDrawable(new Drawable[] {base, glow});
    }

    /** Real frosted glass: blur the backdrop so the panels read as glass, not as tinted windows over a sharp photo. */
    private static void blur(Activity activity, ImageView picture) {
        if (Build.VERSION.SDK_INT >= 31) {
            float radius = Ui.dp(activity, blurDp(activity)); // user-tunable 0..24dp, default a barely-there frosting
            picture.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }
    }

    /** Background layers are decoration: no touches, no focus, no TalkBack stops. */
    private static void decorate(View view) {
        view.setTag(BACKDROP_TAG);
        view.setClickable(false);
        view.setFocusable(false);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private static FrameLayout.LayoutParams fullscreen() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /** Removes the layers of a previous apply, releasing an old picture bitmap before the host rebuilds. */
    private static void removeBackdrop(FrameLayout host) {
        for (int i = host.getChildCount() - 1; i >= 0; i--) {
            View child = host.getChildAt(i);
            if (child.getTag() != BACKDROP_TAG)
                continue;
            if (child instanceof ImageView)
                ((ImageView) child).setImageDrawable(null);
            host.removeViewAt(i);
        }
    }

    // ---- prefs ---------------------------------------------------------------------------------------------------

    private static SharedPreferences prefs(Activity activity) {
        return activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    /** Drops a persisted SAF read grant; providers that do not support persistence simply ignore it. */
    private static void release(Activity activity, Uri uri) {
        try {
            activity.getContentResolver().releasePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
            // not persisted, already released, or a provider that does not implement it
        }
    }
}
