package com.oneuibook.prism;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Remote playback state + commands through Spotify Connect (Web API player endpoints). */
final class Player {
    private Player() {}

    static final class Track {
        String id, uri, name, artist, album, albumUri, artistUri, art, artSmall;
        long duration;
        /** On-device songs only: track number within the album (0 = none), disc, and when it was added. */
        int trackNo, disc;
        /** Merged multi-disc album: the album name without "Disc N" (null for ordinary albums). */
        String albumGroup;
        long added;
        long albumId = -1;

        static Track from(JSONObject t) {
            if (t == null) return null;
            Track r = new Track();
            r.id = t.optString("id");
            r.uri = t.optString("uri");
            r.name = t.optString("name");
            r.artist = Spotify.artists(t);
            JSONObject al = t.optJSONObject("album");
            if (al == null && t.optJSONObject("show") != null) al = t.optJSONObject("show");
            r.album = al == null ? "" : al.optString("name");
            r.albumUri = al == null ? null : al.optString("uri", null);
            JSONArray ar = t.optJSONArray("artists");
            r.artistUri = ar != null && ar.length() > 0 ? ar.optJSONObject(0).optString("uri", null) : null;
            r.art = Spotify.image(al != null ? al : t, 600);
            r.artSmall = Spotify.image(al != null ? al : t, 120);
            r.duration = t.optLong("duration_ms", 0);
            return r;
        }
    }

    static Track track;
    static boolean playing, shuffle, liked, hasDevice;
    static String repeat = "off";
    static long progress, stamp;
    static String deviceName, deviceType, deviceId;
    static int volume = -1;

    static Activity activity;
    static final List<Runnable> listeners = new ArrayList<>();
    static final List<Runnable> trackListeners = new ArrayList<>();
    private static boolean polling, fast;
    private static long lastCommand, lastRepeatFix;

    private static final Runnable POLL = new Runnable() {
        @Override public void run() {
            if (!polling) return;
            // In-app SpotifyWeb SDK pushes real-time track/position/pause/shuffle/repeat with 0 Web API calls!
            // Keep background polling ultra-light (90s in-app, 15-22s remote) and NEVER poll during 429 cooldown.
            if (!Spotify.isRateLimited()) {
                boolean sdkHasState = SpotifyWeb.active() && track != null;
                if (!sdkHasState || SystemClock.elapsedRealtime() - lastRefresh >= 85000) refresh();
            }
            long nextDelay = (SpotifyWeb.active() && track != null) ? 90000 : (fast ? 15000 : 22000);
            Spotify.MAIN.postDelayed(this, nextDelay);
        }
    };

    static void start(Activity a) {
        activity = a;
        if (!local()) repeat = wantedRepeat();
        if (polling) return;
        polling = true;
        Spotify.MAIN.post(POLL);
    }

    static void stop() {
        polling = false;
        Spotify.MAIN.removeCallbacks(POLL);
    }

    static void setFast(boolean f) { fast = f; }

    static long position() {
        long p = playing ? progress + (SystemClock.elapsedRealtime() - stamp) : progress;
        if (track != null && track.duration > 0) p = Math.min(p, track.duration);
        return Math.max(0, p);
    }

    static void notifyAll0() { for (Runnable r : new ArrayList<>(listeners)) r.run(); }

    static boolean local() { return LocalMusic.isLocal(track); }
    static boolean ytm() { return YouTubeMusic.isYtm(track); }
    /** Tracks the active playlist/album URI & display title so Queue can jump straight back to it. */
    static String playingSourceUri, playingSourceTitle;

    private static long lastRefresh;

    static void refresh() {
        if (local()) { LocalMusic.sync(); return; }
        if (ytm()) { YouTubeMusic.sync(); return; }
        if (!Spotify.loggedIn() || Spotify.isRateLimited()) return;
        final long requested = SystemClock.elapsedRealtime();
        // Throttle /me/player polls so rapid UI events never burn through 2026 Dev Mode rolling rate limits
        if (requested - lastRefresh < 4000) return;
        lastRefresh = requested;
        Spotify.get("/me/player?additional_types=track,episode", new Spotify.Cb() {
            @Override public void ok(JSONObject o) {
                if (requested < lastCommand) return; // stale response raced an optimistic update
                apply(o);
            }
        });
    }

