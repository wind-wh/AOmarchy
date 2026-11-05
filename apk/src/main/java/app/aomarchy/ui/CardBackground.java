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
    private static final int SHEEN_START = 0x24FFFFFF;
    private static final int SHEEN_END = 0x00FFFFFF;
    /** 15% white 1dp rim inset from the outer edge: the "thickness" of the glass edge. */
    private static final int INNER_RIM = 0x33FFFFFF;
    /** The sheen is gone by this fraction of the diagonal (0.55 = 55%). */
    private static final float SHEEN_EXTENT = 0.55f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private final RectF rimRect = new RectF();
    private final float radiusPx;
    private final float rimPx;
    private final int baseColor;
    private final int strokeColor;
    private final float strokeWidth;
    private float hotX = -1, hotY = -1;
    private float hotAlpha;
    /** Ambient colour sampled from the backdrop; drives the soft coloured sheen inside the glass. */
    private int tint = GlassTint.DEFAULT;
    private ValueAnimator fade;

    public CardBackground(Context c, int baseColor, int strokeColor, float strokeDp, float radiusDp) {
        this.baseColor = baseColor;
        this.strokeColor = strokeColor;
        this.strokeWidth = Ui.dp(c, strokeDp);
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
        int start = (tint & 0x00FFFFFF) | 0x3D000000;
        int end = tint & 0x00FFFFFF;
        glint.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, start, end, Shader.TileMode.CLAMP));
        glint.setAlpha(255);
        canvas.drawRect(r, glint);
    }
}
