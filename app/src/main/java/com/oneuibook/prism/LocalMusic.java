package com.oneuibook.prism;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** On-device music: MediaStore library + a MediaPlayer that needs no Spotify at all. */
final class LocalMusic {
    private LocalMusic() {}

    static Context app;
    static final List<Player.Track> songs = new ArrayList<>();
    static boolean loaded;

    static String permission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    static boolean hasPermission(Context c) { return c.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED; }

    static void requestPermission(Activity a) {
        List<String> p = new ArrayList<>();
        p.add(permission());
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        a.requestPermissions(p.toArray(new String[0]), 41);
    }

    static boolean isLocal(Player.Track t) { return t != null && t.uri != null && t.uri.startsWith("content://"); }

    private static boolean loading;
    private static final List<Runnable> afterLoad = new ArrayList<>();

    /**
     * Loads the library on a background thread (one MediaStore query, no per-file work). Calls made while a load is
     * running just wait for it instead of starting another.
     */
    static void load(Context c, Runnable done) {
        app = c.getApplicationContext();
        if (!hasPermission(c)) { if (done != null) done.run(); return; }
        if (done != null) afterLoad.add(done);
        if (loading) return;
        loading = true;
        Spotify.IO.execute(() -> {
            List<Player.Track> l = new ArrayList<>();
            String[] cols = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.ALBUM_ARTIST};
            boolean merge = mergeDiscs(app);
            try (Cursor cur = app.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                    MediaStore.Audio.Media.IS_MUSIC + "!=0", null, MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
                if (cur != null) while (cur.moveToNext()) {
                    long id = cur.getLong(0);
                    Player.Track t = new Player.Track();
                    Uri u = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                    t.id = "local:" + id;
                    t.uri = u.toString();
                    t.name = nz(cur.getString(1), "?");
                    String ar = cur.getString(2);
                    t.artist = ar == null || "<unknown>".equals(ar) ? app.getString(R.string.unknown_artist) : ar;
                    t.album = nz(cur.getString(3), "");
                    t.albumId = cur.getLong(4);
                    t.artistUri = "lartist:" + t.artist;
                    t.duration = cur.getLong(5);
                    t.added = cur.getLong(6);
                    int tr = cur.isNull(7) ? 0 : cur.getInt(7);
                    t.disc = tr >= 1000 ? tr / 1000 : 0;     // MediaStore packs disc × 1000 + track
                    t.trackNo = tr % 1000;
                    String aa = cur.isNull(8) ? null : cur.getString(8);
                    keyAlbum(t, aa, merge);
                    l.add(t);
                }
            } catch (Exception ignored) {}
            pickRepresentatives(l);
            for (Player.Track t : l) setArt(t);
            Spotify.MAIN.post(() -> {
                songs.clear();
                songs.addAll(l);
                loaded = true;
                loading = false;
                List<Runnable> cbs = new ArrayList<>(afterLoad);
                afterLoad.clear();
                for (Runnable r : cbs) r.run();
            });
        });
    }

    /** Reads the library again (e.g. after the disc-merge setting changed). */
    static void reload(Context c, Runnable done) {
        loaded = false;
        load(c, done);
    }

    // ---- multi-disc albums: "Name Disc 1" + "Name Disc 2" → one album "Name" ----
    static boolean mergeDiscs(Context c) { return MainActivity.prefs(c).getBoolean("merge_discs", true); }

    /**
     * Trailing disc marker: "Disc 2", "Disk2", "CD 1", "디스크 2", "Disc II", optionally in () [] or after
     * a dash / colon. Group 1 = the album name without it, group 2 = the number (digits or I–X).
     */
    static final java.util.regex.Pattern DISC = java.util.regex.Pattern.compile(
            "^(.*?)[\\s\\-–—:_,]*[\\(\\[]?\\s*(?:disc|disk|cd|디스크)\\s*[\\.#]?\\s*(\\d{1,2}|[ivx]{1,4})\\s*[\\)\\]]?\\s*$",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    /** {base name, disc number} if the album name ends in a disc marker, else null. */
    static Object[] splitDisc(String album) {
        if (album == null) return null;
        java.util.regex.Matcher m = DISC.matcher(album.trim());
        if (!m.matches()) return null;
        String base = m.group(1).trim();
        if (base.isEmpty()) return null;
        String n = m.group(2);
        int disc;
        if (n.matches("\\d+")) disc = Integer.parseInt(n);
        else disc = roman(n.toUpperCase(java.util.Locale.ROOT));
        return disc <= 0 ? null : new Object[]{base, disc};
    }

    private static int roman(String s) {
        int total = 0, prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            char ch = s.charAt(i);
            int v = ch == 'I' ? 1 : ch == 'V' ? 5 : ch == 'X' ? 10 : 0;
            if (v < prev) total -= v; else { total += v; prev = v; }
        }
        return total;
    }

    /**
     * Album key used for grouping. Plain albums: "lalbum:<MediaStore id>". Disc albums (merging on): one key for the
     * base name + album artist, so every disc lands in the same album; the disc number fills in if the tags lack it.
     */
    static void keyAlbum(Player.Track t, String albumArtist, boolean merge) {
        Object[] d = merge ? splitDisc(t.album) : null;
        if (d == null) {
            t.albumUri = "lalbum:" + t.albumId;
            t.albumGroup = null;
            return;
        }
        String base = (String) d[0];
        if (t.disc == 0) t.disc = (Integer) d[1];
        String who = albumArtist == null || albumArtist.isEmpty() || "<unknown>".equals(albumArtist) ? "" : albumArtist;
        t.albumUri = "lalbumx:" + base.toLowerCase(java.util.Locale.ROOT) + "\u0001" + who.toLowerCase(java.util.Locale.ROOT);
        t.albumGroup = base;
    }

    /** Album key → the track whose artwork stands for the album (first disc, first track). */
    private static final Map<String, Player.Track> reps = new java.util.concurrent.ConcurrentHashMap<>();

    private static void pickRepresentatives(List<Player.Track> l) {
        reps.clear();
        for (Player.Track t : l) {
            Player.Track r = reps.get(t.albumUri);
            if (r == null || order(t) < order(r)) reps.put(t.albumUri, t);
        }
    }

    private static long order(Player.Track t) {
        return (t.disc == 0 ? 0 : t.disc) * 10000L + (t.trackNo == 0 ? 9999 : t.trackNo);
    }

    // ---- artwork: one image per album (not per song), or the user's own cover ----
    /**
     * Where the user's cover for an album is kept. Plain albums keep the old "<id>.jpg" name (covers picked in
     * earlier versions still work); merged disc albums use a hash of their key.
     */
    static File coverFile(String albumKey) {
        String name;
        if (albumKey.startsWith("lalbum:")) name = albumKey.substring(7) + ".jpg";
        else name = "x_" + Integer.toHexString(albumKey.hashCode()) + "_" + albumKey.length() + ".jpg";
        return new File(new File(app.getFilesDir(), "covers"), name);
    }

    /**
     * Album art key for the image pipeline: the user's cover file if set, else the MediaStore thumbnail of the
     * album's representative song (one key, decoded once, shared by every song of the album, all discs included).
     */
    static void setArt(Player.Track t) {
        File f = coverFile(t.albumUri);
        String a;
        if (f.exists()) a = "file://" + f.getAbsolutePath() + "?v=" + f.lastModified();
        else {
            Player.Track r = reps.get(t.albumUri);
            if (r == null) r = t;
            a = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, r.albumId) + "#song=" + r.uri;
        }
        t.art = a;
        t.artSmall = a;
    }

    /** Called on the main thread with (old art key, new art key) when an album's cover changes. */
    static java.util.function.BiConsumer<String, String> artChanged;

    /** Uses an image the user picked as the cover of the whole album (every song, the player and the notification). */
    static boolean setCover(String albumKey, Uri image) {
        try {
            File out = coverFile(albumKey);
            out.getParentFile().mkdirs();
            android.graphics.ImageDecoder.Source src = android.graphics.ImageDecoder.createSource(app.getContentResolver(), image);
            Bitmap b = android.graphics.ImageDecoder.decodeBitmap(src, (dec, info, s) -> {
                int max = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                if (max > 1200) dec.setTargetSampleSize(Math.max(1, max / 1200));
                dec.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
            });
            // square centre crop, like every other cover
            int s = Math.min(b.getWidth(), b.getHeight());
            Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - s) / 2, (b.getHeight() - s) / 2, s, s);
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(out)) { sq.compress(Bitmap.CompressFormat.JPEG, 92, o); }
            // decode the new cover off the main thread too, so the swap on screen is instant
            Img.prefetch("file://" + out.getAbsolutePath() + "?v=" + out.lastModified());
            Spotify.MAIN.post(() -> refreshArt(albumKey));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void clearCover(String albumKey) {
        File f = coverFile(albumKey);
        if (f.exists()) f.delete();
        refreshArt(albumKey);
    }

    static boolean hasCover(String albumKey) { return coverFile(albumKey).exists(); }

    /**
     * Main thread. Re-points every song of the album at the new art key and tells the screen to swap images in place
     * (no page rebuilds: rebuilding every page at once is what froze the home screen).
     */
    static void refreshArt(String albumKey) {
        String old = null;
        for (Player.Track t : songs) if (albumKey.equals(t.albumUri)) { if (old == null) old = t.art; setArt(t); }
        for (Player.Track t : queue) if (albumKey.equals(t.albumUri)) setArt(t);
        String now = null;
        for (Player.Track t : songs) if (albumKey.equals(t.albumUri)) { now = t.art; break; }
        Player.Track cur = Player.track;
        if (cur != null && albumKey.equals(cur.albumUri)) {
            setArt(cur);
            if (now == null) now = cur.art;
            for (Runnable r : new ArrayList<>(Player.trackListeners)) r.run();
            Player.notifyAll0();
        }
        if (artChanged != null && old != null && now != null && !old.equals(now)) artChanged.accept(old, now);
    }

    private static String nz(String s, String d) { return s == null ? d : s; }

    /** Groups songs by album (key = albumUri) or artist (key = artistUri), preserving recency. */
    static Map<String, List<Player.Track>> group(boolean byAlbum) {
        Map<String, List<Player.Track>> m = new LinkedHashMap<>();
        for (Player.Track t : songs) {
            String k = byAlbum ? t.albumUri : t.artistUri;
            List<Player.Track> l = m.get(k);
            if (l == null) { l = new ArrayList<>(); m.put(k, l); }
            l.add(t);
        }
        return m;
    }

    static List<Player.Track> tracksFor(String key) {
        List<Player.Track> l = new ArrayList<>();
        for (Player.Track t : songs) if (key.equals(t.albumUri) || key.equals(t.artistUri)) l.add(t);
        return l;
    }

    static Widgets.Item item(Player.Track t) {
        Widgets.Item i = new Widgets.Item();
        i.type = "ltrack";
        i.id = t.id;
        i.uri = t.uri;
        i.title = t.name;
        i.subtitle = t.artist;
        i.image = t.artSmall;
        i.track = t;
        return i;
    }

    static Widgets.Item group(String key, List<Player.Track> l, boolean album) {
        Widgets.Item i = new Widgets.Item();
        Player.Track f = l.get(0);
        i.type = album ? "lalbum" : "lartist";
        i.id = key;
        i.uri = key;
        i.title = album ? (f.albumGroup != null ? f.albumGroup : f.album.isEmpty() ? f.name : f.album) : f.artist;
        i.subtitle = album ? f.artist : "";
        i.image = f.art;
        i.count = l.size();
        return i;
    }

    // ================================================================ playback engine
    static MediaPlayer mp;
    static final List<Player.Track> queue = new ArrayList<>();
    static final List<Player.Track> original = new ArrayList<>();
    static int index;
    private static AudioManager am;
    private static AudioFocusRequest focus;
    static MediaSession session;

    static void play(List<Player.Track> list, int idx) {
        play(list, idx, null, null);
    }

    static void play(List<Player.Track> list, int idx, String sourceUri, String sourceTitle) {
        if (list.isEmpty()) return;
        // switching from Spotify / YouTube Music: pause them so two engines never play together
        if (!Player.local() && !Player.ytm() && Player.playing && Spotify.loggedIn()) Spotify.send("PUT", "/me/player/pause", null, null);
        YouTubeMusic.stopForOther();
        Player.playingSourceUri = sourceUri;
        Player.playingSourceTitle = sourceTitle;
        Player.repeat = Spotify.prefs.getString("repeat", "off");
        original.clear();
        original.addAll(list);
        queue.clear();
        queue.addAll(list);
        index = Math.max(0, Math.min(idx, list.size() - 1));
        if (Player.shuffle) shuffleRest();
        start(0);
    }

    private static void shuffleRest() {
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
        Player.Track t = queue.get(index);
        release();
        // bit-perfect / direct USB output must be set before the player opens its stream
        try { BitPerfect.prepare(app, Uri.parse(t.uri)); } catch (Exception ignored) {}
        mp = new MediaPlayer();
        boolean bpBypass = BitPerfect.enabled(app) && BitPerfect.isActive();
        if (bpBypass) {
            // Attaching AudioEffect (EQ/NPU) forces AudioFlinger out of BIT_PERFECT / direct USB into 48kHz mixer!
            try { Eq.release(); } catch (Throwable ignored) {}
            try { NpuAudio.release(); } catch (Throwable ignored) {}
            try { mp.setPreferredDevice(BitPerfect.activeDevice); } catch (Throwable ignored) {}
        } else {
            // 10-band equalizer & Bit-Perfect Assist ride on this player's audio session
            try { Eq.attach(app, mp.getAudioSessionId()); } catch (Throwable ignored) {}
            try { NpuAudio.attachLocalSession(app, mp.getAudioSessionId()); } catch (Throwable ignored) {}
            applyChannelBalance();
        }
        mp.setWakeMode(app, android.os.PowerManager.PARTIAL_WAKE_LOCK);
        mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        try {
            mp.setDataSource(app, Uri.parse(t.uri));
        } catch (Exception e) {
            Player.toast(R.string.playback_failed);
            return;
        }
        mp.setOnPreparedListener(p -> {
            if (seekTo > 0) p.seekTo((int) seekTo);
            if (requestFocus()) p.start();
            sync();
        });
        mp.setOnCompletionListener(p -> onComplete());
        mp.setOnErrorListener((p, w, x) -> { Player.toast(R.string.playback_failed); return true; });
        mp.prepareAsync();
        boolean changed = Player.track == null || !t.id.equals(Player.track.id);
        Player.track = t;
        Player.playing = true;
        Player.progress = seekTo;
        Player.stamp = SystemClock.elapsedRealtime();
        Player.deviceName = app.getString(R.string.this_device);
        Player.liked = false;
        if (changed) {
            Recap.recordPlay(app, t);
            for (Runnable r : new ArrayList<>(Player.trackListeners)) r.run();
        }
        Player.notifyAll0();
        PlaybackService.update(app);
    }

    static void applyChannelBalance() {
        if (mp == null || app == null) return;
        if (BitPerfect.enabled(app) && BitPerfect.isActive()) return;
        float[] lr = BitPerfect.channelGains(app);
        try { mp.setVolume(lr[0], lr[1]); } catch (Throwable ignored) {}
    }

    private static void onComplete() {
        if ("track".equals(Player.repeat)) { start(0); return; }
        if (index + 1 < queue.size()) { index++; start(0); }
        else if ("context".equals(Player.repeat)) { index = 0; start(0); }
        else if (Spotify.prefs.getBoolean("autoplay", true) && songs.size() > 1) {
            // autoplay: keep going with the rest of the library instead of stopping
            List<Player.Track> rest = new ArrayList<>(songs);
            Collections.shuffle(rest, new Random());
            queue.addAll(rest);
            index++;
            start(0);
        }
        else { Player.playing = false; Player.progress = 0; sync(); }
    }

    private static boolean requestFocus() {
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

    /** Copies MediaPlayer state into the shared Player fields and refreshes UI + notification. */
    /** Hand-off to Spotify: stop the device player completely and give up audio focus. */
    static void stopForOther() {
        if (mp == null) return;
        release();
        if (am != null && focus != null) am.abandonAudioFocusRequest(focus);
        // Spotify plays through the normal mixer: give the USB output back
        try { BitPerfect.clear(app); } catch (Exception ignored) {}
    }

    static void sync() {
        // only report into the shared player state while an on-device song is the current one
        if (!isLocal(Player.track)) return;
        if (mp != null) {
            try {
                Player.playing = mp.isPlaying();
                Player.progress = mp.getCurrentPosition();
            } catch (IllegalStateException ignored) {}
        }
        Player.stamp = SystemClock.elapsedRealtime();
        Player.notifyAll0();
        PlaybackService.update(app);
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

    /** Reopens the current on-device song at its position (output format changes apply to new streams only). */
    static void reopen() {
        if (mp == null || queue.isEmpty() || !isLocal(Player.track)) return;
        boolean was = Player.playing;
        long at = Player.position();
        start(at);
        if (!was) {
            // keep it paused: start() plays once prepared
            mp.setOnPreparedListener(p -> { p.seekTo((int) at); Player.playing = false; sync(); });
        }
    }

    /** Jumps to the {@code i}-th song of {@link #upNext()}. */
    static void jumpUpNext(int i) {
        int to = index + 1 + i;
        if (i < 0 || to >= queue.size()) return;
        index = to;
        start(0);
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

    static List<Player.Track> upNext() {
        List<Player.Track> l = new ArrayList<>();
        for (int i = index + 1; i < queue.size() && l.size() < 50; i++) l.add(queue.get(i));
        return l;
    }

    private static void release() {
        if (mp != null) { try { mp.release(); } catch (Exception ignored) {} mp = null; }
    }

    // ================================================================ foreground service + media notification
    public static final class PlaybackService extends Service {
        static final String CH = "playback";
        static PlaybackService running;

        /** Spotify playing through Prism's own in-app device. */
        static boolean inApp() { return SpotifyWeb.ready() && (SpotifyWeb.deviceId.equals(Player.deviceId) || Player.deviceId == null); }

        private android.os.PowerManager.WakeLock wake;

        static void update(Context c) {
            if (c == null || Player.track == null || !(isLocal(Player.track) || YouTubeMusic.isYtm(Player.track) || inApp())) return;
            Intent i = new Intent(c, PlaybackService.class);
            try {
                if (running == null) c.startForegroundService(i); else running.post();
            } catch (Exception ignored) {}
        }

        /**
         * Re-asserts Prism's foreground MediaSession as the top-priority active session (both now and after
         * a short settle delay) so Chromium WebView's internal audio session or the official Spotify app
         * cannot hijack background media / hardware volume controls.
         */
        static void claimControl(Context c) {
            if (c == null || Player.track == null || !(isLocal(Player.track) || YouTubeMusic.isYtm(Player.track) || inApp())) return;
            update(c);
            Spotify.MAIN.postDelayed(() -> {
                PlaybackService s = running;
                if (s == null || session == null || Player.track == null) return;
                if (!(isLocal(Player.track) || YouTubeMusic.isYtm(Player.track) || inApp())) return;
                try {
                    session.setPlaybackToLocal(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                    if (Player.playing) {
                        session.setActive(false);
                        session.setActive(true);
                    }
                    s.post();
                } catch (Throwable ignored) {}
            }, 260);
        }

        @Override public void onCreate() {
            super.onCreate();
            running = this;
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CH, getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW));
            session = new MediaSession(this, "prism");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            // always route hardware volume keys to local STREAM_MUSIC (never a remote Spotify Connect speaker)
            session.setPlaybackToLocal(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            session.setCallback(new MediaSession.Callback() {
                // route through Player so both on-device and in-app Spotify playback respond
                @Override public void onPlay() { if (!Player.playing) Player.toggle(); }
                @Override public void onPause() { if (Player.playing) Player.toggle(); }
                @Override public void onSkipToNext() { Player.next(); }
                @Override public void onSkipToPrevious() { Player.prev(); }
                @Override public void onSeekTo(long pos) { Player.seek(pos); }
                // shuffle / repeat buttons beside the controls in the system media player (live activity)
                @Override public void onCustomAction(String action, android.os.Bundle extras) {
                    if (A_SHUFFLE.equals(action)) Player.toggleShuffle();
                    else if (A_REPEAT.equals(action)) Player.cycleRepeat();
                    post();
                }            });
            session.setActive(true);
        }

        @Override public int onStartCommand(Intent intent, int flags, int startId) {
            String a = intent == null ? null : intent.getAction();
            if ("toggle".equals(a)) Player.toggle();
            else if ("next".equals(a)) Player.next();
            else if ("prev".equals(a)) Player.prev();
            else if (A_SHUFFLE.equals(a)) Player.toggleShuffle();
            else if (A_REPEAT.equals(a)) Player.cycleRepeat();
            else if ("dismiss".equals(a)) {
                // paused notification swiped away with the app closed: release the player as well
                if (!uiOpen() && !Player.playing) { shutDown(); return START_NOT_STICKY; }
                return START_NOT_STICKY;
            }
            post();
            return START_NOT_STICKY;
        }

        private PendingIntent action(String a, int code) {
            return PendingIntent.getService(this, code, new Intent(this, PlaybackService.class).setAction(a), PendingIntent.FLAG_IMMUTABLE);
        }

        static final String A_SHUFFLE = "prism.shuffle", A_REPEAT = "prism.repeat";

        private int shuffleIcon() { return Player.shuffle ? R.drawable.ic_stat_shuffle_on : R.drawable.ic_stat_shuffle_off; }
        private int repeatIcon() {
            return "track".equals(Player.repeat) ? R.drawable.ic_stat_repeat_one
                    : "off".equals(Player.repeat) ? R.drawable.ic_stat_repeat_off : R.drawable.ic_stat_repeat_on;
        }
        private String shuffleLabel() { return getString(Player.shuffle ? R.string.shuffle_on : R.string.shuffle_off); }
        private String repeatLabel() {
            return getString("track".equals(Player.repeat) ? R.string.repeat_one : "off".equals(Player.repeat) ? R.string.repeat_off : R.string.repeat_all);
        }

        void post() {
            Player.Track t = Player.track;
            if (t == null) return;
            Bitmap art = Img.cached(t.art);
            if (art == null && t.art != null) Img.load(t.art, b -> { if (b != null && Player.track == t) post(); });
            holdWake(Player.playing && (inApp() || YouTubeMusic.isYtm(Player.track)));
            // keep local STREAM_MUSIC volume routing active when backgrounded during in-app Spotify playback
            session.setPlaybackToLocal(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            if (!session.isActive()) session.setActive(true);
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, t.name)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, t.duration)
                    .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art).build());
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
                    .setState(Player.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, Player.position(), Player.playing ? 1f : 0f)
                    // Android 13+ media controls (and One UI's Now Bar) place up to two custom actions beside
                    // previous / play / next: shuffle on the left, repeat on the right
                    .addCustomAction(new PlaybackState.CustomAction.Builder(A_SHUFFLE, shuffleLabel(), shuffleIcon()).build())
                    .addCustomAction(new PlaybackState.CustomAction.Builder(A_REPEAT, repeatLabel(), repeatIcon()).build())
                    .build());
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, CH)
                    .setSmallIcon(R.drawable.ic_stat_prism)
                    .setContentTitle(t.name)
                    .setContentText(t.artist)
                    .setLargeIcon(art)
                    .setContentIntent(open)
                    .setDeleteIntent(action("dismiss", 6))
                    .setOngoing(Player.playing)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, shuffleIcon()), shuffleLabel(), action(A_SHUFFLE, 4)).build())
                    .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_prev), "prev", action("prev", 1)).build())
                    .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, Player.playing ? R.drawable.ic_stat_pause : R.drawable.ic_stat_play), Player.playing ? "pause" : "play", action("toggle", 2)).build())
                    .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_next), "next", action("next", 3)).build())
                    .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, repeatIcon()), repeatLabel(), action(A_REPEAT, 5)).build())
                    .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(1, 2, 3))
                    .build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(7, n);
            if (!Player.playing) stopForeground(STOP_FOREGROUND_DETACH);
        }

        /** The WebView player needs the CPU kept awake while the screen is off. */
        private void holdWake(boolean on) {
            if (on && wake == null) {
                wake = ((android.os.PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "prism:playback");
                wake.acquire(6 * 60 * 60 * 1000L);
            } else if (!on && wake != null) {
                if (wake.isHeld()) wake.release();
                wake = null;
            }
        }

        /** Ends playback controls everywhere (notification, lock screen, Now Bar): nothing is playing any more. */
        static void stopNow() {
            PlaybackService s = running;
            if (s == null) return;
            s.holdWake(false);
            try { if (session != null) session.setActive(false); } catch (Exception ignored) {}
            s.stopForeground(STOP_FOREGROUND_REMOVE);
            s.stopSelf();
        }

        /** True while an app window exists; without one the service alone owns playback. */
        static boolean uiOpen() { return Player.activity != null; }

        /**
         * App closed from Recents. Still playing: keep going (the in-app Spotify player and MediaPlayer live in this
         * process, held by this foreground service). Paused: nothing left to control, so close everything.
         */
        @Override public void onTaskRemoved(Intent rootIntent) {
            super.onTaskRemoved(rootIntent);
            if (!Player.playing) shutDown();
        }

        /** Stops the player sources too, so no control is left pointing at a dead player. */
        static void shutDown() {
            try { if (isLocal(Player.track)) { stopForOther(); Player.playing = false; } } catch (Exception ignored) {}
            try { if (YouTubeMusic.isYtm(Player.track)) { YouTubeMusic.stopForOther(); Player.playing = false; } } catch (Exception ignored) {}
            if (SpotifyWeb.web != null && !uiOpen()) SpotifyWeb.stop();
            if (YouTubeMusic.web != null && !uiOpen()) YouTubeMusic.stopWeb();
            stopNow();
        }

        @Override public void onDestroy() {
            holdWake(false);
            running = null;
            if (session != null) session.release();
            super.onDestroy();
        }

        @Override public IBinder onBind(Intent intent) { return null; }
    }
}
