package com.oneuibook.prism;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multi-provider synced lyrics fetcher (LRCLIB -> NetEase YRC/LRC -> Kugou LRC -> lyrics.ovh).
 * Supports both line-synced LRC and word/syllable-synced YRC / Enhanced LRC for real-time fill animation.
 */
final class Lyrics {
    private Lyrics() {}

    static final class Word {
        final long start;
        final long duration;
        final String text;
        Word(long s, long d, String t) { start = s; duration = Math.max(1L, d); text = t; }
    }

    static final class Line {
        final long time;
        long endTime;
        final String text;
        final List<Word> words; // null if only line-synced

        Line(long t, String s) { this(t, 0, s, null); }
        Line(long t, long end, String s, List<Word> w) { time = t; endTime = end; text = s; words = w; }
    }

    static final class Result {
        final List<Line> lines = new ArrayList<>();
        boolean synced;
        String source = "";
    }

    private static final Map<String, Result> CACHE = new HashMap<>();
    private static final java.util.Set<String> MISSING = new java.util.HashSet<>();
    private static final Map<String, List<Consumer<Result>>> PENDING = new HashMap<>();
    private static final java.util.concurrent.ExecutorService POOL = java.util.concurrent.Executors.newFixedThreadPool(3);
    private static final Pattern TAG = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]");
    private static final Pattern ELRC_WORD = Pattern.compile("<(\\d+):(\\d+)(?:[.:](\\d+))?>([^<]*)");
    private static final Pattern YRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)$");
    private static final Pattern YRC_WORD = Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^(]*)");
    private static final String LRCLIB = "https://lrclib.net/api/";

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
        if (waiting != null) { if (cb != null) waiting.add(cb); return; }
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
     * Multi-provider lookup chain:
     * 1) LRCLIB (get-cached -> exact search -> cleaned-title search -> fuzzy q search -> get)
     * 2) NetEase Cloud Music (YRC word-synced preferred, then LRC)
     * 3) Kugou Lyrics (LRC synced)
     * 4) lyrics.ovh (plain text fallback)
     * If a provider only returns plain lyrics, we keep it as fallback and still try synced providers first.
     */
    private static Result lookup(Player.Track t) {
        String artist = primaryArtist(t.artist);
        String name = t.name == null ? "" : t.name.trim();
        String clean = cleanTitle(name);
        long durSec = t.duration / 1000L;
        Result plainFallback = null;

        // 1) LRCLIB
        Result r = lookupLrclib(artist, name, clean, t.album, durSec);
        if (r != null && r.synced) return finalizeEndTimes(r, t.duration);
        if (r != null) plainFallback = r;

        // 2) NetEase Cloud Music (great K-Pop / J-Pop / Asian / global coverage + word-level YRC)
        r = lookupNetEase(artist, clean, t.duration);
        if (r != null && r.synced) return finalizeEndTimes(r, t.duration);
        if (r != null && plainFallback == null) plainFallback = r;

        // 3) Kugou Lyrics (strong synced LRC fallback for K-Pop / J-Pop / OST / Pop)
        r = lookupKugou(artist, clean, t.duration);
        if (r != null && r.synced) return finalizeEndTimes(r, t.duration);
        if (r != null && plainFallback == null) plainFallback = r;

        // 4) Plain lyrics fallback: prefer earlier plain hit or query lyrics.ovh
        if (plainFallback != null) return finalizeEndTimes(plainFallback, t.duration);
        r = lookupLyricsOvh(artist, clean);
        return r == null ? null : finalizeEndTimes(r, t.duration);
    }

    private static String primaryArtist(String a) {
        if (a == null) return "";
        int i = a.indexOf(',');
        if (i > 0) a = a.substring(0, i);
        int j = a.indexOf('&');
        if (j > 0) a = a.substring(0, j);
        return a.trim();
    }

    // ---- Provider 1: LRCLIB ----
    private static Result lookupLrclib(String artist, String name, String clean, String album, long dur) {
        String q = "artist_name=" + Spotify.enc(artist) + "&track_name=" + Spotify.enc(name);
        Result plain = null;
        try {
            JSONObject o = getObj(LRCLIB + "get-cached?" + q + "&album_name=" + Spotify.enc(album == null ? "" : album) + "&duration=" + dur, 5000);
            Result r = o == null ? null : parse(o.optString("syncedLyrics", ""), o.optString("plainLyrics", ""));
            if (r != null) { r.source = "LRCLIB"; if (r.synced) return r; plain = r; }
        } catch (Exception ignored) {}
        Result r = searchLrclib(q, dur);
        if (r != null) { r.source = "LRCLIB"; if (r.synced) return r; if (plain == null) plain = r; }
        if (!clean.equals(name)) {
            r = searchLrclib("artist_name=" + Spotify.enc(artist) + "&track_name=" + Spotify.enc(clean), dur);
            if (r != null) { r.source = "LRCLIB"; if (r.synced) return r; if (plain == null) plain = r; }
        }
        // Broad keyword query catches Romanized / Korean / Japanese title variants
        r = searchLrclib("q=" + Spotify.enc((artist + " " + clean).trim()), dur);
        if (r != null) { r.source = "LRCLIB"; if (r.synced) return r; if (plain == null) plain = r; }
        try {
            JSONObject o = getObj(LRCLIB + "get?" + q + "&duration=" + dur, 8000);
            r = o == null ? null : parse(o.optString("syncedLyrics", ""), o.optString("plainLyrics", ""));
            if (r != null) { r.source = "LRCLIB"; if (r.synced) return r; if (plain == null) plain = r; }
        } catch (Exception ignored) {}
        return plain;
    }

    private static Result searchLrclib(String q, long dur) {
        try {
            String body = getText(LRCLIB + "search?" + q, 6000);
            if (body == null) return null;
            JSONArray a = new JSONArray(body);
            JSONObject best = null;
            long bestScore = Long.MAX_VALUE;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                long d = Math.abs((long) o.optDouble("duration", 0) - dur);
                if (dur > 0 && d > 6) continue;
                boolean synced = !o.isNull("syncedLyrics") && !o.optString("syncedLyrics", "").isEmpty();
                boolean plain = !o.isNull("plainLyrics") && !o.optString("plainLyrics", "").isEmpty();
                if (!synced && !plain) continue;
                long score = d * 10 + (synced ? 0 : 100);
                if (score < bestScore) { bestScore = score; best = o; }
            }
            return best == null ? null : parse(best.optString("syncedLyrics", ""), best.optString("plainLyrics", ""));
        } catch (Exception e) { return null; }
    }

    // ---- Provider 2: NetEase Cloud Music (YRC word-sync + LRC) ----
    private static Result lookupNetEase(String artist, String title, long durMs) {
        try {
            String kw = (artist + " " + title).trim();
            if (kw.isEmpty()) return null;
            JSONObject s = getObj("https://music.163.com/api/search/get/web?s=" + Spotify.enc(kw) + "&type=1&limit=8", 6000);
            if (s == null) return null;
            JSONObject res = s.optJSONObject("result");
            JSONArray songs = res == null ? null : res.optJSONArray("songs");
            if (songs == null || songs.length() == 0) return null;
            long bestId = -1, bestDiff = Long.MAX_VALUE;
            for (int i = 0; i < songs.length(); i++) {
                JSONObject item = songs.optJSONObject(i);
                if (item == null) continue;
                long d = item.optLong("duration", 0);
                long diff = durMs > 0 && d > 0 ? Math.abs(d - durMs) : i * 1000L;
                if (durMs > 0 && d > 0 && diff > 8000) continue;
                if (diff < bestDiff) { bestDiff = diff; bestId = item.optLong("id", -1); }
            }
            if (bestId <= 0) bestId = songs.optJSONObject(0).optLong("id", -1);
            if (bestId <= 0) return null;
            JSONObject lyr = getObj("https://music.163.com/api/song/lyric?id=" + bestId + "&lv=1&yv=1", 6000);
            if (lyr == null) return null;
            JSONObject yrc = lyr.optJSONObject("yrc");
            if (yrc != null) {
                Result r = parseYrc(yrc.optString("lyric", ""));
                if (r != null && r.synced) { r.source = "NetEase"; return r; }
            }
            JSONObject lrc = lyr.optJSONObject("lrc");
            if (lrc != null) {
                Result r = parse(lrc.optString("lyric", ""), "");
                if (r != null) { r.source = "NetEase"; return r; }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ---- Provider 3: Kugou Lyrics (LRC synced) ----
    private static Result lookupKugou(String artist, String title, long durMs) {
        try {
            String kw = (artist.isEmpty() ? title : artist + "-" + title).trim();
            if (kw.isEmpty()) return null;
            String url = "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=" + Spotify.enc(kw)
                    + (durMs > 0 ? "&duration=" + durMs : "");
            JSONObject s = getObj(url, 6000);
            JSONArray cands = s == null ? null : s.optJSONArray("candidates");
            if (cands == null || cands.length() == 0) return null;
            JSONObject best = cands.optJSONObject(0);
            if (best == null) return null;
            String id = best.optString("id", "");
            String key = best.optString("accesskey", "");
            if (id.isEmpty() || key.isEmpty()) return null;
            JSONObject dl = getObj("https://lyrics.kugou.com/download?ver=1&client=pc&id=" + Spotify.enc(id)
                    + "&accesskey=" + Spotify.enc(key) + "&fmt=lrc&charset=utf8", 6000);
            if (dl == null) return null;
            String b64 = dl.optString("content", "");
            if (b64.isEmpty()) return null;
            String lrcText = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
            Result r = parse(lrcText, "");
            if (r != null) { r.source = "Kugou"; return r; }
        } catch (Exception ignored) {}
        return null;
    }

    // ---- Provider 4: lyrics.ovh (plain text fallback) ----
    private static Result lookupLyricsOvh(String artist, String title) {
        try {
            if (artist.isEmpty() || title.isEmpty()) return null;
            JSONObject o = getObj("https://api.lyrics.ovh/v1/" + Spotify.enc(artist) + "/" + Spotify.enc(title), 6000);
            if (o == null) return null;
            String plain = o.optString("lyrics", "").trim();
            if (plain.isEmpty()) return null;
            Result r = parse("", plain);
            if (r != null) r.source = "lyrics.ovh";
            return r;
        } catch (Exception ignored) {}
        return null;
    }

    /** "Song (feat. X) - 2011 Remaster" -> "Song". */
    static String cleanTitle(String s) {
        if (s == null) return "";
        String r = s.replaceAll("(?i)\\s*[(\\[](feat\\.?|ft\\.?|with|prod\\.?|ver\\.?|version|remaster|live|ost|from\\s)[^)\\]]*[)\\]]", "");
        r = r.replaceAll("(?i)\\s+-\\s+.*(remaster|version|edit|live|mix|mono|stereo|ver\\.).*$", "");
        return r.trim().isEmpty() ? s : r.trim();
    }

    private static String getText(String url, int timeout) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(4500);
        h.setReadTimeout(timeout);
        h.setRequestProperty("User-Agent", "Prism/9.5 (Android)");
        try {
            if (h.getResponseCode() != 200) return null;
            return Spotify.read(h.getInputStream());
        } finally { h.disconnect(); }
    }

    private static JSONObject getObj(String url, int timeout) throws Exception {
        String s = getText(url, timeout);
        return s == null ? null : new JSONObject(s);
    }

    // ---- disk cache ----
    private static java.io.File file(String id) {
        if (Img.appContext == null) return null;
        java.io.File d = new java.io.File(Img.appContext.getCacheDir(), "lyrics_v2");
        d.mkdirs();
        return new java.io.File(d, Integer.toHexString(id.hashCode()) + "_" + id.length() + ".json");
    }

    private static Result readDisk(String id) {
        try {
            java.io.File f = file(id);
            if (f == null || !f.exists()) return null;
            JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (!id.equals(o.optString("id"))) return null;
            Result r = new Result();
            r.synced = o.optBoolean("synced", false);
            r.source = o.optString("src", "");
            JSONArray la = o.optJSONArray("lines");
            if (la == null) return null;
            for (int i = 0; i < la.length(); i++) {
                JSONObject lo = la.getJSONObject(i);
                List<Word> words = null;
                JSONArray wa = lo.optJSONArray("w");
                if (wa != null && wa.length() > 0) {
                    words = new ArrayList<>(wa.length());
                    for (int j = 0; j < wa.length(); j++) {
                        JSONObject wo = wa.getJSONObject(j);
                        words.add(new Word(wo.optLong("s"), wo.optLong("d"), wo.optString("t")));
                    }
                }
                r.lines.add(new Line(lo.optLong("t"), lo.optLong("e"), lo.optString("x"), words));
            }
            return r.lines.isEmpty() ? null : r;
        } catch (Exception e) { return null; }
    }

    private static void writeDisk(String id, Result r) {
        try {
            java.io.File f = file(id);
            if (f == null) return;
            JSONArray la = new JSONArray();
            for (Line l : r.lines) {
                JSONObject lo = new JSONObject().put("t", l.time).put("e", l.endTime).put("x", l.text);
                if (l.words != null && !l.words.isEmpty()) {
                    JSONArray wa = new JSONArray();
                    for (Word w : l.words) wa.put(new JSONObject().put("s", w.start).put("d", w.duration).put("t", w.text));
                    lo.put("w", wa);
                }
                la.put(lo);
            }
            JSONObject o = new JSONObject().put("id", id).put("synced", r.synced).put("src", r.source).put("lines", la);
            java.nio.file.Files.write(f.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    /** Populates {@link Line#endTime} from the next line's start time (or +4.5s for the final line). */
    static Result finalizeEndTimes(Result r, long trackDurationMs) {
        if (r == null || !r.synced || r.lines.isEmpty()) return r;
        for (int i = 0; i < r.lines.size(); i++) {
            Line cur = r.lines.get(i);
            if (cur.endTime > cur.time) continue;
            if (i + 1 < r.lines.size()) {
                long next = r.lines.get(i + 1).time;
                cur.endTime = next > cur.time ? next : cur.time + 2500L;
            } else {
                long tail = trackDurationMs > cur.time ? Math.min(cur.time + 6000L, trackDurationMs) : cur.time + 4500L;
                cur.endTime = Math.max(cur.time + 1500L, tail);
            }
        }
        return r;
    }

    /** Parses NetEase YRC word-level format: {@code [lineStart,lineDur](wordStart,wordDur,0)text...} */
    static Result parseYrc(String yrc) {
        if (yrc == null || yrc.isEmpty()) return null;
        Result r = new Result();
        for (String raw : yrc.split("\n")) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("{")) continue;
            Matcher lm = YRC_LINE.matcher(trimmed);
            if (!lm.matches()) continue;
            long start = Long.parseLong(lm.group(1));
            long dur = Long.parseLong(lm.group(2));
            String body = lm.group(3);
            Matcher wm = YRC_WORD.matcher(body);
            List<Word> words = new ArrayList<>();
            StringBuilder full = new StringBuilder();
            while (wm.find()) {
                long ws = Long.parseLong(wm.group(1));
                long wd = Long.parseLong(wm.group(2));
                String wt = wm.group(3);
                if (wt == null || wt.isEmpty()) continue;
                words.add(new Word(ws, wd, wt));
                full.append(wt);
            }
            String text = full.toString().trim();
            if (!text.isEmpty()) r.lines.add(new Line(start, start + Math.max(1L, dur), text, words.isEmpty() ? null : words));
        }
        r.lines.sort((a, b) -> Long.compare(a.time, b.time));
        r.synced = !r.lines.isEmpty();
        return r.synced ? r : null;
    }

    /** Parses standard LRC, Enhanced LRC ({@code <mm:ss.xx>word}), and plain text fallback. */
    static Result parse(String synced, String plain) {
        Result r = new Result();
        if (synced != null && !synced.isEmpty() && !"null".equals(synced)) {
            for (String raw : synced.split("\n")) {
                Matcher m = TAG.matcher(raw);
                List<Long> times = new ArrayList<>();
                int end = 0;
                while (m.find()) {
                    long ms = parseTagMs(m.group(1), m.group(2), m.group(3));
                    times.add(ms);
                    end = m.end();
                }
                if (times.isEmpty()) continue;
                String body = raw.substring(end).trim();
                // Check for Enhanced LRC inline word timestamps: <mm:ss.xx>word
                List<Word> words = parseEnhancedWords(body);
                String text = words != null ? joinWords(words) : body;
                for (long tm : times) r.lines.add(new Line(tm, 0, text, words));
            }
            r.lines.sort((a, b) -> Long.compare(a.time, b.time));
            r.synced = !r.lines.isEmpty();
        }
        if (r.lines.isEmpty() && plain != null && !plain.isEmpty() && !"null".equals(plain)) {
            for (String s : plain.split("\n")) {
                String t = s.trim();
                if (!t.isEmpty()) r.lines.add(new Line(-1, t));
            }
        }
        return r.lines.isEmpty() ? null : r;
    }

    private static long parseTagMs(String min, String sec, String frac) {
        long ms = Long.parseLong(min) * 60000L + Long.parseLong(sec) * 1000L;
        if (frac != null) {
            ms += frac.length() == 2 ? Long.parseLong(frac) * 10L
                    : (frac.length() == 1 ? Long.parseLong(frac) * 100L : Long.parseLong(frac.substring(0, 3)));
        }
        return ms;
    }

    private static List<Word> parseEnhancedWords(String body) {
        if (!body.contains("<")) return null;
        Matcher wm = ELRC_WORD.matcher(body);
        List<Long> starts = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        while (wm.find()) {
            starts.add(parseTagMs(wm.group(1), wm.group(2), wm.group(3)));
            texts.add(wm.group(4));
        }
        if (starts.isEmpty()) return null;
        List<Word> out = new ArrayList<>(starts.size());
        for (int i = 0; i < starts.size(); i++) {
            long s = starts.get(i);
            long d = i + 1 < starts.size() ? Math.max(80L, starts.get(i + 1) - s) : 500L;
            out.add(new Word(s, d, texts.get(i)));
        }
        return out;
    }

    private static String joinWords(List<Word> words) {
        StringBuilder sb = new StringBuilder();
        for (Word w : words) sb.append(w.text);
        return sb.toString().trim();
    }
}
