package com.example.clockin;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Lightweight visual-only expanding rings. */
public final class ReminderPulseView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator animator;
    private float phase;

    public ReminderPulseView(Context context, AttributeSet attrs) { super(context, attrs); }

    void startPulse() {
        if (animator != null || !isShown()) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(2100);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> { phase = (float) value.getAnimatedValue(); invalidate(); });
        animator.start();
    }

    void stopPulse() {
        if (animator != null) { animator.cancel(); animator = null; }
        phase = 0;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float max = Math.min(getWidth() * .42f, getHeight() * .47f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(getResources().getDisplayMetrics().density * 2f);
        for (int i = 0; i < 3; i++) {
            float p = (phase + i / 3f) % 1f;
            paint.setColor(0x99FFFFFF);
            paint.setAlpha((int) (110 * (1f - p)));
            canvas.drawCircle(cx, cy, max * (.18f + .82f * p), paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFF8E8);
        canvas.drawCircle(cx, cy, getResources().getDisplayMetrics().density * 7f, paint);
    }
}
