package com.oneuibook.prism;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * In-app Spotify Connect device using Spotify's official Web Playback SDK inside a hidden WebView.
 * Audio is decoded and played by Prism itself, so the Spotify app doesn't have to be installed or opened.
 * Needs a Premium account and the "streaming" scope.
 */
final class SpotifyWeb {
    private SpotifyWeb() {}

    static WebView web;
    static String deviceId;
    static boolean failed;
    static String error;
    private static final List<Runnable> waiting = new ArrayList<>();

    static boolean ready() { return deviceId != null; }

    private static final String SDK_URL = "https://sdk.scdn.co/spotify-player.js";
    private static final long SDK_TTL_MS = 24L * 60 * 60 * 1000;

    private static final String HTML = "<!DOCTYPE html><html><head><meta charset='utf-8'>"
            + "<link rel='preconnect' href='https://apresolve.spotify.com' crossorigin>"
            + "<link rel='preconnect' href='https://api.spotify.com' crossorigin>"
            + "<link rel='preconnect' href='https://dealer.spotify.com' crossorigin>"
            + "<link rel='preconnect' href='https://spclient.wg.spotify.com' crossorigin>"
            + "<script src='" + SDK_URL + "'></script></head><body><script>\n"
            + "window.onSpotifyWebPlaybackSDKReady = function() {\n"
            // disable SDK's own MediaSession so Android never routes background volume keys to a remote Spotify session
            + "  var player = new Spotify.Player({ name: 'Prism', volume: 1.0, enableMediaSession: false,\n"
            + "    getOAuthToken: function(cb) { cb(Prism.token()); } });\n"
            + "  window.prismPlayer = player;\n"
            + "  player.addListener('ready', function(e) { Prism.onReady(e.device_id); });\n"
            + "  player.addListener('not_ready', function(e) { Prism.onNotReady(e.device_id); });\n"
            + "  ['initialization_error','authentication_error','account_error','playback_error'].forEach(function(n) {\n"
            + "    player.addListener(n, function(e) { Prism.onError(n + ': ' + e.message); });\n"
            + "  });\n"
            + "  player.addListener('player_state_changed', function(s) { if (s) Prism.onState(JSON.stringify({paused: s.paused, position: s.position, id: (s.track_window && s.track_window.current_track && s.track_window.current_track.id) || ''})); });\n"
            + "  player.connect();\n"
            + "};\n"
            // short volume ramps: cutting a waveform mid-cycle (old song) or starting one at full level (new song)
            // is what produces the click when switching songs
            + "window.prismVol = 1;\n"
            + "window.prismRestoreVol = function() {\n"
            + "  window.prismFadeId = (window.prismFadeId || 0) + 1;\n"
            + "  if (window.prismVol < 0.99 && window.prismPlayer) { window.prismVol = 1; try { window.prismPlayer.setVolume(1.0); } catch (e) {} }\n"
            + "};\n"
            + "window.prismFade = function(to, ms, tag) {\n"
            + "  var p = window.prismPlayer; if (!p) { Prism.faded(tag); return; }\n"
            + "  var from = window.prismVol, t0 = performance.now(); window.prismFadeId = (window.prismFadeId || 0) + 1; var id = window.prismFadeId;\n"
            + "  (function step() {\n"
            + "    if (id !== window.prismFadeId) return;\n"
            + "    var k = Math.min(1, (performance.now() - t0) / ms), e = k * k * (3 - 2 * k), v = from + (to - from) * e;\n"
            + "    window.prismVol = v;\n"
            // fire-and-forget: waiting on each setVolume promise stretched a 120ms ramp to several hundred ms
            + "    try { p.setVolume(Math.max(0.0001, v)); } catch (e) {}\n"
            + "    if (k < 1) setTimeout(step, 16); else Prism.faded(tag);\n"
            + "  })();\n"
            + "};\n"
            + "</script></body></html>";

    /** The WebView's context: the current activity while one is open, the application after it closes. */
    private static android.content.MutableContextWrapper ctx;

