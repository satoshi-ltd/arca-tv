package com.satoshilimited.tv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

final class PhotoMotion {
    private PhotoMotion() {}

    static final long HOLD_MS = 8_000, FADE_MS = 1_200;
    private static final float LIFE_S = (HOLD_MS + 2 * FADE_MS) / 1000f, EASE_S = 1f, ZOOM = 0.04f;
    private static final float MAX_SPEED = 12f, PORTRAIT_SPEED = 10f, PORTRAIT_START = 1f / 3f;

    static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    static float progress(long ageMs) {
        float t = Math.max(0f, Math.min(LIFE_S, ageMs / 1000f)), span = LIFE_S - EASE_S, s;
        if (t < EASE_S) s = t * t / (2 * EASE_S);
        else if (t > LIFE_S - EASE_S) s = span - (LIFE_S - t) * (LIFE_S - t) / (2 * EASE_S);
        else s = t - EASE_S / 2;
        return s / span;
    }

    static void draw(Canvas c, Bitmap b, int width, int height, int alpha, long ageMs, int seed, Paint paint, RectF rect) {
        float p = progress(ageMs), k = width / 1920f, dir = seed % 2 == 0 ? 1 : -1;
        float cover = Math.max((float) width / b.getWidth(), (float) height / b.getHeight());
        float scale = cover * (1f + ZOOM * p), w = b.getWidth() * scale, h = b.getHeight() * scale;
        float oxEnd = b.getWidth() * cover * (1f + ZOOM) - width, oyEnd = b.getHeight() * cover * (1f + ZOOM) - height;
        float fx = 0.5f, fy = 0.5f, reach = MAX_SPEED * LIFE_S * k / 2;
        if (b.getHeight() > b.getWidth()) {
            fy = PORTRAIT_START + Math.min(0.5f - PORTRAIT_START, PORTRAIT_SPEED * LIFE_S * k / Math.max(1f, oyEnd)) * p;
        } else if (oyEnd > oxEnd) {
            fy = 0.5f + dir * (p - 0.5f) * 2 * Math.min(0.5f, reach / Math.max(1f, oyEnd));
        } else {
            fx = 0.5f + dir * (p - 0.5f) * 2 * Math.min(0.5f, reach / Math.max(1f, oxEnd));
        }
        float x = -(w - width) * fx, y = -(h - height) * fy;
        rect.set(x, y, x + w, y + h);
        paint.setAlpha(alpha);
        c.drawBitmap(b, null, rect, paint);
        paint.setAlpha(255);
    }
}
