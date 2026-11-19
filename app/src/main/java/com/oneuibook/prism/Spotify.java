package com.oneuibook.prism;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Spotify Web API client using Authorization Code + PKCE (no client secret on device).
 * The user registers their own app on developer.spotify.com and enters its Client ID in Settings.
 */
final class Spotify {
    private Spotify() {}

    static final String REDIRECT = "prismmusic://callback";
    static final String SCOPES = "user-read-private user-read-email user-read-playback-state user-modify-playback-state "
            + "user-read-currently-playing user-read-recently-played user-top-read user-library-read user-library-modify "
            + "playlist-read-private playlist-read-collaborative user-follow-read streaming";
    static final String API = "https://api.spotify.com/v1";

    static final ExecutorService IO = Executors.newFixedThreadPool(8);
    /** Dedicated lane for playback commands so home/explore/probe traffic never delays first play. */
    static final ExecutorService PLAY_IO = Executors.newSingleThreadExecutor();
    private static final ExecutorService PROBE = Executors.newFixedThreadPool(8);
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static SharedPreferences prefs;

    // cached profile
    static String userName, userImage, product, country;

    interface Cb {
        void ok(JSONObject o);
        default void err(int code, String message) {}
    }

    static final class ApiError extends Exception {
        final int code;
        final String reason;
        ApiError(int code, String msg, String reason) { super(msg); this.code = code; this.reason = reason; }
    }

    static void init(SharedPreferences p) {
        prefs = p;
        userName = p.getString("user_name", null);
        userImage = p.getString("user_image", null);
        product = p.getString("user_product", null);
        country = p.getString("user_country", null);
    }

    static String clientId() { return prefs.getString("client_id", "").trim(); }
    static boolean configured() { return clientId().length() > 0; }
    static boolean hasScope(String s) { return (" " + prefs.getString("granted_scope", "") + " ").contains(" " + s + " "); }

    static boolean loggedIn() { return prefs.getString("refresh_token", null) != null; }

    static void login(Activity a) {
        String verifier = random(64);
        String state = random(16);
        prefs.edit().putString("pkce_verifier", verifier).putString("pkce_state", state).apply();
        String url = "https://accounts.spotify.com/authorize?response_type=code"
                + "&client_id=" + enc(clientId())
                + "&scope=" + enc(SCOPES)
                + "&redirect_uri=" + enc(REDIRECT)
                + "&state=" + enc(state)
                + "&code_challenge_method=S256"
                + "&code_challenge=" + enc(challenge(verifier));
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        a.startActivity(i);
    }

    static void logout() {
        prefs.edit().remove("access_token").remove("refresh_token").remove("expires_at")
                .remove("user_name").remove("user_image").remove("user_product").remove("user_country").apply();
        userName = userImage = product = country = null;
    }

