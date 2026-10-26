package com.satoshilimited.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

final class DreamView extends View implements Choreographer.FrameCallback, Watching.Listener, HomeData.Listener, ArcaDomo.Listener {
    private static final long HOLD_MS = 40_000, FADE_MS = 8_000, FRAME_MS = 66;
    private static final int[][] PALETTE = {{0xFF3A2A2A, 0xFF121010}, {0xFF1F2A3F, 0xFF0B0E14}, {0xFF1F3A33, 0xFF0A1210}, {0xFF362A45, 0xFF100D16}};

    private final Watching watching;
    private final HomeData data;
    private final Header header;
    private final float k;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final Backdrop backdrop = new Backdrop();
    private PhotoSlideshow show;
    private Bitmap headerBitmap;
    private final Canvas headerCanvas = new Canvas();
    private final Paint headerPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private boolean headerDirty = true;
    private long startedAt, lastFrame;
    private boolean running;
    private String clockText = "", meridiemText = "", dateText = "";
    private long clockMinute = -1;
    private boolean dim;

    DreamView(Context c) {
        super(c);
        watching = Watching.get(c);
        data = HomeData.get(c);
        k = c.getResources().getDisplayMetrics().widthPixels / 1920f;
        header = new Header(k, getContext());
        setBackgroundColor(0xFF000000);
        headerPaint.setAlpha(200);
    }

    void start() {
        if (running) return;
        running = true;
        startedAt = SystemClock.uptimeMillis();
        watching.addListener(this);
        data.addListener(this);
        ArcaDomo.get().start(this);
        if (PhotoStream.available(getContext())) show = new PhotoSlideshow(getContext());
        watching.refresh();
        data.refreshIfStale();
        Choreographer.getInstance().postFrameCallback(this);
    }

    void stop() {
        if (!running) return;
        running = false;
        if (show != null) {
            show.close();
            show = null;
        }
        watching.removeListener(this);
        data.removeListener(this);
        ArcaDomo.get().stop(this);
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override public void onWatching() {
    }

    @Override public void onData() {
        headerDirty = true;
        invalidate();
    }

    @Override public void onDomo() {
        headerDirty = true;
        invalidate();
    }

    @Override public void doFrame(long nanos) {
        if (!running) return;
        long now = SystemClock.uptimeMillis();
        if (show != null && show.ready() || now - lastFrame >= FRAME_MS) {
            lastFrame = now;
            invalidate();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void updateClock(long now) {
        long minute = now / 60_000L;
        if (minute == clockMinute) return;
        clockMinute = minute;
        headerDirty = true;
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        dim = hour >= 22 || hour < 6;
        clockText = Ui.clock(now);
        meridiemText = Ui.meridiem(now);
        dateText = Ui.date(now);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        long elapsed = SystemClock.uptimeMillis() - startedAt;
        if (show != null && show.ready()) show.draw(c, w, h, true);
        else drawBackground(c, w, h, elapsed);
        long now = System.currentTimeMillis();
        updateClock(now);
        drawHeader(c, w, h);
        if (dim) {
            fill.setShader(null);
            fill.setColor(0x99000000);
            c.drawRect(0, 0, w, h, fill);
        }
    }

    private void drawHeader(Canvas c, int w, int h) {
        int height = Math.round(280 * w / 1920f);
        if (headerBitmap == null || headerBitmap.getWidth() != w) {
            headerBitmap = Bitmap.createBitmap(w, height, Bitmap.Config.ARGB_8888);
            headerCanvas.setBitmap(headerBitmap);
            headerDirty = true;
        }
        if (headerDirty) {
            headerDirty = false;
            headerBitmap.eraseColor(0);
            header.draw(headerCanvas, clockText, meridiemText, dateText, data.weather, ArcaDomo.get().insideLabel());
        }
        c.drawBitmap(headerBitmap, 0, 0, headerPaint);
    }

    private void drawBackground(Canvas c, int w, int h, long elapsed) {
        List<Watching.Card> items = new ArrayList<>();
        for (Watching.Card it : watching.items()) if (it.image != null) items.add(it);
        int count = items.isEmpty() ? PALETTE.length : items.size();
        int index = (int) (elapsed / (HOLD_MS + FADE_MS) % count);
        if (items.isEmpty()) backdrop.showWash("palette" + index, PALETTE[index][0], 0.3f);
        else backdrop.showPhoto(items.get(index).id, items.get(index).image);
        backdrop.draw(c, w, h, 1f);
    }
}
