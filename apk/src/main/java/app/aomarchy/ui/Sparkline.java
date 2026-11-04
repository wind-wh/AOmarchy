package app.aomarchy.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Tiny CPU history line for the overview: 32 samples, no dependency, no allocation while drawing. */
public final class Sparkline extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float[] values = new float[32];
    private int count;

    public Sparkline(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Ui.dp(c, 1.6f));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(Ui.ACCENT);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x1A7C8CFF);
    }

    /** Appends a 0..100 sample; negative values are ignored. */
    public void push(int value) {
        if (value < 0)
            return;
        if (count < values.length) {
            values[count++] = value;
        } else {
            System.arraycopy(values, 1, values, 0, values.length - 1);
            values[values.length - 1] = value;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (count < 2)
            return;
        float w = getWidth(), h = getHeight();
        float dx = w / (values.length - 1f);
        int offset = values.length - count;
        path.reset();
        for (int i = 0; i < count; i++) {
            float x = (offset + i) * dx;
            float y = h - Ui.dp(getContext(), 2) - (h - Ui.dp(getContext(), 4)) * (values[i] / 100f);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        canvas.drawPath(path, line);
        Path area = new Path(path);
        area.lineTo((offset + count - 1) * dx, h);
        area.lineTo(offset * dx, h);
        area.close();
        canvas.drawPath(area, fill);
    }
}
