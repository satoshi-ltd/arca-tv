package com.satoshilimited.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Watching {
    static final int LIMIT = 8;

    static final class Card {
        String id, title, sub, tag;
        float progress;
        boolean next, page;
        Bitmap image;
    }

    interface Listener { void onWatching(); }

    private static Watching instance;

    static synchronized Watching get(Context c) {
        if (instance == null) instance = new Watching(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile List<Card> cards = new ArrayList<>();
    private volatile boolean fetching;
    private volatile long generation;
    private volatile long fetchedAt, failedAt;

    static synchronized void forget() {
        if (instance == null) return;
        instance.generation++;
        instance.cards = new ArrayList<>();
        instance.fetchedAt = instance.failedAt = 0;
        instance.notifyListeners();
    }

    private Watching(Context c) {
        context = c;
        VideoLibrary.addListener(() -> {
            VideoLibrary lib = VideoLibrary.current();
            if (lib != null && enabled(context)) io.execute(() -> build(lib));
        });
    }

    static boolean enabled(Context c) {
        return ArcaHub.has(c, "video");
    }

    List<Card> items() {
        return enabled(context) ? cards : new ArrayList<>();
    }

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onWatching(); });
    }

    void refresh() {
        if (!enabled(context) || fetching) return;
        long now = System.currentTimeMillis();
        if (now >= fetchedAt && now - fetchedAt < 3_000L) return;
        if (failedAt != 0 && now >= failedAt && now - failedAt < 10_000L) return;
        fetching = true;
        io.execute(() -> {
            try {
                VideoLibrary lib = VideoLibrary.fetch(context);
                build(lib);
                fetchedAt = System.currentTimeMillis();
            } catch (Exception | OutOfMemoryError e) {
                failedAt = System.currentTimeMillis();
                AppLog.w("arca video continue: " + e.getClass().getSimpleName());
                VideoLibrary cached = VideoLibrary.current();
                if (cached != null && cards.isEmpty()) build(cached);
            } finally {
                fetching = false;
            }
        });
    }

    private void build(VideoLibrary lib) {
        long requestGeneration = generation;
        if (VideoLibrary.current() != lib) return;
        Map<String, Card> known = new HashMap<>();
        for (Card old : cards) if (old.image != null) known.put(old.id, old);
        List<Card> fresh = new ArrayList<>();
        List<String> art = new ArrayList<>();
        VideoLibrary.Watch row = lib.watchingRow();
        for (VideoLibrary.Item v : row.items) {
            if (fresh.size() >= LIMIT) break;
            if (!row.page(v) && !v.playable()) continue;
            boolean next = !row.resume(v);
            Card it = new Card();
            it.id = v.id;
            it.title = row.title(v);
            it.sub = row.caption(v);
            it.next = next;
            it.page = row.page(v);
            it.tag = row.tag(v);
            it.progress = row.progress(v);
            Card old = known.get(it.id);
            if (old != null) it.image = old.image;
            fresh.add(it);
            art.add(row.art(v));
        }
        synchronized (Watching.class) {
            if (requestGeneration != generation) return;
            cards = fresh;
        }
        notifyListeners();
        for (int i = 0; i < fresh.size(); i++) {
            Card it = fresh.get(i);
            String key = art.get(i);
            if (it.image != null || key.isEmpty()) continue;
            it.image = VideoArt.get(context, key, VideoArt.SMALL, () -> {
                it.image = VideoArt.peek(key, VideoArt.SMALL);
                notifyListeners();
            });
        }
        notifyListeners();
    }

    void hide(String id, Runnable failed) {
        VideoLibrary lib = VideoLibrary.current();
        if (lib == null || !lib.hidden(id)) return;
        io.execute(() -> {
            try {
                ArcaHub.post(context, "/v1/video/watch-next/remove", new JSONObject().put("volume", lib.volume).put("id", id));
            } catch (Exception e) {
                AppLog.w("arca video remove: " + e.getMessage());
                main.post(() -> {
                    lib.restored(id);
                    if (failed != null) failed.run();
                });
            }
        });
    }

    boolean details(Activity a, Card it) {
        try {
            a.startActivity(new Intent(a, HubMoviesActivity.class).putExtra(HubMoviesActivity.OPEN, it.id));
            return true;
        } catch (RuntimeException e) {
            AppLog.w("couldn't open: " + e.getMessage());
            return false;
        }
    }

    boolean open(Activity a, Card it) {
        if (it.page) return details(a, it);
        try {
            a.startActivity(new Intent(a, VideoPlayerActivity.class).putExtra(VideoPlayerActivity.ID, it.id).putExtra(VideoPlayerActivity.FROM_START, it.next));
            return true;
        } catch (RuntimeException e) {
            AppLog.w("couldn't play: " + e.getMessage());
            return false;
        }
    }

    String failure() {
        return "Couldn't play this video";
    }
}
