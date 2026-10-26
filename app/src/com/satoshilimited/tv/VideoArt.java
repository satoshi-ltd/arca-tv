package com.satoshilimited.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.LruCache;

import org.json.JSONObject;

import java.io.File;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class VideoArt {
    private VideoArt() {}

    static final int SMALL = 0, LARGE = 1;
    private static final int RETRIES = 6, RETRY_MS = 4000;
    private static final long MAX_DISK = 200L * 1024 * 1024;
    private static final int[] WIDTH = {480, 1280};

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newFixedThreadPool(3);
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(28 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }

        @Override protected void entryRemoved(boolean evicted, String key, Bitmap old, Bitmap fresh) {
            if (evicted) asked.remove(key);
        }
    };
    private static final Set<String> asked = new HashSet<>(), missing = new HashSet<>();
    private static final Map<String, List<Done>> waiting = new HashMap<>();
    private static final Map<String, Integer> tries = new HashMap<>();
    private static volatile long generation;

    interface Done { void loaded(); }

    static synchronized Bitmap peek(String key, int size) {
        return key == null || key.isEmpty() ? null : MEMORY.get(id(key, size));
    }

    static synchronized boolean absent(String key, int size) {
        return key == null || key.isEmpty() || missing.contains(id(key, size));
    }

    static Bitmap get(Context c, String key, int size, Done done) {
        if (key == null || !key.matches("[a-f0-9]{64}") || (size != SMALL && size != LARGE)) return null;
        long requestGeneration = generation;
        String id = id(key, size);
        synchronized (VideoArt.class) {
            if (requestGeneration != generation) return null;
            Bitmap b = MEMORY.get(id);
            if (b != null || missing.contains(id)) return b;
            if (!asked.add(id)) {
                if (done != null) {
                    List<Done> list = waiting.get(id);
                    if (list == null) waiting.put(id, list = new ArrayList<>());
                    if (!list.contains(done)) list.add(done);
                }
                return null;
            }
        }
        load(c.getApplicationContext(), key, size, id, done, requestGeneration);
        return null;
    }

    static synchronized void forget(Context c) {
        generation++;
        File[] files = new File(c.getCacheDir(), "video-art").listFiles();
        if (files != null) for (File f : files) f.delete();
        MEMORY.evictAll();
        asked.clear();
        missing.clear();
        tries.clear();
        waiting.clear();
    }

    private static String id(String key, int size) {
        return size + "|" + key;
    }

    private static void load(Context c, String key, int size, String id, Done done, long requestGeneration) {
        IO.execute(() -> {
            if (requestGeneration != generation) return;
            Bitmap decoded = null;
            boolean retry = false, unavailable = false, offline = false;
            try {
                File f = file(c, key, size);
                byte[] raw = f.exists() ? Files.readAllBytes(f.toPath()) : null;
                if (raw == null) {
                    String volume = ArcaHub.volume(c, "video");
                    String body = ArcaHub.get(c, "/v1/video/art?volume=" + URLEncoder.encode(volume, "UTF-8") + "&key=" + URLEncoder.encode(key, "UTF-8")
                            + "&size=" + (size == LARGE ? "large" : "small"), 12_000_000);
                    JSONObject o = new JSONObject(body);
                    String data = o.optString("data", "");
                    int comma = data.indexOf(',');
                    if (comma > 0) {
                        raw = Base64.decode(data.substring(comma + 1), Base64.DEFAULT);
                        synchronized (VideoArt.class) {
                            if (requestGeneration != generation) return;
                            File tmp = new File(f.getPath() + ".tmp");
                            Files.write(tmp.toPath(), raw);
                            if (!tmp.renameTo(f)) tmp.delete();
                            trim(f.getParentFile());
                        }
                    } else if (o.optBoolean("retry")) {
                        retry = true;
                    } else {
                        unavailable = true;
                    }
                }
                if (raw != null) {
                    decoded = decode(raw, WIDTH[size]);
                    if (decoded == null) {
                        file(c, key, size).delete();
                        unavailable = true;
                    }
                }
            } catch (Exception e) {
                AppLog.w("arca video art: " + e.getMessage());
                retry = true;
                offline = true;
            }
            final Bitmap result = decoded;
            final boolean again = retry && result == null, gone = unavailable && result == null, lost = offline;
            synchronized (VideoArt.class) {
                if (requestGeneration != generation) return;
                if (result != null) MEMORY.put(id, result);
                if (gone) missing.add(id);
                if (again) {
                    int n = tries.containsKey(id) ? tries.get(id) : 0;
                    tries.put(id, n + 1);
                    if (n + 1 >= RETRIES && !lost) missing.add(id);
                }
                if (again) asked.remove(id);
            }
            List<Done> others;
            synchronized (VideoArt.class) {
                if (requestGeneration != generation) return;
                others = waiting.remove(id);
            }
            if (result != null) {
                MAIN.post(() -> {
                    if (requestGeneration != generation) return;
                    if (done != null) done.loaded();
                    if (others != null) for (Done d : others) d.loaded();
                });
            }
            else if (again) MAIN.postDelayed(() -> {
                if (requestGeneration != generation) return;
                get(c, key, size, done);
                if (others != null) for (Done d : others) get(c, key, size, d);
            }, RETRY_MS);
        });
    }

    private static Bitmap decode(byte[] raw, int width) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= width) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
    }

    private static synchronized void trim(File dir) {
        File[] files = dir.listFiles((parent, name) -> name.endsWith(".jpg"));
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= MAX_DISK) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= MAX_DISK * 3 / 4) break;
            long length = f.length();
            if (f.delete()) total -= length;
        }
    }

    private static File file(Context c, String key, int size) {
        File dir = new File(c.getCacheDir(), "video-art");
        dir.mkdirs();
        trim(dir);
        return new File(dir, key + "-" + size + ".jpg");
    }
}
