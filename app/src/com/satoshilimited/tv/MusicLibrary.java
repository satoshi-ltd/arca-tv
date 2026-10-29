package com.satoshilimited.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class MusicLibrary {
    static final int RECENT_LIMIT = 12;
    private static final String UNKNOWN_ARTIST = "Unknown artist", UNKNOWN_ALBUM = "Unknown album";

    static final class Track {
        String title = "", artist = "", album = "", albumArtist = "", hash = "", path = "", cover = "";
        int number, disc, year, seconds;
        long rev;
    }

    static final class Album {
        String title = "", artist = "", cover = "";
        int year;
        final List<Track> tracks = new ArrayList<>();

        String key() {
            return artist + "\u0001" + title;
        }
    }

    static final class Artist {
        String name = "", cover = "";
        final List<Album> albums = new ArrayList<>();
        final List<Track> tracks = new ArrayList<>();
    }

    static final class Playlist {
        String name = "";
        final List<Track> tracks = new ArrayList<>();

        String cover() {
            return tracks.isEmpty() ? "" : tracks.get(0).cover;
        }
    }

    final List<Album> albums = new ArrayList<>();
    final List<Artist> artists = new ArrayList<>();
    final List<Playlist> playlists = new ArrayList<>();
    private final Map<String, Album> albumByKey = new HashMap<>();

    static MusicLibrary parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        MusicLibrary lib = new MusicLibrary();
        JSONArray list = root.optJSONArray("tracks");
        Map<String, Track> byPath = new HashMap<>();
        Map<String, Album> albums = new LinkedHashMap<>();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            if ("podcast".equalsIgnoreCase(o.optString("genre"))) continue;
            Track t = new Track();
            t.hash = o.optString("hash");
            if (t.hash.isEmpty()) continue;
            t.path = o.optString("path");
            t.rev = o.optLong("rev");
            t.title = o.optString("title", "");
            if (t.title.isEmpty()) t.title = fileName(t.path);
            t.artist = o.optString("artist", "");
            t.albumArtist = o.optString("albumArtist", o.optString("album_artist", ""));
            t.album = o.optString("album", "");
            t.cover = o.optString("cover", "");
            t.number = o.optInt("track");
            t.disc = o.optInt("disc");
            t.year = o.optInt("year");
            t.seconds = (int) Math.round(o.optDouble("duration", 0));
            byPath.put(t.path, t);
            String artist = !t.albumArtist.isEmpty() ? t.albumArtist : !t.artist.isEmpty() ? t.artist : UNKNOWN_ARTIST;
            String title = t.album.isEmpty() ? UNKNOWN_ALBUM : t.album;
            String key = artist + "\u0001" + title;
            Album a = albums.get(key);
            if (a == null) {
                a = new Album();
                a.artist = artist;
                a.title = title;
                albums.put(key, a);
            }
            a.tracks.add(t);
            if (a.cover.isEmpty()) a.cover = t.cover;
            if (a.year == 0) a.year = t.year;
        }
        Map<String, Artist> artists = new HashMap<>();
        for (Album a : albums.values()) {
            Collections.sort(a.tracks, (x, y) -> {
                int c = Integer.compare(x.disc, y.disc);
                if (c == 0) c = Integer.compare(x.number, y.number);
                return c == 0 ? x.title.compareToIgnoreCase(y.title) : c;
            });
            lib.albums.add(a);
            lib.albumByKey.put(a.key(), a);
            Artist ar = artists.get(a.artist.toLowerCase(Locale.ROOT));
            if (ar == null) {
                ar = new Artist();
                ar.name = a.artist;
                artists.put(a.artist.toLowerCase(Locale.ROOT), ar);
            }
            ar.albums.add(a);
            ar.tracks.addAll(a.tracks);
            if (ar.cover.isEmpty()) ar.cover = a.cover;
        }
        lib.artists.addAll(artists.values());
        Collections.sort(lib.artists, (x, y) -> x.name.compareToIgnoreCase(y.name));
        for (Artist ar : lib.artists) Collections.sort(ar.albums, (x, y) -> y.year != x.year ? Integer.compare(y.year, x.year) : x.title.compareToIgnoreCase(y.title));
        JSONArray lists = root.optJSONArray("playlists");
        for (int i = 0; lists != null && i < lists.length(); i++) {
            JSONObject o = lists.getJSONObject(i);
            Playlist p = new Playlist();
            p.name = o.optString("name", fileName(o.optString("path")));
            JSONArray entries = o.optJSONArray("entries");
            for (int j = 0; entries != null && j < entries.length(); j++) {
                Track t = byPath.get(entries.optString(j, ""));
                if (t != null) p.tracks.add(t);
            }
            if (!p.tracks.isEmpty()) lib.playlists.add(p);
        }
        return lib;
    }

    private static String fileName(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("arca-music", Context.MODE_PRIVATE);
    }

    List<Album> recent(Context c) {
        List<Album> out = new ArrayList<>();
        try {
            JSONArray keys = new JSONArray(prefs(c).getString("recent", "[]"));
            for (int i = 0; i < keys.length() && out.size() < RECENT_LIMIT; i++) {
                Album a = albumByKey.get(keys.getString(i));
                if (a != null) out.add(a);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    void played(Context c, Track t) {
        String artist = !t.albumArtist.isEmpty() ? t.albumArtist : !t.artist.isEmpty() ? t.artist : UNKNOWN_ARTIST;
        String key = artist + "\u0001" + (t.album.isEmpty() ? UNKNOWN_ALBUM : t.album);
        if (!albumByKey.containsKey(key)) return;
        try {
            JSONArray old = new JSONArray(prefs(c).getString("recent", "[]"));
            JSONArray next = new JSONArray().put(key);
            for (int i = 0; i < old.length() && next.length() < RECENT_LIMIT; i++) if (!key.equals(old.getString(i))) next.put(old.getString(i));
            prefs(c).edit().putString("recent", next.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    void remove(Track gone) {
        for (Album a : albums) a.tracks.remove(gone);
        for (Artist ar : artists) {
            ar.tracks.remove(gone);
            for (Album a : ar.albums) a.tracks.remove(gone);
            ar.albums.removeIf(a -> a.tracks.isEmpty());
        }
        for (Playlist p : playlists) p.tracks.remove(gone);
        albums.removeIf(a -> a.tracks.isEmpty());
        artists.removeIf(ar -> ar.tracks.isEmpty());
    }
}
