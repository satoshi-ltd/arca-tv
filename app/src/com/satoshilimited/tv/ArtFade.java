package com.satoshilimited.tv;

import android.os.SystemClock;

import java.util.LinkedHashMap;
import java.util.Map;

final class ArtFade {
    static final long MS = 200;
    private static final int KEEP = 512;
    private static final Map<String, Long> arrived = new LinkedHashMap<String, Long>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > KEEP;
        }
    };

    private ArtFade() {
    }

    static synchronized void arrived(String key) {
        if (key != null && !key.isEmpty()) arrived.put(key, SystemClock.uptimeMillis());
    }

    static synchronized float alpha(String key) {
        Long at = key == null ? null : arrived.get(key);
        if (at == null) return 1f;
        float t = (SystemClock.uptimeMillis() - at) / (float) MS;
        if (t >= 1f) {
            arrived.remove(key);
            return 1f;
        }
        return Math.max(0f, t);
    }

    static synchronized boolean settling() {
        long now = SystemClock.uptimeMillis();
        for (long at : arrived.values()) if (now - at < MS) return true;
        return false;
    }
}
