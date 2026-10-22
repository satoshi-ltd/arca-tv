package com.satoshilimited.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PhotoStream implements PhotoSource {
    static final class Photo {
        final Bitmap bitmap;
        final String date;
        final String hash;
        final boolean video;

        Photo(Bitmap bitmap, String date) {
            this(bitmap, date, "", false);
        }

        Photo(Bitmap bitmap, String date, String hash, boolean video) {
            this.bitmap = bitmap;
            this.date = date;
            this.hash = hash;
            this.video = video;
        }
    }

    private final Context context;
    private final String size;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<String[]> queue = new ArrayList<>(), shown = new ArrayList<>();
    private final List<String[]> months = new ArrayList<>();
    private final java.util.Random random = new java.util.Random();
    private static final int PAGE = 60, BATCH = 6, RECENT = 200;
    private final java.util.Map<String, String> cursors = new java.util.HashMap<>();
    private final java.util.Set<String> recent = new java.util.LinkedHashSet<>();
    private boolean first = true;
    private int pos = -1, total;

    PhotoStream(Context c, String size) {
        context = c.getApplicationContext();
        this.size = size;
    }

    static boolean available(Context c) {
        return ArcaHub.has(c, "photos");
    }

    @Override public void next(int dir, ArcaHub.Callback<Photo> callback) {
        io.execute(() -> {
            Photo result = null;
            for (int attempt = 0; attempt < 3 && result == null; attempt++) {
                String[] photo = pick(dir);
                if (photo == null) break;
                Bitmap bitmap = fetch(photo);
                if (bitmap != null) result = new Photo(bitmap, caption(photo[3]));
            }
            final Photo shown = result;
            main.post(() -> callback.done(shown, shown == null ? ArcaHub.UNREACHABLE : null));
            if (queue.size() < 2) refill();
        });
    }

    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"};

    static String caption(String iso) {
        String s = iso == null ? "" : iso.trim();
        try {
            boolean utc = s.endsWith("Z"), offset = !utc && s.matches(".*T.*[+-]\\d{2}:\\d{2}$");
            if (s.length() >= 19 && s.charAt(10) == 'T' && (utc || offset)) {
                java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US);
                java.util.Date date = in.parse(s.substring(0, 19) + (utc ? "+00:00" : s.substring(s.length() - 6)));
                return new java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.US).format(date);
            }
            if (s.length() >= 10 && s.charAt(4) == '-' && s.charAt(7) == '-') {
                return Integer.parseInt(s.substring(8, 10)) + " " + MONTHS[Integer.parseInt(s.substring(5, 7)) - 1] + " " + s.substring(0, 4);
            }
            if (s.length() >= 7 && s.charAt(4) == '-') return MONTHS[Integer.parseInt(s.substring(5, 7)) - 1] + " " + s.substring(0, 4);
            if (s.length() == 4 && Integer.parseInt(s) > 0) return s;
        } catch (Exception ignored) {
        }
        return "";
    }

    @Override public void close() {
        io.shutdownNow();
    }

    private String[] pick(int dir) {
        if (dir < 0 && pos > 0) return shown.get(--pos);
        if (pos + 1 < shown.size()) return shown.get(++pos);
        if (queue.isEmpty()) refill();
        if (queue.isEmpty()) return null;
        String[] photo = queue.remove(queue.size() - 1);
        shown.add(photo);
        if (shown.size() > 50) shown.remove(0);
        pos = shown.size() - 1;
        return photo;
    }

    private void refill() {
        try {
            String volume = ArcaHub.volume(context, "photos");
            if (first) {
                JSONObject head = new JSONObject(ArcaHub.get(context, "/v1/gallery?volume=" + URLEncoder.encode(volume, "UTF-8"), 4_000_000));
                JSONArray timeline = head.optJSONArray("timeline");
                for (int i = 0; timeline != null && i < timeline.length(); i++) {
                    JSONObject m = timeline.getJSONObject(i);
                    if (m.optInt("count") <= 0) continue;
                    months.add(new String[] {m.optString("month"), String.valueOf(m.optInt("count"))});
                    total += m.optInt("count");
                }
                first = false;
            }
            if (months.isEmpty()) return;
            for (int i = 0; i < BATCH; i++) {
                try {
                    String[] photo = randomPhoto(volume);
                    if (photo != null) queue.add(0, photo);
                } catch (Exception e) {
                    AppLog.w("arca photos pick: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            AppLog.w("arca photos list: " + e.getMessage());
        }
    }

    private String[] randomPhoto(String volume) throws Exception {
        int pick = random.nextInt(total), before = 0;
        String month = months.get(0)[0];
        for (String[] m : months) {
            int count = Integer.parseInt(m[1]);
            if (pick < before + count) {
                month = m[0];
                break;
            }
            before += count;
        }
        int offset = pick - before;
        JSONArray items = page(volume, month, offset / PAGE);
        if (items == null || items.length() == 0) return null;
        int start = Math.min(offset % PAGE, items.length() - 1);
        for (int step = 0; step < items.length(); step++) {
            int i = (start + step) % items.length();
            JSONObject o = items.getJSONObject(i);
            String hash = o.optString("hash");
            if (o.optString("kind").toLowerCase().contains("video") || recent.contains(hash)) continue;
            recent.add(hash);
            if (recent.size() > RECENT) recent.remove(recent.iterator().next());
            return new String[] {volume, o.getString("path"), hash, o.optString("date")};
        }
        return null;
    }

    private JSONArray page(String volume, String month, int index) throws Exception {
        String base = "/v1/gallery?volume=" + URLEncoder.encode(volume, "UTF-8");
        int known = index;
        while (known > 0 && !cursors.containsKey(month + "#" + known)) known--;
        String query = known == 0 ? base + "&month=" + URLEncoder.encode(month, "UTF-8") : base + "&after=" + URLEncoder.encode(cursors.get(month + "#" + known), "UTF-8");
        while (true) {
            JSONObject gallery = new JSONObject(ArcaHub.get(context, query, 4_000_000));
            String next = gallery.isNull("next") ? "" : gallery.optString("next", "");
            if (!next.isEmpty()) cursors.put(month + "#" + (known + 1), next);
            if (known == index || next.isEmpty()) return gallery.optJSONArray("items");
            known++;
            query = base + "&after=" + URLEncoder.encode(next, "UTF-8");
        }
    }

    private Bitmap fetch(String[] p) {
        try {
            String path = "/v1/gallery/preview?volume=" + URLEncoder.encode(p[0], "UTF-8") + "&path=" + URLEncoder.encode(p[1], "UTF-8")
                    + "&hash=" + URLEncoder.encode(p[2], "UTF-8") + "&size=" + size;
            String data = new JSONObject(ArcaHub.get(context, path, 24_000_000)).optString("data");
            byte[] bytes = Base64.decode(data.substring(data.indexOf(',') + 1), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            AppLog.w("arca photo: " + e.getMessage());
            return null;
        }
    }
}
