package com.oneuibook.prism;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.MutableContextWrapper;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
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
 *   optional Google Sign-In via standard WebView cookies (SAPISIDHASH) for personal playlists/likes/official Recap.
 * - Playback powered by YouTube's official HTML5 mobile web player inside a hidden Android WebView (same proven architecture
 *   as SpotifyWeb), eliminating the ~1 MiB (~60s) HTTP 403 cutoff on unofficial InnerTube stream URLs while sharing the user's
 *   Google/YouTube Premium session cookies natively.
 */
final class YouTubeMusic {
    private YouTubeMusic() {}

    static final String ORIGIN = "https://music.youtube.com";
    static final String API_BASE = "https://music.youtube.com/youtubei/v1/";
    private static final String PREF_COOKIE = "ytm_cookie";
    private static final String PREF_ACCOUNT = "ytm_account";
    static final String PREF_ADBLOCK = "ytm_adblock";

    static boolean adBlockEnabled(Context c) {
        Context ctx = c != null ? c : app;
        return ctx == null || MainActivity.prefs(ctx).getBoolean(PREF_ADBLOCK, true);
    }

    /** Matches standard ad-network hosts & YouTube ad-only endpoints (never blocks actual playback/player endpoints). */
    static boolean isAdUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) return false;
        String u = rawUrl.toLowerCase(Locale.US);
        if (u.contains("googlevideo.com/videoplayback")) return false;
        if (u.contains("/youtubei/v1/player") || u.contains("/youtubei/v1/browse") || u.contains("/youtubei/v1/search")) return false;
        try {
            android.net.Uri uri = android.net.Uri.parse(u);
            String host = uri.getHost();
            if (host != null) {
                if (host.equals("doubleclick.net") || host.endsWith(".doubleclick.net")
                        || host.equals("googlesyndication.com") || host.endsWith(".googlesyndication.com")
                        || host.equals("googleadservices.com") || host.endsWith(".googleadservices.com")
                        || host.equals("adservice.google.com") || host.endsWith(".adservice.google.com")
                        || host.equals("imasdk.googleapis.com")) {
                    return true;
                }
            }
            String path = uri.getPath();
            if (path != null) {
                if (path.startsWith("/pagead/") || path.startsWith("/api/stats/ads")
                        || path.startsWith("/get_midroll_info") || path.startsWith("/ptracking")
                        || path.startsWith("/pcs/activeview")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    static final ExecutorService IO = Executors.newFixedThreadPool(4);
    static Context app;

    /** Test seam for Robolectric UI tests (returns mock shelves/tracks without network). */
    static java.util.function.Function<String, List<Widgets.Item>> testFixtures;

    static final class OfficialRecap {
        boolean fromOfficial;
        String headline = "YouTube Music 리캡";
        String periodLabel = "전체 기간";
        String statSummary = "";
        final List<Widgets.Item> topTracks = new ArrayList<>();
        final List<Widgets.Item> topArtists = new ArrayList<>();
        final List<Widgets.Item> topAlbums = new ArrayList<>();
        final List<Widgets.Item> playlists = new ArrayList<>();
    }

    static final class DetailData {
        String browseId;
        String type; // ytm_album / ytm_playlist / ytm_artist
        String title = "";
        String subtitle = "";
        String meta = "";
        String image = "";
        String artistBrowseId = "";
        final List<Widgets.Item> tracks = new ArrayList<>();
        final Map<String, List<Widgets.Item>> shelves = new LinkedHashMap<>();
    }

    static final class SearchBundle {
        final List<Widgets.Item> tracks = new ArrayList<>();
        final List<Widgets.Item> artists = new ArrayList<>();
        final List<Widgets.Item> albums = new ArrayList<>();
        final List<Widgets.Item> playlists = new ArrayList<>();
        boolean isEmpty() {
            return tracks.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty();
        }
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
        String v = SecureVault.get(MainActivity.prefs(ctx), PREF_COOKIE);
        return v == null ? "" : v;
    }

    static boolean isTrustedGoogleOrYouTubeUrl(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        try {
            android.net.Uri u = android.net.Uri.parse(url);
            String host = u.getHost();
            if (host == null) return false;
            String h = host.toLowerCase(Locale.ROOT);
            return "youtube.com".equals(h) || h.endsWith(".youtube.com")
                    || "google.com".equals(h) || h.endsWith(".google.com")
                    || "gstatic.com".equals(h) || h.endsWith(".gstatic.com")
                    || "googleapis.com".equals(h) || h.endsWith(".googleapis.com");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void hardenWebSettings(WebSettings ws) {
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setAllowFileAccessFromFileURLs(false);
        ws.setAllowUniversalAccessFromFileURLs(false);
        ws.setSaveFormData(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
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
        Spotify.MAIN.post(YouTubeMusic::stopWeb);
    }

    /** Opens a standard user-driven Google sign-in WebView for YouTube Music library sync. */
    static void showSignInDialog(Activity a, Runnable onDone) {
        if (a == null) return;
        app = a.getApplicationContext();
        FrameLayout box = new FrameLayout(a);
        WebView wv = new WebView(a);
        WebSettings ws = wv.getSettings();
        hardenWebSettings(ws);
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        box.addView(wv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(500)));
        final AlertDialog[] dlg = new AlertDialog[1];
        final boolean[] completed = {false};
        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest req) {
                String u = req != null && req.getUrl() != null ? req.getUrl().toString() : null;
                return !isTrustedGoogleOrYouTubeUrl(u);
            }
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
                        SharedPreferences sp = MainActivity.prefs(a);
                        SecureVault.put(sp, PREF_COOKIE, merged);
                        sp.edit().putString(PREF_ACCOUNT, a.getString(R.string.ytm_signed_in)).apply();
                        stopWeb();
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
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod("POST");
        c.setConnectTimeout(7000);
        c.setReadTimeout(12000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("Origin", ORIGIN);
        c.setRequestProperty("X-Origin", ORIGIN);
        c.setRequestProperty("Referer", ORIGIN + "/");
        c.setRequestProperty("X-YouTube-Client-Name", "67");
        c.setRequestProperty("X-YouTube-Client-Version", "1.20250317.01.00");
        if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
        if (sendAuth && app != null) {
            String ck = cookie(app);
            if (!ck.isEmpty()) {
                c.setRequestProperty("Cookie", ck);
                c.setRequestProperty("X-Goog-AuthUser", "0");
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
                if (res != null) {
                    extractShelves(res, sections);
                    // Follow up to 2 Home continuation tokens so YTM Home is rich like Spotify Home
                    String cont = findContinuationToken(res);
                    for (int page = 0; page < 2 && !cont.isEmpty() && sections.size() < 10; page++) {
                        try {
                            JSONObject creq = new JSONObject().put("context", webRemixContext());
                            JSONObject cres = postJson(API_BASE + "browse?ctoken=" + Spotify.enc(cont)
                                    + "&continuation=" + Spotify.enc(cont) + "&type=next&prettyPrint=false", creq, null, true);
                            if (cres == null) break;
                            extractShelves(cres, sections);
                            String next = findContinuationToken(cres);
                            if (next.equals(cont)) break;
                            cont = next;
                        } catch (Exception ignored) { break; }
                    }
                }
            } catch (Exception ignored) {}
            // Supplement with Explore (New releases, Moods, Charts) when Home has few shelves
            if (sections.size() < 5) {
                try {
                    JSONObject req = new JSONObject()
                            .put("context", webRemixContext())
                            .put("browseId", "FEmusic_explore");
                    JSONObject res = postJson(API_BASE + "browse?prettyPrint=false", req, null, true);
                    if (res != null) extractShelves(res, sections);
                } catch (Exception ignored) {}
            }
            if (sections.isEmpty()) {
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

    private static String findContinuationToken(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject ncd = o.optJSONObject("nextContinuationData");
            if (ncd != null) {
                String c = ncd.optString("continuation", "");
                if (!c.isEmpty()) return c;
            }
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                if ("header".equals(k) || "menu".equals(k)) continue;
                String f = findContinuationToken(o.opt(k));
                if (!f.isEmpty()) return f;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String f = findContinuationToken(a.opt(i));
                if (!f.isEmpty()) return f;
            }
        }
        return "";
    }

    /** Fetches a YouTube Music browse endpoint (Library playlists/albums/artists/liked songs/history or playlist tracks). */
    static void fetchBrowseList(String browseId, Consumer<List<Widgets.Item>> cb) {
        if (testFixtures != null) {
            List<Widgets.Item> list = testFixtures.apply("browse:" + browseId);
            if (list == null || list.isEmpty()) list = testFixtures.apply("charts");
            final List<Widgets.Item> resList = list != null ? list : Collections.emptyList();
            Spotify.MAIN.post(() -> cb.accept(resList));
            return;
        }
        IO.execute(() -> {
            List<Widgets.Item> out = fetchBrowseListSync(browseId, 50);
            Spotify.MAIN.post(() -> cb.accept(out));
        });
    }

    static List<Widgets.Item> fetchBrowseListSync(String browseId, int limit) {
        List<Widgets.Item> out = new ArrayList<>();
        if (browseId == null || browseId.isEmpty()) return out;
        try {
            JSONObject req = new JSONObject()
                    .put("context", webRemixContext())
                    .put("browseId", browseId);
            JSONObject res = postJson(API_BASE + "browse?prettyPrint=false", req, null, true);
            if (res != null) extractAnyItemsRecursive(res, out, limit);
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * Fetches official YouTube Music Recap via authenticated InnerTube endpoints:
     * 1) FEmusic_listening_review (official seasonal/yearly Recap page)
     * 2) FEmusic_liked_playlists (official generated Recap playlists)
     * 3) FEmusic_history (official server-side listening history for top songs/artists)
     */
    static void fetchOfficialRecap(Consumer<OfficialRecap> cb) {
        if (testFixtures != null) {
            OfficialRecap r = new OfficialRecap();
            List<Widgets.Item> fix = testFixtures.apply("recap_official");
            if (fix != null && !fix.isEmpty()) {
                r.fromOfficial = true;
                r.headline = "YouTube Music 공식 리캡";
                r.statSummary = fix.size() + "곡 · 공식 기록";
                r.topTracks.addAll(fix);
            }
            Spotify.MAIN.post(() -> cb.accept(r));
            return;
        }
        IO.execute(() -> {
            OfficialRecap out = new OfficialRecap();
            if (!signedIn(app)) {
                Spotify.MAIN.post(() -> cb.accept(out));
                return;
            }
            try {
                // 1) Official YouTube Music Listening Review (Recap)
                JSONObject req = new JSONObject().put("context", webRemixContext()).put("browseId", "FEmusic_listening_review");
                JSONObject res = postJson(API_BASE + "browse?prettyPrint=false", req, null, true);
                if (res != null) {
                    Map<String, List<Widgets.Item>> shelves = new LinkedHashMap<>();
                    extractShelvesAny(res, shelves);
                    for (Map.Entry<String, List<Widgets.Item>> e : shelves.entrySet()) {
                        String k = e.getKey().toLowerCase(Locale.ROOT);
                        for (Widgets.Item it : e.getValue()) {
                            if ("ytm_playlist".equals(it.type)) {
                                if (!containsId(out.playlists, it.id)) out.playlists.add(it);
                            } else if ("ytm_artist".equals(it.type) || k.contains("artist") || k.contains("아티스트")) {
                                if (!containsId(out.topArtists, it.id)) out.topArtists.add(it);
                            } else if ("ytm_album".equals(it.type) || k.contains("album") || k.contains("앨범")) {
                                if (!containsId(out.topAlbums, it.id)) out.topAlbums.add(it);
                            } else if (it.track != null) {
                                if (!containsId(out.topTracks, it.id)) out.topTracks.add(it);
                            }
                        }
                    }
                    if (!out.topTracks.isEmpty() || !out.topArtists.isEmpty() || !out.playlists.isEmpty()) {
                        out.fromOfficial = true;
                        out.headline = "YouTube Music 공식 리캡";
                    }
                }
            } catch (Exception ignored) {}

            // 2) Official Recap playlists inside user's playlists (e.g. "2026 Recap", "2025 Recap", "가을 리캡")
            try {
                List<Widgets.Item> pls = fetchBrowseListSync("FEmusic_liked_playlists", 40);
                for (Widgets.Item pl : pls) {
                    String tl = pl.title == null ? "" : pl.title.toLowerCase(Locale.ROOT);
                    if (tl.contains("recap") || tl.contains("리캡")) {
                        if (!containsId(out.playlists, pl.id)) out.playlists.add(pl);
                    }
                }
                if (out.topTracks.isEmpty() && !out.playlists.isEmpty()) {
                    Widgets.Item firstRecap = out.playlists.get(0);
                    String bid = firstRecap.uri != null && firstRecap.uri.startsWith("ytm:browse:")
                            ? firstRecap.uri.substring(11) : firstRecap.id;
                    List<Widgets.Item> tracks = fetchBrowseListSync(bid, 30);
                    for (Widgets.Item tr : tracks) {
                        if (tr.track != null && !containsId(out.topTracks, tr.id)) out.topTracks.add(tr);
                    }
                    if (!out.topTracks.isEmpty()) {
                        out.fromOfficial = true;
                        out.headline = firstRecap.title;
                    }
                }
            } catch (Exception ignored) {}

            // 3) Supplement with official server-side YouTube Music History (FEmusic_history)
            if (out.topTracks.isEmpty()) {
                try {
                    List<Widgets.Item> hist = fetchBrowseListSync("FEmusic_history", 40);
                    for (Widgets.Item tr : hist) {
                        if (tr.track != null && !containsId(out.topTracks, tr.id)) out.topTracks.add(tr);
                    }
                    if (!out.topTracks.isEmpty()) {
                        out.fromOfficial = true;
                        out.headline = "YouTube Music 공식 감상 기록";
                    }
                } catch (Exception ignored) {}
            }

            // Synthesize top artists from official topTracks if artist shelf wasn't provided separately
            if (out.topArtists.isEmpty() && !out.topTracks.isEmpty()) {
                Map<String, Widgets.Item> arMap = new LinkedHashMap<>();
                for (Widgets.Item tr : out.topTracks) {
                    if (tr.track == null || tr.track.artist == null || tr.track.artist.isEmpty() || "YouTube Music".equals(tr.track.artist)) continue;
                    String an = tr.track.artist;
                    if (!arMap.containsKey(an)) {
                        Widgets.Item ai = new Widgets.Item();
                        ai.type = "artist";
                        ai.id = "ytm_ar:" + an;
                        ai.title = an;
                        ai.subtitle = "YouTube Music";
                        ai.image = tr.image;
                        arMap.put(an, ai);
                    }
                    if (arMap.size() >= 10) break;
                }
                out.topArtists.addAll(arMap.values());
            }
            if (out.fromOfficial) {
                out.statSummary = out.topTracks.size() + "곡 · 공식 YouTube Music";
            }
            Spotify.MAIN.post(() -> cb.accept(out));
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
                if (res != null) extractAnyItemsRecursive(res, out, 30);
            } catch (Exception ignored) {}
            Spotify.MAIN.post(() -> cb.accept(out));
        });
    }

    /** Full YouTube Music search separated into songs, artists, albums, and playlists. */
    static void searchAll(String query, Consumer<SearchBundle> cb) {
        if (query == null || query.trim().isEmpty()) {
            Spotify.MAIN.post(() -> cb.accept(new SearchBundle()));
            return;
        }
        if (testFixtures != null) {
            SearchBundle b = new SearchBundle();
            List<Widgets.Item> list = testFixtures.apply("search:" + query.trim());
            if (list != null) {
                for (Widgets.Item it : list) classifyIntoBundle(it, b);
            }
            Spotify.MAIN.post(() -> cb.accept(b));
            return;
        }
        IO.execute(() -> {
            SearchBundle b = new SearchBundle();
            try {
                JSONObject req = new JSONObject()
                        .put("context", webRemixContext())
                        .put("query", query.trim());
                JSONObject res = postJson(API_BASE + "search?prettyPrint=false", req, null, false);
                if (res != null) {
                    List<Widgets.Item> all = new ArrayList<>();
                    extractAnyItemsRecursive(res, all, 45);
                    for (Widgets.Item it : all) classifyIntoBundle(it, b);
                }
            } catch (Exception ignored) {}
            Spotify.MAIN.post(() -> cb.accept(b));
        });
    }

    private static void classifyIntoBundle(Widgets.Item it, SearchBundle b) {
        if (it == null) return;
        if (it.track != null || "ytm_track".equals(it.type)) {
            if (!containsId(b.tracks, it.id)) b.tracks.add(it);
        } else if ("ytm_artist".equals(it.type)) {
            if (!containsId(b.artists, it.id)) b.artists.add(it);
        } else if ("ytm_album".equals(it.type)) {
            if (!containsId(b.albums, it.id)) b.albums.add(it);
        } else if ("ytm_playlist".equals(it.type)) {
            if (!containsId(b.playlists, it.id)) b.playlists.add(it);
        }
    }

    /** Resolves an artist's public YouTube Music channel browseId (UC...) by name when a track lacks artistUri. */
    static void resolveArtistBrowseId(String artistName, Consumer<String> cb) {
        if (artistName == null || artistName.trim().isEmpty()) {
            Spotify.MAIN.post(() -> cb.accept(""));
            return;
        }
        final String clean = artistName.split(",|&|feat\\.|ft\\.")[0].trim();
        if (testFixtures != null) {
            List<Widgets.Item> list = testFixtures.apply("search:" + clean);
            String found = "";
            if (list != null) {
                for (Widgets.Item it : list) {
                    if ("ytm_artist".equals(it.type) && it.id != null && !it.id.isEmpty()) { found = it.id; break; }
                }
            }
            final String out = found;
            Spotify.MAIN.post(() -> cb.accept(out));
            return;
        }
        IO.execute(() -> {
            String found = "";
            try {
                JSONObject req = new JSONObject()
                        .put("context", webRemixContext())
                        .put("query", clean)
                        .put("params", "EgWKAQIgAWoKEAMQBBAJEAoQBQ%3D%3D");
                JSONObject res = postJson(API_BASE + "search?prettyPrint=false", req, null, false);
                if (res == null) {
                    JSONObject req2 = new JSONObject().put("context", webRemixContext()).put("query", clean);
                    res = postJson(API_BASE + "search?prettyPrint=false", req2, null, false);
                }
                if (res != null) {
                    List<Widgets.Item> all = new ArrayList<>();
                    extractAnyItemsRecursive(res, all, 20);
                    for (Widgets.Item it : all) {
                        if ("ytm_artist".equals(it.type) && it.id != null && !it.id.isEmpty()) {
                            found = normalizeArtistBrowseId(it.id);
                            break;
                        }
                    }
                    if (found.isEmpty()) {
                        for (Widgets.Item it : all) {
                            if (it.track != null && it.track.artistUri != null && it.track.artistUri.startsWith("ytm:browse:")) {
                                found = normalizeArtistBrowseId(it.track.artistUri.substring(11));
                                break;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
            final String out = found;
            Spotify.MAIN.post(() -> cb.accept(out));
        });
    }

    /** Strips library-only MPLA prefix (MPLAUC...) so artist pages load the FULL public channel (albums, singles, related). */
    static String normalizeArtistBrowseId(String bid) {
        if (bid == null) return "";
        if (bid.startsWith("MPLA") && bid.length() > 6) return bid.substring(4);
        return bid;
    }

    /** Fetches structured album / playlist / artist details (header art, artist, tracklist, related shelves). */
    static void fetchDetail(String rawBrowseId, String fallbackTitle, String fallbackSubtitle, String fallbackImage, String fallbackType, Consumer<DetailData> cb) {
        if (rawBrowseId == null || rawBrowseId.isEmpty()) {
            Spotify.MAIN.post(() -> cb.accept(null));
            return;
        }
        final String resolvedType = fallbackType != null ? fallbackType : typeFromBrowseId(rawBrowseId, null);
        final String browseId = "ytm_artist".equals(resolvedType) ? normalizeArtistBrowseId(rawBrowseId) : rawBrowseId;
        if (testFixtures != null) {
            DetailData d = new DetailData();
            d.browseId = browseId;
            d.type = resolvedType;
            d.title = fallbackTitle != null ? fallbackTitle : "";
            d.subtitle = fallbackSubtitle != null ? fallbackSubtitle : "";
            d.image = fallbackImage != null ? fallbackImage : "";
            List<Widgets.Item> list = testFixtures.apply("browse:" + browseId);
            if (list == null || list.isEmpty()) list = testFixtures.apply("charts");
            if (list != null) {
                for (Widgets.Item it : list) {
                    if (it.track != null) d.tracks.add(it);
                    else {
                        List<Widgets.Item> alb = d.shelves.computeIfAbsent("앨범", k -> new ArrayList<>());
                        alb.add(it);
                    }
                }
            }
            Spotify.MAIN.post(() -> cb.accept(d));
            return;
        }
        IO.execute(() -> {
            DetailData d = new DetailData();
            d.browseId = browseId;
            d.type = resolvedType;
            d.title = fallbackTitle != null ? fallbackTitle : "";
            d.subtitle = fallbackSubtitle != null ? fallbackSubtitle : "";
            d.image = fallbackImage != null ? fallbackImage : "";
            try {
                JSONObject req = new JSONObject()
                        .put("context", webRemixContext())
                        .put("browseId", browseId);
                JSONObject res = postJson(API_BASE + "browse?prettyPrint=false", req, null, true);
                if (res != null) {
                    parseDetailResponse(res, d);
                }
                // If artist page came back without albums/singles shelves (e.g. topic channel), supplement via YTM search!
                if ("ytm_artist".equals(d.type) && d.shelves.isEmpty() && d.title != null && !d.title.isEmpty()) {
                    try {
                        JSONObject sreq = new JSONObject().put("context", webRemixContext()).put("query", d.title);
                        JSONObject sres = postJson(API_BASE + "search?prettyPrint=false", sreq, null, false);
                        if (sres != null) {
                            List<Widgets.Item> all = new ArrayList<>();
                            extractAnyItemsRecursive(sres, all, 40);
                            List<Widgets.Item> albs = new ArrayList<>();
                            List<Widgets.Item> pls = new ArrayList<>();
                            for (Widgets.Item it : all) {
                                if ("ytm_album".equals(it.type) && !containsId(albs, it.id)) albs.add(it);
                                else if ("ytm_playlist".equals(it.type) && !containsId(pls, it.id)) pls.add(it);
                            }
                            if (!albs.isEmpty()) d.shelves.put("앨범", albs);
                            if (!pls.isEmpty()) d.shelves.put("재생목록", pls);
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            Spotify.MAIN.post(() -> cb.accept(d));
        });
    }

    static void parseDetailResponse(JSONObject res, DetailData d) {
        if (res == null || d == null) return;
        // 1) Header metadata (include musicVisualHeaderRenderer used by YTM Artist pages!)
        List<JSONObject> hdrs = new ArrayList<>();
        collectByKey(res, "musicResponsiveHeaderRenderer", hdrs);
        if (hdrs.isEmpty()) collectByKey(res, "musicDetailHeaderRenderer", hdrs);
        if (hdrs.isEmpty()) collectByKey(res, "musicImmersiveHeaderRenderer", hdrs);
        if (hdrs.isEmpty()) collectByKey(res, "musicVisualHeaderRenderer", hdrs);
        String headerArtist = "";
        if (!hdrs.isEmpty()) {
            JSONObject h = hdrs.get(0);
            String ht = extractText(h.optJSONObject("title"));
            if (!ht.isEmpty()) d.title = ht;
            String hs = extractText(h.optJSONObject("subtitle"));
            String strap = extractText(h.optJSONObject("straplineTextOne"));
            // Extract artist browseId from header strapline/subtitle so album tracks inherit clickable artistUri
            String foundArBid = extractArtistBrowseIdFromRuns(h.optJSONObject("straplineTextOne"));
            if (foundArBid.isEmpty()) foundArBid = extractArtistBrowseIdFromRuns(h.optJSONObject("subtitle"));
            if (!foundArBid.isEmpty()) d.artistBrowseId = normalizeArtistBrowseId(foundArBid);
            if (!strap.isEmpty()) {
                headerArtist = cleanSubtitleArtist(strap);
                d.subtitle = hs.isEmpty() ? strap : (strap + " · " + hs);
            } else if (!hs.isEmpty()) {
                d.subtitle = hs;
                headerArtist = cleanSubtitleArtist(hs);
            }
            String sec = extractText(h.optJSONObject("secondSubtitle"));
            if (sec.isEmpty()) sec = extractText(h.optJSONObject("description"));
            if (!sec.isEmpty()) d.meta = sec;
            // Strictly read the primary header "thumbnail" first so straplineThumbnail (artist avatar) NEVER overwrites album cover!
            String img = extractDirectThumb(h.optJSONObject("thumbnail"));
            if (img.isEmpty() && "ytm_artist".equals(d.type)) {
                img = extractDirectThumb(h.optJSONObject("foregroundThumbnail"));
            }
            if (img.isEmpty()) img = extractBestThumb(h, "");
            if (!img.isEmpty() && !img.endsWith("/hqdefault.jpg")) d.image = img;
        }
        if ("ytm_artist".equals(d.type) && d.artistBrowseId.isEmpty() && d.browseId != null) {
            d.artistBrowseId = normalizeArtistBrowseId(d.browseId);
        }
        if (headerArtist.isEmpty() || "YouTube Music".equals(headerArtist)) {
            if ("ytm_artist".equals(d.type)) headerArtist = d.title;
            else headerArtist = cleanSubtitleArtist(d.subtitle);
        }

        // 2) Extract shelves & tracks
        Map<String, List<Widgets.Item>> rawShelves = new LinkedHashMap<>();
        extractShelvesAny(res, rawShelves);
        boolean isArtist = "ytm_artist".equals(d.type);
        for (Map.Entry<String, List<Widgets.Item>> e : rawShelves.entrySet()) {
            List<Widgets.Item> tracksInShelf = new ArrayList<>();
            List<Widgets.Item> entitiesInShelf = new ArrayList<>();
            for (Widgets.Item it : e.getValue()) {
                if (it.track != null) tracksInShelf.add(it);
                else entitiesInShelf.add(it);
            }
            if (!entitiesInShelf.isEmpty()) {
                List<Widgets.Item> bucket = d.shelves.get(e.getKey());
                if (bucket == null) d.shelves.put(e.getKey(), entitiesInShelf);
                else for (Widgets.Item x : entitiesInShelf) if (!containsId(bucket, x.id)) bucket.add(x);
            }
            if (!tracksInShelf.isEmpty()) {
                if (d.tracks.isEmpty() || !isArtist) {
                    for (Widgets.Item tr : tracksInShelf) {
                        decorateAlbumTrack(tr, d, headerArtist);
                        if (!containsId(d.tracks, tr.id)) d.tracks.add(tr);
                    }
                } else {
                    // Secondary track/video shelves on Artist page (e.g. Videos / Live performances)
                    for (Widgets.Item tr : tracksInShelf) decorateAlbumTrack(tr, d, headerArtist);
                    d.shelves.put(e.getKey(), tracksInShelf);
                }
            }
        }
        // Fallback: album pages sometimes place tracks directly in musicShelfRenderer without shelf headers
        if (d.tracks.isEmpty()) {
            List<Widgets.Item> all = new ArrayList<>();
            extractAnyItemsRecursive(res, all, 100);
            for (Widgets.Item it : all) {
                if (it.track != null) {
                    decorateAlbumTrack(it, d, headerArtist);
                    if (!containsId(d.tracks, it.id)) d.tracks.add(it);
                }
            }
        }
    }

    private static String extractArtistBrowseIdFromRuns(JSONObject textNode) {
        if (textNode == null) return "";
        JSONArray runs = textNode.optJSONArray("runs");
        if (runs == null) return "";
        for (int i = 0; i < runs.length(); i++) {
            JSONObject r = runs.optJSONObject(i);
            if (r == null) continue;
            JSONObject nav = r.optJSONObject("navigationEndpoint");
            JSONObject be = nav == null ? null : nav.optJSONObject("browseEndpoint");
            if (be == null) continue;
            String bid = be.optString("browseId", "");
            String pt = pageTypeOf(be);
            if ("MUSIC_PAGE_TYPE_ARTIST".equals(pt) || bid.startsWith("UC") || bid.startsWith("MPLA")) {
                return bid;
            }
        }
        return "";
    }

    private static void decorateAlbumTrack(Widgets.Item it, DetailData d, String headerArtist) {
        if (it == null || it.track == null) return;
        Player.Track t = it.track;
        if ((t.artist == null || t.artist.isEmpty() || "YouTube Music".equals(t.artist))
                && headerArtist != null && !headerArtist.isEmpty() && !"YouTube Music".equals(headerArtist)) {
            t.artist = headerArtist;
            it.subtitle = headerArtist;
        }
        if ((t.artistUri == null || t.artistUri.isEmpty()) && d.artistBrowseId != null && !d.artistBrowseId.isEmpty()) {
            t.artistUri = "ytm:browse:" + d.artistBrowseId;
        }
        if ("ytm_album".equals(d.type)) {
            if (t.album == null || t.album.isEmpty()) t.album = d.title;
            if (t.albumUri == null || t.albumUri.isEmpty()) t.albumUri = "ytm:browse:" + d.browseId;
            if (d.image != null && !d.image.isEmpty()
                    && (t.art == null || t.art.isEmpty() || t.art.contains("i.ytimg.com/vi/"))) {
                t.art = d.image;
                t.artSmall = d.image;
                it.image = d.image;
            }
        }
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

    // ---------------------------------------------------------------- JSON shelf/track extraction
    private static void extractShelves(JSONObject root, Map<String, List<Widgets.Item>> out) {
        extractShelvesAny(root, out);
    }

    /** Extracts ONLY the shelf's clean title (ignoring strapline account name and moreContentButton "더보기"/"모두 재생"). */
    static String extractShelfTitle(JSONObject shelf) {
        if (shelf == null) return "";
        JSONObject hdr = shelf.optJSONObject("header");
        String title = "";
        if (hdr != null) {
            JSONObject basic = hdr.optJSONObject("musicCarouselShelfBasicHeaderRenderer");
            if (basic != null) title = extractText(basic.optJSONObject("title"));
            if (title.isEmpty()) {
                JSONObject gridH = hdr.optJSONObject("gridHeaderRenderer");
                if (gridH != null) title = extractText(gridH.optJSONObject("title"));
            }
            if (title.isEmpty()) title = extractText(hdr.optJSONObject("title"));
        }
        if (title.isEmpty()) title = extractText(shelf.optJSONObject("title"));
        title = title.replaceAll("\\s*(더보기|모두 재생|모두 보기|More|Play all|See all)$", "").trim();
        return title;
    }

    private static void extractShelvesAny(JSONObject root, Map<String, List<Widgets.Item>> out) {
        List<JSONObject> shelves = new ArrayList<>();
        collectByKey(root, "musicCarouselShelfRenderer", shelves);
        collectByKey(root, "musicShelfRenderer", shelves);
        collectByKey(root, "gridRenderer", shelves);
        for (JSONObject shelf : shelves) {
            String title = extractShelfTitle(shelf);
            if (title.isEmpty()) title = "YouTube Music";
            List<Widgets.Item> items = new ArrayList<>();
            extractAnyItemsRecursive(shelf, items, 25);
            if (!items.isEmpty()) {
                List<Widgets.Item> existing = out.get(title);
                if (existing == null) out.put(title, items);
                else for (Widgets.Item x : items) if (!containsId(existing, x.id)) existing.add(x);
            }
        }
    }

    private static void extractAnyItemsRecursive(Object node, List<Widgets.Item> out, int limit) {
        if (out.size() >= limit || node == null) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject rli = o.optJSONObject("musicResponsiveListItemRenderer");
            if (rli != null) {
                Widgets.Item it = parseListItemAny(rli);
                if (it != null && !containsId(out, it.id)) out.add(it);
                return;
            }
            JSONObject two = o.optJSONObject("musicTwoRowItemRenderer");
            if (two != null) {
                Widgets.Item it = parseTwoRowItemAny(two);
                if (it != null && !containsId(out, it.id)) out.add(it);
                return;
            }
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext() && out.size() < limit) extractAnyItemsRecursive(o.opt(it.next()), out, limit);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length() && out.size() < limit; i++) extractAnyItemsRecursive(a.opt(i), out, limit);
        }
    }

    /** Extracts browseEndpoint's pageType (e.g. MUSIC_PAGE_TYPE_ALBUM / ARTIST / PLAYLIST). */
    private static String pageTypeOf(JSONObject browseEndpoint) {
        if (browseEndpoint == null) return "";
        JSONObject cfg = browseEndpoint.optJSONObject("browseEndpointContextSupportedConfigs");
        JSONObject mc = cfg == null ? null : cfg.optJSONObject("browseEndpointContextMusicConfig");
        return mc == null ? "" : mc.optString("pageType", "");
    }

    private static String typeFromBrowseId(String browseId, String pageType) {
        if (pageType != null && !pageType.isEmpty()) {
            if ("MUSIC_PAGE_TYPE_ALBUM".equals(pageType)) return "ytm_album";
            if ("MUSIC_PAGE_TYPE_ARTIST".equals(pageType) || "MUSIC_PAGE_TYPE_USER_CHANNEL".equals(pageType)) return "ytm_artist";
            if ("MUSIC_PAGE_TYPE_PLAYLIST".equals(pageType)) return "ytm_playlist";
        }
        if (browseId == null) return "ytm_playlist";
        if (browseId.startsWith("MPREb_") || browseId.startsWith("OLAK")) return "ytm_album";
        if (browseId.startsWith("UC") || browseId.startsWith("MPLA")) return "ytm_artist";
        return "ytm_playlist";
    }

    private static boolean isPlayableTrackPageType(String pageType, String browseId) {
        if ("MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE".equals(pageType)
                || "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE".equals(pageType)) {
            return true;
        }
        return browseId != null && browseId.startsWith("MPED");
    }

    /**
     * Parses a musicResponsiveListItemRenderer with browseEndpoint checked FIRST so albums, playlists, and artists
     * never get misclassified as tracks when they contain a quick-play overlay or menu videoId.
     */
    static Widgets.Item parseListItemAny(JSONObject rli) {
        if (rli == null) return null;
        JSONObject nav = rli.optJSONObject("navigationEndpoint");
        JSONObject be = nav == null ? null : nav.optJSONObject("browseEndpoint");
        JSONArray cols = rli.optJSONArray("flexColumns");
        if (be == null && cols != null && cols.length() > 0) {
            JSONObject c0Nav = firstRunNavEndpoint(cols.optJSONObject(0));
            if (c0Nav != null) be = c0Nav.optJSONObject("browseEndpoint");
        }
        if (be != null) {
            String bid = be.optString("browseId", "");
            String pt = pageTypeOf(be);
            if (!bid.isEmpty() && !bid.startsWith("FE") && !isPlayableTrackPageType(pt, bid)) {
                String title = cols != null && cols.length() > 0 ? extractText(cols.optJSONObject(0)) : "";
                String sub = cols != null && cols.length() > 1 ? extractText(cols.optJSONObject(1)) : "";
                if (!title.isEmpty()) {
                    return buildBrowseItem(bid, pt, title, sub, extractBestThumb(rli, ""));
                }
            }
        }
        Widgets.Item tr = parseListItem(rli);
        if (tr != null) return tr;
        String bid = findBrowseId(rli);
        if (bid.isEmpty()) return null;
        String title = cols != null && cols.length() > 0 ? extractText(cols.optJSONObject(0)) : "";
        String sub = cols != null && cols.length() > 1 ? extractText(cols.optJSONObject(1)) : "";
        if (title.isEmpty()) return null;
        return buildBrowseItem(bid, "", title, sub, extractBestThumb(rli, ""));
    }

    /**
     * Parses a musicTwoRowItemRenderer (Home/Explore/Recap/Library cards) checking its primary navigationEndpoint
     * FIRST so album/playlist/artist cards open their detail screen instead of playing an embedded preview videoId!
     */
    static Widgets.Item parseTwoRowItemAny(JSONObject two) {
        if (two == null) return null;
        JSONObject nav = two.optJSONObject("navigationEndpoint");
        JSONObject be = nav == null ? null : nav.optJSONObject("browseEndpoint");
        if (be == null) {
            JSONObject tNav = firstRunNavEndpoint(two.optJSONObject("title"));
            if (tNav != null) be = tNav.optJSONObject("browseEndpoint");
        }
        if (be != null) {
            String bid = be.optString("browseId", "");
            String pt = pageTypeOf(be);
            if (!bid.isEmpty() && !bid.startsWith("FE") && !isPlayableTrackPageType(pt, bid)) {
                String title = extractText(two.optJSONObject("title"));
                String sub = extractText(two.optJSONObject("subtitle"));
                if (!title.isEmpty()) {
                    return buildBrowseItem(bid, pt, title, sub, extractBestThumb(two, ""));
                }
            }
        }
        Widgets.Item tr = parseTwoRowItem(two);
        if (tr != null) return tr;
        String bid = findBrowseId(two);
        if (bid.isEmpty()) return null;
        String title = extractText(two.optJSONObject("title"));
        String sub = extractText(two.optJSONObject("subtitle"));
        if (title.isEmpty()) return null;
        return buildBrowseItem(bid, "", title, sub, extractBestThumb(two, ""));
    }

    private static JSONObject firstRunNavEndpoint(JSONObject node) {
        if (node == null) return null;
        JSONObject textObj = node.optJSONObject("text");
        if (textObj == null) {
            JSONObject fcr = node.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
            if (fcr != null) textObj = fcr.optJSONObject("text");
        }
        if (textObj == null) textObj = node;
        JSONArray runs = textObj.optJSONArray("runs");
        if (runs == null || runs.length() == 0) return null;
        JSONObject r0 = runs.optJSONObject(0);
        return r0 == null ? null : r0.optJSONObject("navigationEndpoint");
    }

    private static Widgets.Item buildBrowseItem(String browseId, String pageType, String title, String subtitle, String thumb) {
        Widgets.Item it = new Widgets.Item();
        it.type = typeFromBrowseId(browseId, pageType);
        it.id = browseId;
        it.uri = "ytm:browse:" + browseId;
        it.title = title;
        it.subtitle = subtitle == null ? "" : subtitle;
        it.image = thumb;
        return it;
    }

    private static String findBrowseId(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String b = o.optString("browseId", "");
            if (b.length() >= 4 && !b.startsWith("FE")) return b;
            if (b.equals("VLLM") || b.startsWith("VL")) return b;
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                if ("menu".equals(k)) continue;
                String f = findBrowseId(o.opt(k));
                if (!f.isEmpty()) return f;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String f = findBrowseId(a.opt(i));
                if (!f.isEmpty()) return f;
            }
        }
        return "";
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

    private static boolean containsId(List<Widgets.Item> list, String id) {
        if (id == null) return false;
        for (Widgets.Item x : list) if (id.equals(x.id)) return true;
        return false;
    }

    /** Extracts linked artist & album browseIds/names from track subtitle runs so "Go to album/artist" works on YTM. */
    private static void enrichTrackLinks(Object node, Player.Track tr) {
        if (node == null || tr == null) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONArray runs = o.optJSONArray("runs");
            if (runs != null) {
                for (int i = 0; i < runs.length(); i++) {
                    JSONObject r = runs.optJSONObject(i);
                    if (r == null) continue;
                    JSONObject nav = r.optJSONObject("navigationEndpoint");
                    JSONObject be = nav == null ? null : nav.optJSONObject("browseEndpoint");
                    if (be == null) continue;
                    String bid = be.optString("browseId", "");
                    String pt = pageTypeOf(be);
                    String txt = r.optString("text", "").trim();
                    if (bid.isEmpty() || txt.isEmpty()) continue;
                    if ("MUSIC_PAGE_TYPE_ALBUM".equals(pt) || bid.startsWith("MPREb_")) {
                        if (tr.albumUri == null || tr.albumUri.isEmpty()) {
                            tr.albumUri = "ytm:browse:" + bid;
                            if (tr.album == null || tr.album.isEmpty()) tr.album = txt;
                        }
                    } else if ("MUSIC_PAGE_TYPE_ARTIST".equals(pt) || bid.startsWith("UC") || bid.startsWith("MPLA")) {
                        if (tr.artistUri == null || tr.artistUri.isEmpty()) {
                            tr.artistUri = "ytm:browse:" + normalizeArtistBrowseId(bid);
                        }
                    }
                }
                return;
            }
            // Also check menuNavigationItemRenderer (e.g., "Go to artist" / "Go to album" in YTM track menu)
            JSONObject mni = o.optJSONObject("menuNavigationItemRenderer");
            if (mni != null) {
                JSONObject nav = mni.optJSONObject("navigationEndpoint");
                JSONObject be = nav == null ? null : nav.optJSONObject("browseEndpoint");
                if (be != null) {
                    String bid = be.optString("browseId", "");
                    String pt = pageTypeOf(be);
                    if (("MUSIC_PAGE_TYPE_ALBUM".equals(pt) || bid.startsWith("MPREb_"))
                            && (tr.albumUri == null || tr.albumUri.isEmpty())) {
                        tr.albumUri = "ytm:browse:" + bid;
                    } else if (("MUSIC_PAGE_TYPE_ARTIST".equals(pt) || bid.startsWith("UC") || bid.startsWith("MPLA"))
                            && (tr.artistUri == null || tr.artistUri.isEmpty())) {
                        tr.artistUri = "ytm:browse:" + normalizeArtistBrowseId(bid);
                    }
                }
                return;
            }
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                if ("overlay".equals(k) || "thumbnailOverlay".equals(k)) continue;
                enrichTrackLinks(o.opt(k), tr);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) enrichTrackLinks(a.opt(i), tr);
        }
    }

    private static Widgets.Item parseListItem(JSONObject rli) {
        String vid = "";
        JSONObject pid = rli.optJSONObject("playlistItemData");
        if (pid != null) vid = pid.optString("videoId", "");
        JSONArray cols = rli.optJSONArray("flexColumns");
        if (vid.isEmpty() && cols != null && cols.length() > 0) {
            JSONObject c0Nav = firstRunNavEndpoint(cols.optJSONObject(0));
            JSONObject we = c0Nav == null ? null : c0Nav.optJSONObject("watchEndpoint");
            if (we != null) vid = we.optString("videoId", "");
        }
        if (vid.isEmpty()) vid = findVideoId(rli);
        if (vid.isEmpty()) return null;
        String title = cols != null && cols.length() > 0 ? extractText(cols.optJSONObject(0)) : "";
        String sub = cols != null && cols.length() > 1 ? extractText(cols.optJSONObject(1)) : "";
        if (title.isEmpty()) return null;
        String artist = cleanSubtitleArtist(sub);
        long durMs = parseDurationFromText(sub);
        if (durMs <= 0) {
            String fixed = extractText(rli.optJSONArray("fixedColumns"));
            durMs = parseDurationFromText(fixed);
        }
        String thumb = extractBestThumb(rli, vid);
        Widgets.Item it = buildItem(vid, title, artist, "", thumb, durMs);
        enrichTrackLinks(cols, it.track);
        enrichTrackLinks(rli.optJSONObject("menu"), it.track);
        return it;
    }

    private static Widgets.Item parseTwoRowItem(JSONObject two) {
        String vid = "";
        JSONObject nav = two.optJSONObject("navigationEndpoint");
        JSONObject we = nav == null ? null : nav.optJSONObject("watchEndpoint");
        if (we != null) vid = we.optString("videoId", "");
        if (vid.isEmpty()) vid = findVideoId(two);
        if (vid.isEmpty()) return null;
        String title = extractText(two.optJSONObject("title"));
        String sub = extractText(two.optJSONObject("subtitle"));
        if (title.isEmpty()) return null;
        String artist = cleanSubtitleArtist(sub);
        String thumb = extractBestThumb(two, vid);
        Widgets.Item it = buildItem(vid, title, artist, "", thumb, 0);
        enrichTrackLinks(two.optJSONObject("subtitle"), it.track);
        enrichTrackLinks(two.optJSONObject("menu"), it.track);
        return it;
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

    /** Extracts the highest-resolution thumbnail strictly inside a single thumbnail node (never crossing into straplineThumbnail). */
    private static String extractDirectThumb(JSONObject thumbNode) {
        if (thumbNode == null) return "";
        JSONObject mtr = thumbNode.optJSONObject("musicThumbnailRenderer");
        JSONObject th = mtr != null ? mtr.optJSONObject("thumbnail") : thumbNode.optJSONObject("thumbnail");
        JSONArray arr = th != null ? th.optJSONArray("thumbnails") : thumbNode.optJSONArray("thumbnails");
        if (arr == null) return "";
        String best = "";
        int bestW = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            int w = item.optInt("width", 0);
            String u = item.optString("url", "");
            if (!u.isEmpty() && w >= bestW) { bestW = w; best = u; }
        }
        return best.isEmpty() ? "" : best.replaceAll("=w\\d+-h\\d+", "=w544-h544");
    }

    private static void collectThumbRenderersSafe(Object node, List<JSONObject> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (o.has("musicThumbnailRenderer") && o.optJSONObject("musicThumbnailRenderer") != null) {
                out.add(o.optJSONObject("musicThumbnailRenderer"));
            }
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                // Skip artist strapline avatars / menu / badge thumbnails so album covers are never overwritten!
                if ("straplineThumbnail".equals(k) || "menu".equals(k) || "badges".equals(k) || "thumbnailOverlay".equals(k)) continue;
                collectThumbRenderersSafe(o.opt(k), out);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectThumbRenderersSafe(a.opt(i), out);
        }
    }

    private static String extractBestThumb(JSONObject o, String vid) {
        if (o != null) {
            String direct = extractDirectThumb(o.optJSONObject("thumbnailRenderer"));
            if (direct.isEmpty()) direct = extractDirectThumb(o.optJSONObject("thumbnail"));
            if (!direct.isEmpty()) return direct;
        }
        List<JSONObject> tList = new ArrayList<>();
        collectThumbRenderersSafe(o, tList);
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
        if (best.isEmpty()) return vid == null || vid.isEmpty() ? "" : ("https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg");
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

    // ---------------------------------------------------------------- Official YouTube HTML5 WebView Audio Engine
    static YtmWebView web;
    private static MutableContextWrapper webCtx;
    private static boolean webPageReady;
    static final List<Player.Track> queue = new ArrayList<>();
    static final List<Player.Track> original = new ArrayList<>();
    static int index;
    static int playGen;
    private static volatile boolean wantPlaying;
    private static volatile String activeVideoId = "";
    private static volatile double pendingSeekSec;
    private static volatile int everStartedGen = -1;
    private static volatile int endedHandledGen = -1;
    private static volatile long loadStampMs;
    private static volatile boolean loadRetryDone;
    private static volatile long preservePlayUntilMs;

    /**
     * Hidden WebView hosting YouTube's official mobile HTML5 player (m.youtube.com/watch?v=...).
     * Keeps visibility reported as VISIBLE while actively playing so screen-off / foldable fold-unfold
     * / background audio stays uninterrupted.
     */
    static final class YtmWebView extends WebView {
        YtmWebView(Context c) { super(c); }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged((Player.ytm() && (wantPlaying || SystemClock.elapsedRealtime() < preservePlayUntilMs))
                    ? View.VISIBLE : visibility);
        }

        @Override protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, (Player.ytm() && (wantPlaying || SystemClock.elapsedRealtime() < preservePlayUntilMs))
                    ? View.VISIBLE : visibility);
        }

        @Override public void onPause() {
            if (Player.ytm() && (wantPlaying || SystemClock.elapsedRealtime() < preservePlayUntilMs)) return;
            super.onPause();
        }

        @Override public boolean onTouchEvent(MotionEvent event) { return false; }
        @Override public boolean onInterceptTouchEvent(MotionEvent ev) { return false; }
    }

    private static final String BRIDGE_JS = "(function(){"
            + "if(window.__prismYtmHooked){return;}"
            + "window.__prismYtmHooked=true;"
            + "window.__prismTargetVid='';"
            + "window.__prismGen=0;"
            + "window.__prismWantPlay=false;"
            + "window.__prismPendingSeek=0;"
            + "window.__prismSawTarget=false;"
            + "window.__prismEndedGen=-1;"
            + "window.__prismNudgeLeft=0;"
            + "window.__prismSwitchUntil=0;"
            + "window.__prismVol=100;"
            + "window.__prismPan=0;"
            + "window.__prismLastVol=-1;"
            + "window.__prismLastPan=-99;"
            + "window.__prismQualGen=-1;"
            + "window.__prismAdBlock=true;"
            + "window.__prismWasAd=false;"
            + "(function(){try{"
            + "  if(!document.getElementById('prism-lite-css')){"
            + "    var st=document.createElement('style');st.id='prism-lite-css';"
            + "    st.textContent='ytm-comment-section-renderer,ytm-item-section-renderer[section-identifier=\"related-items\"],#related,#comments{display:none!important;}';"
            + "    (document.head||document.documentElement).appendChild(st);"
            + "  }"
            + "}catch(e){}})();"
            + "function suppressMS(){"
            + "  try{if(navigator.mediaSession&&"
            + "    (navigator.mediaSession.metadata!==null||navigator.mediaSession.playbackState!=='none')){"
            + "    navigator.mediaSession.metadata=null;"
            + "    navigator.mediaSession.playbackState='none';"
            + "  }}catch(e){}"
            + "}"
            + "function getP(){return document.getElementById('movie_player');}"
            + "function getV(){return document.querySelector('video.html5-main-video')||document.querySelector('video');}"
            + "function applyPan(force){"
            + "  try{"
            + "    var vol=window.__prismVol,pan=window.__prismPan||0;"
            + "    if(!force&&vol===window.__prismLastVol&&Math.abs(pan-window.__prismLastPan)<0.005)return;"
            + "    window.__prismLastVol=vol;window.__prismLastPan=pan;"
            + "    var p=getP(),v=getV();"
            + "    if(p&&p.setVolume)p.setVolume(vol);"
            + "    if(v)v.volume=Math.max(0,Math.min(1,vol/100));"
            + "    if(window.__prismPanner){window.__prismPanner.pan.value=pan;return;}"
            + "    if(Math.abs(pan)>0.02&&v&&v.currentSrc&&v.currentSrc.indexOf('blob:')===0&&!v.__prismPanWired){"
            + "      var AC=window.AudioContext||window.webkitAudioContext;"
            + "      if(AC){var ac=new AC({latencyHint:'playback'});var src=ac.createMediaElementSource(v);"
            + "        var pn=ac.createStereoPanner();pn.pan.value=pan;src.connect(pn);pn.connect(ac.destination);"
            + "        window.__prismAc=ac;window.__prismPanner=pn;v.__prismPanWired=true;ac.resume();}"
            + "    }"
            + "  }catch(e){}"
            + "}"
            + "function preferHighAudio(p){"
            + "  try{"
            + "    if(!p||window.__prismQualGen===window.__prismGen)return;"
            + "    var q=p.getPlaybackQuality?p.getPlaybackQuality():'';"
            + "    if(q==='tiny'||q==='small'||q==='medium'){"
            + "      window.__prismQualGen=window.__prismGen;"
            + "      if(p.setPlaybackQualityRange)p.setPlaybackQualityRange('large','hd720');"
            + "      else if(p.setPlaybackQuality)p.setPlaybackQuality('hd720');"
            + "    }else if(q){window.__prismQualGen=window.__prismGen;}"
            + "  }catch(e){}"
            + "}"
            + "window.__prismSetBalance=function(volPct,pan){window.__prismVol=volPct;window.__prismPan=pan;applyPan(true);};"
            + "window.__prismSetAdBlock=function(on){window.__prismAdBlock=!!on;};"
            + "window.__prismSkipAd=function(){"
            + "  try{"
            + "    var b=document.querySelector('.ytp-skip-ad-button,.ytp-ad-skip-button,.ytp-ad-skip-button-modern');"
            + "    if(b&&b.offsetParent!==null&&!b.disabled){b.click();return true;}"
            + "  }catch(e){}"
            + "  return false;"
            + "};"
            + "window.__prismSyncTarget=function(vid,startSec,gen,want){"
            + "  window.__prismTargetVid=vid||'';"
            + "  window.__prismGen=gen||0;"
            + "  window.__prismWantPlay=!!want;"
            + "  window.__prismPendingSeek=startSec||0;"
            + "  window.__prismSawTarget=false;"
            + "  window.__prismEndedGen=-1;"
            + "  window.__prismNudgeLeft=want?20:0;"
            + "  window.__prismSwitchUntil=Date.now()+3500;"
            + "  suppressMS();"
            + "};"
            + "window.__prismLoad=function(vid,startSec,gen){"
            + "  window.__prismSyncTarget(vid,startSec,gen,true);"
            + "  var p=getP();"
            + "  if(p&&typeof p.loadVideoById==='function'){"
            + "    try{"
            + "      p.loadVideoById({videoId:vid,startSeconds:startSec||0});"
            + "      if(p.unMute)p.unMute();"
            + "      if(p.setVolume)p.setVolume(window.__prismVol);"
            + "      if(p.playVideo)p.playVideo();"
            + "      return 'SPA';"
            + "    }catch(e){}"
            + "  }"
            + "  return 'NEED_NAV';"
            + "};"
            + "window.__prismPlay=function(){"
            + "  window.__prismWantPlay=true;window.__prismNudgeLeft=10;"
            + "  var p=getP(),v=getV();"
            + "  try{if(p&&p.unMute)p.unMute();if(p&&p.setVolume)p.setVolume(window.__prismVol);if(p&&p.playVideo)p.playVideo();}catch(e){}"
            + "  try{if(v){v.muted=false;v.play().catch(function(){});}}catch(e){}"
            + "  if(window.__prismAc)try{window.__prismAc.resume();}catch(e){}"
            + "};"
            + "window.__prismPause=function(){"
            + "  window.__prismWantPlay=false;window.__prismNudgeLeft=0;"
            + "  var p=getP(),v=getV();"
            + "  try{if(p&&p.pauseVideo)p.pauseVideo();}catch(e){}"
            + "  try{if(v)v.pause();}catch(e){}"
            + "};"
            + "window.__prismSeek=function(sec){"
            + "  window.__prismPendingSeek=0;"
            + "  var p=getP(),v=getV();"
            + "  try{if(p&&p.seekTo)p.seekTo(sec,true);else if(v)v.currentTime=sec;}catch(e){}"
            + "};"
            + "function fireEnded(){"
            + "  if(window.__prismEndedGen===window.__prismGen)return;"
            + "  window.__prismEndedGen=window.__prismGen;"
            + "  window.__prismWantPlay=false;"
            + "  var p=getP(),v=getV();"
            + "  try{if(p&&p.pauseVideo)p.pauseVideo();if(v)v.pause();}catch(e){}"
            + "  try{PrismYtm.onEnded(window.__prismGen);}catch(e){}"
            + "}"
            + "function hookVideo(v){"
            + "  if(!v||v.__prismEvents)return;"
            + "  v.__prismEvents=true;"
            + "  v.addEventListener('ended',function(){"
            + "    var p=getP();"
            + "    var ad=!!(p&&(p.classList.contains('ad-showing')||p.classList.contains('ad-interrupting')));"
            + "    if(!ad&&window.__prismSawTarget&&isFinite(v.duration)&&v.duration>5){fireEnded();}"
            + "  });"
            + "}"
            + "function tick(){"
            + "  suppressMS();"
            + "  var p=getP(),v=getV();"
            + "  hookVideo(v);"
            + "  if(!p&&!v)return;"
            + "  var ad=!!(p&&(p.classList.contains('ad-showing')||p.classList.contains('ad-interrupting')));"
            + "  var curVid='';"
            + "  try{if(p&&p.getVideoData)curVid=(p.getVideoData()&&p.getVideoData().video_id)||'';}catch(e){}"
            + "  var pState=-99,pDur=0,pCt=0;"
            + "  try{if(p&&p.getPlayerState)pState=p.getPlayerState();}catch(e){}"
            + "  try{if(p&&p.getDuration)pDur=p.getDuration()||0;}catch(e){}"
            + "  try{if(p&&p.getCurrentTime)pCt=p.getCurrentTime()||0;}catch(e){}"
            + "  var vDur=(v&&isFinite(v.duration))?v.duration:0;"
            + "  var vCt=(v&&isFinite(v.currentTime))?v.currentTime:0;"
            + "  var adRem=ad&&vDur>0?Math.max(0,Math.ceil(vDur-vCt)):0;"
            + "  var skipBtn=ad?document.querySelector('.ytp-skip-ad-button,.ytp-ad-skip-button,.ytp-ad-skip-button-modern'):null;"
            + "  var canSkip=!!(skipBtn&&skipBtn.offsetParent!==null&&!skipBtn.disabled);"
            + "  if(ad){"
            + "    window.__prismWasAd=true;"
            + "    if(window.__prismAdBlock){"
            + "      if(canSkip){try{skipBtn.click();}catch(e){}}"
            + "      try{if(v){v.muted=true;if(isFinite(vDur)&&vDur>0.5&&vCt<vDur-0.25){v.playbackRate=16;v.currentTime=Math.max(vCt,vDur-0.15);}}}catch(e){}"
            + "    }"
            + "  }else if(window.__prismWasAd){"
            + "    window.__prismWasAd=false;"
            + "    try{if(v){v.playbackRate=1;v.muted=false;v.volume=Math.max(0,Math.min(1,window.__prismVol/100));}}catch(e){}"
            + "    try{if(p&&p.unMute)p.unMute();if(p&&p.setVolume)p.setVolume(window.__prismVol);}catch(e){}"
            + "  }"
            + "  var target=window.__prismTargetVid;"
            + "  if(window.__prismWantPlay&&window.__prismNudgeLeft>0){"
            + "    if((v&&(v.paused||(!ad&&v.muted)))||pState===-1||pState===5||pState===2){"
            + "      try{if(!ad&&p&&p.unMute)p.unMute();if(!ad&&p&&p.setVolume)p.setVolume(window.__prismVol);if(p&&p.playVideo)p.playVideo();}catch(e){}"
            + "      try{if(v){if(!ad)v.muted=false;if(v.paused)v.play().catch(function(){});}}catch(e){}"
            + "      window.__prismNudgeLeft--;"
            + "    }else if(!ad&&v&&!v.paused&&vCt>0.15){"
            + "      window.__prismNudgeLeft=0;"
            + "      preferHighAudio(p);"
            + "    }"
            + "  }"
            + "  if(!ad&&target&&curVid===target){"
            + "    if((v&&!v.paused&&vCt>0.05)||pState===1||pCt>0.2){"
            + "      var firstStart=!window.__prismSawTarget;"
            + "      window.__prismSawTarget=true;"
            + "      if(firstStart){preferHighAudio(p);applyPan(true);}"
            + "      if(window.__prismPendingSeek>1){"
            + "        var sk=window.__prismPendingSeek;window.__prismPendingSeek=0;"
            + "        try{if(p&&p.seekTo)p.seekTo(sk,true);else if(v)v.currentTime=sk;}catch(e){}"
            + "      }"
            + "    }"
            + "  }"
            + "  if(!ad&&window.__prismSawTarget&&curVid&&target&&curVid!==target&&Date.now()>(window.__prismSwitchUntil||0)){"
            + "    fireEnded();return;"
            + "  }"
            + "  var dur=pDur>0?pDur:vDur;"
            + "  var ct=(!ad&&pCt>0)?pCt:vCt;"
            + "  var playing=!!(v&&!v.paused&&!v.ended)||pState===1;"
            + "  if(!ad&&window.__prismSawTarget&&(pState===0||(v&&v.ended&&dur>5)||(dur>10&&ct>=dur-0.35&&playing))){"
            + "    fireEnded();return;"
            + "  }"
            + "  try{PrismYtm.onState(window.__prismGen,playing,Math.round(ct*1000),Math.round(dur*1000),ad,adRem,canSkip);}catch(e){}"
            + "}"
            + "setInterval(tick,500);"
            + "tick();"
            + "})();";

    static String sanitizeVideoId(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < raw.length() && sb.length() < 16; i++) {
            char ch = raw.charAt(i);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_') {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** Re-attaches an already running YTM WebView into a newly created Activity (survives rotation / fold / unfold). */
    static void attachWeb(Activity a, ViewGroup root) {
        if (web == null || a == null || root == null) return;
        final boolean shouldResume = wantPlaying || SystemClock.elapsedRealtime() < preservePlayUntilMs;
        if (webCtx != null) webCtx.setBaseContext(a);
        if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
        root.addView(web, 0, new FrameLayout.LayoutParams(Ui.dp(2), Ui.dp(2)));
        try {
            web.onResume();
            web.resumeTimers();
        } catch (Throwable ignored) {}
        if (shouldResume && Player.ytm()) {
            wantPlaying = true;
            preservePlayUntilMs = SystemClock.elapsedRealtime() + 4000L;
            web.evaluateJavascript("window.__prismPlay&&window.__prismPlay();", null);
            final int g = playGen;
            Spotify.MAIN.postDelayed(() -> {
                if (g == playGen && wantPlaying && web != null) {
                    web.evaluateJavascript("window.__prismPlay&&window.__prismPlay();", null);
                }
            }, 260L);
            Spotify.MAIN.postDelayed(() -> {
                if (g == playGen && wantPlaying && web != null) {
                    web.evaluateJavascript("window.__prismPlay&&window.__prismPlay();", null);
                }
            }, 850L);
        }
    }

    /** Detaches from a dying Activity without stopping playback (Recents swipe / fold / rotation). */
    static void detachWeb(Activity a) {
        if (web == null) return;
        if (wantPlaying || (Player.ytm() && Player.playing)) {
            wantPlaying = true;
            preservePlayUntilMs = SystemClock.elapsedRealtime() + 5000L;
            try {
                web.evaluateJavascript("window.__prismWantPlay=true;window.__prismNudgeLeft=24;", null);
            } catch (Throwable ignored) {}
        }
        if (webCtx != null && webCtx.getBaseContext() == a) webCtx.setBaseContext(a.getApplicationContext());
        if (web.getParent() instanceof ViewGroup && ((ViewGroup) web.getParent()).getContext() == a) {
            ((ViewGroup) web.getParent()).removeView(web);
        }
    }

    /** Completely destroys the hidden WebView (used on sign-in/out cookie change or full shutdown when paused). */
    static void stopWeb() {
        webPageReady = false;
        if (web == null) return;
        try {
            if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
            web.stopLoading();
            web.loadUrl("about:blank");
            web.destroy();
        } catch (Throwable ignored) {}
        web = null;
        webCtx = null;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private static void ensureWeb() {
        Activity a = Player.activity;
        ViewGroup root = (a instanceof MainActivity) ? ((MainActivity) a).root : null;
        if (web != null) {
            if (a != null && root != null && web.getParent() != root) attachWeb(a, root);
            return;
        }
        Context base = a != null ? a : app;
        if (base == null) return;
        webCtx = new MutableContextWrapper(base);
        web = new YtmWebView(webCtx);
        WebSettings ws = web.getSettings();
        hardenWebSettings(ws);
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        // Save RAM & data: Prism renders artwork natively, so skip downloading thumbnails inside the hidden WebView
        ws.setBlockNetworkImage(true);
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(web, true);
        } catch (Throwable ignored) {}
        web.setWebViewClient(new WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, android.webkit.WebResourceRequest request) {
                if (request != null && request.getUrl() != null && adBlockEnabled(app) && isAdUrl(request.getUrl().toString())) {
                    return new android.webkit.WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return true;
                String u = request.getUrl().toString();
                // Block intent:// or external app-launch redirects; keep everything inside YouTube's official web player
                if (u.startsWith("https://m.youtube.com/") || u.startsWith("https://www.youtube.com/") || u.startsWith("https://music.youtube.com/")) {
                    return false;
                }
                return true;
            }

            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (view != web || url == null) return;
                if (url.contains("youtube.com")) {
                    webPageReady = true;
                    injectBridgeAndSync(view);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest r) {
                List<String> ok = new ArrayList<>();
                for (String res : r.getResources()) {
                    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(res)) ok.add(res);
                }
                if (ok.isEmpty()) r.deny(); else r.grant(ok.toArray(new String[0]));
            }
        });
        web.addJavascriptInterface(new WebBridge(), "PrismYtm");
        try {
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        } catch (Throwable ignored) {}
        // Keep inside viewport at 2dp x 2dp behind the opaque backdrop so Chromium never throttles MSE audio timers
        web.setAlpha(0.01f);
        web.setTranslationX(0f);
        web.setClickable(false);
        web.setFocusable(false);
        if (root != null) {
            root.addView(web, 0, new FrameLayout.LayoutParams(Ui.dp(2), Ui.dp(2)));
        }
    }

    private static void injectBridgeAndSync(WebView view) {
        if (view == null) return;
        final String vid = sanitizeVideoId(activeVideoId);
        final int g = playGen;
        final double sk = pendingSeekSec;
        final boolean wp = wantPlaying;
        final boolean ab = adBlockEnabled(app);
        view.evaluateJavascript(BRIDGE_JS
                + "window.__prismSetAdBlock&&window.__prismSetAdBlock(" + ab + ");"
                + "window.__prismSyncTarget&&window.__prismSyncTarget('" + vid + "'," + sk + "," + g + "," + wp + ");", null);
        applyChannelBalance();
    }

    static void syncAdBlock() {
        final boolean ab = adBlockEnabled(app);
        Spotify.MAIN.post(() -> {
            if (web != null) {
                web.evaluateJavascript("window.__prismSetAdBlock&&window.__prismSetAdBlock(" + ab + ");", null);
            }
        });
    }

    static void skipAd() {
        Spotify.MAIN.post(() -> {
            if (web != null) {
                web.evaluateJavascript("window.__prismSkipAd&&window.__prismSkipAd();", null);
            }
        });
    }

    static final class WebBridge {
        @JavascriptInterface public void onState(int gen, boolean isPlaying, long posMs, long durMs, boolean adShowing, int adRemainSec, boolean adSkippable) {
            Spotify.MAIN.post(() -> handleWebState(gen, isPlaying, posMs, durMs, adShowing, adRemainSec, adSkippable));
        }

        @JavascriptInterface public void onEnded(int gen) {
            Spotify.MAIN.post(() -> handleWebEnded(gen));
        }
    }

    /** Convenience overload for tests and callers without ad countdown metadata. */
    static void handleWebState(int gen, boolean isPlaying, long posMs, long durMs, boolean adShowing) {
        handleWebState(gen, isPlaying, posMs, durMs, adShowing, 0, false);
    }

    /** Package-visible state handler (also directly exercised by Robolectric regression tests). */
    static void handleWebState(int gen, boolean isPlaying, long posMs, long durMs, boolean adShowing, int adRemainSec, boolean adSkippable) {
        if (gen != playGen || !isYtm(Player.track)) return;
        boolean adChanged = (Player.adPlaying != adShowing)
                || (adShowing && (Player.adRemainSec != Math.max(0, adRemainSec) || Player.adSkippable != adSkippable));
        Player.adPlaying = adShowing;
        Player.adRemainSec = adShowing ? Math.max(0, adRemainSec) : 0;
        Player.adSkippable = adShowing && adSkippable;
        if (!adShowing && durMs > 1000L && Player.track != null) {
            Player.track.duration = durMs;
        }
        if (adShowing) {
            if (adChanged) {
                Player.notifyAll0();
                if (app != null) LocalMusic.PlaybackService.update(app);
            }
            return;
        }
        if (isPlaying && posMs > 100L) {
            if (everStartedGen != gen) {
                everStartedGen = gen;
                if (app != null) LocalMusic.PlaybackService.claimControl(app);
                applyChannelBalance();
            }
        }
        boolean preserving = SystemClock.elapsedRealtime() < preservePlayUntilMs && wantPlaying;
        if (preserving && !isPlaying) {
            if (web != null) web.evaluateJavascript("window.__prismPlay&&window.__prismPlay();", null);
            if (!Player.playing) { Player.playing = true; Player.notifyAll0(); }
            return;
        }
        boolean allowPauseSync = !preserving && ((everStartedGen == gen) || !wantPlaying || (SystemClock.elapsedRealtime() - loadStampMs > 12000L));
        boolean newPlaying = isPlaying || (!allowPauseSync && wantPlaying);
        if (!isPlaying && allowPauseSync) wantPlaying = false;
        long prevPos = Player.position();
        boolean changed = adChanged || (newPlaying != Player.playing) || (posMs >= 0 && Math.abs(posMs - prevPos) > 1200L);
        Player.playing = newPlaying;
        if (posMs >= 0) {
            Player.progress = posMs;
            Player.stamp = SystemClock.elapsedRealtime();
        }
        if (changed) {
            Player.notifyAll0();
            if (app != null) LocalMusic.PlaybackService.update(app);
        }
    }

    /** Package-visible track-end handler (advances Prism queue / repeat instead of YouTube's own autoplay). */
    static void handleWebEnded(int gen) {
        if (gen != playGen || endedHandledGen == gen || !isYtm(Player.track)) return;
        endedHandledGen = gen;
        onComplete();
    }

    static void play(List<Player.Track> list, int idx, String sourceTitle) {
        play(list, idx, null, sourceTitle);
    }

    static void play(List<Player.Track> list, int idx, String sourceUri, String sourceTitle) {
        if (list == null || list.isEmpty()) return;
        UnifiedPlaylist.clearActive();
        if (!Player.local() && !Player.ytm() && Player.playing && Spotify.loggedIn()) {
            Spotify.send("PUT", "/me/player/pause", null, null);
        }
        LocalMusic.stopForOther();
        Player.playingSourceUri = (sourceUri != null && !sourceUri.isEmpty()) ? sourceUri : "ytm:queue";
        Player.playingSourceTitle = (sourceTitle == null || sourceTitle.isEmpty()) ? "YouTube Music" : sourceTitle;
        original.clear();
        original.addAll(list);
        queue.clear();
        queue.addAll(list);
        index = Math.max(0, Math.min(idx, list.size() - 1));
        if (Player.shuffle) shuffleRest();
        start(0);
    }

    /** Appends a track right after the currently playing track in the YTM queue (or starts it if queue is empty). */
    static void enqueueNext(Player.Track t) {
        if (t == null) return;
        if (queue.isEmpty()) {
            List<Player.Track> one = new ArrayList<>();
            one.add(t);
            play(one, 0, null, t.name);
            return;
        }
        int ins = Math.min(queue.size(), index + 1);
        queue.add(ins, t);
        original.add(t);
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
        final String vid = sanitizeVideoId(videoIdOf(t.uri));
        wantPlaying = true;
        activeVideoId = vid;
        pendingSeekSec = Math.max(0L, seekTo) / 1000.0;
        everStartedGen = -1;
        endedHandledGen = -1;
        loadStampMs = SystemClock.elapsedRealtime();
        loadRetryDone = false;

        boolean changed = Player.track == null || !t.id.equals(Player.track.id);
        Player.clearAdState();
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
        if (app != null) LocalMusic.PlaybackService.claimControl(app);

        if (vid.isEmpty()) {
            wantPlaying = false;
            Player.playing = false;
            Player.notifyAll0();
            Player.toast(R.string.playback_failed);
            return;
        }
        if (testFixtures != null) return; // Robolectric UI tests do not hit live YouTube pages

        ensureWeb();
        if (web == null) {
            wantPlaying = false;
            Player.playing = false;
            Player.notifyAll0();
            Player.toast(R.string.playback_failed);
            return;
        }
        final String watchUrl = "https://m.youtube.com/watch?v=" + vid;
        if (webPageReady) {
            web.evaluateJavascript(
                    "window.__prismLoad?window.__prismLoad('" + vid + "'," + pendingSeekSec + "," + gen + "):'NEED_NAV'",
                    res -> {
                        if (gen != playGen || web == null) return;
                        if (res == null || !res.contains("SPA")) {
                            webPageReady = false;
                            web.loadUrl(watchUrl);
                        }
                    });
        } else {
            web.loadUrl(watchUrl);
        }

        // Single safety reload if the official mobile web player hasn't begun playback within 12 seconds
        Spotify.MAIN.postDelayed(() -> {
            if (gen != playGen || !wantPlaying || everStartedGen == gen || loadRetryDone || web == null) return;
            loadRetryDone = true;
            webPageReady = false;
            web.loadUrl(watchUrl);
        }, 12000L);
    }

    static void applyChannelBalance() {
        if (web == null || app == null) return;
        float[] lr = BitPerfect.channelGains(app);
        int volPct = Math.max(0, Math.min(100, Math.round(Math.max(lr[0], lr[1]) * 100f)));
        float pan = Math.max(-1f, Math.min(1f, lr[1] - lr[0]));
        Spotify.MAIN.post(() -> {
            if (web != null) {
                web.evaluateJavascript("window.__prismSetBalance&&window.__prismSetBalance(" + volPct + "," + pan + ");", null);
            }
        });
    }

    private static void onComplete() {
        if (UnifiedPlaylist.onBackendTrackEnded()) return;
        if ("track".equals(Player.repeat)) { start(0); return; }
        if (index + 1 < queue.size()) { index++; start(0); }
        else if ("context".equals(Player.repeat) && !queue.isEmpty()) { index = 0; start(0); }
        else { wantPlaying = false; Player.playing = false; Player.progress = 0; sync(); }
    }

    static void stopForOther() {
        playGen++;
        wantPlaying = false;
        Player.clearAdState();
        if (web != null) {
            web.evaluateJavascript("window.__prismPause&&window.__prismPause();", null);
        }
    }

    static void sync() {
        if (!isYtm(Player.track)) return;
        Player.stamp = SystemClock.elapsedRealtime();
        Player.notifyAll0();
        if (app != null) LocalMusic.PlaybackService.update(app);
    }

    static void toggle() {
        if (web == null && testFixtures == null) {
            if (!queue.isEmpty()) start(Player.progress);
            return;
        }
        wantPlaying = !Player.playing;
        Player.progress = Player.position();
        Player.playing = wantPlaying;
        Player.stamp = SystemClock.elapsedRealtime();
        if (web != null) {
            web.evaluateJavascript(wantPlaying
                    ? "window.__prismPlay&&window.__prismPlay();"
                    : "window.__prismPause&&window.__prismPause();", null);
        }
        Player.notifyAll0();
        if (app != null) {
            if (wantPlaying) LocalMusic.PlaybackService.claimControl(app);
            else LocalMusic.PlaybackService.update(app);
        }
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
        if (Player.adPlaying) return;
        long clamped = Math.max(0L, ms);
        Player.progress = clamped;
        Player.stamp = SystemClock.elapsedRealtime();
        if (web != null) {
            web.evaluateJavascript("window.__prismSeek&&window.__prismSeek(" + (clamped / 1000.0) + ");", null);
        }
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
}
