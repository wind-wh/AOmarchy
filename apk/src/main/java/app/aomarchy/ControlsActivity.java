package app.aomarchy;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.aomarchy.i18n.T;
import app.aomarchy.ui.Ui;

/**
 * The controls as a half-screen sheet over the desktop: opened from the session notification (or anywhere via an
 * explicit intent). Dimmed background; tapping outside or 关闭 dismisses; the desktop keeps running underneath.
 */
public class ControlsActivity extends Activity {
    /** ui_lang also decides the resource Locale (see {@link T#wrap(Context)}); auto keeps the system Locale. */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(T.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        T.init(this); // the sheet's header follows the console's ui_lang
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xB3000000);
        root.setOnClickListener(v -> finish());

        LinearLayout sheet = Ui.column(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.SURFACE);
        float r = Ui.dp(this, 24);
        bg.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(bg);
        sheet.setOnClickListener(v -> {
        }); // swallow taps on the sheet

        LinearLayout head = Ui.row(this);
        head.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 4));
        head.addView(Ui.text(this, T.t("tab.controls"), 18, Ui.TEXT, true));
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView close = Ui.text(this, T.t("btn.close"), 14, Ui.ACCENT, false);
        close.setOnClickListener(v -> finish());
        head.addView(close);
        sheet.addView(head);

        // Morph sheet: drag the header down to dismiss, spring back if the drag is short (Kinetik's morph-sheet feel).
        final FrameLayout dim = root;
        final android.view.VelocityTracker tracker = android.view.VelocityTracker.obtain();
        final float[] state = new float[2]; // downY, startTranslation
        head.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    state[0] = e.getRawY();
                    state[1] = sheet.getTranslationY();
                    tracker.clear();
                    tracker.addMovement(e);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    tracker.addMovement(e);
                    float dy = Math.max(0, e.getRawY() - state[0] + state[1]);
                    sheet.setTranslationY(dy);
                    dim.getBackground().setAlpha((int) (0xB3 * Math.max(0, 1 - dy / (sheet.getHeight() + 1))));
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    tracker.addMovement(e);
                    tracker.computeCurrentVelocity(1000);
                    float dy = sheet.getTranslationY();
                    if (dy > Ui.dp(this, 110) || tracker.getYVelocity() > 900) {
                        sheet.animate().translationY(sheet.getHeight()).setDuration(180)
                                .withEndAction(this::finish).start();
                    } else {
                        sheet.animate().translationY(0).setDuration(320)
                                .setInterpolator(app.aomarchy.ui.Motion.SPRING).start();
                        dim.getBackground().setAlpha(0xB3);
                    }
                    return true;
                }
                default:
                    return true;
            }
        });

        ScrollView scroll = new ScrollView(this);
        ControlsView controls = new ControlsView(this);
        controls.refresh();
        int pad = Ui.dp(this, 20);
        controls.root.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 24));
        scroll.addView(controls.root);
        sheet.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        lp.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.72f);
        lp.gravity = Gravity.BOTTOM;
        root.addView(sheet, lp);
        setContentView(root);
    }
}
