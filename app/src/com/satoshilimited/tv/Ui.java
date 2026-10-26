package com.satoshilimited.tv;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.function.Supplier;

final class Ui {
    private Ui() {}

    static final boolean LEGACY = Build.VERSION.SDK_INT < 31;
    static final String language = "en";
    static final boolean en = true, es = false, de = false, ru = false;
    static Typeface thin, light, displayMedium, displaySemibold, regular, medium, semibold;
    private static boolean loaded;
    private static final Rect BOUNDS = new Rect();
    private static final RectF OVAL = new RectF();
    private static final String FONT = "fonts/Geist.ttf";

    static synchronized void init(Context c) {
        if (loaded) return;
        loaded = true;
        AssetManager a = c.getAssets();
        thin = font(a, 200);
        light = font(a, 300);
        displayMedium = font(a, 500);
        displaySemibold = font(a, 600);
        regular = font(a, 400);
        medium = font(a, 500);
        semibold = font(a, 600);
    }

    private static Typeface font(AssetManager a, int weight) {
        if (has(a, FONT)) {
            try {
                Typeface t = new Typeface.Builder(a, FONT).setFontVariationSettings("'wght' " + weight).build();
                if (t != null) return t;
            } catch (RuntimeException ignored) {
            }
        }
        return Typeface.create(Typeface.DEFAULT, weight, false);
    }

    private static boolean has(AssetManager a, String path) {
        try (InputStream in = a.open(path)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    static Paint text(Typeface face, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    static Paint numbers(Typeface face, float size, int color) {
        Paint p = text(face, size, color);
        p.setFontFeatureSettings("'tnum'");
        return p;
    }

    static Paint tagPaint(float k) {
        Paint p = text(semibold, 18 * k, 0xFFFFFFFF);
        p.setLetterSpacing(0.11f);
        return p;
    }

    static void tag(Canvas c, Paint fill, Paint text, RectF box, String label, float x, float y, float k) {
        String upper = label.toUpperCase(Locale.ROOT);
        box.set(x, y, x + text.measureText(upper) + 24 * k, y + 34 * k);
        fill.setColor(0x8C000000);
        c.drawRoundRect(box, 10 * k, 10 * k, fill);
        c.drawText(upper, x + 12 * k, y + 23 * k, text);
    }

    static String fit(String text, Paint paint, float max) {
        if (paint.measureText(text) <= max) return text;
        int n = paint.breakText(text, true, max - paint.measureText("…"), null);
        return text.substring(0, Math.max(0, n)).trim() + "…";
    }

    static String clockTime(long seconds) {
        long s = Math.max(0, seconds);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    static float cap(Paint p) {
        synchronized (BOUNDS) {
            p.getTextBounds("H8", 0, 2, BOUNDS);
            return -BOUNDS.top;
        }
    }

    static float halfCap(Paint p) {
        return cap(p) / 2f;
    }

    static float leftBearing(Paint p, String s) {
        if (s.isEmpty()) return 0;
        synchronized (BOUNDS) {
            p.getTextBounds(s, 0, 1, BOUNDS);
            return BOUNDS.left;
        }
    }

    static int alpha(int color, float a) {
        int base = (color >>> 24) & 255;
        return (Math.round(base * Math.max(0, Math.min(1, a))) << 24) | (color & 0xFFFFFF);
    }

    static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    static String tr(String pt, String english) {
        return english;
    }

    static String tr(String pt, String english, String spanish) {
        return english;
    }

    static Locale locale() {
        return Locale.US;
    }

    static String clock(long now) {
        return new SimpleDateFormat("h:mm", Locale.US).format(new Date(now));
    }

    static String meridiem(long now) {
        return new SimpleDateFormat("a", Locale.US).format(new Date(now)).toLowerCase(Locale.US);
    }

    static String time(long at) {
        return once("time", at, () -> new SimpleDateFormat("h:mm a", Locale.US).format(new Date(at)));
    }

    static String time24(long at) {
        return once("time24", at, () -> new SimpleDateFormat("HH:mm", Locale.US).format(new Date(at)));
    }

    private static final HashMap<String, String> FORMATTED = new HashMap<>();

    static String once(String kind, double value, Supplier<String> format) {
        String key = kind + '|' + value;
        synchronized (FORMATTED) {
            String s = FORMATTED.get(key);
            if (s == null) {
                if (FORMATTED.size() > 200) FORMATTED.clear();
                s = format.get();
                FORMATTED.put(key, s);
            }
            return s;
        }
    }

    static String date(long now) {
        return new SimpleDateFormat("EEEE d MMMM", Locale.US).format(new Date(now));
    }

    static void spinner(Canvas c, float cx, float cy, float r, float seconds, Paint stroke) {
        OVAL.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(OVAL, (seconds * 360f) % 360f, 270f, false, stroke);
    }
}
