package com.satoshilimited.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class JellyfinLink {
    private JellyfinLink() {}

    static final int PORT = 8096;
    static final String OFF = "off", EXPIRED = "expired", UNREACHABLE = "unreachable";
    private static final long CODE_LIFE_MS = 10 * 60_000L, POLL_MS = 3000;
    private static final int MAX_MISSES = 4;

    static final class Server {
        String name, id, version, url;
    }

    static final class Session {
        String token, userId, user;
    }

    interface Callback<T> { void done(T result, String error); }

    interface Listener {
        void code(String code);
        void signedIn(Session session);
        void failed(String error);
    }

    private static final ExecutorService SEARCH = Executors.newSingleThreadExecutor(), LINK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void search(Context c, Callback<List<Server>> callback) {
        Context app = c.getApplicationContext();
        SEARCH.execute(() -> {
            List<Server> found = new ArrayList<>();
            for (String target : known(app)) {
                Server s = probeNow(target, 700);
                if (s != null && !same(found, s)) found.add(s);
            }
            if (found.isEmpty()) found = LanScan.run(LanScan.prefix(), host -> probeNow(host, 700));
            Collections.sort(found, (a, b) -> a.name.compareToIgnoreCase(b.name));
            List<Server> result = found;
            MAIN.post(() -> callback.done(result, null));
        });
    }

    static void probe(String host, Callback<Server> callback) {
        SEARCH.execute(() -> {
            Server s = probeNow(host, 2500);
            MAIN.post(() -> callback.done(s, s == null ? UNREACHABLE : null));
        });
    }

    static final class Link {
        private final Server server;
        private volatile boolean cancelled;

        Link(Server server) {
            this.server = server;
        }

        void cancel() {
            cancelled = true;
        }

        void start(Context c, Listener listener) {
            Context app = c.getApplicationContext();
            LINK.execute(() -> {
                try {
                    if (!"true".equalsIgnoreCase(request("GET", server.url + "/QuickConnect/Enabled", null, null, 4000).trim())) {
                        report(() -> listener.failed(OFF));
                        return;
                    }
                    String auth = header(app, null);
                    JSONObject started;
                    try {
                        started = new JSONObject(request("POST", server.url + "/QuickConnect/Initiate", auth, null, 5000));
                    } catch (IOException e) {
                        report(() -> listener.failed(String.valueOf(e.getMessage()).equals("HTTP 401") ? OFF : UNREACHABLE));
                        return;
                    }
                    String secret = started.getString("Secret"), code = started.getString("Code");
                    report(() -> listener.code(code));
                    long until = SystemClock.elapsedRealtime() + CODE_LIFE_MS;
                    int misses = 0;
                    while (!cancelled && SystemClock.elapsedRealtime() < until) {
                        Thread.sleep(POLL_MS);
                        if (cancelled) return;
                        JSONObject state;
                        try {
                            state = new JSONObject(request("GET", server.url + "/QuickConnect/Connect?secret=" + secret, auth, null, 5000));
                            misses = 0;
                        } catch (IOException e) {
                            if (String.valueOf(e.getMessage()).equals("HTTP 404")) throw e;
                            if (++misses >= MAX_MISSES) throw e;
                            continue;
                        }
                        if (!state.optBoolean("Authenticated")) continue;
                        JSONObject done = new JSONObject(request("POST", server.url + "/Users/AuthenticateWithQuickConnect", auth,
                                new JSONObject().put("Secret", secret).toString(), 8000));
                        Session s = new Session();
                        s.token = done.getString("AccessToken");
                        s.userId = done.getJSONObject("User").getString("Id");
                        s.user = done.getJSONObject("User").optString("Name", "");
                        report(() -> listener.signedIn(s));
                        return;
                    }
                    report(() -> listener.failed(EXPIRED));
                } catch (Exception e) {
                    AppLog.w("jellyfin quick connect: " + e.getMessage());
                    String message = String.valueOf(e.getMessage());
                    report(() -> listener.failed(message.equals("HTTP 404") ? EXPIRED : UNREACHABLE));
                }
            });
        }

        private void report(Runnable r) {
            MAIN.post(() -> {
                if (!cancelled) r.run();
            });
        }
    }

    static String header(Context c, String token) {
        String device = ArcaHub.deviceName(c).replaceAll("[^\\x20-\\x7e]", "").replace("\"", "").trim();
        if (device.isEmpty()) device = "arca TV";
        return "MediaBrowser Client=\"arca TV\", Device=\"" + device + "\", DeviceId=\"" + Prefs.jellyfinDevice()
                + "\", Version=\"" + ArcaHub.version(c) + "\"" + (token == null ? "" : ", Token=\"" + token + "\"");
    }

    private static List<String> known(Context c) {
        List<String> out = new ArrayList<>();
        String saved = Prefs.jellyfinLast().replaceFirst("^https?://", "");
        if (!saved.isEmpty()) out.add(saved.replaceAll("/.*$", ""));
        String hub = ArcaHub.connected(c) ? ArcaHub.url(c).replaceFirst("^https?://", "").replaceAll("[:/].*$", "") : "";
        if (!hub.isEmpty() && !out.contains(hub)) out.add(hub);
        return out;
    }

    private static boolean same(List<Server> list, Server s) {
        for (Server other : list) if (other.url.equals(s.url)) return true;
        return false;
    }

    private static Server probeNow(String target, int timeoutMs) {
        String url = "http://" + (target.contains(":") ? target : target + ":" + PORT);
        try {
            JSONObject o = new JSONObject(request("GET", url + "/System/Info/Public", null, null, timeoutMs));
            if (!o.has("ServerName") || !o.has("Id")) return null;
            Server s = new Server();
            s.name = o.optString("ServerName", target);
            s.id = o.optString("Id");
            s.version = o.optString("Version");
            s.url = url;
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private static String request(String method, String url, String auth, String body, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            if (auth != null) c.setRequestProperty("Authorization", auth);
            if (method.equals("POST")) {
                c.setDoOutput(true);
                byte[] data = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
                if (body != null) c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(data);
                }
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
