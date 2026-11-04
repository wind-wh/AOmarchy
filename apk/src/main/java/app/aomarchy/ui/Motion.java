package app.aomarchy.ui;

import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import java.util.Locale;

/**
 * The motion vocabulary of the console, borrowed from the Kinetik UI set and re-implemented for plain Android views:
 * springs instead of linear tweens, numbers that roll, cards that give under the finger, sheets that follow a drag.
 */
public final class Motion {
    public static final android.view.animation.Interpolator SPRING = new OvershootInterpolator(1.35f);
    public static final android.view.animation.Interpolator OUT = new DecelerateInterpolator(1.6f);

    private Motion() {
    }

    /** Animates a TextView from its current number to a new one ("62%" or "4.7 GB" style: prefix/suffix kept). */
    public static void number(final TextView view, final int from, final int to, final String suffix) {
        ValueAnimator a = ValueAnimator.ofInt(from, to);
        a.setDuration(420);
        a.setInterpolator(OUT);
        a.addUpdateListener(v -> view.setText(String.format(Locale.US, "%d%s", (int) v.getAnimatedValue(), suffix)));
        a.start();
    }

    /** The card feel: a soft squash on press, spring back on release. */
    public static void pressable(final View view) {
        view.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.985f).scaleY(0.985f).setDuration(90).setInterpolator(OUT).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(SPRING).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** Fade + slight rise, for content that appears after a load. */
    public static void appear(View v, long delayMs) {
        v.setAlpha(0f);
        v.setTranslationY(Ui.dp(v.getContext(), 8));
        v.animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(260).setInterpolator(OUT).start();
    }
}
