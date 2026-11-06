package app.aomarchy.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import android.content.res.ColorStateList;
import android.os.Build;

import java.io.File;

/**
 * The AOmarchy design system: fixed brand dark theme (deliberately not the desktop's Omarchy theme), no
 * androidx, everything drawn with framework views so the APK stays tiny.
 */
public final class Ui {
    /** Palette — keep in sync with res/values/colors.xml and
     *  docs/2026-11-05-AOmarchy-UI交互设计规范.md (dual-tone design system). */
    public static final int BG = 0xFF0A0C10;
    public static final int SURFACE = 0xFF14171F;
    public static final int SURFACE_HI = 0xFF1C2230;
    public static final int LINE = 0xFF262C3A;
    /** Real glass: translucent enough for the blurred backdrop to read through, dark enough for body text.
     *  CardBackground lights it from above (thickness gradient, top glow, lit rim). */
    public static final int GLASS = 0x4D101622; // bumped from 0x33 (20%) → 0x4D (30%): less transparent, still glass
    /** The rail and floating pills: a little denser than a card so labels stay calm, but still glass. */
    public static final int GLASS_STRONG = 0x6610151F; // bumped from 0x47 (28%) → 0x66 (40%): reads as solid glass over the backdrop
    public static final int GLASS_LINE = 0xB0FFFFFF;
    public static final int GLASS_LINE_SOFT = 0x2EFFFFFF;
    /** Structural primary: actions, selection, focus, navigation. The full 50-900 scale lives in colors.xml. */
    public static final int ACCENT = 0xFF7C8CFF;
    /** Status only: running / ready / success. Never use as a primary button or a large fill. */
    public static final int SIGNAL = 0xFF9CD462;
    /** Terminal startup mode only — NOT a general status colour. */
    public static final int MINT = 0xFF5FE1B4;
    public static final int WARN = 0xFFFF6B6B;
    public static final int TEXT = 0xFFE6E9F0;
    public static final int DIM = 0xFFA8B3C4; // brighter: clear glass shows the picture, secondary text needs more contrast
    /** Tertiary text. #5C6675 was 3.4:1 on BG (below WCAG 2.2 AA); #7A8595 reaches 5.2:1. */
    public static final int FAINT = 0xFF7A8595;

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

    /** A frosted-glass panel: translucent surface, light hairline, for pills, cells and floating bars. */
    public static Drawable surface(Context c, float radiusDp) {
        return new CardBackground(c, GLASS_STRONG, GLASS_LINE, 1f, radiusDp);
    }

    /** The same glass with its full-perimeter prismatic edge lit up: use for the selected item in a set. */
    public static CardBackground surfaceSelected(Context c, float radiusDp) {
        CardBackground bg = new CardBackground(c, GLASS_STRONG, GLASS_LINE, 1f, radiusDp);
        bg.setPrismGlow(1f);
        return bg;
    }

    /**
     * Every console button carries the prismatic bloom, a notch below the selected-state intensity so a
     * selected source tile still reads as the strongest element when it sits next to a button.
     */
    public static final float BUTTON_PRISM = 0.7f;

    public static CardBackground buttonSurface(Context c, int baseColor, float radiusDp) {
        CardBackground bg = new CardBackground(c, baseColor, GLASS_LINE, 1f, radiusDp);
        bg.setPrismGlow(BUTTON_PRISM);
        return bg;
    }

    /** The console rail: the same glass as the cards, so the blurred backdrop and its light read through it too. */
    public static Drawable rail(Context c) {
        return new CardBackground(c, GLASS_STRONG, GLASS_LINE, 1.2f, 22);
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

    /** A section card: rounded surface, optional title, children in a vertical list. Compact by default —
     *  the console shows many short cards at once, so padding and the gap between cards stay tight. */
    public static LinearLayout card(Context c, String title) {
        LinearLayout card = column(c);
        // Glass card: translucent over the blurred wallpaper, light hairline and a soft shadow so it floats.
        CardBackground bg = new CardBackground(c, GLASS, GLASS_LINE, 1.2f, 18);
        card.setBackground(bg);
        card.setElevation(dp(c, 8));
        CardBackground.attach(card, bg);
        GlassTint.register(card); // the backdrop's colour becomes this card's glow
        int pad = dp(c, 10);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 6);
        card.setLayoutParams(lp);
        if (title != null) {
            TextView t = text(c, title, 13, DIM, true);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.bottomMargin = dp(c, 5);
            card.addView(t, tlp);
        }
        return card;
    }

    /**
     * Two short cards sharing one row on a wide (landscape) screen. On a narrow screen they stack, because a
     * half-width card would squeeze the key/value rows. Each card keeps its own bottom margin from {@link #card}.
     */
    public static LinearLayout pair(Context c, LinearLayout a, LinearLayout b) {
        boolean wide = c.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout group = wide ? row(c) : column(c);
        if (wide)
            group.setGravity(Gravity.TOP); // unequal card heights must align at the top, not float centred
        if (wide) {
            // MATCH_PARENT makes both cards the same height, so a short card does not leave a bare gap
            // beneath it. Ui.card's own bottomMargin is replaced here, so re-apply it for the next row.
            int edge = dp(c, 6);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.rightMargin = edge;
            lp.bottomMargin = edge;
            group.addView(a, lp);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp2.bottomMargin = edge;
            group.addView(b, lp2);
        } else {
            group.addView(a);
            group.addView(b);
        }
        group.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return group;
    }

