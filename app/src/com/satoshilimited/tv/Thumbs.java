package com.satoshilimited.tv;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

final class Thumbs {
    private Thumbs() {}

    static final class Entry {
        String name, logoFile;
        int brand;
        Bitmap logo;
        boolean loaded;
        final Map<Integer, Bitmap> sized = new HashMap<>();
    }

    private static final Map<String, Entry> BY_PACKAGE = new HashMap<>();
    private static AssetManager assets;
    private static boolean ready;

    static synchronized void load(Context c) {
        if (ready) return;
        ready = true;
        assets = c.getApplicationContext().getAssets();
        try {
            JSONArray apps = new JSONObject(text("thumbnails/thumbnails.json")).getJSONArray("apps");
            for (int i = 0; i < apps.length(); i++) {
                JSONObject o = apps.getJSONObject(i);
                Entry e = new Entry();
                e.name = o.getString("name");
                e.brand = Color.parseColor(o.getString("brand"));
                String logo = o.optString("logo", "");
                e.logoFile = logo.isEmpty() ? null : "thumbnails/" + logo.replaceAll("\\.svg$", ".png");
                JSONArray packages = o.getJSONArray("packages");
                for (int p = 0; p < packages.length(); p++) BY_PACKAGE.put(packages.getString(p), e);
            }
        } catch (Exception ex) {
            AppLog.w("thumbnails: " + ex.getMessage());
        }
    }

    static synchronized Entry entry(String pkg) {
        Entry e = pkg == null ? null : BY_PACKAGE.get(pkg);
        if (e != null && !e.loaded) {
            e.loaded = true;
            if (e.logoFile != null) {
                try (InputStream in = assets.open(e.logoFile)) {
                    e.logo = BitmapFactory.decodeStream(in);
                } catch (IOException ex) {
                    AppLog.w("thumbnail " + e.logoFile + ": " + ex.getMessage());
                }
            }
        }
        return e;
    }

    static synchronized Bitmap fit(Entry e, int width) {
        if (e == null || e.logo == null) return null;
        int target = Math.min(e.logo.getWidth(), Math.max(32, (width + 31) / 32 * 32));
        Bitmap cached = e.sized.get(target);
        if (cached != null) return cached;
        Bitmap bitmap = e.logo;
        while (bitmap.getWidth() / 2 >= target) {
            bitmap = Bitmap.createScaledBitmap(bitmap, bitmap.getWidth() / 2, Math.max(1, bitmap.getHeight() / 2), true);
        }
        if (bitmap.getWidth() != target) {
            int height = Math.max(1, Math.round(target * (float) e.logo.getHeight() / e.logo.getWidth()));
            bitmap = Bitmap.createScaledBitmap(bitmap, target, height, true);
        }
        e.sized.put(target, bitmap);
        return bitmap;
    }

    static int brand(String pkg) {
        Entry e = pkg == null ? null : BY_PACKAGE.get(pkg);
        return e == null ? 0 : e.brand;
    }

    private static String text(String path) throws IOException {
        try (InputStream in = assets.open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
