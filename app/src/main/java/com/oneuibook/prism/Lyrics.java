package com.oneuibook.prism;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multi-provider synced lyrics fetcher (LRCLIB -> NetEase YRC/LRC -> Kugou LRC -> lyrics.ovh).
 * Enforces strict duration & version matching (rejecting mismatched live/remix/cover hits),
 * supports LRC [offset:] tags, per-track manual sync offset, word-synced YRC / Enhanced LRC,
 * and automatic Korean subtitle translation under non-Korean lyrics when the app language is Korean.
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
        String trans; // optional Korean translation shown below original when app language is Korean
        final List<Word> words;

        Line(long t, String s) { this(t, 0, s, null, null); }
        Line(long t, long end, String s, List<Word> w) { this(t, end, s, null, w); }
        Line(long t, long end, String s, String tr, List<Word> w) {
            time = t; endTime = end; text = s; trans = tr; words = w;
        }
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
    private static final Pattern OFFSET_TAG = Pattern.compile("\\[offset:([+-]?\\d+)]", Pattern.CASE_INSENSITIVE);
    private static final Pattern ELRC_WORD = Pattern.compile("<(\\d+):(\\d+)(?:[.:](\\d+))?>([^<]*)");
    private static final Pattern YRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)$");
    private static final Pattern YRC_WORD = Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^(]*)");
    private static final String LRCLIB = "https://lrclib.net/api/";

    static boolean isKoreanApp(Context c) {
        if (c == null) c = Img.appContext;
        if (c == null) return false;
        try {
            String lang = c.getResources().getConfiguration().getLocales().get(0).getLanguage();
            return Locale.KOREAN.getLanguage().equals(lang);
        } catch (Throwable t) { return false; }
    }

    /** Per-track fine sync offset in ms (negative = earlier, positive = later). */
    static long syncOffsetMs(Context c, String trackId) {
        if (c == null || trackId == null) return 0L;
        return MainActivity.prefs(c).getLong("lyr_off_" + trackId, 0L);
    }

    static void setSyncOffsetMs(Context c, String trackId, long ms) {
        if (c == null || trackId == null) return;
        MainActivity.prefs(c).edit().putLong("lyr_off_" + trackId, Math.max(-5000L, Math.min(5000L, ms))).apply();
    }

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
            boolean koApp = isKoreanApp(Img.appContext);
            if (r == null) {
                r = lookup(t, koApp);
                if (r != null) writeDisk(t.id, r);
            } else if (koApp && needsKoreanTranslation(r) && !hasTranslations(r)) {
                attachKoreanTranslation(r);
                writeDisk(t.id, r);
            }
            final Result res = r;
            Spotify.MAIN.post(() -> {
                if (res != null) CACHE.put(t.id, res); else MISSING.add(t.id);
                List<Consumer<Result>> cbs = PENDING.remove(t.id);
                if (cbs != null) for (Consumer<Result> k : cbs) k.accept(res);
            });
        });
    }

    private static Result lookup(Player.Track t, boolean koApp) {
        String artist = primaryArtist(t.artist);
        String name = t.name == null ? "" : t.name.trim();
        String clean = cleanTitle(name);
        long durSec = t.duration / 1000L;
        Result plainFallback = null;

        // 1) LRCLIB (strict duration + version match)
        Result r = lookupLrclib(artist, name, clean, t.album, durSec);
        if (r != null && r.synced) return finish(r, t.duration, koApp);
        if (r != null) plainFallback = r;

        // 2) NetEase Cloud Music (YRC word-sync + LRC + strict duration & version filter)
        r = lookupNetEase(artist, name, clean, t.duration, koApp);
        if (r != null && r.synced) return finish(r, t.duration, koApp);
        if (r != null && plainFallback == null) plainFallback = r;

        // 3) Kugou Lyrics (strict duration check)
        r = lookupKugou(artist, clean, t.duration);
        if (r != null && r.synced) return finish(r, t.duration, koApp);
        if (r != null && plainFallback == null) plainFallback = r;

        if (plainFallback != null) return finish(plainFallback, t.duration, koApp);
        r = lookupLyricsOvh(artist, clean);
        return r == null ? null : finish(r, t.duration, koApp);
    }

    private static Result finish(Result r, long trackDurationMs, boolean koApp) {
        finalizeEndTimes(r, trackDurationMs);
        if (koApp && needsKoreanTranslation(r) && !hasTranslations(r)) {
            attachKoreanTranslation(r);
        }
        return r;
    }

    private static String primaryArtist(String a) {
        if (a == null) return "";
        int i = a.indexOf(',');
        if (i > 0) a = a.substring(0, i);
        int j = a.indexOf('&');
        if (j > 0) a = a.substring(0, j);
        return a.trim();
    }

    /** Penalizes mismatched variants (live / remix / acoustic / cover / sped up) when the target track isn't one. */
    static boolean isVersionMismatch(String targetTitle, String candTitle) {
        String t = targetTitle == null ? "" : targetTitle.toLowerCase(Locale.ROOT);
        String c = candTitle == null ? "" : candTitle.toLowerCase(Locale.ROOT);
        String[] tags = {"live", "remix", "acoustic", "instrumental", "karaoke", "sped up", "slowed", "nightcore", "cover", "piano ver"};
        for (String tag : tags) {
            if (c.contains(tag) && !t.contains(tag)) return true;
        }
        return false;
    }

    private static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    // ---- Provider 1: LRCLIB ----
    private static Result lookupLrclib(String artist, String name, String clean, String album, long dur) {
        String q = "artist_name=" + Spotify.enc(artist) + "&track_name=" + Spotify.enc(name);
        Result plain = null;
        try {
            JSONObject o = getObj(LRCLIB + "get-cached?" + q + "&album_name=" + Spotify.enc(album == null ? "" : album) + "&duration=" + dur, 5000);
            if (o != null && (dur <= 0 || Math.abs((long) o.optDouble("duration", dur) - dur) <= 4)) {
                Result r = parse(o.optString("syncedLyrics", ""), o.optString("plainLyrics", ""));
                if (r != null) { r.source = "LRCLIB"; if (r.synced) return r; plain = r; }
            }
        } catch (Exception ignored) {}
        Result r = searchLrclib(q, artist, name, dur);
        if (r != null) { r.source = "LRCLIB"; if (r.synced) return r; if (plain == null) plain = r; }
        if (!clean.equals(name)) {
            r = searchLrclib("artist_name=" + Spotify.enc(artist) + "&track_name=" + Spotify.enc(clean), artist, clean, dur);
            if (r != null) { r.source = "LRCLIB"; if (r.synced) return r; if (plain == null) plain = r; }
        }
        r = searchLrclib("q=" + Spotify.enc((artist + " " + clean).trim()), artist, clean, dur);
        if (r != null) { r.source = "LRCLIB"; if (r.synced) return r; if (plain == null) plain = r; }
        try {
            JSONObject o = getObj(LRCLIB + "get?" + q + "&duration=" + dur, 7500);
            if (o != null && (dur <= 0 || Math.abs((long) o.optDouble("duration", dur) - dur) <= 4)) {
                r = parse(o.optString("syncedLyrics", ""), o.optString("plainLyrics", ""));
                if (r != null) { r.source = "LRCLIB"; if (r.synced) return r; if (plain == null) plain = r; }
            }
        } catch (Exception ignored) {}
        return plain;
    }

    private static Result searchLrclib(String q, String wantArtist, String wantTitle, long dur) {
        try {
            String body = getText(LRCLIB + "search?" + q, 5500);
            if (body == null) return null;
            JSONArray a = new JSONArray(body);
            JSONObject best = null;
            long bestScore = Long.MAX_VALUE;
            String nTitle = norm(cleanTitle(wantTitle));
            String nArtist = norm(wantArtist);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String cTitle = o.optString("trackName", "");
                String cArtist = o.optString("artistName", "");
                if (isVersionMismatch(wantTitle, cTitle)) continue;
                long d = Math.abs((long) o.optDouble("duration", 0) - dur);
                if (dur > 0 && d > 4) continue; // strict <= 4s duration gate prevents off-sync edits
                boolean synced = !o.isNull("syncedLyrics") && !o.optString("syncedLyrics", "").isEmpty();
                boolean plain = !o.isNull("plainLyrics") && !o.optString("plainLyrics", "").isEmpty();
                if (!synced && !plain) continue;
                long score = d * 25 + (synced ? 0 : 200);
                if (!nTitle.isEmpty() && !norm(cleanTitle(cTitle)).contains(nTitle) && !nTitle.contains(norm(cleanTitle(cTitle)))) score += 120;
                if (!nArtist.isEmpty() && !norm(cArtist).contains(nArtist) && !nArtist.contains(norm(cArtist))) score += 120;
                if (score < bestScore) { bestScore = score; best = o; }
            }
            return best == null ? null : parse(best.optString("syncedLyrics", ""), best.optString("plainLyrics", ""));
        } catch (Exception e) { return null; }
    }

    // ---- Provider 2: NetEase Cloud Music (strict duration & title match + optional Korean tlyric) ----
    private static Result lookupNetEase(String artist, String origTitle, String clean, long durMs, boolean koApp) {
        try {
            String kw = (artist + " " + clean).trim();
            if (kw.isEmpty()) return null;
            JSONObject s = getObj("https://music.163.com/api/search/get/web?s=" + Spotify.enc(kw) + "&type=1&limit=10", 5500);
            if (s == null) return null;
            JSONObject res = s.optJSONObject("result");
            JSONArray songs = res == null ? null : res.optJSONArray("songs");
            if (songs == null || songs.length() == 0) return null;
            long bestId = -1, bestScore = Long.MAX_VALUE;
            String nTitle = norm(clean);
            for (int i = 0; i < songs.length(); i++) {
                JSONObject item = songs.optJSONObject(i);
                if (item == null) continue;
                String cname = item.optString("name", "");
                if (isVersionMismatch(origTitle, cname)) continue;
                long d = item.optLong("duration", 0);
                long diff = (durMs > 0 && d > 0) ? Math.abs(d - durMs) : 0;
                if (durMs > 0 && d > 0 && diff > 4500) continue; // never pick a wrong-length version!
                long score = diff + i * 150L;
                if (!nTitle.isEmpty() && !norm(cleanTitle(cname)).contains(nTitle) && !nTitle.contains(norm(cleanTitle(cname)))) score += 3000;
                if (score < bestScore) { bestScore = score; bestId = item.optLong("id", -1); }
            }
            if (bestId <= 0) return null;
            JSONObject lyr = getObj("https://music.163.com/api/song/lyric?id=" + bestId + "&lv=1&yv=1&tv=1", 5500);
            if (lyr == null) return null;
            Result out = null;
            JSONObject yrc = lyr.optJSONObject("yrc");
            if (yrc != null) {
                Result r = parseYrc(yrc.optString("lyric", ""));
                if (r != null && r.synced) { r.source = "NetEase"; out = r; }
            }
            if (out == null) {
                JSONObject lrc = lyr.optJSONObject("lrc");
                if (lrc != null) {
                    Result r = parse(lrc.optString("lyric", ""), "");
                    if (r != null) { r.source = "NetEase"; out = r; }
                }
            }
            // If NetEase also has a Korean translation track, merge it by timestamp
            if (out != null && koApp) {
                JSONObject tlyr = lyr.optJSONObject("tlyric");
                String tStr = tlyr == null ? "" : tlyr.optString("lyric", "");
                if (hasHangul(tStr)) {
                    Result tr = parse(tStr, "");
                    if (tr != null && tr.synced) mergeTranslationByTime(out, tr);
                }
            }
            return out;
        } catch (Exception ignored) {}
        return null;
    }

    // ---- Provider 3: Kugou Lyrics (strict duration check) ----
    private static Result lookupKugou(String artist, String title, long durMs) {
        try {
            String kw = (artist.isEmpty() ? title : artist + "-" + title).trim();
            if (kw.isEmpty()) return null;
            String url = "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=" + Spotify.enc(kw)
                    + (durMs > 0 ? "&duration=" + durMs : "");
            JSONObject s = getObj(url, 5500);
            JSONArray cands = s == null ? null : s.optJSONArray("candidates");
            if (cands == null || cands.length() == 0) return null;
            JSONObject best = null;
            long bestDiff = Long.MAX_VALUE;
            for (int i = 0; i < cands.length(); i++) {
                JSONObject c = cands.optJSONObject(i);
                if (c == null) continue;
                long d = c.optLong("duration", 0);
                long diff = (durMs > 0 && d > 0) ? Math.abs(d - durMs) : 0;
                if (durMs > 0 && d > 0 && diff > 4500) continue;
                if (diff < bestDiff) { bestDiff = diff; best = c; }
            }
            if (best == null) return null;
            String id = best.optString("id", "");
            String key = best.optString("accesskey", "");
            if (id.isEmpty() || key.isEmpty()) return null;
            JSONObject dl = getObj("https://lyrics.kugou.com/download?ver=1&client=pc&id=" + Spotify.enc(id)
                    + "&accesskey=" + Spotify.enc(key) + "&fmt=lrc&charset=utf8", 5500);
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
            JSONObject o = getObj("https://api.lyrics.ovh/v1/" + Spotify.enc(artist) + "/" + Spotify.enc(title), 5500);
            if (o == null) return null;
            String plain = o.optString("lyrics", "").trim();
            if (plain.isEmpty()) return null;
            Result r = parse("", plain);
            if (r != null) r.source = "lyrics.ovh";
            return r;
        } catch (Exception ignored) {}
        return null;
    }

    // ---- Automatic Korean subtitle translation for non-Korean songs (only when app language is Korean) ----
    static boolean hasHangul(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0xAC00 && ch <= 0xD7A3) return true;
        }
        return false;
    }

    static boolean needsKoreanTranslation(Result r) {
        if (r == null || r.lines.isEmpty()) return false;
        int letters = 0, hangul = 0;
        for (Line l : r.lines) {
            if (l.text == null) continue;
            for (int i = 0; i < l.text.length(); i++) {
                char ch = l.text.charAt(i);
                if (Character.isLetter(ch)) {
                    letters++;
                    if (ch >= 0xAC00 && ch <= 0xD7A3) hangul++;
                }
            }
        }
        return letters >= 12 && (hangul / (float) letters) < 0.15f;
    }

    private static boolean hasTranslations(Result r) {
        for (Line l : r.lines) if (l.trans != null && !l.trans.isEmpty()) return true;
        return false;
    }

    private static void mergeTranslationByTime(Result target, Result trans) {
        for (Line l : target.lines) {
            Line best = null;
            long bestDiff = 600L;
            for (Line tl : trans.lines) {
                long d = Math.abs(tl.time - l.time);
                if (d < bestDiff) { bestDiff = d; best = tl; }
            }
            if (best != null && best.text != null && !best.text.isEmpty() && !best.text.equals(l.text)) {
                l.trans = best.text;
            }
        }
    }

    /** Translates non-Korean lyrics lines in batches into Korean via public translate endpoint (best-effort). */
    private static void attachKoreanTranslation(Result r) {
        try {
            List<Integer> idxs = new ArrayList<>();
            for (int i = 0; i < r.lines.size(); i++) {
                String t = r.lines.get(i).text;
                if (t != null && !t.isEmpty() && !"♪".equals(t) && !hasHangul(t)) idxs.add(i);
            }
            if (idxs.isEmpty()) return;
            // Batch ~20 lines per request to keep URL length safe
            int batch = 20;
            for (int start = 0; start < idxs.size(); start += batch) {
                int end = Math.min(idxs.size(), start + batch);
                StringBuilder sb = new StringBuilder();
                for (int j = start; j < end; j++) {
                    if (j > start) sb.append('\n');
                    sb.append(r.lines.get(idxs.get(j)).text.replace('\n', ' '));
                }
                String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=ko&dt=t&q=" + Spotify.enc(sb.toString());
                String body = getText(url, 5500);
                if (body == null) return;
                JSONArray root = new JSONArray(body);
                JSONArray segs = root.optJSONArray(0);
                if (segs == null) continue;
                StringBuilder joined = new StringBuilder();
                for (int k = 0; k < segs.length(); k++) {
                    JSONArray seg = segs.optJSONArray(k);
                    if (seg != null) joined.append(seg.optString(0, ""));
                }
                String[] translated = joined.toString().split("\n");
                for (int j = 0; j < Math.min(end - start, translated.length); j++) {
                    String tr = translated[j].trim();
                    Line ln = r.lines.get(idxs.get(start + j));
                    if (!tr.isEmpty() && !tr.equalsIgnoreCase(ln.text)) ln.trans = tr;
                }
            }
        } catch (Throwable ignored) {}
    }

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

    // ---- disk cache (v3 includes Korean translation + corrected strict-matched lyrics) ----
    private static java.io.File file(String id) {
        if (Img.appContext == null) return null;
        java.io.File d = new java.io.File(Img.appContext.getCacheDir(), "lyrics_v3");
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
                String tr = lo.optString("tr", null);
                r.lines.add(new Line(lo.optLong("t"), lo.optLong("e"), lo.optString("x"), (tr == null || tr.isEmpty()) ? null : tr, words));
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
                if (l.trans != null && !l.trans.isEmpty()) lo.put("tr", l.trans);
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

    /** Parses standard LRC (including [offset:±ms] tag!), Enhanced LRC, and plain text fallback. */
    static Result parse(String synced, String plain) {
        Result r = new Result();
        if (synced != null && !synced.isEmpty() && !"null".equals(synced)) {
            long offsetMs = 0L;
            Matcher om = OFFSET_TAG.matcher(synced);
            if (om.find()) {
                try { offsetMs = Long.parseLong(om.group(1)); } catch (Exception ignored) {}
            }
            for (String raw : synced.split("\n")) {
                Matcher m = TAG.matcher(raw);
                List<Long> times = new ArrayList<>();
                int end = 0;
                while (m.find()) {
                    long ms = Math.max(0L, parseTagMs(m.group(1), m.group(2), m.group(3)) - offsetMs);
                    times.add(ms);
                    end = m.end();
                }
                if (times.isEmpty()) continue;
                String body = raw.substring(end).trim();
                List<Word> words = parseEnhancedWords(body, offsetMs);
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

    private static List<Word> parseEnhancedWords(String body, long offsetMs) {
        if (!body.contains("<")) return null;
        Matcher wm = ELRC_WORD.matcher(body);
        List<Long> starts = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        while (wm.find()) {
            starts.add(Math.max(0L, parseTagMs(wm.group(1), wm.group(2), wm.group(3)) - offsetMs));
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