    static void apply(JSONObject o) {
        if (o == null || o.length() == 0) {
            hasDevice = false;
            if (playing) { progress = position(); playing = false; }
            notifyAll0();
            return;
        }
        hasDevice = true;
        JSONObject item = o.optJSONObject("item");
        Track t = Track.from(item);
        boolean changed = t != null && (track == null || !t.id.equals(track.id));
        boolean wasPlaying = playing;
        long prevPos = position();
        if (t != null) track = t;
        playing = o.optBoolean("is_playing");
        long serverProg = o.optLong("progress_ms", 0);
        // Smooth out poll RTT jitter: if same track is continuously playing and within 1.2s of our local clock, keep local clock smooth
        if (!changed && wasPlaying && playing && Math.abs(serverProg - prevPos) <= 1200L) {
            progress = prevPos;
        } else {
            progress = serverProg;
        }
        stamp = SystemClock.elapsedRealtime();
        shuffle = o.optBoolean("shuffle_state");
        String serverRepeat = o.optString("repeat_state", "off");
        repeat = wantedRepeat();
        // Only sync repeat on an actual track change (never hammer PUT /me/player/repeat every 3s!)
        if (changed && !serverRepeat.equals(repeat) && !Spotify.isRateLimited() && SystemClock.elapsedRealtime() - lastRepeatFix > 15000) {
            lastRepeatFix = SystemClock.elapsedRealtime();
            applyWantedRepeat();
        }
        JSONObject ctx = o.optJSONObject("context");
        contextUri = ctx == null ? null : ctx.optString("uri", null);
        JSONObject d = o.optJSONObject("device");
        if (d != null) {
            deviceName = d.optString("name");
            deviceType = d.optString("type");
            deviceId = d.optString("id");
            volume = d.isNull("volume_percent") ? -1 : d.optInt("volume_percent", -1);
        }
        if (changed) {
            checkLiked();
            for (Runnable r : new ArrayList<>(trackListeners)) r.run();
        }
        notifyAll0();
    }

    private static void optimistic() {
        lastCommand = SystemClock.elapsedRealtime();
        notifyAll0();
    }

    static void toggle() {
        if (local()) { LocalMusic.toggle(); return; }
        if (ytm()) { YouTubeMusic.toggle(); return; }
        if (track == null && !hasDevice) { toast(R.string.nothing_playing); return; }
        progress = position();
        stamp = SystemClock.elapsedRealtime();
        boolean wasPlaying = playing;
        playing = !playing;
        optimistic();
        if (SpotifyWeb.active()) { SpotifyWeb.togglePlay(); return; }
        command("PUT", wasPlaying ? "/me/player/pause" : "/me/player/play", null, null);
    }

    static void next() {
        if (local()) { LocalMusic.next(); return; }
        if (ytm()) { YouTubeMusic.next(); return; }
        if (SpotifyWeb.active()) {
            SpotifyWeb.switchTrack(SpotifyWeb::nextTrack);
            progress = 0; stamp = SystemClock.elapsedRealtime(); optimistic();
            return;
        }
        SpotifyWeb.switchTrack(() -> command("POST", "/me/player/next", null, null));
        progress = 0; stamp = SystemClock.elapsedRealtime(); optimistic(); later();
    }

    static void prev() {
        if (local()) { LocalMusic.prev(); return; }
        if (ytm()) { YouTubeMusic.prev(); return; }
        if (position() > 3000) { seek(0); return; }
        if (SpotifyWeb.active()) {
            SpotifyWeb.switchTrack(SpotifyWeb::prevTrack);
            progress = 0; stamp = SystemClock.elapsedRealtime(); optimistic();
            return;
        }
        SpotifyWeb.switchTrack(() -> command("POST", "/me/player/previous", null, null));
        progress = 0; stamp = SystemClock.elapsedRealtime(); optimistic(); later();
    }

    static void seek(long ms) {
        if (local()) { LocalMusic.seek(ms); return; }
        if (ytm()) { YouTubeMusic.seek(ms); return; }
        progress = ms;
        stamp = SystemClock.elapsedRealtime();
        optimistic();
        if (SpotifyWeb.active()) { SpotifyWeb.seekTo(ms); return; }
        command("PUT", "/me/player/seek?position_ms=" + ms, null, null);
    }

