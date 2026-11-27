package com.oneuibook.prism;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Cross-platform Unified Playlists ("공용 플레이리스트") combining Spotify, YouTube Music, and Local tracks
 * in a single list, with seamless cross-backend playback handoff (Spotify <-> YouTube Music <-> Local).
 */
final class UnifiedPlaylist {
    private UnifiedPlaylist() {}

    static final String SRC_SPOTIFY = "spotify";
    static final String SRC_YTM = "ytm";
    static final String SRC_LOCAL = "local";

    static final int COLOR_SPOTIFY = 0xFF1DB954;
    static final int COLOR_YTM = 0xFFFF0033;

    static final class Entry {
        String source = SRC_SPOTIFY;
        String id = "";
        String uri = "";
        String title = "";
        String artist = "";
        String album = "";
        String art = "";
        String artistUri = "";
        String albumUri = "";
        long durationMs;

        static Entry fromTrack(Player.Track t) {
            if (t == null) return null;
            Entry e = new Entry();
            e.source = sourceOf(t);
            e.id = nz(t.id);
            e.uri = nz(t.uri);
            e.title = nz(t.name);
            e.artist = nz(t.artist);
            e.album = nz(t.album);
            e.art = nz(t.art != null && !t.art.isEmpty() ? t.art : t.artSmall);
            e.artistUri = nz(t.artistUri);
            e.albumUri = nz(t.albumUri);
            e.durationMs = t.duration;
            return e;
        }

        static Entry fromItem(Widgets.Item it) {
            if (it == null) return null;
            if (it.track != null) return fromTrack(it.track);
            if (it.raw != null) {
                Player.Track t = Player.Track.from(it.raw);
                if (t != null) return fromTrack(t);
            }
            if (it.uri == null || it.uri.isEmpty()) return null;
            Entry e = new Entry();
            e.source = sourceOfUri(it.uri);
            e.id = nz(it.id);
            e.uri = nz(it.uri);
            e.title = nz(it.title);
            e.artist = nz(it.subtitle);
            e.art = nz(it.image);
            return e;
        }

        Player.Track toTrack() {
            Player.Track t = new Player.Track();
            t.id = id.isEmpty() ? uri : id;
            t.uri = uri;
            t.name = title;
            t.artist = artist;
            t.album = album;
            t.art = art;
            t.artSmall = art;
            t.artistUri = artistUri.isEmpty() ? null : artistUri;
            t.albumUri = albumUri.isEmpty() ? null : albumUri;
            t.duration = durationMs > 0 ? durationMs : 210_000L;
            return t;
        }

        Widgets.Item toItem() {
            Player.Track tr = toTrack();
            Widgets.Item it = new Widgets.Item();
            it.type = SRC_YTM.equals(source) ? "ytm_track" : (SRC_LOCAL.equals(source) ? "ltrack" : "track");
            it.id = tr.id;
            it.uri = tr.uri;
            it.title = tr.name;
            it.subtitle = tr.artist;
            it.image = tr.art;
            it.track = tr;
            return it;
        }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("source", source)
                    .put("id", id)
                    .put("uri", uri)
                    .put("title", title)
                    .put("artist", artist)
                    .put("album", album)
                    .put("art", art)
                    .put("artistUri", artistUri)
                    .put("albumUri", albumUri)
                    .put("durationMs", durationMs);
        }

