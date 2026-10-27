package com.satoshilimited.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;

final class PhotoSlideshow {
    private final PhotoSource stream;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG), scrim = new Paint(), bottomScrim = new Paint(), caption = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private Bitmap current, previous;
    private PhotoStream.Photo pending;
    private String date = "", previousDate = "", hash = "";
    private boolean video;
    private long currentAt, previousAt, fadeAt, pausedAt;
    private int seed, scrimHeight;
    private boolean loading, failed, paused;

    PhotoSlideshow(Context c) {
        this(new PhotoStream(c, "large"));
    }

    PhotoSlideshow(PhotoSource source) {
        stream = source;
        caption.setTypeface(Ui.regular);
        caption.setShadowLayer(8, 0, 2, 0xAA000000);
        loading = true;
        stream.next(1, (photo, error) -> {
            loading = false;
            if (photo == null) failed = true;
            else show(photo);
        });
    }

    boolean ready() {
        return current != null;
    }

    boolean failed() {
        return failed;
    }

    boolean paused() {
        return paused;
    }

    boolean currentVideo() {
        return video;
    }

    String currentHash() {
        return hash;
    }

    Bitmap currentBitmap() {
        return current;
    }

    void settle() {
        long now = SystemClock.uptimeMillis();
        currentAt = now;
        fadeAt = now;
        if (paused) pausedAt = now;
    }

    void close() {
        stream.close();
    }

    void step(int dir) {
        if (dir > 0 && pending != null) {
            show(pending);
            return;
        }
        if (loading) return;
        loading = true;
        stream.next(dir, (photo, error) -> {
            loading = false;
            if (photo != null) show(photo);
        });
    }

    void togglePause() {
        long now = SystemClock.uptimeMillis();
        if (paused) {
            long gap = now - pausedAt;
            currentAt += gap;
            previousAt += gap;
            fadeAt += gap;
        } else {
            pausedAt = now;
        }
        paused = !paused;
    }

    private void show(PhotoStream.Photo photo) {
        long now = SystemClock.uptimeMillis();
        previous = current;
        previousAt = currentAt;
        current = photo.bitmap;
        previousDate = date;
        date = photo.date;
        hash = photo.hash;
        video = photo.video;
        currentAt = now;
        fadeAt = now;
        pending = null;
        seed++;
    }

    private void advance(long now) {
        if (paused || current == null || video) return;
        long age = now - currentAt;
        if (pending == null && !loading) {
            loading = true;
            stream.next(1, (photo, error) -> {
                loading = false;
                if (photo != null) pending = photo;
            });
        }
        if (pending != null && age >= PhotoMotion.HOLD_MS + PhotoMotion.FADE_MS) show(pending);
    }

    void draw(Canvas c, int width, int height, boolean shade) {
        draw(c, width, height, shade, false);
    }

    void draw(Canvas c, int width, int height, boolean shade, boolean bottomOnly) {
        long now = paused ? pausedAt : SystemClock.uptimeMillis();
        advance(SystemClock.uptimeMillis());
        if (current == null) return;
        float fade = PhotoMotion.ease((now - fadeAt) / (float) PhotoMotion.FADE_MS);
        if (previous != null && fade < 1f) PhotoMotion.draw(c, previous, width, height, 255, now - previousAt, seed - 1, paint, dst);
        PhotoMotion.draw(c, current, width, height, previous == null ? 255 : Math.round(255 * fade), now - currentAt, seed, paint, dst);
        if (shade || bottomOnly) {
            if (scrimHeight != height) {
                scrimHeight = height;
                scrim.setShader(new LinearGradient(0, 0, 0, height, new int[] {0x99000000, 0x14000000, 0x00000000, 0x66000000}, new float[] {0, 0.3f, 0.6f, 1}, Shader.TileMode.CLAMP));
                bottomScrim.setShader(new LinearGradient(0, height - 220 * width / 1920f, 0, height, 0x00000000, 0xB3000000, Shader.TileMode.CLAMP));
            }
            if (bottomOnly) c.drawRect(0, height - 220 * width / 1920f, width, height, bottomScrim);
            else c.drawRect(0, 0, width, height, scrim);
        }
        float k = width / 1920f, base = height - 84 * k, lift = 12 * k;
        caption.setTextSize(28 * k);
        caption.setColor(0xFFFFFFFF);
        if (previous != null && fade < 1f && !previousDate.isEmpty()) {
            caption.setAlpha(Math.round(190 * (1f - fade)));
            c.drawText(previousDate, 112 * k, base - lift * fade, caption);
        }
        if (!date.isEmpty()) {
            caption.setAlpha(Math.round(190 * fade));
            c.drawText(date, 112 * k, base + lift * (1f - fade), caption);
        }
    }
}
