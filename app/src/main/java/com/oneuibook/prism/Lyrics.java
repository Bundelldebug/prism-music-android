package com.oneuibook.prism;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Synced lyrics from LRCLIB (the Spotify Web API has no lyrics endpoint). Optional, toggle in settings. */
final class Lyrics {
    private Lyrics() {}

    static final class Line {
        final long time;
        final String text;
        Line(long t, String s) { time = t; text = s; }
    }

    static final class Result {
        final List<Line> lines = new ArrayList<>();
        boolean synced;
    }

    private static final Map<String, Result> CACHE = new HashMap<>();
    /** Tracks already looked up with no lyrics found (this run only — LRCLIB may gain them later). */
    private static final java.util.Set<String> MISSING = new java.util.HashSet<>();
    private static final Map<String, List<Consumer<Result>>> PENDING = new HashMap<>();
    /** Own threads: lyrics never queue behind Spotify API calls or image loads. */
    private static final java.util.concurrent.ExecutorService POOL = java.util.concurrent.Executors.newFixedThreadPool(2);
    private static final Pattern TAG = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]");
    private static final String API = "https://lrclib.net/api/";

    /** Warm the cache as soon as a song starts, so the lyrics pane opens instantly. */
    static void prefetch(Player.Track t) {
        if (t == null || t.id == null || CACHE.containsKey(t.id) || MISSING.contains(t.id)) return;
        fetch(t, null);
    }

    static void fetch(Player.Track t, Consumer<Result> cb) {
        if (t == null || t.id == null) { if (cb != null) cb.accept(null); return; }
        Result c = CACHE.get(t.id);
        if (c != null || MISSING.contains(t.id)) { if (cb != null) cb.accept(c); return; }
        List<Consumer<Result>> waiting = PENDING.get(t.id);
        if (waiting != null) { if (cb != null) waiting.add(cb); return; }   // already on its way
        waiting = new ArrayList<>();
        if (cb != null) waiting.add(cb);
        PENDING.put(t.id, waiting);
        POOL.execute(() -> {
            Result r = readDisk(t.id);
            if (r == null) {
                r = lookup(t);
                if (r != null) writeDisk(t.id, r);
            }
            final Result res = r;
            Spotify.MAIN.post(() -> {
                if (res != null) CACHE.put(t.id, res); else MISSING.add(t.id);
                List<Consumer<Result>> cbs = PENDING.remove(t.id);
                if (cbs != null) for (Consumer<Result> k : cbs) k.accept(res);
            });
        });
    }

    /**
     * Fastest first: LRCLIB's own database (get-cached, no external crawling), then a fuzzy search
     * matched by duration, and only then the slow /get that may crawl other providers.
     */
    private static Result lookup(Player.Track t) {
        String artist = t.artist == null ? "" : (t.artist.contains(",") ? t.artist.substring(0, t.artist.indexOf(',')).trim() : t.artist);
        String name = t.name == null ? "" : t.name;
        long dur = t.duration / 1000;
        String q = "artist_name=" + Spotify.enc(artist) + "&track_name=" + Spotify.enc(name);
        try {
            JSONObject o = getObj(API + "get-cached?" + q + "&album_name=" + Spotify.enc(t.album == null ? "" : t.album) + "&duration=" + dur, 6000);
            Result r = o == null ? null : parse(o.optString("syncedLyrics", ""), o.optString("plainLyrics", ""));
            if (r != null) return r;
        } catch (Exception ignored) {}
        Result r = search(q, dur);
        if (r != null) return r;
        String clean = cleanTitle(name);
        if (!clean.equals(name)) {
            r = search("artist_name=" + Spotify.enc(artist) + "&track_name=" + Spotify.enc(clean), dur);
            if (r != null) return r;
        }
        try {
            JSONObject o = getObj(API + "get?" + q + "&duration=" + dur, 12000);
            return o == null ? null : parse(o.optString("syncedLyrics", ""), o.optString("plainLyrics", ""));
        } catch (Exception e) { return null; }
    }

    /** Best search hit: closest duration (within 4 s), synced lyrics preferred. */
    private static Result search(String q, long dur) {
        try {
            String body = getText(API + "search?" + q, 7000);
            if (body == null) return null;
            org.json.JSONArray a = new org.json.JSONArray(body);
            JSONObject best = null;
            long bestScore = Long.MAX_VALUE;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                long d = Math.abs((long) o.optDouble("duration", 0) - dur);
                if (dur > 0 && d > 4) continue;
                boolean synced = !o.isNull("syncedLyrics") && !o.optString("syncedLyrics", "").isEmpty();
                boolean plain = !o.isNull("plainLyrics") && !o.optString("plainLyrics", "").isEmpty();
                if (!synced && !plain) continue;
                long score = d * 10 + (synced ? 0 : 100);
                if (score < bestScore) { bestScore = score; best = o; }
            }
            return best == null ? null : parse(best.optString("syncedLyrics", ""), best.optString("plainLyrics", ""));
        } catch (Exception e) { return null; }
    }

    /** "Song (feat. X) - 2011 Remaster" → "Song". */
    static String cleanTitle(String s) {
        String r = s.replaceAll("(?i)\\s*[(\\[](feat\\.?|ft\\.?|with|prod\\.?)[^)\\]]*[)\\]]", "");
        r = r.replaceAll("(?i)\\s+-\\s+.*(remaster|version|edit|live|mix|mono|stereo).*$", "");
        return r.trim().isEmpty() ? s : r.trim();
    }

    private static String getText(String url, int timeout) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(5000);
        h.setReadTimeout(timeout);
        h.setRequestProperty("User-Agent", "Prism/2.0 (Android)");
        try {
            if (h.getResponseCode() != 200) return null;
            return Spotify.read(h.getInputStream());
        } finally { h.disconnect(); }
    }

    private static JSONObject getObj(String url, int timeout) throws Exception {
        String s = getText(url, timeout);
        return s == null ? null : new JSONObject(s);
    }

    // ---- disk cache: lyrics survive restarts, so replaying a song never waits on the network ----
    private static java.io.File file(String id) {
        if (Img.appContext == null) return null;
        java.io.File d = new java.io.File(Img.appContext.getCacheDir(), "lyrics");
        d.mkdirs();
        return new java.io.File(d, Integer.toHexString(id.hashCode()) + "_" + id.length() + ".json");
    }

    private static Result readDisk(String id) {
        try {
            java.io.File f = file(id);
            if (f == null || !f.exists()) return null;
            JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            if (!id.equals(o.optString("id"))) return null;
            return parse(o.optString("s", ""), o.optString("p", ""));
        } catch (Exception e) { return null; }
    }

    private static void writeDisk(String id, Result r) {
        try {
            java.io.File f = file(id);
            if (f == null) return;
            StringBuilder s = new StringBuilder(), p = new StringBuilder();
            for (Line l : r.lines) {
                if (r.synced) s.append(String.format(java.util.Locale.US, "[%02d:%02d.%03d]", l.time / 60000, (l.time / 1000) % 60, l.time % 1000)).append(l.text).append('\n');
                else p.append(l.text).append('\n');
            }
            JSONObject o = new JSONObject().put("id", id).put("s", s.toString()).put("p", p.toString());
            java.nio.file.Files.write(f.toPath(), o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    static Result parse(String synced, String plain) {
        Result r = new Result();
        if (synced != null && !synced.isEmpty() && !"null".equals(synced)) {
            for (String raw : synced.split("\n")) {
                Matcher m = TAG.matcher(raw);
                List<Long> times = new ArrayList<>();
                int end = 0;
                while (m.find()) {
                    long ms = Long.parseLong(m.group(1)) * 60000L + Long.parseLong(m.group(2)) * 1000L;
                    if (m.group(3) != null) {
                        String f = m.group(3);
                        ms += f.length() == 2 ? Long.parseLong(f) * 10 : (f.length() == 1 ? Long.parseLong(f) * 100 : Long.parseLong(f.substring(0, 3)));
                    }
                    times.add(ms);
                    end = m.end();
                }
                String text = raw.substring(end).trim();
                for (long tm : times) r.lines.add(new Line(tm, text));
            }
            r.lines.sort((a, b) -> Long.compare(a.time, b.time));
            r.synced = !r.lines.isEmpty();
        }
        if (r.lines.isEmpty() && plain != null && !plain.isEmpty() && !"null".equals(plain)) {
            for (String s : plain.split("\n")) r.lines.add(new Line(-1, s.trim()));
        }
        return r.lines.isEmpty() ? null : r;
    }
}
