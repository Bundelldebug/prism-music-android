package com.oneuibook.prism;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
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
 * Multi-provider synced lyrics fetcher with per-provider toggles in Settings:
 *   1) LRCLIB        (lrclib.net — synced LRC & dual-language LRC)
 *   2) NetEase       (music.163.com — word-synced YRC, LRC & native tlyric Korean subtitle track)
 *   3) QQ Music      (c.y.qq.com — LRC & native trans Korean subtitle track)
 *   4) Kugou         (krcs.kugou.com — synced LRC)
 *   5) SimpMusic     (api-lyrics.simpmusic.org — community synced LRC)
 *   6) lyrics.ovh    (api.lyrics.ovh — plain text fallback)
 *
 * Never runs awkward machine translation: Korean subtitle lines are displayed under non-Korean lyrics
 * ONLY when the lyric source itself includes a native Korean translation track or dual-language LRC
 * (and only when the app language is Korean and the setting is enabled).
 */
final class Lyrics {
    private Lyrics() {}

    static final String PREF_KO_TRANS = "lyrics_ko_trans";
    static final String PREF_NAMUWIKI = "lyrics_namuwiki";

    static final String SRC_LRCLIB = "lrclib";
    static final String SRC_NETEASE = "netease";
    static final String SRC_QQ = "qq";
    static final String SRC_KUGOU = "kugou";
    static final String SRC_SIMPMUSIC = "simpmusic";
    static final String SRC_LYRICSOVH = "lyricsovh";
    static final String SRC_MUSIXMATCH = "musixmatch";
    static final String SRC_NAMU = "namuwiki";

    // 9.5.31: Musixmatch added; Namuwiki is now one of the providers (a toggle in the list, off by default)
    static final String[] PROVIDER_IDS = {SRC_LRCLIB, SRC_NETEASE, SRC_QQ, SRC_KUGOU, SRC_SIMPMUSIC, SRC_MUSIXMATCH, SRC_NAMU, SRC_LYRICSOVH};
    static final String[] PROVIDER_NAMES = {"LRCLIB", "NetEase Cloud Music", "QQ Music", "Kugou Lyrics", "SimpMusic Lyrics", "Musixmatch", "나무위키", "lyrics.ovh"};

    /** "auto" (the order above) or a provider id that is asked first. Replaces 9.5.30's "Namuwiki first" switch. */
    static final String PREF_PRIORITY = "lyrics_priority";
    static final String PRIORITY_AUTO = "auto";
    /** Musixmatch developer API key (the user's own, stored encrypted). */
    static final String PREF_MXM_KEY = "mxm_api_key";

    static String providerPrefKey(String id) { return "lyr_src_" + id; }

    static boolean isProviderEnabled(Context c, String id) {
        if (c == null) c = Img.appContext;
        if (c == null) return !SRC_NAMU.equals(id);
        android.content.SharedPreferences p = MainActivity.prefs(c);
        // Namuwiki: off by default; a 9.5.30 user who had switched on "Namuwiki first" keeps it on
        boolean def = !SRC_NAMU.equals(id) || p.getBoolean(PREF_NAMUWIKI, false);
        return p.getBoolean(providerPrefKey(id), def);
    }

    static boolean namuwikiEnabled(Context c) { return isProviderEnabled(c, SRC_NAMU); }

    /** The provider asked first, or {@link #PRIORITY_AUTO}. Migrates 9.5.30's "Namuwiki first" switch. */
    static String priority(Context c) {
        if (c == null) c = Img.appContext;
        if (c == null) return PRIORITY_AUTO;
        android.content.SharedPreferences p = MainActivity.prefs(c);
        String v = p.getString(PREF_PRIORITY, null);
        if (v == null) v = p.getBoolean(PREF_NAMUWIKI, false) ? SRC_NAMU : PRIORITY_AUTO;
        if (!PRIORITY_AUTO.equals(v) && !isProviderEnabled(c, v)) return PRIORITY_AUTO;   // turned off → back to the list order
        return v;
    }

    static String providerName(String id) {
        for (int i = 0; i < PROVIDER_IDS.length; i++) if (PROVIDER_IDS[i].equals(id)) return PROVIDER_NAMES[i];
        return id;
    }

    static boolean musixmatchKeySet(Context c) {
        if (c == null) c = Img.appContext;
        return c != null && SecureVault.has(MainActivity.prefs(c), PREF_MXM_KEY);
    }

    /** Translation lines under the lyrics: the source's own Korean translation, or machine translation when switched on. */
    static boolean showKoreanSubtitles(Context c) {
        if (c == null) c = Img.appContext;
        if (c == null) return false;
        return (namuwikiEnabled(c) && isKoreanApp(c)) || (isKoreanApp(c) && MainActivity.prefs(c).getBoolean(PREF_KO_TRANS, true))
                || LyricsTranslate.enabled(c);
    }

    /** Only the source's own Korean translations (never machine translation): kept for a Korean app. */
    static boolean wantNativeKorean(Context c) {
        if (c == null) c = Img.appContext;
        return c != null && isKoreanApp(c) && (MainActivity.prefs(c).getBoolean(PREF_KO_TRANS, true) || namuwikiEnabled(c));
    }

    /**
     * Bumped on every settings change / cache clear. A lookup that was already running under the old settings
     * must not write its result into the cache (memory or disk) afterwards — 9.5.30 let such stale results land,
     * so toggling lyrics settings back and forth could leave songs stuck on "no lyrics" or wrong lyrics.
     */
    private static volatile int generation;
    /** Called on the main thread after a cache clear, so an open lyrics panel reloads with the new settings. */
    static Runnable onCleared;

    static void clearCache(Context c) {
        synchronized (CACHE) { CACHE.clear(); MISSING.clear(); generation++; }
        Context ctx = c != null ? c : Img.appContext;
        if (ctx != null) {
            for (String dirName : new String[]{"lyrics_v4", "lyrics_v5", "lyrics_v6", "lyrics_v7"}) {
                File d = new File(ctx.getCacheDir(), dirName);
                File[] fs = d.listFiles();
                if (fs != null) for (File f : fs) f.delete();
            }
        }
        settingsChanged();
    }

    /** Tells an open lyrics pane to reload (lyrics on/off, providers, translation, Namuwiki). */
    static void settingsChanged() {
        Runnable r = onCleared;
        if (r != null) Spotify.MAIN.post(r);
    }

    static final class Word {
        final long start;
        final long duration;
        final String text;
        Word(long s, long d, String t) { start = s; duration = Math.max(1L, d); text = t; }
    }

    static final class Line {
        final long time;
        long endTime;
        boolean explicitEnd;
        final String text;
        String trans; // native Korean translation line from lyric source (never machine-translated)
        final List<Word> words;

        Line(long t, String s) { this(t, 0, s, null, null); }
        Line(long t, long end, String s, List<Word> w) { this(t, end, s, null, w); }
        Line(long t, long end, String s, String tr, List<Word> w) {
            time = t; endTime = end; explicitEnd = end > t; text = s; trans = tr; words = w;
        }
    }

    static final class Result {
        final List<Line> lines = new ArrayList<>();
        boolean synced;
        String source = "";
        /** Machine translation engine that filled {@code trans} (null: the source's own translations or none). */
        String transEngine;
    }

    private static final Map<String, Result> CACHE = new HashMap<>();
    /** track id → time it was found to have no lyrics; retried after {@link #MISSING_TTL_MS} (a network blip isn't forever) */
    private static final Map<String, Long> MISSING = new HashMap<>();
    private static final long MISSING_TTL_MS = 10 * 60_000L;
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