    /**
     * Puts the (already running) player into a new activity's view tree. The player outlives activities: closing the
     * app from Recents must not stop the song (the media notification keeps controlling it).
     */
    private static void attach(Activity a, ViewGroup root) {
        if (ctx != null) ctx.setBaseContext(a);
        if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
        root.addView(web, 0, new FrameLayout.LayoutParams(1, 1));
    }

    /** The activity is going away (closed from Recents, or re-created): keep the player, drop the activity. */
    static void detach(Activity a) {
        if (web == null) return;
        if (ctx != null && ctx.getBaseContext() == a) ctx.setBaseContext(a.getApplicationContext());
        if (web.getParent() instanceof ViewGroup && ((ViewGroup) web.getParent()).getContext() == a)
            ((ViewGroup) web.getParent()).removeView(web);
    }

    /** Cached spotify-player.js on disk so cold start doesn't wait on scdn.co before connecting. */
    private static File sdkCacheFile(android.content.Context c) {
        return new File(c.getApplicationContext().getCacheDir(), "spotify_sdk.js");
    }

    private static void refreshSdkCache(File f) {
        Spotify.IO.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(SDK_URL).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                if (c.getResponseCode() == 200) {
                    File tmp = new File(f.getParentFile(), "spotify_sdk.js.tmp");
                    try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    if (tmp.length() > 1024) tmp.renameTo(f);
                    else tmp.delete();
                }
            } catch (Exception ignored) {}
        });
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    static void start(Activity a, ViewGroup root) {
        if (web != null) { attach(a, root); return; }
        if (!Spotify.loggedIn() || !Spotify.hasScope("streaming")) return;
        failed = false;
        Spotify.prewarm();
        final File sdkFile = sdkCacheFile(a);
        if (!sdkFile.isFile() || System.currentTimeMillis() - sdkFile.lastModified() > SDK_TTL_MS) refreshSdkCache(sdkFile);
        ctx = new android.content.MutableContextWrapper(a);
        web = new WebView(ctx);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                if (req != null && req.getUrl() != null && SDK_URL.equals(req.getUrl().toString()) && sdkFile.isFile() && sdkFile.length() > 1024) {
                    try { return new WebResourceResponse("application/javascript", "utf-8", new FileInputStream(sdkFile)); }
                    catch (Exception ignored) {}
                }
                return super.shouldInterceptRequest(view, req);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            // Spotify's streams are DRM-protected (Widevine): allow protected media for this page only
            @Override public void onPermissionRequest(PermissionRequest r) {
                List<String> ok = new ArrayList<>();
                for (String res : r.getResources())
                    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(res)) ok.add(res);
                if (ok.isEmpty()) r.deny(); else r.grant(ok.toArray(new String[0]));
            }
        });
        web.addJavascriptInterface(new Bridge(), "Prism");
        // 1px, invisible, but attached so the page keeps running
        root.addView(web, 0, new FrameLayout.LayoutParams(1, 1));
        web.setAlpha(0f);
        web.loadDataWithBaseURL("https://prism.local/", HTML, "text/html", "utf-8", null);
    }

    static void stop() {
        if (web == null) return;
        web.evaluateJavascript("window.prismPlayer && window.prismPlayer.disconnect()", null);
        if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
        web.destroy();
        web = null;
        ctx = null;
        deviceId = null;
        activated = false;
        // no player left: a notification still saying "playing" would be a lie
        if (!LocalMusic.isLocal(Player.track)) {
            Player.playing = false;
            LocalMusic.PlaybackService.stopNow();
        }
    }

    /** Runs {@code r} once the in-app device is registered (or immediately if it already is). */
    static void whenReady(Runnable r, Runnable fallback) {
        if (ready()) { r.run(); return; }
        if (web == null || failed) { fallback.run(); return; }
        waiting.add(r);
        Spotify.MAIN.postDelayed(() -> {
            if (waiting.remove(r)) fallback.run();
        }, 8000);
    }

    /** Required by browsers' autoplay rules on first playback. */
    private static boolean activated;

    /** Unlocks audio once per session; calling it on every play made an audible tick at each new song. */
    static void activate() {
        if (activated || web == null) return;
        activated = true;
        web.evaluateJavascript("window.prismPlayer && window.prismPlayer.activateElement()", null);
    }

    // ---- click-free song switches ----
    private static boolean fadeInPending;
    private static int fadeSeq;
    private static String lastStateId = "", fadeFromId = "";

    static void switchTrack(Runnable send) { switchTrack(Player.playing, send); }

    /**
     * Click-free switch without the wait: only fades when another song is actually playing on this device.
     * On first play (or from paused), skips the fade-out/fade-in completely and ensures volume is 1.0 so audio starts
     * immediately without an extra ramp delay.
     */
    static void switchTrack(boolean wasPlaying, Runnable send) {
        if (web == null || !ready() || !wasPlaying || !deviceId.equals(Player.deviceId)) {
            if (web != null && ready()) web.evaluateJavascript("window.prismRestoreVol && window.prismRestoreVol()", null);
            fadeInPending = false;
            send.run();
            return;
        }
        final int seq = ++fadeSeq;
        fadeFromId = lastStateId;
        fadeInPending = true;
        web.evaluateJavascript("window.prismFade && window.prismFade(0, 110, 'out')", null);
        send.run();
        // fallback if no state with the new song arrives
        Spotify.MAIN.postDelayed(() -> { if (seq == fadeSeq) fadeIn(); }, 900);
    }

    /** Silently registers Prism as the active Connect device when nothing else is playing, cutting first-play latency. */
    private static void preTransfer(String id) {
        if (id == null || !Spotify.loggedIn() || Spotify.fixtures != null) return;
        if (Player.playing || Player.hasDevice || LocalMusic.isLocal(Player.track)) return;
        try {
            JSONObject b = new JSONObject().put("device_ids", new JSONArray().put(id)).put("play", false);
            Spotify.sendPlay("PUT", "/me/player", b.toString(), new Spotify.Cb() {
                @Override public void ok(JSONObject o) {
                    if (Player.deviceId == null) Player.deviceId = id;
                    Player.hasDevice = true;
                }
            });
        } catch (Exception ignored) {}
    }

    private static void fadeIn() {
        if (!fadeInPending || web == null) return;
        fadeInPending = false;
        web.evaluateJavascript("window.prismFade && window.prismFade(1, 180, 'in')", null);
    }

    static final class Bridge {
        @JavascriptInterface public void faded(String tag) { }

        @JavascriptInterface public String token() {
            try { return Spotify.token(); } catch (Exception e) { return ""; }
        }

        @JavascriptInterface public void onReady(String id) {
            Spotify.MAIN.post(() -> {
                deviceId = id;
                activate();
                List<Runnable> l = new ArrayList<>(waiting);
                waiting.clear();
                for (Runnable r : l) r.run();
                if (l.isEmpty()) preTransfer(id);
            });
        }

        @JavascriptInterface public void onNotReady(String id) {
            Spotify.MAIN.post(() -> { if (id.equals(deviceId)) deviceId = null; });
        }

        @JavascriptInterface public void onError(String msg) {
            Spotify.MAIN.post(() -> {
                error = msg;
                if (msg.startsWith("initialization") || msg.startsWith("account") || msg.startsWith("authentication")) failed = true;
            });
        }

        @JavascriptInterface public void onState(String json) {
            Spotify.MAIN.post(() -> {
                try {
                    JSONObject o = new JSONObject(json);
                    String sid = o.optString("id");
                    boolean newSong = !sid.isEmpty() && !sid.equals(fadeFromId);
                    lastStateId = sid;
                    // the new song is running (a different track, or the same one restarted): bring the level back
                    if (fadeInPending && !o.optBoolean("paused") && (newSong || o.optLong("position") < 2000))
                        Spotify.MAIN.postDelayed(SpotifyWeb::fadeIn, 40);
                    if (Player.local() || !ready() || !deviceId.equals(Player.deviceId)) return;
                    Player.playing = !o.optBoolean("paused");
                    Player.progress = o.optLong("position");
                    Player.stamp = android.os.SystemClock.elapsedRealtime();
                    Player.notifyAll0();
                } catch (Exception ignored) {}
                Player.refresh();
            });
        }
    }
}
