package app.aomarchy.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.SweepGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * Real glass, not a dark card: a translucent slab lit by the picture behind it.
 *
 * Layers, bottom to top, all clipped to the rounded rect:
 *   1. thickness fill — a faint vertical gradient, barely lighter at the top (a whisper of thickness, not a band);
 *   2. one soft edge ring — a single subtle ring just inside the outline so the edge diffuses instead of reading
 *      as a hard drawn line (no stacked white glows);
 *   3. diagonal sheen — the top-left corner highlight, the tell-tale reflection of real glass;
 *   4. ambient wash — the backdrop's own colour, so the glass is lit by the picture behind it;
 *   5. finger glint and press squash;
 *   6. a 1dp inner rim, and the prismatic edge: a 1dp chromatic fringe running the ENTIRE outline as an angular
 *      (sweep) gradient — indigo, blue, teal, signal green and amber cycle around the whole perimeter, so every
 *      side carries prismatic dispersion, not just the corners.
 *
 * Used for cards (24dp radius), the side rail (22dp) and every small pill/cell via {@link Ui#surface}.
 */
public final class CardBackground extends Drawable {
    /** Diagonal sheen: 12% white at the top-left corner, gone by 55% of the diagonal. */
    private static final int SHEEN_START = 0x1AFFFFFF;
    private static final int SHEEN_END = 0x00FFFFFF;
    private static final float SHEEN_EXTENT = 0.55f;
    /** Faint white 1dp rim inset from the outer edge: the inside of the glass edge, kept low so the slab feels thin. */
    private static final int INNER_RIM = 0x1CFFFFFF;
    /**
     * The prismatic edge: a 1dp chromatic fringe that runs the ENTIRE outline as an angular (sweep) gradient around
     * the panel centre. Indigo, blue, teal, signal green and amber cycle around the whole perimeter, so every side
     * carries prismatic dispersion — not just the corners. Peaks at white, troughs at ~25% so it reads as a thin
     * dispersion line, not a rainbow band.
     */
    private static final int[] EDGE_COLORS = {
            0x70FFFFFF, // white
            0x5A7C8CFF, // indigo
            0x5040C0FF, // blue
            0x4848D8C8, // teal
            0x489CD462, // signal green
            0x48FFC66B, // amber
            0x70FFFFFF  // back to white
    };
    private static final float[] EDGE_STOPS = {0f, 0.16f, 0.33f, 0.50f, 0.66f, 0.83f, 1f};

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The prismatic fringe: an angular (sweep) gradient around the panel centre, rebuilt only when bounds change. */
    private Shader borderShader;
    private final RectF borderBounds = new RectF();
    /** The thickness fill is a vertical gradient, also rebuilt only when the bounds change. */
    private LinearGradient fillShader;
    private final RectF fillBounds = new RectF();
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private final RectF rimRect = new RectF();
    private final float radiusPx;
    private final float rimPx;
    private final int baseColor;
    private final int strokeColor;
    private final float strokeWidth;
    private final float density;
    private float hotX = -1, hotY = -1;
    private float hotAlpha;
    /** Ambient colour sampled from the backdrop; drives the soft coloured sheen inside the glass. */
    private int tint = GlassTint.DEFAULT;
    private ValueAnimator fade;

    public CardBackground(Context c, int baseColor, int strokeColor, float strokeDp, float radiusDp) {
        this.baseColor = baseColor;
        this.strokeColor = strokeColor;
        this.strokeWidth = Ui.dp(c, strokeDp);
        this.density = c.getResources().getDisplayMetrics().density;
        this.radiusPx = Ui.dp(c, radiusDp);
        this.rimPx = Math.max(1f, Ui.dp(c, 1f));
        // Assign the fill: a default opaque Paint would turn every "glass" panel into a solid black box.
        fill.setColor(baseColor);
        fill.setStyle(Paint.Style.FILL);
        sheen.setStyle(Paint.Style.FILL);
        glint.setStyle(Paint.Style.FILL);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(rimPx);
        rim.setColor(INNER_RIM);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(this.strokeWidth);
        border.setColor(strokeColor);
    }

    /** Adds the glint + squash behaviour to a card that uses this background. */
    public static void attach(final View card, final CardBackground bg) {
        card.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    bg.hotX = e.getX();
                    bg.hotY = e.getY();
                    bg.showGlint();
                    v.animate().scaleX(0.987f).scaleY(0.987f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_MOVE:
                    bg.hotX = e.getX();
                    bg.hotY = e.getY();
                    v.invalidate();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    bg.hideGlint();
                    v.animate().scaleX(1f).scaleY(1f).setDuration(280)
                            .setInterpolator(new OvershootInterpolator(1.4f)).start();
                    break;
                default:
                    break;
            }
            return false; // clicks still reach the card's own handlers
        });
    }

    private void showGlint() {
        if (fade != null)
            fade.cancel();
        fade = ValueAnimator.ofFloat(hotAlpha, 0.55f);
        fade.setDuration(160);
        fade.addUpdateListener(a -> {
            hotAlpha = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        fade.start();
    }

    private void hideGlint() {
        if (fade != null)
            fade.cancel();
        fade = ValueAnimator.ofFloat(hotAlpha, 0f);
        fade.setDuration(320);
        fade.addUpdateListener(a -> {
            hotAlpha = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        fade.start();
    }

    @Override
    public void draw(Canvas canvas) {
        RectF r = rect;
        r.set(getBounds());
        float inset = strokeWidth / 2f;
        r.inset(inset, inset);
        clip.reset();
        clip.addRoundRect(r, radiusPx, radiusPx, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        drawThickness(canvas, r);
        drawBevel(canvas, r);
        drawSheen(canvas, r);
        drawAmbient(canvas, r);
        if (hotAlpha > 0 && hotX >= 0) {
            float radius = Math.max(r.width(), r.height()) * 0.55f;
            glint.setShader(new RadialGradient(hotX, hotY, radius,
                    new int[] {0x40FFFFFF, 0x14FFFFFF, 0x00FFFFFF},
                    new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            glint.setAlpha((int) (hotAlpha * 255));
            canvas.drawCircle(hotX, hotY, radius, glint);
        }
        // 1dp bright rim, inset so it reads as the inside of the glass edge and never pokes outside the border.
        rimRect.set(r);
        rimRect.inset(rimPx, rimPx);
        float rimRadius = Math.max(0f, radiusPx - rimPx);
        canvas.drawRoundRect(rimRect, rimRadius, rimRadius, rim);
        canvas.restore();
        drawChromaticBorder(canvas, r);
    }

    /** The slab's thickness: a faint vertical gradient, barely lighter at the top — a whisper, not a visible band. */
    private void drawThickness(Canvas canvas, RectF r) {
        if (fillShader == null || !fillBounds.equals(r)) {
            fillBounds.set(r);
            fillShader = new LinearGradient(r.left, r.top, r.left, r.bottom,
                    lighten(baseColor, 0.025f), baseColor, Shader.TileMode.CLAMP);
            fill.setShader(fillShader);
        }
        canvas.drawRect(r, fill);
    }

    private static int lighten(int color, float amount) {
        int add = Math.round(255 * amount);
        int r = Math.min(255, ((color >> 16) & 0xFF) + add);
        int g = Math.min(255, ((color >> 8) & 0xFF) + add);
        int b = Math.min(255, (color & 0xFF) + add);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** A single soft ring just inside the outline: the edge diffuses outward as a faint glow, not a hard drawn line. */
    private void drawBevel(Canvas canvas, RectF r) {
        if (r.height() <= 0)
            return;
        float w = 6f * density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(w);
        ring.setColor(0x0CFFFFFF);
        rimRect.set(r);
        rimRect.inset(rimPx + w * 0.5f, rimPx + w * 0.5f);
        canvas.drawRoundRect(rimRect, Math.max(0f, radiusPx - rimPx - w * 0.5f),
                Math.max(0f, radiusPx - rimPx - w * 0.5f), ring);
    }

    /**
     * The prismatic edge: a 1dp chromatic fringe running the ENTIRE outline. An angular (sweep) gradient around the
     * panel centre cycles indigo → blue → teal → signal green → amber → white around the perimeter, so every side
     * carries prismatic dispersion — not just the corners. Drawn as the rounded-rect stroke, so it hugs the outline.
     */
    private void drawChromaticBorder(Canvas canvas, RectF r) {
        if (borderShader == null || !borderBounds.equals(r)) {
            borderBounds.set(r);
            borderShader = new SweepGradient(r.centerX(), r.centerY(), EDGE_COLORS, EDGE_STOPS);
            border.setShader(borderShader);
        }
        canvas.drawRoundRect(r, radiusPx, radiusPx, border);
    }

    /** Diagonal glass sheen: bright at the top-left corner, transparent by {@link #SHEEN_EXTENT} of the diagonal. */
    private void drawSheen(Canvas canvas, RectF r) {
        if (r.width() <= 0 || r.height() <= 0)
            return;
        float endX = r.left + r.width() * SHEEN_EXTENT;
        float endY = r.top + r.height() * SHEEN_EXTENT;
        sheen.setShader(new LinearGradient(r.left, r.top, endX, endY,
                new int[] {SHEEN_START, SHEEN_END}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(r, sheen);
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        sheen.setAlpha(alpha);
        glint.setAlpha(alpha);
        rim.setAlpha(alpha);
        border.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
        sheen.setColorFilter(colorFilter);
        glint.setColorFilter(colorFilter);
        rim.setColorFilter(colorFilter);
        border.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }

    public int baseColor() {
        return baseColor;
    }

    public int strokeColor() {
        return strokeColor;
    }

    /** Ambient colour from the backdrop (see {@link GlassTint}); the sheen and rim pick it up. */
    public void setTint(int rgb) {
        this.tint = rgb;
        invalidateSelf();
    }

    /** A soft wash of the backdrop colour, so the glass looks lit by the picture behind it. */
    private void drawAmbient(Canvas canvas, RectF r) {
        int start = (tint & 0x00FFFFFF) | 0x15000000;
        int end = tint & 0x00FFFFFF;
        glint.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, start, end, Shader.TileMode.CLAMP));
        glint.setAlpha(255);
        canvas.drawRect(r, glint);
    }
}
