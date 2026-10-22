package com.satoshilimited.tv;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ArcaDomo {
    static final int LOADING = 0, READY = 1, UNREACHABLE = 2, REJECTED = 3;
    static final long STALE_MS = 120_000, OLD_MS = 300_000, POLL_MS = 10_000;
    static final String[] LEVELS = {"Excellent", "Good", "Light", "Moderate", "Heavy", "Severe"};
    static final int GREEN = 0xFF7FB893, AMBER = 0xFFE0A030, RED = 0xFFD9484A;
    private static final int LIST_TIMEOUT = 5_000, LIVE_TIMEOUT = 15_000;

    interface Listener { void onDomo(); }

    interface Done { void done(Device device, String error); }

    static final class Device {
        String id = "", type = "", name = "", error = "";
        boolean hasState, power;
        String mode = "", status = "", cleaning = "";
        int pm25 = -1, humidity = -1, filter = -1, filterHours = -1, fan = -1, battery = -1;
        double temperature = Double.NaN;
        long readAt;
        String statusText = "", pmText = "", humidityText = "", temperatureText = "", filterText = "", filterNote = "", batteryText = "";
        String modeWord = "", statusWord = "", statusSub = "", cleaningWord = "";

        boolean purifier() { return "purifier".equals(type); }
        boolean vacuum() { return "vacuum".equals(type); }
        boolean failed() { return !error.isEmpty(); }

        long age(long now) { return hasState ? Math.max(0, now - readAt) : Long.MAX_VALUE; }

        int level() { return hasState && pm25 >= 0 ? ArcaDomo.level(pm25) : -1; }

        boolean cleaning() { return "sweeping".equals(status) || "sweeping_mopping".equals(status) || "mopping".equals(status); }

        boolean charging() { return "charging".equals(status); }
    }

    private static final ArcaDomo INSTANCE = new ArcaDomo();

    static ArcaDomo get() { return INSTANCE; }

    static boolean enabled() { return !Config.DOMO_URL.trim().isEmpty(); }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService actions = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();
    private volatile List<Device> devices = Collections.emptyList();
    private int status = LOADING, users;
    private long lastReading;
    private boolean polling;
    private final Runnable poll = this::poll;

    private ArcaDomo() {}

    int status() { return status; }

    List<Device> devices() { return devices; }

    long lastReading() { return lastReading; }

    Device device(String id) {
        for (Device d : devices) if (d.id.equals(id)) return d;
        return null;
    }

    void start(Listener l) {
        if (!enabled()) return;
        if (!listeners.contains(l)) listeners.add(l);
        users++;
        if (users == 1) {
            main.removeCallbacks(poll);
            poll();
        }
    }

    void stop(Listener l) {
        if (!listeners.remove(l)) return;
        users = Math.max(0, users - 1);
        if (users == 0) main.removeCallbacks(poll);
    }

    void retry() {
        if (!enabled()) return;
        main.removeCallbacks(poll);
        poll();
    }

    private void poll() {
        if (users == 0 || polling) return;
        polling = true;
        io.execute(() -> {
            int next;
            List<Device> found = null;
            try {
                Response r = request("GET", "/api/devices", null, LIST_TIMEOUT);
                if (r.code == 401) next = REJECTED;
                else if (r.code >= 200 && r.code < 300) {
                    JSONArray list = new JSONArray(r.body);
                    long now = SystemClock.elapsedRealtime();
                    found = new ArrayList<>();
                    for (int i = 0; i < list.length(); i++) {
                        Device d = parse(list.optJSONObject(i), now);
                        if (d != null && (d.purifier() || d.vacuum())) found.add(d);
                    }
                    sort(found);
                    next = READY;
                } else next = UNREACHABLE;
            } catch (Exception e) {
                AppLog.w("arca domo: " + e.getMessage());
                next = UNREACHABLE;
            }
            final int result = next;
            final List<Device> fresh = found;
            main.post(() -> {
                polling = false;
                status = result;
                if (fresh != null) {
                    devices = fresh;
                    lastReading = System.currentTimeMillis();
                }
                notifyListeners();
                if (users > 0) main.postDelayed(poll, POLL_MS);
            });
        });
    }

    void act(String id, String action, JSONObject body, Done done) {
        actions.execute(() -> {
            Device updated = null;
            String error = null;
            try {
                Response r = request("POST", "/api/devices/" + id + "/actions/" + action, body == null ? "{}" : body.toString(), LIVE_TIMEOUT);
                if (r.code == 401) {
                    error = "rejected";
                } else {
                    JSONObject o = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
                    if (r.code >= 200 && r.code < 300 && o.optBoolean("ok", true) && o.isNull("error")) {
                        Device base = device(id);
                        JSONObject shaped = new JSONObject();
                        shaped.put("id", id);
                        shaped.put("type", base == null ? "" : base.type);
                        shaped.put("state", o.opt("state"));
                        shaped.put("error", JSONObject.NULL);
                        shaped.put("age", o.optDouble("age", 0));
                        updated = parse(shaped, SystemClock.elapsedRealtime());
                    } else {
                        error = o.optString("error", "no answer");
                    }
                }
            } catch (Exception e) {
                error = "no answer";
            }
            final Device result = updated;
            final String failure = error;
            main.post(() -> {
                if (result != null) replace(result);
                done.done(result, failure);
                notifyListeners();
            });
        });
    }

    private void replace(Device d) {
        List<Device> next = new ArrayList<>(devices);
        for (int i = 0; i < next.size(); i++) if (next.get(i).id.equals(d.id)) next.set(i, d);
        devices = next;
    }

    private void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) l.onDomo();
    }

    static int level(int pm) {
        if (pm <= 5) return 0;
        if (pm <= 12) return 1;
        if (pm <= 35) return 2;
        if (pm <= 55) return 3;
        if (pm <= 150) return 4;
        return 5;
    }

    static String name(String id) {
        String spaced = id.replace('-', ' ').replace('_', ' ').trim();
        if (spaced.isEmpty()) return id;
        return spaced.substring(0, 1).toUpperCase(Locale.US) + spaced.substring(1);
    }

    private static void sort(List<Device> list) {
        Collections.sort(list, (a, b) -> {
            if (a.vacuum() != b.vacuum()) return a.vacuum() ? 1 : -1;
            return 0;
        });
    }

    private static Device parse(JSONObject o, long now) {
        if (o == null) return null;
        Device d = new Device();
        d.id = o.optString("id");
        d.type = o.optString("type");
        d.name = name(d.id);
        d.error = o.isNull("error") ? "" : o.optString("error");
        JSONObject s = o.optJSONObject("state");
        d.hasState = s != null;
        d.readAt = now - Math.round(o.optDouble("age", 0) * 1000);
        if (s == null) return d;
        d.power = s.optBoolean("power");
        d.mode = s.optString("mode");
        d.pm25 = s.isNull("pm25") ? -1 : s.optInt("pm25", -1);
        d.humidity = s.isNull("humidity") ? -1 : s.optInt("humidity", -1);
        d.temperature = s.isNull("temperature") ? Double.NaN : s.optDouble("temperature", Double.NaN);
        d.filter = s.isNull("filter_life") ? -1 : s.optInt("filter_life", -1);
        d.filterHours = s.isNull("filter_hours_left") ? -1 : s.optInt("filter_hours_left", -1);
        d.fan = s.isNull("fan_level") ? -1 : s.optInt("fan_level", -1);
        d.status = s.optString("status");
        d.cleaning = s.optString("mode");
        d.battery = s.isNull("battery") ? -1 : s.optInt("battery", -1);
        describe(d);
        return d;
    }

    private static void describe(Device d) {
        d.modeWord = word(d.mode);
        d.pmText = d.pm25 >= 0 ? String.valueOf(d.pm25) : "";
        d.humidityText = d.humidity >= 0 ? d.humidity + " %" : "";
        d.temperatureText = Double.isNaN(d.temperature) ? "" : Math.round(d.temperature) + "°";
        d.filterText = d.filter >= 0 ? "Filter " + d.filter + " %" : "";
        String hours = d.filterHours >= 0 ? d.filterHours + (d.filterHours == 1 ? " hour left" : " hours left") : "";
        d.filterNote = d.filter >= 0 && d.filter <= 10 ? (hours.isEmpty() ? "Change the filter" : "Change the filter · " + hours) : hours;
        d.batteryText = d.battery >= 0 ? String.valueOf(d.battery) : "";
        d.cleaningWord = "sweep_mop".equals(d.cleaning) ? "Sweep and mop" : "mop".equals(d.cleaning) ? "Mop" : "sweep".equals(d.cleaning) ? "Sweep" : word(d.cleaning);
        switch (d.status) {
            case "sleep": d.statusWord = "Asleep"; d.statusSub = "Waiting for a job"; break;
            case "standby": d.statusWord = "Ready"; d.statusSub = "Waiting for a job"; break;
            case "paused": d.statusWord = "Paused"; d.statusSub = "Stopped in the middle of a job"; break;
            case "returning": d.statusWord = "Going home"; d.statusSub = "On its way to the dock"; break;
            case "charging": d.statusWord = "Charging"; d.statusSub = "On the dock"; break;
            case "sweeping": d.statusWord = "Cleaning"; d.statusSub = "Sweeping the house"; break;
            case "sweeping_mopping": d.statusWord = "Cleaning"; d.statusSub = "Sweeping and mopping"; break;
            case "mopping": d.statusWord = "Mopping"; d.statusSub = "Mopping the floors"; break;
            case "upgrading": d.statusWord = "Updating"; d.statusSub = "Installing an update"; break;
            default: d.statusWord = word(d.status); d.statusSub = ""; break;
        }
        d.statusText = d.vacuum() ? d.statusWord : d.power ? (d.modeWord.isEmpty() ? "On" : "On · " + d.modeWord) : "Off";
    }

    static String word(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String spaced = raw.replace('_', ' ');
        return spaced.substring(0, 1).toUpperCase(Locale.US) + spaced.substring(1);
    }

    String summary() {
        if (!enabled()) return null;
        if (status == UNREACHABLE || status == REJECTED) return "no reading";
        if (status == LOADING || devices.isEmpty()) return null;
        long now = SystemClock.elapsedRealtime(), newest = Long.MAX_VALUE;
        for (Device d : devices) if (d.hasState) newest = Math.min(newest, d.age(now));
        if (newest == Long.MAX_VALUE || newest > OLD_MS) return "out of date";
        int worst = worstLevel();
        return worst < 0 ? null : LEVELS[worst];
    }

    int worstLevel() {
        if (status != READY) return -1;
        long now = SystemClock.elapsedRealtime();
        int worst = -1;
        for (Device d : devices) {
            if (d.hasState && d.purifier() && d.age(now) <= OLD_MS && !d.failed()) worst = Math.max(worst, d.level());
        }
        return worst;
    }

    int washColor() {
        int worst = worstLevel();
        return worst >= 4 ? RED : worst >= 2 ? AMBER : GREEN;
    }

    String insideLabel() {
        String s = summary();
        if (s == null) return null;
        if (!Character.isUpperCase(s.charAt(0))) return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        long now = SystemClock.elapsedRealtime();
        int worst = -1;
        for (Device d : devices) {
            if (d.hasState && d.purifier() && d.age(now) <= OLD_MS && !d.failed()) worst = Math.max(worst, d.pm25);
        }
        return worst < 0 ? null : String.valueOf(worst);
    }

    private static final class Response {
        int code;
        String body = "";
    }

    private static Response request(String method, String path, String body, int timeoutMs) throws IOException {
        String base = Config.DOMO_URL.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(false);
            if (!Config.DOMO_TOKEN.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + Config.DOMO_TOKEN);
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
                    while ((n = stream.read(buffer)) > 0 && total < 2_000_000) {
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
