package com.satoshilimited.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.KeyEvent;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

final class RemoteColours {
    private RemoteColours() {}

    static final String[] NAMES = {"Red", "Green", "Yellow", "Blue"};
    static final int[] COLOURS = {0xFFE5383B, 0xFF2FB35A, 0xFFF2C230, 0xFF2F7BF2};
    static final int[] KEYS = {KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_PROG_GREEN, KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_PROG_BLUE};
    static final String PHOTOS = "arca:photos", MUSIC = "arca:music", SAVER = "arca:saver";

    private static JSONObject cache = new JSONObject();
    private static long stamp = -1;

    static int index(int keyCode) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == keyCode) return i;
        return -1;
    }

    static String target(Context c, int colour) {
        return read(c).optString(NAMES[colour], "");
    }

    static boolean any(Context c) {
        return count(c) > 0;
    }

    static int count(Context c) {
        int n = 0;
        for (int i = 0; i < NAMES.length; i++) if (!target(c, i).isEmpty()) n++;
        return n;
    }

    static synchronized void set(Context c, int colour, String target) {
        try {
            JSONObject all = new JSONObject(read(c).toString());
            if (target.isEmpty()) all.remove(NAMES[colour]);
            else all.put(NAMES[colour], target);
            File file = file(c), tmp = new File(file.getPath() + ".tmp");
            Files.write(tmp.toPath(), all.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(file)) throw new IOException("rename");
            stamp = -1;
        } catch (Exception e) {
            AppLog.w("colour buttons not saved: " + e.getMessage());
        }
    }

    static Intent intent(Context c, String target) {
        HomeApps.Item it;
        if (target.equals(PHOTOS)) {
            it = HomeApps.app(HomeApps.PHOTOS, "Photos");
        } else if (target.equals(MUSIC)) {
            it = HomeApps.app(HomeApps.MUSIC, "Music");
        } else if (target.equals(SAVER)) {
            return new Intent().setComponent(new ComponentName(c, HomeActivity.class)).putExtra(HomeActivity.SAVER, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } else if (target.startsWith("app:")) {
            it = HomeApps.app(target.substring(4), "");
        } else if (target.startsWith("input:")) {
            it = HomeApps.action(HomeApps.INPUT, "", "", "");
            it.inputId = target.substring(6);
        } else {
            return null;
        }
        if (it.kind == HomeApps.APP && HomeApps.hubApp(it.pkg) && !HomeApps.hubAvailable(c, it.pkg)) return null;
        Intent intent = HomeApps.intentFor(c, it);
        return intent == null ? null : intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static String label(Context c, String target) {
        if (intent(c, target) == null) return "Default";
        if (target.equals(PHOTOS)) return "Photos";
        if (target.equals(MUSIC)) return "Music";
        if (target.equals(SAVER)) return "Screensaver";
        if (target.startsWith("app:")) {
            PackageManager pm = c.getPackageManager();
            try {
                return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(target.substring(4), 0)));
            } catch (PackageManager.NameNotFoundException e) {
                return "Default";
            }
        }
        for (HomeApps.Item it : HomeApps.allInputs(c)) {
            if (it.kind == HomeApps.INPUT && target.equals("input:" + it.inputId)) return it.label;
        }
        return "Input";
    }

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "colours.json");
    }

    private static synchronized JSONObject read(Context c) {
        File file = file(c);
        long now = file.exists() ? file.lastModified() * 31 + file.length() : 0;
        if (now == stamp) return cache;
        try {
            cache = file.exists() ? new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)) : new JSONObject();
        } catch (Exception e) {
            AppLog.w("colour buttons unreadable: " + e.getMessage());
            cache = new JSONObject();
        }
        stamp = now;
        return cache;
    }
}
