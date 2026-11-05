package app.aomarchy.ui;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The ambient light of the console: the backdrop picture contributes one colour, and every glass panel picks it up as a
 * soft glow (a tint inside the card and a coloured shadow under it). This is what makes the glass feel like it is lit by
 * the picture behind it instead of floating on a fixed grey.
 *
 * <p>Panels register themselves when they are created; the wallpaper dispatches the sampled colour once it has decoded
 * the picture, which can happen after the console has already been built.
 */
public final class GlassTint {
    /** Fallback when there is no picture: the brand indigo, at the same low intensity as a sampled glow. */
    public static final int DEFAULT = 0xFF7C8CFF;

    private static final List<WeakReference<View>> PANELS = new ArrayList<>();
    private static int color = DEFAULT;

    private GlassTint() {
    }

    /** The colour every glass panel is currently glowing with. */
    public static int color() {
        return color;
    }

    /** Registers a panel and immediately applies the current colour (usually the default until the picture decodes). */
    public static void register(View panel) {
        if (panel == null)
            return;
        apply(panel, color);
        PANELS.add(new WeakReference<>(panel));
    }

    /** Called by the wallpaper once the backdrop has been decoded; lights every registered panel with {@code rgb}. */
    public static void dispatch(int rgb) {
        int next = normalize(rgb);
        color = next;
        for (Iterator<WeakReference<View>> it = PANELS.iterator(); it.hasNext(); ) {
            View panel = it.next().get();
            if (panel == null) {
                it.remove();
                continue;
            }
            apply(panel, next);
        }
    }

    /** Forces a repaint of every panel with a new colour, without waiting for a wallpaper (used when going back to flat). */
    public static void reset() {
        dispatch(DEFAULT);
    }

    private static void apply(View panel, int rgb) {
        Drawable background = panel.getBackground();
        if (background instanceof CardBackground)
            ((CardBackground) background).setTint(rgb);
        if (Build.VERSION.SDK_INT >= 28)
            try {
                // The elevation shadow carries the colour: a soft glow under the card that matches the picture behind it.
                panel.setOutlineSpotShadowColor(rgb);
                panel.setOutlineAmbientShadowColor(rgb);
            } catch (RuntimeException ignored) {
                // outline not supported for this view: the tint alone is enough
            }
    }

    /** Keeps a sampled colour readable as a glow: clamps to a useful saturation/brightness band. */
    private static int normalize(int rgb) {
        if (rgb == 0)
            return DEFAULT;
        float[] hsv = new float[3];
        Color.colorToHSV(rgb | 0xFF000000, hsv);
        hsv[1] = Math.min(0.72f, Math.max(0.30f, hsv[1])); // grey skies should not glow grey
        hsv[2] = Math.min(0.95f, Math.max(0.55f, hsv[2])); // and dark pictures should not glow black
        return Color.HSVToColor(hsv);
    }
}
