package app.aomarchy.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/**
 * The AOmarchy design system: fixed brand dark theme (deliberately not the desktop's Omarchy theme), no
 * androidx, everything drawn with framework views so the APK stays tiny.
 */
public final class Ui {
    /** Palette — keep in sync with docs/ui-redesign-plan.md. */
    public static final int BG = 0xFF0A0C10;
    public static final int SURFACE = 0xFF14171F;
    public static final int SURFACE_HI = 0xFF1C2230;
    public static final int LINE = 0xFF262C3A;
    public static final int ACCENT = 0xFF7C8CFF;
    public static final int MINT = 0xFF5FE1B4;
    public static final int WARN = 0xFFFF6B6B;
    public static final int TEXT = 0xFFE6E9F0;
    public static final int DIM = 0xFF98A2B3;
    public static final int FAINT = 0xFF5C6675;

    private static Typeface cjk;

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** The Chinese font the session copies into the rootfs: same glyphs as the desktop. Falls back to the system one. */
    public static Typeface cjk(Context c) {
        if (cjk == null) {
            Typeface f = null;
            File[] candidates = {
                    new File(new File(c.getFilesDir(), "rootfs"), "usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc"),
                    new File("/system/fonts/NotoSansCJK-Regular.ttc")
            };
            for (File candidate : candidates) {
                try {
                    if (candidate.isFile()) {
                        f = Typeface.createFromFile(candidate);
                        break;
                    }
                } catch (RuntimeException ignored) {
                    // fall through to the next candidate
                }
            }
            cjk = f != null ? f : Typeface.DEFAULT;
        }
        return cjk;
    }

    private static GradientDrawable rounded(Context c, int color, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) {
            d.setStroke(dp(c, 1), stroke);
        }
        return d;
    }

    public static GradientDrawable surface(Context c, float radiusDp) {
        return rounded(c, SURFACE, LINE, radiusDp);
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static TextView text(Context c, String s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setTypeface(cjk(c), bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    /** Monospace numbers for metrics: they must not jump around while refreshing. */
    public static TextView mono(Context c, String s, float sizeSp, int color) {
        TextView t = text(c, s, sizeSp, color, false);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    /** A section card: rounded surface, optional title, children in a vertical list. */
    public static LinearLayout card(Context c, String title) {
        LinearLayout card = column(c);
        CardBackground bg = new CardBackground(c, SURFACE, LINE, 1, 20);
        card.setBackground(bg);
        CardBackground.attach(card, bg);
        int pad = dp(c, 18);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        card.setLayoutParams(lp);
        if (title != null) {
            TextView t = text(c, title, 13, DIM, true);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.bottomMargin = dp(c, 10);
            card.addView(t, tlp);
        }
        return card;
    }

    public static TextView chip(Context c, String s, int color) {
        TextView t = text(c, s, 12, color, true);
        t.setBackground(rounded(c, 0x1AFFFFFF, 0, 30));
        int h = dp(c, 6), w = dp(c, 12);
        t.setPadding(w, h, w, h);
        return t;
    }

    public static TextView button(Context c, String s, boolean primary, View.OnClickListener onClick) {
        TextView t = text(c, s, 14, primary ? 0xFF0A0C10 : TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(c, primary ? ACCENT : SURFACE_HI, primary ? 0 : LINE, 14));
        Motion.pressable(t);
        int v = dp(c, 12), h = dp(c, 18);
        t.setPadding(h, v, h, v);
        t.setOnClickListener(onClick);
        return t;
    }

    public static View gap(Context c, int heightDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp)));
        return v;
    }

    public static View space(Context c, int widthDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, widthDp), 1));
        return v;
    }

    /** Key/value row: label left, value right (value is mono so numbers line up). */
    public static LinearLayout kv(Context c, String key, TextView value) {
        LinearLayout r = row(c);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        r.setPadding(0, dp(c, 7), 0, dp(c, 7));
        TextView k = text(c, key, 14, DIM, false);
        r.addView(k, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View space = new View(c);
        r.addView(space, new LinearLayout.LayoutParams(0, 1, 1f));
        r.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    /** 1dp separator, for rows inside a card. */
    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1)));
        return v;
    }

    public static void weight(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        v.setLayoutParams(lp);
    }
}
