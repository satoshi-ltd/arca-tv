package com.satoshilimited.tv;

import android.content.Intent;
import android.graphics.RectF;
import android.os.SystemClock;

final class Reveal {
    static final String EXTRA = "com.satoshilimited.tv.reveal";
    static final long MS = 240;

    private final float[] from;
    private long start = -1;
    private boolean back;

    Reveal(Intent intent) {
        from = intent == null ? null : intent.getFloatArrayExtra(EXTRA);
    }

    static void put(Intent intent, RectF rect, float radius) {
        intent.putExtra(EXTRA, new float[] {rect.left, rect.top, rect.right, rect.bottom, radius});
    }

    boolean present() {
        return from != null;
    }

    void reverse() {
        if (from == null) return;
        back = true;
        start = -1;
    }

    float progress() {
        if (from == null) return 1f;
        long now = SystemClock.uptimeMillis();
        if (start < 0) start = now;
        float t = Math.min(1f, (now - start) / (float) MS);
        float eased = 1f - (1f - t) * (1f - t) * (1f - t);
        return back ? 1f - eased : eased;
    }

    boolean running() {
        if (from == null) return false;
        float p = progress();
        return back ? p > 0f : p < 1f;
    }

    float rect(RectF out, float width, float height) {
        float p = progress();
        if (from == null) {
            out.set(0, 0, width, height);
            return 0f;
        }
        out.set(from[0] + (0 - from[0]) * p, from[1] + (0 - from[1]) * p, from[2] + (width - from[2]) * p, from[3] + (height - from[3]) * p);
        return from[4] * (1f - p);
    }
}
