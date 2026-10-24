package com.example.clockin;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

/** A compact vertical number wheel with a perspective size falloff. */
final class TimeWheelPickerView extends View {
    private static final int SELECTED_COLOR = 0xFFF5D11B;
    private static final int SELECTED_TEXT_COLOR = 0xFF2D2D2D;
    private static final int REST_TEXT_COLOR = 0xFF8D867A;
    private static final int VISIBLE_RADIUS = 2;
    private static final long SNAP_DURATION_MS = 150L;
    private static final float TOUCH_SLOP_DP = 5f;
    private static final float SNAP_THRESHOLD_RATIO = 0.30f;
    private static final float FLING_TRAVEL_ITEMS = 8f;

    private final String label;
    private final int maxValue;
    private final float itemHeight;
    private final float selectedTextSize;
    private final float restTextSize;
    private final float touchSlop;
    private final int minimumFlingVelocity;
    private final float maxFlingVelocity;
    private final Typeface normalTypeface;
    private final Typeface boldTypeface;
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF highlightRect = new RectF();
    private final OverScroller scroller;

    private int value;
    private float offset;
    private float lastTouchY;
    private float initialTouchY;
    private int lastFlingY;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private VelocityTracker velocityTracker;
    private ValueAnimator snapAnimator;
    private boolean dragging;
    private boolean flinging;
    private boolean multiTouch;

