package com.satoshilimited.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings changed on the TV itself (arca TV > Settings). config.yaml only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    // MARK: - City

    static boolean placeReady() {
        return sp().getBoolean("placeManual", false)
                || (!Config.CITY.trim().isEmpty() && Config.CITY.trim().equals(sp().getString("placeFor", "")));
    }
    static String city() { return placeReady() ? sp().getString("city", "") : ""; }
    static String latitude() { return placeReady() ? sp().getString("latitude", "") : ""; }
    static String longitude() { return placeReady() ? sp().getString("longitude", "") : ""; }
    static String country() { return placeReady() ? sp().getString("country", "") : ""; }

    static void setPlace(String name, String latitude, String longitude, String country) {
        sp().edit().putString("placeFor", Config.CITY.trim()).putString("city", name).putString("latitude", latitude)
                .putString("longitude", longitude).putString("country", country).apply();
        version++;
    }

    /** Set from the TV itself (Weather & Location, or onboarding): wins over config.yaml for good. */
    static void setPlaceManual(String name, String latitude, String longitude, String country) {
        sp().edit().putBoolean("placeManual", true).putString("city", name).putString("latitude", latitude)
                .putString("longitude", longitude).putString("country", country).apply();
        version++;
    }

    // MARK: - TV name

    static final String DEFAULT_TV_NAME = "arca TV";

    static String tvName() {
        String v = sp().getString("tvName", "").trim();
        return v.isEmpty() ? DEFAULT_TV_NAME : v;
    }

    static void setTvName(String name) { put("tvName", name.trim()); }

    // MARK: - Widgets

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }
    static boolean screensaver() { return sp().getBoolean("screensaver", true); }
    static void setScreensaver(boolean on) { put("screensaver", on); }

    // MARK: - Onboarding

    private static final int ONBOARDING_VERSION = 3;   // bump when onboarding's content changes, so it shows again

    static boolean onboardingDone() { return sp().getInt("onboardingSeen", 0) >= ONBOARDING_VERSION; }
    static void setOnboardingDone() { sp().edit().putInt("onboardingSeen", ONBOARDING_VERSION).apply(); version++; }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    // MARK: - Continue watching

    static String continueSource() { return sp().getString("continueSource", Config.JELLYFIN_URL.trim().isEmpty() ? "off" : "jellyfin"); }
    static void setContinueSource(String source) { put("continueSource", source); }
}
