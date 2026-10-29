package com.satoshilimited.tv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

final class VideoLibrary {
    static final int MAX_BYTES = 24_000_000;

    static final class Audio {
        int stream, channels;
        String codec = "", language = "", label = "";
        boolean preferred;
    }

    static final class Sub {
        String path = "", language = "", label = "";
        int stream = -1;
        boolean embedded, forced, image;
    }

    static final class Entry {
        String id = "", reason = "", title = "", caption = "", tag = "", art = "";
        float progress;
    }

    static final class Watch {
        final List<Item> items = new ArrayList<>();
        final Map<String, Entry> entry = new HashMap<>();

        boolean isNext(Item it) {
            Entry e = entry.get(it.id);
            return e != null && e.reason.equals("next");
        }

        boolean resume(Item it) {
            Entry e = entry.get(it.id);
            return e == null || e.reason.equals("resume");
        }

        boolean page(Item it) {
            Entry e = entry.get(it.id);
            return e != null && (e.reason.equals("related") || e.reason.equals("new") || e.reason.equals("waiting"));
        }

        String title(Item it) {
            Entry e = entry.get(it.id);
            return e == null ? it.title : e.title;
        }

        String caption(Item it) {
            Entry e = entry.get(it.id);
            return e == null ? "" : e.caption;
        }

        String tag(Item it) {
            Entry e = entry.get(it.id);
            return e == null ? null : e.tag.equals("next") ? "Next" : e.tag.equals("new") ? "New" : null;
        }

        String art(Item it) {
            Entry e = entry.get(it.id);
            return e == null ? "" : e.art;
        }

        float progress(Item it) {
            Entry e = entry.get(it.id);
            return e == null ? 0f : e.progress;
        }
    }

    static final Set<String> REASONS = new HashSet<>(Arrays.asList("resume", "next", "related", "new", "waiting"));

    final List<Entry> watchNext = new CopyOnWriteArrayList<>();
    private final Map<String, Object[]> hiddenEntries = new HashMap<>();

    static final class Progress {
        int done, total;
    }

    static final class Season {
        int number;
        String name = "";
        final List<Item> episodes = new ArrayList<>();
    }

    static final class Item {
        String id = "", kind = "", title = "", overview = "", certification = "", collection = "", poster = "", backdrop = "", still = "";
        String show = "", added = "", aired = "", path = "", hash = "", container = "", videoCodec = "", sortKey;
        int year, runtime, season, episode, width, height;
        long rev;
        double rating, duration, position, positionDuration;
        String ratingText = "";
        boolean kids, watched;
        final List<String> genres = new ArrayList<>(), cast = new ArrayList<>(), audioLanguages = new ArrayList<>(), subtitleLanguages = new ArrayList<>();
        final List<Audio> audio = new ArrayList<>();
        final List<Sub> subtitles = new ArrayList<>();
        final List<Season> seasons = new ArrayList<>();
        final List<String> related = new ArrayList<>();

        boolean playable() {
            return !hash.isEmpty() && !path.isEmpty();
        }

        boolean isShow() {
            return "show".equals(kind);
        }

        boolean isEpisode() {
            return "episode".equals(kind);
        }

        boolean resumable() {
            return position > 0;
        }

        float progress() {
            double total = positionDuration > 0 ? positionDuration : duration;
            return total > 0 && position > 0 ? (float) Math.min(1, position / total) : 0;
        }

        int channels() {
            for (Audio a : audio) if (a.preferred) return a.channels;
            return audio.isEmpty() ? 0 : audio.get(0).channels;
        }

        String quality() {
            if (height >= 2000 || width >= 3600) return "4K";
            return height >= 700 || width >= 1200 ? "HD" : "";
        }

        String sound() {
            int ch = channels();
            return ch >= 8 ? "7.1" : ch >= 6 ? "5.1" : "";
        }

        int minutes() {
            if (runtime > 0) return runtime;
            return duration > 0 ? (int) Math.round(duration / 60) : 0;
        }
    }

    private static final Pattern ARTICLE = Pattern.compile("^(?:the|an?|el|la|los|las|le|les|der|die|das)\\s+(?=\\S)", Pattern.CASE_INSENSITIVE);
    private static final Collator COLLATOR = Collator.getInstance(Locale.ROOT);

