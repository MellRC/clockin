package com.example.clockin;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/** Right-only gesture surface. A completed swipe requests system Keyguard dismissal. */
public final class LockReminderSwipeLayout extends FrameLayout {
    interface Listener {
        void onGestureStarted();
        void onGestureSettled();
        boolean onUnlockRequested(String source);
    }

    private final int touchSlop;
    private final float density;
    private float downX, downY;
    private int activePointer = MotionEvent.INVALID_POINTER_ID;
    private boolean tracking, dragging, multiTouch, cancelled, submitted;
    private VelocityTracker velocity;
    private Listener listener;

    public LockReminderSwipeLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        ViewConfiguration config = ViewConfiguration.get(context);
        touchSlop = config.getScaledTouchSlop();
        density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setListener(Listener value) { listener = value; }

    void unlockRequestFinished() {
        submitted = false;
        animate().cancel();
        animate().translationX(0).setDuration(TaskPolicy.SWIPE_SPRING_MS)
                .withEndAction(() -> { if (listener != null) listener.onGestureSettled(); }).start();
    }

    boolean requestUnlock(String source) {
        if (submitted || listener == null) return false;
        submitted = true;
        if (!listener.onUnlockRequested(source)) {
            submitted = false;
            return false;
        }
        animate().cancel();
        animate().translationX(0).setDuration(TaskPolicy.SWIPE_SPRING_MS).start();
        return true;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            clearGesture();
            tracking = true;
            activePointer = event.getPointerId(0);
            downX = event.getX(0);
            downY = event.getY(0);
            velocity = VelocityTracker.obtain();
        }
        if (velocity != null) velocity.addMovement(event);
        if (action == MotionEvent.ACTION_POINTER_DOWN) multiTouch = true;
        if (action == MotionEvent.ACTION_CANCEL) cancelled = true;
        boolean handled = super.dispatchTouchEvent(event);
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && !dragging) clearGesture();
        return handled;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && tracking && !multiTouch && !cancelled && !dragging) {
            int index = event.findPointerIndex(activePointer);
            if (index >= 0) {
                float dx = event.getX(index) - downX;
                float dy = event.getY(index) - downY;
                if (dx > touchSlop && dx > Math.abs(dy) * TaskPolicy.SWIPE_AXIS_RATIO) {
                    dragging = true;
                    if (listener != null) listener.onGestureStarted();
                    return true;
                }
            }
        }
        return super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (!dragging) return super.onTouchEvent(event);
        if (action == MotionEvent.ACTION_MOVE && !multiTouch && !cancelled) {
            int index = event.findPointerIndex(activePointer);
            if (index >= 0) setTranslationX(Math.max(0, event.getX(index) - downX));
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) {
            cancelled = true;
            springBack();
            clearGesture();
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (velocity != null) velocity.computeCurrentVelocity(1000);
            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            float vx = velocity == null ? 0 : velocity.getXVelocity(activePointer);
            boolean complete = !submitted && TaskPolicy.shouldCollapseSwipe(dx, dy, vx,
                    getWidth(), density, touchSlop, multiTouch, cancelled);
            if (complete) requestUnlock("right_swipe");
            else springBack();
            clearGesture();
            return true;
        }
        return true;
    }

    private void springBack() {
        animate().cancel();
        animate().translationX(0).setDuration(TaskPolicy.SWIPE_SPRING_MS)
                .withEndAction(() -> { if (listener != null) listener.onGestureSettled(); }).start();
    }

    private void clearGesture() {
        tracking = false;
        dragging = false;
        activePointer = MotionEvent.INVALID_POINTER_ID;
        if (velocity != null) { velocity.recycle(); velocity = null; }
    }
}
