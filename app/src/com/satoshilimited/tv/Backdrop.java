package com.satoshilimited.tv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

final class Backdrop {
    static final long FADE_MS = 600, DRIFT_MS = 48_000;
    private static final int SOFT_W = 96, SOFT_H = 54;

    private static final class Layer {
        Bitmap bitmap;
        boolean photo;
    }

    private final Map<Bitmap, Bitmap> softs = new WeakHashMap<>();
    private final Map<Object, Bitmap> washes = new HashMap<>();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG), scrim = new Paint();
    private final RectF rect = new RectF();
    private Layer from = new Layer(), to = new Layer();
    private Object token;
    private long changedAt;
    private int scrimHeight;
    private float scrimStrength = 1f;

    private void show(Object key, Bitmap bitmap, boolean photo) {
        token = key;
        if (bitmap == to.bitmap) return;
        from = to;
        to = new Layer();
        to.bitmap = bitmap;
        to.photo = photo;
        changedAt = SystemClock.uptimeMillis();
    }

    void showPhoto(Object key, Bitmap image) {
        if (key.equals(token)) return;
        show(key, image, true);
    }

    void showWash(Object key, int color, float xFraction) {
        showWash(key, color, xFraction, 0.85f, 1f);
    }

    /** yFraction positions the glow's centre (0 = top, 1 = bottom); spread scales its radius, for a full-bleed wash. */
    void showWash(Object key, int color, float xFraction, float yFraction, float spread) {
        if (key.equals(token)) return;
        Bitmap wash = washes.get(key);
        if (wash == null) {
            wash = wash(color, xFraction, yFraction, spread);
            washes.put(key, wash);
        }
        show(key, wash, false);
    }

    void clear() {
        token = null;
        if (to.bitmap == null) return;
        from = to;
        to = new Layer();
        changedAt = SystemClock.uptimeMillis();
    }

    void scrimStrength(float strength) {
        scrimStrength = strength;
    }

    boolean active() {
        return to.bitmap != null || from.bitmap != null && fading();
    }

    boolean fading() {
        return SystemClock.uptimeMillis() - changedAt < FADE_MS;
    }

    void draw(Canvas c, int w, int h, float alpha) {
        if (alpha <= 0.002f || !active()) return;
        long now = SystemClock.uptimeMillis();
        float mix = Math.min(1f, (now - changedAt) / (float) FADE_MS);
        mix = mix * mix * (3f - 2f * mix);
        boolean target = to.bitmap != null, fading = from.bitmap != null && mix < 1f;
        if (fading) drawLayer(c, w, h, from, target ? alpha : alpha * (1 - mix), now);
        if (target) drawLayer(c, w, h, to, fading || from.bitmap == null ? alpha * mix : alpha, now);
        float photo = (fading && from.photo ? 1 - mix : 0) + (target && to.photo ? mix : 0);
        if (photo <= 0.002f) return;
        if (scrimHeight != h) {
            scrimHeight = h;
            scrim.setShader(new LinearGradient(0, 0, 0, h, new int[] {0x9E0A0A0A, 0x990A0A0A, 0xDB0A0A0A}, new float[] {0, 0.38f, 1}, Shader.TileMode.CLAMP));
        }
        scrim.setAlpha(Math.round(255 * alpha * scrimStrength * Math.min(1f, photo)));
        c.drawRect(0, 0, w, h, scrim);
    }

    private void drawLayer(Canvas c, int w, int h, Layer layer, float alpha, long now) {
        double phase = (now % DRIFT_MS) / (double) DRIFT_MS * 2 * Math.PI;
        float zoom = 1.14f + 0.05f * (float) Math.sin(phase);
        float dx = (float) Math.sin(phase) * w * 0.05f, dy = (float) Math.cos(phase) * h * 0.04f;
        paint.setAlpha(Math.round(255 * Math.min(1f, alpha)));
        c.save();
        c.translate(dx, dy);
        c.scale(zoom, zoom, w / 2f, h / 2f);
        rect.set(0, 0, w, h);
        c.drawBitmap(layer.photo ? soft(layer.bitmap) : layer.bitmap, null, rect, paint);
        c.restore();
        paint.setAlpha(255);
    }

    private Bitmap soft(Bitmap source) {
        Bitmap b = softs.get(source);
        if (b == null) {
            Bitmap step = source;
            while (step.getWidth() > SOFT_W * 2) step = Bitmap.createScaledBitmap(step, step.getWidth() / 2, Math.max(1, step.getHeight() / 2), true);
            b = blur(Bitmap.createScaledBitmap(step, SOFT_W, SOFT_H, true), 4);
            softs.put(source, b);
        }
        return b;
    }

    static Bitmap blur(Bitmap source, int radius) {
        int w = source.getWidth(), h = source.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        source.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            box(px, tmp, w, h, radius, true);
            box(tmp, px, w, h, radius, false);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, length = horizontal ? w : h;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < length; i++) {
                int ra = 0, ga = 0, ba = 0, n = 0;
                for (int j = Math.max(0, i - r); j <= Math.min(length - 1, i + r); j++) {
                    int p = horizontal ? in[line * w + j] : in[j * w + line];
                    ra += (p >> 16) & 0xFF;
                    ga += (p >> 8) & 0xFF;
                    ba += p & 0xFF;
                    n++;
                }
                int v = 0xFF000000 | (ra / n << 16) | (ga / n << 8) | ba / n;
                if (horizontal) out[line * w + i] = v;
                else out[i * w + line] = v;
            }
        }
    }

    static Bitmap wash(int color, float xFraction) {
        return wash(color, xFraction, 0.85f, 1f);
    }

    static Bitmap wash(int color, float xFraction, float yFraction, float spread) {
        Bitmap b = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888);
        Canvas bc = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, 27, 0xFF141414, 0xFF0A0A0A, Shader.TileMode.CLAMP));
        bc.drawRect(0, 0, 48, 27, p);
        float lum = (0.299f * ((color >> 16) & 0xFF) + 0.587f * ((color >> 8) & 0xFF) + 0.114f * (color & 0xFF)) / 255f;
        float dim = lum > 0.35f ? 0.35f / lum : 1f;
        int rgb = ((int) (((color >> 16) & 0xFF) * dim) << 16) | ((int) (((color >> 8) & 0xFF) * dim) << 8) | (int) ((color & 0xFF) * dim);
        RadialGradient glow = new RadialGradient(0, 0, 1, new int[] {rgb | 0x88000000, rgb | 0x44000000, 0xCC0A0A0A}, new float[] {0, 0.48f, 1}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.postScale(0.85f * 48 * spread, 0.9f * 27 * spread);
        m.postTranslate(xFraction * 48, yFraction * 27);
        glow.setLocalMatrix(m);
        p.setShader(glow);
        bc.drawRect(0, 0, 48, 27, p);
        return blur(Bitmap.createScaledBitmap(b, 96, 54, true), 3);
    }
}