    static {
        COLLATOR.setStrength(Collator.PRIMARY);
    }

    static final Comparator<Item> BY_TITLE = (a, b) -> {
        int order = COLLATOR.compare(sortKey(a), sortKey(b));
        return order != 0 ? order : COLLATOR.compare(a.title, b.title);
    };

    private static String sortKey(Item it) {
        if (it.sortKey == null) it.sortKey = ARTICLE.matcher(it.title.trim()).replaceFirst("");
        return it.sortKey;
    }

    private static VideoLibrary current;
    private static volatile long generation;
    private static final CopyOnWriteArraySet<Runnable> LISTENERS = new CopyOnWriteArraySet<>();

    static void addListener(Runnable r) {
        LISTENERS.add(r);
    }

    static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    private static void changed() {
        for (Runnable r : LISTENERS) r.run();
    }

    final String volume;
    String version = "", tmdb = "none";
    Progress indexing, matching;
    final List<Item> movies = new ArrayList<>(), grownMovies = new ArrayList<>(), shows = new ArrayList<>(), others = new ArrayList<>();
    final List<String> continueIds = new CopyOnWriteArrayList<>(), nextUpIds = new CopyOnWriteArrayList<>();
    private List<Item> kidsCache = new ArrayList<>();
    private final Map<String, Item> byId = new HashMap<>();
    private final Map<String, Item> showOfEpisode = new HashMap<>();

    private VideoLibrary(String volume) {
        this.volume = volume;
    }

    static synchronized VideoLibrary current() {
        return current;
    }