    private static boolean missingNow(String id) {
        Long at = MISSING.get(id);
        if (at == null) return false;
        if (System.currentTimeMillis() - at > MISSING_TTL_MS) { MISSING.remove(id); return false; }
        return true;
    }

    static void prefetch(Player.Track t) {
        if (t == null || t.id == null) return;
        synchronized (CACHE) { if (CACHE.containsKey(t.id) || missingNow(t.id)) return; }
        fetch(t, null);
    }

    /** Main thread. Callbacks always run (on the main thread), even when a lookup throws. */
    static void fetch(Player.Track t, Consumer<Result> cb) {
        if (t == null || t.id == null) { if (cb != null) cb.accept(null); return; }
        Result cached;
        boolean miss;
        synchronized (CACHE) {
            cached = CACHE.get(t.id);
            miss = cached == null && missingNow(t.id);
        }
        if (cached != null || miss) { if (cb != null) cb.accept(cached); return; }
        List<Consumer<Result>> waiting = PENDING.get(t.id);
        if (waiting != null) { if (cb != null) waiting.add(cb); return; }
        waiting = new ArrayList<>();
        if (cb != null) waiting.add(cb);
        PENDING.put(t.id, waiting);
        final Context ctx = Img.appContext;
        final int gen = generation;
        POOL.execute(() -> {
            Result r = null;
            boolean failed = false;
            try {
                r = resolve(ctx, t, gen);
            } catch (Throwable e) {
                failed = true;   // a broken provider response must not wedge this song's lyrics
            }
            final Result res = r;
            final boolean err = failed;
            Spotify.MAIN.post(() -> {
                List<Consumer<Result>> cbs = PENDING.remove(t.id);
                if (gen != generation) {
                    // settings changed while this lookup ran: throw it away and look up again with the new settings
                    if (cbs != null && !cbs.isEmpty()) {
                        Consumer<Result> first = cbs.get(0);
                        fetch(t, first);
                        List<Consumer<Result>> again = PENDING.get(t.id);
                        for (int i = 1; i < cbs.size(); i++) { if (again != null) again.add(cbs.get(i)); else cbs.get(i).accept(null); }
                    }
                    return;
                }
                synchronized (CACHE) {
                    if (res != null) CACHE.put(t.id, res);
                    else if (!err) MISSING.put(t.id, System.currentTimeMillis());
                }
                if (cbs != null) for (Consumer<Result> k : cbs) k.accept(res);
            });
        });
    }

    /** Worker thread: disk cache → providers (Namuwiki first when enabled), honouring the current settings. */
    private static Result resolve(Context ctx, Player.Track t, int gen) {
        Result r = readDisk(t.id);
        boolean wantKoSub = wantNativeKorean(ctx);
        boolean useNamu = namuwikiEnabled(ctx);
        String prio = priority(ctx);
        boolean dirty = false;
        String namuArtist = primaryArtist(t.artist);
        String namuTitle = t.name == null ? "" : t.name.trim();
        String namuClean = cleanTitle(namuTitle);
        if (r == null) {
            if (SRC_NAMU.equals(prio)) {
                // Namuwiki asked first: its lyrics / translations win when they belong to this song
                NamuMatch nm = fetchNamuwiki(namuArtist, namuTitle, namuClean);
                Result other = lookup(ctx, t, wantKoSub, SRC_NAMU);
                r = resolveWithNamuPriority(nm, other, wantKoSub);
            } else if (!PRIORITY_AUTO.equals(prio)) {
                // another provider asked first: its synced lyrics win; its plain lyrics win unless a synced
                // version of the same text exists elsewhere (then that one supplies the timing)
                Result first = lookupOne(ctx, t, prio, wantKoSub);
                if (first != null && first.synced) r = first;
                else {
                    Result other = lookup(ctx, t, wantKoSub, prio);
                    r = preferPlain(first, other);
                }
            } else {
                r = lookup(ctx, t, wantKoSub, null);
            }
            // Namuwiki in the list (not first): fills in when nothing else had lyrics, or adds its Korean
            // translation to synced lyrics of the same song
            if (useNamu && !SRC_NAMU.equals(prio) && (r == null || (wantKoSub && !hasTranslations(r) && needsKoreanTranslation(r)))) {
                NamuMatch nm = fetchNamuwiki(namuArtist, namuTitle, namuClean);
                if (nm != null && nm.hasLyrics()) r = r == null ? resolveWithNamuPriority(nm, null, wantKoSub) : resolveWithNamuPriority(nm, r, wantKoSub);
            }
            dirty = r != null;
        }
        if (r != null && !wantKoSub && r.transEngine == null) {
            for (Line l : r.lines) l.trans = null;   // r is this lookup's own copy (fresh or read from disk)
        }
        // machine translation (off unless chosen in settings), only where the source has no translation of its own
        if (r != null && r.transEngine == null && !hasTranslations(r) && LyricsTranslate.enabled(ctx)) {
            if (LyricsTranslate.translate(ctx, r)) dirty = true;
        }
        // only results made under the current settings reach the disk cache
        if (dirty && r != null && gen == generation) writeDisk(t.id, r);
        return r;
    }

    /** Preferred provider gave plain lyrics: keep them, unless {@code other} is a synced version of the same text. */
    private static Result preferPlain(Result first, Result other) {
        if (first == null) return other;
        if (other != null && other.synced && !other.lines.isEmpty()) {
            List<String> plain = new ArrayList<>();
            for (Line l : first.lines) plain.add(l.text);
            int need = Math.max(2, Math.min(other.lines.size(), plain.size()) / 4);
            if (countPlainOverlap(other, plain) >= need) { other.source = first.source + " · " + other.source; return other; }
        }
        return first;
    }

    /** One provider only (for "ask this one first"). */
    private static Result lookupOne(Context c, Player.Track t, String id, boolean wantKoSub) {
        String artist = primaryArtist(t.artist), name = t.name == null ? "" : t.name.trim(), clean = cleanTitle(name);
        Result r = null;
        try {
            switch (id) {
                case SRC_LRCLIB: r = lookupLrclib(artist, name, clean, t.album, t.duration / 1000L); break;
                case SRC_NETEASE: r = lookupNetEase(artist, name, clean, t.duration, wantKoSub); break;
                case SRC_QQ: r = lookupQqMusic(artist, name, clean, t.duration, wantKoSub); break;
                case SRC_KUGOU: r = lookupKugou(artist, clean, t.duration); break;
                case SRC_SIMPMUSIC: r = lookupSimpMusic(artist, name, clean, t.duration); break;
                case SRC_MUSIXMATCH: r = lookupMusixmatch(c, artist, clean, t.duration); break;
                case SRC_LYRICSOVH: r = lookupLyricsOvh(artist, clean); break;
                default: return null;
            }
        } catch (Exception ignored) {}
        if (r == null) return null;
        if (wantKoSub) extractDualKoreanLines(r);
        return finish(r, t.duration, wantKoSub);
    }