        static Entry fromJson(JSONObject o) {
            if (o == null) return null;
            Entry e = new Entry();
            e.source = o.optString("source", SRC_SPOTIFY);
            e.id = o.optString("id", "");
            e.uri = o.optString("uri", "");
            e.title = o.optString("title", "");
            e.artist = o.optString("artist", "");
            e.album = o.optString("album", "");
            e.art = o.optString("art", "");
            e.artistUri = o.optString("artistUri", "");
            e.albumUri = o.optString("albumUri", "");
            e.durationMs = o.optLong("durationMs", 0L);
            if (e.uri.isEmpty()) return null;
            return e;
        }
    }

    static final class Playlist {
        String id;
        String name;
        String desc = "";
        final List<Entry> tracks = new ArrayList<>();

        String coverArt() {
            for (Entry e : tracks) if (e.art != null && !e.art.isEmpty()) return e.art;
            return "";
        }

        JSONObject toJson() throws Exception {
            JSONArray a = new JSONArray();
            for (Entry e : tracks) a.put(e.toJson());
            return new JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("desc", desc)
                    .put("tracks", a);
        }

        static Playlist fromJson(JSONObject o) {
            if (o == null) return null;
            Playlist p = new Playlist();
            p.id = o.optString("id", "");
            p.name = o.optString("name", "");
            p.desc = o.optString("desc", "");
            if (p.id.isEmpty() || p.name.isEmpty()) return null;
            JSONArray a = o.optJSONArray("tracks");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    Entry e = Entry.fromJson(a.optJSONObject(i));
                    if (e != null) p.tracks.add(e);
                }
            }
            return p;
        }
    }

    static String sourceOf(Player.Track t) {
        if (t == null) return SRC_SPOTIFY;
        if (YouTubeMusic.isYtm(t)) return SRC_YTM;
        if (LocalMusic.isLocal(t)) return SRC_LOCAL;
        return SRC_SPOTIFY;
    }

    static String sourceOfUri(String uri) {
        if (uri == null) return SRC_SPOTIFY;
        if (uri.startsWith("ytm:")) return SRC_YTM;
        if (uri.startsWith("content://")) return SRC_LOCAL;
        return SRC_SPOTIFY;
    }

    static int sourceIcon(String source) {
        if (SRC_YTM.equals(source)) return Icons.YTM;
        if (SRC_LOCAL.equals(source)) return Icons.NOTE;
        return Icons.SPOTIFY;
    }

    static int sourceColor(String source) {
        if (SRC_YTM.equals(source)) return COLOR_YTM;
        if (SRC_LOCAL.equals(source)) return Ui.ACCENT;
        return COLOR_SPOTIFY;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ---------------------------------------------------------------- Persistence
    private static final String FILE_NAME = "unified_playlists_v1.json";
    private static List<Playlist> cache;

    private static File storeFile(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    static synchronized List<Playlist> list(Context c) {
        if (c == null) return Collections.emptyList();
        if (cache == null) loadLocked(c);
        return new ArrayList<>(cache);
    }

    static synchronized Playlist get(Context c, String id) {
        if (c == null || id == null) return null;
        if (cache == null) loadLocked(c);
        for (Playlist p : cache) if (id.equals(p.id)) return p;
        return null;
    }

    static synchronized Playlist create(Context c, String name, String desc) {
        if (c == null || name == null || name.trim().isEmpty()) return null;
        if (cache == null) loadLocked(c);
        Playlist p = new Playlist();
        p.id = "upl_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        p.name = name.trim();
        p.desc = desc == null ? "" : desc.trim();
        cache.add(0, p);
        saveLocked(c);
        return p;
    }

    static synchronized boolean rename(Context c, String id, String name, String desc) {
        Playlist p = get(c, id);
        if (p == null || name == null || name.trim().isEmpty()) return false;
        p.name = name.trim();
        p.desc = desc == null ? "" : desc.trim();
        saveLocked(c);
        return true;
    }

    static synchronized boolean delete(Context c, String id) {
        if (c == null || id == null) return false;
        if (cache == null) loadLocked(c);
        for (int i = 0; i < cache.size(); i++) {
            if (id.equals(cache.get(i).id)) {
                cache.remove(i);
                saveLocked(c);
                return true;
            }
        }
        return false;
    }

    static synchronized boolean addEntry(Context c, String id, Entry e) {
        Playlist p = get(c, id);
        if (p == null || e == null || e.uri.isEmpty()) return false;
        p.tracks.add(e);
        saveLocked(c);
        return true;
    }

    static synchronized boolean removeTrack(Context c, String id, int index) {
        Playlist p = get(c, id);
        if (p == null || index < 0 || index >= p.tracks.size()) return false;
        p.tracks.remove(index);
        saveLocked(c);
        return true;
    }

    static synchronized boolean moveTrack(Context c, String id, int from, int to) {
        Playlist p = get(c, id);
        if (p == null || from < 0 || from >= p.tracks.size() || to < 0 || to >= p.tracks.size() || from == to) return false;
        Entry e = p.tracks.remove(from);
        p.tracks.add(to, e);
        saveLocked(c);
        return true;
    }

    static synchronized void resetCacheForTest() {
        cache = null;
        clearActive();
    }

    private static void loadLocked(Context c) {
        cache = new ArrayList<>();
        File f = storeFile(c);
        if (!f.isFile()) return;
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 2 * 1024 * 1024)];
            int n = fis.read(buf);
            if (n <= 0) return;
            JSONArray a = new JSONArray(new String(buf, 0, n, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                Playlist p = Playlist.fromJson(a.optJSONObject(i));
                if (p != null) cache.add(p);
            }
        } catch (Throwable ignored) {}
    }

    private static void saveLocked(Context c) {
        if (c == null || cache == null) return;
        try {
            JSONArray a = new JSONArray();
            for (Playlist p : cache) a.put(p.toJson());
            File f = storeFile(c);
            File tmp = new File(f.getParentFile(), FILE_NAME + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) {
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(a.toString().getBytes(StandardCharsets.UTF_8));
                }
                tmp.delete();
            }
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- Cross-Platform Mixed Queue Playback
    private static boolean active;
    private static boolean dispatching;
    private static String activePlaylistId = "";
    private static String activePlaylistName = "";
    private static final List<Player.Track> queue = new ArrayList<>();
    private static final List<Player.Track> original = new ArrayList<>();
    private static int index;
    private static int playGen;
    private static long spotifyStartStampMs;
    private static String spotifyExpectedId = "";
    private static boolean spotifySawPlaying;

    static boolean isActive() { return active && !queue.isEmpty(); }
    static boolean isDispatching() { return dispatching; }
    static String activePlaylistId() { return activePlaylistId; }
    static List<Player.Track> fullQueue() { return new ArrayList<>(queue); }
    static int currentIndex() { return index; }

    static void clearActive() {
        if (dispatching) return;
        active = false;
        activePlaylistId = "";
        activePlaylistName = "";
        queue.clear();
        original.clear();
        index = 0;
        spotifyExpectedId = "";
        spotifySawPlaying = false;
    }

    static void play(Playlist pl, int startIdx, boolean shuffle) {
        if (pl == null || pl.tracks.isEmpty()) return;
        List<Player.Track> list = new ArrayList<>(pl.tracks.size());
        for (Entry e : pl.tracks) list.add(e.toTrack());
        active = true;
        activePlaylistId = pl.id;
        activePlaylistName = pl.name;
        original.clear();
        original.addAll(list);
        queue.clear();
        queue.addAll(list);
        index = Math.max(0, Math.min(startIdx, queue.size() - 1));
        Player.shuffle = shuffle;
        if (shuffle) shuffleRest();
        playCurrent();
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
        if (!isActive()) return;
        Player.Track cur = queue.get(index);
        if (on) shuffleRest();
        else {
            queue.clear();
            queue.addAll(original);
            index = Math.max(0, queue.indexOf(cur));
        }
    }

    static void jumpTo(int absIndex) {
        if (!isActive() || absIndex < 0 || absIndex >= queue.size()) return;
        index = absIndex;
        playCurrent();
    }

    static void next() {
        if (!isActive()) return;
        index = (index + 1) % queue.size();
        playCurrent();
    }

    static void prev() {
        if (!isActive()) return;
        if (Player.position() > 3000) {
            Player.seek(0);
            return;
        }
        index = (index - 1 + queue.size()) % queue.size();
        playCurrent();
    }

    /** Called by LocalMusic / YouTubeMusic / SpotifyWeb when the current track finishes naturally. */
    static boolean onBackendTrackEnded() {
        if (!isActive()) return false;
        if ("track".equals(Player.repeat)) {
            playCurrent();
            return true;
        }
        if (index + 1 < queue.size()) {
            index++;
            playCurrent();
            return true;
        }
        if ("context".equals(Player.repeat) && !queue.isEmpty()) {
            index = 0;
            playCurrent();
            return true;
        }
        Player.playing = false;
        Player.progress = 0;
        Player.notifyAll0();
        return true;
    }

    /** Detects end-of-single-track in SpotifyWeb SDK when playing a Spotify entry inside a mixed playlist. */
    static void onSpotifyWebState(String trackId, boolean paused, long posMs, long durMs) {
        if (!isActive() || index < 0 || index >= queue.size()) return;
        Player.Track cur = queue.get(index);
        if (!SRC_SPOTIFY.equals(sourceOf(cur))) return;
        if (SystemClock.elapsedRealtime() - spotifyStartStampMs < 1500L) return;
        if (!paused && posMs > 500L && (spotifyExpectedId.isEmpty() || spotifyExpectedId.equals(trackId))) {
            spotifySawPlaying = true;
            return;
        }
        // Spotify Web Playback SDK signals end of a single-URI queue by pausing at position 0 (or near duration)
        if (spotifySawPlaying && paused && (posMs == 0L || (durMs > 5000L && posMs >= durMs - 1500L))) {
            spotifySawPlaying = false;
            final int g = playGen;
            Spotify.MAIN.post(() -> {
                if (g == playGen && isActive()) onBackendTrackEnded();
            });
        }
    }

    private static void playCurrent() {
        if (!isActive() || index < 0 || index >= queue.size()) return;
        playGen++;
        Player.Track t = queue.get(index);
        String src = sourceOf(t);
        String srcUri = "upl:" + activePlaylistId;
        String srcTitle = activePlaylistName;
        dispatching = true;
        try {
            Player.playingSourceUri = srcUri;
            Player.playingSourceTitle = srcTitle;
            if (SRC_YTM.equals(src)) {
                spotifyExpectedId = "";
                spotifySawPlaying = false;
                List<Player.Track> one = new ArrayList<>(1);
                one.add(t);
                YouTubeMusic.play(one, 0, srcUri, srcTitle);
            } else if (SRC_LOCAL.equals(src)) {
                spotifyExpectedId = "";
                spotifySawPlaying = false;
                List<Player.Track> one = new ArrayList<>(1);
                one.add(t);
                LocalMusic.play(one, 0, srcUri, srcTitle);
            } else {
                // Spotify track inside unified playlist
                spotifyStartStampMs = SystemClock.elapsedRealtime();
                spotifyExpectedId = t.id == null ? "" : t.id;
                spotifySawPlaying = false;
                if (!Spotify.loggedIn()) {
                    Player.toast(R.string.login_needed);
                    return;
                }
                List<String> oneUri = Collections.singletonList(t.uri);
                Player.playUris(oneUri, 0, t);
                Player.playingSourceUri = srcUri;
                Player.playingSourceTitle = srcTitle;
            }
        } finally {
            dispatching = false;
        }
    }
}
