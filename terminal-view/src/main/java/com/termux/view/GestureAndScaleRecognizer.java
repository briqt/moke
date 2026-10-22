package com.termux.view;

import android.content.Context;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;

/** One owner per touch sequence. Detector callbacks never retain a caller-owned MotionEvent. */
final class GestureAndScaleRecognizer {
    interface Listener {
        boolean onSingleTapUp(MotionEvent e);
        boolean onDoubleTap(MotionEvent e);
        boolean onScroll(MotionEvent e, float dx, float dy);
        boolean onFling(MotionEvent e, float velocityX, float velocityY);
        boolean onScale(float focusX, float focusY, float scale);
        /** True consumes this entire sequence (for example a tap that stops old inertia). */
        boolean onDown(float x, float y);
        boolean onUp(MotionEvent e);
        void onLongPress(MotionEvent e);
        void onCancel();
    }

    private enum Owner { IDLE, PENDING, SCROLL, SCALE, SELECTION, CONSUMED }
    private Owner owner = Owner.IDLE;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scale;
    private final Listener listener;
    private long downTime = -1, tapDownTime = -1;
    private float pendingX, pendingY, downX, downY;
    private final int tapSlopSquared;
    private boolean tapEligible;

    GestureAndScaleRecognizer(Context context, Listener target) {
        listener = target;
        int tapSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        tapSlopSquared = tapSlop * tapSlop;
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (owner == Owner.PENDING && tapEligible) tapDownTime = e.getDownTime();
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                tapEligible = false;
                tapDownTime = -1;
                if (owner != Owner.PENDING && owner != Owner.SCROLL) return true;
                if (owner == Owner.PENDING) {
                    pendingX += dx;
                    pendingY += dy;
                    if (Math.abs(pendingY) <= Math.abs(pendingX)) return true;
                    owner = Owner.SCROLL;
                    tapDownTime = -1;
                    dx = pendingX;
                    dy = pendingY;
                }
                return listener.onScroll(e2, dx, dy);
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                return owner == Owner.SCROLL && listener.onFling(e2, vx, vy);
            }
            @Override public void onLongPress(MotionEvent e) {
                if (owner != Owner.PENDING) return;
                owner = Owner.SELECTION;
                tapEligible = false;
                tapDownTime = -1;
                listener.onLongPress(e);
            }
        });
        gestures.setOnDoubleTapListener(new GestureDetector.OnDoubleTapListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (tapDownTime != e.getDownTime()) return true;
                tapDownTime = -1;
                return listener.onSingleTapUp(e);
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                tapDownTime = -1;
                return listener.onDoubleTap(e);
            }
            @Override public boolean onDoubleTapEvent(MotionEvent e) { return true; }
        });
        scale = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) { return true; }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                return owner == Owner.SCALE && listener.onScale(detector.getFocusX(), detector.getFocusY(), detector.getScaleFactor());
            }
        });
        scale.setQuickScaleEnabled(false);
    }

    void onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downTime = event.getDownTime();
            pendingX = pendingY = 0;
            downX = event.getX();
            downY = event.getY();
            tapEligible = true;
            owner = listener.onDown(event.getX(), event.getY()) ? Owner.CONSUMED : Owner.PENDING;
        } else if ((action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) && tapEligible) {
            float dx = event.getX() - downX, dy = event.getY() - downY;
            if (dx * dx + dy * dy > tapSlopSquared) {
                tapEligible = false;
                tapDownTime = -1;
            }
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            tapEligible = false;
            if (owner != Owner.CONSUMED && owner != Owner.SELECTION) owner = Owner.SCALE;
            tapDownTime = -1;
            listener.onCancel();
            cancelDetector();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            cancel();
            listener.onCancel();
        }
        // Multi-touch owns the entire sequence, including the last remaining finger.
        scale.onTouchEvent(event);
        if (owner == Owner.PENDING || owner == Owner.SCROLL) gestures.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP) {
            if (owner == Owner.PENDING && tapEligible && listener.onUp(event)) tapDownTime = -1;
            owner = Owner.IDLE;
        }
    }

    void cancel() {
        owner = Owner.CONSUMED;
        tapEligible = false;
        tapDownTime = -1;
        cancelDetector();
    }

    void claimSelection() { cancel(); owner = Owner.SELECTION; }
    boolean isInProgress() { return owner == Owner.SCALE || scale.isInProgress(); }

    private void cancelDetector() {
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(downTime < 0 ? now : downTime, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        gestures.onTouchEvent(cancel);
        cancel.recycle();
    }
}
