package app.aomarchy.ui;

import android.app.Activity;
import android.app.WallpaperManager;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * The console's background: the device wallpaper, blurred and dimmed under the brand scrim, so the console keeps its
 * fixed dark look while still feeling like part of the home screen. Falls back to the flat brand background when the
 * wallpaper cannot be read (some launchers/ROMs refuse it).
 */
public final class Wallpaper {
    /** Brand background at 88% — enough to keep white-on-dark text readable over any photo. */
    private static final int SCRIM = 0xE60A0C10;

    private Wallpaper() {
    }

    /**
     * Adds the wallpaper (and its scrim) behind everything already in {@code host}. Call after the console's views are
     * built; the two layers are inserted at the bottom of the stack and never intercept touches.
     */
    public static void behind(Activity activity, FrameLayout host) {
        if (activity == null || host == null)
            return;
        try {
            Drawable src = WallpaperManager.getInstance(activity).getDrawable();
            if (src == null)
                return;
            ImageView image = new ImageView(activity);
            image.setImageDrawable(src);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (Build.VERSION.SDK_INT >= 31) {
                // A real blur instead of a scaled bitmap: the photo becomes texture, not a readable picture.
                image.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        24f, 24f, android.graphics.Shader.TileMode.CLAMP));
            }
            View scrim = new View(activity);
            scrim.setBackground(new ColorDrawable(SCRIM));
            scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            host.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            host.addView(scrim, 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable ignored) {
            // no wallpaper: the flat brand background stays
        }
    }
}