    // ---- Provider: Musixmatch (official developer API with the user's own key) ----
    /**
     * matcher.subtitle.get (synced; needs a Musixmatch plan that includes subtitles), then matcher.lyrics.get.
     * The free plan returns only part of the lyrics plus a notice line — that notice is removed and the source is
     * marked "Musixmatch (일부)".
     */
    private static Result lookupMusixmatch(Context c, String artist, String title, long durMs) {
        if (c == null) c = Img.appContext;
        if (c == null) return null;
        String key = SecureVault.get(MainActivity.prefs(c), PREF_MXM_KEY);
        if (key == null || key.trim().isEmpty() || artist.isEmpty() || title.isEmpty()) return null;
        String base = "https://api.musixmatch.com/ws/1.1/";
        String q = "q_track=" + Spotify.enc(title) + "&q_artist=" + Spotify.enc(artist) + "&apikey=" + Spotify.enc(key.trim());
        try {
            String sub = base + "matcher.subtitle.get?" + q + "&subtitle_format=lrc"
                    + (durMs > 0 ? "&f_subtitle_length=" + (durMs / 1000L) + "&f_subtitle_length_max_deviation=4" : "");
            JSONObject o = getObj(sub, 5500);
            JSONObject body = mxmBody(o);
            JSONObject st = body == null ? null : body.optJSONObject("subtitle");
            if (st != null) {
                Result r = parse(st.optString("subtitle_body", ""), "");
                if (r != null && r.synced) { r.source = "Musixmatch"; return r; }
            }
        } catch (Exception ignored) {}
        try {
            JSONObject o = getObj(base + "matcher.lyrics.get?" + q, 5500);
            JSONObject body = mxmBody(o);
            JSONObject ly = body == null ? null : body.optJSONObject("lyrics");
            if (ly == null) return null;
            String text = ly.optString("lyrics_body", "");
            boolean partial = text.contains("NOT for Commercial use") || text.contains("*******");
            StringBuilder sb = new StringBuilder();
            for (String line : text.split("\n")) {
                String s = line.trim();
                if (s.startsWith("*******") || s.matches("^\\(\\d+\\)$")) continue;
                sb.append(line).append('\n');
            }
            Result r = parse("", sb.toString().trim());
            if (r != null) r.source = partial ? "Musixmatch (일부)" : "Musixmatch";
            return r;
        } catch (Exception ignored) {}
        return null;
    }

    private static JSONObject mxmBody(JSONObject o) {
        JSONObject msg = o == null ? null : o.optJSONObject("message");
        if (msg == null) return null;
        JSONObject h = msg.optJSONObject("header");
        if (h == null || h.optInt("status_code") != 200) return null;
        return msg.optJSONObject("body");
    }

    /** All enabled providers in list order; {@code skip} = a provider already asked (null for none). */
    private static Result lookup(Context c, Player.Track t, boolean wantKoSub, String skip) {
        String artist = primaryArtist(t.artist);
        String name = t.name == null ? "" : t.name.trim();
        String clean = cleanTitle(name);
        long durSec = t.duration / 1000L;
        Result bestSynced = null;
        Result plainFallback = null;

        // 1) LRCLIB
        if (isProviderEnabled(c, SRC_LRCLIB) && !SRC_LRCLIB.equals(skip)) {
            Result r = lookupLrclib(artist, name, clean, t.album, durSec);
            if (r != null) {
                if (wantKoSub) extractDualKoreanLines(r);
                if (r.synced) {
                    if (hasTranslations(r) || !wantKoSub || !needsKoreanTranslation(r)) return finish(r, t.duration, wantKoSub);
                    bestSynced = r;
                } else if (plainFallback == null) plainFallback = r;
            }
        }

        // 2) NetEase Cloud Music (YRC word-sync + LRC + native Korean tlyric)
        if (isProviderEnabled(c, SRC_NETEASE) && !SRC_NETEASE.equals(skip)) {
            Result r = lookupNetEase(artist, name, clean, t.duration, wantKoSub);
            if (r != null) {
                if (wantKoSub) extractDualKoreanLines(r);
                if (r.synced) {
                    if (hasTranslations(r) || bestSynced == null) {
                        if (hasTranslations(r) || !wantKoSub || !needsKoreanTranslation(r)) return finish(r, t.duration, wantKoSub);
                        bestSynced = r;
                    }
                } else if (plainFallback == null) plainFallback = r;
            }
        }

        // 3) QQ Music (LRC + native Korean trans track)
        if (isProviderEnabled(c, SRC_QQ) && !SRC_QQ.equals(skip)) {
            Result r = lookupQqMusic(artist, name, clean, t.duration, wantKoSub);
            if (r != null) {
                if (wantKoSub) extractDualKoreanLines(r);
                if (r.synced) {
                    if (hasTranslations(r)) return finish(r, t.duration, wantKoSub);
                    if (bestSynced == null) bestSynced = r;
                } else if (plainFallback == null) plainFallback = r;
            }
        }

        if (bestSynced != null) return finish(bestSynced, t.duration, wantKoSub);

        // 4) Kugou Lyrics
        if (isProviderEnabled(c, SRC_KUGOU) && !SRC_KUGOU.equals(skip)) {
            Result r = lookupKugou(artist, clean, t.duration);
            if (r != null) {
                if (wantKoSub) extractDualKoreanLines(r);
                if (r.synced) return finish(r, t.duration, wantKoSub);
                if (plainFallback == null) plainFallback = r;
            }
        }

        // 5) SimpMusic Lyrics
        if (isProviderEnabled(c, SRC_SIMPMUSIC) && !SRC_SIMPMUSIC.equals(skip)) {
            Result r = lookupSimpMusic(artist, name, clean, t.duration);
            if (r != null) {
                if (wantKoSub) extractDualKoreanLines(r);
                if (r.synced) return finish(r, t.duration, wantKoSub);
                if (plainFallback == null) plainFallback = r;
            }
        }

        // 6) Musixmatch (the user's own API key)
        if (isProviderEnabled(c, SRC_MUSIXMATCH) && !SRC_MUSIXMATCH.equals(skip)) {
            Result r = lookupMusixmatch(c, artist, clean, t.duration);
            if (r != null) {
                if (wantKoSub) extractDualKoreanLines(r);
                if (r.synced) return finish(r, t.duration, wantKoSub);
                if (plainFallback == null) plainFallback = r;
            }
        }

        if (plainFallback != null) return finish(plainFallback, t.duration, wantKoSub);

        // 7) lyrics.ovh (plain text fallback)
        if (isProviderEnabled(c, SRC_LYRICSOVH) && !SRC_LYRICSOVH.equals(skip)) {
            Result r = lookupLyricsOvh(artist, clean);
            if (r != null) return finish(r, t.duration, wantKoSub);
        }
        return null;
    }

