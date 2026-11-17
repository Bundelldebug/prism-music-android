package com.oneuibook.prism;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import org.json.JSONObject;

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

    private static final String HTML = "<!DOCTYPE html><html><head><meta charset='utf-8'>"
            + "<script src='https://sdk.scdn.co/spotify-player.js'></script></head><body><script>\n"
            + "window.onSpotifyWebPlaybackSDKReady = function() {\n"
            + "  var player = new Spotify.Player({ name: 'Prism', volume: 1.0,\n"
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

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    static void start(Activity a, ViewGroup root) {
        if (web != null) { attach(a, root); return; }
        if (!Spotify.loggedIn() || !Spotify.hasScope("streaming")) return;
        failed = false;
        ctx = new android.content.MutableContextWrapper(a);
        web = new WebView(ctx);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
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

    /**
     * Click-free switch without the wait: the play request goes out at once and the short fade-out runs while it is
     * in flight (a Web API round trip is longer than the 110ms ramp), so the old song is already quiet when the new
     * one starts. The level comes back on the first state of the new song, not after a timeout.
     */
    static void switchTrack(Runnable send) {
        if (web == null || !ready() || !deviceId.equals(Player.deviceId)) { send.run(); return; }
        final int seq = ++fadeSeq;
        fadeFromId = lastStateId;
        fadeInPending = true;
        if (Player.playing) web.evaluateJavascript("window.prismFade && window.prismFade(0, 110, 'out')", null);
        send.run();
        // fallback if no state with the new song arrives
        Spotify.MAIN.postDelayed(() -> { if (seq == fadeSeq) fadeIn(); }, 900);
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
                List<Runnable> l = new ArrayList<>(waiting);
                waiting.clear();
                for (Runnable r : l) r.run();
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
