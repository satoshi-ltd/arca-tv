package com.satoshilimited.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class HubDelete {
    private HubDelete() {}

    static final String OLD_HUB = "Update arca on the hub to delete from the TV.", CHANGED = "It changed on the hub. Reopen it and try again.", FAILED = "Couldn't delete it.";

    static final class Target {
        final String path;
        final long rev;

        Target(String path, long rev) {
            this.path = path;
            this.rev = rev;
        }
    }

    interface Done { void done(List<String> removed, String error); }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void file(Context c, String volume, List<Target> targets, Done done) {
        run(c, "/v1/delete-file", volume, targets, false, done);
    }

    static void photos(Context c, String volume, List<Target> targets, Done done) {
        run(c, "/v1/gallery/delete", volume, targets, true, done);
    }

    private static void run(Context c, String route, String volume, List<Target> targets, boolean gallery, Done done) {
        Context app = c.getApplicationContext();
        IO.execute(() -> {
            List<String> removed = new ArrayList<>();
            String error = null;
            for (Target t : targets) {
                try {
                    JSONObject body = new JSONObject().put("volume", volume).put("path", t.path).put("rev", t.rev);
                    if (gallery) body.put("id", "tv-" + UUID.randomUUID().toString().replace("-", ""));
                    ArcaHub.post(app, route, body);
                    removed.add(t.path);
                } catch (Exception e) {
                    AppLog.w("arca delete: " + e.getMessage());
                    String m = String.valueOf(e.getMessage());
                    error = m.contains("403") ? OLD_HUB : m.contains("409") ? CHANGED : FAILED;
                    break;
                }
            }
            String result = error;
            MAIN.post(() -> done.done(removed, result));
        });
    }
}
