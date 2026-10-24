package com.example.clockin;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Typeface;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.LinearInterpolator;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

/** The entire visible reminder design: one title and one accessible swipe hint. */
public final class LockReminderContentView extends View {
    private static final String TITLE = "打卡时间到了！";
    private static final String HINT = "滑动解锁";

    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevron = new Path();
    private final Path sweep = new Path();
    private final float density;
    private final int backgroundColor;
    private final int titleColor;
    private final int hintColor;
    private final int sweepColor;
    private int safeTop, safeBottom;
    private ValueAnimator animator;
    private long animatorTime;
    private boolean animationEnabled = true;
    private OnClickListener unlockAction;

    public LockReminderContentView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        backgroundColor = context.getColor(R.color.lock_reminder_yellow);
        titleColor = context.getColor(R.color.lock_reminder_title);
        hintColor = context.getColor(R.color.lock_reminder_hint);
        sweepColor = context.getColor(R.color.lock_reminder_sweep);
        setBackgroundColor(backgroundColor);
        setClickable(true);
        setFocusable(true);
        setContentDescription("滑动解锁，双击请求系统解锁");
        titlePaint.setColor(titleColor);
        titlePaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        hintPaint.setColor(hintColor);
        hintPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        shapePaint.setStyle(Paint.Style.STROKE);
        shapePaint.setStrokeCap(Paint.Cap.ROUND);
        shapePaint.setStrokeJoin(Paint.Join.ROUND);
        shapePaint.setColor(hintColor);
        maskPaint.setColor(Color.WHITE);
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                safeTop = safe.top;
                safeBottom = safe.bottom;
            } else {
                safeTop = insets.getSystemWindowInsetTop();
                safeBottom = insets.getSystemWindowInsetBottom();
            }
            invalidate();
            return insets;
        });
        setOnClickListener(view -> { if (unlockAction != null) unlockAction.onClick(view); });
    }

    void setUnlockAction(OnClickListener action) { unlockAction = action; }

    void startSweep() {
        if (!animationEnabled || animator != null || !isShown() || !ValueAnimator.areAnimatorsEnabled()) {
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setStartDelay(TaskPolicy.LOCK_HINT_INITIAL_DELAY_MS);
        animator.setDuration(TaskPolicy.LOCK_HINT_CYCLE_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> {
            animatorTime = (long) (value.getAnimatedFraction() * TaskPolicy.LOCK_HINT_CYCLE_MS);
            invalidate();
        });
        animator.start();
    }

    void pauseSweep() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        animatorTime = 0;
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        pauseSweep();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(backgroundColor);
        float safeHeight = Math.max(0, getHeight() - safeTop - safeBottom);
        float titleSize = getResources().getDisplayMetrics().scaledDensity * TaskPolicy.LOCK_TITLE_SIZE_SP;
        float hintSize = getResources().getDisplayMetrics().scaledDensity * TaskPolicy.LOCK_HINT_SIZE_SP;
        titlePaint.setTextSize(titleSize);
        hintPaint.setTextSize(hintSize);
        float horizontalPadding = dp(TaskPolicy.LOCK_TITLE_HORIZONTAL_DP);
        int titleWidth = Math.max(1, (int) (getWidth() - horizontalPadding * 2));
        StaticLayout titleLayout = StaticLayout.Builder.obtain(TITLE, 0, TITLE.length(), titlePaint, titleWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build();
        float titleCenter = safeTop + safeHeight * TaskPolicy.LOCK_TITLE_CENTER_FRACTION;
        canvas.save();
        canvas.translate((getWidth() - titleWidth) / 2f, titleCenter - titleLayout.getHeight() / 2f);
        titleLayout.draw(canvas);
        canvas.restore();

        drawHint(canvas, getHeight() - safeBottom - dp(TaskPolicy.LOCK_HINT_BOTTOM_DP), hintSize);
    }

    private void drawHint(Canvas canvas, float centerY, float textSize) {
        float iconWidth = dp(TaskPolicy.LOCK_HINT_ICON_WIDTH_DP);
        float iconHeight = dp(TaskPolicy.LOCK_HINT_ICON_HEIGHT_DP);
        float iconGap = dp(TaskPolicy.LOCK_HINT_ICON_GAP_DP);
        float iconGroupWidth = iconWidth * 3 + iconGap * 2;
        float textGap = dp(TaskPolicy.LOCK_HINT_TEXT_GAP_DP);
        float textWidth = Math.max(1, hintPaint.measureText(HINT));
        float totalWidth = iconGroupWidth + textGap + textWidth;
        float left = (getWidth() - totalWidth) / 2f;
        float top = centerY - iconHeight / 2f;
        float stroke = dp(TaskPolicy.LOCK_HINT_STROKE_DP);
        shapePaint.setStrokeWidth(stroke);

        drawHintBase(canvas, left, top, iconWidth, iconHeight, iconGap, iconGroupWidth,
                textGap, textWidth, centerY, textSize, stroke);
        if (!animationEnabled || !ValueAnimator.areAnimatorsEnabled() || animatorTime >= TaskPolicy.LOCK_HINT_SWEEP_MS)
            return;

        float sweepWidth = dp(TaskPolicy.LOCK_HINT_BAND_WIDTH_DP);
        float travel = totalWidth + sweepWidth * 2;
        float progress = Math.min(1f, animatorTime / (float) TaskPolicy.LOCK_HINT_SWEEP_MS);
        float bandLeft = left - sweepWidth + travel * progress;
        float bend = dp(TaskPolicy.LOCK_HINT_BEND_DP);
        canvas.saveLayer(left - dp(2), centerY - textSize, left + totalWidth + dp(2), centerY + textSize, null);
        shapePaint.setStyle(Paint.Style.FILL);
        shapePaint.setColor(sweepColor);
        sweep.reset();
        sweep.moveTo(bandLeft, centerY - textSize * .65f);
        sweep.quadTo(bandLeft + bend, centerY, bandLeft, centerY + textSize * .65f);
        sweep.lineTo(bandLeft + sweepWidth, centerY + textSize * .65f);
        sweep.quadTo(bandLeft + sweepWidth - bend, centerY, bandLeft + sweepWidth, centerY - textSize * .65f);
        sweep.close();
        canvas.drawPath(sweep, shapePaint);
        maskPaint.setTextSize(textSize);
        maskPaint.setTypeface(hintPaint.getTypeface());
        maskPaint.setStyle(Paint.Style.STROKE);
        maskPaint.setStrokeCap(Paint.Cap.ROUND);
        maskPaint.setStrokeJoin(Paint.Join.ROUND);
        maskPaint.setStrokeWidth(stroke);
        drawChevronMask(canvas, left, top, iconWidth, iconHeight, iconGap, maskPaint);
        maskPaint.setStyle(Paint.Style.FILL);
        canvas.drawText(HINT, left + iconGroupWidth + textGap,
                centerY - (hintPaint.ascent() + hintPaint.descent()) / 2f, maskPaint);
        canvas.restore();
    }

    private void drawHintBase(Canvas canvas, float left, float top, float iconWidth, float iconHeight,
                              float iconGap, float iconGroupWidth, float textGap, float textWidth,
                              float centerY, float textSize, float stroke) {
        shapePaint.setColor(hintColor);
        shapePaint.setStyle(Paint.Style.STROKE);
        shapePaint.setStrokeWidth(stroke);
        drawChevronMask(canvas, left, top, iconWidth, iconHeight, iconGap, shapePaint);
        canvas.drawText(HINT, left + iconGroupWidth + textGap,
                centerY - (hintPaint.ascent() + hintPaint.descent()) / 2f, hintPaint);
    }

    private void drawChevronMask(Canvas canvas, float left, float top, float width, float height,
                                 float gap, Paint paint) {
        for (int i = 0; i < 3; i++) {
            float x = left + i * (width + gap);
            chevron.reset();
            chevron.moveTo(x, top);
            chevron.lineTo(x + width, top + height / 2f);
            chevron.lineTo(x, top + height);
            canvas.drawPath(chevron, paint);
        }
    }

    private float dp(float value) { return value * density; }
}