    private static Result finish(Result r, long trackDurationMs, boolean wantKoSub) {
        if (!wantKoSub) for (Line l : r.lines) l.trans = null;
        return finalizeEndTimes(r, trackDurationMs);
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
            JSONObject o = getObj(LRCLIB + "get?" + q + "&duration=" + dur, 7000);
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
                if (dur > 0 && d > 4) continue;
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

    // ---- Provider 2: NetEase Cloud Music (YRC + LRC + native Korean tlyric) ----
    private static Result lookupNetEase(String artist, String origTitle, String clean, long durMs, boolean wantKoSub) {
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
                if (durMs > 0 && d > 0 && diff > 4500) continue;
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
            if (out != null && wantKoSub) {
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

    // ---- Provider 3: QQ Music (LRC + native Korean trans track) ----
    private static Result lookupQqMusic(String artist, String origTitle, String clean, long durMs, boolean wantKoSub) {
        try {
            String kw = (artist + " " + clean).trim();
            if (kw.isEmpty()) return null;
            JSONObject s = getObj("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=" + Spotify.enc(kw) + "&p=1&n=8&format=json", 5500);
            JSONObject data = s == null ? null : s.optJSONObject("data");
            JSONObject songObj = data == null ? null : data.optJSONObject("song");
            JSONArray list = songObj == null ? null : songObj.optJSONArray("list");
            if (list == null || list.length() == 0) return null;
            String bestMid = null;
            long bestScore = Long.MAX_VALUE;
            String nTitle = norm(clean);
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                String cname = item.optString("songname", "");
                if (isVersionMismatch(origTitle, cname)) continue;
                long dMs = item.optLong("interval", 0) * 1000L;
                long diff = (durMs > 0 && dMs > 0) ? Math.abs(dMs - durMs) : 0;
                if (durMs > 0 && dMs > 0 && diff > 4500) continue;
                long score = diff + i * 150L;
                if (!nTitle.isEmpty() && !norm(cleanTitle(cname)).contains(nTitle) && !nTitle.contains(norm(cleanTitle(cname)))) score += 3000;
                if (score < bestScore) { bestScore = score; bestMid = item.optString("songmid", null); }
            }
            if (bestMid == null || bestMid.isEmpty()) return null;
            String lyrUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=" + Spotify.enc(bestMid) + "&format=json&nobase64=0";
            JSONObject lyr = getObjWithHeader(lyrUrl, "Referer", "https://y.qq.com/", 5500);
            if (lyr == null) return null;
            String b64 = lyr.optString("lyric", "");
            if (b64.isEmpty()) return null;
            String lrcStr = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
            Result out = parse(lrcStr, "");
            if (out == null) return null;
            out.source = "QQ Music";
            if (wantKoSub) {
                String tb64 = lyr.optString("trans", "");
                if (!tb64.isEmpty()) {
                    String tStr = new String(Base64.decode(tb64, Base64.DEFAULT), StandardCharsets.UTF_8);
                    if (hasHangul(tStr)) {
                        Result tr = parse(tStr, "");
                        if (tr != null && tr.synced) mergeTranslationByTime(out, tr);
                    }
                }
            }
            return out;
        } catch (Exception ignored) {}
        return null;
    }

    // ---- Provider 4: Kugou Lyrics (strict duration check) ----
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

    // ---- Provider 5: SimpMusic Lyrics API (community synced LRC) ----
    private static Result lookupSimpMusic(String artist, String origTitle, String clean, long durMs) {
        try {
            String kw = (artist + " " + clean).trim();
            if (kw.isEmpty()) return null;
            String body = getText("https://api-lyrics.simpmusic.org/v1/search?q=" + Spotify.enc(kw), 5500);
            if (body == null) return null;
            JSONArray arr = body.trim().startsWith("[") ? new JSONArray(body) : new JSONObject(body).optJSONArray("data");
            if (arr == null || arr.length() == 0) return null;
            JSONObject best = null;
            long bestScore = Long.MAX_VALUE;
            long durSec = durMs / 1000L;
            String nTitle = norm(clean);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String ct = o.optString("songTitle", o.optString("title", ""));
                if (isVersionMismatch(origTitle, ct)) continue;
                long d = o.optLong("durationSeconds", o.optLong("duration", 0));
                if (d > 10000) d /= 1000L;
                long diff = (durSec > 0 && d > 0) ? Math.abs(d - durSec) : 0;
                if (durSec > 0 && d > 0 && diff > 4) continue;
                String sync = o.optString("syncedLyrics", "");
                String plain = o.optString("plainLyrics", "");
                if (sync.isEmpty() && plain.isEmpty()) continue;
                long score = diff * 25 + (sync.isEmpty() ? 200 : 0);
                if (!nTitle.isEmpty() && !norm(cleanTitle(ct)).contains(nTitle) && !nTitle.contains(norm(cleanTitle(ct)))) score += 150;
                if (score < bestScore) { bestScore = score; best = o; }
            }
            if (best == null) return null;
            Result r = parse(best.optString("syncedLyrics", ""), best.optString("plainLyrics", ""));
            if (r != null) r.source = "SimpMusic";
            return r;
        } catch (Exception ignored) {}
        return null;
    }

    // ---- Provider 6: lyrics.ovh (plain text fallback) ----
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

    // ---- Native Korean translation subtitle helpers (NO machine translation!) ----
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

    /**
     * Detects dual-language LRC where a non-Korean lyric line and its human-authored Korean translation
     * share the same timestamp (<= 150ms apart), merging the Korean line into {@code line.trans}.
     */
    static void extractDualKoreanLines(Result r) {
        if (r == null || !r.synced || r.lines.size() < 2) return;
        List<Line> merged = new ArrayList<>(r.lines.size());
        int i = 0;
        while (i < r.lines.size()) {
            Line a = r.lines.get(i);
            if (i + 1 < r.lines.size()) {
                Line b = r.lines.get(i + 1);
                if (Math.abs(b.time - a.time) <= 150L) {
                    boolean aKo = hasHangul(a.text);
                    boolean bKo = hasHangul(b.text);
                    if (!aKo && bKo) {
                        a.trans = b.text;
                        merged.add(a);
                        i += 2;
                        continue;
                    } else if (aKo && !bKo) {
                        b.trans = a.text;
                        merged.add(b);
                        i += 2;
                        continue;
                    }
                }
            }
            merged.add(a);
            i++;
        }
        r.lines.clear();
        r.lines.addAll(merged);
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

    // ---- Optional real-time Namuwiki song article lyrics & Korean translation lookup (default OFF; honest app UA) ----
    private static final Pattern HTML_TR = Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr>");
    private static final Pattern HTML_TD = Pattern.compile("(?is)<td\\b[^>]*>(.*?)</td>");
    private static final Pattern HTML_DETAILS_LYRICS = Pattern.compile("(?is)<summary\\b[^>]*>[^<]*가사[^<]*</summary>(.*?)</details>");
    private static final Pattern HTML_RUBY_ANNO = Pattern.compile("(?is)<(?:rt|rp)\\b[^>]*>.*?</(?:rt|rp)>");
    private static final Pattern HTML_BREAK = Pattern.compile("(?i)<(?:br\\b[^>]*|/p\\s*|/div\\s*|/li\\s*)>");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern HTML_SCRIPT_STYLE = Pattern.compile("(?is)<(?:script|style|noscript)\\b[^>]*>.*?</(?:script|style|noscript)>");
    private static final Pattern HTML_ENTITY_NUM = Pattern.compile("&#(x?[0-9a-fA-F]+);");

    static final class NamuMatch {
        final List<String[]> pairs;
        final List<String> plain;
        NamuMatch(List<String[]> pairs, List<String> plain) {
            this.pairs = pairs == null ? new ArrayList<>() : pairs;
            this.plain = plain == null ? new ArrayList<>() : plain;
        }
        boolean hasLyrics() {
            return pairs.size() >= 3 || plain.size() >= 4;
        }
    }

    private static List<String> namuCandidates(String artist, String origTitle, String clean) {
        String base = !clean.isEmpty() ? clean : origTitle;
        List<String> out = new ArrayList<>(6);
        if (base == null || base.isEmpty()) return out;
        if (artist != null && !artist.isEmpty()) {
            // Handle "IVE (아이브)" -> try full artist name and primary/alias tokens
            String a = artist.trim();
            addUnique(out, base + "(" + a + ")");
            int par = a.indexOf('(');
            int parEnd = a.indexOf(')');
            if (par > 0 && parEnd > par) {
                String before = a.substring(0, par).trim();
                String inside = a.substring(par + 1, parEnd).trim();
                if (!before.isEmpty()) addUnique(out, base + "(" + before + ")");
                if (!inside.isEmpty()) addUnique(out, base + "(" + inside + ")");
            }
        }
        addUnique(out, base);
        addUnique(out, base + "(노래)");
        addUnique(out, base + "(곡)");
        if (origTitle != null && !origTitle.isEmpty() && !origTitle.equals(base)) addUnique(out, origTitle);
        return out;
    }

    private static void addUnique(List<String> list, String v) {
        if (v != null && !v.isEmpty() && !list.contains(v)) list.add(v);
    }

    /** Queries Namuwiki public song articles (honest app UA, stops immediately on Cloudflare/challenge pages). */
    private static NamuMatch fetchNamuwiki(String artist, String origTitle, String clean) {
        List<String> candidates = namuCandidates(artist, origTitle, clean);
        int tried = 0;
        for (String doc : candidates) {
            if (tried >= 4) break;
            tried++;
            try {
                String url = "https://namu.wiki/w/" + Spotify.enc(doc).replace("+", "%20");
                String html = getText(url, 5500);
                if (html == null || html.isEmpty()) continue;
                // Respect anti-bot / Cloudflare challenge responses: stop immediately without bypassing.
                if (isNamuChallengePage(html)) return null;
                // 9.5.31: only the song's own article — a bare title often lands on an unrelated article
                // (another song, a film, an astronomy page…) whose text was being shown as "lyrics"
                if (!isNamuSongArticle(html, artist, origTitle, clean)) continue;
                String songTitle = clean.isEmpty() ? origTitle : clean;
                List<String[]> pairs = parseNamuwikiHtml(html, songTitle);
                List<String> plain = parseNamuwikiPlainLines(html, songTitle);
                NamuMatch m = new NamuMatch(pairs, plain);
                if (m.hasLyrics()) return m;
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Resolves final lyrics with Namuwiki as TOP priority:
     * - If Namuwiki has (original, Korean) pairs:
     *   - When a synced source matches the same song, merges Namuwiki's Korean translations (overwriting other
     *     providers' translations so Namuwiki always wins) while preserving synced timestamps.
     *   - When no synced source matches (or only plain/wrong-song lyrics exist), returns Namuwiki's lyrics directly.
     * - If Namuwiki has plain lyrics (e.g. Korean songs):
     *   - Keeps synced timestamps only when the synced text matches Namuwiki; otherwise returns Namuwiki directly.
     */
    static Result resolveWithNamuPriority(NamuMatch nm, Result other, boolean wantKoSub) {
        if (nm == null || !nm.hasLyrics()) return other;
        if (nm.pairs.size() >= 3) {
            if (other != null && other.synced && !other.lines.isEmpty()) {
                int minNeed = Math.max(2, Math.min(other.lines.size(), nm.pairs.size()) / 4);
                String[] before = new String[other.lines.size()];
                for (int i = 0; i < before.length; i++) before[i] = other.lines.get(i).trans;
                int matched = applyNamuwikiPairs(other, nm.pairs);
                if (matched < minNeed) for (int i = 0; i < before.length; i++) other.lines.get(i).trans = before[i];   // wrong page: undo
                if (matched >= minNeed) {
                    other.source = (other.source == null || other.source.isEmpty())
                            ? "Namuwiki" : ("Namuwiki · " + other.source);
                    return other;
                }
                // 9.5.31: synced lyrics exist and Namuwiki's text doesn't match them → Namuwiki found the wrong page
                if (other.lines.size() >= 8) return other;
            }
            Result res = new Result();
            res.synced = false;
            res.source = "Namuwiki";
            for (String[] pair : nm.pairs) {
                res.lines.add(new Line(-1, 0, pair[0], wantKoSub ? pair[1] : null, null));
            }
            return res;
        }
        if (nm.plain.size() >= 4) {
            if (other != null && other.synced && !other.lines.isEmpty()) {
                int minNeed = Math.max(2, Math.min(other.lines.size(), nm.plain.size()) / 4);
                if (countPlainOverlap(other, nm.plain) >= minNeed) {
                    other.source = (other.source == null || other.source.isEmpty())
                            ? "Namuwiki" : ("Namuwiki · " + other.source);
                    return other;
                }
                if (other.lines.size() >= 8) return other;   // see above
            }
            Result res = new Result();
            res.synced = false;
            res.source = "Namuwiki";
            for (String ln : nm.plain) res.lines.add(new Line(-1, ln));
            return res;
        }
        return other;
    }

    private static int countPlainOverlap(Result r, List<String> plain) {
        if (r == null || plain == null || plain.isEmpty()) return 0;
        String[] normPlain = new String[plain.size()];
        for (int i = 0; i < plain.size(); i++) normPlain[i] = norm(plain.get(i));
        int matched = 0;
        for (Line l : r.lines) {
            String nl = norm(l.text);
            if (nl.length() < 2) continue;
            for (String np : normPlain) {
                if (np.length() >= 2 && lineSimilarity(nl, np) >= 0.65f) {
                    matched++;
                    break;
                }
            }
        }
        return matched;
    }

    static boolean isNamuChallengePage(String html) {
        if (html == null || html.isEmpty()) return true;
        String head = html.length() > 4000 ? html.substring(0, 4000).toLowerCase(Locale.ROOT) : html.toLowerCase(Locale.ROOT);
        if (head.contains("just a moment") || head.contains("cf-browser-verification")
                || head.contains("challenge-platform") || head.contains("attention required")) return true;
        return !hasHangul(html);
    }

    /** Extracts the best collapsible "[ 가사 보기 ]" block from a Namuwiki song article (preferring full over TV Ver.). */
    private static String extractNamuLyricsBlock(String body) {
        Matcher dm = HTML_DETAILS_LYRICS.matcher(body);
        String best = null;
        int bestLen = 0;
        while (dm.find()) {
            String block = dm.group(1);
            if (block == null) continue;
            // Prefer the longest lyrics foldout (skips short TV Ver. / chorus snippets)
            if (block.length() > bestLen) {
                best = block;
                bestLen = block.length();
            }
        }
        return best;
    }

    /** Extracts plain lyrics lines from a Namuwiki song article's lyrics box (supports Korean-only songs too). */
    static List<String> parseNamuwikiPlainLines(String html) { return parseNamuwikiPlainLines(html, null); }

    static List<String> parseNamuwikiPlainLines(String html, String title) {
        List<String> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;
        String body = HTML_SCRIPT_STYLE.matcher(html).replaceAll("");
        NamuRegion reg = namuLyricsRegion(body, title);
        if (reg == null) return out;
        String block = reg.foldout != null ? reg.foldout : (reg.lyricsHeading ? reg.html : null);
        if (block == null) return out;
        List<String> lines = namuBlockLines(block);
        // a foldout that is mostly page chrome isn't lyrics
        if (junkShare(lines) > .3f) return out;
        // a translated song ("original / Korean translation" alternating) is handled as pairs, not as plain lyrics
        if (isTranslationLayout(lines)) return out;
        for (String line : lines) {
            if (line.length() <= 180 && !isMetaLine(line) && !isJunkLine(line)) out.add(line);
        }
        return out;
    }

    /** The foldout's lines in order, without the member colour legend ("ALL 카리나 지젤 윈터 닝닝") at the top. */
    static List<String> namuBlockLines(String block) {
        List<String> lines = splitCellLines(block);
        while (!lines.isEmpty() && isMemberLegend(lines.get(0))) lines.remove(0);
        return lines;
    }

    /** "ALL 카리나 지젤 윈터 닝닝", "카리나 지젤 윈터 닝닝": short name tokens only, the part-colour key of a group song. */
    static boolean isMemberLegend(String line) {
        String t = line.trim();
        String[] tok = t.split("\\s+");
        if (tok.length < 3) return false;
        boolean all = tok[0].equalsIgnoreCase("ALL") || tok[0].equals("전원") || tok[0].equals("모두");
        int names = 0;
        for (String k : tok) {
            if (k.length() > 6) return false;
            if (hasHangul(k) || k.equalsIgnoreCase("ALL")) names++;
        }
        return all || names == tok.length && t.length() <= 40 && !t.matches(".*[,.!?~].*");
    }

    /**
     * Namuwiki writes a foreign song as original line + Korean translation (Japanese: + reading), strictly alternating.
     * A Korean song mixes English and Korean freely — treating its next Korean line as the "translation" of an
     * English line (9.5.30) mangled it: "Look at me go" got "재미 좀 볼" as a translation and Korean lines vanished.
     */
    static boolean isTranslationLayout(List<String> lines) {
        int foreign = 0, followed = 0, hangul = 0, orphan = 0;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (isMetaLine(l) || isJunkLine(l) || !hasLettersStr(l)) continue;
            if (!hasHangul(l)) {
                foreign++;
                if (i + 1 < lines.size() && hasHangul(lines.get(i + 1))) followed++;
            } else {
                hangul++;
                boolean afterForeign = (i >= 1 && !hasHangul(lines.get(i - 1)) && hasLettersStr(lines.get(i - 1)))
                        || (i >= 2 && hasHangul(lines.get(i - 1)) && !hasHangul(lines.get(i - 2)) && hasLettersStr(lines.get(i - 2)));
                if (!afterForeign) orphan++;
            }
        }
        return foreign >= 4 && hangul >= 4 && followed >= foreign * .85f && orphan <= hangul * .15f;
    }

    /** Extracts (original, Korean translation) line pairs from a Namuwiki song article's HTML. */
    static List<String[]> parseNamuwikiHtml(String html) { return parseNamuwikiHtml(html, null); }

    static List<String[]> parseNamuwikiHtml(String html, String title) {
        List<String[]> pairs = new ArrayList<>();
        if (html == null || html.isEmpty()) return pairs;
        String full = HTML_SCRIPT_STYLE.matcher(html).replaceAll("");
        // 9.5.31: never the whole article — the top of the page (infobox, overview, other tracks) isn't lyrics
        NamuRegion reg = namuLyricsRegion(full, title);
        if (reg == null) return pairs;
        String body = reg.html;

        // 1) Modern Namuwiki collapsible "[ 가사 보기 ]" lyrics block (handles both 2-line EN/KO and 3-line JP/pronunciation/KO)
        String lyricsBlock = reg.foldout;
        if (lyricsBlock != null) {
            List<String> bl = namuBlockLines(lyricsBlock);
            // only a real translated layout gives pairs; a Korean song is plain lyrics (parseNamuwikiPlainLines)
            if (junkShare(bl) <= .3f && isTranslationLayout(bl)) extractPairsFromSequentialLines(bl, pairs);
            return pairs;
        }

        // 2) Legacy 2-column / 3-column lyric tables (orig cell + Korean translation cell)
        Matcher trm = HTML_TR.matcher(body);
        while (trm.find()) {
            String row = trm.group(1);
            List<String> cells = new ArrayList<>();
            Matcher tdm = HTML_TD.matcher(row);
            while (tdm.find()) cells.add(tdm.group(1));
            if (cells.size() < 2) continue;
            List<String> origLines = null, koLines = null;
            for (String cell : cells) {
                List<String> lines = splitCellLines(cell);
                if (lines.isEmpty()) continue;
                boolean ko = majorityHangul(lines);
                if (!ko && origLines == null && hasLetters(lines)) origLines = lines;
                else if (ko && !isMetaCell(lines)) koLines = lines; // keep last Korean cell (translation over pronunciation)
            }
            if (origLines != null && koLines != null) {
                int n = Math.min(origLines.size(), koLines.size());
                if (n > 0 && Math.abs(origLines.size() - koLines.size()) <= Math.max(2, n / 3)) {
                    for (int i = 0; i < n; i++) {
                        String o = origLines.get(i), k = koLines.get(i);
                        if (!hasHangul(o) && hasHangul(k) && !isMetaLine(k) && !isJunkLine(o) && !isJunkLine(k)) {
                            pairs.add(new String[]{o, k});
                        }
                    }
                }
            }
        }
        if (pairs.size() >= 2) return pairs;

        // (9.5.31) the old 3rd fallback — pairing any English line with the next Korean line anywhere after the
        // word "가사", or across the WHOLE page when it wasn't found — produced the unrelated "recommendation"-like
        // text. Lyrics now come only from the lyrics foldout or lyric tables.
        return pairs;
    }

    private static final Pattern HTML_TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern HTML_HEADING = Pattern.compile("(?is)<h([1-6])\\b[^>]*>(.*?)</h\\1>");

    /** The part of an article that holds the lyrics: a section's HTML and the "[ 가사 보기 ]" foldout in it (if any). */
    static final class NamuRegion {
        final String html, foldout;
        final boolean lyricsHeading;
        NamuRegion(String html, String foldout, boolean lyricsHeading) { this.html = html; this.foldout = foldout; this.lyricsHeading = lyricsHeading; }
    }

    /** "3.1. Supernova[편집]" → "Supernova" */
    static String headingText(String h) {
        String t = decodeHtmlEntities(HTML_TAG.matcher(h).replaceAll(" ")).replace('\u00A0', ' ').trim();
        t = t.replaceAll("^[0-9]+(?:\\.[0-9]+)*\\.?\\s*", "").replaceAll("\\s*\\[?편집\\]?$", "").trim();
        return t;
    }

    private static boolean isLyricsHeading(String name) {
        String n = name.replace(" ", "");
        return n.equals("가사") || n.startsWith("가사") || n.endsWith("가사") || n.equals("Lyrics");
    }

    /**
     * Finds where this song's lyrics are, by the article's section headings — never by scanning the whole page:
     *  1. a "가사" section inside the section named after the song (album articles: "3.1. Supernova" › "가사")
     *  2. a "가사" section ("2. 가사" in a song article)
     *  3. the section named after the song ("2. Shape of You", "3.1. Supernova") — its lyrics foldout
     *  4. no such heading: only when the whole article has exactly one "가사 보기" foldout
     * The section ends at the next heading of the same or a higher level. Inside it, the FIRST lyrics foldout
     * is used (the longest one was sometimes another track's lyrics in an album article).
     */
    static NamuRegion namuLyricsRegion(String body, String title) {
        List<int[]> hs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Matcher m = HTML_HEADING.matcher(body);
        while (m.find()) { hs.add(new int[]{m.start(), m.end(), Integer.parseInt(m.group(1))}); names.add(headingText(m.group(2))); }
        String nt = title == null ? "" : norm(cleanTitle(title));
        int best = -1, bestScore = 0;
        for (int i = 0; i < hs.size(); i++) {
            int lv = hs.get(i)[2];
            if (lv <= 1) continue;   // the article title
            String name = names.get(i);
            boolean songHead = !nt.isEmpty() && norm(cleanTitle(name)).equals(nt);
            int score = 0;
            if (isLyricsHeading(name)) {
                score = 3;
                // inside the song's own section (nearest enclosing heading)?
                for (int j = i - 1; j >= 0; j--) {
                    if (hs.get(j)[2] < lv) { if (!nt.isEmpty() && norm(cleanTitle(names.get(j))).equals(nt)) score = 5; break; }
                }
            } else if (songHead) score = 2;
            if (score > bestScore) { bestScore = score; best = i; }
        }
        if (best >= 0) {
            int lv = hs.get(best)[2], end = body.length();
            for (int j = best + 1; j < hs.size(); j++) if (hs.get(j)[2] <= lv) { end = hs.get(j)[0]; break; }
            String region = body.substring(hs.get(best)[1], end);
            String fold = firstLyricsFoldout(region);
            if (fold != null || bestScore >= 3) return new NamuRegion(region, fold, bestScore >= 3);
        }
        // no lyrics / song heading: accept only an unambiguous single foldout in the article
        Matcher dm = HTML_DETAILS_LYRICS.matcher(body);
        String only = null;
        int n = 0;
        while (dm.find()) { n++; only = dm.group(1); }
        return n == 1 ? new NamuRegion(only, only, false) : null;
    }

    private static String firstLyricsFoldout(String region) {
        Matcher dm = HTML_DETAILS_LYRICS.matcher(region);
        return dm.find() ? dm.group(1) : null;
    }

    /** Is this Namuwiki page the article about this song by this artist? */
    static boolean isNamuSongArticle(String html, String artist, String origTitle, String clean) {
        if (html == null) return false;
        Matcher tm = HTML_TITLE.matcher(html);
        String title = tm.find() ? decodeHtmlEntities(HTML_TAG.matcher(tm.group(1)).replaceAll("")).trim() : "";
        title = title.replaceAll("\\s*-\\s*나무위키\\s*$", "").trim();
        if (title.isEmpty()) return false;
        String base = title, paren = null;
        int p = title.lastIndexOf('(');
        if (p > 0 && title.endsWith(")")) { base = title.substring(0, p).trim(); paren = title.substring(p + 1, title.length() - 1).trim(); }
        String nb = norm(base), nc = norm(clean), no = norm(origTitle);
        boolean titleOk = !nb.isEmpty() && (nb.equals(nc) || nb.equals(no)
                || (nc.length() >= 3 && (nb.contains(nc) || nc.contains(nb)) && Math.min(nb.length(), nc.length()) * 10 >= Math.max(nb.length(), nc.length()) * 7));
        if (!titleOk) return false;
        String lower = html.toLowerCase(Locale.ROOT);
        List<String> aliases = artistAliases(artist);
        if (paren != null) {
            String np = norm(paren);
            boolean parenOk = np.equals("노래") || np.equals("곡") || np.equals("싱글") || np.startsWith("노래") || np.endsWith("노래") || np.endsWith("곡");
            for (String a : aliases) if (!norm(a).isEmpty() && (np.contains(norm(a)) || norm(a).contains(np))) parenOk = true;
            if (!parenOk) return false;   // e.g. "Supernova(천문학)", "Hype Boy(다른 가수)"
        }
        boolean artistOk = aliases.isEmpty();
        for (String a : aliases) if (a.length() >= 2 && lower.contains(a.toLowerCase(Locale.ROOT))) { artistOk = true; break; }
        if (!artistOk) return false;
        // a song article lists its credits / release
        return html.contains("작사") || html.contains("작곡") || html.contains("수록") || html.contains("발매");
    }

    /** "IVE (아이브)" → ["IVE (아이브)", "IVE", "아이브"] */
    private static List<String> artistAliases(String artist) {
        List<String> out = new ArrayList<>();
        if (artist == null) return out;
        String a = artist.trim();
        if (a.isEmpty()) return out;
        addUnique(out, a);
        int par = a.indexOf('('), end = a.indexOf(')');
        if (par > 0 && end > par) { addUnique(out, a.substring(0, par).trim()); addUnique(out, a.substring(par + 1, end).trim()); }
        return out;
    }

    private static float junkShare(List<String> lines) {
        if (lines == null || lines.isEmpty()) return 1f;
        int j = 0;
        for (String l : lines) if (isJunkLine(l)) j++;
        return j / (float) lines.size();
    }

    /** Text that is page chrome, links or recommendations rather than a lyric line. */
    static boolean isJunkLine(String s) {
        if (s == null) return true;
        String t = s.trim(), l = t.toLowerCase(Locale.ROOT);
        if (t.length() < 2) return true;
        if (l.contains("http") || l.contains("www.") || l.contains(".com") || l.contains("youtube") || l.contains("유튜브")) return true;
        String[] chrome = {"조회수", "구독자", "재생목록", "추천 영상", "추천 문서", "광고", "최근 변경", "최근 토론", "문서로", "편집 요청", "분류:", "틀:", "각주",
                "댓글", "알고리즘", "관련 영상", "다음 동영상", "앨범 정보", "수록곡", "트랙리스트", "뮤직비디오", "ⓒ", "©", "▶", "|", "[편집]"};
        for (String c : chrome) if (t.contains(c)) return true;
        int digits = 0;
        for (int i = 0; i < t.length(); i++) if (Character.isDigit(t.charAt(i))) digits++;
        return digits >= 5 || (t.length() > 3 && digits * 2 > t.length());
    }

    /**
     * Parses sequential Namuwiki lyric lines into (original, Korean translation) pairs.
     * Supports both 2-line blocks (English -> Korean translation) and 3-line Japanese blocks
     * (Japanese original -> Korean phonetic reading -> Korean translation, taking the last Hangul line).
     */
    private static void extractPairsFromSequentialLines(List<String> lines, List<String[]> out) {
        int i = 0;
        while (i < lines.size()) {
            String a = lines.get(i);
            if (a.length() < 2 || a.length() > 180 || hasHangul(a) || !hasLettersStr(a) || isMetaLine(a) || isJunkLine(a)) {
                i++;
                continue;
            }
            // Collect 1..2 immediately following Hangul lines (2 lines = phonetic reading + meaning translation)
            int j = i + 1;
            String bestKo = null;
            while (j < lines.size() && j <= i + 2) {
                String cand = lines.get(j);
                if (cand.length() < 2 || cand.length() > 180 || !hasHangul(cand) || isMetaLine(cand) || isJunkLine(cand)) break;
                bestKo = cand; // overwrite phonetic line with meaning translation line when 2 Korean lines follow
                j++;
            }
            if (bestKo != null) {
                out.add(new String[]{a, bestKo});
                i = j;
            } else {
                i++;
            }
        }
    }

    /** Matches extracted Namuwiki (original, Korean) pairs to synced Result lines and returns match count. */
    static int applyNamuwikiPairs(Result r, List<String[]> pairs) {
        if (r == null || pairs == null || pairs.isEmpty()) return 0;
        String[] normPairs = new String[pairs.size()];
        for (int i = 0; i < pairs.size(); i++) normPairs[i] = norm(pairs.get(i)[0]);
        int matched = 0;
        int cursor = 0;
        for (Line l : r.lines) {
            if (l.text == null || l.text.isEmpty() || hasHangul(l.text)) continue;
            String nl = norm(l.text);
            if (nl.length() < 2) continue;
            int bestIdx = -1;
            float bestScore = 0f;
            for (int i = 0; i < pairs.size(); i++) {
                String np = normPairs[i];
                if (np.length() < 2) continue;
                float sc = lineSimilarity(nl, np);
                // Small locality boost for sequential lines so repeated choruses align naturally
                if (i >= cursor && i <= cursor + 6) sc += 0.08f;
                if (sc > bestScore) { bestScore = sc; bestIdx = i; }
            }
            if (bestIdx >= 0 && bestScore >= 0.65f) {
                l.trans = pairs.get(bestIdx)[1];
                cursor = bestIdx + 1;
                matched++;
            }
        }
        return matched;
    }

    private static float lineSimilarity(String a, String b) {
        if (a.equals(b)) return 1.0f;
        int min = Math.min(a.length(), b.length()), max = Math.max(a.length(), b.length());
        if (min >= 4 && (a.contains(b) || b.contains(a))) return (float) min / max;
        if (a.length() < 2 || b.length() < 2) return 0f;
        Map<String, Integer> bg = new HashMap<>();
        int total = (a.length() - 1) + (b.length() - 1);
        for (int i = 0; i < a.length() - 1; i++) {
            String k = a.substring(i, i + 2);
            Integer c = bg.get(k);
            bg.put(k, c == null ? 1 : c + 1);
        }
        int common = 0;
        for (int i = 0; i < b.length() - 1; i++) {
            String k = b.substring(i, i + 2);
            Integer c = bg.get(k);
            if (c != null && c > 0) { common++; bg.put(k, c - 1); }
        }
        return (2f * common) / total;
    }

    private static List<String> splitCellLines(String html) {
        // Strip <rt>/<rp> furigana annotations BEFORE stripping tags so Japanese kanji stays exact for matching
        String s = HTML_RUBY_ANNO.matcher(html).replaceAll("");
        s = HTML_BREAK.matcher(s).replaceAll("\n");
        s = HTML_TAG.matcher(s).replaceAll("");
        s = decodeHtmlEntities(s);
        List<String> out = new ArrayList<>();
        for (String raw : s.split("\n")) {
            String t = raw.replace('\u00A0', ' ').replaceAll("\\[[0-9]+\\]", "").trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String decodeHtmlEntities(String s) {
        if (s == null || s.indexOf('&') < 0) return s == null ? "" : s;
        String r = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ");
        Matcher m = HTML_ENTITY_NUM.matcher(r);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            try {
                String g = m.group(1);
                int cp = g.startsWith("x") || g.startsWith("X") ? Integer.parseInt(g.substring(1), 16) : Integer.parseInt(g);
                m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
            } catch (Exception e) {
                m.appendReplacement(sb, "");
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean majorityHangul(List<String> lines) {
        int ko = 0;
        for (String s : lines) if (hasHangul(s)) ko++;
        return ko * 2 >= lines.size();
    }

    private static boolean hasLetters(List<String> lines) {
        for (String s : lines) if (hasLettersStr(s)) return true;
        return false;
    }

    private static boolean hasLettersStr(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) n++;
        return n >= 2;
    }

    private static boolean isMetaCell(List<String> lines) {
        return lines.size() == 1 && isMetaLine(lines.get(0));
    }

    private static boolean isMetaLine(String s) {
        if (s == null) return true;
        String t = s.trim();
        return t.startsWith("작사") || t.startsWith("작곡") || t.startsWith("편곡")
                || t.startsWith("가수") || t.startsWith("발매") || t.startsWith("수록")
                || t.startsWith("번역") || t.startsWith("출처") || t.startsWith("원문")
                || t.startsWith("해석") || t.startsWith("편집") || t.startsWith("분류:");
    }

    static String cleanTitle(String s) {
        if (s == null) return "";
        String r = s.replaceAll("(?i)\\s*[(\\[](feat\\.?|ft\\.?|with|prod\\.?|ver\\.?|version|remaster|live|ost|from\\s)[^)\\]]*[)\\]]", "");
        r = r.replaceAll("(?i)\\s+-\\s+.*(remaster|version|edit|live|mix|mono|stereo|ver\\.).*$", "");
        return r.trim().isEmpty() ? s : r.trim();
    }

    private static String getText(String url, int timeout) throws Exception {
        return getTextWithHeader(url, null, null, timeout);
    }

    private static String getTextWithHeader(String url, String hKey, String hVal, int timeout) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setInstanceFollowRedirects(true);
        h.setConnectTimeout(4500);
        h.setReadTimeout(timeout);
        h.setRequestProperty("User-Agent", "Prism/9.5 (Android)");
        if (hKey != null && hVal != null) h.setRequestProperty(hKey, hVal);
        try {
            if (h.getResponseCode() != 200) return null;
            return Spotify.read(h.getInputStream());
        } finally { h.disconnect(); }
    }

    private static JSONObject getObj(String url, int timeout) throws Exception {
        String s = getText(url, timeout);
        return s == null ? null : new JSONObject(s);
    }

    private static JSONObject getObjWithHeader(String url, String hKey, String hVal, int timeout) throws Exception {
        String s = getTextWithHeader(url, hKey, hVal, timeout);
        return s == null ? null : new JSONObject(s);
    }

    // ---- disk cache (v5: Namuwiki top-priority lyrics + native translation tracks) ----
    private static File file(String id) {
        if (Img.appContext == null) return null;
        File d = new File(Img.appContext.getCacheDir(), "lyrics_v7");
        d.mkdirs();
        return new File(d, Integer.toHexString(id.hashCode()) + "_" + id.length() + ".json");
    }

    private static Result readDisk(String id) {
        try {
            File f = file(id);
            if (f == null || !f.exists()) return null;
            JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (!id.equals(o.optString("id"))) return null;
            Result r = new Result();
            r.synced = o.optBoolean("synced", false);
            r.source = o.optString("src", "");
            String te = o.optString("te", "");
            r.transEngine = te.isEmpty() ? null : te;
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
            File f = file(id);
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
            if (r.transEngine != null) o.put("te", r.transEngine);
            java.nio.file.Files.write(f.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    /** Estimates natural vocal duration for a sung lyric line so instrumental interludes never drag out the fill. */
    static long estimateVocalDurationMs(String text) {
        if (text == null || text.trim().isEmpty()) return 1800L;
        String s = text.trim();
        int cjk = 0, other = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isWhitespace(ch)) continue;
            if ((ch >= 0xAC00 && ch <= 0xD7A3) || (ch >= 0x3040 && ch <= 0x30FF) || (ch >= 0x4E00 && ch <= 0x9FFF)) cjk++;
            else other++;
        }
        long est = 1050L + cjk * 240L + other * 95L;
        return Math.max(2000L, Math.min(6800L, est));
    }

    static Result finalizeEndTimes(Result r, long trackDurationMs) {
        if (r == null || !r.synced || r.lines.isEmpty()) return r;
        // Pass 1: collapse explicit blank/interlude timestamp lines (e.g. "[01:24.00] ") into the previous line's endTime!
        List<Line> cleaned = new ArrayList<>(r.lines.size());
        for (int i = 0; i < r.lines.size(); i++) {
            Line cur = r.lines.get(i);
            String txt = cur.text == null ? "" : cur.text.trim();
            boolean isBlankMarker = txt.isEmpty() || "♪".equals(txt) || "🎵".equals(txt) || "...".equals(txt) || "…".equals(txt);
            if (isBlankMarker && !cleaned.isEmpty()) {
                Line prev = cleaned.get(cleaned.size() - 1);
                if (!prev.explicitEnd && cur.time > prev.time + 300L) {
                    prev.endTime = cur.time;
                    prev.explicitEnd = true;
                }
                continue;
            }
            cleaned.add(cur);
        }
        if (!cleaned.isEmpty()) {
            r.lines.clear();
            r.lines.addAll(cleaned);
        }

        // Pass 2: assign vocal endTimes; cap across long instrumental interludes so live fill finishes cleanly on tempo!
        for (int i = 0; i < r.lines.size(); i++) {
            Line cur = r.lines.get(i);
            long next = (i + 1 < r.lines.size()) ? r.lines.get(i + 1).time : -1L;
            if (cur.words != null && !cur.words.isEmpty()) {
                Word last = cur.words.get(cur.words.size() - 1);
                long wEnd = last.start + last.duration;
                if (wEnd > cur.time) {
                    cur.endTime = next > cur.time ? Math.min(wEnd, next) : wEnd;
                    cur.explicitEnd = true;
                    continue;
                }
            }
            if (cur.explicitEnd && cur.endTime > cur.time) {
                if (next > cur.time && cur.endTime > next) cur.endTime = next;
                continue;
            }
            long est = estimateVocalDurationMs(cur.text);
            if (next > cur.time) {
                long gap = next - cur.time;
                // If there is a clear instrumental interlude (> est + 2.2s), end vocal fill at cur.time + est!
                if (gap > est + 2200L) {
                    cur.endTime = cur.time + est;
                } else {
                    cur.endTime = next;
                }
            } else {
                long tail = trackDurationMs > cur.time ? Math.min(cur.time + Math.max(est, 4800L), trackDurationMs) : cur.time + est;
                cur.endTime = Math.max(cur.time + 1800L, tail);
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
