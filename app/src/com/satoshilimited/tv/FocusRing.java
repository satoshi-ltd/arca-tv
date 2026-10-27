package com.satoshilimited.tv;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;

import java.util.Objects;

final class FocusRing {
    static final long MS = 180, HELD_MS = 120, BUMP_MS = 120;
    private static final float LEAD = 0.7f, DRIFT = 40, BUMP = 6;

    private final View view;
    private final RectF from = new RectF(), to = new RectF(), now = new RectF();
    private float fromRadius, toRadius, radius, bumpX, bumpY;
    private long start, duration = MS, bumpStart = -1, pressedAt = -1, frames, placedFrame = -1;
    private int pressX, pressY;
    private boolean ready, stretch;
    private Object group, identity;
    private final ViewTreeObserver.OnDrawListener counter = () -> frames++;

    FocusRing(View view) {
        this.view = view;
        if (view == null) return;
        if (view.isAttachedToWindow()) view.getViewTreeObserver().addOnDrawListener(counter);
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                v.getViewTreeObserver().addOnDrawListener(counter);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.getViewTreeObserver().removeOnDrawListener(counter);
                ready = false;
            }
        });
    }

    void at(Object id) {
        if (!Objects.equals(id, identity)) pressedAt = -1;
        identity = id;
    }

    boolean current(RectF out) {
        if (!ready || now.isEmpty()) return false;
        out.set(now);
        return true;
    }

    void snap() {
        ready = false;
    }

    void bump(int dx, int dy) {
        bumpX = Math.signum(dx);
        bumpY = Math.signum(dy);
        bumpStart = SystemClock.uptimeMillis();
        if (view != null) view.postInvalidateOnAnimation();
    }

    void pressed(int dx, int dy) {
        pressX = dx;
        pressY = dy;
        pressedAt = SystemClock.uptimeMillis();
        if (view != null) view.postInvalidateOnAnimation();
    }

    void key(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                pressed(-1, 0);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                pressed(1, 0);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                pressed(0, -1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                pressed(0, 1);
                break;
            default:
                break;
        }
    }

    boolean moving() {
        long t = SystemClock.uptimeMillis();
        return ready && (t - start < duration || bumpStart >= 0 && t - bumpStart < BUMP_MS);
    }

    void ring(Canvas c, RectF target, float targetRadius, Object key, float k, Paint stroke) {
        place(target, targetRadius, key, k);
        Glass.ring(c, now, radius, k, stroke);
    }

    void row(Canvas c, RectF target, Object key, float k, Paint fill, Paint stroke) {
        place(target, Glass.ROW_RADIUS * k, key, k);
        Glass.focus(c, now, radius, k, fill, stroke);
    }

    void focus(Canvas c, RectF target, float targetRadius, Object key, float k, Paint fill, Paint stroke) {
        place(target, targetRadius, key, k);
        Glass.focus(c, now, radius, k, fill, stroke);
    }

    void oval(Canvas c, RectF target, Object key, float k, Paint stroke) {
        place(target, target.height() / 2, key, k);
        Glass.ringOval(c, now, k, stroke);
    }

    RectF place(RectF target, float targetRadius, Object key, float k) {
        long t = SystemClock.uptimeMillis();
        float far = view != null && view.getWidth() > 0 ? Math.max(view.getWidth(), view.getHeight()) * 0.55f : 1056 * k;
        boolean jump = Math.abs(target.centerX() - to.centerX()) > far || Math.abs(target.centerY() - to.centerY()) > far;
        boolean unseen = view != null && placedFrame >= 0 && frames - placedFrame > 1;
        if (!ready || !Objects.equals(key, group) || unseen || jump) {
            from.set(target);
            to.set(target);
            now.set(target);
            fromRadius = toRadius = radius = targetRadius;
            start = t - duration;
            group = key;
            ready = true;
            pressedAt = -1;
        } else if (!to.equals(target) || toRadius != targetRadius) {
            float dl = target.left - to.left, dt = target.top - to.top;
            boolean drift = Math.abs(dl) < DRIFT * k && Math.abs(dt) < DRIFT * k
                    && Math.abs(target.width() - to.width()) < 1 && Math.abs(target.height() - to.height()) < 1 && toRadius == targetRadius;
            if (drift) {
                from.offset(dl, dt);
                to.offset(dl, dt);
                pressedAt = -1;
            } else {
                boolean held = t - start < duration;
                from.set(now);
                fromRadius = radius;
                to.set(target);
                toRadius = targetRadius;
                start = t;
                duration = held ? HELD_MS : MS;
                stretch = !held;
            }
        }
        if (pressedAt >= 0) {
            if (start >= pressedAt) pressedAt = -1;
            else if (t - pressedAt > 60) {
                pressedAt = -1;
                if (pressX != 0 || pressY != 0) bump(pressX, pressY);
            } else if (view != null) view.postInvalidateOnAnimation();
        }
        placedFrame = frames;
        float p = Math.min(1f, (t - start) / (float) duration);
        float trail = stretch ? ease(p) : p, lead = stretch ? ease(Math.min(1f, p / LEAD)) : p;
        float dx = to.centerX() - from.centerX(), dy = to.centerY() - from.centerY();
        now.left = lerp(from.left, to.left, dx < 0 ? lead : trail);
        now.right = lerp(from.right, to.right, dx > 0 ? lead : trail);
        now.top = lerp(from.top, to.top, dy < 0 ? lead : trail);
        now.bottom = lerp(from.bottom, to.bottom, dy > 0 ? lead : trail);
        radius = lerp(fromRadius, toRadius, trail);
        boolean bumping = bumpStart >= 0 && t - bumpStart < BUMP_MS;
        if (bumping) {
            float q = (float) Math.sin(Math.PI * (t - bumpStart) / BUMP_MS) * BUMP * k;
            now.offset(bumpX * q, bumpY * q);
        } else {
            bumpStart = -1;
        }
        if ((p < 1f || bumping) && view != null) view.postInvalidateOnAnimation();
        return now;
    }

    float radius() {
        return radius;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    static float ease(float x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        float t = x;
        for (int i = 0; i < 6; i++) {
            float bx = bezier(t, 0.2f, 0.2f) - x, d = slope(t, 0.2f, 0.2f);
            if (Math.abs(d) < 1e-5f) break;
            t -= bx / d;
            t = Math.max(0, Math.min(1, t));
        }
        return bezier(t, 0.8f, 1f);
    }

    private static float bezier(float t, float p1, float p2) {
        float u = 1 - t;
        return 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t;
    }

    private static float slope(float t, float p1, float p2) {
        float u = 1 - t;
        return 3 * u * u * p1 + 6 * u * t * (p2 - p1) + 3 * t * t * (1 - p2);
    }
}
