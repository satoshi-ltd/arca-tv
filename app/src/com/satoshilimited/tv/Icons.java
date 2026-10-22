package com.satoshilimited.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.SparseArray;

final class Icons {
    private Icons() {}

    static final int MUTE = 0xB8FFFFFF, INK = 0xFFF2F2F2;

    private static final SparseArray<Drawable> CACHE = new SparseArray<>();

    static int res(String name) {
        switch (name) {
            case "tv": return R.drawable.icon_tv;
            case "sound": return R.drawable.icon_sound;
            case "settings": return R.drawable.icon_settings;
            case "info": return R.drawable.icon_info;
            case "wifi": return R.drawable.icon_wifi;
            case "gamepad": return R.drawable.icon_gamepad;
            case "streamer": return R.drawable.icon_streamer;
            case "apps": return R.drawable.icon_apps;
            default: return R.drawable.icon_hdmi;
        }
    }

    static synchronized void draw(Context c, Canvas canvas, int res, float cx, float cy, float size, int color) {
        Drawable d = CACHE.get(res);
        if (d == null) {
            d = c.getResources().getDrawable(res, null).mutate();
            CACHE.put(res, d);
        }
        int half = Math.round(size / 2);
        d.setBounds(Math.round(cx) - half, Math.round(cy) - half, Math.round(cx) + half, Math.round(cy) + half);
        d.setTint(color);
        d.draw(canvas);
    }
}
