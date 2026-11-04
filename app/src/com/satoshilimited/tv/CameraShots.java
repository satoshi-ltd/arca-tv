package com.satoshilimited.tv;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class CameraShots {
    static final long REFRESH_MS = 10_000, RETRY_MS = 5_000;
    private static final int WIDTH = 960;

    private static final class Entry {
        Bitmap bitmap;
        long takenAt, nextAt;
        boolean loading, failed;
    }

    private static final CameraShots INSTANCE = new CameraShots();

    static CameraShots get() { return INSTANCE; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Map<String, Entry> entries = new HashMap<>();

    private CameraShots() {}

    Bitmap bitmap(String id) {
        Entry e = entries.get(id);
        return e == null ? null : e.bitmap;
    }

    long age(String id, long now) {
        Entry e = entries.get(id);
        return e == null || e.bitmap == null ? Long.MAX_VALUE : Math.max(0, now - e.takenAt);
    }

    boolean failed(String id) {
        Entry e = entries.get(id);
        return e != null && e.failed;
    }

    void refresh(List<ArcaDomo.Device> cameras, Runnable changed) {
        long now = SystemClock.elapsedRealtime();
        for (ArcaDomo.Device d : cameras) {
            Entry e = entries.get(d.id);
            if (e == null) {
                e = new Entry();
                entries.put(d.id, e);
            }
            if (e.loading || now < e.nextAt) continue;
            e.loading = true;
            final Entry entry = e;
            final String id = d.id;
            io.execute(() -> {
                ArcaDomo.Shot shot = null;
                try {
                    shot = ArcaDomo.snapshot(id, WIDTH);
                } catch (Exception ex) {
                    AppLog.w("arca domo camera " + id + ": " + ex.getMessage());
                }
                final ArcaDomo.Shot result = shot;
                main.post(() -> {
                    long done = SystemClock.elapsedRealtime();
                    entry.loading = false;
                    if (result != null && result.bitmap != null) {
                        entry.bitmap = result.bitmap;
                        entry.takenAt = done - result.ageMs;
                        entry.nextAt = done + REFRESH_MS;
                        entry.failed = false;
                    } else {
                        entry.failed = true;
                        entry.nextAt = done + RETRY_MS;
                    }
                    changed.run();
                });
            });
        }
    }
}
