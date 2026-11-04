package app.aomarchy.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/** The progress bar of the console: it springs to each amount and a light band sweeps across while it works. */
public final class SpringBar extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float shown;
    private float target;
    private boolean active;
    private ValueAnimator anim;

    public SpringBar(Context c) {
        super(c);
        track.setColor(0xFF262C3A);
        bar.setColor(Ui.ACCENT);
        sheen.setStyle(Paint.Style.FILL);
        setMinimumHeight(Ui.dp(c, 10));
    }

    /** @param permille 0..1000 (what the installers report). */
    public void setProgress(int permille) {
        target = Math.max(0, Math.min(1f, permille / 1000f));
        if (anim != null)
            anim.cancel();
        anim = ValueAnimator.ofFloat(shown, target);
        anim.setDuration(520);
        anim.setInterpolator(Motion.SPRING);
        anim.addUpdateListener(a -> {
            shown = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    /** Sweep the light band while something is running. */
    public void setRunning(boolean running) {
        active = running;
        if (running)
            postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float r = h / 2f;
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, track);
        float fillW = Math.max(h, w * shown);
        rect.set(0, 0, fillW, h);
        canvas.drawRoundRect(rect, r, r, bar);
        if (active && fillW > h) {
            float phase = (SystemClock.uptimeMillis() % 1600) / 1600f;
            float x = -h + (fillW + 2 * h) * phase;
            sheen.setShader(new LinearGradient(x, 0, x + h * 2f, 0,
                    new int[] {0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF},
                    new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new RectF(Math.max(0, x), 0, Math.min(fillW, x + h * 2f), h), r, r, sheen);
            postInvalidateOnAnimation();
        }
    }
}