    static void toggleShuffle() {
        shuffle = !shuffle;
        optimistic();
        if (local()) { LocalMusic.setShuffle(shuffle); return; }
        if (ytm()) { YouTubeMusic.setShuffle(shuffle); return; }
        command("PUT", "/me/player/shuffle?state=" + shuffle, null, null);
    }

    static void cycleRepeat() {
        repeat = "off".equals(repeat) ? "context" : ("context".equals(repeat) ? "track" : "off");
        Spotify.prefs.edit().putString("repeat", repeat).apply();
        optimistic();
        if (local() || ytm()) return;
        command("PUT", "/me/player/repeat?state=" + repeat, null, null);
    }

    static void playContext(String contextUri, String offsetUri, Track preview) {
        playContext(contextUri, offsetUri, preview, null, 0);
    }

    /**
     * Starts playback for an album/playlist context, automatically falling back to {@code fallbackUris} if Spotify
     * rejects {@code context_uri} or {@code offset.uri} (e.g. relinked tracks or restricted Connect state).
     */
    static void playContext(String contextUri, String offsetUri, Track preview, List<String> fallbackUris, int fallbackOffset) {
        try {
            JSONObject b = new JSONObject().put("context_uri", contextUri);
            if (offsetUri != null && (offsetUri.startsWith("spotify:track:") || offsetUri.startsWith("spotify:episode:"))) {
                b.put("offset", new JSONObject().put("uri", offsetUri));
            }
            String fallbackBody = null;
            if (fallbackUris != null && !fallbackUris.isEmpty()) {
                JSONArray a = new JSONArray();
                for (String u : fallbackUris) a.put(u);
                fallbackBody = new JSONObject()
                        .put("uris", a)
                        .put("offset", new JSONObject().put("position", Math.max(0, Math.min(fallbackOffset, fallbackUris.size() - 1))))
                        .toString();
            }
            startPlay(b, preview, offsetUri != null ? offsetUri : contextUri, fallbackBody);
        } catch (Exception ignored) {}
    }

    // ---- volume (player-screen volume bar) ----
    private static long lastVolSend;
    private static int pendingVol = -1;

    /**
     * 0..1, or -1 if unknown. This phone (on-device songs, in-app Spotify): the media stream volume.
     * Another Spotify Connect device: that device's volume_percent.
     */
    static float volume01(android.content.Context c) {
        if (onThisPhone()) {
            android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(android.content.Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            return max <= 0 ? -1 : am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) / (float) max;
        }
        return volume < 0 ? -1 : volume / 100f;
    }

    static void setVolume01(android.content.Context c, float v, boolean done) {
        v = Math.max(0f, Math.min(1f, v));
        if (onThisPhone()) {
            android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(android.content.Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.round(v * max), 0);
            return;
        }
        // remote device: Web API call, lightly throttled while dragging, always sent on release
        volume = Math.round(v * 100);
        long now = SystemClock.elapsedRealtime();
        if (!done && now - lastVolSend < 100) { pendingVol = volume; return; }
        lastVolSend = now;
        pendingVol = -1;
        Spotify.sendPlay("PUT", "/me/player/volume?volume_percent=" + volume, null, null);
    }

    // ---- what the sound actually comes out of ----
    private static boolean outputWatch;

    /** True when this phone itself produces the audio (an on-device song, YouTube Music, or Spotify on Prism's in-app player). */
    static boolean onThisPhone() {
        return local() || ytm() || (SpotifyWeb.ready() && SpotifyWeb.deviceId.equals(deviceId));
    }

