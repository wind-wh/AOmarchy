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
 * The card surface plus the "glint" that follows a finger: a radial highlight drawn inside the rounded rectangle,
 * fading out on release. Also gives the card a small squash on press.
 */
public final class CardBackground extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private final float radiusPx;
    private final int baseColor;
    private final int strokeColor;
    private final float strokeWidth;
    private float hotX = -1, hotY = -1;
    private float hotAlpha;
    private ValueAnimator fade;

    public CardBackground(Context c, int baseColor, int strokeColor, float strokeDp, float radiusDp) {
        this.baseColor = baseColor;
        this.strokeColor = strokeColor;
        this.strokeWidth = Ui.dp(c, strokeDp);
        this.radiusPx = Ui.dp(c, radiusDp);
        fill.setStyle(Paint.Style.FILL);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(this.strokeWidth);
        border.setColor(strokeColor);
        glint.setStyle(Paint.Style.FILL);
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
        if (hotAlpha > 0 && hotX >= 0) {
            float radius = Math.max(r.width(), r.height()) * 0.55f;
            glint.setShader(new RadialGradient(hotX, hotY, radius,
                    new int[] {0x40FFFFFF, 0x14FFFFFF, 0x00FFFFFF},
                    new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            glint.setAlpha((int) (hotAlpha * 255));
            canvas.drawCircle(hotX, hotY, radius, glint);
        }
        // A faint top edge keeps cards readable on the very dark background.
        LinearGradient edge = new LinearGradient(r.left, r.top, r.left, r.top + strokeWidth * 3,
                0x44FFFFFF, 0x00000000, Shader.TileMode.CLAMP);
        glint.setShader(edge);
        glint.setAlpha(255);
        canvas.drawRect(r.left, r.top, r.right, r.top + strokeWidth * 3, glint);
        canvas.restore();
        canvas.drawRoundRect(r, radiusPx, radiusPx, border);
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        border.setAlpha(alpha);
        glint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
        border.setColorFilter(colorFilter);
        glint.setColorFilter(colorFilter);
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
}