    static synchronized VideoLibrary cached(Context c) {
        String volume = ArcaHub.volume(c, "video");
        if (volume.isEmpty()) return null;
        synchronized (VideoLibrary.class) {
            if (current != null && current.volume.equals(volume)) return current;
        }
        try {
            File f = file(c, volume);
            if (!f.exists()) return null;
            VideoLibrary lib = parse(volume, new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
            synchronized (VideoLibrary.class) {
                if (current == null || !current.volume.equals(volume)) current = lib;
                return current;
            }
        } catch (Exception e) {
            AppLog.w("arca video cache: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static final Object FETCHING = new Object();

    static VideoLibrary fetch(Context c) throws Exception {
        synchronized (FETCHING) {
            return fetchNow(c);
        }
    }

    private static VideoLibrary fetchNow(Context c) throws Exception {
        long requestGeneration = generation;
        String volume = ArcaHub.volume(c, "video");
        if (volume.isEmpty()) throw new IOException("no video folder");
        VideoLibrary known = cached(c);
        String path = "/v1/video/library?volume=" + URLEncoder.encode(volume, "UTF-8");
        if (known != null && !known.version.isEmpty()) path += "&version=" + URLEncoder.encode(known.version, "UTF-8");
        String body = ArcaHub.get(c, path, MAX_BYTES);
        JSONObject o = new JSONObject(body);
        synchronized (VideoLibrary.class) {
            if (requestGeneration != generation || !volume.equals(ArcaHub.volume(c, "video"))) throw new IOException("video library changed");
            if (o.optBoolean("unchanged") && known != null) {
                known.status(o);
                return known;
            }
            VideoLibrary lib = parse(volume, o);
            current = lib;
            try {
                File f = file(c, volume);
                File tmp = new File(f.getPath() + ".tmp");
                Files.write(tmp.toPath(), body.getBytes(StandardCharsets.UTF_8));
                if (!tmp.renameTo(f)) tmp.delete();
            } catch (Exception e) {
                AppLog.w("arca video cache not saved: " + e.getClass().getSimpleName());
            }
            return lib;
        }
    }

    static synchronized void forget(Context c) {
        generation++;
        current = null;
        File[] files = c.getCacheDir().listFiles((dir, name) -> name.startsWith("video-library-"));
        if (files != null) for (File f : files) f.delete();
        VideoArt.forget(c);
        Watching.forget();
    }

    private static File file(Context c, String volume) {
        return new File(c.getCacheDir(), "video-library-" + java.util.UUID.nameUUIDFromBytes(volume.getBytes(StandardCharsets.UTF_8)).toString() + ".json");
    }

    private void status(JSONObject o) {
        version = o.isNull("version") ? version : o.optString("version", version);
        tmdb = o.isNull("tmdb") ? "none" : o.optString("tmdb", tmdb);
        indexing = progress(o.opt("indexing"));
        matching = progress(o.opt("matching"));
    }

    private static Progress progress(Object v) {
        if (!(v instanceof JSONObject)) return null;
        Progress p = new Progress();
        p.done = ((JSONObject) v).optInt("done");
        p.total = ((JSONObject) v).optInt("total");
        return p;
    }

    static VideoLibrary parse(String volume, JSONObject root) throws Exception {
        VideoLibrary lib = new VideoLibrary(volume);
        lib.status(root);
        JSONArray list = root.optJSONArray("movies");
        for (int i = 0; list != null && i < list.length(); i++) lib.add(lib.movies, item(list.getJSONObject(i), "movie"));
        list = root.optJSONArray("other");
        for (int i = 0; list != null && i < list.length(); i++) lib.add(lib.others, item(list.getJSONObject(i), "other"));
        list = root.optJSONArray("shows");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            Item show = item(o, "show");
            JSONArray seasons = o.optJSONArray("seasons");
            for (int s = 0; seasons != null && s < seasons.length(); s++) {
                JSONObject so = seasons.getJSONObject(s);
                Season season = new Season();
                season.number = so.optInt("season");
                season.name = so.isNull("name") || so.optString("name", "").isEmpty() ? (season.number == 0 ? "Specials" : "Season " + season.number) : so.optString("name");
                JSONArray eps = so.optJSONArray("episodes");
                for (int e = 0; eps != null && e < eps.length(); e++) {
                    Item ep = item(eps.getJSONObject(e), "episode");
                    if (ep.show.isEmpty()) ep.show = show.id;
                    season.episodes.add(ep);
                    lib.byId.put(ep.id, ep);
                    lib.showOfEpisode.put(ep.id, show);
                }
                if (!season.episodes.isEmpty()) show.seasons.add(season);
            }
            show.seasons.sort((a, b) -> Boolean.compare(a.number == 0, b.number == 0));
            lib.add(lib.shows, show);
        }
        strings(root.optJSONArray("continue"), lib.continueIds);
        strings(root.optJSONArray("nextUp"), lib.nextUpIds);
        JSONArray watchNext = root.optJSONArray("watchNext");
        for (int i = 0; watchNext != null && i < watchNext.length(); i++) {
            JSONObject e = watchNext.optJSONObject(i);
            if (e == null || !REASONS.contains(e.optString("reason"))) continue;
            Entry entry = new Entry();
            entry.id = e.optString("id");
            entry.reason = e.optString("reason");
            entry.title = e.isNull("title") ? "" : e.optString("title");
            entry.caption = e.isNull("caption") ? "" : e.optString("caption");
            entry.tag = e.isNull("tag") ? "" : e.optString("tag");
            entry.art = e.isNull("art") ? "" : e.optString("art");
            entry.progress = e.isNull("progress") ? 0f : (float) Math.max(0, Math.min(100, e.optDouble("progress", 0))) / 100f;
            lib.watchNext.add(entry);
        }
        Collections.sort(lib.movies, BY_TITLE);
        Collections.sort(lib.shows, BY_TITLE);
        Collections.sort(lib.others, BY_TITLE);
        for (Item it : lib.movies) if (!it.kids) lib.grownMovies.add(it);
        lib.indexKids();
        return lib;
    }

    private void add(List<Item> into, Item it) {
        into.add(it);
        byId.put(it.id, it);
    }

    private static void strings(JSONArray a, List<String> out) {
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optString(i));
    }

    private static Item item(JSONObject o, String kind) {
        Item it = new Item();
        it.kind = kind;
        it.id = str(o, "id");
        it.title = str(o, "title");
        if (it.title.isEmpty()) it.title = name(str(o, "path"));
        it.year = o.optInt("year");
        it.overview = str(o, "overview");
        it.certification = str(o, "certification");
        it.collection = str(o, "collection");
        it.rating = o.optDouble("rating", 0);
        if (Double.isNaN(it.rating)) it.rating = 0;
        it.ratingText = it.rating > 0 ? String.format(java.util.Locale.US, "%.1f", it.rating) : "";
        it.runtime = o.optInt("runtime");
        it.kids = o.optBoolean("kids");
        it.poster = key(o, "poster");
        it.backdrop = key(o, "backdrop");
        it.still = key(o, "still");
        it.show = str(o, "show");
        it.season = o.optInt("season");
        it.episode = o.optInt("episode");
        it.aired = str(o, "aired");
        it.added = str(o, "added");
        strings(o.optJSONArray("genres"), it.genres);
        strings(o.optJSONArray("cast"), it.cast);
        strings(o.optJSONArray("audioLanguages"), it.audioLanguages);
        strings(o.optJSONArray("subtitleLanguages"), it.subtitleLanguages);
        it.path = str(o, "path");
        it.hash = str(o, "hash");
        it.rev = o.optLong("rev");
        it.container = str(o, "container");
        it.videoCodec = str(o, "videoCodec");
        strings(o.optJSONArray("related"), it.related);
        it.duration = o.optDouble("duration", 0);
        if (Double.isNaN(it.duration)) it.duration = 0;
        it.width = o.optInt("width");
        it.height = o.optInt("height");
        it.watched = o.optBoolean("watched");
        JSONObject pos = o.optJSONObject("position");
        if (pos != null) {
            it.position = pos.optDouble("position", 0);
            it.positionDuration = pos.optDouble("duration", 0);
        }
        JSONArray tracks = o.optJSONArray("audioTracks");
        for (int i = 0; tracks != null && i < tracks.length(); i++) {
            JSONObject t = tracks.optJSONObject(i);
            if (t == null) continue;
            Audio a = new Audio();
            a.stream = t.optInt("stream");
            a.codec = str(t, "codec");
            a.language = str(t, "language");
            a.label = str(t, "label");
            a.channels = t.optInt("channels");
            a.preferred = t.optBoolean("default");
            it.audio.add(a);
        }
        JSONArray subs = o.optJSONArray("subtitles");
        for (int i = 0; subs != null && i < subs.length(); i++) {
            JSONObject t = subs.optJSONObject(i);
            if (t == null) continue;
            Sub s = new Sub();
            s.path = str(t, "path");
            s.language = str(t, "language");
            s.label = str(t, "label");
            s.embedded = t.optBoolean("embedded");
            s.stream = t.optInt("stream", -1);
            s.forced = t.optBoolean("forced");
            s.image = t.optBoolean("image");
            it.subtitles.add(s);
        }
        return it;
    }

    private static String str(JSONObject o, String name) {
        return o.isNull(name) ? "" : o.optString(name, "");
    }

    private static String key(JSONObject o, String name) {
        return o.isNull(name) ? "" : o.optString(name, "");
    }

    private static String name(String path) {
        String n = path.substring(path.lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    Item find(String id) {
        return byId.get(id);
    }

    Item showOf(Item episode) {
        Item s = showOfEpisode.get(episode.id);
        return s != null ? s : byId.get(episode.show);
    }

    boolean empty() {
        return movies.isEmpty() && shows.isEmpty() && others.isEmpty();
    }

    boolean hasKids() {
        return !kidsCache.isEmpty();
    }

    List<Item> kids() {
        return kidsCache;
    }

    private void indexKids() {
        List<Item> out = new ArrayList<>();
        for (Item it : movies) if (it.kids) out.add(it);
        Collections.sort(out, BY_TITLE);
        kidsCache = out;
    }

    List<Item> resolve(List<String> ids) {
        List<Item> out = new ArrayList<>();
        for (String id : ids) {
            Item it = byId.get(id);
            if (it != null) out.add(it);
        }
        return out;
    }

    List<Item> continueWatching() {
        return resolve(continueIds);
    }

    List<Item> nextUp() {
        return resolve(nextUpIds);
    }

    List<Item> related(Item it) {
        return resolve(it.related);
    }

    Watch watchingRow() {
        Watch w = new Watch();
        for (Entry e : watchNext) {
            Item it = byId.get(e.id);
            if (it == null || w.entry.containsKey(it.id)) continue;
            w.items.add(it);
            w.entry.put(it.id, e);
        }
        return w;
    }

    List<Item> recentlyAdded(int limit) {
        List<Item> all = new ArrayList<>(grownMovies);
        all.addAll(shows);
        Collections.sort(all, (a, b) -> b.added.compareTo(a.added));
        return new ArrayList<>(all.subList(0, Math.min(limit, all.size())));
    }

    List<Item> kidsPicks(int limit) {
        List<Item> out = new ArrayList<>(kids());
        Collections.sort(out, (a, b) -> b.added.compareTo(a.added));
        return new ArrayList<>(out.subList(0, Math.min(limit, out.size())));
    }

    List<Item> episodes(Item show) {
        List<Item> out = new ArrayList<>();
        for (Season s : show.seasons) out.addAll(s.episodes);
        return out;
    }

    Item next(Item episode) {
        Item show = showOf(episode);
        if (show == null) return null;
        List<Item> all = episodes(show);
        int at = all.indexOf(episode);
        if (at < 0 || at + 1 >= all.size()) return null;
        Item next = all.get(at + 1);
        return next.season == 0 && episode.season != 0 ? null : next;
    }

    Item toResume(Item show) {
        List<Item> all = episodes(show);
        for (String id : continueIds) {
            Item e = byId.get(id);
            if (e != null && e.resumable() && show.id.equals(e.show)) return e;
        }
        for (Item e : all) if (e.resumable()) return e;
        for (String id : nextUpIds) {
            Item e = byId.get(id);
            if (e != null && show.id.equals(e.show)) return e;
        }
        for (Item e : all) if (!e.watched && e.season != 0) return e;
        for (Item e : all) if (!e.watched) return e;
        return all.isEmpty() ? null : all.get(0);
    }

    boolean seen(Item it) {
        if (!it.isShow()) return it.watched;
        if (it.seasons.isEmpty()) return false;
        for (Season s : it.seasons) for (Item e : s.episodes) if (!e.watched && e.season != 0) return false;
        return true;
    }

    boolean seasonWatched(Season s) {
        for (Item e : s.episodes) if (!e.watched) return false;
        return true;
    }

    void progressed(Item it, double position, double duration, boolean finished) {
        if (finished) {
            it.watched = true;
            it.position = 0;
            continueIds.remove(it.id);
            dropWatchNext(it.id);
        } else if (position >= 10) {
            it.position = position;
            it.positionDuration = duration;
            it.watched = false;
            continueIds.remove(it.id);
            continueIds.add(0, it.id);
            dropWatchNext(it.id);
        }
        if (finished && it.isEpisode()) nextUpIds.remove(it.id);
        changed();
    }

    void marked(Item it, boolean watched) {
        it.watched = watched;
        it.position = 0;
        if (watched) {
            continueIds.remove(it.id);
            Object[] gone = dropWatchNext(it.id);
            if (gone != null) hiddenEntries.put(it.id, gone);
        }
        changed();
    }

    private Object[] dropWatchNext(String id) {
        for (int i = 0; i < watchNext.size(); i++) {
            Entry e = watchNext.get(i);
            if (e.id.equals(id)) {
                watchNext.remove(i);
                return new Object[] {i, e};
            }
        }
        return null;
    }

    boolean hidden(String id) {
        Object[] gone = dropWatchNext(id);
        if (gone == null) return false;
        hiddenEntries.put(id, gone);
        changed();
        return true;
    }

    void unmarked(Item it, boolean watched, double position) {
        it.watched = watched;
        it.position = position;
        if (position > 0 && !continueIds.contains(it.id)) continueIds.add(0, it.id);
        Object[] gone = hiddenEntries.remove(it.id);
        if (gone != null) watchNext.add(Math.min((Integer) gone[0], watchNext.size()), (Entry) gone[1]);
        changed();
    }

    void restored(String id) {
        Object[] gone = hiddenEntries.remove(id);
        if (gone == null) return;
        watchNext.add(Math.min((Integer) gone[0], watchNext.size()), (Entry) gone[1]);
        changed();
    }

    static String left(Item it) {
        double total = it.positionDuration > 0 ? it.positionDuration : it.duration;
        int minutes = (int) Math.max(1, Math.ceil((total - it.position) / 60));
        return (minutes >= 60 ? minutes / 60 + " h " + (minutes % 60 == 0 ? "" : minutes % 60 + " min ") : minutes + " min ") + "left";
    }

    static String length(int minutes) {
        return minutes >= 60 ? minutes / 60 + " h " + (minutes % 60 == 0 ? "" : minutes % 60 + " min") : minutes + " min";
    }
}
