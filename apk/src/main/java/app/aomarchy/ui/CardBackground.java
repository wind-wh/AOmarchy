package app.aomarchy.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * The glass panel surface: a translucent fill, a diagonal sheen from the top-left corner, a 1dp bright inner rim, the
 * finger "glint" and the press squash. Drawn inside the rounded-rectangle clip, so it works for any panel size — cards
 * (22dp radius) and the square side rail (0dp) alike.
 *
 * <p>Glass layers, bottom to top: fill → diagonal sheen ({@value #SHEEN_START} at the top-left, transparent by 55% of
 * the diagonal) → press glint → 1dp inner rim ({@value #INNER_RIM}) → outer hairline stroke. The sheen and the rim are
 * what make the panel read as glass instead of a flat translucent box; both stay inside the clip, so the outer edge
 * never gets a bright halo.
 */
public final class CardBackground extends Drawable {
    /** 10% white at the top-left corner, fading to nothing by 55% of the diagonal. */
    private static final int SHEEN_START = 0x14FFFFFF;
    private static final int SHEEN_END = 0x00FFFFFF;
    /** 15% white 1dp rim inset from the outer edge: the "thickness" of the glass edge. */
    private static final int INNER_RIM = 0x1AFFFFFF;
    /** Top-inner highlight: light comes from above, so the bevel catches it. */
    private static final int BEVEL_LIGHT = 0x2EFFFFFF;
    /** Bottom-inner shadow: the thickness of the slab, so the panel reads as a solid piece of glass. */
    private static final int BEVEL_SHADOW = 0x4A000000;
    /** The sheen is gone by this fraction of the diagonal (0.55 = 55%). */
    private static final float SHEEN_EXTENT = 0.55f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The outline is a gradient (bright top-left → dim bottom-right), rebuilt only when the bounds change. */
    private LinearGradient borderShader;
    private final RectF borderBounds = new RectF();
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
        // The card's translucent glass fill. This was never assigned before, so Paint's default opaque black won and
        // every "glass" card painted as a solid black box — the main reason 2.9 read as dark and showed no backdrop.
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
        canvas.drawRect(r, fill);
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
        drawGradientBorder(canvas, r);
    }

    /** One hairline, but lit: bright at the top-left, fading along the diagonal, so the edge looks like real glass. */
    private void drawGradientBorder(Canvas canvas, RectF r) {
        if (borderShader == null || !borderBounds.equals(r)) {
            borderBounds.set(r);
            borderShader = new LinearGradient(r.left, r.top, r.right, r.bottom,
                    new int[] {0xF2FFFFFF, 0x8CFFFFFF, 0x2EFFFFFF},
                    new float[] {0f, 0.42f, 1f}, Shader.TileMode.CLAMP);
            border.setShader(borderShader);
        }
        canvas.drawRoundRect(r, radiusPx, radiusPx, border);
    }

    /**
     * The 3D part of the glass: a bright band under the top edge and a dark band above the bottom edge, both inside the
     * clip. Together with the brighter rim this makes the panel read as a thick slab lit from above, not a flat tint.
     */
    private void drawBevel(Canvas canvas, RectF r) {
        if (r.height() <= 0)
            return;
        // Reference look: one even luminous edge, no top band. A soft 3dp ring just inside the stroke gives the outline
        // a gentle fall-off so it glows instead of reading as a drawn hairline.
        float corner = Math.max(0f, radiusPx - rimPx);
        ring.setStyle(Paint.Style.STROKE);
        float[] widths = {3f, 6f};
        int[] alphas = {0x1EFFFFFF, 0x0CFFFFFF};
        for (int i = 0; i < widths.length; i++) {
            ring.setStrokeWidth(widths[i] * density);
            ring.setColor(alphas[i]);
            rimRect.set(r);
            rimRect.inset(rimPx + widths[i] * density * 0.5f, rimPx + widths[i] * density * 0.5f);
            canvas.drawRoundRect(rimRect, Math.max(0f, corner - widths[i] * density * 0.5f),
                    Math.max(0f, corner - widths[i] * density * 0.5f), ring);
        }
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
