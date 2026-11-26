package com.oneuibook.prism;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Unified Listening Recap engine for both Local / YouTube Music (persistent on-device play history)
 * and Spotify (Top Tracks / Top Artists across 4 weeks, 6 months, and All time).
 */
final class Recap {
    private Recap() {}

    static final class Entry {
        String id, uri, name, artist, album, art;
        long durationMs;
        int plays;
        long listenedMs;
        long lastPlayedAt;
        String ym; // yyyy-MM
    }

    static final class Summary {
        int totalPlays;
        long totalMs;
        final List<Entry> topTracks = new ArrayList<>();
        final List<Entry> topArtists = new ArrayList<>();
        final List<Entry> topAlbums = new ArrayList<>();
    }

    private static final Object LOCK = new Object();
    private static List<Entry> cache;

    private static File storeFile(Context c) {
        Context ctx = c != null ? c : Img.appContext;
        if (ctx == null) return null;
        return new File(ctx.getFilesDir(), "prism_recap_v1.json");
    }

    private static String currentYm() {
        return new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date());
    }

    private static String currentYear() {
        return new SimpleDateFormat("yyyy", Locale.US).format(new Date());
    }

    private static List<Entry> loadLocked(Context c) {
        if (cache != null) return cache;
        cache = new ArrayList<>();
        try {
            File f = storeFile(c);
            if (f == null || !f.exists()) return cache;
            JSONArray a = new JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Entry e = new Entry();
                e.id = o.optString("id");
                e.uri = o.optString("uri");
                e.name = o.optString("name");
                e.artist = o.optString("artist");
                e.album = o.optString("album");
                e.art = o.optString("art");
                e.durationMs = o.optLong("dur", 180_000L);
                e.plays = o.optInt("plays", 1);
                e.listenedMs = o.optLong("ms", e.durationMs);
                e.lastPlayedAt = o.optLong("ts", 0L);
                e.ym = o.optString("ym", currentYm());
                cache.add(e);
            }
        } catch (Exception ignored) {}
        return cache;
    }

    private static void saveLocked(Context c) {
        try {
            File f = storeFile(c);
            if (f == null || cache == null) return;
            JSONArray a = new JSONArray();
            for (Entry e : cache) {
                a.put(new JSONObject()
                        .put("id", e.id)
                        .put("uri", e.uri)
                        .put("name", e.name)
                        .put("artist", e.artist)
                        .put("album", e.album)
                        .put("art", e.art == null ? "" : e.art)
                        .put("dur", e.durationMs)
                        .put("plays", e.plays)
                        .put("ms", e.listenedMs)
                        .put("ts", e.lastPlayedAt)
                        .put("ym", e.ym));
            }
            java.nio.file.Files.write(f.toPath(), a.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    /** Records a track play into the on-device Recap store (used for Local & YouTube Music). */
    static void recordPlay(Context c, Player.Track t) {
        if (t == null || t.id == null || t.name == null || t.name.isEmpty()) return;
        Context ctx = c != null ? c : Img.appContext;
        if (ctx == null) return;
        final String ym = currentYm();
        final long now = System.currentTimeMillis();
        final long dur = t.duration > 10_000L ? t.duration : 195_000L;
        Spotify.IO.execute(() -> {
            synchronized (LOCK) {
                List<Entry> list = loadLocked(ctx);
                Entry hit = null;
                for (Entry e : list) {
                    if (t.id.equals(e.id) && ym.equals(e.ym)) { hit = e; break; }
                }
                if (hit == null) {
                    hit = new Entry();
                    hit.id = t.id;
                    hit.uri = t.uri;
                    hit.name = t.name;
                    hit.artist = t.artist == null ? "" : t.artist;
                    hit.album = t.album == null ? "" : t.album;
                    hit.art = t.artSmall != null ? t.artSmall : t.art;
                    hit.durationMs = dur;
                    hit.ym = ym;
                    list.add(hit);
                }
                hit.plays++;
                hit.listenedMs += dur;
                hit.lastPlayedAt = now;
                if (hit.art == null || hit.art.isEmpty()) hit.art = t.artSmall != null ? t.artSmall : t.art;
                // Keep store compact (up to 600 monthly-track buckets)
                if (list.size() > 600) {
                    list.sort((x, y) -> Long.compare(y.lastPlayedAt, x.lastPlayedAt));
                    while (list.size() > 500) list.remove(list.size() - 1);
                }
                saveLocked(ctx);
            }
        });
    }

    /** Computes Local/Device Recap summary for period: "month", "year", or "all". */
    static Summary localSummary(Context c, String period) {
        Summary s = new Summary();
        String curYm = currentYm();
        String curY = currentYear();
        Map<String, Entry> tracks = new LinkedHashMap<>();
        Map<String, Entry> artists = new LinkedHashMap<>();
        Map<String, Entry> albums = new LinkedHashMap<>();
        synchronized (LOCK) {
            List<Entry> list = loadLocked(c);
            for (Entry e : list) {
                if ("month".equals(period) && !curYm.equals(e.ym)) continue;
                if ("year".equals(period) && (e.ym == null || !e.ym.startsWith(curY))) continue;
                s.totalPlays += e.plays;
                s.totalMs += e.listenedMs;

                Entry t = tracks.get(e.id);
                if (t == null) {
                    t = copy(e);
                    tracks.put(e.id, t);
                } else {
                    t.plays += e.plays;
                    t.listenedMs += e.listenedMs;
                }
                if (e.artist != null && !e.artist.isEmpty()) {
                    Entry ar = artists.get(e.artist);
                    if (ar == null) {
                        ar = copy(e);
                        ar.name = e.artist;
                        artists.put(e.artist, ar);
                    } else {
                        ar.plays += e.plays;
                        ar.listenedMs += e.listenedMs;
                    }
                }
                if (e.album != null && !e.album.isEmpty()) {
                    Entry al = albums.get(e.album);
                    if (al == null) {
                        al = copy(e);
                        al.name = e.album;
                        albums.put(e.album, al);
                    } else {
                        al.plays += e.plays;
                        al.listenedMs += e.listenedMs;
                    }
                }
            }
        }
        s.topTracks.addAll(tracks.values());
        s.topArtists.addAll(artists.values());
        s.topAlbums.addAll(albums.values());
        Collections.sort(s.topTracks, (a, b) -> Integer.compare(b.plays, a.plays));
        Collections.sort(s.topArtists, (a, b) -> Integer.compare(b.plays, a.plays));
        Collections.sort(s.topAlbums, (a, b) -> Integer.compare(b.plays, a.plays));
        return s;
    }

    /** Seeds test/demo stats synchronously for UI tests. */
    static void seedForTest(Context c, List<Player.Track> demo) {
        synchronized (LOCK) {
            cache = new ArrayList<>();
            String ym = currentYm();
            long now = System.currentTimeMillis();
            for (int i = 0; i < demo.size(); i++) {
                Player.Track t = demo.get(i);
                Entry e = new Entry();
                e.id = t.id;
                e.uri = t.uri;
                e.name = t.name;
                e.artist = t.artist;
                e.album = t.album;
                e.art = t.artSmall != null ? t.artSmall : t.art;
                e.durationMs = t.duration > 0 ? t.duration : 210_000L;
                e.plays = Math.max(2, 18 - i * 3);
                e.listenedMs = e.plays * e.durationMs;
                e.lastPlayedAt = now - i * 60_000L;
                e.ym = ym;
                cache.add(e);
            }
            saveLocked(c);
        }
    }

    private static Entry copy(Entry e) {
        Entry n = new Entry();
        n.id = e.id; n.uri = e.uri; n.name = e.name; n.artist = e.artist;
        n.album = e.album; n.art = e.art; n.durationMs = e.durationMs;
        n.plays = e.plays; n.listenedMs = e.listenedMs; n.lastPlayedAt = e.lastPlayedAt; n.ym = e.ym;
        return n;
    }
}
