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
        Spotify.MAIN.post(YouTubeMusic::stopWeb);
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

    private static void extractShelvesAny(JSONObject root, Map<String, List<Widgets.Item>> out) {
        List<JSONObject> shelves = new ArrayList<>();
        collectByKey(root, "musicCarouselShelfRenderer", shelves);
        collectByKey(root, "musicShelfRenderer", shelves);
        collectByKey(root, "gridRenderer", shelves);
        for (JSONObject shelf : shelves) {
            String title = extractText(shelf.optJSONObject("header"));
            if (title.isEmpty()) title = extractText(shelf.optJSONObject("title"));
            if (title.isEmpty()) title = "YouTube Music";
            List<Widgets.Item> items = new ArrayList<>();
            extractAnyItemsRecursive(shelf, items, 25);
            if (!items.isEmpty()) {
                List<Widgets.Item> existing = out.get(title);
                if (existing == null) out.put(title, items);
                else existing.addAll(items);
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

    private static Widgets.Item parseListItemAny(JSONObject rli) {
        Widgets.Item tr = parseListItem(rli);
        if (tr != null) return tr;
        String bid = findBrowseId(rli);
        if (bid.isEmpty()) return null;
        JSONArray cols = rli.optJSONArray("flexColumns");
        String title = cols != null && cols.length() > 0 ? extractText(cols.optJSONObject(0)) : "";
        String sub = cols != null && cols.length() > 1 ? extractText(cols.optJSONObject(1)) : "";
        if (title.isEmpty()) return null;
        return buildBrowseItem(bid, title, sub, extractBestThumb(rli, ""));
    }

    private static Widgets.Item parseTwoRowItemAny(JSONObject two) {
        Widgets.Item tr = parseTwoRowItem(two);
        if (tr != null) return tr;
        String bid = findBrowseId(two);
        if (bid.isEmpty()) return null;
        String title = extractText(two.optJSONObject("title"));
        String sub = extractText(two.optJSONObject("subtitle"));
        if (title.isEmpty()) return null;
        return buildBrowseItem(bid, title, sub, extractBestThumb(two, ""));
    }

    private static Widgets.Item buildBrowseItem(String browseId, String title, String subtitle, String thumb) {
        Widgets.Item it = new Widgets.Item();
        if (browseId.startsWith("MPREb_")) it.type = "ytm_album";
        else if (browseId.startsWith("UC") || browseId.startsWith("MPLA")) it.type = "ytm_artist";
        else it.type = "ytm_playlist";
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
                String f = findBrowseId(o.opt(it.next()));
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

    /**
     * Hidden WebView hosting YouTube's official mobile HTML5 player (m.youtube.com/watch?v=...).
     * Keeps visibility reported as VISIBLE while actively playing so screen-off / background audio stays uninterrupted.
     */
    static final class YtmWebView extends WebView {
        YtmWebView(Context c) { super(c); }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged((Player.ytm() && wantPlaying) ? View.VISIBLE : visibility);
        }

        @Override protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, (Player.ytm() && wantPlaying) ? View.VISIBLE : visibility);
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
            + "function suppressMS(){"
            + "  try{if(navigator.mediaSession){"
            + "    navigator.mediaSession.metadata=null;"
            + "    navigator.mediaSession.playbackState='none';"
            + "  }}catch(e){}"
            + "}"
            + "function getP(){return document.getElementById('movie_player');}"
            + "function getV(){return document.querySelector('video.html5-main-video')||document.querySelector('video');}"
            + "function applyPan(){"
            + "  try{"
            + "    var p=getP(),v=getV();"
            + "    if(p&&p.setVolume)p.setVolume(window.__prismVol);"
            + "    if(v)v.volume=Math.max(0,Math.min(1,window.__prismVol/100));"
            + "    var pan=window.__prismPan||0;"
            + "    if(window.__prismPanner){window.__prismPanner.pan.value=pan;return;}"
            + "    if(Math.abs(pan)>0.02&&v&&v.currentSrc&&v.currentSrc.indexOf('blob:')===0&&!v.__prismPanWired){"
            + "      var AC=window.AudioContext||window.webkitAudioContext;"
            + "      if(AC){var ac=new AC({latencyHint:'playback'});var src=ac.createMediaElementSource(v);"
            + "        var pn=ac.createStereoPanner();pn.pan.value=pan;src.connect(pn);pn.connect(ac.destination);"
            + "        window.__prismAc=ac;window.__prismPanner=pn;v.__prismPanWired=true;ac.resume();}"
            + "    }"
            + "  }catch(e){}"
            + "}"
            + "window.__prismSetBalance=function(volPct,pan){window.__prismVol=volPct;window.__prismPan=pan;applyPan();};"
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
            + "  var target=window.__prismTargetVid;"
            + "  if(window.__prismWantPlay&&window.__prismNudgeLeft>0){"
            + "    if((v&&(v.paused||v.muted))||pState===-1||pState===5||pState===2){"
            + "      try{if(p&&p.unMute)p.unMute();if(p&&p.setVolume)p.setVolume(window.__prismVol);if(p&&p.playVideo)p.playVideo();}catch(e){}"
            + "      try{if(v){v.muted=false;if(v.paused)v.play().catch(function(){});}}catch(e){}"
            + "      window.__prismNudgeLeft--;"
            + "    }else if(!ad&&v&&!v.paused&&vCt>0.15){"
            + "      window.__prismNudgeLeft=0;"
            + "      try{if(p&&p.setPlaybackQuality)p.setPlaybackQuality('small');}catch(e){}"
            + "    }"
            + "  }"
            + "  if(!ad&&target&&curVid===target){"
            + "    if((v&&!v.paused&&vCt>0.05)||pState===1||pCt>0.2){"
            + "      window.__prismSawTarget=true;"
            + "      if(window.__prismPendingSeek>1){"
            + "        var sk=window.__prismPendingSeek;window.__prismPendingSeek=0;"
            + "        try{if(p&&p.seekTo)p.seekTo(sk,true);else if(v)v.currentTime=sk;}catch(e){}"
            + "      }"
            + "      applyPan();"
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
            + "  try{PrismYtm.onState(window.__prismGen,playing,Math.round(ct*1000),Math.round(dur*1000),ad);}catch(e){}"
            + "}"
            + "setInterval(tick,350);"
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
        if (webCtx != null) webCtx.setBaseContext(a);
        if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
        root.addView(web, 0, new FrameLayout.LayoutParams(Ui.dp(320), Ui.dp(180)));
    }

    /** Detaches from a dying Activity without stopping playback (Recents swipe / fold / rotation). */
    static void detachWeb(Activity a) {
        if (web == null) return;
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
        web.setAlpha(0f);
        web.setTranslationX(-Ui.dp(4000));
        web.setClickable(false);
        web.setFocusable(false);
        if (root != null) {
            root.addView(web, 0, new FrameLayout.LayoutParams(Ui.dp(320), Ui.dp(180)));
        }
    }

    private static void injectBridgeAndSync(WebView view) {
        if (view == null) return;
        final String vid = sanitizeVideoId(activeVideoId);
        final int g = playGen;
        final double sk = pendingSeekSec;
        final boolean wp = wantPlaying;
        view.evaluateJavascript(BRIDGE_JS + "window.__prismSyncTarget&&window.__prismSyncTarget('" + vid + "'," + sk + "," + g + "," + wp + ");", null);
        applyChannelBalance();
    }

    static final class WebBridge {
        @JavascriptInterface public void onState(int gen, boolean isPlaying, long posMs, long durMs, boolean adShowing) {
            Spotify.MAIN.post(() -> handleWebState(gen, isPlaying, posMs, durMs, adShowing));
        }

        @JavascriptInterface public void onEnded(int gen) {
            Spotify.MAIN.post(() -> handleWebEnded(gen));
        }
    }

    /** Package-visible state handler (also directly exercised by Robolectric regression tests). */
    static void handleWebState(int gen, boolean isPlaying, long posMs, long durMs, boolean adShowing) {
        if (gen != playGen || !isYtm(Player.track)) return;
        if (!adShowing && durMs > 1000L && Player.track != null) {
            Player.track.duration = durMs;
        }
        if (adShowing) return;
        if (isPlaying && posMs > 100L) {
            if (everStartedGen != gen) {
                everStartedGen = gen;
                if (app != null) LocalMusic.PlaybackService.claimControl(app);
                applyChannelBalance();
            }
        }
        boolean allowPauseSync = (everStartedGen == gen) || !wantPlaying || (SystemClock.elapsedRealtime() - loadStampMs > 12000L);
        boolean newPlaying = isPlaying || (!allowPauseSync && wantPlaying);
        if (!isPlaying && allowPauseSync) wantPlaying = false;
        long prevPos = Player.position();
        boolean changed = (newPlaying != Player.playing) || (posMs >= 0 && Math.abs(posMs - prevPos) > 1200L);
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
        final String vid = sanitizeVideoId(videoIdOf(t.uri));
        wantPlaying = true;
        activeVideoId = vid;
        pendingSeekSec = Math.max(0L, seekTo) / 1000.0;
        everStartedGen = -1;
        endedHandledGen = -1;
        loadStampMs = SystemClock.elapsedRealtime();
        loadRetryDone = false;

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
        if ("track".equals(Player.repeat)) { start(0); return; }
        if (index + 1 < queue.size()) { index++; start(0); }
        else if ("context".equals(Player.repeat) && !queue.isEmpty()) { index = 0; start(0); }
        else { wantPlaying = false; Player.playing = false; Player.progress = 0; sync(); }
    }

    static void stopForOther() {
        playGen++;
        wantPlaying = false;
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
