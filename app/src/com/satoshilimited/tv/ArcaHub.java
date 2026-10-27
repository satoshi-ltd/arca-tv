package com.satoshilimited.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ArcaHub {
    private ArcaHub() {}

    static final int PORT = 17831;
    static final String INVALID_CODE = "invalid", TOO_MANY = "toomany", NOT_OPEN = "notopen", UNREACHABLE = "unreachable", REVOKED = "revoked";

    static final class Found {
        String id = "", name = "", url = "";
        boolean lanHttp = true;
    }

    static final class Folders {
        final List<String> photos = new ArrayList<>(), music = new ArrayList<>(), video = new ArrayList<>(), photoIds = new ArrayList<>(), musicIds = new ArrayList<>(), videoIds = new ArrayList<>();
    }

    interface Callback<T> { void done(T result, String error); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newFixedThreadPool(4);

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("arca-hub", Context.MODE_PRIVATE);
    }

    static boolean connected(Context c) {
        SharedPreferences p = prefs(c);
        return !p.getString("token", "").isEmpty() && !p.getString("url", "").isEmpty();
    }

    static String name(Context c) {
        return prefs(c).getString("name", "");
    }

    static String url(Context c) {
        return prefs(c).getString("url", "");
    }

    static String volume(Context c, String kind) {
        return prefs(c).getString(kind, "");
    }

    static boolean has(Context c, String kind) {
        return connected(c) && !volume(c, kind).isEmpty();
    }

    static String get(Context c, String path, int maxBytes) throws IOException {
        return get(c, path, maxBytes, 15000);
    }

    static String get(Context c, String path, int maxBytes, int timeoutMs) throws IOException {
        SharedPreferences p = prefs(c);
        String token = p.getString("token", "");
        Response r = request("GET", p.getString("url", "") + path, token, null, timeoutMs, maxBytes);
        if (r.code == 401) {
            disconnectIfCurrent(c.getApplicationContext(), token);
            throw new IOException(REVOKED);
        }
        if (r.code != 200) throw new IOException("HTTP " + r.code);
        return r.body;
    }

    static Response reply(Context c, String path, int maxBytes) throws IOException {
        SharedPreferences p = prefs(c);
        String token = p.getString("token", "");
        Response r = request("GET", p.getString("url", "") + path, token, null, 15000, maxBytes);
        if (r.code == 401) {
            disconnectIfCurrent(c.getApplicationContext(), token);
            throw new IOException(REVOKED);
        }
        return r;
    }

    static String post(Context c, String path, JSONObject body) throws IOException {
        SharedPreferences p = prefs(c);
        String token = p.getString("token", "");
        Response r = request("POST", p.getString("url", "") + path, token, body.toString(), 10000);
        if (r.code == 401) {
            disconnectIfCurrent(c.getApplicationContext(), token);
            throw new IOException(REVOKED);
        }
        if (r.code != 200) throw new IOException("HTTP " + r.code);
        return r.body;
    }

    static String authorization(Context c) {
        return "Bearer " + prefs(c).getString("token", "");
    }

    private static synchronized void disconnectIfCurrent(Context c, String token) {
        if (token.equals(prefs(c).getString("token", ""))) disconnect(c);
    }

    static synchronized void disconnect(Context c) {
        prefs(c).edit().clear().apply();
        VideoLibrary.forget(c);
        MusicCovers.forget(c);
        PhotoLibrary.forget(c);
        HubCounts.forget(c);
    }

    static final String DEFAULT_NAME = "arca TV";

    static String deviceName(Context c) {
        String name = Settings.Global.getString(c.getContentResolver(), Settings.Global.DEVICE_NAME);
        name = name == null || name.trim().isEmpty() ? DEFAULT_NAME : name.trim();
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    static void scan(Callback<List<Found>> callback) {
        IO.execute(() -> {
            List<Found> result = LanScan.run(LanScan.prefix(), host -> probeNow(host, 700));
            Collections.sort(result, (x, y) -> x.name.compareToIgnoreCase(y.name));
            MAIN.post(() -> callback.done(result, null));
        });
    }

    static void probe(String host, Callback<Found> callback) {
        IO.execute(() -> {
            Found f = probeNow(host, 2500);
            MAIN.post(() -> callback.done(f, f == null ? UNREACHABLE : null));
        });
    }

    private static Found probeNow(String host, int timeoutMs) {
        try {
            String url = "http://" + host + ":" + PORT;
            Response r = request("GET", url + "/.well-known/arca", null, null, timeoutMs);
            if (r.code != 200) return null;
            JSONObject o = new JSONObject(r.body);
            if (!"arca".equals(o.optString("service")) || !"hub".equals(o.optString("role"))) return null;
            Found f = new Found();
            f.id = o.optString("id");
            f.name = o.optString("name", host);
            f.url = url;
            JSONObject access = o.optJSONObject("access");
            f.lanHttp = access == null || access.optBoolean("allowLanHttp", true);
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    static void pair(Context c, Found hub, String code, Callback<Void> callback) {
        Context app = c.getApplicationContext();
        IO.execute(() -> {
            String error = null;
            try {
                JSONObject body = new JSONObject().put("code", code).put("name", deviceName(app)).put("kind", "player");
                Response r = request("POST", hub.url + "/pair", null, body.toString(), 6000);
                if (r.code == 201) {
                    JSONObject o = new JSONObject(r.body);
                    String token = o.optString("token"), id = o.optString("id"), hubId = o.optString("hubId");
                    boolean player = "player".equals(o.optString("role"));
                    if (token.isEmpty() || id.isEmpty()) throw new IOException("bad reply");
                    synchronized (ArcaHub.class) {
                        VideoLibrary.forget(app);
                        MusicCovers.forget(app);
                        PhotoLibrary.forget(app);
                        prefs(app).edit().clear().putString("url", hub.url).putString("name", hub.name).putString("token", token)
                                .putString("device", id).putString("hub", hubId).putBoolean("player", player).apply();
                    }
                    reportName(app);
                } else if (r.code == 401) {
                    error = INVALID_CODE;
                } else if (r.code == 429) {
                    error = TOO_MANY;
                } else if (r.code == 412) {
                    error = NOT_OPEN;
                } else {
                    error = UNREACHABLE;
                }
            } catch (Exception e) {
                AppLog.w("arca hub pairing: " + e.getMessage());
                error = UNREACHABLE;
            }
            final String result = error;
            MAIN.post(() -> callback.done(null, result));
        });
    }

    static void reportName(Context c) {
        if (!connected(c)) return;
        Context app = c.getApplicationContext();
        IO.execute(() -> {
            try {
                SharedPreferences p = prefs(app);
                Set<String> ids = playedFolders(p);
                String name = deviceName(app), version = version(app), sent = name + "|" + version + "|" + ids;
                JSONArray played = new JSONArray();
                for (String id : ids) played.put(id);
                JSONObject body = new JSONObject().put("machineId", p.getString("device", "")).put("name", name)
                        .put("platform", "android").put("app", "arca TV").put("version", version)
                        .put("arch", "").put("kernelRelease", "").put("phase", "idle")
                        .put("uptimeSeconds", 0).put("selectedFolders", played.length()).put("indexedFiles", 0).put("indexedBytes", 0)
                        .put("lastSync", JSONObject.NULL).put("folderIds", played).put("albumFolderIds", new JSONArray());
                Response r = request("POST", p.getString("url", "") + "/v1/machine-report", p.getString("token", ""), body.toString(), 5000);
                if (r.code == 200) p.edit().putString("reported", sent).apply();
                else AppLog.w("arca hub name report: HTTP " + r.code);
            } catch (Exception e) {
                AppLog.w("arca hub name report: " + e.getMessage());
            }
        });
    }

    private static Set<String> playedFolders(SharedPreferences p) {
        Set<String> ids = new LinkedHashSet<>();
        if (!p.getBoolean("player", false)) return ids;
        for (String kind : new String[] {"photos", "music", "video"})
            if (!p.getString(kind, "").isEmpty()) ids.add(p.getString(kind, ""));
        return ids;
    }

    private static String reported(Context c) {
        SharedPreferences p = prefs(c);
        return deviceName(c) + "|" + version(c) + "|" + playedFolders(p);
    }

    static String version(Context c) {
        try {
            String name = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return name.matches("\\d+\\.\\d+") ? name + ".0" : name;
        } catch (Exception e) {
            return "";
        }
    }

    static void refresh(Context c, Runnable changed) {
        if (!connected(c)) return;
        String before = volume(c, "photos") + "|" + volume(c, "music") + "|" + volume(c, "video");
        folders(c, (folders, error) -> {
            if (!(volume(c, "photos") + "|" + volume(c, "music") + "|" + volume(c, "video")).equals(before)) changed.run();
        });
    }

    static void folders(Context c, Callback<Folders> callback) {
        Context app = c.getApplicationContext();
        IO.execute(() -> {
            Folders folders = new Folders();
            String error = null;
            try {
                SharedPreferences p = prefs(app);
                String token = p.getString("token", "");
                Response r = request("GET", p.getString("url", "") + "/v1/catalog", token, null, 6000);
                if (r.code == 401) {
                    disconnectIfCurrent(app, token);
                    error = REVOKED;
                } else if (r.code == 200) {
                    JSONArray volumes = new JSONObject(r.body).optJSONArray("volumes");
                    for (int i = 0; volumes != null && i < volumes.length(); i++) {
                        JSONObject v = volumes.getJSONObject(i);
                        if (v.optBoolean("gallery")) {
                            folders.photos.add(v.optString("name"));
                            folders.photoIds.add(v.optString("id"));
                        }
                        if (v.optBoolean("music")) {
                            folders.music.add(v.optString("name"));
                            folders.musicIds.add(v.optString("id"));
                        }
                        if (v.optBoolean("video")) {
                            folders.video.add(v.optString("name"));
                            folders.videoIds.add(v.optString("id"));
                        }
                    }
                    synchronized (ArcaHub.class) {
                        if (!token.equals(p.getString("token", ""))) throw new IOException("hub changed");
                        p.edit().putString("photos", folders.photoIds.isEmpty() ? "" : folders.photoIds.get(0))
                                .putString("music", folders.musicIds.isEmpty() ? "" : folders.musicIds.get(0))
                                .putString("video", folders.videoIds.isEmpty() ? "" : folders.videoIds.get(0)).apply();
                    }
                    if (!reported(app).equals(p.getString("reported", ""))) reportName(app);
                } else {
                    error = UNREACHABLE;
                }
            } catch (Exception e) {
                AppLog.w("arca hub folders: " + e.getMessage());
                error = UNREACHABLE;
            }
            final String result = error;
            MAIN.post(() -> callback.done(folders, result));
        });
    }

    static final class Response {
        int code;
        String body = "";
    }

    private static Response request(String method, String url, String token, String body, int timeoutMs) throws IOException {
        return request(method, url, token, body, timeoutMs, 1_000_000);
    }

    private static Response request(String method, String url, String token, String body, int timeoutMs, int maxBytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(false);
            if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(data);
                }
            }
            Response r = new Response();
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                try (InputStream stream = in) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int n, total = 0;
                    while ((n = stream.read(buffer)) > 0 && total < maxBytes) {
                        out.write(buffer, 0, n);
                        total += n;
                    }
                    r.body = out.toString(StandardCharsets.UTF_8.name());
                }
            }
            return r;
        } finally {
            c.disconnect();
        }
    }
}
