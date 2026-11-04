package app.aomarchy.ui;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * In-app toasts: they rise from the bottom, stack over each other and fade out, instead of the system toast that
 * cannot be styled. One stack per activity.
 */
public final class Toasts {
    private static WeakReference<Activity> host = new WeakReference<>(null);
    /** While a full-screen sheet (Dialog) is open, toasts belong in its window or they stay behind it. */
    private static WeakReference<ViewGroup> sheetRoot = new WeakReference<>(null);
    private static final List<View> shown = new ArrayList<>();

    private Toasts() {
    }

    /**
     * Routes toasts into a sheet's own decor view: a Dialog window sits above the activity's content, so a toast
     * added to the activity would be invisible while the sheet is open. Pair with {@link #clearSheet()} on dismiss.
     */
    public static void useSheet(ViewGroup root) {
        sheetRoot = new WeakReference<>(root);
    }

    public static void clearSheet() {
        sheetRoot = new WeakReference<>(null);
    }

    public static void show(Activity activity, String text) {
        if (activity == null || activity.isFinishing())
            return;
        host = new WeakReference<>(activity);
        ViewGroup sheet = sheetRoot.get();
        boolean inSheet = sheet != null && sheet.isAttachedToWindow();
        ViewGroup root = inSheet ? sheet : activity.findViewById(android.R.id.content);
        if (root == null)
            return;
        TextView toast = Ui.text(activity, text, 13, Ui.TEXT, false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE61C2230);
        bg.setCornerRadius(Ui.dp(activity, 14));
        bg.setStroke(Ui.dp(activity, 1), Ui.LINE);
        toast.setBackground(bg);
        int h = Ui.dp(activity, 14), v = Ui.dp(activity, 10);
        toast.setPadding(h, v, h, v);
        toast.setElevation(Ui.dp(activity, 8));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = Ui.dp(activity, 90) + shown.size() * Ui.dp(activity, 52);
        root.addView(toast, lp);
        shown.add(toast);

        toast.setAlpha(0f);
        toast.setTranslationY(Ui.dp(activity, 18));
        toast.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(Motion.SPRING).start();
        toast.postDelayed(() -> dismiss(toast), 1900);
    }

    private static void dismiss(View toast) {
        if (toast.getParent() == null)
            return;
        toast.animate().alpha(0f).translationY(Ui.dp(toast.getContext(), 10)).setDuration(220)
                .withEndAction(() -> {
                    ViewGroup parent = (ViewGroup) toast.getParent();
                    if (parent != null)
                        parent.removeView(toast);
                    shown.remove(toast);
                }).start();
    }
}
