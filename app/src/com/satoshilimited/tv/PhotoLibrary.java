package com.satoshilimited.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class PhotoLibrary {
    static final class Item {
        String path = "", hash = "", date = "";
        long rev;
        boolean video;
    }

    static final class Month {
        String key = "";
        int count, videos, first;
    }

    static final class Memory {
        String month = "", title = "", detail = "";
        Item item;
    }

    interface Listener { void onPhotoLibrary(); }

    private static PhotoLibrary instance;

    private static volatile int generation;

    static synchronized PhotoLibrary get(Context c) {
        if (instance == null) instance = new PhotoLibrary(c.getApplicationContext());
        return instance;
    }

    static void forget(Context c) {
        generation++;
        PhotoLibrary library;
        synchronized (PhotoLibrary.class) {
            library = instance;
        }
        if (library != null) {
            library.memory.evictAll();
            library.fetched.clear();
        }
        File dir = new File(c.getCacheDir(), "arca-thumbs");
        new Thread(() -> {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) f.delete();
        }).start();
    }

    void forgetFetched() {
        fetched.clear();
    }

    private static final class LifoQueue extends LinkedBlockingDeque<Runnable> {
        @Override public boolean offer(Runnable r) {
            return offerFirst(r);
        }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pages = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LifoQueue());
    private final ExecutorService loader = new ThreadPoolExecutor(3, 3, 0, TimeUnit.SECONDS, new LifoQueue());
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(48 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Set<String> inFlight = new HashSet<>();
    private final Set<String> fetched = ConcurrentHashMap.newKeySet();
    private final Set<Integer> loadingMonths = new HashSet<>();
    private final Map<Integer, String> cursors = new HashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicBoolean refreshing = new java.util.concurrent.atomic.AtomicBoolean();
    private final File disk;
    final List<Month> months = new ArrayList<>();
    private Item[] items = new Item[0];
    private volatile int wantLow, wantHigh = -1;
    private volatile boolean ready, failed;
    private volatile String signature = "";
    private volatile List<Memory> memories = new ArrayList<>();
    private String memoriesKey = "";
    private volatile long lastStaleReload;
    private int total;

    private PhotoLibrary(Context c) {
        context = c;
        disk = new File(c.getCacheDir(), "arca-thumbs");
        disk.mkdirs();
        trimDisk();
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    boolean ready() { return ready; }
    List<Memory> memories() { return memories; }
    boolean failed() { return failed; }
    int total() { return total; }

    Item item(int index) {
        return index >= 0 && index < items.length ? items[index] : null;
    }

    int monthOf(int index) {
        int lo = 0, hi = months.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (months.get(mid).first <= index) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    void load() {
        if (!ArcaHub.has(context, "photos") || !refreshing.compareAndSet(false, true)) return;
        pages.execute(() -> {
            try {
                String volume = ArcaHub.volume(context, "photos");
                JSONObject gallery = new JSONObject(ArcaHub.get(context, "/v1/gallery?volume=" + URLEncoder.encode(volume, "UTF-8"), 4_000_000));
                JSONArray timeline = gallery.optJSONArray("timeline");
                int sum = 0;
                List<Month> found = new ArrayList<>();
                StringBuilder sig = new StringBuilder();
                for (int i = 0; timeline != null && i < timeline.length(); i++) {
                    JSONObject m = timeline.getJSONObject(i);
                    Month month = new Month();
                    month.key = m.optString("month");
                    month.count = m.optInt("count");
                    month.videos = m.optInt("videos");
                    month.first = sum;
                    sum += month.count;
                    if (month.count > 0) found.add(month);
                    sig.append(month.key).append(':').append(month.count).append(':').append(m.optString("rev")).append(',');
                }
                if (ready && sig.toString().equals(signature)) {
                    failed = false;
                } else {
                    signature = sig.toString();
                    Item[] array = new Item[sum];
                    synchronized (this) {
                        months.clear();
                        months.addAll(found);
                        items = array;
                        total = sum;
                        cursors.clear();
                        loadingMonths.clear();
                    }
                    store(0, gallery);
                    ready = true;
                    failed = false;
                }
            } catch (Exception e) {
                failed = failed || !ready;
                AppLog.w("arca photo library: " + e.getMessage());
            } finally {
                refreshing.set(false);
            }
            notifyListeners();
            if (ready && refreshMemories()) notifyListeners();
        });
    }

    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"};

    private boolean refreshMemories() {
        java.util.Calendar now = java.util.Calendar.getInstance();
        int year = now.get(java.util.Calendar.YEAR);
        String day = String.format(Locale.US, "%02d-%02d", now.get(java.util.Calendar.MONTH) + 1, now.get(java.util.Calendar.DAY_OF_MONTH));
        String key = year + "-" + day + "|" + signature;
        if (key.equals(memoriesKey)) return false;
        List<Memory> found;
        try {
            String volume = ArcaHub.volume(context, "photos");
            try {
                found = hubMemories(volume, day, year);
            } catch (Exception e) {
                found = localMemories(volume, day, year);
            }
        } catch (Exception e) {
            AppLog.w("arca photo memories: " + e.getMessage());
            return false;
        }
        memoriesKey = key;
        memories = found;
        return true;
    }

    private List<Memory> hubMemories(String volume, String day, int year) throws Exception {
        List<Memory> out = new ArrayList<>();
        JSONObject o = new JSONObject(ArcaHub.get(context, "/v1/gallery/memories?volume=" + URLEncoder.encode(volume, "UTF-8") + "&day=" + day + "&year=" + year, 1_000_000));
        JSONArray list = o.optJSONArray("memories");
        for (int i = 0; list != null && i < list.length() && out.size() < 4; i++) {
            JSONObject m = list.getJSONObject(i);
            String cursor = m.optString("cursor"), date = cursor.contains("|") ? cursor.substring(0, cursor.indexOf('|')) : "";
            Memory card = card(date, m.optInt("count", 1), m, year);
            if (card != null) out.add(card);
        }
        return out;
    }

    private List<Memory> localMemories(String volume, String day, int year) throws Exception {
        List<Memory> out = new ArrayList<>();
        String month = day.substring(0, 2);
        List<Month> candidates = new ArrayList<>();
        synchronized (this) {
            for (Month m : months) if (m.key.endsWith("-" + month) && m.key.compareTo(year + "") < 0) candidates.add(m);
        }
        for (Month m : candidates) {
            if (out.size() >= 4) break;
            String prefix = m.key + "-" + day.substring(3), query = "&month=" + m.key;
            JSONObject cover = null;
            int count = 0;
            boolean done = false;
            for (int guard = 0; guard < 40 && !done; guard++) {
                JSONObject gallery = new JSONObject(ArcaHub.get(context, "/v1/gallery?volume=" + URLEncoder.encode(volume, "UTF-8") + query, 4_000_000));
                JSONArray list = gallery.optJSONArray("items");
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject o = list.getJSONObject(i);
                    String date = o.optString("date");
                    if (date.startsWith(prefix)) {
                        count++;
                        cover = o;
                    } else if (!date.startsWith(m.key) || date.compareTo(prefix) < 0) {
                        done = true;
                        break;
                    }
                }
                String next = gallery.isNull("next") ? "" : gallery.optString("next", "");
                if (next.isEmpty()) break;
                query = "&after=" + URLEncoder.encode(next, "UTF-8");
            }
            if (cover == null) continue;
            Memory card = card(cover.optString("date"), count, cover, year);
            if (card != null) out.add(card);
        }
        return out;
    }

    private static Memory card(String date, int count, JSONObject photo, int year) {
        if (date.length() < 10) return null;
        int y = Integer.parseInt(date.substring(0, 4)), ago = year - y;
        Memory card = new Memory();
        card.month = date.substring(0, 7);
        card.title = MONTHS[Integer.parseInt(date.substring(5, 7)) - 1] + " " + Integer.parseInt(date.substring(8, 10)) + ", " + y;
        card.detail = ago + (ago == 1 ? " year ago · " : " years ago · ") + count + (count == 1 ? " photo" : " photos");
        card.item = new Item();
        card.item.path = photo.optString("path");
        card.item.hash = photo.optString("hash");
        card.item.date = date;
        card.item.video = photo.optString("kind").toLowerCase(Locale.ROOT).contains("video");
        return card;
    }

    Bitmap memoryThumb(Memory m) {
        String key = m.item.hash + "t";
        Bitmap b = memory.get(key);
        if (b == null) request(-1, m.item, "thumb", key);
        return b;
    }

    int indexOfHash(String hash, int from, int to) {
        Item[] array = items;
        for (int i = Math.max(0, from); i < Math.min(to, array.length); i++) {
            if (array[i] != null && array[i].hash.equals(hash)) return i;
        }
        return -1;
    }

    private void staleReload() {
        long now = System.currentTimeMillis();
        if (now - lastStaleReload < 10_000) return;
        lastStaleReload = now;
        signature = "";
        load();
    }

    private boolean store(int start, JSONObject gallery) {
        JSONArray list = gallery.optJSONArray("items");
        int n = list == null ? 0 : list.length();
        for (int i = 0; i < n && start + i < items.length; i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            String date = o.optString("date");
            Month month = months.get(monthOf(start + i));
            if (!date.startsWith(month.key)) {
                staleReload();
                return false;
            }
        }
        for (int i = 0; i < n && start + i < items.length; i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            Item it = new Item();
            it.path = o.optString("path");
            it.hash = o.optString("hash");
            it.rev = o.optLong("rev");
            it.date = o.optString("date");
            it.video = o.optString("kind").toLowerCase(Locale.ROOT).contains("video");
            items[start + i] = it;
        }
        String next = gallery.isNull("next") ? "" : gallery.optString("next", "");
        if (!next.isEmpty()) cursors.put(start + n, next);
        return true;
    }

    void want(int low, int high) {
        wantLow = low;
        wantHigh = high;
    }

    void ensure(int index) {
        if (!ready || index < 0 || index >= total || items[index] != null) return;
        int month = monthOf(index);
        int key = months.get(month).first;
        synchronized (loadingMonths) {
            if (!loadingMonths.add(key * 1000 + Math.min(999, (index - key) / 60))) return;
        }
        pages.execute(() -> {
            try {
                loadFor(index);
            } catch (Exception e) {
                AppLog.w("arca photo page: " + e.getMessage());
            }
            synchronized (loadingMonths) {
                loadingMonths.clear();
            }
            notifyListeners();
        });
    }

    void loadFor(int index) throws Exception {
        if (items[index] != null) return;
        String volume = ArcaHub.volume(context, "photos");
        int start = index;
        while (start > 0 && items[start - 1] == null && !cursors.containsKey(start)) start--;
        String query;
        int at;
        if (cursors.containsKey(start)) {
            query = "&after=" + URLEncoder.encode(cursors.get(start), "UTF-8");
            at = start;
        } else {
            Month month = months.get(monthOf(index));
            query = "&month=" + month.key;
            at = month.first;
        }
        for (int guard = 0; guard < 80 && items[index] == null; guard++) {
            JSONObject gallery = new JSONObject(ArcaHub.get(context, "/v1/gallery?volume=" + URLEncoder.encode(volume, "UTF-8") + query, 4_000_000));
            if (!store(at, gallery)) break;
            JSONArray list = gallery.optJSONArray("items");
            int n = list == null ? 0 : list.length();
            String next = cursors.get(at + n);
            if (n == 0 || next == null) break;
            query = "&after=" + URLEncoder.encode(next, "UTF-8");
            at += n;
        }
    }

    Bitmap thumb(int index) {
        Item it = item(index);
        if (it == null) {
            ensure(index);
            return null;
        }
        String key = it.hash + "t";
        Bitmap b = memory.get(key);
        if (b != null) return b;
        request(index, it, "thumb", key);
        return null;
    }

    boolean fetched(String hash) {
        return fetched.remove(hash + "t");
    }

    Bitmap cachedThumb(int index) {
        Item it = item(index);
        return it == null ? null : memory.get(it.hash + "t");
    }

    private void request(int index, Item it, String size, String key) {
        synchronized (inFlight) {
            if (!inFlight.add(key)) return;
        }
        int requestGeneration = generation;
        loader.execute(() -> {
            try {
                if (index >= 0 && (index < wantLow || index > wantHigh)) return;
                Bitmap b = fromDisk(key);
                if (b == null) {
                    b = fetch(it, size, key, requestGeneration == generation);
                    if (requestGeneration != generation) {
                        new File(disk, key + ".jpg").delete();
                        return;
                    }
                    if (b != null) {
                        if (fetched.size() >= 512) fetched.clear();
                        fetched.add(key);
                    }
                }
                if (b != null) {
                    memory.put(key, b);
                    notifyListeners();
                }
            } finally {
                synchronized (inFlight) {
                    inFlight.remove(key);
                }
            }
        });
    }

    private Bitmap fromDisk(String key) {
        File f = new File(disk, key + ".jpg");
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) f.delete();
        else f.setLastModified(System.currentTimeMillis());
        return b;
    }

    Bitmap fetch(Item it, String size, String key, boolean save) {
        try {
            String volume = ArcaHub.volume(context, "photos");
            String path = "/v1/gallery/preview?volume=" + URLEncoder.encode(volume, "UTF-8") + "&path=" + URLEncoder.encode(it.path, "UTF-8")
                    + "&hash=" + URLEncoder.encode(it.hash, "UTF-8") + "&size=" + size;
            String data = new JSONObject(ArcaHub.get(context, path, 24_000_000)).optString("data");
            byte[] bytes = Base64.decode(data.substring(data.indexOf(',') + 1), Base64.DEFAULT);
            if (save) {
                try (FileOutputStream out = new FileOutputStream(new File(disk, key + ".jpg"))) {
                    out.write(bytes);
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            if (save) o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (Exception e) {
            AppLog.w("arca photo preview: " + e.getMessage());
            return null;
        }
    }

    private void trimDisk() {
        loader.execute(() -> {
            File[] files = disk.listFiles();
            if (files == null || files.length < 8000) return;
            java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < files.length - 6000; i++) files[i].delete();
        });
    }

    private void notifyListeners() {
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onPhotoLibrary();
        });
    }
}
