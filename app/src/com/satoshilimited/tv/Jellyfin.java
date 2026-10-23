package com.satoshilimited.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Jellyfin {
    static final String PACKAGE = "org.jellyfin.androidtv";
    private static final String START = "org.jellyfin.androidtv.ui.startup.StartupActivity";
    static final int LIMIT = 8;

    interface Listener { void onJellyfin(); }

    static final class Item {
        String id, title, sub, seriesId = "";
        float progress;
        long playedAt;
        Bitmap image;
    }

    private static Jellyfin instance;

    static synchronized Jellyfin get(Context c) {
        if (instance == null) instance = new Jellyfin(c.getApplicationContext());
        return instance;
    }

    volatile List<Item> items = new ArrayList<>();

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile boolean fetching;
    private volatile long fetchedAt, failedAt;

    private Jellyfin(Context c) {
        context = c;
    }

    static boolean enabled() {
        return configured() && Prefs.continueSource().equals("jellyfin");
    }

    static boolean configured() {
        return !Prefs.jellyfinUrl().isEmpty() && !Prefs.jellyfinToken().isEmpty() && !Prefs.jellyfinUserId().isEmpty();
    }

    void reset() {
        items = new ArrayList<>();
        fetchedAt = 0;
        failedAt = 0;
        notifyListeners();
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    void refresh() {
        if (!enabled() || fetching) return;
        long now = System.currentTimeMillis();
        if (now >= fetchedAt && now - fetchedAt < 3_000L) return;
        if (failedAt != 0 && now >= failedAt && now - failedAt < 10_000L) return;
        fetching = true;
        io.execute(() -> {
            try {
                load();
                fetchedAt = System.currentTimeMillis();
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                AppLog.w("jellyfin: " + e.getMessage());
            } finally {
                fetching = false;
            }
        });
    }

    private void load() throws Exception {
        String userId = Prefs.jellyfinUserId();
        JSONArray resume = new JSONObject(text(url("/UserItems/Resume?userId=" + userId + "&limit=" + LIMIT + "&mediaTypes=Video"))).getJSONArray("Items");
        JSONArray next = new JSONObject(text(url("/Shows/NextUp?userId=" + userId + "&limit=" + LIMIT + "&enableResumable=false"))).getJSONArray("Items");
        java.util.Set<String> series = new java.util.HashSet<>();
        List<Item> all = new ArrayList<>();
        java.util.Map<Item, String> imagePaths = new java.util.HashMap<>();
        collect(resume, false, series, all, imagePaths);
        List<Item> nextItems = new ArrayList<>();
        collect(next, true, series, nextItems, imagePaths);
        for (Item it : nextItems) {
            if (!it.seriesId.isEmpty()) it.playedAt = lastPlayed(it.seriesId);
            all.add(it);
        }
        java.util.Collections.sort(all, (x, y) -> Long.compare(y.playedAt, x.playedAt));
        List<Item> fresh = new ArrayList<>(all.subList(0, Math.min(all.size(), LIMIT)));
        List<String> images = new ArrayList<>();
        for (Item it : fresh) images.add(imagePaths.get(it));
        java.util.Map<String, Bitmap> known = new java.util.HashMap<>();
        for (Item old : items) if (old.image != null) known.put(old.id, old.image);
        for (Item it : fresh) it.image = known.get(it.id);
        items = fresh;
        notifyListeners();
        for (int i = 0; i < fresh.size(); i++) {
            if (fresh.get(i).image != null) continue;
            try {
                byte[] data = bytes(url(images.get(i)));
                fresh.get(i).image = BitmapFactory.decodeByteArray(data, 0, data.length);
            } catch (Exception e) {
                AppLog.w("jellyfin image: " + e.getMessage());
            }
            notifyListeners();
        }
    }

    private void collect(JSONArray list, boolean upNext, java.util.Set<String> series, List<Item> out, java.util.Map<Item, String> imagePaths) throws Exception {
        for (int i = 0; i < list.length() && out.size() < 2 * LIMIT; i++) {
            JSONObject o = list.getJSONObject(i);
            String seriesId = o.optString("SeriesId", "");
            boolean episode = "Episode".equals(o.optString("Type"));
            if (episode && !seriesId.isEmpty() && !series.add(seriesId)) continue;
            Item it = new Item();
            it.id = o.getString("Id");
            long total = o.optLong("RunTimeTicks", 0) / 10_000_000L;
            JSONObject data = o.optJSONObject("UserData");
            long position = data == null ? 0 : data.optLong("PlaybackPositionTicks", 0) / 10_000_000L;
            it.progress = total > 0 ? Math.min(1f, (float) position / total) : 0;
            it.seriesId = episode ? seriesId : "";
            if (data != null) it.playedAt = parseDate(data.optString("LastPlayedDate", ""));
            if (episode) {
                it.title = o.optString("SeriesName", o.optString("Name"));
                it.sub = (upNext && position == 0 ? "Next · " : "") + "S" + o.optInt("ParentIndexNumber") + " · E" + o.optInt("IndexNumber") + " · " + o.optString("Name");
            } else {
                it.title = o.optString("Name");
                it.sub = total > 0 ? position / 60 + " of " + total / 60 + " min" : "";
            }
            boolean backdrop = o.optJSONArray("BackdropImageTags") != null && o.optJSONArray("BackdropImageTags").length() > 0;
            imagePaths.put(it, episode && !seriesId.isEmpty() ? "/Items/" + seriesId + "/Images/Backdrop?maxWidth=800&quality=80"
                    : backdrop ? "/Items/" + it.id + "/Images/Backdrop?maxWidth=800&quality=80"
                    : "/Items/" + it.id + "/Images/Primary?maxWidth=800&quality=80");
            out.add(it);
        }
    }

    private long lastPlayed(String seriesId) {
        try {
            JSONArray found = new JSONObject(text(url("/Items?userId=" + Prefs.jellyfinUserId() + "&parentId=" + seriesId
                    + "&recursive=true&includeItemTypes=Episode&filters=IsPlayed&sortBy=DatePlayed&sortOrder=Descending&limit=1"))).getJSONArray("Items");
            JSONObject data = found.length() == 0 ? null : found.getJSONObject(0).optJSONObject("UserData");
            return data == null ? 0 : parseDate(data.optString("LastPlayedDate", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseDate(String iso) {
        try {
            return iso.isEmpty() ? 0 : java.time.Instant.parse(iso).toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onJellyfin(); });
    }

    private static String base() {
        String u = Prefs.jellyfinUrl().trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private static String url(String path) {
        return base() + path;
    }

    private HttpURLConnection connect(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Authorization", JellyfinLink.header(context, Prefs.jellyfinToken()));
        return c;
    }

    private byte[] bytes(String url) throws IOException {
        HttpURLConnection c = connect(url);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private String text(String url) throws IOException {
        return new String(bytes(url), StandardCharsets.UTF_8);
    }

    static boolean open(Activity a, Item it) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(dashed(it.id)))
                .setComponent(new ComponentName(PACKAGE, START)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            a.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("couldn't open jellyfin: " + e.getMessage());
            return false;
        }
    }

    private static String dashed(String id) {
        if (id.length() != 32) return id;
        return UUID.fromString(id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16) + "-"
                + id.substring(16, 20) + "-" + id.substring(20)).toString();
    }
}
