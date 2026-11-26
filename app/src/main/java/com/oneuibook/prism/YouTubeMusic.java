package com.oneuibook.prism;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * YouTube Music integration inspired by Square (https://github.com/Lelonio/Square):
 * - Full third music source ("ytm") alongside Local & Spotify: Home Quick Picks, Charts, Search (songs/videos/albums/artists),
 *   optional Google Sign-In via standard WebView cookies (SAPISIDHASH) for personal playlists/likes.
 * - Direct audio & music-video stream resolution via YouTube InnerTube API (ANDROID_MUSIC / IOS / ANDROID_VR / WEB_REMIX)
 *   with clean fallback chain so catalog browsing, song playback, and Switch-to-Video work even without logging in.
 */
final class YouTubeMusic {
    private YouTubeMusic() {}

    static final String ORIGIN = "https://music.youtube.com";
    static final String API_BASE = "https://music.youtube.com/youtubei/v1/";
    static final String YT_API_BASE = "https://www.youtube.com/youtubei/v1/";
    private static final String IOS_UA = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X)";
    private static final String PREF_COOKIE = "ytm_cookie";
    private static final String PREF_ACCOUNT = "ytm_account";

    static final ExecutorService IO = Executors.newFixedThreadPool(4);
    static Context app;

    /** Test seam for Robolectric UI tests (returns mock shelves/tracks without network). */
    static java.util.function.Function<String, List<Widgets.Item>> testFixtures;

    static final class MediaStreams {
        String audioUrl;
        long audioContentLength = -1;
        String videoUrl;
        String hlsUrl;
        String userAgent;
        String videoId;
        long durationMs;
        String title;
        String artist;
        String thumb;
    }

    static boolean isYtm(Player.Track t) {
        return t != null && t.uri != null && t.uri.startsWith("ytm:");
    }

    static String videoIdOf(String uriOrId) {
        if (uriOrId == null) return "";
        if (uriOrId.startsWith("ytm:track:")) return uriOrId.substring(10);
        if (uriOrId.startsWith("ytm:video:")) return uriOrId.substring(10);
        if (uriOrId.startsWith("ytm:")) return uriOrId.substring(4);
        return uriOrId;
    }

    static boolean signedIn(Context c) {
        String ck = cookie(c);
        return ck != null && (ck.contains("SAPISID=") || ck.contains("__Secure-3PAPISID="));
    }

    static String cookie(Context c) {
        Context ctx = c != null ? c : app;
        if (ctx == null) return "";
        return MainActivity.prefs(ctx).getString(PREF_COOKIE, "");
    }

    static String accountLabel(Context c) {
        Context ctx = c != null ? c : app;
        if (ctx == null) return "";
        String lbl = MainActivity.prefs(ctx).getString(PREF_ACCOUNT, "");
        return lbl.isEmpty() ? (signedIn(ctx) ? ctx.getString(R.string.ytm_signed_in) : "") : lbl;
    }

    static void signOut(Context c) {
        Context ctx = c != null ? c : app;
        if (ctx == null) return;
        MainActivity.prefs(ctx).edit().remove(PREF_COOKIE).remove(PREF_ACCOUNT).apply();
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
        } catch (Throwable ignored) {}
    }

    /** Opens a standard user-driven Google sign-in WebView for YouTube Music library sync. */
    static void showSignInDialog(Activity a, Runnable onDone) {
        if (a == null) return;
        app = a.getApplicationContext();
        FrameLayout box = new FrameLayout(a);
        WebView wv = new WebView(a);
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        box.addView(wv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(500)));
        final AlertDialog[] dlg = new AlertDialog[1];
        final boolean[] completed = {false};
        wv.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (completed[0]) return;
                try {
                    CookieManager.getInstance().flush();
                    String c1 = CookieManager.getInstance().getCookie("https://music.youtube.com");
                    String c2 = CookieManager.getInstance().getCookie("https://www.youtube.com");
                    String merged = (c1 != null && (c1.contains("SAPISID=") || c1.contains("__Secure-3PAPISID="))) ? c1 : (c2 != null ? c2 : "");
                    if (merged.contains("SAPISID=") || merged.contains("__Secure-3PAPISID=")) {
                        completed[0] = true;
                        MainActivity.prefs(a).edit()
                                .putString(PREF_COOKIE, merged)
                                .putString(PREF_ACCOUNT, a.getString(R.string.ytm_signed_in))
                                .apply();
                        if (dlg[0] != null && dlg[0].isShowing()) dlg[0].dismiss();
                        Toast.makeText(a, R.string.ytm_signed_in, Toast.LENGTH_SHORT).show();
                        if (onDone != null) a.runOnUiThread(onDone);
                    }
                } catch (Throwable ignored) {}
            }
        });
        wv.setFocusable(true);
        wv.setFocusableInTouchMode(true);
        wv.setOnTouchListener((v, ev) -> {
            if (!v.hasFocus()) v.requestFocus();
            return false;
        });
        wv.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F");
        dlg[0] = new AlertDialog.Builder(a,
                Ui.dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle(R.string.ytm_sign_in)
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .show();
        // Critical Android fix: AlertDialog sets FLAG_ALT_FOCUSABLE_IM when it has no EditText child,
        // which blocks the soft keyboard (IME) from ever opening inside a WebView input field.
        android.view.Window dw = dlg[0].getWindow();
        if (dw != null) {
            dw.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            dw.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        wv.requestFocus(android.view.View.FOCUS_DOWN);
    }

    private static String sapisidHash(String cookieStr) {
        if (cookieStr == null || cookieStr.isEmpty()) return null;
        String sid = extractCookie(cookieStr, "SAPISID");
        if (sid == null) sid = extractCookie(cookieStr, "__Secure-3PAPISID");
        if (sid == null || sid.isEmpty()) return null;
        try {
            long ts = System.currentTimeMillis() / 1000L;
            String raw = ts + " " + sid + " " + ORIGIN;
            byte[] dig = MessageDigest.getInstance("SHA-1").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : dig) sb.append(String.format(Locale.US, "%02x", b));
            return "SAPISIDHASH " + ts + "_" + sb;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractCookie(String all, String name) {
        String key = name + "=";
        int idx = all.indexOf(key);
        if (idx < 0) return null;
        int end = all.indexOf(';', idx + key.length());
        return (end < 0 ? all.substring(idx + key.length()) : all.substring(idx + key.length(), end)).trim();
    }

    // ---------------------------------------------------------------- InnerTube requests
    private static JSONObject webRemixContext() throws Exception {
        String hl = Locale.getDefault().getLanguage();
        String gl = Locale.getDefault().getCountry();
        if (hl == null || hl.isEmpty()) hl = "ko";
        if (gl == null || gl.isEmpty()) gl = "KR";
        return new JSONObject()
                .put("client", new JSONObject()
                        .put("clientName", "WEB_REMIX")
                        .put("clientVersion", "1.20250317.01.00")
                        .put("hl", hl)
                        .put("gl", gl));
    }

    private static JSONObject postJson(String url, JSONObject body, String userAgent, boolean sendAuth) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(7000);
        c.setReadTimeout(12000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("Origin", ORIGIN);
        c.setRequestProperty("Referer", ORIGIN + "/");
        if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
        if (sendAuth && app != null) {
            String ck = cookie(app);
            if (!ck.isEmpty()) {
                c.setRequestProperty("Cookie", ck);
                String auth = sapisidHash(ck);
                if (auth != null) c.setRequestProperty("Authorization", auth);
            }
        }
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) { os.write(bytes); }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String txt = in == null ? "" : Spotify.read(in);
        c.disconnect();
        if (code >= 400 || txt.isEmpty()) return null;
        return new JSONObject(txt);
    }

    // ---------------------------------------------------------------- Catalog: Home / Charts / Search
    static void fetchHome(Consumer<Map<String, List<Widgets.Item>>> cb) {
        if (testFixtures != null) {
            Map<String, List<Widgets.Item>> m = new LinkedHashMap<>();
            m.put("quick", testFixtures.apply("home_quick"));
            m.put("charts", testFixtures.apply("home_charts"));
            Spotify.MAIN.post(() -> cb.accept(m));
            return;
        }
        IO.execute(() -> {
            Map<String, List<Widgets.Item>> sections = new LinkedHashMap<>();
            try {
                JSONObject req = new JSONObject()
                        .put("context", webRemixContext())
                        .put("browseId", "FEmusic_home");
                JSONObject res = postJson(API_BASE + "browse?prettyPrint=false", req, null, true);
                if (res != null) extractShelves(res, sections);
            } catch (Exception ignored) {}
            if (sections.isEmpty()) {
                // Fallback to charts browseId if personalized home returned empty
                try {
                    JSONObject req = new JSONObject()
                            .put("context", webRemixContext())
                            .put("browseId", "FEmusic_charts");
                    JSONObject res = postJson(API_BASE + "browse?prettyPrint=false", req, null, false);
                    if (res != null) extractShelves(res, sections);
                } catch (Exception ignored) {}
            }
            Spotify.MAIN.post(() -> cb.accept(sections));
        });
    }

    static void fetchExploreCharts(Consumer<List<Widgets.Item>> cb) {
        if (testFixtures != null) {
            List<Widgets.Item> list = testFixtures.apply("charts");
            Spotify.MAIN.post(() -> cb.accept(list));
            return;
        }
        IO.execute(() -> {
            List<Widgets.Item> out = new ArrayList<>();
            try {
                JSONObject req = new JSONObject()
                        .put("context", webRemixContext())
                        .put("browseId", "FEmusic_charts");
                JSONObject res = postJson(API_BASE + "browse?prettyPrint=false", req, null, false);
                if (res != null) {
                    Map<String, List<Widgets.Item>> map = new LinkedHashMap<>();
                    extractShelves(res, map);
                    for (List<Widgets.Item> v : map.values()) out.addAll(v);
                }
            } catch (Exception ignored) {}
            Spotify.MAIN.post(() -> cb.accept(out));
        });
    }

    static void search(String query, String filterParams, Consumer<List<Widgets.Item>> cb) {
        if (query == null || query.trim().isEmpty()) {
            Spotify.MAIN.post(() -> cb.accept(Collections.emptyList()));
            return;
        }
        if (testFixtures != null) {
            List<Widgets.Item> list = testFixtures.apply("search:" + query.trim());
            Spotify.MAIN.post(() -> cb.accept(list));
            return;
        }
        IO.execute(() -> {
            List<Widgets.Item> out = new ArrayList<>();
            try {
                JSONObject req = new JSONObject()
                        .put("context", webRemixContext())
                        .put("query", query.trim());
                if (filterParams != null && !filterParams.isEmpty()) req.put("params", filterParams);
                JSONObject res = postJson(API_BASE + "search?prettyPrint=false", req, null, false);
                if (res != null) extractTrackItemsRecursive(res, out, 30);
            } catch (Exception ignored) {}
            Spotify.MAIN.post(() -> cb.accept(out));
        });
    }

    /** Real-time autocomplete suggestions via YouTube/Google Suggest API (no login needed). */
    static void fetchSuggestions(String query, Consumer<List<String>> cb) {
        if (query == null || query.trim().isEmpty()) {
            Spotify.MAIN.post(() -> cb.accept(Collections.emptyList()));
            return;
        }
        IO.execute(() -> {
            List<String> out = new ArrayList<>();
            try {
                String hl = Locale.getDefault().getLanguage();
                String url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&hl="
                        + Spotify.enc(hl.isEmpty() ? "ko" : hl) + "&q=" + Spotify.enc(query.trim());
                HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
                h.setConnectTimeout(3500);
                h.setReadTimeout(4500);
                try {
                    if (h.getResponseCode() == 200) {
                        String body = Spotify.read(h.getInputStream());
                        JSONArray root = new JSONArray(body);
                        if (root.length() >= 2) {
                            JSONArray arr = root.optJSONArray(1);
                            if (arr != null) {
                                for (int i = 0; i < Math.min(8, arr.length()); i++) {
                                    String s = arr.optString(i, "").trim();
                                    if (!s.isEmpty() && !out.contains(s)) out.add(s);
                                }
                            }
                        }
                    }
                } finally { h.disconnect(); }
            } catch (Exception ignored) {}
            Spotify.MAIN.post(() -> cb.accept(out));
        });
    }

    // ---------------------------------------------------------------- Music Video Resolver (for Switch to Video)
    private static final Map<String, MediaStreams> MV_CACHE = Collections.synchronizedMap(new LinkedHashMap<String, MediaStreams>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, MediaStreams> e) { return size() > 40; }
    });

    /**
     * Resolves an official Music Video stream for ANY track (YouTube Music, Spotify, or Local).
     * Uses the track's own videoId when already a YTM track, otherwise searches YouTube Music videos cleanly.
     */
    static void findMusicVideo(Player.Track t, Consumer<MediaStreams> cb) {
        if (t == null) { Spotify.MAIN.post(() -> cb.accept(null)); return; }
        final String cacheKey = (t.id != null && !t.id.isEmpty()) ? t.id : (t.name + "|" + t.artist);
        MediaStreams cached = MV_CACHE.get(cacheKey);
        if (cached != null && cached.videoUrl != null) {
            Spotify.MAIN.post(() -> cb.accept(cached));
            return;
        }
        IO.execute(() -> {
            MediaStreams ms = null;
            try {
                String vid = isYtm(t) ? videoIdOf(t.uri) : "";
                if (vid.isEmpty()) {
                    // Search YouTube Music specifically for official music video (params EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D = Videos)
                    String q = ((t.artist == null ? "" : t.artist) + " " + (t.name == null ? "" : t.name) + " official music video").trim();
                    JSONObject req = new JSONObject()
                            .put("context", webRemixContext())
                            .put("query", q)
                            .put("params", "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D");
                    JSONObject res = postJson(API_BASE + "search?prettyPrint=false", req, null, false);
                    List<Widgets.Item> found = new ArrayList<>();
                    if (res != null) extractTrackItemsRecursive(res, found, 6);
                    if (found.isEmpty()) {
                        // Retry general search without filter
                        JSONObject req2 = new JSONObject().put("context", webRemixContext()).put("query", q);
                        JSONObject res2 = postJson(API_BASE + "search?prettyPrint=false", req2, null, false);
                        if (res2 != null) extractTrackItemsRecursive(res2, found, 6);
                    }
                    if (!found.isEmpty() && found.get(0).track != null) {
                        vid = videoIdOf(found.get(0).track.uri);
                    }
                }
                if (!vid.isEmpty()) {
                    ms = resolveStreamsSync(vid);
                    if (ms != null && ms.videoUrl != null) MV_CACHE.put(cacheKey, ms);
                }
            } catch (Exception ignored) {}
            final MediaStreams out = ms;
            Spotify.MAIN.post(() -> cb.accept(out));
        });
    }

    /** Posts an InnerTube /player request with exact native client headers (no mismatched web Origin/Referer). */
    private static JSONObject postPlayerJson(String url, JSONObject body, String clientId, String clientVer, String userAgent) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(7000);
        c.setReadTimeout(12000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("Accept", "application/json");
        if (clientId != null) c.setRequestProperty("X-YouTube-Client-Name", clientId);
        if (clientVer != null) c.setRequestProperty("X-YouTube-Client-Version", clientVer);
        if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) { os.write(bytes); }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String txt = in == null ? "" : Spotify.read(in);
        c.disconnect();
        if (code >= 400 || txt.isEmpty()) return null;
        return new JSONObject(txt);
    }

    /** Resolves direct playable audio & HLS URLs via InnerTube /player (verified IOS 20.10.4 -> ANDROID_VR -> ANDROID_MUSIC). */
    static MediaStreams resolveStreamsSync(String videoId) {
        if (videoId == null || videoId.isEmpty()) return null;
        String[][] clients = {
                {"IOS", "5", "20.10.4", IOS_UA},
                {"ANDROID_VR", "28", "1.62.27", "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"},
                {"ANDROID_MUSIC", "21", "7.16.53", "com.google.android.apps.youtube.music/7.16.53 (Linux; U; Android 14) gzip"}
        };
        for (String[] cl : clients) {
            try {
                JSONObject client = new JSONObject()
                        .put("clientName", cl[0])
                        .put("clientVersion", cl[2])
                        .put("hl", "ko")
                        .put("gl", "KR");
                if ("IOS".equals(cl[0])) {
                    client.put("deviceMake", "Apple")
                            .put("deviceModel", "iPhone16,2")
                            .put("osName", "iPhone")
                            .put("osVersion", "18.3.1.22D72");
                } else if ("ANDROID_VR".equals(cl[0])) {
                    client.put("androidSdkVersion", 32)
                            .put("osName", "Android")
                            .put("osVersion", "12");
                }
                JSONObject body = new JSONObject()
                        .put("videoId", videoId)
                        .put("contentCheckOk", true)
                        .put("racyCheckOk", true)
                        .put("context", new JSONObject().put("client", client));
                JSONObject res = postPlayerJson(YT_API_BASE + "player?prettyPrint=false", body, cl[1], cl[2], cl[3]);
                if (res == null) continue;
                MediaStreams ms = parseStreamingData(videoId, res);
                if (ms != null && (ms.audioUrl != null || ms.hlsUrl != null)) {
                    ms.userAgent = cl[3];
                    return ms;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static MediaStreams parseStreamingData(String videoId, JSONObject res) {
        JSONObject sd = res.optJSONObject("streamingData");
        if (sd == null) return null;
        MediaStreams ms = new MediaStreams();
        ms.videoId = videoId;
        JSONObject vd = res.optJSONObject("videoDetails");
        if (vd != null) {
            ms.title = vd.optString("title", "");
            ms.artist = vd.optString("author", "");
            ms.durationMs = vd.optLong("lengthSeconds", 0) * 1000L;
        }
        ms.thumb = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";

        // 1) Progressive formats (video + audio muxed, mp4 — super reliable for MediaPlayer/TextureView!)
        JSONArray formats = sd.optJSONArray("formats");
        int bestProgH = -1;
        if (formats != null) {
            for (int i = 0; i < formats.length(); i++) {
                JSONObject f = formats.optJSONObject(i);
                if (f == null) continue;
                String u = f.optString("url", "");
                if (u.isEmpty()) continue;
                String mime = f.optString("mimeType", "");
                int h = f.optInt("height", 0);
                if (mime.contains("mp4") && h >= bestProgH) {
                    bestProgH = h;
                    ms.videoUrl = u;
                    if (ms.audioUrl == null) ms.audioUrl = u;
                }
            }
        }
        // 2) Adaptive audio-only stream (highest bitrate m4a/mp4a or opus)
        JSONArray adaptive = sd.optJSONArray("adaptiveFormats");
        int bestAudioBr = -1;
        if (adaptive != null) {
            for (int i = 0; i < adaptive.length(); i++) {
                JSONObject f = adaptive.optJSONObject(i);
                if (f == null) continue;
                String u = f.optString("url", "");
                if (u.isEmpty()) continue;
                String mime = f.optString("mimeType", "");
                int br = f.optInt("bitrate", 0);
                long clen = parseLongSafe(f.optString("contentLength", "-1"), -1L);
                if (mime.startsWith("audio/mp4") && br > bestAudioBr) {
                    bestAudioBr = br;
                    ms.audioUrl = u;
                    ms.audioContentLength = clen;
                } else if (ms.audioUrl == null && mime.startsWith("audio/") && br > bestAudioBr) {
                    bestAudioBr = br;
                    ms.audioUrl = u;
                    ms.audioContentLength = clen;
                }
            }
        }
        // 3) HLS manifest fallback (iOS client returns hlsManifestUrl which Android MediaPlayer supports directly!)
        String hls = sd.optString("hlsManifestUrl", "");
        if (!hls.isEmpty()) {
            ms.hlsUrl = hls;
            if (ms.videoUrl == null) ms.videoUrl = hls;
            if (ms.audioUrl == null) ms.audioUrl = hls;
        }
        return (ms.audioUrl != null || ms.hlsUrl != null) ? ms : null;
    }

    // ---------------------------------------------------------------- JSON shelf/track extraction
    private static void extractShelves(JSONObject root, Map<String, List<Widgets.Item>> out) {
        List<JSONObject> shelves = new ArrayList<>();
        collectByKey(root, "musicCarouselShelfRenderer", shelves);
        collectByKey(root, "musicShelfRenderer", shelves);
        for (JSONObject shelf : shelves) {
            String title = extractText(shelf.optJSONObject("header"));
            if (title.isEmpty()) title = extractText(shelf.optJSONObject("title"));
            if (title.isEmpty()) title = "YouTube Music";
            List<Widgets.Item> items = new ArrayList<>();
            extractTrackItemsRecursive(shelf, items, 18);
            if (!items.isEmpty()) {
                List<Widgets.Item> existing = out.get(title);
                if (existing == null) out.put(title, items);
                else existing.addAll(items);
            }
        }
    }

    private static void collectByKey(Object node, String key, List<JSONObject> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (o.has(key) && o.optJSONObject(key) != null) out.add(o.optJSONObject(key));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) collectByKey(o.opt(it.next()), key, out);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectByKey(a.opt(i), key, out);
        }
    }

    private static void extractTrackItemsRecursive(Object node, List<Widgets.Item> out, int limit) {
        if (out.size() >= limit || node == null) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject rli = o.optJSONObject("musicResponsiveListItemRenderer");
            if (rli != null) {
                Widgets.Item it = parseListItem(rli);
                if (it != null && !containsId(out, it.id)) out.add(it);
                return;
            }
            JSONObject two = o.optJSONObject("musicTwoRowItemRenderer");
            if (two != null) {
                Widgets.Item it = parseTwoRowItem(two);
                if (it != null && !containsId(out, it.id)) out.add(it);
                return;
            }
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext() && out.size() < limit) extractTrackItemsRecursive(o.opt(it.next()), out, limit);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length() && out.size() < limit; i++) extractTrackItemsRecursive(a.opt(i), out, limit);
        }
    }

    private static boolean containsId(List<Widgets.Item> list, String id) {
        if (id == null) return false;
        for (Widgets.Item x : list) if (id.equals(x.id)) return true;
        return false;
    }

    private static Widgets.Item parseListItem(JSONObject rli) {
        String vid = findVideoId(rli);
        if (vid.isEmpty()) return null;
        JSONArray cols = rli.optJSONArray("flexColumns");
        String title = cols != null && cols.length() > 0 ? extractText(cols.optJSONObject(0)) : "";
        String sub = cols != null && cols.length() > 1 ? extractText(cols.optJSONObject(1)) : "";
        if (title.isEmpty()) return null;
        String artist = cleanSubtitleArtist(sub);
        long durMs = parseDurationFromText(sub);
        String thumb = extractBestThumb(rli, vid);
        return buildItem(vid, title, artist, "", thumb, durMs);
    }

    private static Widgets.Item parseTwoRowItem(JSONObject two) {
        String vid = findVideoId(two);
        if (vid.isEmpty()) return null;
        String title = extractText(two.optJSONObject("title"));
        String sub = extractText(two.optJSONObject("subtitle"));
        if (title.isEmpty()) return null;
        String artist = cleanSubtitleArtist(sub);
        String thumb = extractBestThumb(two, vid);
        return buildItem(vid, title, artist, "", thumb, 0);
    }

    static Widgets.Item buildItem(String videoId, String title, String artist, String album, String thumb, long durationMs) {
        Player.Track tr = new Player.Track();
        tr.id = "ytm:" + videoId;
        tr.uri = "ytm:track:" + videoId;
        tr.name = title == null ? "" : title;
        tr.artist = (artist == null || artist.isEmpty()) ? "YouTube Music" : artist;
        tr.album = album == null ? "" : album;
        tr.art = (thumb == null || thumb.isEmpty()) ? ("https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg") : thumb;
        tr.artSmall = tr.art;
        tr.duration = durationMs > 0 ? durationMs : 210_000L;

        Widgets.Item it = new Widgets.Item();
        it.type = "ytm_track";
        it.id = tr.id;
        it.uri = tr.uri;
        it.title = tr.name;
        it.subtitle = tr.artist;
        it.image = tr.art;
        it.track = tr;
        return it;
    }

    private static String findVideoId(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String v = o.optString("videoId", "");
            if (v.length() == 11) return v;
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String f = findVideoId(o.opt(it.next()));
                if (!f.isEmpty()) return f;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String f = findVideoId(a.opt(i));
                if (!f.isEmpty()) return f;
            }
        }
        return "";
    }

    private static String extractBestThumb(JSONObject o, String vid) {
        List<JSONObject> tList = new ArrayList<>();
        collectByKey(o, "musicThumbnailRenderer", tList);
        String best = "";
        int bestW = 0;
        for (JSONObject t : tList) {
            JSONObject th = t.optJSONObject("thumbnail");
            JSONArray arr = th == null ? null : th.optJSONArray("thumbnails");
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                int w = item.optInt("width", 0);
                String u = item.optString("url", "");
                if (!u.isEmpty() && w >= bestW) { bestW = w; best = u; }
            }
        }
        if (best.isEmpty()) return "https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg";
        // Upscale Google LH3 thumbnails for crisp full-screen artwork
        return best.replaceAll("=w\\d+-h\\d+", "=w544-h544");
    }

    private static String extractText(Object node) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();
        collectRuns(node, sb);
        return sb.toString().trim();
    }

    private static void collectRuns(Object node, StringBuilder sb) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONArray runs = o.optJSONArray("runs");
            if (runs != null) {
                for (int i = 0; i < runs.length(); i++) {
                    JSONObject r = runs.optJSONObject(i);
                    if (r != null) sb.append(r.optString("text", ""));
                }
                return;
            }
            if (o.has("simpleText")) { sb.append(o.optString("simpleText", "")); return; }
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                if ("accessibility".equals(k) || "navigationEndpoint".equals(k) || "menu".equals(k)) continue;
                collectRuns(o.opt(k), sb);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectRuns(a.opt(i), sb);
        }
    }

    private static String cleanSubtitleArtist(String sub) {
        if (sub == null || sub.isEmpty()) return "YouTube Music";
        String[] parts = sub.split("•|·");
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (t.matches("\\d+:\\d+(?::\\d+)?")) continue;
            if (t.equalsIgnoreCase("Song") || t.equalsIgnoreCase("Video") || t.equals("노래") || t.equals("동영상")) continue;
            if (t.contains("views") || t.contains("조회") || t.contains("plays") || t.contains("재생")) continue;
            return t;
        }
        return sub.trim();
    }

    private static long parseDurationFromText(String sub) {
        if (sub == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+):(\\d{2})(?::(\\d{2}))?").matcher(sub);
        if (!m.find()) return 0;
        try {
            if (m.group(3) != null) {
                return (Long.parseLong(m.group(1)) * 3600 + Long.parseLong(m.group(2)) * 60 + Long.parseLong(m.group(3))) * 1000L;
            }
            return (Long.parseLong(m.group(1)) * 60 + Long.parseLong(m.group(2))) * 1000L;
        } catch (Exception e) { return 0; }
    }

    // ---------------------------------------------------------------- Native YTM Audio Engine
    static MediaPlayer mp;
    static final List<Player.Track> queue = new ArrayList<>();
    static final List<Player.Track> original = new ArrayList<>();
    static int index;
    private static int playGen;
    private static AudioManager am;
    private static AudioFocusRequest focus;

    static void play(List<Player.Track> list, int idx, String sourceTitle) {
        if (list == null || list.isEmpty()) return;
        if (!Player.local() && !Player.ytm() && Player.playing && Spotify.loggedIn()) {
            Spotify.send("PUT", "/me/player/pause", null, null);
        }
        LocalMusic.stopForOther();
        Player.playingSourceUri = "ytm:queue";
        Player.playingSourceTitle = (sourceTitle == null || sourceTitle.isEmpty()) ? "YouTube Music" : sourceTitle;
        original.clear();
        original.addAll(list);
        queue.clear();
        queue.addAll(list);
        index = Math.max(0, Math.min(idx, list.size() - 1));
        if (Player.shuffle) shuffleRest();
        start(0);
    }

    private static void shuffleRest() {
        if (queue.isEmpty()) return;
        Player.Track cur = queue.get(index);
        List<Player.Track> rest = new ArrayList<>(queue);
        rest.remove(cur);
        Collections.shuffle(rest, new Random());
        queue.clear();
        queue.add(cur);
        queue.addAll(rest);
        index = 0;
    }

    static void setShuffle(boolean on) {
        if (queue.isEmpty()) return;
        Player.Track cur = queue.get(index);
        if (on) shuffleRest();
        else { queue.clear(); queue.addAll(original); index = Math.max(0, queue.indexOf(cur)); }
    }

    private static void start(long seekTo) {
        if (queue.isEmpty() || index < 0 || index >= queue.size()) return;
        final int gen = ++playGen;
        final Player.Track t = queue.get(index);
        release();
        boolean changed = Player.track == null || !t.id.equals(Player.track.id);
        Player.track = t;
        Player.playing = true;
        Player.progress = seekTo;
        Player.stamp = SystemClock.elapsedRealtime();
        Player.deviceName = app == null ? "YouTube Music" : app.getString(R.string.this_device);
        Player.liked = false;
        if (changed) {
            Recap.recordPlay(app, t);
            for (Runnable r : new ArrayList<>(Player.trackListeners)) r.run();
        }
        Player.notifyAll0();
        if (app != null) LocalMusic.PlaybackService.update(app);

        final String vid = videoIdOf(t.uri);
        IO.execute(() -> {
            MediaStreams ms = resolveStreamsSync(vid);
            if (gen != playGen) return;
            if (ms == null || (ms.audioUrl == null && ms.hlsUrl == null)) {
                Spotify.MAIN.post(() -> {
                    if (gen != playGen) return;
                    Player.playing = false;
                    Player.notifyAll0();
                    Player.toast(R.string.playback_failed);
                });
                return;
            }
            if (ms.durationMs > 0) t.duration = ms.durationMs;
            MV_CACHE.put(t.id, ms);
            Spotify.MAIN.post(() -> {
                if (gen != playGen) return;
                openStream(ms, seekTo, false);
            });
        });
    }

    private static long parseLongSafe(String s, long def) {
        try { return Long.parseLong(s); } catch (Throwable t) { return def; }
    }

    /**
     * Chunked HTTP Range MediaDataSource (API 23+ native Android SDK).
     * YouTube googlevideo CDN returns HTTP 403 to unbounded GETs on IOS adaptiveFormats streams,
     * but serves HTTP 206 Partial Content cleanly for bounded Range: bytes=start-end requests!
     */
    static final class RangeMediaDataSource extends android.media.MediaDataSource {
        private static final int CHUNK = 524288; // 512 KB per Range request (fewer round-trips, smoother streaming)
        private static final int MAX_CACHED_CHUNKS = 28; // 14 MB LRU cache (holds entire typical audio track in RAM)
        private static final ExecutorService PREFETCH_IO = Executors.newFixedThreadPool(2);
        private final String videoId;
        private volatile String url;
        private volatile String ua;
        private volatile long totalSize;
        private volatile boolean closed;
        private final LinkedHashMap<Long, byte[]> cache = new LinkedHashMap<Long, byte[]>(MAX_CACHED_CHUNKS + 2, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
                return size() > MAX_CACHED_CHUNKS;
            }
        };
        private final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.CompletableFuture<byte[]>> inFlight =
                new java.util.concurrent.ConcurrentHashMap<>();

        RangeMediaDataSource(String videoId, String url, long contentLength, String ua) {
            this.videoId = videoId;
            this.url = url;
            this.totalSize = contentLength;
            this.ua = ua != null ? ua : IOS_UA;
            // Immediately warm up the first two chunks asynchronously so initial playback & early seconds never stutter!
            prefetchChunk(0);
            if (contentLength <= 0 || contentLength > CHUNK) prefetchChunk(1);
        }

        private void prefetchChunk(final long chunkIdx) {
            if (closed || chunkIdx < 0) return;
            long total = totalSize;
            if (total > 0 && chunkIdx * CHUNK >= total) return;
            synchronized (cache) {
                if (cache.containsKey(chunkIdx)) return;
            }
            if (inFlight.containsKey(chunkIdx)) return;
            final java.util.concurrent.CompletableFuture<byte[]> fut = new java.util.concurrent.CompletableFuture<>();
            if (inFlight.putIfAbsent(chunkIdx, fut) != null) return;
            PREFETCH_IO.execute(() -> {
                try {
                    if (closed) { fut.complete(null); return; }
                    long start = chunkIdx * CHUNK;
                    long curTotal = totalSize;
                    long end = curTotal > 0 ? Math.min(curTotal - 1, start + CHUNK - 1) : (start + CHUNK - 1);
                    byte[] data = fetchRange(start, end, curTotal <= 0);
                    if (data != null && data.length > 0 && !closed) {
                        synchronized (cache) {
                            if (!closed) cache.put(chunkIdx, data);
                        }
                    }
                    fut.complete(data);
                } catch (Throwable t) {
                    fut.completeExceptionally(t);
                } finally {
                    inFlight.remove(chunkIdx, fut);
                }
            });
        }

        private long ensureSize() throws java.io.IOException {
            if (totalSize > 0) return totalSize;
            byte[] first = loadChunk(0);
            if (first != null && first.length > 0 && totalSize <= 0) totalSize = first.length;
            return totalSize > 0 ? totalSize : -1;
        }

        private byte[] loadChunk(long chunkIdx) throws java.io.IOException {
            if (closed) return null;
            synchronized (cache) {
                byte[] cached = cache.get(chunkIdx);
                if (cached != null) return cached;
            }
            java.util.concurrent.CompletableFuture<byte[]> existing = inFlight.get(chunkIdx);
            if (existing != null) {
                try {
                    byte[] waited = existing.get(16, java.util.concurrent.TimeUnit.SECONDS);
                    if (waited != null && waited.length > 0) return waited;
                } catch (Throwable ignored) {}
            }
            long total = totalSize;
            long start = chunkIdx * CHUNK;
            long end = total > 0 ? Math.min(total - 1, start + CHUNK - 1) : (start + CHUNK - 1);
            byte[] data = fetchRange(start, end, total <= 0);
            if (data != null && data.length > 0 && !closed) {
                synchronized (cache) {
                    if (!closed) cache.put(chunkIdx, data);
                }
            }
            return data;
        }

        @Override public long getSize() throws java.io.IOException {
            return ensureSize();
        }

        @Override public int readAt(long position, byte[] buffer, int offset, int size) throws java.io.IOException {
            if (closed || size <= 0) return 0;
            long total = ensureSize();
            if (total > 0 && position >= total) return -1;
            int want = total > 0 ? (int) Math.min(size, total - position) : size;
            long chunkIdx = position / CHUNK;
            int inChunk = (int) (position - chunkIdx * CHUNK);
            // Proactively prefetch the next 2 chunks ahead of the playback cursor so MediaPlayer NEVER stalls on a chunk boundary!
            prefetchChunk(chunkIdx + 1);
            prefetchChunk(chunkIdx + 2);
            byte[] data = loadChunk(chunkIdx);
            if (data == null || data.length == 0) return -1;
            if (inChunk >= data.length) return -1;
            int n = Math.min(want, data.length - inChunk);
            System.arraycopy(data, inChunk, buffer, offset, n);
            return n;
        }

        private byte[] fetchRange(long start, long end, boolean parseTotal) throws java.io.IOException {
            java.io.IOException lastErr = null;
            boolean refreshedUrl = false;
            for (int attempt = 0; attempt < 4 && !closed; attempt++) {
                if (attempt > 0) {
                    try { Thread.sleep(attempt == 1 ? 120L : (attempt == 2 ? 320L : 650L)); } catch (InterruptedException ignored) { break; }
                }
                HttpURLConnection conn = null;
                try {
                    String curUrl = this.url;
                    String curUa = this.ua;
                    conn = (HttpURLConnection) new URL(curUrl).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("User-Agent", curUa);
                    conn.setRequestProperty("Accept-Encoding", "identity");
                    conn.setRequestProperty("Connection", "keep-alive");
                    conn.setRequestProperty("Range", "bytes=" + start + "-" + end);
                    int code = conn.getResponseCode();
                    if ((code == 403 || code == 410) && !refreshedUrl && videoId != null && !videoId.isEmpty()) {
                        // Signed googlevideo URL expired or throttled mid-track: transparently re-resolve fresh URL!
                        refreshedUrl = true;
                        MediaStreams fresh = resolveStreamsSync(videoId);
                        if (fresh != null && fresh.audioUrl != null && !fresh.audioUrl.isEmpty()) {
                            this.url = fresh.audioUrl;
                            if (fresh.userAgent != null) this.ua = fresh.userAgent;
                            if (fresh.audioContentLength > 0) this.totalSize = fresh.audioContentLength;
                            continue;
                        }
                    }
                    if (code != 206 && code != 200) {
                        throw new java.io.IOException("HTTP " + code);
                    }
                    if (parseTotal || totalSize <= 0) {
                        String cr = conn.getHeaderField("Content-Range");
                        if (cr != null) {
                            int slash = cr.lastIndexOf('/');
                            if (slash > 0) {
                                long parsed = parseLongSafe(cr.substring(slash + 1).trim(), -1L);
                                if (parsed > 0) totalSize = parsed;
                            }
                        } else if (code == 200) {
                            long cl = conn.getContentLengthLong();
                            if (cl > 0) totalSize = cl;
                        }
                    }
                    try (InputStream is = conn.getInputStream();
                         java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream((int) Math.min(CHUNK, Math.max(8192, end - start + 1)))) {
                        byte[] buf = new byte[32768];
                        int r;
                        while (!closed && (r = is.read(buf)) != -1) bos.write(buf, 0, r);
                        byte[] out = bos.toByteArray();
                        if (out.length > 0) return out;
                        throw new java.io.IOException("empty range body");
                    }
                } catch (java.io.IOException e) {
                    lastErr = e;
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
            if (lastErr != null) throw lastErr;
            return null;
        }

        @Override public void close() {
            closed = true;
            inFlight.clear();
            synchronized (cache) { cache.clear(); }
        }
    }

    private static void openStream(MediaStreams ms, long seekTo, boolean useHls) {
        openStream(ms, seekTo, useHls, 0);
    }

    private static void openStream(MediaStreams ms, long seekTo, boolean useHls, int retryCount) {
        release();
        if (app == null || ms == null) return;
        final int gen = playGen;
        boolean playingHls = useHls || (ms.audioUrl == null && ms.hlsUrl != null);
        String url = playingHls ? ms.hlsUrl : ms.audioUrl;
        if (url == null || url.isEmpty()) return;
        mp = new MediaPlayer();
        try { Eq.attach(app, mp.getAudioSessionId()); } catch (Throwable ignored) {}
        try { NpuAudio.attachLocalSession(app, mp.getAudioSessionId()); } catch (Throwable ignored) {}
        applyChannelBalance();
        mp.setWakeMode(app, android.os.PowerManager.PARTIAL_WAKE_LOCK);
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        try {
            if (playingHls) {
                Map<String, String> headers = new java.util.HashMap<>();
                headers.put("User-Agent", ms.userAgent != null ? ms.userAgent : IOS_UA);
                mp.setDataSource(app, android.net.Uri.parse(url), headers);
            } else {
                // Prefetching HTTP Range MediaDataSource prevents CDN 403s AND mid-track stalls/stuttering!
                mp.setDataSource(new RangeMediaDataSource(ms.videoId, url, ms.audioContentLength, ms.userAgent));
            }
        } catch (Exception e) {
            if (!playingHls && ms.hlsUrl != null && !ms.hlsUrl.equals(url)) {
                openStream(ms, seekTo, true, retryCount + 1);
                return;
            }
            Player.playing = false;
            Player.notifyAll0();
            Player.toast(R.string.playback_failed);
            return;
        }
        mp.setOnPreparedListener(p -> {
            if (gen != playGen) return;
            if (Player.track != null && p.getDuration() > 0) Player.track.duration = p.getDuration();
            if (seekTo > 0) p.seekTo((int) seekTo);
            if (requestFocus()) p.start();
            sync();
        });
        mp.setOnCompletionListener(p -> onComplete());
        mp.setOnErrorListener((p, w, x) -> {
            if (gen != playGen) return true;
            long resumePos = Math.max(seekTo, Player.position());
            if (retryCount < 2) {
                if (!playingHls && ms.hlsUrl != null && !ms.hlsUrl.equals(url)) {
                    openStream(ms, resumePos, true, retryCount + 1);
                    return true;
                }
                if (ms.videoId != null && !ms.videoId.isEmpty()) {
                    IO.execute(() -> {
                        MediaStreams fresh = resolveStreamsSync(ms.videoId);
                        if (gen != playGen) return;
                        Spotify.MAIN.post(() -> {
                            if (gen != playGen) return;
                            if (fresh != null) openStream(fresh, resumePos, retryCount >= 1, retryCount + 1);
                            else { Player.playing = false; Player.notifyAll0(); Player.toast(R.string.playback_failed); }
                        });
                    });
                    return true;
                }
            }
            Player.playing = false;
            Player.notifyAll0();
            Player.toast(R.string.playback_failed);
            return true;
        });
        mp.prepareAsync();
    }

    static void applyChannelBalance() {
        if (mp == null || app == null) return;
        float[] lr = BitPerfect.channelGains(app);
        try { mp.setVolume(lr[0], lr[1]); } catch (Throwable ignored) {}
    }

    private static void onComplete() {
        if ("track".equals(Player.repeat)) { start(0); return; }
        if (index + 1 < queue.size()) { index++; start(0); }
        else if ("context".equals(Player.repeat) && !queue.isEmpty()) { index = 0; start(0); }
        else { Player.playing = false; Player.progress = 0; sync(); }
    }

    private static boolean requestFocus() {
        if (app == null) return true;
        if (am == null) am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(ch -> {
                        if (ch == AudioManager.AUDIOFOCUS_LOSS || ch == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            try { if (mp != null && mp.isPlaying()) mp.pause(); } catch (IllegalStateException ignored) {}
                            sync();
                        }
                    }).build();
        }
        return am.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    static void stopForOther() {
        playGen++;
        if (mp == null) return;
        release();
        if (am != null && focus != null) am.abandonAudioFocusRequest(focus);
    }

    static void sync() {
        if (!isYtm(Player.track)) return;
        if (mp != null) {
            try {
                Player.playing = mp.isPlaying();
                Player.progress = mp.getCurrentPosition();
            } catch (IllegalStateException ignored) {}
        }
        Player.stamp = SystemClock.elapsedRealtime();
        Player.notifyAll0();
        if (app != null) LocalMusic.PlaybackService.update(app);
    }

    static void toggle() {
        if (mp == null) { if (!queue.isEmpty()) start(Player.progress); return; }
        try {
            if (mp.isPlaying()) mp.pause();
            else if (requestFocus()) mp.start();
        } catch (IllegalStateException ignored) {}
        sync();
    }

    static void next() {
        if (queue.isEmpty()) return;
        index = (index + 1) % queue.size();
        start(0);
    }

    static void prev() {
        if (queue.isEmpty()) return;
        if (Player.position() > 3000) { seek(0); return; }
        index = (index - 1 + queue.size()) % queue.size();
        start(0);
    }

    static void seek(long ms) {
        try { if (mp != null) mp.seekTo((int) ms); } catch (IllegalStateException ignored) {}
        Player.progress = ms;
        sync();
    }

    static List<Player.Track> upNext() {
        List<Player.Track> l = new ArrayList<>();
        for (int i = index + 1; i < queue.size() && l.size() < 50; i++) l.add(queue.get(i));
        return l;
    }

    static List<Player.Track> fullQueue() {
        return new ArrayList<>(queue);
    }

    static int currentIndex() {
        return index;
    }

    static void jumpTo(int absIndex) {
        if (absIndex < 0 || absIndex >= queue.size()) return;
        index = absIndex;
        start(0);
    }

    static void jumpUpNext(int i) {
        int to = index + 1 + i;
        if (i < 0 || to >= queue.size()) return;
        index = to;
        start(0);
    }

    private static void release() {
        if (mp != null) { try { mp.release(); } catch (Exception ignored) {} mp = null; }
    }
}
