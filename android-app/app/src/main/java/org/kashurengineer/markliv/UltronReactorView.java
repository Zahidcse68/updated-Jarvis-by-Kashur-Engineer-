package org.kashurengineer.markliv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class UltronReactorView extends View {

    private Paint outerPaint;
    private Paint arcPaint;
    private Paint irisPaint;
    private float angle = 0f;
    private float audioLevel = 0.1f;
    private boolean isSpeaking = false;

    public UltronReactorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        outerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        outerPaint.setStyle(Paint.Style.STROKE);
        outerPaint.setColor(Color.parseColor("#00D4FF"));
        outerPaint.setStrokeWidth(3f);

        arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setColor(Color.parseColor("#00E5FF"));
        arcPaint.setStrokeWidth(6f);

        irisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        irisPaint.setStyle(Paint.Style.FILL);
        irisPaint.setColor(Color.parseColor("#00D4FF"));
    }

    public void setAudioLevel(float level) {
        this.audioLevel = Math.max(0.05f, Math.min(1.0f, level));
        invalidate();
    }

    public void setSpeaking(boolean speaking) {
        this.isSpeaking = speaking;
        if (speaking) {
            arcPaint.setColor(Color.parseColor("#FF2255"));
            irisPaint.setColor(Color.parseColor("#FF1744"));
        } else {
            arcPaint.setColor(Color.parseColor("#00E5FF"));
            irisPaint.setColor(Color.parseColor("#00D4FF"));
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) * 0.85f;

        if (radius <= 0) return;

        // Draw outer ring
        canvas.drawCircle(cx, cy, radius, outerPaint);

        // Draw rotating 6 turbine arcs
        RectF arcBounds = new RectF(cx - radius * 0.75f, cy - radius * 0.75f, cx + radius * 0.75f, cy + radius * 0.75f);
        for (int i = 0; i < 6; i++) {
            float startAngle = (angle + i * 60) % 360;
            canvas.drawArc(arcBounds, startAngle, 35, false, arcPaint);
        }

        // Draw counter-rotating 4 inner arcs
        RectF innerBounds = new RectF(cx - radius * 0.5f, cy - radius * 0.5f, cx + radius * 0.5f, cy + radius * 0.5f);
        for (int i = 0; i < 4; i++) {
            float startAngle = (-angle * 1.5f + i * 90) % 360;
            canvas.drawArc(innerBounds, startAngle, 45, false, arcPaint);
        }

        // Center pulsing iris
        float pulseRadius = radius * 0.25f + (audioLevel * radius * 0.25f);
        canvas.drawCircle(cx, cy, pulseRadius, irisPaint);

        // Advance animation angle
        angle = (angle + 2.0f) % 360;
        postInvalidateOnAnimation();
    }
}
