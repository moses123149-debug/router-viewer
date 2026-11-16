package com.routerviewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class RingView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float fraction;

    public RingView(Context c, AttributeSet a) {
        super(c, a);
        float w = 12 * getResources().getDisplayMetrics().density;
        for (Paint p : new Paint[] {track, arc}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(w);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        track.setColor(c.getColor(R.color.line));
        arc.setColor(c.getColor(R.color.accent));
    }

    public void setFraction(float f) {
        fraction = Math.max(0f, Math.min(1f, f));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = track.getStrokeWidth() / 2;
        box.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawArc(box, 0, 360, false, track);
        if (fraction > 0) canvas.drawArc(box, -90, 360 * fraction, false, arc);
    }
}