    /** Handles prismmusic://callback?code=…&state=… */
    static void handleRedirect(Uri uri, Runnable done, java.util.function.Consumer<String> fail) {
        String err = uri.getQueryParameter("error");
        if (err != null) { fail.accept(err); return; }
        String code = uri.getQueryParameter("code");
        String state = uri.getQueryParameter("state");
        if (code == null || state == null || !state.equals(prefs.getString("pkce_state", null))) {
            fail.accept("state mismatch");
            return;
        }
        String verifier = prefs.getString("pkce_verifier", "");
        IO.execute(() -> {
            try {
                String body = "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(REDIRECT)
                        + "&client_id=" + enc(clientId()) + "&code_verifier=" + enc(verifier);
                JSONObject t = tokenRequest(body);
                storeToken(t);
                prefs.edit().remove("pkce_verifier").remove("pkce_state").apply();
                loadProfileSync();
                MAIN.post(done);
            } catch (Exception e) {
                MAIN.post(() -> fail.accept(e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
    }

    private static JSONObject tokenRequest(String form) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://accounts.spotify.com/api/token").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream o = c.getOutputStream()) { o.write(form.getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code >= 400) {
            String reason = "";
            try { reason = new JSONObject(text).optString("error_description", new JSONObject(text).optString("error")); } catch (Exception ignored) {}
            throw new ApiError(code, "token " + code + " " + reason, reason);
        }
        return new JSONObject(text);
    }

    private static void storeToken(JSONObject t) {
        SharedPreferences.Editor e = prefs.edit();
        e.putString("access_token", t.optString("access_token"));
        if (t.has("refresh_token")) e.putString("refresh_token", t.optString("refresh_token"));
        if (t.has("scope")) e.putString("granted_scope", t.optString("scope"));
        e.putLong("expires_at", System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000L - 60000L);
        e.apply();
    }

    private static final Object LOCK = new Object();

    static String token() throws Exception {
        synchronized (LOCK) {
            String at = prefs.getString("access_token", null);
            if (at != null && System.currentTimeMillis() < prefs.getLong("expires_at", 0)) return at;
            String rt = prefs.getString("refresh_token", null);
            if (rt == null) throw new ApiError(401, "not logged in", "login");
            JSONObject t = tokenRequest("grant_type=refresh_token&refresh_token=" + enc(rt) + "&client_id=" + enc(clientId()));
            storeToken(t);
            return t.optString("access_token");
        }
    }

    /** Synchronous request. Returns an empty object for 204/empty bodies. */
    /** Test seam: when set, answers requests from fixtures instead of the network (UI tests only). */
    static java.util.function.Function<String, JSONObject> fixtures;

    static JSONObject call(String method, String path, String jsonBody) throws Exception {
        if (fixtures != null) {
            JSONObject f = fixtures.apply(method + " " + path);
            if (f == null) throw new ApiError(404, "no fixture", "");
            return f;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            // shared rate-limit cooldown: once Spotify says "slow down", every request waits it out instead of
            // piling on (which only extends the penalty and made whole home sections fail after a fold / refresh)
            long cool = cooldownUntil - System.currentTimeMillis();
            if (cool > 0) {
                if (cool > 30000) throw new ApiError(429, "rate limited", "rate");
                Thread.sleep(cool);
            }
            String url = path.startsWith("http") ? path : API + path;
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Authorization", "Bearer " + token());
            c.setRequestProperty("Accept", "application/json");
            if (jsonBody != null || "PUT".equals(method) || "POST".equals(method)) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = c.getOutputStream()) {
                    o.write((jsonBody == null ? "" : jsonBody).getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code == 401 && attempt == 0) {
                prefs.edit().putLong("expires_at", 0).apply();
                continue;
            }
            if (code == 429) {
                long wait = 2;
                try { wait = Math.max(1, Long.parseLong(c.getHeaderField("Retry-After"))); } catch (Exception ignored) {}
                cooldownUntil = Math.max(cooldownUntil, System.currentTimeMillis() + wait * 1000L);
                if (wait <= 30 && attempt < 2) continue;   // the loop top sleeps out the cooldown
            }
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) {
                String msg = "HTTP " + code, reason = "";
                try {
                    JSONObject e = new JSONObject(text).optJSONObject("error");
                    if (e != null) { msg = e.optString("message", msg); reason = e.optString("reason", ""); }
                } catch (Exception ignored) {}
                throw new ApiError(code, msg, reason);
            }
            if (text == null || text.trim().isEmpty()) return new JSONObject();
            String tr = text.trim();
            if (tr.startsWith("[")) return new JSONObject().put("items", new JSONArray(tr));
            return new JSONObject(tr);
        }
        throw new ApiError(401, "unauthorized", "");
    }

    static void get(String path, Cb cb) { send("GET", path, null, cb); }

    // ---- playable-playlist filter ----
    private static final java.util.Set<String> OK = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    static boolean isBlocked(String id) { return prefs.getStringSet("blocked_playlists", java.util.Collections.emptySet()).contains(id); }

    static void block(String id) {
        java.util.Set<String> s = new java.util.HashSet<>(prefs.getStringSet("blocked_playlists", java.util.Collections.emptySet()));
        s.add(id);
        prefs.edit().putStringSet("blocked_playlists", s).apply();
    }

    /**
     * Removes playlists the app can't play from: Spotify-owned/editorial ones (blocked for apps in
     * development mode), empty ones, and others whose items endpoint refuses (probed once, cached).
     * Runs on the IO thread.
     */
    static JSONArray playable(JSONArray in) {
        JSONArray out = new JSONArray();
        if (in == null) return out;
        String me = prefs.getString("user_id", "");
        for (int i = 0; i < in.length(); i++) {
            JSONObject p = in.optJSONObject(i);
            if (p == null) continue;
            if (!"playlist".equals(p.optString("type"))) { out.put(p); continue; }
            String id = p.optString("id");
            JSONObject owner = p.optJSONObject("owner");
            String ownerId = owner == null ? "" : owner.optString("id");
            if ("spotify".equals(ownerId) || total(p) == 0 || isBlocked(id)) continue;
            out.put(p);
        }
        // probe unknown playlists in parallel (was one-by-one: the main cause of slow home/explore)
        java.util.List<java.util.concurrent.Future<Boolean>> checks = new java.util.ArrayList<>();
        java.util.List<JSONObject> kept = new java.util.ArrayList<>();
        for (int i = 0; i < out.length(); i++) {
            JSONObject p = out.optJSONObject(i);
            kept.add(p);
            JSONObject owner = p.optJSONObject("owner");
            String id = p.optString("id");
            boolean needs = "playlist".equals(p.optString("type")) && fixtures == null && !OK.contains(id) && !knownOk(id)
                    && !(owner != null && owner.optString("id").equals(me));
            checks.add(needs ? PROBE.submit(() -> probe(id)) : null);
        }
        JSONArray res = new JSONArray();
        for (int i = 0; i < kept.size(); i++) {
            java.util.concurrent.Future<Boolean> f = checks.get(i);
            boolean ok = true;
            if (f != null) { try { ok = f.get(8, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { ok = true; } }
            if (ok) res.put(kept.get(i));
        }
        return res;
    }

    private static boolean knownOk(String id) { return prefs.getStringSet("ok_playlists", java.util.Collections.emptySet()).contains(id); }

    private static boolean probe(String id) {
        for (String p : new String[]{"/playlists/" + id + "/items?limit=1", "/playlists/" + id + "/tracks?limit=1"}) {
            try {
                call("GET", p, null);
                OK.add(id);
                synchronized (OK) {
                    java.util.Set<String> s = new java.util.HashSet<>(prefs.getStringSet("ok_playlists", java.util.Collections.emptySet()));
                    s.add(id);
                    prefs.edit().putStringSet("ok_playlists", s).apply();
                }
                return true;
            } catch (Exception ignored) {}
        }
        block(id);
        return false;
    }

    /** Applies {@link #playable} to playlist arrays inside a response. */
    static void filterPlaylists(JSONObject o) {
        try {
            JSONObject pl = o.optJSONObject("playlists");
            if (pl != null) pl.put("items", playable(pl.optJSONArray("items")));
            JSONArray items = o.optJSONArray("items");
            if (items != null && items.length() > 0 && items.optJSONObject(0) != null && "playlist".equals(items.optJSONObject(0).optString("type")))
                o.put("items", playable(items));
        } catch (Exception ignored) {}
    }

    static volatile long cooldownUntil;

    // ---- GET cache: re-created screens (fold / unfold / rotation / theme) reuse what was just loaded instead of
    //      firing every home/library request again at once; a failed request falls back to the last good answer ----
    private static final long FRESH_MS = 3 * 60 * 1000L;
    private static final Map<String, Object[]> GET_CACHE = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Object[]>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Object[]> e) { return size() > 160; }
    });
    private static boolean cacheable(String method, String path) { return "GET".equals(method) && !path.startsWith("/me/player") && fixtures == null; }

    static void sendPlay(String method, String path, String body, Cb cb) {
        PLAY_IO.execute(() -> {
            try {
                JSONObject o = call(method, path, body);
                if (cb != null) MAIN.post(() -> cb.ok(o));
            } catch (Exception e) {
                int code = e instanceof ApiError ? ((ApiError) e).code : 0;
                String msg = String.valueOf(e.getMessage());
                if (cb != null) MAIN.post(() -> cb.err(code, msg));
            }
        });
    }

    /** Refreshes an expired access token ahead of time so the SDK and first play request never block on OAuth. */
    static void prewarm() {
        if (!loggedIn() || fixtures != null) return;
        PLAY_IO.execute(() -> { try { token(); } catch (Exception ignored) {} });
    }

    static void send(String method, String path, String body, Cb cb) {
        final boolean cache = cacheable(method, path);
        final Object[] hit = cache ? GET_CACHE.get(path) : null;
        if (hit != null && System.currentTimeMillis() - (long) hit[0] < FRESH_MS) {
            if (cb != null) MAIN.post(() -> cb.ok((JSONObject) hit[1]));
            return;
        }
        // library changes (likes, saves, follows) make cached lists stale
        if (!"GET".equals(method) && !path.startsWith("/me/player")) GET_CACHE.clear();
        IO.execute(() -> {
            try {
                JSONObject o = call(method, path, body);
                if ("GET".equals(method) && (path.startsWith("/me/playlists") || path.startsWith("/search"))) filterPlaylists(o);
                if (cache) GET_CACHE.put(path, new Object[]{System.currentTimeMillis(), o});
                if (cb != null) MAIN.post(() -> cb.ok(o));
            } catch (Exception e) {
                if (hit != null) { if (cb != null) MAIN.post(() -> cb.ok((JSONObject) hit[1])); return; }
                int code = e instanceof ApiError ? ((ApiError) e).code : 0;
                String msg = String.valueOf(e.getMessage());
                if (cb != null) MAIN.post(() -> cb.err(code, msg));
            }
        });
    }

    /** Tries several endpoints in order (the Web API has been renaming/restricting routes). */
    static void getFirst(Cb cb, String... paths) {
        IO.execute(() -> {
            Exception last = null;
            for (String p : paths) {
                try {
                    JSONObject o = call("GET", p, null);
                    MAIN.post(() -> cb.ok(o));
                    return;
                } catch (Exception e) { last = e; }
            }
            final Exception fe = last;
            MAIN.post(() -> cb.err(fe instanceof ApiError ? ((ApiError) fe).code : 0, fe == null ? "" : String.valueOf(fe.getMessage())));
        });
    }

    static void loadProfileSync() throws Exception {
        JSONObject me = call("GET", "/me", null);
        userName = me.optString("display_name", me.optString("id"));
        JSONArray imgs = me.optJSONArray("images");
        userImage = imgs != null && imgs.length() > 0 ? imgs.optJSONObject(0).optString("url") : null;
        product = me.optString("product", null);
        country = me.optString("country", null);
        prefs.edit().putString("user_id", me.optString("id")).apply();
        prefs.edit().putString("user_name", userName).putString("user_image", userImage)
                .putString("user_product", product).putString("user_country", country).apply();
    }

    static void loadProfile(Runnable done) {
        IO.execute(() -> {
            try { loadProfileSync(); } catch (Exception ignored) {}
            if (done != null) MAIN.post(done);
        });
    }

    // ---- helpers ----
    static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return ""; }
    }

    static String read(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream i = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    private static String random(int n) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    private static String challenge(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.encodeToString(d, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- JSON model helpers ----
    static String image(JSONObject o, int prefer) {
        if (o == null) return null;
        JSONArray imgs = o.optJSONArray("images");
        if ((imgs == null || imgs.length() == 0) && o.optJSONObject("album") != null) imgs = o.optJSONObject("album").optJSONArray("images");
        if (imgs == null || imgs.length() == 0) return null;
        // images are sorted widest first; pick the smallest that is >= prefer px
        String best = imgs.optJSONObject(0).optString("url", null);
        for (int i = 0; i < imgs.length(); i++) {
            JSONObject im = imgs.optJSONObject(i);
            if (im == null) continue;
            int w = im.optInt("width", 0);
            if (w == 0 || w >= prefer) best = im.optString("url", best);
        }
        return best;
    }

    static String artists(JSONObject track) {
        JSONArray a = track == null ? null : track.optJSONArray("artists");
        if (a == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(a.optJSONObject(i).optString("name"));
        }
        return sb.toString();
    }

    /** Playlist items moved from "track" to "item" in newer API responses; accept both. */
    static JSONObject itemTrack(JSONObject it) {
        if (it == null) return null;
        JSONObject t = it.optJSONObject("item");
        if (t == null) t = it.optJSONObject("track");
        if (t == null && "track".equals(it.optString("type"))) t = it;
        return t;
    }

    static int total(JSONObject playlist) {
        JSONObject t = playlist.optJSONObject("items");
        if (t == null) t = playlist.optJSONObject("tracks");
        return t == null ? -1 : t.optInt("total", -1);
    }

    static boolean isPremium() { return product == null || "premium".equals(product); }
}
