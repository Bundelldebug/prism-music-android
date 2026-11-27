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

    /** True when the hidden in-app Web Playback SDK is currently the active output device (zero Web API calls needed for controls/state). */
    static boolean active() { return web != null && ready() && deviceId.equals(Player.deviceId); }

    static void togglePlay() { if (web != null) web.evaluateJavascript("window.prismPlayer && window.prismPlayer.togglePlay()", null); }
    static void nextTrack() { if (web != null) web.evaluateJavascript("window.prismPlayer && window.prismPlayer.nextTrack()", null); }
    static void prevTrack() { if (web != null) web.evaluateJavascript("window.prismPlayer && window.prismPlayer.previousTrack()", null); }
    static void seekTo(long ms) { if (web != null) web.evaluateJavascript("window.prismPlayer && window.prismPlayer.seek(" + Math.max(0, ms) + ")", null); }

    private static final String SDK_URL = "https://sdk.scdn.co/spotify-player.js";
    private static final long SDK_TTL_MS = 24L * 60 * 60 * 1000;
    private static volatile String bridgeNonce = newNonce();

    private static String newNonce() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING);
    }

    static boolean verifyBridgeNonce(String candidate) {
        String cur = bridgeNonce;
        if (cur == null || candidate == null) return false;
        return java.security.MessageDigest.isEqual(
                cur.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static boolean isTrustedSdkOrigin(android.net.Uri org) {
        if (org == null || !"https".equalsIgnoreCase(org.getScheme())) return false;
        String host = org.getHost();
        if (host == null) return false;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        return "prism.local".equals(h)
                || "scdn.co".equals(h) || h.endsWith(".scdn.co")
                || "spotify.com".equals(h) || h.endsWith(".spotify.com")
                || "spotifycdn.com".equals(h) || h.endsWith(".spotifycdn.com");
    }

    static boolean shouldAllowNavigation(boolean isMainFrame, android.net.Uri uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost();
        if (host == null) return false;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        if (isMainFrame) return "prism.local".equals(h);
        return isTrustedSdkOrigin(uri);
    }

    private static final String HTML = "<!DOCTYPE html><html><head><meta charset='utf-8'>"
            // Block browser MediaSession BEFORE Spotify SDK loads so WebView never hijacks lockscreen / Now Bar / volume controls
            + "<script>try{Object.defineProperty(navigator,'mediaSession',{configurable:true,get:function(){return {"
            + "metadata:null,playbackState:'none',setActionHandler:function(){},setPositionState:function(){}"
            + "};}});}catch(e){}</script>"
            + "<link rel='preconnect' href='https://apresolve.spotify.com' crossorigin>"
            + "<link rel='preconnect' href='https://api.spotify.com' crossorigin>"
            + "<link rel='preconnect' href='https://dealer.spotify.com' crossorigin>"
            + "<link rel='preconnect' href='https://spclient.wg.spotify.com' crossorigin>"
            + "<script>\n"
            + "(function(){ var __N = '__PRISM_BRIDGE_NONCE__';\n"
            + "window.onSpotifyWebPlaybackSDKReady = function() {\n"
            // disable SDK's own MediaSession so Android never routes background volume keys to a remote Spotify session
            + "  var player = new Spotify.Player({ name: '__PRISM_DEVICE_NAME__', volume: 1.0, enableMediaSession: false,\n"
            + "    getOAuthToken: function(cb) { cb(Prism.token(__N)); } });\n"
            + "  window.prismPlayer = player;\n"
            + "  player.addListener('ready', function(e) { Prism.onReady(__N, e.device_id); });\n"
            + "  player.addListener('not_ready', function(e) { Prism.onNotReady(__N, e.device_id); });\n"
            + "  ['initialization_error','authentication_error','account_error','playback_error'].forEach(function(n) {\n"
            + "    player.addListener(n, function(e) { Prism.onError(__N, n + ': ' + e.message); });\n"
            + "  });\n"
            + "  player.addListener('player_state_changed', function(s) {\n"
            + "    if (!s) return;\n"
            + "    var t = s.track_window && s.track_window.current_track;\n"
            + "    var al = t && t.album;\n"
            + "    var ars = (t && t.artists) ? t.artists.map(function(a){ return {name: a.name || '', uri: a.uri || ''}; }) : [];\n"
            + "    var imgs = (al && al.images) ? al.images.map(function(im){ return {url: im.url || '', width: im.width || 0}; }) : [];\n"
            + "    Prism.onState(__N, JSON.stringify({\n"
            + "      paused: !!s.paused, position: s.position || 0, duration: s.duration || (t && t.duration_ms) || 0,\n"
            + "      shuffle: !!s.shuffle, repeat_mode: s.repeat_mode || 0,\n"
            + "      context_uri: (s.context && s.context.uri) || '',\n"
            + "      id: (t && t.id) || '', uri: (t && t.uri) || '', name: (t && t.name) || '',\n"
            + "      album_name: (al && al.name) || '', album_uri: (al && al.uri) || '',\n"
            + "      images: imgs, artists: ars\n"
            + "    }));\n"
            + "  });\n"
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
            + "  var p = window.prismPlayer; if (!p) { Prism.faded(__N, tag); return; }\n"
            + "  var from = window.prismVol, t0 = performance.now(); window.prismFadeId = (window.prismFadeId || 0) + 1; var id = window.prismFadeId;\n"
            + "  (function step() {\n"
            + "    if (id !== window.prismFadeId) return;\n"
            + "    var k = Math.min(1, (performance.now() - t0) / ms), e = k * k * (3 - 2 * k), v = from + (to - from) * e;\n"
            + "    window.prismVol = v;\n"
            // fire-and-forget: waiting on each setVolume promise stretched a 120ms ramp to several hundred ms
            + "    try { p.setVolume(Math.max(0.0001, v)); } catch (e) {}\n"
            + "    if (k < 1) setTimeout(step, 16); else Prism.faded(__N, tag);\n"
            + "  })();\n"
            + "};\n"
            + "})();\n"
            + "</script><script src='" + SDK_URL + "'></script></head><body></body></html>";

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
        if (web != null && failed) stop();
        if (web != null) { attach(a, root); return; }
        if (!Spotify.loggedIn() || !Spotify.hasScope("streaming")) return;
        failed = false;
        error = null;
        Spotify.prewarm();
        final File sdkFile = sdkCacheFile(a);
        if (!sdkFile.isFile() || System.currentTimeMillis() - sdkFile.lastModified() > SDK_TTL_MS) refreshSdkCache(sdkFile);
        bridgeNonce = newNonce();
        ctx = new android.content.MutableContextWrapper(a);
        web = new WebView(ctx);
        WebSettings s = web.getSettings();
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setSaveFormData(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if (req == null) return true;
                return !shouldAllowNavigation(req.isForMainFrame(), req.getUrl());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                if (req != null && req.getUrl() != null && SDK_URL.equals(req.getUrl().toString()) && sdkFile.isFile() && sdkFile.length() > 1024) {
                    try { return new WebResourceResponse("application/javascript", "utf-8", new FileInputStream(sdkFile)); }
                    catch (Exception ignored) {}
                }
                return super.shouldInterceptRequest(view, req);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            // Spotify's Web Playback SDK runs Widevine EME DRM inside its https://sdk.scdn.co iframe
            @Override public void onPermissionRequest(PermissionRequest r) {
                if (!isTrustedSdkOrigin(r.getOrigin())) { r.deny(); return; }
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
        web.loadDataWithBaseURL("https://prism.local/", buildHtml(playerDeviceName(a)), "text/html", "utf-8", null);
    }

    /** Builds a distinct Spotify Connect device name ("Prism (<device>)") so multiple Prism devices are distinguishable. */
    static String playerDeviceName(android.content.Context c) {
        String label = null;
        if (c != null) {
            try {
                label = android.provider.Settings.Global.getString(c.getContentResolver(), android.provider.Settings.Global.DEVICE_NAME);
            } catch (Throwable ignored) {}
            if (label == null || label.trim().isEmpty()) {
                try {
                    label = android.provider.Settings.Secure.getString(c.getContentResolver(), "bluetooth_name");
                } catch (Throwable ignored) {}
            }
        }
        if (label == null || label.trim().isEmpty() || "unknown".equalsIgnoreCase(label.trim())) {
            String model = android.os.Build.MODEL == null ? "" : android.os.Build.MODEL.trim();
            String mfr = android.os.Build.MANUFACTURER == null ? "" : android.os.Build.MANUFACTURER.trim();
            if (!model.isEmpty() && !"robolectric".equalsIgnoreCase(model)) {
                if (!mfr.isEmpty() && !model.toLowerCase(java.util.Locale.US).startsWith(mfr.toLowerCase(java.util.Locale.US))) {
                    label = mfr.substring(0, 1).toUpperCase(java.util.Locale.US) + mfr.substring(1) + " " + model;
                } else {
                    label = model;
                }
            }
        }
        return formatConnectName(label);
    }

    static String formatConnectName(String deviceLabel) {
        if (deviceLabel == null) return "Prism";
        String clean = deviceLabel.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (clean.isEmpty() || "unknown".equalsIgnoreCase(clean)) return "Prism";
        if (clean.length() > 36) clean = clean.substring(0, 36).trim();
        return "Prism (" + clean + ")";
    }

    static String buildHtml(String deviceName) {
        String safe = (deviceName == null || deviceName.trim().isEmpty() ? "Prism" : deviceName.trim())
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "")
                .replace("\n", "")
                .replace("<", "")
                .replace(">", "");
        return HTML.replace("__PRISM_DEVICE_NAME__", safe)
                .replace("__PRISM_BRIDGE_NONCE__", bridgeNonce);
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
        if (!LocalMusic.isLocal(Player.track) && !YouTubeMusic.isYtm(Player.track)) {
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

    static void restoreVolume() {
        fadeInPending = false;
        if (web != null) web.evaluateJavascript("window.prismRestoreVol && window.prismRestoreVol()", null);
    }

    private static void fadeIn() {
        if (!fadeInPending || web == null) return;
        fadeInPending = false;
        web.evaluateJavascript("window.prismFade && window.prismFade(1, 180, 'in')", null);
    }

    /** 5-stage Web Audio post-processing chain (Air/HighShelf/Vocal/Sub + DynamicsCompressor) for Spotify playback. */
    static void setNpuEnhancer(boolean on, float airDb, float vocalDb, float subDb, float dynK) {
        if (web == null) return;
        final float aDb = on ? airDb : 0f;
        final float vDb = on ? vocalDb : 0f;
        final float sDb = on ? subDb : 0f;
        final float compRatio = on ? (1.2f + 1.4f * dynK) : 1.0f;
        Spotify.MAIN.post(() -> {
            if (web == null) return;
            String js = "try{window.__prismNpu=" + on + ";"
                    + "if(window.__prismNpu && !window.__prismNpuHooked){window.__prismNpuHooked=true;"
                    + "var AC=window.AudioContext||window.webkitAudioContext;"
                    + "if(AC){var ctx=new AC({latencyHint:'playback'});window.__prismAc=ctx;"
                    + "var sub=ctx.createBiquadFilter();sub.type='lowshelf';sub.frequency.value=65;"
                    + "var pk=ctx.createBiquadFilter();pk.type='peaking';pk.frequency.value=3200;pk.Q.value=0.85;"
                    + "var hs=ctx.createBiquadFilter();hs.type='highshelf';hs.frequency.value=10500;"
                    + "var air=ctx.createBiquadFilter();air.type='highshelf';air.frequency.value=15500;"
                    + "var cmp=ctx.createDynamicsCompressor();cmp.threshold.value=-18;cmp.knee.value=12;cmp.attack.value=0.004;cmp.release.value=0.08;"
                    + "sub.connect(pk);pk.connect(hs);hs.connect(air);air.connect(cmp);cmp.connect(ctx.destination);"
                    + "window.__prismSub=sub;window.__prismPk=pk;window.__prismHs=hs;window.__prismAir=air;window.__prismCmp=cmp;window.__prismNpuIn=sub;}}"
                    + "if(window.__prismSub){window.__prismSub.gain.value=" + sDb + ";}"
                    + "if(window.__prismPk){window.__prismPk.gain.value=" + vDb + ";}"
                    + "if(window.__prismHs){window.__prismHs.gain.value=" + (aDb * 0.72f) + ";}"
                    + "if(window.__prismAir){window.__prismAir.gain.value=" + (aDb * 0.45f) + ";}"
                    + "if(window.__prismCmp){window.__prismCmp.ratio.value=" + compRatio + ";}"
                    + "if(window.__prismAc && window.__prismNpu){window.__prismAc.resume();}"
                    + "document.querySelectorAll('audio,video').forEach(function(el){"
                    + "if(window.__prismNpu && !el.__prismWired && window.__prismAc && window.__prismNpuIn){"
                    + "try{var s=window.__prismAc.createMediaElementSource(el);s.connect(window.__prismNpuIn);el.__prismWired=true;}catch(e){}"
                    + "}});}catch(e){}";
            web.evaluateJavascript(js, null);
        });
    }

    static final class Bridge {
        @JavascriptInterface public void faded(String nonce, String tag) {
            if (!verifyBridgeNonce(nonce)) return;
        }

        @JavascriptInterface public String token(String nonce) {
            if (!verifyBridgeNonce(nonce)) return "";
            try { return Spotify.token(); } catch (Exception e) { return ""; }
        }

        @JavascriptInterface public void onReady(String nonce, String id) {
            if (!verifyBridgeNonce(nonce)) return;
            Spotify.MAIN.post(() -> {
                deviceId = id;
                activate();
                if (web != null) NpuAudio.apply(web.getContext());
                List<Runnable> l = new ArrayList<>(waiting);
                waiting.clear();
                for (Runnable r : l) r.run();
                if (l.isEmpty()) preTransfer(id);
            });
        }

        @JavascriptInterface public void onNotReady(String nonce, String id) {
            if (!verifyBridgeNonce(nonce)) return;
            Spotify.MAIN.post(() -> { if (id.equals(deviceId)) deviceId = null; });
        }

        @JavascriptInterface public void onError(String nonce, String msg) {
            if (!verifyBridgeNonce(nonce)) return;
            Spotify.MAIN.post(() -> {
                error = msg;
                if (msg.startsWith("initialization") || msg.startsWith("account") || msg.startsWith("authentication")) failed = true;
            });
        }

        @JavascriptInterface public void onState(String nonce, String json) {
            if (!verifyBridgeNonce(nonce)) return;
            Spotify.MAIN.post(() -> {
                try {
                    JSONObject o = new JSONObject(json);
                    String sid = o.optString("id");
                    boolean newSong = !sid.isEmpty() && !sid.equals(fadeFromId);
                    lastStateId = sid;
                    // the new song is running (a different track, or the same one restarted): bring the level back
                    if (fadeInPending && !o.optBoolean("paused") && (newSong || o.optLong("position") < 2000))
                        Spotify.MAIN.postDelayed(SpotifyWeb::fadeIn, 40);
                    if (Player.local() || Player.ytm() || !ready()) return;
                    if (Player.deviceId == null || deviceId.equals(Player.deviceId)) {
                        Player.deviceId = deviceId;
                        Player.hasDevice = true;
                        boolean wasPlaying = Player.playing;
                        Player.playing = !o.optBoolean("paused");
                        Player.progress = o.optLong("position");
                        Player.stamp = android.os.SystemClock.elapsedRealtime();
                        Player.shuffle = o.optBoolean("shuffle", Player.shuffle);
                        String ctxUri = o.optString("context_uri", "");
                        if (!ctxUri.isEmpty()) Player.contextUri = ctxUri;
                        boolean trackChanged = false;
                        if (!sid.isEmpty() && (Player.track == null || !sid.equals(Player.track.id))) {
                            Player.Track t = new Player.Track();
                            t.id = sid;
                            t.uri = o.optString("uri", "spotify:track:" + sid);
                            t.name = o.optString("name", "");
                            t.duration = o.optLong("duration", 0);
                            t.album = o.optString("album_name", "");
                            String aUri = o.optString("album_uri", "");
                            t.albumUri = aUri.isEmpty() ? null : aUri;
                            JSONArray ars = o.optJSONArray("artists");
                            StringBuilder sb = new StringBuilder();
                            if (ars != null) {
                                for (int i = 0; i < ars.length(); i++) {
                                    JSONObject ar = ars.optJSONObject(i);
                                    if (ar == null) continue;
                                    if (sb.length() > 0) sb.append(", ");
                                    sb.append(ar.optString("name"));
                                    if (t.artistUri == null && !ar.optString("uri").isEmpty()) t.artistUri = ar.optString("uri");
                                }
                            }
                            t.artist = sb.toString();
                            JSONArray imgs = o.optJSONArray("images");
                            if (imgs != null && imgs.length() > 0) {
                                JSONObject imgWrap = new JSONObject().put("images", imgs);
                                t.art = Spotify.image(imgWrap, 600);
                                t.artSmall = Spotify.image(imgWrap, 120);
                            }
                            Player.track = t;
                            Player.liked = false;
                            trackChanged = true;
                        } else if (Player.track != null && o.optLong("duration", 0) > 0 && Player.track.duration <= 0) {
                            Player.track.duration = o.optLong("duration");
                        }
                        if (trackChanged) {
                            for (Runnable r : new ArrayList<>(Player.trackListeners)) r.run();
                            Spotify.MAIN.postDelayed(Player::checkLiked, 800);
                        }
                        Player.notifyAll0();
                        UnifiedPlaylist.onSpotifyWebState(sid, o.optBoolean("paused"), o.optLong("position"), o.optLong("duration"));
                        if (ctx != null && (Player.playing || wasPlaying)) {
                            LocalMusic.PlaybackService.claimControl(ctx.getApplicationContext());
                        }
                    }
                } catch (Exception ignored) {}
                // Do NOT call Player.refresh() on every SDK state event: SDK already pushes full track/position/pause state with 0 Web API calls!
            });
        }
    }
}
