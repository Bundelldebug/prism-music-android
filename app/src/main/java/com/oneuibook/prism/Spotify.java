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
            + "playlist-read-private playlist-read-collaborative playlist-modify-public playlist-modify-private "
            + "user-follow-read user-follow-modify streaming";
    static final String API = "https://api.spotify.com/v1";

    static final ExecutorService IO = Executors.newFixedThreadPool(10);
    /** Dedicated lane for playback commands so home/explore/probe traffic never delays first play. */
    static final ExecutorService PLAY_IO = Executors.newSingleThreadExecutor();
    /** Separate lane for external third-party calls (Bandsintown) so they never block Spotify API IO. */
    private static final ExecutorService EVENT_IO = Executors.newFixedThreadPool(2);
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
        // 9.5.12 migration: clear playlists that earlier builds mistakenly blacklisted during 429 / limit=100 errors
        if (!p.getBoolean("unblock_playlists_9512", false)) {
            p.edit().remove("blocked_playlists").putBoolean("unblock_playlists_9512", true).apply();
        }
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
        final boolean isPlayer = path.startsWith("/me/player");
        for (int attempt = 0; attempt < 3; attempt++) {
            // Separate playback commands (/me/player/*) from browsing cooldown so browsing 429s NEVER freeze Play/Pause/Playlists!
            long until = isPlayer ? playCooldownUntil : cooldownUntil;
            long cool = until - System.currentTimeMillis();
            if (cool > 0) {
                if (cool > 3500) throw new ApiError(429, lastRateReason.isEmpty() ? "rate limited" : lastRateReason, "rate");
                Thread.sleep(cool);
            }
            String url = path.startsWith("http") ? path : API + path;
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
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
                String errBody = "";
                try { errBody = read(c.getErrorStream()); } catch (Exception ignored) {}
                String reason = "";
                try {
                    JSONObject eo = new JSONObject(errBody).optJSONObject("error");
                    if (eo != null) reason = eo.optString("reason", "");
                } catch (Exception ignored) {}
                lastRateReason = "QUOTA_EXCEEDED".equalsIgnoreCase(reason) ? "QUOTA_EXCEEDED" : ("rate limited (" + wait + "s)");
                long storeWait = isPlayer ? Math.min(wait, 3) : Math.min(wait, 20);
                if (isPlayer) playCooldownUntil = Math.max(playCooldownUntil, System.currentTimeMillis() + storeWait * 1000L);
                else cooldownUntil = Math.max(cooldownUntil, System.currentTimeMillis() + storeWait * 1000L);
                if (wait <= 3 && attempt == 0) continue;
                throw new ApiError(429, lastRateReason, reason.isEmpty() ? "rate" : reason);
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
    static boolean isBlocked(String id) { return prefs != null && prefs.getStringSet("blocked_playlists", java.util.Collections.emptySet()).contains(id); }

    static void block(String id) {
        java.util.Set<String> s = new java.util.HashSet<>(prefs.getStringSet("blocked_playlists", java.util.Collections.emptySet()));
        s.add(id);
        prefs.edit().putStringSet("blocked_playlists", s).apply();
    }

    /**
     * Local-only playlist filter (0 network calls — never fires burst probes that trigger 2026 Dev Mode 429s
     * and never hides the user's own playlists).
     */
    static JSONArray playable(JSONArray in) {
        return playable(in, false);
    }

    static JSONArray playable(JSONArray in, boolean isUserLibrary) {
        JSONArray out = new JSONArray();
        if (in == null) return out;
        String me = prefs == null ? "" : prefs.getString("user_id", "");
        for (int i = 0; i < in.length(); i++) {
            JSONObject p = in.optJSONObject(i);
            if (p == null) continue;
            if (!"playlist".equals(p.optString("type"))) { out.put(p); continue; }
            JSONObject owner = p.optJSONObject("owner");
            String ownerId = owner == null ? "" : owner.optString("id");
            boolean mine = !me.isEmpty() && me.equals(ownerId);
            if (isUserLibrary || mine) {
                out.put(p);
                continue;
            }
            // In public search results, hide Spotify-owned editorial playlists (blocked in dev mode) and empty lists locally
            if ("spotify".equals(ownerId) || total(p) == 0) continue;
            out.put(p);
        }
        return out;
    }

    /** Applies local-only {@link #playable} filter without making any extra network calls. */
    static void filterPlaylists(JSONObject o, String path) {
        try {
            boolean isUserLib = path != null && path.startsWith("/me/playlists");
            JSONObject pl = o.optJSONObject("playlists");
            if (pl != null) pl.put("items", playable(pl.optJSONArray("items"), isUserLib));
            JSONArray items = o.optJSONArray("items");
            if (items != null && items.length() > 0 && items.optJSONObject(0) != null && "playlist".equals(items.optJSONObject(0).optString("type")))
                o.put("items", playable(items, isUserLib));
        } catch (Exception ignored) {}
    }

    static volatile long cooldownUntil;
    static volatile long playCooldownUntil;
    static volatile String lastRateReason = "";

    // ---- GET cache: re-created screens (fold / unfold / rotation / theme) reuse what was just loaded instead of
    //      firing every home/library request again at once; a failed request falls back to the last good answer ----
    private static final long FRESH_MS = 10 * 60 * 1000L;
    private static final Map<String, Object[]> GET_CACHE = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Object[]>(96, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Object[]> e) { return size() > 280; }
    });
    private static final Map<String, Boolean> FOLLOW_CACHE = java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    /** Evicts only user-mutable cache entries (/me/* and /playlists/*), keeping catalog metadata cached to avoid 429 bursts. */
    static void invalidateUserCache() {
        synchronized (GET_CACHE) {
            java.util.Iterator<String> it = GET_CACHE.keySet().iterator();
            while (it.hasNext()) {
                String k = it.next();
                if (k.startsWith("/me/") || k.startsWith("/playlists/")) it.remove();
            }
        }
    }

    private static boolean cacheable(String method, String path) {
        return "GET".equals(method) && !path.startsWith("/me/player")
                && !path.startsWith("/me/following/contains")
                && !path.startsWith("/me/library/contains")
                && fixtures == null;
    }

    /** Synchronous GET backed by GET_CACHE (falls back to stale cache on 429/network error). */
    static JSONObject callCached(String path) throws Exception {
        final boolean cache = cacheable("GET", path);
        final Object[] hit = cache ? GET_CACHE.get(path) : null;
        if (hit != null && System.currentTimeMillis() - (long) hit[0] < FRESH_MS) {
            return (JSONObject) hit[1];
        }
        try {
            JSONObject o = call("GET", path, null);
            if (cache) GET_CACHE.put(path, new Object[]{System.currentTimeMillis(), o});
            return o;
        } catch (Exception e) {
            if (hit != null) return (JSONObject) hit[1];
            throw e;
        }
    }

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

    static void getPlay(String path, Cb cb) { sendPlay("GET", path, null, cb); }

    static void send(String method, String path, String body, Cb cb) {
        final boolean cache = cacheable(method, path);
        final Object[] hit = cache ? GET_CACHE.get(path) : null;
        if (hit != null && System.currentTimeMillis() - (long) hit[0] < FRESH_MS) {
            if (cb != null) MAIN.post(() -> cb.ok((JSONObject) hit[1]));
            return;
        }
        // library changes (likes, saves, follows) invalidate only /me/* and /playlists/*, keeping catalog cached
        if (!"GET".equals(method) && !path.startsWith("/me/player")) invalidateUserCache();
        IO.execute(() -> {
            try {
                JSONObject o = call(method, path, body);
                if ("GET".equals(method) && (path.startsWith("/me/playlists") || path.startsWith("/search"))) filterPlaylists(o, path);
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

    static String userId() { return prefs == null ? "" : prefs.getString("user_id", ""); }

    /** Creates a Spotify playlist (2026 POST /me/playlists first, legacy /users/{uid}/playlists fallback). */
    static void createPlaylist(String name, String description, boolean isPublic, Cb cb) {
        IO.execute(() -> {
            Exception last = null;
            try {
                JSONObject body = new JSONObject()
                        .put("name", name)
                        .put("description", description == null ? "" : description)
                        .put("public", isPublic);
                try {
                    JSONObject o = call("POST", "/me/playlists", body.toString());
                    invalidateUserCache();
                    if (cb != null) MAIN.post(() -> cb.ok(o));
                    return;
                } catch (Exception e1) {
                    last = e1;
                }
                String uid = userId();
                if (uid == null || uid.isEmpty()) {
                    try { loadProfileSync(); } catch (Exception ignored) {}
                    uid = userId();
                }
                if (uid != null && !uid.isEmpty()) {
                    JSONObject o2 = call("POST", "/users/" + enc(uid) + "/playlists", body.toString());
                    invalidateUserCache();
                    if (cb != null) MAIN.post(() -> cb.ok(o2));
                    return;
                }
            } catch (Exception e) { last = e; }
            int code = last instanceof ApiError ? ((ApiError) last).code : 0;
            String msg = last == null ? "" : String.valueOf(last.getMessage());
            if (cb != null) MAIN.post(() -> cb.err(code, msg));
        });
    }

    /** Edits a Spotify playlist's title and description. */
    static void editPlaylist(String playlistId, String name, String description, Cb cb) {
        try {
            JSONObject body = new JSONObject()
                    .put("name", name)
                    .put("description", description == null ? "" : description);
            send("PUT", "/playlists/" + enc(playlistId), body.toString(), cb);
        } catch (Exception e) {
            if (cb != null) MAIN.post(() -> cb.err(0, String.valueOf(e.getMessage())));
        }
    }

    /** Adds one or more track URIs to a Spotify playlist (2026 /items first, legacy /tracks fallback). */
    static void addTracksToPlaylist(String playlistId, java.util.List<String> uris, Cb cb) {
        IO.execute(() -> {
            Exception last = null;
            try {
                JSONArray a = new JSONArray();
                for (String u : uris) if (u != null && !u.isEmpty()) a.put(u);
                String body = new JSONObject().put("uris", a).toString();
                for (String suffix : new String[]{"/items", "/tracks"}) {
                    try {
                        JSONObject o = call("POST", "/playlists/" + enc(playlistId) + suffix, body);
                        invalidateUserCache();
                        if (cb != null) MAIN.post(() -> cb.ok(o));
                        return;
                    } catch (Exception e) { last = e; }
                }
            } catch (Exception e) { last = e; }
            int code = last instanceof ApiError ? ((ApiError) last).code : 0;
            String msg = last == null ? "" : String.valueOf(last.getMessage());
            if (cb != null) MAIN.post(() -> cb.err(code, msg));
        });
    }

    /** Removes a track URI from a Spotify playlist (2026 /items first, legacy /tracks fallback). */
    static void removeTrackFromPlaylist(String playlistId, String uri, Cb cb) {
        IO.execute(() -> {
            Exception last = null;
            try {
                JSONArray arr = new JSONArray().put(new JSONObject().put("uri", uri));
                String bodyItems = new JSONObject().put("items", arr).toString();
                String bodyTracks = new JSONObject().put("tracks", arr).toString();
                String[][] tries = {
                        {"/items", bodyItems},
                        {"/items", bodyTracks},
                        {"/tracks", bodyTracks}
                };
                for (String[] t : tries) {
                    try {
                        JSONObject o = call("DELETE", "/playlists/" + enc(playlistId) + t[0], t[1]);
                        invalidateUserCache();
                        if (cb != null) MAIN.post(() -> cb.ok(o));
                        return;
                    } catch (Exception e) { last = e; }
                }
            } catch (Exception e) { last = e; }
            int code = last instanceof ApiError ? ((ApiError) last).code : 0;
            String msg = last == null ? "" : String.valueOf(last.getMessage());
            if (cb != null) MAIN.post(() -> cb.err(code, msg));
        });
    }

    /** Moves a single track inside a Spotify playlist (2026 /items first, legacy /tracks fallback). */
    static void reorderPlaylistTrack(String playlistId, int from, int insertBefore, Cb cb) {
        IO.execute(() -> {
            Exception last = null;
            try {
                String body = new JSONObject().put("range_start", from).put("insert_before", insertBefore).toString();
                for (String suffix : new String[]{"/items", "/tracks"}) {
                    try {
                        JSONObject o = call("PUT", "/playlists/" + enc(playlistId) + suffix, body);
                        invalidateUserCache();
                        if (cb != null) MAIN.post(() -> cb.ok(o));
                        return;
                    } catch (Exception e) { last = e; }
                }
            } catch (Exception e) { last = e; }
            int code = last instanceof ApiError ? ((ApiError) last).code : 0;
            String msg = last == null ? "" : String.valueOf(last.getMessage());
            if (cb != null) MAIN.post(() -> cb.err(code, msg));
        });
    }

    /** Checks whether the signed-in user follows the given artist ID (2026 /me/library first, legacy fallback). */
    static void isFollowingArtist(String artistId, java.util.function.Consumer<Boolean> cb) {
        if (artistId == null || artistId.isEmpty() || !loggedIn()) {
            if (cb != null) MAIN.post(() -> cb.accept(false));
            return;
        }
        IO.execute(() -> {
            Boolean res = null;
            // 1) 2026 Spotify Web API unified endpoint (/me/library/contains?uris=spotify:artist:<id>)
            try {
                JSONObject o = call("GET", "/me/library/contains?uris=" + enc("spotify:artist:" + artistId), null);
                JSONArray a = o == null ? null : o.optJSONArray("items");
                if (a != null && a.length() > 0) res = a.optBoolean(0, false);
            } catch (Exception ignored) {}
            // 2) Legacy fallback (/me/following/contains)
            if (res == null) {
                try {
                    JSONObject o = call("GET", "/me/following/contains?type=artist&ids=" + enc(artistId), null);
                    JSONArray a = o == null ? null : o.optJSONArray("items");
                    if (a != null && a.length() > 0) res = a.optBoolean(0, false);
                } catch (Exception ignored) {}
            }
            if (res != null) FOLLOW_CACHE.put(artistId, res);
            else res = FOLLOW_CACHE.getOrDefault(artistId, false);
            final boolean f = res;
            if (cb != null) MAIN.post(() -> cb.accept(f));
        });
    }

    /** Follows or unfollows an artist (2026 /me/library first using user-library-modify, legacy /me/following fallback). */
    static void setFollowArtist(String artistId, boolean follow, Cb cb) {
        if (artistId == null || artistId.isEmpty()) {
            if (cb != null) MAIN.post(() -> cb.err(400, "missing artist id"));
            return;
        }
        IO.execute(() -> {
            String method = follow ? "PUT" : "DELETE";
            Exception lastErr = null;
            // 1) 2026 unified library endpoint: PUT/DELETE /me/library?uris=spotify:artist:<id>
            String artistUri = "spotify:artist:" + artistId;
            try {
                JSONObject o = call(method, "/me/library?uris=" + enc(artistUri), null);
                FOLLOW_CACHE.put(artistId, follow);
                invalidateUserCache();
                if (cb != null) MAIN.post(() -> cb.ok(o));
                return;
            } catch (Exception e) {
                lastErr = e;
            }
            // 1b) Retry /me/library with JSON body {"uris":[...]} in case endpoint rejects query-only DELETE/PUT
            try {
                String body = new JSONObject().put("uris", new JSONArray().put(artistUri)).toString();
                JSONObject o1b = call(method, "/me/library", body);
                FOLLOW_CACHE.put(artistId, follow);
                invalidateUserCache();
                if (cb != null) MAIN.post(() -> cb.ok(o1b));
                return;
            } catch (Exception ignored) {}
            // 2) Legacy fallback: PUT/DELETE /me/following?type=artist&ids=<id>
            try {
                JSONObject o2 = call(method, "/me/following?type=artist&ids=" + enc(artistId), null);
                FOLLOW_CACHE.put(artistId, follow);
                invalidateUserCache();
                if (cb != null) MAIN.post(() -> cb.ok(o2));
                return;
            } catch (Exception e2) {
                if (lastErr == null) lastErr = e2;
            }
            int code = lastErr instanceof ApiError ? ((ApiError) lastErr).code : 0;
            String msg = lastErr == null ? "" : String.valueOf(lastErr.getMessage());
            if (cb != null) MAIN.post(() -> cb.err(code, msg));
        });
    }

    /**
     * Fetches upcoming live/offline concerts for an artist via the public Bandsintown REST API
     * on a dedicated background pool so it never slows down Spotify API requests.
     */
    static void fetchArtistEvents(String artistName, java.util.function.Consumer<JSONArray> cb) {
        if (artistName == null || artistName.trim().isEmpty()) {
            if (cb != null) MAIN.post(() -> cb.accept(new JSONArray()));
            return;
        }
        EVENT_IO.execute(() -> {
            JSONArray out = new JSONArray();
            try {
                if (fixtures != null) {
                    JSONObject f = fixtures.apply("EVENTS " + artistName);
                    JSONArray fa = f == null ? null : f.optJSONArray("items");
                    if (fa != null) out = fa;
                } else {
                    String url = "https://rest.bandsintown.com/artists/" + enc(artistName.trim()) + "/events?app_id=prism_music";
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod("GET");
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(5000);
                    c.setRequestProperty("Accept", "application/json");
                    int code = c.getResponseCode();
                    if (code >= 200 && code < 300) {
                        String txt = read(c.getInputStream()).trim();
                        if (txt.startsWith("[")) out = new JSONArray(txt);
                    }
                }
            } catch (Exception ignored) {}
            final JSONArray res = out;
            if (cb != null) MAIN.post(() -> cb.accept(res));
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