    /**
     * Label for the output chip: on this phone → the connected earphones/headset/DAC by name, else "이 휴대전화";
     * on another Spotify Connect device → that device's name.
     */
    static String outputName(android.content.Context c) {
        if (!onThisPhone()) return deviceName == null || deviceName.isEmpty() ? "Spotify" : deviceName;
        android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(android.content.Context.AUDIO_SERVICE);
        if (am != null) {
            if (!outputWatch) {
                outputWatch = true;
                am.registerAudioDeviceCallback(new android.media.AudioDeviceCallback() {
                    @Override public void onAudioDevicesAdded(android.media.AudioDeviceInfo[] d) { notifyAll0(); }
                    @Override public void onAudioDevicesRemoved(android.media.AudioDeviceInfo[] d) { notifyAll0(); }
                }, Spotify.MAIN);
            }
            android.media.AudioDeviceInfo best = null;
            int bestRank = 0;
            for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
                int r = outputRank(d.getType());
                if (r > bestRank) { bestRank = r; best = d; }
            }
            if (best != null) {
                CharSequence n = best.getProductName();
                String s = n == null ? "" : n.toString().trim();
                // wired jacks report the phone model as product name: call them by what they are
                if (best.getType() == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || best.getType() == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || s.isEmpty() || s.equalsIgnoreCase(android.os.Build.MODEL))
                    s = c.getString(R.string.wired_earphones);
                return s;
            }
        }
        return c.getString(Ui.screenW / Ui.density >= 600 ? R.string.this_device : R.string.this_phone);
    }

    /** Which connected output Android routes media to first (higher wins); 0 = the phone's own speaker. */
    private static int outputRank(int type) {
        switch (type) {
            case android.media.AudioDeviceInfo.TYPE_USB_HEADSET:
            case android.media.AudioDeviceInfo.TYPE_USB_DEVICE: return 5;
            case android.media.AudioDeviceInfo.TYPE_BLE_HEADSET:
            case android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return 4;
            case android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return 3;
            default: return 0;
        }
    }

    /** The context the current Spotify song plays from (playlist, album, …), from /me/player. */
    static String contextUri;

    /**
     * Plays the {@code i}-th entry of a /me/player/queue list. The Web API cannot skip to a queue position, so:
     * inside an album/playlist context restart that context at the song (keeps what follows it), otherwise play the
     * rest of the shown queue as a list starting at the song.
     */
    static void playFromQueue(JSONArray q, int i) {
        JSONObject t = q.optJSONObject(i);
        if (t == null) return;
        String uri = t.optString("uri");
        Track preview = Track.from(t);
        String ctx = contextUri;
        if (ctx != null && (ctx.contains(":playlist:") || ctx.contains(":album:"))) {
            playContext(ctx, uri, preview);
            return;
        }
        List<String> uris = new ArrayList<>();
        for (int k = i; k < q.length(); k++) {
            JSONObject x = q.optJSONObject(k);
            if (x != null && x.optString("uri").startsWith("spotify:")) uris.add(x.optString("uri"));
        }
        if (!uris.isEmpty()) playUris(uris, 0, preview);
    }

    static void playUris(List<String> uris, int index, Track preview) {
        try {
            JSONArray a = new JSONArray();
            for (String u : uris) a.put(u);
            JSONObject b = new JSONObject().put("uris", a).put("offset", new JSONObject().put("position", Math.max(0, index)));
            startPlay(b, preview, index >= 0 && index < uris.size() ? uris.get(index) : null, null);
        } catch (Exception ignored) {}
    }

    private static int playSeq;

    private static void startPlay(JSONObject body, Track preview, String openUri, String fallbackBody) {
        final int seq = ++playSeq;
        final boolean wasPlaying = playing && !local() && !ytm();
        LocalMusic.stopForOther();
        YouTubeMusic.stopForOther();
        if (body != null && body.has("context_uri")) {
            playingSourceUri = body.optString("context_uri", null);
            contextUri = playingSourceUri;
        }
        boolean changed = false;
        if (preview != null) {
            changed = track == null || !preview.id.equals(track.id);
            track = preview;
            if (changed) { liked = false; for (Runnable r : new ArrayList<>(trackListeners)) r.run(); }
        }
        playing = true;
        progress = 0;
        stamp = SystemClock.elapsedRealtime();
        optimistic();
        // play inside Prism (in-app Spotify Connect device) instead of handing off to the Spotify app
        if (SpotifyWeb.web == null && activity instanceof MainActivity) ((MainActivity) activity).startSpotifyPlayer();
        SpotifyWeb.activate();
        SpotifyWeb.whenReady(() -> {
            if (seq != playSeq) return;
            String dev = SpotifyWeb.deviceId;
            SpotifyWeb.switchTrack(wasPlaying, () -> commandPlay(seq, "PUT", "/me/player/play?device_id=" + Spotify.enc(dev), body.toString(), openUri, fallbackBody, true, true));
            deviceId = dev;
            Spotify.MAIN.postDelayed(Player::applyWantedRepeat, 900);
            later();
        }, () -> {
            if (seq != playSeq) return;
            commandPlay(seq, "PUT", "/me/player/play", body.toString(), openUri, fallbackBody, false, true);
            later();
        });
        // defer secondary metadata call so it never competes with the first play request
        if (changed) Spotify.MAIN.postDelayed(Player::checkLiked, 700);
    }

    // ---- repeat survives track/context changes ----
    static String wantedRepeat() { return Spotify.prefs.getString("repeat", "off"); }

    /** Spotify resets repeat when a new context starts; re-apply the user's choice. */
    static void applyWantedRepeat() {
        String w = wantedRepeat();
        repeat = w;
        if (local() || !Spotify.loggedIn()) return;
        Spotify.send("PUT", "/me/player/repeat?state=" + w, null, null);
    }

    private static void later() {
        // When in-app SpotifyWeb SDK is active, onState() delivers the updated track within ~150ms for 0 API calls
        if (!SpotifyWeb.active()) Spotify.MAIN.postDelayed(Player::refresh, 1500);
    }

    /** Forces an immediate /me/player sync, bypassing throttle and optimistic guard, so cover never gets stuck on a failed switch. */
    private static void refreshForce() {
        lastRefresh = 0;
        lastCommand = 0;
        refresh();
    }

    /** Strips any existing device_id query param so recovery never appends duplicate device_id=...&device_id=... */
    static String stripDeviceId(String path) {
        if (path == null) return "/me/player/play";
        int q = path.indexOf('?');
        if (q < 0) return path;
        String base = path.substring(0, q);
        String[] parts = path.substring(q + 1).split("&");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty() || p.startsWith("device_id=")) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(p);
        }
        return sb.length() == 0 ? base : (base + "?" + sb);
    }

    /** High-priority playback command on the dedicated PLAY_IO lane, with URI-list, single-track & device recovery fallbacks. */
    private static void commandPlay(int seq, String method, String path, String body, String openUri, String fallbackBody, boolean allowFastRetry, boolean allowSingleFallback) {
        if (!Spotify.loggedIn()) { toast(R.string.login_needed); return; }
        Spotify.sendPlay(method, path, body, new Spotify.Cb() {
            @Override public void ok(JSONObject o) { }
            @Override public void err(int code, String message) {
                if (seq != playSeq) return;
                if (code == 404) {
                    if (allowFastRetry && SpotifyWeb.ready() && path.contains("device_id=")) {
                        Spotify.MAIN.postDelayed(() -> {
                            if (seq == playSeq) commandPlay(seq, method, path, body, openUri, fallbackBody, false, allowSingleFallback);
                        }, 250);
                    } else if (fallbackBody != null) {
                        // A 404 on context_uri + offset.uri can mean the track isn't matched inside the context: retry with explicit URIs
                        String retryPath = SpotifyWeb.ready() ? ("/me/player/play?device_id=" + Spotify.enc(SpotifyWeb.deviceId)) : "/me/player/play";
                        commandPlay(seq, method, retryPath, fallbackBody, openUri, null, false, allowSingleFallback);
                    } else {
                        recoverNoDevice(method, path, body, openUri);
                    }
                } else if (code == 403 && (!Spotify.isPremium() || String.valueOf(message).toLowerCase().contains("premium"))) {
                    SpotifyWeb.restoreVolume();
                    toast(R.string.premium_needed);
                    refreshForce();
                } else if (code == 401) {
                    SpotifyWeb.restoreVolume();
                    toast(R.string.login_needed);
                    refreshForce();
                } else if (fallbackBody != null) {
                    // Context playback rejected (400/403/5xx): fall back seamlessly to the loaded track URI list!
                    String retryPath = SpotifyWeb.ready() ? ("/me/player/play?device_id=" + Spotify.enc(SpotifyWeb.deviceId)) : "/me/player/play";
                    commandPlay(seq, method, retryPath, fallbackBody, openUri, null, false, allowSingleFallback);
                } else if (allowSingleFallback && openUri != null && (openUri.startsWith("spotify:track:") || openUri.startsWith("spotify:episode:"))) {
                    // Fallback: if a multi-track uris payload was rejected, retry playing just the tapped track
                    try {
                        String singleBody = new JSONObject().put("uris", new JSONArray().put(openUri)).toString();
                        String retryPath = SpotifyWeb.ready() ? ("/me/player/play?device_id=" + Spotify.enc(SpotifyWeb.deviceId)) : "/me/player/play";
                        commandPlay(seq, method, retryPath, singleBody, openUri, null, false, false);
                    } catch (Exception ignored) {
                        SpotifyWeb.restoreVolume();
                        refreshForce();
                    }
                } else {
                    SpotifyWeb.restoreVolume();
                    if (activity != null) {
                        Toast.makeText(activity, activity.getString(R.string.playback_failed_code, code > 0 ? String.valueOf(code) : "net"), Toast.LENGTH_SHORT).show();
                    }
                    refreshForce();
                }
            }
        });
    }

    /** Sends a player command; recovers from "no active device" by transferring or launching Spotify. */
    private static void command(String method, String path, String body, String openUri) {
        if (!Spotify.loggedIn()) { toast(R.string.login_needed); return; }
        Spotify.sendPlay(method, path, body, new Spotify.Cb() {
            @Override public void ok(JSONObject o) { }
            @Override public void err(int code, String message) {
                if (code == 404) {
                    recoverNoDevice(method, path, body, openUri);
                } else if (code == 403 && (!Spotify.isPremium() || String.valueOf(message).toLowerCase().contains("premium"))) {
                    toast(R.string.premium_needed);
                    refresh();
                } else if (code == 401) {
                    toast(R.string.login_needed);
                } else {
                    refresh();
                }
            }
        });
    }

    private static void recoverNoDevice(String method, String path, String body, String openUri) {
        final String cleanPath = stripDeviceId(path);
        Spotify.getPlay("/me/player/devices", new Spotify.Cb() {
            @Override public void ok(JSONObject o) {
                JSONArray ds = o.optJSONArray("devices");
                JSONObject pick = null;
                if (SpotifyWeb.ready()) {
                    // Prefer the in-app player if Spotify actually sees it in /devices, else pick any active non-restricted device
                    if (ds != null) {
                        for (int i = 0; i < ds.length(); i++) {
                            JSONObject d = ds.optJSONObject(i);
                            if (d != null && SpotifyWeb.deviceId.equals(d.optString("id"))) { pick = d; break; }
                        }
                    }
                    if (pick == null) {
                        try { pick = new JSONObject().put("id", SpotifyWeb.deviceId); } catch (Exception ignored) {}
                    }
                }
                if (pick == null && ds != null) {
                    for (int i = 0; i < ds.length(); i++) {
                        JSONObject d = ds.optJSONObject(i);
                        if (d == null || d.optBoolean("is_restricted")) continue;
                        if (pick == null || d.optBoolean("is_active") || "Smartphone".equalsIgnoreCase(d.optString("type"))) pick = d;
                    }
                }
                if (pick != null) {
                    String id = pick.optString("id");
                    String sep = cleanPath.contains("?") ? "&" : "?";
                    Spotify.sendPlay(method, cleanPath + sep + "device_id=" + Spotify.enc(id), body, new Spotify.Cb() {
                        @Override public void ok(JSONObject r) { later(); }
                        @Override public void err(int c, String m) {
                            if (activity != null) Toast.makeText(activity, activity.getString(R.string.playback_failed_code, c > 0 ? String.valueOf(c) : "net"), Toast.LENGTH_SHORT).show();
                            refresh();
                        }
                    });
                } else {
                    // no Spotify app needed: wait for the in-app player instead of launching Spotify
                    toast(SpotifyWeb.failed ? R.string.prism_player_failed : R.string.prism_player_starting);
                    SpotifyWeb.whenReady(() -> {
                        String sep = cleanPath.contains("?") ? "&" : "?";
                        Spotify.sendPlay(method, cleanPath + sep + "device_id=" + Spotify.enc(SpotifyWeb.deviceId), body, new Spotify.Cb() {
                            @Override public void ok(JSONObject r) { later(); }
                        });
                    }, () -> {});
                }
            }
            @Override public void err(int code, String message) {
                if (activity != null) Toast.makeText(activity, activity.getString(R.string.playback_failed_code, code > 0 ? String.valueOf(code) : "net"), Toast.LENGTH_SHORT).show();
            }
        });
    }

    static void openInSpotify(String uri) {
        if (activity == null) return;
        if (uri == null) uri = "spotify:";
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            i.putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://" + activity.getPackageName()));
            i.setPackage("com.spotify.music");
            activity.startActivity(i);
        } catch (ActivityNotFoundException e) {
            String web = "https://open.spotify.com/";
            String[] parts = uri.split(":");
            if (parts.length >= 3) web += parts[1] + "/" + parts[2];
            try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(web))); } catch (Exception ignored) {}
        }
    }

    static void devices(Spotify.Cb cb) { Spotify.get("/me/player/devices", cb); }

    static void transfer(String id) {
        try {
            JSONObject b = new JSONObject().put("device_ids", new JSONArray().put(id)).put("play", playing);
            Spotify.send("PUT", "/me/player", b.toString(), new Spotify.Cb() {
                @Override public void ok(JSONObject o) { later(); }
                @Override public void err(int c, String m) { toast(R.string.playback_failed); }
            });
        } catch (Exception ignored) {}
    }

    static final java.util.Map<String, Boolean> LIKED_CACHE = java.util.Collections.synchronizedMap(new java.util.HashMap<>());
    private static String lastQueueKey;
    private static long lastQueueTime;
    private static JSONObject lastQueueObj;

    static void queue(Spotify.Cb cb) {
        String key = (track == null ? "" : track.id) + "|" + shuffle + "|" + repeat;
        long now = SystemClock.elapsedRealtime();
        if (lastQueueObj != null && key.equals(lastQueueKey) && (now - lastQueueTime < 12000L || Spotify.isRateLimited())) {
            if (cb != null) Spotify.MAIN.post(() -> cb.ok(lastQueueObj));
            return;
        }
        Spotify.get("/me/player/queue", new Spotify.Cb() {
            @Override public void ok(JSONObject o) {
                lastQueueKey = key;
                lastQueueTime = SystemClock.elapsedRealtime();
                lastQueueObj = o;
                if (cb != null) cb.ok(o);
            }
            @Override public void err(int code, String message) {
                if (lastQueueObj != null) { if (cb != null) cb.ok(lastQueueObj); return; }
                if (cb != null) cb.err(code, message);
            }
        });
    }

    static void checkLiked() {
        if (local()) return;
        if (track == null || track.id == null) return;
        final String id = track.id;
        Boolean cached = LIKED_CACHE.get(id);
        if (cached != null) {
            liked = cached;
            notifyAll0();
            return;
        }
        if (Spotify.isRateLimited()) return;
        Spotify.getFirst(new Spotify.Cb() {
            @Override public void ok(JSONObject o) {
                JSONArray a = o.optJSONArray("items");
                if (a != null && a.length() > 0) {
                    boolean val = a.optBoolean(0);
                    LIKED_CACHE.put(id, val);
                    if (track != null && id.equals(track.id)) {
                        liked = val;
                        notifyAll0();
                    }
                }
            }
        }, "/me/library/contains?uris=" + Spotify.enc("spotify:track:" + id), "/me/tracks/contains?ids=" + id);
    }

    static void toggleLike() {
        if (track == null || local()) return;
        final boolean now = !liked;
        liked = now;
        if (track.id != null) LIKED_CACHE.put(track.id, now);
        notifyAll0();
        final String id = track.id;
        Spotify.IO.execute(() -> {
            String m = now ? "PUT" : "DELETE";
            try {
                Spotify.call(m, "/me/library?uris=" + Spotify.enc("spotify:track:" + id), null);
                Spotify.invalidateUserCache();
            } catch (Spotify.ApiError ae) {
                if (ae.code == 429) {
                    Spotify.MAIN.post(() -> { liked = !now; LIKED_CACHE.put(id, !now); notifyAll0(); toast(R.string.action_failed); });
                    return;
                }
                try {
                    Spotify.call(m, "/me/tracks?ids=" + id, null);
                    Spotify.invalidateUserCache();
                } catch (Exception e2) {
                    Spotify.MAIN.post(() -> { liked = !now; LIKED_CACHE.put(id, !now); notifyAll0(); toast(R.string.action_failed); });
                }
            } catch (Exception e) {
                try {
                    Spotify.call(m, "/me/tracks?ids=" + id, null);
                    Spotify.invalidateUserCache();
                } catch (Exception e2) {
                    Spotify.MAIN.post(() -> { liked = !now; LIKED_CACHE.put(id, !now); notifyAll0(); toast(R.string.action_failed); });
                }
            }
        });
    }

    static void toast(int res) {
        if (activity != null) Toast.makeText(activity, res, Toast.LENGTH_SHORT).show();
    }
}