    public static TextView chip(Context c, String s, int color) {
        TextView t = text(c, s, 12, color, true);
        t.setBackground(rounded(c, 0x1AFFFFFF, 0, 30));
        int h = dp(c, 5), w = dp(c, 10);
        t.setPadding(w, h, w, h);
        return t;
    }

    public static TextView button(Context c, String s, boolean primary, View.OnClickListener onClick) {
        TextView t = text(c, s, 14, primary ? 0xFF0A0C10 : TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(buttonSurface(c, primary ? ACCENT : 0x3D1C2230, 14));
        Motion.pressable(t);
        int v = dp(c, 9), h = dp(c, 14);
        t.setPadding(h, v, h, v);
        t.setOnClickListener(onClick);
        return t;
    }

    /** Vertical spacer. Callers still pass their intended size; the console's compact layout scales it once here. */
    private static final float GAP_SCALE = 0.7f;

    public static View gap(Context c, int heightDp) {
        int h = Math.max(2, Math.round(heightDp * GAP_SCALE));
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, h)));
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
        r.setPadding(0, dp(c, 3), 0, dp(c, 3));
        TextView k = text(c, key, 14, DIM, false);
        r.addView(k, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View space = new View(c);
        r.addView(space, new LinearLayout.LayoutParams(0, 1, 1f));
        r.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    /** Key on the left, value with a one-line explanation under it on the right: fills a wide row with information. */
    public static LinearLayout kv2(Context c, String key, TextView value, String caption) {
        LinearLayout r = row(c);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        r.setPadding(0, dp(c, 3), 0, dp(c, 3));
        r.addView(text(c, key, 14, DIM, false),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View space = new View(c);
        r.addView(space, new LinearLayout.LayoutParams(0, 1, 1f));
        LinearLayout right = column(c);
        right.setGravity(android.view.Gravity.END);
        value.setGravity(android.view.Gravity.END);
        right.addView(value);
        if (caption != null && !caption.isEmpty()) {
            TextView cap = text(c, caption, 11, FAINT, false);
            cap.setGravity(android.view.Gravity.END);
            right.addView(cap);
        }
        r.addView(right, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }
    /** 1dp separator, for rows inside a card. */
    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(GLASS_LINE_SOFT);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1)));
        return v;
    }

    public static void weight(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        v.setLayoutParams(lp);
    }

    /** Callback for the settings pages' toggle rows: the row already shows the new state, the caller persists it. */
    public interface OnToggle {
        void onToggle(boolean value);
    }

    /** A settings row that opens something: label (optional explanation) left, a value and › right. */
    public static LinearLayout actionRow(Context c, String label, String caption, String value, Runnable onClick) {
        LinearLayout r = row(c);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        r.setPadding(0, dp(c, 7), 0, dp(c, 7));

        LinearLayout texts = column(c);
        texts.addView(text(c, label, 14, TEXT, false));
        if (caption != null && !caption.isEmpty())
            texts.addView(text(c, caption, 11, FAINT, false));
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = row(c);
        if (value != null && !value.isEmpty()) {
            TextView v = text(c, value, 13, DIM, false);
            right.addView(v);
            right.addView(space(c, 6));
        }
        right.addView(text(c, "›", 15, FAINT, false));
        r.addView(right);
        r.setOnClickListener(v -> onClick.run());
        r.setClickable(true);
        return r;
    }

    /** A settings slider: a title + live value on top, a SeekBar below. Reports user-driven changes (0..max). */
    public static LinearLayout slider(Context c, String title, int value, int max, String unit, OnSeek onSeek) {
        LinearLayout wrap = column(c);
        wrap.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.setPadding(0, dp(c, 6), 0, dp(c, 2));

        LinearLayout head = row(c);
        head.addView(text(c, title, 14, TEXT, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = text(c, "", 13, ACCENT, true);
        head.addView(val);
        wrap.addView(head);

        SeekBar bar = new SeekBar(c);
        bar.setMax(max);
        bar.setProgress(value);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (Build.VERSION.SDK_INT >= 21) {
            bar.setThumbTintList(ColorStateList.valueOf(ACCENT));
            bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(GLASS_LINE_SOFT));
        }
        final String u = unit;
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                val.setText(progress + (u == null || u.isEmpty() ? "" : " " + u));
                if (fromUser && onSeek != null)
                    onSeek.onSeek(progress);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        val.setText(value + (u == null || u.isEmpty() ? "" : " " + u));
        wrap.addView(bar);
        return wrap;
    }

    /** Callback for the settings pages' slider rows: reports the new value while the user drags. */
    public interface OnSeek {
        void onSeek(int value);
    }
}
