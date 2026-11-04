package app.aomarchy;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.aomarchy.ui.Ui;

/**
 * The controls as a half-screen sheet over the desktop: opened from the session notification (or anywhere via an
 * explicit intent). Dimmed background; tapping outside or 关闭 dismisses; the desktop keeps running underneath.
 */
public class ControlsActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
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
        head.addView(Ui.text(this, "控制", 18, Ui.TEXT, true));
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView close = Ui.text(this, "关闭", 14, Ui.ACCENT, false);
        close.setOnClickListener(v -> finish());
        head.addView(close);
        sheet.addView(head);

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
