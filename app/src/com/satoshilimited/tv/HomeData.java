package com.satoshilimited.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class HomeData {
    private static final String WEATHER_QUERY = "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,weather_code&timezone=auto&forecast_days=1";
    private static final long WEATHER_EVERY = 30 * 60_000L;

    interface Listener { void onData(); }

    static final class Weather {
        double temp = Double.NaN, max = Double.NaN, min = Double.NaN;
        int code = -1, dayCode = -1, pm25 = -1;
        boolean isDay = true;
    }

    private static HomeData instance;

    static synchronized HomeData get(Context c) {
        if (instance == null) instance = new HomeData(c.getApplicationContext());
        return instance;
    }

    volatile long weatherAt;
    volatile Weather weather = new Weather();

    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile boolean fetching;
    private volatile long failedAt;

    private HomeData(Context c) {
        cache = c.getSharedPreferences("homedata", Context.MODE_PRIVATE);
        try {
            String w = cache.getString("weather", null);
            if (w != null && place().equals(cache.getString("weatherPlace", ""))) {
                weather = parseWeather(w, cache.getInt("pm25", -1));
                weatherAt = cache.contains("pm25") ? cache.getLong("weatherAt", 0) : 0;
            }
        } catch (Exception ignored) {}
    }

    private void resolvePlace() {
        if (fetching || Config.CITY.trim().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (failedAt != 0 && now >= failedAt && now - failedAt < 2 * 60_000L) return;
        fetching = true;
        io.execute(() -> {
            try {
                String[] parts = Config.CITY.trim().split(",", 2);
                List<String[]> found = findCity(parts[0]);
                if (found.isEmpty()) throw new IOException("city not found: " + Config.CITY.trim());
                String hint = parts.length > 1 ? parts[1].trim().toLowerCase(Locale.ROOT) : "";
                String[] city = found.get(0);
                if (!hint.isEmpty()) {
                    for (String[] candidate : found) {
                        if (candidate[5].toLowerCase(Locale.ROOT).equals(hint) || candidate[4].toLowerCase(Locale.ROOT).equals(hint)
                                || candidate[0].toLowerCase(Locale.ROOT).contains(hint)) {
                            city = candidate;
                            break;
                        }
                    }
                }
                Prefs.setPlace(city[1], city[2], city[3], city[4]);
                weatherAt = 0;
                failedAt = 0;
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                AppLog.w("city: " + e.getMessage());
            } finally {
                fetching = false;
            }
            main.post(() -> {
                for (Listener l : new ArrayList<>(listeners)) l.onData();
                refreshIfStale();
            });
        });
    }

    private static String place() {
        return Prefs.latitude() + "," + Prefs.longitude();
    }

    static boolean hasPlace() {
        return !Prefs.latitude().isEmpty() && !Prefs.longitude().isEmpty();
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    void settingsChanged() {
        weatherAt = 0;
        failedAt = 0;
    }

    void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (!hasPlace()) {
            resolvePlace();
            return;
        }
        if (fetching || !(now - weatherAt > WEATHER_EVERY || now < weatherAt)) return;
        if (failedAt != 0 && now >= failedAt && now - failedAt < 2 * 60_000L) return;
        fetching = true;
        io.execute(() -> {
            try {
                String lat = Prefs.latitude(), lon = Prefs.longitude();
                String body = get("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon + WEATHER_QUERY);
                int pm25 = -1;
                try {
                    JSONObject air = new JSONObject(get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + lat
                            + "&longitude=" + lon + "&current=pm2_5&timezone=auto"));
                    pm25 = (int) Math.round(air.getJSONObject("current").getDouble("pm2_5"));
                } catch (Exception e) {
                    AppLog.w("air quality: " + e.getMessage());
                }
                weather = parseWeather(body, pm25);
                weatherAt = System.currentTimeMillis();
                cache.edit().putString("weather", body).putInt("pm25", pm25).putLong("weatherAt", weatherAt)
                        .putString("weatherPlace", lat + "," + lon).apply();
                main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onData(); });
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                AppLog.w("weather: " + e.getMessage());
            } finally {
                fetching = false;
            }
        });
    }

    private static Weather parseWeather(String body, int pm25) throws Exception {
        JSONObject o = new JSONObject(body);
        Weather w = new Weather();
        JSONObject current = o.getJSONObject("current");
        w.temp = current.getDouble("temperature_2m");
        w.code = current.getInt("weather_code");
        w.isDay = current.optInt("is_day", 1) == 1;
        JSONObject daily = o.getJSONObject("daily");
        w.max = daily.getJSONArray("temperature_2m_max").getDouble(0);
        w.min = daily.getJSONArray("temperature_2m_min").getDouble(0);
        w.dayCode = daily.getJSONArray("weather_code").getInt(0);
        w.pm25 = pm25;
        return w;
    }

    static List<String[]> findCity(String name) throws Exception {
        JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&language=en"
                + "&name=" + URLEncoder.encode(name.trim(), "UTF-8")));
        JSONArray results = o.optJSONArray("results");
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String city = r.optString("name", "");
            if (city.isEmpty() || !r.has("latitude") || !r.has("longitude")) continue;
            StringBuilder label = new StringBuilder(city);
            for (String part : new String[] {r.optString("admin1", ""), r.optString("country", "")}) {
                if (!part.isEmpty() && !part.equals(city)) label.append(", ").append(part);
            }
            if (!seen.add(label.toString())) continue;
            out.add(new String[] {label.toString(), city,
                    String.format(Locale.US, "%.4f", r.getDouble("latitude")), String.format(Locale.US, "%.4f", r.getDouble("longitude")),
                    r.optString("country_code", ""), r.optString("country", "")});
        }
        return out;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", "arca-tv");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
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

    static String condition(int code, boolean day) {
        if (code < 0) return "";
        if (code == 0) return day ? "Clear" : "Clear night";
        if (code == 1) return day ? "Mostly sunny" : "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code == 65 || code == 67 || code == 82) return "Heavy rain";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Snow";
        if (code >= 95) return "Thunderstorm";
        return "Rain";
    }

    static String degrees(double v) {
        if (Double.isNaN(v)) return "—";
        return Math.round("US".equals(Prefs.country()) ? v * 9 / 5 + 32 : v) + "°";
    }
}
