package com.satoshilimited.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.StringReader;
import java.net.URLEncoder;
import java.text.NumberFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class HubCounts {
    private HubCounts() {}

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean running = new AtomicBoolean();

    static String text(Context c, String kind) {
        return prefs(c).getString(kind, "");
    }

    static void refresh(Context c, Runnable changed) {
        Context app = c.getApplicationContext();
        if (!running.compareAndSet(false, true)) return;
        IO.execute(() -> {
            try {
                if (ArcaHub.has(app, "photos")) store(app, "photos", photos(app), changed);
                if (ArcaHub.has(app, "music")) store(app, "music", music(app), changed);
                if (ArcaHub.has(app, "video")) store(app, "video", media(app), changed);
            } finally {
                running.set(false);
            }
        });
    }

    static void forget(Context c) {
        prefs(c).edit().clear().apply();
    }

    private static void store(Context c, String kind, String value, Runnable changed) {
        if (value == null) return;
        prefs(c).edit().putString(kind, value).apply();
        MAIN.post(changed);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("hub-counts", Context.MODE_PRIVATE);
    }

    private static String photos(Context c) {
        try {
            String body = ArcaHub.get(c, "/v1/gallery?volume=" + URLEncoder.encode(ArcaHub.volume(c, "photos"), "UTF-8"), 4_000_000);
            JSONArray timeline = new JSONObject(body).optJSONArray("timeline");
            int all = 0, videos = 0;
            for (int i = 0; timeline != null && i < timeline.length(); i++) {
                JSONObject month = timeline.getJSONObject(i);
                all += month.optInt("count");
                videos += month.optInt("videos");
            }
            return join(count(Math.max(0, all - videos), "photo", "photos"), videos > 0 ? count(videos, "video", "videos") : "");
        } catch (Exception | OutOfMemoryError e) {
            AppLog.w("arca hub counts photos: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static String music(Context c) {
        try {
            String body = ArcaHub.get(c, "/v1/music/library?volume=" + URLEncoder.encode(ArcaHub.volume(c, "music"), "UTF-8"), 24_000_000);
            int songs = 0;
            try (JsonReader reader = new JsonReader(new StringReader(body))) {
                reader.beginObject();
                while (reader.hasNext()) {
                    if (!"tracks".equals(reader.nextName())) {
                        reader.skipValue();
                        continue;
                    }
                    reader.beginArray();
                    while (reader.hasNext()) songs += song(reader) ? 1 : 0;
                    reader.endArray();
                }
            }
            return count(songs, "song", "songs");
        } catch (Exception | OutOfMemoryError e) {
            AppLog.w("arca hub counts music: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean song(JsonReader reader) throws java.io.IOException {
        boolean hash = false, podcast = false;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (reader.peek() != JsonToken.STRING) {
                reader.skipValue();
            } else if ("hash".equals(name)) {
                hash = !reader.nextString().isEmpty();
            } else if ("genre".equals(name)) {
                podcast = "podcast".equalsIgnoreCase(reader.nextString());
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        return hash && !podcast;
    }

    private static String media(Context c) {
        try {
            VideoLibrary lib = VideoLibrary.fetch(c);
            String text = join(lib.movies.isEmpty() ? "" : count(lib.movies.size(), "movie", "movies"), lib.shows.isEmpty() ? "" : count(lib.shows.size(), "show", "shows"));
            return text.isEmpty() ? "Empty" : text;
        } catch (Exception | OutOfMemoryError e) {
            AppLog.w("arca hub counts media: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static String count(int n, String one, String many) {
        return NumberFormat.getIntegerInstance().format(n) + " " + (n == 1 ? one : many);
    }

    private static String join(String a, String b) {
        return a.isEmpty() ? b : b.isEmpty() ? a : a + " · " + b;
    }
}