    TimeWheelPickerView(Context context, String label, int maxValue, int initialValue) {
        super(context);
        this.label = label;
        this.maxValue = maxValue;
        this.value = normalize(initialValue);
        float density = getResources().getDisplayMetrics().density;
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        itemHeight = 46f * density;
        selectedTextSize = 26f * scaledDensity;
        restTextSize = 23f * scaledDensity;
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = Math.min(configuration.getScaledTouchSlop(), TOUCH_SLOP_DP * density);
        minimumFlingVelocity = configuration.getScaledMinimumFlingVelocity();
        maxFlingVelocity = configuration.getScaledMaximumFlingVelocity();
        normalTypeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL);
        boldTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
        scroller = new OverScroller(context);
        scroller.setFriction(ViewConfiguration.getScrollFriction() * 0.7f);
        setFocusable(true);
        setClickable(true);
        setBackgroundColor(android.graphics.Color.WHITE);
        highlightPaint.setColor(SELECTED_COLOR);
        updateAccessibilityDescription();
    }

    int getValue() {
        return value;
    }

    private int normalize(int input) {
        int range = maxValue + 1;
        return ((input % range) + range) % range;
    }

    private void setValue(int nextValue, boolean announce) {
        value = normalize(nextValue);
        if (announce) {
            updateAccessibilityDescription();
            invalidate();
            announceForAccessibility(label + " " + format(value));
        }
    }

    private String format(int number) {
        return number < 10 ? "0" + number : Integer.toString(number);
    }

    private void updateAccessibilityDescription() {
        setContentDescription(label + " " + format(value));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float centerX = getWidth() * 0.5f;
        float centerY = getHeight() * 0.5f;
        float sideInset = 8f * getResources().getDisplayMetrics().density;
        float cornerRadius = 14f * getResources().getDisplayMetrics().density;
        highlightRect.set(sideInset, centerY - itemHeight * 0.47f,
                getWidth() - sideInset, centerY + itemHeight * 0.47f);
        canvas.drawRoundRect(highlightRect, cornerRadius, cornerRadius, highlightPaint);

        for (int index = -VISIBLE_RADIUS; index <= VISIBLE_RADIUS; index++) {
            float y = centerY + index * itemHeight + offset;
            if (y < -itemHeight || y > getHeight() + itemHeight) continue;
            float distance = Math.abs(y - centerY) / itemHeight;
            float scale = Math.max(0.68f, 1f - distance * 0.15f);
            boolean selected = distance < 0.5f;
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(selected ? boldTypeface : normalTypeface);
            textPaint.setTextSize((selected ? selectedTextSize : restTextSize) * scale);
            if (selected) {
                textPaint.setColor(SELECTED_TEXT_COLOR);
                textPaint.setAlpha(255);
            } else {
                textPaint.setColor(REST_TEXT_COLOR);
                textPaint.setAlpha(Math.max(75, (int) (205f - distance * 52f)));
            }
            float baseline = y - (textPaint.ascent() + textPaint.descent()) * 0.5f;
            canvas.drawText(format(normalize(value + index)), centerX, baseline, textPaint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (snapAnimator != null) snapAnimator.cancel();
                if (!scroller.isFinished()) scroller.forceFinished(true);
                if (velocityTracker != null) velocityTracker.recycle();
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(event);
                activePointerId = event.getPointerId(0);
                lastTouchY = event.getY();
                initialTouchY = lastTouchY;
                dragging = false;
                flinging = false;
                multiTouch = false;
                requestParentTouchOwnership(true);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                multiTouch = true;
                requestParentTouchOwnership(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (multiTouch) return true;
                int pointerIndex = event.findPointerIndex(activePointerId);
                if (pointerIndex < 0) return true;
                requestParentTouchOwnership(true);
                velocityTracker.addMovement(event);
                float y = event.getY(pointerIndex);
                if (!dragging && Math.abs(y - initialTouchY) > touchSlop) {
                    dragging = true;
                    offset += y - initialTouchY;
                    normalizeOffset();
                    invalidate();
                } else if (dragging) {
                    offset += y - lastTouchY;
                    normalizeOffset();
                    invalidate();
                }
                lastTouchY = y;
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                multiTouch = true;
                return true;
            case MotionEvent.ACTION_UP:
                if (velocityTracker != null) {
                    velocityTracker.addMovement(event);
                    velocityTracker.computeCurrentVelocity(1000, maxFlingVelocity);
                    float velocityY = velocityTracker.getYVelocity();
                    velocityTracker.recycle();
                    velocityTracker = null;
                    if (!multiTouch && dragging && Math.abs(velocityY) > minimumFlingVelocity) {
                        int maxTravel = Math.round(itemHeight * FLING_TRAVEL_ITEMS);
                        lastFlingY = 0;
                        dragging = false;
                        flinging = true;
                        scroller.fling(0, 0, 0, Math.round(velocityY),
                                0, 0, -maxTravel, maxTravel);
                        postInvalidateOnAnimation();
                        activePointerId = MotionEvent.INVALID_POINTER_ID;
                        return true;
                    }
                }
                dragging = false;
                flinging = false;
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                settleToNearest();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                if (!scroller.isFinished()) scroller.forceFinished(true);
                dragging = false;
                flinging = false;
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                settleToNearest();
                return true;
            default:
                return true;
        }
    }

    private void requestParentTouchOwnership(boolean disallow) {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(disallow);
    }

    private void normalizeOffset() {
        while (offset <= -itemHeight) {
            offset += itemHeight;
            setValue(value + 1, false);
        }
        while (offset >= itemHeight) {
            offset -= itemHeight;
            setValue(value - 1, false);
        }
    }

    private void settleToNearest() {
        if (snapAnimator != null) snapAnimator.cancel();
        if (offset <= -itemHeight * SNAP_THRESHOLD_RATIO) {
            offset += itemHeight;
            setValue(value + 1, false);
        } else if (offset >= itemHeight * SNAP_THRESHOLD_RATIO) {
            offset -= itemHeight;
            setValue(value - 1, false);
        }
        float start = offset;
        snapAnimator = ValueAnimator.ofFloat(start, 0f);
        snapAnimator.setDuration(SNAP_DURATION_MS);
        snapAnimator.setInterpolator(new DecelerateInterpolator());
        snapAnimator.addUpdateListener(animation -> {
            offset = (float) animation.getAnimatedValue();
            invalidate();
        });
        snapAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }

            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (cancelled) return;
                offset = 0f;
                dragging = false;
                updateAccessibilityDescription();
                invalidate();
            }
        });
        snapAnimator.start();
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            int currentY = scroller.getCurrY();
            offset += currentY - lastFlingY;
            lastFlingY = currentY;
            normalizeOffset();
            postInvalidateOnAnimation();
        } else if (flinging) {
            flinging = false;
            settleToNearest();
        }
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.NumberPicker");
        info.setScrollable(true);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override public boolean performAccessibilityAction(int action, android.os.Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
            setValue(value + 1, true);
            return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            setValue(value - 1, true);
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }

    @Override protected void onDetachedFromWindow() {
        if (snapAnimator != null) snapAnimator.cancel();
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
        scroller.forceFinished(true);
        super.onDetachedFromWindow();
    }
}
