package app.aomarchy.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * A minimal flow layout: children are measured with their own (wrap_content) size and placed left to right, wrapping
 * to a new row when the next child no longer fits. Used by the terminal's quick-command panel so a chip hugs its label
 * instead of being stretched over an equal-width grid cell.
 *
 * <p>Spacing is uniform ({@code gapDp} horizontally between chips and vertically between rows); this ViewGroup's
 * padding is respected. Width should be given (usually {@code match_parent}); with an UNSPECIFIED width it measures
 * the widest row, and the height always wraps its rows.
 */
public class FlowRow extends ViewGroup {
    private final int gap;

    public FlowRow(Context context) {
        this(context, 8);
    }

    public FlowRow(Context context, int gapDp) {
        super(context);
        gap = Math.max(0, Ui.dp(context, gapDp));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int available = widthMode == MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE
                : Math.max(0, widthSize - getPaddingLeft() - getPaddingRight());

        int x = 0;
        int y = 0;
        int rowHeight = 0;
        int widestRow = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE)
                continue;
            measureChild(child, widthMeasureSpec, heightMeasureSpec);
            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();
            if (x > 0 && x + gap + childWidth > available) {
                widestRow = Math.max(widestRow, x);
                x = 0;
                y += rowHeight + gap;
                rowHeight = 0;
            }
            x += (x > 0 ? gap : 0) + childWidth;
            rowHeight = Math.max(rowHeight, childHeight);
        }
        widestRow = Math.max(widestRow, x);

        int width = widthMode == MeasureSpec.UNSPECIFIED
                ? widestRow + getPaddingLeft() + getPaddingRight()
                : widthSize;
        int height = y + rowHeight + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int available = Math.max(0, r - l - getPaddingLeft() - getPaddingRight());
        int left = getPaddingLeft();
        int x = left;
        int y = getPaddingTop();
        int rowHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE)
                continue;
            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();
            if (x > left && x + gap + childWidth > left + available) {
                x = left;
                y += rowHeight + gap;
                rowHeight = 0;
            }
            if (x > left)
                x += gap;
            child.layout(x, y, x + childWidth, y + childHeight);
            x += childWidth;
            rowHeight = Math.max(rowHeight, childHeight);
        }
    }
}
