package com.oneuibook.prism;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.os.Looper;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class PrismUiTest {
    static final File OUT = new File(System.getProperty("prism.output"));

    private ActivityController<MainActivity> launch(String qualifiers) {
        RuntimeEnvironment.setQualifiers(qualifiers);
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class, new Intent(Intent.ACTION_MAIN)).setup().visible();
        idle(600);
        return c;
    }

    private static Widgets.Toggle findToggle(View v) {
        if (v instanceof Widgets.Toggle) return (Widgets.Toggle) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) { Widgets.Toggle t = findToggle(g.getChildAt(i)); if (t != null) return t; }
        }
        return null;
    }

    /** True if some artwork view under {@code v} shows {@code url}. */
    private static boolean findArt(View v, String url) {
        if (v instanceof Img.ArtView && url.equals(((Img.ArtView) v).url)) return true;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) if (findArt(g.getChildAt(i), url)) return true;
        }
        return false;
    }

    private static void idle(long ms) {
        for (long t = 0; t < ms; t += 16) Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
    }

    private static void shot(MainActivity a, String name) throws Exception {
        View d = a.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(d.getWidth(), d.getHeight(), Bitmap.Config.ARGB_8888);
        d.draw(new Canvas(b));
        OUT.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(OUT, name + ".png"))) { assertTrue(b.compress(Bitmap.CompressFormat.PNG, 100, o)); }
    }

    private static void captureAnim(MainActivity a, String name, int frames, int stepMs) throws Exception {
        File dir = new File(new File(OUT, "frames"), name);
        if (dir.exists()) { File[] old = dir.listFiles(); if (old != null) for (File f : old) f.delete(); }
        dir.mkdirs();
        View d = a.getWindow().getDecorView();
        int W = d.getWidth(), H = d.getHeight();
        int w = (W / 2) & ~1, h = (H / 2) & ~1; // even dimensions for H.264 / 60fps video encoding (590x1278)
        Bitmap full = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas fc = new Canvas(full);
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(stepMs));
        try {
            for (int i = 0; i < frames; i++) {
                d.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
                d.layout(0, 0, W, H);
                full.eraseColor(0);
                d.draw(fc);
                Bitmap scaled = Bitmap.createScaledBitmap(full, w, h, true);
                try (FileOutputStream o = new FileOutputStream(new File(dir, String.format("f_%03d.png", i)))) {
                    scaled.compress(Bitmap.CompressFormat.PNG, 90, o);
                }
                scaled.recycle();
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(stepMs));
            }
        } finally {
            org.robolectric.shadows.ShadowChoreographer.setPaused(false);
            org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(1));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            full.recycle();
        }
    }

    private static void fakeTrack() {
        Player.Track t = new Player.Track();
        t.id = "x"; t.uri = "spotify:track:x"; t.name = "Supernova Love"; t.artist = "IVE, David Guetta"; t.album = "Supernova Love";
        t.duration = 192000;
        Player.track = t;
        Player.playing = true;
        Player.progress = 61000;
        Player.stamp = android.os.SystemClock.elapsedRealtime();
        Player.notifyAll0();
        for (Runnable r : new java.util.ArrayList<>(Player.trackListeners)) r.run();
    }

    @Test public void shaderCompiles() throws Exception {
        String result;
        try {
            new RuntimeShader(Glass.SHADER);
            result = "eval syntax: OK";
        } catch (Throwable t) {
            result = "eval syntax: FAIL: " + t;
        }
        // Robolectric's host Skia may predate `shader.eval()`; compile the legacy spelling to check everything else
        try {
            new RuntimeShader(Glass.SHADER.replace("content.eval(at)", "sample(content, at)"));
            result += "\nlegacy sample() syntax: OK";
        } catch (Throwable t) {
            result += "\nlegacy sample() syntax: FAIL: " + t;
        }
        try {
            new RuntimeShader("uniform shader s; half4 main(float2 p) { return s.eval(p); }");
            result += "\nminimal eval: OK";
        } catch (Throwable t) {
            result += "\nminimal eval: FAIL: " + t;
        }
        OUT.mkdirs();
        try (PrintWriter w = new PrintWriter(new File(OUT, "shader.txt"), "UTF-8")) { w.println(result); }
    }

    @Test public void screensKorean() throws Exception {
        ActivityController<MainActivity> c = launch("ko-w393dp-h852dp-xxhdpi");
        MainActivity a = c.get();
        assertEquals("홈", a.getString(R.string.tab_home));
        shot(a, "01_home_signed_out_ko");
        fakeTrack();
        idle(700);
        shot(a, "02_home_mini_player");
        a.onTab(1, false); idle(700); shot(a, "03_explore");
        a.onTab(2, false); idle(700); shot(a, "04_library");
        a.onTab(3, false); idle(700); shot(a, "05_settings");
        a.push(new Settings.AccountPage(a)); idle(800); shot(a, "06_account");
        a.pop(); idle(600);
        a.push(new Settings.AppearancePage(a)); idle(800); shot(a, "15_appearance");
        a.pop(); idle(600);
        a.push(new Settings.PlaybackPage(a)); idle(800); shot(a, "16_playback");
        a.pop(); idle(600);
        // a home-section switch must not throw the user out of the settings page
        Settings.ContentPage cp = new Settings.ContentPage(a);
        a.push(cp); idle(800);
        Widgets.Toggle tg = findToggle(cp);
        assertNotNull(tg);
        tg.change.accept(false); idle(600);
        assertTrue("settings page still shown after toggle", cp.getParent() != null && cp.getVisibility() == View.VISIBLE);
        tg.change.accept(true); idle(400);
        a.pop(); idle(600);
        a.onTab(0, false); idle(700);
        a.dock.setCollapsed(true); idle(700); shot(a, "07_dock_collapsed");
        a.dock.setCollapsed(false); idle(700);
        a.onSearch(); idle(800); shot(a, "08_search");
        a.onExitSearch(); idle(800);
        RectF anchor = a.header.pill;
        float[] o = Glass.abs(a.header);
        RectF ar = new RectF(anchor); ar.offset(o[0], o[1]);
        a.onHeaderButton(Icons.QUEUE, ar); idle(900); shot(a, "09_menu");
        a.onBackPressed(); idle(900);
        a.np.open(); idle(250); shot(a, "10_player_opening");
        idle(900); shot(a, "11_player");
        {
            Bitmap only = Bitmap.createBitmap(a.np.getWidth(), a.np.getHeight(), Bitmap.Config.ARGB_8888);
            a.np.draw(new Canvas(only));
            try (PrintWriter w = new PrintWriter(new File(OUT, "np_debug.txt"), "UTF-8")) {
                w.println("openP=" + a.np.openP + " alpha=" + a.np.getAlpha() + " vis=" + a.np.getVisibility()
                        + " px(10,1500)=" + Integer.toHexString(only.getPixel(10, 1500)) + " px(600,2200)=" + Integer.toHexString(only.getPixel(600, 2200))
                        + " rootChildren=" + a.root.getChildCount());
                for (int i = 0; i < a.root.getChildCount(); i++) w.println(" child " + i + " " + a.root.getChildAt(i).getClass().getSimpleName() + " vis=" + a.root.getChildAt(i).getVisibility());
            }
        }
        Method m = NowPlaying.class.getDeclaredMethod("setPanel", int.class);
        m.setAccessible(true);
        m.invoke(a.np, 2); idle(900); shot(a, "12_player_queue");
        m.invoke(a.np, 0); idle(900);
        a.np.close(); idle(200); shot(a, "13_player_closing");
        idle(900); shot(a, "14_closed");
    }

    // ------------------------------------------------------------ logged-in screens from fixtures
    private static org.json.JSONObject track(int i) throws Exception {
        return new org.json.JSONObject().put("type", "track").put("id", "t" + i).put("uri", "spotify:track:t" + i)
                .put("name", new String[]{"Supernova", "Magnetic", "Love wins all", "Whiplash", "APT.", "Drama"}[i % 6] + " " + i)
                .put("duration_ms", 180000 + i * 1000)
                .put("artists", new org.json.JSONArray().put(new org.json.JSONObject().put("name", new String[]{"aespa", "ILLIT", "IU", "ROSÉ"}[i % 4]).put("uri", "spotify:artist:a" + i)))
                .put("album", new org.json.JSONObject().put("type", "album").put("id", "al" + i).put("uri", "spotify:album:al" + i).put("name", "Album " + i)
                        .put("images", new org.json.JSONArray()).put("artists", new org.json.JSONArray().put(new org.json.JSONObject().put("name", "Artist " + i))));
    }

    private static org.json.JSONObject playlist(int i) throws Exception {
        return new org.json.JSONObject().put("type", "playlist").put("id", "p" + i).put("uri", "spotify:playlist:p" + i)
                .put("name", new String[]{"드라이브 K-POP", "새벽 감성", "운동할 때", "Chill R&B", "Focus"}[i % 5])
                .put("description", "플레이리스트 설명 " + i).put("images", new org.json.JSONArray())
                .put("owner", new org.json.JSONObject().put("display_name", "지호")).put("tracks", new org.json.JSONObject().put("total", 20 + i));
    }

    private static org.json.JSONObject fixture(String req) {
        try {
            org.json.JSONArray tracks = new org.json.JSONArray(), pls = new org.json.JSONArray(), recent = new org.json.JSONArray(), artists = new org.json.JSONArray();
            for (int i = 0; i < 12; i++) {
                tracks.put(track(i));
                pls.put(playlist(i));
                recent.put(new org.json.JSONObject().put("track", track(i)).put("played_at", "2026-11-1" + (i % 6) + "T12:00:00.000Z"));
                artists.put(new org.json.JSONObject().put("type", "artist").put("id", "a" + i).put("uri", "spotify:artist:a" + i).put("name", "Artist " + i).put("images", new org.json.JSONArray()));
            }
            if (req.startsWith("GET /me/player/recently-played")) return new org.json.JSONObject().put("items", recent);
            if (req.startsWith("GET /me/playlists")) return new org.json.JSONObject().put("items", pls);
            if (req.startsWith("GET /me/top/tracks")) return new org.json.JSONObject().put("items", tracks);
            if (req.startsWith("GET /me/top/artists")) return new org.json.JSONObject().put("items", artists);
            if (req.startsWith("GET /browse/new-releases")) return null; // removed for dev-mode apps: section must hide
            if (req.startsWith("GET /me/following/contains") || req.startsWith("GET /me/library/contains")) return new org.json.JSONObject().put("items", new org.json.JSONArray().put(true));
            if (req.startsWith("GET /artists/") && req.contains("/albums")) {
                org.json.JSONArray als = new org.json.JSONArray();
                for (int i = 0; i < 6; i++) {
                    als.put(new org.json.JSONObject().put("type", "album").put("id", "aal" + i).put("uri", "spotify:album:aal" + i)
                            .put("name", "Release " + (i + 1)).put("release_date", "202" + (6 - i % 4) + "-05-01")
                            .put("total_tracks", 4 + i).put("images", new org.json.JSONArray())
                            .put("artists", new org.json.JSONArray().put(new org.json.JSONObject().put("name", "aespa"))));
                }
                return new org.json.JSONObject().put("items", als);
            }
            if (req.startsWith("GET /artists/") && req.contains("/top-tracks")) return new org.json.JSONObject().put("tracks", tracks);
            if (req.startsWith("EVENTS ")) {
                org.json.JSONArray evs = new org.json.JSONArray();
                evs.put(new org.json.JSONObject().put("datetime", "2026-12-18T19:00:00")
                        .put("title", "SYNK : PARALLEL LINE ENCORE")
                        .put("url", "https://example.com/event/1")
                        .put("venue", new org.json.JSONObject().put("name", "KSPO DOME").put("city", "Seoul").put("country", "South Korea")));
                evs.put(new org.json.JSONObject().put("datetime", "2027-01-10T18:30:00")
                        .put("title", "TOKYO DOME LIVE")
                        .put("url", "https://example.com/event/2")
                        .put("venue", new org.json.JSONObject().put("name", "Tokyo Dome").put("city", "Tokyo").put("country", "Japan")));
                return new org.json.JSONObject().put("items", evs);
            }
            if (req.startsWith("GET /search")) return new org.json.JSONObject()
                    .put("playlists", new org.json.JSONObject().put("items", pls))
                    .put("tracks", new org.json.JSONObject().put("items", tracks));
            if (req.startsWith("GET /playlists/")) {
                org.json.JSONArray it = new org.json.JSONArray();
                for (int i = 0; i < 12; i++) it.put(new org.json.JSONObject().put("item", track(i)));
                return playlist(1).put("items", new org.json.JSONObject().put("items", it));
            }
            if (req.startsWith("GET /me/player")) return new org.json.JSONObject();
            if (req.startsWith("GET /me")) return new org.json.JSONObject().put("display_name", "지호").put("product", "premium");
        } catch (Exception e) { throw new RuntimeException(e); }
        return null;
    }

    @Test public void screensLoggedIn() throws Exception {
        MainActivity.prefs(RuntimeEnvironment.getApplication()).edit().putString("refresh_token", "test").putString("client_id", "abc").commit();
        Spotify.fixtures = PrismUiTest::fixture;
        try {
            ActivityController<MainActivity> c = launch("ko-w393dp-h852dp-xxhdpi");
            MainActivity a = c.get();
            for (int i = 0; i < 40; i++) { idle(50); Thread.sleep(20); }
            shot(a, "30_home_logged_in");
            a.currentPage().scroll.scrollTo(0, 900);
            for (int i = 0; i < 20; i++) { idle(50); Thread.sleep(10); }
            shot(a, "31_home_scrolled");
            a.onTab(1, false);
            for (int i = 0; i < 40; i++) { idle(50); Thread.sleep(20); }
            shot(a, "32_explore_logged_in");
            Widgets.Item pl = Widgets.Item.of(playlist(1));
            a.openItem(pl);
            for (int i = 0; i < 40; i++) { idle(50); Thread.sleep(20); }
            shot(a, "33_playlist_detail");
            // 9.5.8 artist detail (follow + 9.5.7 release rails + offline events)
            Widgets.Item ar = Widgets.Item.of(new org.json.JSONObject().put("type", "artist").put("id", "a0")
                    .put("uri", "spotify:artist:a0").put("name", "aespa").put("images", new org.json.JSONArray()));
            a.openItem(ar);
            for (int i = 0; i < 40; i++) { idle(50); Thread.sleep(20); }
            shot(a, "34_artist_detail");
        } finally {
            Spotify.fixtures = null;
        }
    }

    @Test public void iconAndLocalMode() throws Exception {
        android.graphics.drawable.Drawable d = RuntimeEnvironment.getApplication().getDrawable(R.mipmap.ic_launcher);
        Bitmap b = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        android.graphics.Path clip = new android.graphics.Path();
        clip.addCircle(216, 216, 216, android.graphics.Path.Direction.CW);
        cv.clipPath(clip);
        d.setBounds(-72, -72, 504, 504); // adaptive icons are 108dp with a 72dp visible area
        d.draw(cv);
        OUT.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(OUT, "40_icon.png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
        // unclipped 108dp canvas (layers only) for the Windows icon: central 72dp = pixels 256..1280 of 1536
        Bitmap full = Bitmap.createBitmap(1536, 1536, Bitmap.Config.ARGB_8888);
        Canvas fc = new Canvas(full);
        for (int id : new int[]{R.drawable.ic_launcher_bg, R.drawable.ic_launcher_fg}) {
            android.graphics.drawable.Drawable layer = RuntimeEnvironment.getApplication().getDrawable(id);
            layer.setBounds(0, 0, 1536, 1536);
            layer.draw(fc);
        }
        try (FileOutputStream o = new FileOutputStream(new File(OUT, "layers_1536.png"))) { full.compress(Bitmap.CompressFormat.PNG, 100, o); }

        MainActivity.prefs(RuntimeEnvironment.getApplication()).edit().putString("source", "local").commit();
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(LocalMusic.permission());
        Player.Track t = new Player.Track();
        t.id = "local:1"; t.uri = "content://media/external/audio/media/1"; t.name = "로컬 노래"; t.artist = "아티스트";
        t.album = "앨범"; t.albumUri = "lalbum:1"; t.artistUri = "lartist:아티스트"; t.art = t.uri; t.artSmall = t.uri; t.duration = 200000;
        LocalMusic.songs.clear();
        for (int i = 0; i < 8; i++) LocalMusic.songs.add(t);
        LocalMusic.loaded = true;
        ActivityController<MainActivity> c = launch("ko-w393dp-h852dp-xxhdpi");
        MainActivity a = c.get();
        LocalMusic.songs.clear();
        for (int i = 0; i < 8; i++) LocalMusic.songs.add(t);
        LocalMusic.loaded = true;
        a.reloadAll();
        idle(600);
        shot(a, "41_local_home");
        RectF anchor = new RectF(a.header.pill);
        a.onHeaderButton(Icons.QUEUE, anchor); idle(900); shot(a, "42_source_menu");
        a.onBackPressed(); idle(600);
        Player.track = t; Player.playing = true; Player.repeat = "track"; Player.notifyAll0();
        a.np.open(); idle(1200); shot(a, "43_player_repeat");
    }

    @Test public void screensWide() throws Exception {
        fakeTrack();
        ActivityController<MainActivity> c = launch("ko-w690dp-h841dp-xhdpi");
        MainActivity a = c.get();
        fakeTrack(); idle(700);
        a.onTab(3, false); idle(700);
        // the upright header pill beside the rail needs the tall header (it was cut off after a re-creation)
        assertTrue("rail header tall enough", a.header.getHeight() >= Ui.dp(172));
        shot(a, "50_foldable_rail");
        a.onTab(1, false); idle(900);
        shot(a, "50b_foldable_explore");
        a.onTab(3, false); idle(700);
        ActivityController<MainActivity> t = launch("ko-w1280dp-h800dp-mdpi");
        MainActivity b = t.get();
        fakeTrack(); idle(700);
        b.onTab(3, false); idle(700);
        shot(b, "51_tablet");
        b.onSearch(); idle(900);
        shot(b, "51b_tablet_search");
        b.onExitSearch(); idle(900);
        // unfold: the tablet chrome grows out of the foldable rail's geometry
        b.dock.morphFrom(a.dock.snapshot()); idle(200);
        shot(b, "53_morph_mid");
        idle(900);
        // header pill → menu: icons ride inside the drop
        android.graphics.RectF hp = new android.graphics.RectF(b.header.pill);
        float[] ho = Glass.abs(b.header); hp.offset(ho[0], ho[1]);
        b.onHeaderButton(Icons.QUEUE, hp); idle(90);
        shot(b, "54_menu_early");
        idle(900);
        GlassMenu.dismissAny(b.root); idle(900);
        // the player shrinks back into the bar's cover square (tablet: right of the five controls, not its left edge)
        android.graphics.RectF ar = b.dock.miniArtRect(), mr = b.dock.miniRect();
        assertTrue("tablet cover sits after the controls", ar.left > mr.left + Ui.dpf(200));
        b.np.open(); idle(1400);
        shot(b, "52_tablet_player");
        b.np.close(); idle(220);
        shot(b, "52b_tablet_player_closing");
        idle(900);
        // sidebar: opens from the tab pill's sidebar button, content moves to its right
        b.onTab(0, false); idle(500);
        b.onSidebar(); idle(160);
        shot(b, "55_sidebar_opening");
        idle(900);
        shot(b, "55b_sidebar_home");
        assertTrue("sidebar shown", b.root.getChildAt(1) instanceof Sidebar && b.root.getChildAt(1).getVisibility() == View.VISIBLE);
        android.graphics.RectF mr2 = b.dock.miniRect();
        assertTrue("bar centred right of the sidebar", mr2.left > Sidebar.widthFor(b.root.getWidth()));
        b.openLibrarySection("liked"); idle(900);
        shot(b, "55c_sidebar_library");
        b.onTab(3, false); idle(700);
        shot(b, "55d_sidebar_settings");
        b.closeSidebar(); idle(900);
        shot(b, "55e_sidebar_closed");
    }

    @Test public void foldableAsTablet() throws Exception {
        MainActivity.prefs(RuntimeEnvironment.getApplication()).edit().putString("unfold_ui", "tablet").commit();
        fakeTrack();
        ActivityController<MainActivity> c = launch("ko-w690dp-h841dp-xhdpi");
        MainActivity a = c.get();
        fakeTrack(); idle(700);
        assertTrue("unfolded screen uses the tablet UI when chosen", a.tabletUi());
        // cover cards leave room for the rows below on this short-and-wide screen
        assertTrue("poster height within 41% of the screen", Widgets.posterW() * 1.33f <= Ui.screenH * 0.41f);
        // the in-app Spotify player WebView sits at index 0 of root: the sidebar must still land above the page
        a.root.addView(new View(a), 0, new android.widget.FrameLayout.LayoutParams(1, 1));
        a.openSidebar(false); idle(600);
        int si = -1;
        for (int i = 0; i < a.root.getChildCount(); i++) if (a.root.getChildAt(i) instanceof Sidebar) si = i;
        assertTrue("sidebar above the page backdrop", si > a.root.indexOfChild(a.backdrop));
        shot(a, "56b_foldable_sidebar_with_player");
        a.closeSidebar(); idle(700);
        shot(a, "56_foldable_tablet_ui");
        a.onTab(3, false); idle(500);
        a.push(new Settings.AppearancePage(a)); idle(800);
        shot(a, "57_unfold_setting");
        // equalizer screen: ten bands, presets; picking a preset turns it on and stores the curve
        a.push(new Settings.EqPage(a)); idle(800);
        Eq.setPreset(a, 1); idle(100);
        assertEquals(6f, Eq.gain(a, 0), 0.01f);
        a.pop(); idle(500);
        a.push(new Settings.EqPage(a)); idle(800);
        shot(a, "58_equalizer");
        Eq.setPreset(a, 0);
        MainActivity.prefs(RuntimeEnvironment.getApplication()).edit().remove("unfold_ui").commit();
        Ui.unfoldTablet = false;
    }

    @Test public void screensCover() throws Exception {
        MainActivity.prefs(RuntimeEnvironment.getApplication()).edit().putString("refresh_token", "test").putString("client_id", "abc").putString("source", "spotify").commit();
        Spotify.fixtures = PrismUiTest::fixture;
        try {
            ActivityController<MainActivity> c = launch("ko-w440dp-h704dp-xxhdpi");
            MainActivity a = c.get();
            fakeTrack();
            for (int i = 0; i < 40; i++) { idle(50); Thread.sleep(20); }
            shot(a, "60_cover_home");
        } finally {
            Spotify.fixtures = null;
        }
    }

    @Test public void screensEnglishDark() throws Exception {
        MainActivity.prefs(RuntimeEnvironment.getApplication()).edit().putString("theme", "dark").commit();
        ActivityController<MainActivity> c = launch("en-w393dp-h852dp-xxhdpi");
        MainActivity a = c.get();
        assertEquals("Home", a.getString(R.string.tab_home));
        fakeTrack();
        idle(700);
        a.onTab(3, false); idle(700); shot(a, "20_settings_dark_en");
    }

    @Test public void screensJapanese() throws Exception {
        ActivityController<MainActivity> c = launch("ja-w393dp-h852dp-xxhdpi");
        MainActivity a = c.get();
        assertEquals("ホーム", a.getString(R.string.tab_home));
        fakeTrack();
        idle(700);
        shot(a, "21_home_ja");
        a.onTab(3, false); idle(700); shot(a, "22_settings_ja");
        a.push(new Settings.AppearancePage(a)); idle(800); shot(a, "23_appearance_ja");
    }

    @Test public void deviceMusicAndPlayerStyles() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.READ_MEDIA_AUDIO);
        MainActivity.prefs(app).edit().putString("source", "local").commit();
        // home order: playlists right above charts by default
        String[] def = Pages.homeOrder(app);
        assertEquals("playlists", def[3]);
        assertEquals("charts", def[4]);
        ActivityController<MainActivity> c = launch("ko-w393dp-h852dp-xxhdpi");
        MainActivity a = c.get();
        // the app reads the (empty) device library in the background at start: let that finish first
        for (int i = 0; i < 150 && !LocalMusic.loaded; i++) { Thread.sleep(20); idle(20); }
        // a small fake library: one album with numbered tracks out of order
        LocalMusic.app = app;
        LocalMusic.songs.clear();
        String[] names = {"Interlude", "Opening", "Finale", "Bonus"};
        int[] nos = {2, 1, 3, 0};
        for (int i = 0; i < names.length; i++) {
            Player.Track t = new Player.Track();
            t.id = "local:" + i; t.uri = "content://media/external/audio/media/" + i; t.name = names[i]; t.artist = "Prism Band";
            t.album = "Glass"; t.albumId = 5; t.albumUri = "lalbum:5"; t.artistUri = "lartist:Prism Band";
            t.trackNo = nos[i]; t.added = 100 + i; t.duration = 200000 + i * 1000;
            LocalMusic.setArt(t);
            LocalMusic.songs.add(t);
        }
        LocalMusic.loaded = true;
        Widgets.Item album = LocalMusic.group("lalbum:5", LocalMusic.tracksFor("lalbum:5"), true);
        a.push(new Pages.LocalListPage(a, album)); idle(900);
        shot(a, "60_album_track_order");
        a.pop(); idle(500);

        // [1] cover change: images swap in place on every page, quickly, without rebuilding the home screen
        a.reloadAll(); idle(800);
        java.io.File cf = LocalMusic.coverFile("lalbum:5");
        cf.getParentFile().mkdirs();
        Bitmap cb = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        cb.eraseColor(0xFF3366FF);
        try (FileOutputStream o = new FileOutputStream(cf)) { cb.compress(Bitmap.CompressFormat.JPEG, 90, o); }
        String before = LocalMusic.songs.get(0).art;
        long t0 = System.nanoTime();
        LocalMusic.clearCover("lalbum:5");          // file → album thumbnail: same swap path as picking a cover
        cf.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(cf)) { cb.compress(Bitmap.CompressFormat.JPEG, 90, o); }
        LocalMusic.refreshArt("lalbum:5");   // what setCover does after saving the picked image
        idle(50);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String after = LocalMusic.songs.get(0).art;
        assertTrue("cover swap fast (" + ms + " ms)", ms < 1500);
        assertTrue("art key changed", !after.equals(before) && after.startsWith("file://"));
        assertTrue("home shows the new cover", findArt(a.root, after));
        idle(400);
        shot(a, "59_home_after_cover");
        LocalMusic.clearCover("lalbum:5"); idle(200);

        // [4] multi-disc albums merge into one
        Object[] d = LocalMusic.splitDisc("Pretty Rhythm Spectrum Disc 2");
        assertEquals("Pretty Rhythm Spectrum", d[0]);
        assertEquals(2, d[1]);
        assertEquals(1, LocalMusic.splitDisc("Best Of (CD1)")[1]);
        assertEquals(3, LocalMusic.splitDisc("앨범 디스크3")[1]);
        assertEquals(2, LocalMusic.splitDisc("Live - Disc II")[1]);
        assertTrue(LocalMusic.splitDisc("Best Vol. 2") == null);
        assertTrue(LocalMusic.splitDisc("Disc 1") == null);
        LocalMusic.songs.clear();
        for (int disc = 1; disc <= 2; disc++) for (int n = 1; n <= 2; n++) {
            Player.Track t = new Player.Track();
            t.id = "local:d" + disc + n; t.uri = "content://media/external/audio/media/9" + disc + n; t.name = "Song " + disc + "-" + n;
            t.artist = "Prism Band"; t.album = "Very Long Album Title For Testing The Big Header Wrapping Disc " + disc;
            t.albumId = 70 + disc; t.trackNo = n; t.added = 10; t.artistUri = "lartist:Prism Band";
            LocalMusic.keyAlbum(t, "Prism Band", true);
            LocalMusic.songs.add(t);
        }
        for (Player.Track t : LocalMusic.songs) LocalMusic.setArt(t);
        assertEquals("two discs → one album", 1, LocalMusic.group(true).size());
        String key = LocalMusic.songs.get(0).albumUri;
        Widgets.Item merged = LocalMusic.group(key, LocalMusic.tracksFor(key), true);
        assertEquals("Very Long Album Title For Testing The Big Header Wrapping", merged.title);
        // [5] long album title: wraps / shrinks instead of being cut
        a.push(new Pages.LocalListPage(a, merged)); idle(900);
        shot(a, "64_long_title_discs");
        Page lp = a.currentPage();
        assertTrue("big title not cut to one line", lp.bigTitle.getLineCount() >= 1 && lp.bigTitle.getLayout() != null
                && lp.bigTitle.getLayout().getEllipsisCount(lp.bigTitle.getLineCount() - 1) == 0);
        a.pop(); idle(500);

        // [3] app style A / G: same chrome, glass vs One UI 9.5 frosted pills + cards
        shot(a, "62_style_A");
        MainActivity.prefs(app).edit().putString("player_style", "oneui").commit();
        Ui.gStyle = true;
        a.refreshAppearance(); idle(600);
        shot(a, "63_style_G");
        // [2] G style player: cover, pill output chip, title below it, wave seek bar, round controls, volume
        fakeTrack();
        a.np.applyStyle();
        a.np.open(); idle(1400);
        shot(a, "61_player_oneui");     // first frame starts the wave clock
        idle(400);
        shot(a, "61_player_oneui");     // the waves have risen by now
        a.np.close(); idle(900);

        // [6] Accent color customization + One UI 9.5 G style settings & player
        assertEquals(0xFF387AFF, Ui.resolveAccent(app, "blue"));
        assertEquals(0xFF7B61FF, Ui.resolveAccent(app, "violet"));
        MainActivity.prefs(app).edit().putString("accent_color", "blue").commit();
        Ui.ACCENT = Ui.resolveAccent(app, "blue");
        a.refreshAppearance(); idle(400);
        a.onTab(3, false); idle(500);
        shot(a, "65_g_settings_blue");
        a.push(new Settings.AppearancePage(a)); idle(600);
        shot(a, "66_g_appearance_blue");
        a.pop(); idle(400);
        // open header menu in G style from the top-right header pill (ensure layout is drawn first)
        a.onTab(0, false); idle(400);
        shot(a, "63_style_G_blue_before_menu");
        a.onHeaderButton(Icons.QUEUE, a.header.pillInRoot());
        captureAnim(a, "g_menu_open", 28, 16);
        shot(a, "68_g_menu_blue");
        GlassMenu.dismissAny(a.root);
        captureAnim(a, "g_menu_close", 22, 16);

        // tab switch animation (Home -> Settings) in G style
        a.dock.select(3, true);
        a.onTab(3, false);
        captureAnim(a, "g_tab_switch", 28, 16);

        // page push & pop animation (Settings -> Appearance -> back) in G style
        a.push(new Settings.AppearancePage(a));
        captureAnim(a, "g_page_push", 26, 16);
        a.pop();
        captureAnim(a, "g_page_pop", 24, 16);

        // G style unified bottom search bar preview (with mini media pill floating above search bar!)
        a.onSearch(); idle(600);
        assertTrue("mini pill visible in search mode", a.dock.mini.height() > 0f);
        assertTrue("mini pill sits above search bar", a.dock.mini.bottom <= a.dock.pill.top);
        shot(a, "70_g_search_blue");
        a.onExitSearch(); idle(600);

        // now playing open, wave animation, close in G style (with long title/artist for centred marquee check)
        Player.track.name = "Supernova Love (Extended Club Mix feat. Prism Orchestra)";
        Player.track.artist = "IVE, David Guetta & The Prism Symphony Ensemble";
        for (Runnable r : new java.util.ArrayList<>(Player.trackListeners)) r.run();
        a.np.applyStyle();
        a.np.open();
        captureAnim(a, "g_player_open", 32, 16);
        captureAnim(a, "g_player_wave", 36, 16);
        shot(a, "67_g_player_blue");
        // Full-screen OLED AOD open & close via Back
        a.openAod(); idle(500);
        assertTrue("AOD overlay open", a.aod.isOpen());
        shot(a, "73_aod_fullscreen");
        a.onBackPressed(); idle(500);
        assertTrue("AOD closed on back", !a.aod.isOpen());
        a.np.close();
        captureAnim(a, "g_player_close", 28, 16);

        // G style foldable (unfolded w690dp-h841dp) check: no overlap on rail or two-pane player
        ActivityController<MainActivity> fc = launch("ko-w690dp-h841dp-xhdpi");
        MainActivity fa = fc.get();
        fakeTrack();
        Player.track.name = "Supernova Love (Extended Club Mix)";
        Player.track.artist = "IVE, David Guetta";
        for (Runnable r : new java.util.ArrayList<>(Player.trackListeners)) r.run();
        idle(700);
        shot(fa, "71_g_foldable_rail");
        fa.np.applyStyle();
        fa.np.open(); idle(1400);
        shot(fa, "72_g_foldable_player");
        fa.np.close(); idle(600);

        // G style dark mode settings preview
        Ui.dark = true;
        a.refreshAppearance(); idle(400);
        a.onTab(3, false); idle(500);
        shot(a, "69_g_dark_settings_blue");
        Ui.dark = false;
        // [7] Spotify first-play switchTrack skips fade delay when not previously playing
        final boolean[] sent = {false};
        SpotifyWeb.switchTrack(false, () -> sent[0] = true);
        assertTrue("first-play switchTrack dispatches immediately", sent[0]);

        // [8] 9.5.4 regression checks:
        // (a) SpotifyWeb disables its own MediaSession so background hardware volume stays local
        try {
            java.lang.reflect.Field htmlF = SpotifyWeb.class.getDeclaredField("HTML");
            htmlF.setAccessible(true);
            String html = (String) htmlF.get(null);
            assertTrue("SpotifyWeb disables WebView MediaSession", html.contains("enableMediaSession: false"));
        } catch (Exception e) { throw new RuntimeException(e); }

        // (b) Device connect sheet keeps chip hidden even when player ticker / applyMode runs
        a.np.open(); idle(900);
        try {
            java.lang.reflect.Field chipF = NowPlaying.class.getDeclaredField("chip");
            chipF.setAccessible(true);
            View chipV = (View) chipF.get(a.np);
            java.util.List<GlassMenu.Entry> devEntries = new java.util.ArrayList<>();
            devEntries.add(new GlassMenu.Entry("This phone", Icons.DEVICE, true, () -> {}));
            GlassMenu.sheet(a, new android.graphics.RectF(20, 400, 180, 432), "Devices", devEntries, chipV);
            idle(300);
            assertTrue("chip hidden while sheet is open", GlassMenu.isSourceHidden(chipV));
            // simulate ticker / Player.refresh calling onPlayer -> applyMode
            for (Runnable r : new java.util.ArrayList<>(Player.listeners)) r.run();
            idle(300);
            assertTrue("chip stays invisible after applyMode", chipV.getAlpha() == 0f);
            GlassMenu.dismissAny(a.root);
            idle(500);
            assertTrue("chip restored after sheet closes", !GlassMenu.isSourceHidden(chipV) && chipV.getAlpha() > 0.9f);

            // (c) Tapping the album cover toggles lyrics/queue panel automatically
            java.lang.reflect.Field artF = NowPlaying.class.getDeclaredField("art");
            artF.setAccessible(true);
            android.graphics.RectF artR = (android.graphics.RectF) artF.get(a.np);
            java.lang.reflect.Field panelF = NowPlaying.class.getDeclaredField("panel");
            panelF.setAccessible(true);
            assertEquals("panel starts closed", 0, panelF.getInt(a.np));
            long downT = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent dEv = android.view.MotionEvent.obtain(downT, downT, android.view.MotionEvent.ACTION_DOWN, artR.centerX(), artR.centerY(), 0);
            android.view.MotionEvent uEv = android.view.MotionEvent.obtain(downT, downT + 40, android.view.MotionEvent.ACTION_UP, artR.centerX(), artR.centerY(), 0);
            a.np.dispatchTouchEvent(dEv);
            a.np.dispatchTouchEvent(uEv);
            dEv.recycle(); uEv.recycle();
            idle(600);
            assertTrue("cover tap opens lyrics or queue", panelF.getInt(a.np) != 0);
            a.np.syncVolumeNow();
        } catch (Exception e) { throw new RuntimeException(e); }
        a.np.close(); idle(600);

        // [9] 9.5.5 regression checks:
        // (a) NPU audio enhancer SoC gate: Snapdragon 8 Gen 2+ / Dimensity 8400·9300+ / Exynos 2400+ / Tensor G4+
        assertTrue("SM8550 supported", NpuAudio.isSupportedSoc("SM8550"));
        assertTrue("SM8650 supported", NpuAudio.isSupportedSoc("SM8650"));
        assertTrue("SM8750 supported", NpuAudio.isSupportedSoc("SM8750"));
        assertTrue("SM8850 supported", NpuAudio.isSupportedSoc("SM8850"));
        assertTrue("MT6899 supported", NpuAudio.isSupportedSoc("MT6899"));
        assertTrue("MT6989 supported", NpuAudio.isSupportedSoc("MT6989"));
        assertTrue("MT6991 supported", NpuAudio.isSupportedSoc("MT6991"));
        assertTrue("Dimensity 9400 supported", NpuAudio.isSupportedSoc("MediaTek Dimensity 9400"));
        assertTrue("S5E9945 supported", NpuAudio.isSupportedSoc("S5E9945"));
        assertTrue("Exynos 2400 supported", NpuAudio.isSupportedSoc("Exynos 2400"));
        assertTrue("S5E9955 supported", NpuAudio.isSupportedSoc("S5E9955"));
        assertTrue("Exynos 2500 supported", NpuAudio.isSupportedSoc("Samsung Exynos 2500"));
        assertTrue("GS401 supported", NpuAudio.isSupportedSoc("GS401"));
        assertTrue("GS501 supported", NpuAudio.isSupportedSoc("GS501"));
        assertTrue("SM8450 rejected", !NpuAudio.isSupportedSoc("SM8450"));
        assertTrue("MT6985 rejected", !NpuAudio.isSupportedSoc("MT6985"));
        assertTrue("S5E9925 rejected", !NpuAudio.isSupportedSoc("S5E9925"));
        assertTrue("Exynos 2200 rejected", !NpuAudio.isSupportedSoc("Exynos 2200"));
        assertTrue("GS301 rejected", !NpuAudio.isSupportedSoc("GS301"));

        // (b) Spotify scopes include playlist-modify-public and playlist-modify-private
        assertTrue("playlist-modify-public scope", Spotify.SCOPES.contains("playlist-modify-public"));
        assertTrue("playlist-modify-private scope", Spotify.SCOPES.contains("playlist-modify-private"));

        // (c) Multi-provider word-synced lyrics parsers (NetEase YRC + Enhanced LRC)
        Lyrics.Result yrc = Lyrics.parseYrc("[1000,3000](1000,1500,0)안녕(2500,1500,0)하세요");
        assertTrue("YRC synced", yrc != null && yrc.synced && yrc.lines.size() == 1);
        assertEquals("YRC text", "안녕하세요", yrc.lines.get(0).text);
        assertEquals("YRC words", 2, yrc.lines.get(0).words.size());
        Lyrics.Result elrc = Lyrics.parse("[00:01.00]<00:01.00>Hello <00:02.00>World", "");
        assertTrue("Enhanced LRC synced", elrc != null && elrc.synced && elrc.lines.size() == 1);
        assertEquals("Enhanced LRC words", 2, elrc.lines.get(0).words.size());

        // [10] 9.5.6 regression checks: 4-tier Hi-Res, Bit-Perfect = Master, SSC/SSC-UHQ, device icons, friendly SoC names
        assertEquals("standard audio", AudioInfo.TIER_NONE, AudioInfo.evaluateHiResTier(false, 16, 48f, false));
        assertEquals("1 cond (assist) = Gold", AudioInfo.TIER_GOLD, AudioInfo.evaluateHiResTier(true, 16, 48f, false));
        assertEquals("1 cond (bit>16) = Gold", AudioInfo.TIER_GOLD, AudioInfo.evaluateHiResTier(false, 24, 48f, false));
        assertEquals("1 cond (khz>=96) = Gold", AudioInfo.TIER_GOLD, AudioInfo.evaluateHiResTier(false, 16, 96f, false));
        assertEquals("2 conds (bit+khz) = Blue", AudioInfo.TIER_BLUE, AudioInfo.evaluateHiResTier(false, 24, 96f, false));
        assertEquals("2 conds (assist+bit) = Blue", AudioInfo.TIER_BLUE, AudioInfo.evaluateHiResTier(true, 24, 48f, false));
        assertEquals("3 conds = Master", AudioInfo.TIER_MASTER, AudioInfo.evaluateHiResTier(true, 24, 96f, false));
        assertEquals("Bit-Perfect always Master", AudioInfo.TIER_MASTER, AudioInfo.evaluateHiResTier(false, 16, 44.1f, true));

        // SSC / SSC-UHQ codec mapping
        assertEquals("SSC", AudioInfo.codecTypeName(7, "Samsung Seamless Codec", 24, 48f));
        assertEquals("SSC-UHQ", AudioInfo.codecTypeName(7, "Samsung Seamless Codec", 24, 96f));
        assertEquals("SSC-UHQ", AudioInfo.codecTypeName(-1, "SSC-UHQ", 24, 96f));

        // Dedicated device icons (Buds 2 / Buds Live / Buds 3 / Buds 4 / Buds On / Headphones / Third-party Earbuds / USB DAC)
        assertEquals("Galaxy Buds2 Pro icon", Icons.BUDS2, AudioInfo.deviceIconForName("Galaxy Buds2 Pro", true, false, false));
        assertEquals("Galaxy Buds 2 icon", Icons.BUDS2, AudioInfo.deviceIconForName("Galaxy Buds 2", true, false, false));
        assertEquals("Korean Buds2 Pro icon", Icons.BUDS2, AudioInfo.deviceIconForName("지호의 버즈2 프로", true, false, false));
        assertEquals("Galaxy Buds+ icon", Icons.BUDS2, AudioInfo.deviceIconForName("Galaxy Buds+", true, false, false));
        assertEquals("Galaxy Buds FE icon", Icons.BUDS2, AudioInfo.deviceIconForName("Galaxy Buds FE", true, false, false));
        assertEquals("Galaxy Buds Live icon", Icons.BUDS_LIVE, AudioInfo.deviceIconForName("Galaxy Buds Live", true, false, false));
        assertEquals("Korean Buds Live icon", Icons.BUDS_LIVE, AudioInfo.deviceIconForName("갤럭시 버즈 라이브", true, false, false));
        assertEquals("Galaxy Buds3 icon", Icons.BUDS, AudioInfo.deviceIconForName("Galaxy Buds3 Pro", true, false, false));
        assertEquals("Galaxy Buds4 icon", Icons.BUDS4, AudioInfo.deviceIconForName("Galaxy Buds4 Pro", true, false, false));
        assertEquals("Korean Buds4 icon", Icons.BUDS4, AudioInfo.deviceIconForName("지호의 버즈4", true, false, false));
        assertEquals("Galaxy Buds On icon", Icons.BUDS_ON, AudioInfo.deviceIconForName("Galaxy Buds On", true, false, false));
        assertEquals("Galaxy Buds Able icon", Icons.BUDS_ON, AudioInfo.deviceIconForName("Galaxy Buds Able", true, false, false));
        assertEquals("Korean Buds On icon", Icons.BUDS_ON, AudioInfo.deviceIconForName("갤럭시 버즈 온", true, false, false));
        assertEquals("Headphones icon", Icons.HEADPHONES, AudioInfo.deviceIconForName("Sony WH-1000XM5", true, false, false));
        assertEquals("Third-party earbuds icon", Icons.EARBUDS, AudioInfo.deviceIconForName("AirPods Pro", true, false, false));
        assertEquals("USB DAC icon", Icons.DAC, AudioInfo.deviceIconForName("FiiO KA13", false, true, false));

        // Friendly SoC marketing names & exact 25 supported chipsets check (including MT6993M for Dimensity 9600M)
        assertEquals("25 supported chipsets", 25, NpuAudio.SUPPORTED_CHIPS.length);
        for (NpuAudio.ChipSpec cs : NpuAudio.SUPPORTED_CHIPS) {
            assertTrue("chip supported: " + cs.id, NpuAudio.isSupportedSoc(cs.id));
        }
        assertEquals("스냅드래곤 8 2세대", NpuAudio.friendlySocName(app, "SM8550"));
        assertEquals("스냅드래곤 8 3세대", NpuAudio.friendlySocName(app, "SM8650"));
        assertEquals("스냅드래곤 8 엘리트 5세대", NpuAudio.friendlySocName(app, "SM8850"));
        assertEquals("스냅드래곤 8 엘리트", NpuAudio.friendlySocName(app, "SM8750"));
        assertEquals("디멘시티 9300", NpuAudio.friendlySocName(app, "MT6989"));
        assertEquals("디멘시티 9400", NpuAudio.friendlySocName(app, "MT6991"));
        assertEquals("디멘시티 9400+", NpuAudio.friendlySocName(app, "MT6991Z"));
        assertEquals("디멘시티 9500", NpuAudio.friendlySocName(app, "MT6993"));
        assertEquals("디멘시티 9500s", NpuAudio.friendlySocName(app, "MT6991W"));
        assertEquals("디멘시티 9600 프로", NpuAudio.friendlySocName(app, "MT6995"));
        assertEquals("디멘시티 9600M", NpuAudio.friendlySocName(app, "MT6993M"));
        assertEquals("엑시노스 2400", NpuAudio.friendlySocName(app, "S5E9945"));
        assertEquals("엑시노스 2500", NpuAudio.friendlySocName(app, "S5E9955"));
        assertEquals("구글 텐서 G5", NpuAudio.friendlySocName(app, "GS501"));

        // [11] 9.5.8 regression checks: artist follow scope, lyrics [offset:] & version filter & ko translation check, real-time NPU A/B & params
        assertTrue("user-follow-modify scope", Spotify.SCOPES.contains("user-follow-modify"));
        Lyrics.Result offLrc = Lyrics.parse("[offset:+500]\n[00:02.00]Hello world", "");
        assertTrue("LRC offset applied", offLrc != null && offLrc.synced && offLrc.lines.size() == 1 && offLrc.lines.get(0).time == 1500L);
        assertTrue("live mismatch filtered", Lyrics.isVersionMismatch("Supernova", "Supernova (Live at Tokyo Dome)"));
        assertTrue("exact match allowed", !Lyrics.isVersionMismatch("Supernova", "Supernova"));
        NpuAudio.setParam(app, NpuAudio.PREF_SPACE, 68);
        NpuAudio.setParam(app, NpuAudio.PREF_BASS, 62);
        NpuAudio.setHarmonics(app, "tube");
        assertEquals(68, NpuAudio.space(app));
        assertEquals(62, NpuAudio.bass(app));
        assertEquals("tube", NpuAudio.harmonics(app));
        NpuAudio.setBypassHold(app, true);
        assertTrue("bypass hold active", NpuAudio.bypassHold);
        NpuAudio.setBypassHold(app, false);
        assertTrue("bypass hold released", !NpuAudio.bypassHold);

        // [12] 9.5.8 hotfix regression checks:
        // (a) Dual-language LRC native Korean subtitle split (no machine translation) + 6 selectable lyric providers
        assertEquals("6 lyric providers", 6, Lyrics.PROVIDER_IDS.length);
        Lyrics.Result dual = Lyrics.parse("[00:10.00]Hello world\n[00:10.05]안녕 세상아", "");
        Lyrics.extractDualKoreanLines(dual);
        assertTrue("dual LRC merged", dual != null && dual.lines.size() == 1);
        assertEquals("dual original", "Hello world", dual.lines.get(0).text);
        assertEquals("dual Korean sub", "안녕 세상아", dual.lines.get(0).trans);
        MainActivity.prefs(app).edit().putBoolean(Lyrics.providerPrefKey(Lyrics.SRC_QQ), false).commit();
        assertTrue("QQ provider disabled", !Lyrics.isProviderEnabled(app, Lyrics.SRC_QQ));
        MainActivity.prefs(app).edit().putBoolean(Lyrics.providerPrefKey(Lyrics.SRC_QQ), true).commit();

        // (b) Korean Buds3 / Buds4 UHQ detection AND non-Buds LDAC must NEVER be misread as SSC-UHQ
        assertTrue("Korean Buds3 Pro icon", Icons.BUDS == AudioInfo.deviceIconForName("지호의 버즈3 프로", true, false, false));
        AudioInfo.OutputSpec ldacSpec = new AudioInfo.OutputSpec();
        ldacSpec.deviceName = "WH-1000XM5"; ldacSpec.bits = 24; ldacSpec.khz = 96f; ldacSpec.codec = "";
        AudioInfo.resolveBluetoothCodec(ldacSpec, true, "wh-1000xm5", false, false);
        assertEquals("LDAC on Samsung host never misread as SSC-UHQ", "LDAC", ldacSpec.codec);
        AudioInfo.OutputSpec buds3Spec = new AudioInfo.OutputSpec();
        buds3Spec.deviceName = "지호의 버즈3 프로"; buds3Spec.bits = 24; buds3Spec.khz = 96f; buds3Spec.codec = "";
        AudioInfo.resolveBluetoothCodec(buds3Spec, true, "지호의 버즈3 프로", true, false);
        assertEquals("Korean Buds3 Pro resolved as SSC-UHQ", "SSC-UHQ", buds3Spec.codec);

        // (b2) ChipArtView keeps full alpha=255 across repeated draws (background->foreground regression)
        NpuAudio.ChipArtView chipV = new NpuAudio.ChipArtView(a, NpuAudio.SUPPORTED_CHIPS[1], "SM8850");
        chipV.measure(View.MeasureSpec.makeMeasureSpec(128, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(128, View.MeasureSpec.EXACTLY));
        chipV.layout(0, 0, 128, 128);
        Bitmap c1 = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Bitmap c2 = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        chipV.draw(new Canvas(c1));
        chipV.draw(new Canvas(c2));
        assertEquals("ChipArtView 2nd draw pixel matches 1st draw (no alpha fade)", c1.getPixel(64, 64), c2.getPixel(64, 64));

        // (b3) Render enlarged Galaxy Buds 2 / Buds Live / Buds 3 / Buds 4 / Buds On icons side-by-side for visual check
        Bitmap budsPrev = Bitmap.createBitmap(1480, 340, Bitmap.Config.ARGB_8888);
        Canvas bcv = new Canvas(budsPrev);
        bcv.drawColor(0xFF141418);
        android.graphics.Paint bp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        int[] bIcons = {Icons.BUDS2, Icons.BUDS_LIVE, Icons.BUDS, Icons.BUDS4, Icons.BUDS_ON};
        String[] bLabels = {"Buds 2 / Pro / +", "Buds Live", "Galaxy Buds 3", "Galaxy Buds 4", "Galaxy Buds On"};
        for (int i = 0; i < 5; i++) {
            float x0 = 24f + i * 290f;
            bp.setColor(0xFF22232A);
            bcv.drawRoundRect(new android.graphics.RectF(x0, 24f, x0 + 266f, 316f), 32f, 32f, bp);
            Icons.draw(bcv, bIcons[i], x0 + 133f, 146f, 168f, 0xFFFFFFFF);
            bp.setColor(0xFFEAEAF0);
            bp.setTextSize(24f);
            bp.setTextAlign(android.graphics.Paint.Align.CENTER);
            bp.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, 600, false));
            bcv.drawText(bLabels[i], x0 + 133f, 286f, bp);
        }
        try (FileOutputStream o = new FileOutputStream(new File(OUT, "83_buds_icon_preview.png"))) { budsPrev.compress(Bitmap.CompressFormat.PNG, 100, o); }

        // (b4) Render Classic Prism (Default) vs Obsidian Neon (#3) vs Spectrum Bloom (#5) app icon preview
        Bitmap appIcPrev = Bitmap.createBitmap(960, 360, Bitmap.Config.ARGB_8888);
        Canvas acv = new Canvas(appIcPrev);
        acv.drawColor(0xFF141418);
        int[][] icRes = {
                {R.drawable.ic_launcher_bg, R.drawable.ic_launcher_fg},
                {R.drawable.ic_launcher_neon_bg, R.drawable.ic_launcher_neon_fg},
                {R.drawable.ic_launcher_bloom_bg, R.drawable.ic_launcher_bloom_fg}
        };
        String[] icLabels = {"Classic Prism (Default)", "Obsidian Neon (#3)", "Spectrum Bloom (#5)"};
        for (int i = 0; i < 3; i++) {
            float x0 = 26f + i * 314f;
            bp.setColor(0xFF22232A);
            acv.drawRoundRect(new android.graphics.RectF(x0, 24f, x0 + 280f, 336f), 36f, 36f, bp);
            acv.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(new android.graphics.RectF(x0 + 50f, 54f, x0 + 230f, 234f), 46f, 46f, android.graphics.Path.Direction.CW);
            acv.clipPath(clip);
            android.graphics.drawable.Drawable bgD = a.getDrawable(icRes[i][0]);
            android.graphics.drawable.Drawable fgD = a.getDrawable(icRes[i][1]);
            if (bgD != null) { bgD.setBounds((int) (x0 + 6), 10, (int) (x0 + 274), 278); bgD.draw(acv); }
            if (fgD != null) { fgD.setBounds((int) (x0 + 6), 10, (int) (x0 + 274), 278); fgD.draw(acv); }
            acv.restore();
            bp.setColor(0xFFEAEAF0);
            bp.setTextSize(22f);
            bp.setTextAlign(android.graphics.Paint.Align.CENTER);
            acv.drawText(icLabels[i], x0 + 140f, 294f, bp);
        }
        try (FileOutputStream o = new FileOutputStream(new File(OUT, "85_app_icons_preview.png"))) { appIcPrev.compress(Bitmap.CompressFormat.PNG, 100, o); }

        // (b5) 9.5.14 Namuwiki real-time translation lyrics (default OFF + HTML parser + matcher)
        assertTrue("Namuwiki translation defaults OFF", !Lyrics.namuwikiEnabled(app));
        assertTrue("Challenge page rejected", Lyrics.isNamuChallengePage("<html><title>Just a moment...</title></html>"));
        String namuTableHtml = "<table><tr><td>Hello world<br>Shine so bright</td><td>안녕 세상아<br>눈부시게 빛나</td></tr></table>";
        java.util.List<String[]> namuPairs = Lyrics.parseNamuwikiHtml(namuTableHtml);
        assertEquals("Parsed 2 Namuwiki lyric pairs", 2, namuPairs.size());
        Lyrics.Result namuRes = Lyrics.parse("[00:05.00]Hello world\n[00:10.00]Shine so bright", "");
        assertEquals("Matched 2 Namuwiki lines", 2, Lyrics.applyNamuwikiPairs(namuRes, namuPairs));
        assertEquals("안녕 세상아", namuRes.lines.get(0).trans);
        assertEquals("눈부시게 빛나", namuRes.lines.get(1).trans);

        // (b6) 9.5.14 Spotify 429 cacheability checks (recently-played cacheable; live /me/player excluded)
        java.util.function.Function<String, org.json.JSONObject> savedFix = Spotify.fixtures;
        Spotify.fixtures = null;
        assertTrue("recently-played is cacheable", Spotify.cacheable("GET", "/me/player/recently-played?limit=50"));
        assertTrue("live /me/player is not cached", !Spotify.cacheable("GET", "/me/player?additional_types=track"));
        assertEquals("catalog TTL is 30 min", 30L * 60 * 1000L, Spotify.cacheTtlMs("/artists/abc/albums"));
        Spotify.fixtures = savedFix;

        // (c) NowPlaying ticker stays alive immediately after open() even before openP > 0
        a.np.open();
        idle(200);
        try {
            java.lang.reflect.Field tf = NowPlaying.class.getDeclaredField("tickerRunning");
            tf.setAccessible(true);
            assertTrue("NowPlaying ticker running after open", tf.getBoolean(a.np));
        } catch (Exception e) { throw new RuntimeException(e); }
        a.np.close(); idle(400);

        // Capture new Lyrics Providers settings page
        a.push(new Settings.LyricsPage(a)); idle(500);
        shot(a, "82_lyrics_providers");
        a.pop(); idle(300);

        // Capture Bit-Perfect Assist (with chipset art) + Supported Chipsets 15-chip list + Gold / Blue / Master player effects
        NpuAudio.testSupportedOverride = Boolean.TRUE;
        NpuAudio.testSocOverride = "SM8850";
        NpuAudio.setEnabled(app, true);
        AudioInfo.OutputSpec mockSpec = new AudioInfo.OutputSpec();
        mockSpec.deviceName = "Galaxy Buds3 Pro";
        mockSpec.codec = "SSC-UHQ";
        mockSpec.bits = 24;
        mockSpec.khz = 96f;
        mockSpec.external = true;
        mockSpec.isBt = true;
        AudioInfo.testSpec = mockSpec;

        a.push(new Settings.BitPerfectAssistPage(a)); idle(600);
        shot(a, "74_bitperfect_assist");
        a.push(new Settings.SupportedChipsetsPage(a)); idle(600);
        shot(a, "79_supported_chipsets");
        a.pop(); idle(300);
        a.pop(); idle(400);

        // 3 conditions Master exclusive effect (Assist + 24bit + 96kHz) + animated luxury backdrop
        a.np.open(); idle(1200);
        for (Runnable r : new java.util.ArrayList<>(Player.listeners)) r.run();
        idle(900);
        shot(a, "76_player_hires_master");

        // 2 conditions Luminous Blue glow effect (Assist off, 24bit + 96kHz SSC-UHQ)
        NpuAudio.setEnabled(app, false);
        for (Runnable r : new java.util.ArrayList<>(Player.listeners)) r.run();
        idle(400);
        shot(a, "77_player_hires_blue");

        // 1 condition Gold effect (Assist off, 24bit / 48kHz SSC)
        mockSpec.codec = "SSC";
        mockSpec.khz = 48f;
        for (Runnable r : new java.util.ArrayList<>(Player.listeners)) r.run();
        idle(400);
        shot(a, "75_player_hires_gold");

        // A style check: codec badge sits cleanly below album cover AND 4-button Liquid Glass actionPill is visible
        Ui.gStyle = false;
        MainActivity.prefs(app).edit().putString("player_style", "prism").commit();
        Player.shuffle = true;
        Player.repeat = "context";
        a.np.applyStyle();
        idle(400);
        shot(a, "78_player_a_style_badge");
        try {
            java.lang.reflect.Field apF = NowPlaying.class.getDeclaredField("actionPill");
            apF.setAccessible(true);
            View ap = (View) apF.get(a.np);
            assertEquals("A-style Liquid Glass pill visible", View.VISIBLE, ap.getVisibility());
            assertTrue("A-style wide pill has width (" + ap.getWidth() + ")", ap.getWidth() > Ui.dp(90));
        } catch (Exception e) { throw new RuntimeException(e); }

        a.np.close(); idle(500);

        // Wide foldable 16:10 cover (w412dp-h659dp) layout check (both A-style 4-btn glass pill & G-style active circle non-clipping!)
        ActivityController<MainActivity> wc = launch("ko-w412dp-h659dp-xhdpi");
        MainActivity wa = wc.get();
        fakeTrack();
        Player.shuffle = true;
        Player.repeat = "context";
        idle(600);
        wa.np.open(); idle(900);
        shot(wa, "80_wide_cover_16_10");
        try {
            java.lang.reflect.Field apF = NowPlaying.class.getDeclaredField("actionPill");
            apF.setAccessible(true);
            View ap = (View) apF.get(wa.np);
            assertEquals("Fold8 16:10 A-style 4-btn pill visible", View.VISIBLE, ap.getVisibility());
            assertTrue("Fold8 16:10 A-style 4-btn pill wide (" + ap.getWidth() + ")", ap.getWidth() > Ui.dp(200));
            java.lang.reflect.Field shF = NowPlaying.class.getDeclaredField("shuffleBtn");
            shF.setAccessible(true);
            View shBtn = (View) shF.get(wa.np);
            assertTrue("Fold8 16:10 A-style bottom active button not clipped (bottom=" + shBtn.getBottom() + " H=" + wa.np.getHeight() + ")",
                    shBtn.getBottom() <= wa.np.getHeight() - Ui.navInset - Ui.dp(6));
        } catch (Exception e) { throw new RuntimeException(e); }
        // Also check G-style active circle non-clipping on Fold8 16:10 cover!
        Ui.gStyle = true;
        MainActivity.prefs(app).edit().putString("player_style", "oneui").commit();
        wa.np.applyStyle();
        idle(400);
        shot(wa, "86_wide_cover_16_10_g");
        try {
            java.lang.reflect.Field shF = NowPlaying.class.getDeclaredField("shuffleBtn");
            shF.setAccessible(true);
            View shBtn = (View) shF.get(wa.np);
            assertTrue("Fold8 16:10 G-style bottom active circle not clipped (bottom=" + shBtn.getBottom() + " H=" + wa.np.getHeight() + ")",
                    shBtn.getBottom() <= wa.np.getHeight() - Ui.navInset - Ui.dp(6));
        } catch (Exception e) { throw new RuntimeException(e); }
        wa.np.close(); idle(300);
        Player.shuffle = false;
        Player.repeat = "off";

        ActivityController<MainActivity> innerCtrl = launch("ko-w800dp-h600dp-xhdpi");
        MainActivity ia = innerCtrl.get();
        fakeTrack();
        idle(600);
        ia.np.open(); idle(900);
        shot(ia, "81_wide_inner_4_3");
        ia.np.close(); idle(300);

        // [13] 9.5.11 Artist discography on 4:3 wide foldable even when /artists/{id}/albums AND /search?type=album BOTH fail!
        java.util.function.Function<String, org.json.JSONObject> prevFix = Spotify.fixtures;
        try {
            Ui.gStyle = true;
            MainActivity.prefs(app).edit().putString("player_style", "oneui").commit();
            Spotify.fixtures = req -> {
                try {
                    if (req.startsWith("GET /me/library/contains")) return new org.json.JSONObject().put("items", new org.json.JSONArray().put(true));
                    if (req.startsWith("EVENTS ")) return new org.json.JSONObject().put("items", new org.json.JSONArray());
                    if (req.contains("/albums") || req.contains("type=album")) return null; // simulate 403/429 block on BOTH endpoints!
                    if (req.startsWith("GET /search?type=track")) {
                        org.json.JSONArray trs = new org.json.JSONArray();
                        org.json.JSONObject ar = new org.json.JSONObject().put("id", "avemujica1").put("name", "Ave Mujica").put("type", "artist").put("uri", "spotify:artist:avemujica1");
                        org.json.JSONArray ars = new org.json.JSONArray().put(ar);
                        String[][] rows = {
                                {"t1", "BADDY BADDY", "al1", "BADDY BADDY", "single", "2025-09-01"},
                                {"t2", "Ave Mujica", "al2", "Alea jacta est", "album", "2023-09-13"},
                                {"t3", "KiLLKiSS", "al3", "KiLLKiSS", "single", "2025-01-15"},
                                {"t4", "神の名を", "al4", "Completeness", "album", "2025-04-23"},
                                {"t5", "Symbol I : △", "al5", "ELEMENTS", "album", "2024-10-02"},
                                {"t6", "Georgette Me, Georgette You", "al3", "KiLLKiSS", "single", "2025-01-15"}
                        };
                        for (String[] r : rows) {
                            org.json.JSONObject al = new org.json.JSONObject()
                                    .put("type", "album").put("id", r[2]).put("uri", "spotify:album:" + r[2])
                                    .put("name", r[3]).put("album_type", r[4]).put("release_date", r[5])
                                    .put("artists", ars).put("images", new org.json.JSONArray());
                            trs.put(new org.json.JSONObject()
                                    .put("type", "track").put("id", r[0]).put("uri", "spotify:track:" + r[0])
                                    .put("name", r[1]).put("duration_ms", 210000)
                                    .put("artists", ars).put("album", al));
                        }
                        return new org.json.JSONObject().put("tracks", new org.json.JSONObject().put("items", trs));
                    }
                } catch (Exception ignored) {}
                return prevFix != null ? prevFix.apply(req) : null;
            };
            Widgets.Item ave = Widgets.Item.of(new org.json.JSONObject()
                    .put("type", "artist").put("id", "avemujica1").put("uri", "spotify:artist:avemujica1")
                    .put("name", "Ave Mujica").put("images", new org.json.JSONArray()));
            Pages.DetailPage dp = new Pages.DetailPage(ia, ave);
            ia.push(dp);
            Thread.sleep(100);
            idle(900);
            java.lang.reflect.Field slotsF = Pages.DetailPage.class.getDeclaredField("discogSlots");
            slotsF.setAccessible(true);
            android.widget.LinearLayout[] dSlots = (android.widget.LinearLayout[]) slotsF.get(dp);
            assertTrue("Albums rail rendered even when /albums & search?type=album blocked", dSlots[0].getChildCount() >= 2);
            assertTrue("Singles rail rendered even when /albums & search?type=album blocked", dSlots[1].getChildCount() >= 2);
            dp.scroll.scrollTo(0, Ui.dp(240));
            idle(200);
            shot(ia, "84_artist_wide_albums");
            ia.pop(); idle(300);

            // Also verify direct /artists/{id}/albums path renders all release rails cleanly
            Spotify.fixtures = PrismUiTest::fixture;
            Widgets.Item directAr = Widgets.Item.of(new org.json.JSONObject()
                    .put("type", "artist").put("id", "a0").put("uri", "spotify:artist:a0")
                    .put("name", "Artist 0").put("images", new org.json.JSONArray()));
            Pages.DetailPage dp2 = new Pages.DetailPage(ia, directAr);
            ia.push(dp2);
            Thread.sleep(100);
            idle(900);
            android.widget.LinearLayout[] dSlots2 = (android.widget.LinearLayout[]) slotsF.get(dp2);
            assertTrue("Direct /artists/{id}/albums populates Albums rail", dSlots2[0].getChildCount() >= 2);
            ia.pop(); idle(300);

            // [14] 9.5.12 regression checks:
            // (a) Player.stripDeviceId prevents duplicate device_id=...&device_id=... on playback recovery
            assertEquals("/me/player/play", Player.stripDeviceId("/me/player/play?device_id=old123"));
            assertEquals("/me/player/play?foo=1", Player.stripDeviceId("/me/player/play?foo=1&device_id=old123"));
            // (b) Spotify.playable keeps ONLY owned & collaborative playlists when user_id is known (hides unplayable 3rd-party & Spotify playlists with 0 network calls!)
            Spotify.prefs.edit().putString("user_id", "me").commit();
            org.json.JSONArray myPls = new org.json.JSONArray()
                    .put(new org.json.JSONObject().put("type", "playlist").put("id", "my1").put("name", "My Empty PL")
                            .put("owner", new org.json.JSONObject().put("id", "me")).put("collaborative", false).put("items", new org.json.JSONObject().put("total", 0)))
                    .put(new org.json.JSONObject().put("type", "playlist").put("id", "collab1").put("name", "Collab PL")
                            .put("owner", new org.json.JSONObject().put("id", "friend")).put("collaborative", true).put("items", new org.json.JSONObject().put("total", 5)))
                    .put(new org.json.JSONObject().put("type", "playlist").put("id", "other1").put("name", "Other User PL")
                            .put("owner", new org.json.JSONObject().put("id", "stranger")).put("collaborative", false).put("items", new org.json.JSONObject().put("total", 20)))
                    .put(new org.json.JSONObject().put("type", "playlist").put("id", "sp1").put("name", "Spotify Editorial")
                            .put("owner", new org.json.JSONObject().put("id", "spotify")).put("collaborative", false).put("items", new org.json.JSONObject().put("total", 50)));
            org.json.JSONArray filteredPls = Spotify.playable(myPls, true);
            assertEquals("Only owned + collaborative playlists survive", 2, filteredPls.length());
            assertEquals("my1", filteredPls.optJSONObject(0).optString("id"));
            assertEquals("collab1", filteredPls.optJSONObject(1).optString("id"));
            Spotify.prefs.edit().remove("user_id").commit();
            // (c) Album detail recovers tracks via /search?type=track even when /albums/{id} & /albums/{id}/tracks fail
            java.util.List<String> seenReqs = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            Spotify.fixtures = req -> {
                seenReqs.add(req);
                try {
                    if (req.startsWith("GET /albums/")) return null; // both /albums/{id} and /albums/{id}/tracks fail!
                    if (req.startsWith("GET /search?type=track")) {
                        org.json.JSONObject al = new org.json.JSONObject().put("type", "album").put("id", "al4")
                                .put("uri", "spotify:album:al4").put("name", "Completeness").put("images", new org.json.JSONArray());
                        org.json.JSONArray trs = new org.json.JSONArray()
                                .put(new org.json.JSONObject().put("type", "track").put("id", "t10").put("uri", "spotify:track:t10")
                                        .put("name", "神の名を").put("track_number", 1).put("duration_ms", 200000).put("album", al)
                                        .put("artists", new org.json.JSONArray().put(new org.json.JSONObject().put("name", "Ave Mujica"))));
                        return new org.json.JSONObject().put("tracks", new org.json.JSONObject().put("items", trs));
                    }
                    if (req.startsWith("GET /playlists/pl9512/items?limit=50")) {
                        org.json.JSONArray items = new org.json.JSONArray()
                                .put(new org.json.JSONObject().put("item", track(0)));
                        return new org.json.JSONObject().put("items", items);
                    }
                    if (req.startsWith("GET /playlists/pl9512")) return null; // force fallback to /items?limit=50
                } catch (Exception ignored) {}
                return null;
            };
            Widgets.Item albItem = Widgets.Item.of(new org.json.JSONObject()
                    .put("type", "album").put("id", "al4").put("uri", "spotify:album:al4").put("name", "Completeness"));
            Pages.DetailPage albPage = new Pages.DetailPage(ia, albItem);
            ia.push(albPage);
            Thread.sleep(100);
            idle(700);
            java.lang.reflect.Field trField = Pages.DetailPage.class.getDeclaredField("tracks");
            trField.setAccessible(true);
            assertEquals("Album recovered tracks via search fallback", 1, ((java.util.List<?>) trField.get(albPage)).size());
            ia.pop(); idle(250);

            // (d) Playlist detail falls back to /playlists/{id}/items?limit=50 (never limit=100!) and never blocks
            Widgets.Item plItem = Widgets.Item.of(new org.json.JSONObject()
                    .put("type", "playlist").put("id", "pl9512").put("uri", "spotify:playlist:pl9512").put("name", "My Playlist"));
            Pages.DetailPage plPage = new Pages.DetailPage(ia, plItem);
            ia.push(plPage);
            Thread.sleep(100);
            idle(700);
            assertEquals("Playlist loaded via /items?limit=50", 1, ((java.util.List<?>) trField.get(plPage)).size());
            assertTrue("Playlist fallback used limit=50 not limit=100", seenReqs.contains("GET /playlists/pl9512/items?limit=50"));
            assertTrue("Playlist not blocked", !Spotify.isBlocked("pl9512"));
            ia.pop(); idle(250);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            Spotify.fixtures = prevFix;
        }

        // [15] 9.5.16 regression checks (all 10 update items):
        // (1) Playlist cover upload scope & square JPEG compression under Spotify's 256 KB Base64 limit
        assertTrue("ugc-image-upload scope included", Spotify.SCOPES.contains("ugc-image-upload"));
        Bitmap wideSrc = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888);
        wideSrc.eraseColor(0xFF387AFF);
        byte[] coverJpeg = Spotify.encodePlaylistCoverJpeg(wideSrc);
        wideSrc.recycle();
        assertTrue("Playlist cover JPEG valid and <= 185KB", coverJpeg != null && coverJpeg.length > 500 && coverJpeg.length <= 185_000);

        // (3) Bit-Perfect scoring: exact BP > integer-multiple BP > Direct Hi-Res USB fallback (FiiO KA13)
        int sExact = BitPerfect.scoreCandidate(true, 44100, 24, 2, 44100, 16);
        int sMult = BitPerfect.scoreCandidate(true, 88200, 24, 2, 44100, 16);
        int sDirect = BitPerfect.scoreCandidate(false, 44100, 32, 2, 44100, 16);
        assertTrue("Exact bit-perfect beats integer-multiple", sExact > sMult);
        assertTrue("Integer-multiple bit-perfect beats direct USB", sMult > sDirect);
        assertTrue("Direct Hi-Res USB fallback has positive score", sDirect > 0);

        // (6) Updater release notes Markdown formatter strips raw syntax cleanly
        CharSequence fmtNotes = Updater.formatReleaseNotes("### What's New\n- **Bold** item with `code` and [Link](https://example.com)\n---\n* Second item");
        String fmtStr = fmtNotes.toString();
        assertTrue("No raw ###", !fmtStr.contains("###"));
        assertTrue("No raw **", !fmtStr.contains("**"));
        assertTrue("No raw backticks", !fmtStr.contains("`"));
        assertTrue("No raw markdown link syntax", !fmtStr.contains("]("));
        assertTrue("Bullet converted cleanly", fmtStr.contains("•  Bold item with code and Link"));

        // (8) Multi-device Spotify Connect naming
        assertEquals("Prism (Galaxy S25 Ultra)", SpotifyWeb.formatConnectName("Galaxy S25 Ultra"));
        assertEquals("Prism", SpotifyWeb.formatConnectName(""));
        String builtHtml = SpotifyWeb.buildHtml("Prism (Galaxy Z Fold8)");
        assertTrue("HTML carries device name", builtHtml.contains("name: 'Prism (Galaxy Z Fold8)'"));
        assertTrue("HTML disables WebView MediaSession", builtHtml.contains("enableMediaSession: false"));

        // (9) Full-Screen AOD: timestamps GONE, auto-brightness option works, double-tap button controls playback WITHOUT closing AOD!
        a.openAod(); idle(500);
        assertTrue("AOD open", a.aod.isOpen());
        try {
            java.lang.reflect.Field tlF = AodOverlay.class.getDeclaredField("timeL");
            java.lang.reflect.Field trF = AodOverlay.class.getDeclaredField("timeR");
            java.lang.reflect.Field pbF = AodOverlay.class.getDeclaredField("playBtn");
            tlF.setAccessible(true); trF.setAccessible(true); pbF.setAccessible(true);
            assertEquals("AOD timeL hidden", View.GONE, ((View) tlF.get(a.aod)).getVisibility());
            assertEquals("AOD timeR hidden", View.GONE, ((View) trF.get(a.aod)).getVisibility());
            View pBtn = (View) pbF.get(a.aod);
            // Set local mock track so Player.toggle() flips Player.playing cleanly without network
            Player.Track savedTr = Player.track;
            Player.playing = true;
            long tNow = android.os.SystemClock.uptimeMillis();
            for (int tap = 0; tap < 2; tap++) {
                long ts = tNow + tap * 120L;
                android.view.MotionEvent dn = android.view.MotionEvent.obtain(ts, ts, android.view.MotionEvent.ACTION_DOWN, pBtn.getWidth() / 2f, pBtn.getHeight() / 2f, 0);
                android.view.MotionEvent up = android.view.MotionEvent.obtain(ts, ts + 25, android.view.MotionEvent.ACTION_UP, pBtn.getWidth() / 2f, pBtn.getHeight() / 2f, 0);
                pBtn.dispatchTouchEvent(dn);
                pBtn.dispatchTouchEvent(up);
                dn.recycle(); up.recycle();
            }
            idle(200);
            assertTrue("Double-tapping AOD play button keeps AOD open", a.aod.isOpen());
            assertTrue("Double-tapping AOD play button toggled playing state", !Player.playing);
            Player.track = savedTr;
            Player.playing = true;
        } catch (Exception e) { throw new RuntimeException(e); }
        // Auto-brightness check
        MainActivity.prefs(app).edit().putBoolean("aod_auto_bright", true).commit();
        a.aod.closeImmediate();
        a.openAod(); idle(300);
        assertEquals("AOD auto-brightness delegates to system (-1)", android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, a.getWindow().getAttributes().screenBrightness, 0.01f);
        MainActivity.prefs(app).edit().putBoolean("aod_auto_bright", false).commit();
        a.aod.closeImmediate();

        // (10) All 24 released chipsets (13 existing + 10 newly added; Exynos 2700 unreleased) have official front drawable resources
        int withArt = 0;
        for (NpuAudio.ChipSpec cs : NpuAudio.SUPPORTED_CHIPS) {
            if ("S5E9975".equals(cs.id)) continue;
            assertTrue("Official chip drawable present for " + cs.id, cs.drawableRes != 0);
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeResource(app.getResources(), cs.drawableRes);
            assertNotNull("Decoded bitmap for " + cs.id, bmp);
            bmp.recycle();
            withArt++;
        }
        assertEquals("24 released chipsets have official front artwork", 24, withArt);

        // (2)(4)(5) Visual verification shots: G-style Home (logged in), G-style Player with Hi-Res badge (verify zero overlap!), G-style Tablet with sidebar
        Ui.gStyle = true;
        MainActivity.prefs(app).edit().putString("player_style", "oneui").putString("source", "spotify")
                .putString("refresh_token", "test").putString("client_id", "abc").commit();
        Spotify.fixtures = PrismUiTest::fixture;
        try {
            ActivityController<MainActivity> gHomeC = launch("ko-w393dp-h852dp-xxhdpi");
            MainActivity gha = gHomeC.get();
            fakeTrack();
            for (int i = 0; i < 30; i++) { idle(50); Thread.sleep(15); }
            shot(gha, "87_home_g_style");
            gha.np.open(); idle(1000);
            // Assert zero overlap between elapsed / audioBadge / remain and between chip / aodBtn in G-style!
            try {
                java.lang.reflect.Field elF = NowPlaying.class.getDeclaredField("elapsed");
                java.lang.reflect.Field rmF = NowPlaying.class.getDeclaredField("remain");
                java.lang.reflect.Field abF = NowPlaying.class.getDeclaredField("audioBadge");
                java.lang.reflect.Field chF = NowPlaying.class.getDeclaredField("chip");
                java.lang.reflect.Field aoF = NowPlaying.class.getDeclaredField("aodBtn");
                elF.setAccessible(true); rmF.setAccessible(true); abF.setAccessible(true); chF.setAccessible(true); aoF.setAccessible(true);
                View elV = (View) elF.get(gha.np), rmV = (View) rmF.get(gha.np), abV = (View) abF.get(gha.np), chV = (View) chF.get(gha.np), aoV = (View) aoF.get(gha.np);
                assertTrue("G-style audioBadge left >= elapsed right", abV.getLeft() >= elV.getRight() - 2);
                assertTrue("G-style audioBadge right <= remain left", abV.getRight() <= rmV.getLeft() + 2);
                assertTrue("G-style chip right <= aodBtn left", chV.getRight() <= aoV.getLeft() + 2);
            } catch (Exception e) { throw new RuntimeException(e); }
            shot(gha, "89_g_player_hires_fixed");
            gha.np.close(); idle(400);

            // Tablet PC-like G-style with sidebar open (and close afterwards so static sidebarWanted doesn't affect other tests!)
            ActivityController<MainActivity> gTabC = launch("ko-w1280dp-h800dp-mdpi");
            MainActivity gta = gTabC.get();
            fakeTrack();
            for (int i = 0; i < 30; i++) { idle(50); Thread.sleep(15); }
            gta.openSidebar(false); idle(600);
            shot(gta, "88_tablet_pc_g_style");
            gta.closeSidebar(); idle(500);
        } finally {
            Spotify.fixtures = null;
            MainActivity.prefs(app).edit().remove("refresh_token").remove("client_id").commit();
        }

        // [16] 9.5.17 regression checks + UI screenshots + animated GIFs (all 9 items):
        try {
            // (3) Real-time Lyrics interlude gap fix: 15-second instrumental break must NOT stretch line 1 across 15s!
            Lyrics.Result interLyr = Lyrics.parse(
                    "[00:10.00] Hello world\n[00:26.00] Next verse begins\n[00:30.00] \n[00:45.00] Final chorus", "");
            Lyrics.finalizeEndTimes(interLyr, 180_000L);
            assertNotNull("Parsed lyrics", interLyr);
            assertEquals("Blank end-marker collapsed into explicit endTime", 3, interLyr.lines.size());
            long line0Dur = interLyr.lines.get(0).endTime - interLyr.lines.get(0).time;
            assertTrue("Line before 16s interlude capped cleanly (" + line0Dur + "ms)", line0Dur >= 2000L && line0Dur <= 6800L);
            assertEquals("Explicit blank timestamp sets line 1 endTime to 30000ms", 30_000L, interLyr.lines.get(1).endTime);

            // (8) BitPerfect channel balance + ReplayGain gains math
            MainActivity.prefs(app).edit().putInt(BitPerfect.PREF_BALANCE, 0).putBoolean(BitPerfect.PREF_REPLAYGAIN, false).commit();
            float[] gCenter = BitPerfect.channelGains(app);
            assertEquals(1.0f, gCenter[0], 0.01f);
            assertEquals(1.0f, gCenter[1], 0.01f);
            MainActivity.prefs(app).edit().putInt(BitPerfect.PREF_BALANCE, -50).putBoolean(BitPerfect.PREF_REPLAYGAIN, true).commit();
            float[] gLeft = BitPerfect.channelGains(app);
            assertTrue("Left louder than right when balanced left", gLeft[0] > gLeft[1]);
            MainActivity.prefs(app).edit().putInt(BitPerfect.PREF_BALANCE, 0).putBoolean(BitPerfect.PREF_REPLAYGAIN, false).commit();

            // (1) YouTube Music home screenshot with fixture shelves
            Ui.gStyle = false;
            MainActivity.prefs(app).edit().putString("player_style", "prism").putString("source", "ytm").commit();
            YouTubeMusic.testFixtures = k9517 -> {
                java.util.List<Widgets.Item> l = new java.util.ArrayList<>();
                l.add(YouTubeMusic.buildItem("dQw4w9WgXcQ", "Locked out of Heaven", "Bruno Mars", "Unorthodox Jukebox", "", 233_000L));
                l.add(YouTubeMusic.buildItem("kJQP7kiw5Fk", "Die With A Smile", "Lady Gaga & Bruno Mars", "Single", "", 251_000L));
                l.add(YouTubeMusic.buildItem("fRh_vgS2dFE", "Blinding Lights", "The Weeknd", "After Hours", "", 200_000L));
                l.add(YouTubeMusic.buildItem("JGwWNGJdvx8", "APT.", "ROSE & Bruno Mars", "rosie", "", 169_000L));
                return l;
            };
            ActivityController<MainActivity> ytmC = launch("ko-w393dp-h852dp-xxhdpi");
            MainActivity ya = ytmC.get();
            fakeTrack();
            idle(900);
            shot(ya, "90_ytm_home");

            // (7) & (9) A-Style Expanded Player with "Switch to video" pill + snug compact horizontal active pill capsule + GIF!
            Player.playingSourceUri = "spotify:playlist:pl_demo";
            Player.playingSourceTitle = "My Favorite Hits";
            ya.np.open(); idle(900);
            shot(ya, "91_player_a_style_9517");

            // Capture multi-frame animated GIF of non-bouncy Liquid Glass bottom pill glide (lyrics -> queue -> artwork)
            java.util.List<Bitmap> pillFrames = new java.util.ArrayList<>();
            java.lang.reflect.Method setPanelM = NowPlaying.class.getDeclaredMethod("setPanel", int.class);
            setPanelM.setAccessible(true);
            java.lang.reflect.Field lyrBtnF = NowPlaying.class.getDeclaredField("lyricsBtn");
            java.lang.reflect.Field qBtnF = NowPlaying.class.getDeclaredField("queueBtn");
            java.lang.reflect.Field apF = NowPlaying.class.getDeclaredField("actionPill");
            lyrBtnF.setAccessible(true); qBtnF.setAccessible(true); apF.setAccessible(true);
            View lyrBtnV = (View) lyrBtnF.get(ya.np), qBtnV = (View) qBtnF.get(ya.np);
            Object actionPillObj = apF.get(ya.np);
            java.lang.reflect.Method pulseAtM = actionPillObj.getClass().getDeclaredMethod("pulseAt", View.class);
            pulseAtM.setAccessible(true);
            pillFrames.add(captureScaled(ya, 0.5f));
            pulseAtM.invoke(actionPillObj, lyrBtnV);
            setPanelM.invoke(ya.np, 1);
            for (int f = 0; f < 5; f++) { idle(70); pillFrames.add(captureScaled(ya, 0.5f)); }
            pulseAtM.invoke(actionPillObj, qBtnV);
            setPanelM.invoke(ya.np, 2);
            for (int f = 0; f < 6; f++) { idle(70); pillFrames.add(captureScaled(ya, 0.5f)); }
            shot(ya, "92_player_queue_9517");
            writeGif(new File(OUT, "97_player_pill_glide.gif"), pillFrames, 110);
            for (Bitmap fb : pillFrames) fb.recycle();
            ya.np.close(); idle(400);

            // (6) Local + YouTube Music Recap screenshots (default period: All time)
            java.util.List<Player.Track> demoRecap = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Player.Track tr = new Player.Track();
                tr.id = "local:" + (100 + i);
                tr.uri = "content://media/external/audio/media/" + (100 + i);
                tr.name = new String[]{"Locked out of Heaven", "Supernova", "Night Dancer", "Ditto"}[i];
                tr.artist = new String[]{"Bruno Mars", "aespa", "imase", "NewJeans"}[i];
                tr.album = new String[]{"Unorthodox Jukebox", "Armageddon", "Bonsai", "OMG"}[i];
                tr.duration = 215_000L;
                demoRecap.add(tr);
            }
            for (int i = 0; i < 3; i++) {
                Player.Track tr = new Player.Track();
                tr.id = "ytm:ytvid0000" + i;
                tr.uri = "ytm:track:ytvid0000" + i;
                tr.name = new String[]{"ray - 超かぐや姫！ Version", "壱雫空", "春日影"}[i];
                tr.artist = new String[]{"かぐや(cv.夏吉ゆうこ)", "MyGO!!!!!", "MyGO!!!!!"}[i];
                tr.album = new String[]{"超かぐや姫！", "迷跡波", "迷跡波"}[i];
                tr.duration = 240_000L;
                demoRecap.add(tr);
            }
            Recap.seedForTest(app, demoRecap);
            ya.push(new Pages.RecapPage(ya, 2)); idle(600);
            shot(ya, "93_recap_local");
            ya.pop(); idle(250);
            ya.push(new Pages.RecapPage(ya, 1)); idle(600);
            shot(ya, "93b_recap_ytm");
            ya.pop(); idle(250);

            // (8) Overhauled Bit-Perfect Assist screen + (5) Kyant0 LiquidToggle GIF
            NpuAudio.testSupportedOverride = Boolean.TRUE;
            NpuAudio.testSocOverride = "SM8850";
            MainActivity.prefs(app).edit().putBoolean(NpuAudio.PREF_ON, true).commit();
            ya.push(new Settings.BitPerfectAssistPage(ya)); idle(600);
            shot(ya, "94_bitperfect_assist_9517");
            ya.pop(); idle(250);

            // Capture standalone Kyant0 LiquidToggle animation GIF (off -> pressed refractive glass -> on)
            java.util.List<Bitmap> togFrames = new java.util.ArrayList<>();
            Widgets.Toggle demoT = new Widgets.Toggle(ya, false);
            demoT.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(64), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(Ui.dp(36), View.MeasureSpec.EXACTLY));
            demoT.layout(0, 0, demoT.getMeasuredWidth(), demoT.getMeasuredHeight());
            float[][] states = {{0f, 0f}, {0.05f, 0.6f}, {0.25f, 1.0f}, {0.55f, 1.0f}, {0.85f, 1.0f}, {1.0f, 0.7f}, {1.0f, 0.2f}, {1.0f, 0f}};
            for (float[] st : states) {
                demoT.setPreviewState(st[0], st[1]);
                Bitmap b = Bitmap.createBitmap(Ui.dp(120), Ui.dp(72), Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(b);
                cv.drawColor(0xFFF2F2F7);
                cv.translate(Ui.dp(28), Ui.dp(18));
                demoT.draw(cv);
                togFrames.add(b);
            }
            writeGif(new File(OUT, "96_kyant0_toggle_anim.gif"), togFrames, 120);
            for (Bitmap fb : togFrames) fb.recycle();

            // (4) Search suggestions screenshot
            MainActivity.prefs(app).edit().putString("search_history_v1", "Bruno Mars\nIU\naespa").commit();
            ya.onSearch(); idle(400);
            ya.onQuery("Bru", false); idle(300);
            shot(ya, "95_search_suggestions");
            ya.onExitSearch(); idle(200);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            YouTubeMusic.testFixtures = null;
        }

        AudioInfo.testSpec = null;
        NpuAudio.testSupportedOverride = null;
        NpuAudio.testSocOverride = null;

        // restore defaults
        Ui.gStyle = false;
        MainActivity.prefs(app).edit().putString("accent_color", "red").putString("player_style", "prism").putString("source", "spotify").commit();
        Ui.ACCENT = Ui.ACCENT_DEFAULT;
        a.refreshAppearance(); idle(300);
        // the media notification's prism icon, large, on a dark status-bar colour
        android.graphics.drawable.Drawable ic = a.getDrawable(R.drawable.ic_stat_prism);
        Bitmap ib = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888);
        Canvas icv = new Canvas(ib);
        icv.drawColor(0xFF202124);
        ic.setBounds(24, 24, 216, 216);
        ic.draw(icv);
        try (FileOutputStream o = new FileOutputStream(new File(OUT, "62_notification_icon.png"))) { ib.compress(Bitmap.CompressFormat.PNG, 100, o); }
        LocalMusic.songs.clear();
    }

    @Test public void ytmWebPlayerEngine() {
        android.content.Context app = RuntimeEnvironment.getApplication();
        YouTubeMusic.app = app;
        YouTubeMusic.testFixtures = k -> java.util.Collections.emptyList();
        try {
            Player.Track t1 = YouTubeMusic.buildItem("kJQP7kiw5Fk", "Despacito", "Luis Fonsi", "", "", 282000L).track;
            Player.Track t2 = YouTubeMusic.buildItem("7wtfhZwyrcc", "Believer", "Imagine Dragons", "", "", 204000L).track;
            java.util.List<Player.Track> list = java.util.Arrays.asList(t1, t2);

            Player.repeat = "off";
            Player.shuffle = false;
            YouTubeMusic.play(list, 0, "YouTube Music");
            int gen1 = YouTubeMusic.playGen;
            assertEquals("ytm:kJQP7kiw5Fk", Player.track.id);
            assertTrue("playing immediately after play()", Player.playing);

            // Ad state must NOT overwrite duration or pause track, AND must expose live ad status text
            YouTubeMusic.handleWebState(gen1, true, 5000L, 15000L, true, 12, false);
            assertEquals(282000L, Player.track.duration);
            assertTrue("adPlaying must be true during ad", Player.adPlaying);
            assertEquals(12, Player.adRemainSec);
            assertTrue("ad status label formatted", Player.adStatusText(app).contains("0:12"));

            // Skippable ad updates status text
            YouTubeMusic.handleWebState(gen1, true, 10000L, 15000L, true, 5, true);
            assertTrue("adSkippable must be true", Player.adSkippable);

            // Real playback past 1-minute mark (75s and 180s) clears ad state, updates progress & stays playing
            YouTubeMusic.handleWebState(gen1, true, 75000L, 281565L, false, 0, false);
            assertEquals("adPlaying cleared when song starts", false, Player.adPlaying);
            assertEquals("", Player.adStatusText(app));
            assertTrue("still playing past 1 minute (75s)", Player.playing);
            assertEquals(75000L, Player.progress);
            assertEquals(281565L, Player.track.duration);

            // Ad-blocker URL matcher: blocks ad hosts/paths, NEVER blocks videoplayback or player API
            assertTrue(YouTubeMusic.isAdUrl("https://googleads.g.doubleclick.net/pagead/id"));
            assertTrue(YouTubeMusic.isAdUrl("https://m.youtube.com/pagead/interaction/?ai=123"));
            assertTrue(YouTubeMusic.isAdUrl("https://m.youtube.com/api/stats/ads?foo=bar"));
            assertEquals(false, YouTubeMusic.isAdUrl("https://rr1---sn-3u-bh2lk.googlevideo.com/videoplayback?expire=123"));
            assertEquals(false, YouTubeMusic.isAdUrl("https://m.youtube.com/youtubei/v1/player?prettyPrint=false"));

            YouTubeMusic.handleWebState(gen1, true, 180000L, 281565L, false);
            assertTrue("still playing at 3 minutes (180s)", Player.playing);
            assertEquals(180000L, Player.progress);

            // Stale generation callbacks are ignored
            YouTubeMusic.handleWebState(gen1 - 1, false, 0L, 1000L, false);
            assertTrue("stale gen ignored", Player.playing);
            YouTubeMusic.handleWebEnded(gen1 - 1);
            assertEquals("stale ended ignored", "ytm:kJQP7kiw5Fk", Player.track.id);

            // Track end advances cleanly to next track in Prism queue
            YouTubeMusic.handleWebEnded(gen1);
            assertEquals("advanced to track 2", "ytm:7wtfhZwyrcc", Player.track.id);
            assertEquals(1, YouTubeMusic.currentIndex());
            assertTrue(YouTubeMusic.playGen > gen1);

            // Duplicate onEnded for same gen is ignored
            int gen2 = YouTubeMusic.playGen;
            YouTubeMusic.handleWebEnded(gen1);
            assertEquals("duplicate ended ignored", gen2, YouTubeMusic.playGen);

            // Toggle pause/resume and seek work cleanly
            YouTubeMusic.toggle();
            assertEquals(false, Player.playing);
            YouTubeMusic.toggle();
            assertTrue(Player.playing);
            YouTubeMusic.seek(95000L);
            assertEquals(95000L, Player.progress);

            // Sanitize videoId strips any JS-unsafe characters
            assertEquals("kJQP7kiw5Fk", YouTubeMusic.sanitizeVideoId("kJQP7kiw5Fk'\"();"));

            // Stop when switching to another source
            YouTubeMusic.stopForOther();
            assertTrue(YouTubeMusic.playGen > gen2);
        } finally {
            YouTubeMusic.testFixtures = null;
            YouTubeMusic.stopWeb();
        }
    }

    @Test public void ytmAlbumOpensDetailInsteadOfPlayingTrack() throws Exception {
        // Album card that contains BOTH browseEndpoint (MPREb_...) AND an overlay quick-play videoId!
        // Regression test: MUST be classified as ytm_album (never ytm_track), and tapping it MUST open YtmDetailPage!
        org.json.JSONObject twoRowAlbum = new org.json.JSONObject("{"
                + "\"title\":{\"runs\":[{\"text\":\"LILAC\",\"navigationEndpoint\":{\"browseEndpoint\":{\"browseId\":\"MPREb_YSzaOfn5zXc\",\"browseEndpointContextSupportedConfigs\":{\"browseEndpointContextMusicConfig\":{\"pageType\":\"MUSIC_PAGE_TYPE_ALBUM\"}}}}}]},"
                + "\"subtitle\":{\"runs\":[{\"text\":\"앨범\"},{\"text\":\" • \"},{\"text\":\"아이유(IU)\"}]},"
                + "\"navigationEndpoint\":{\"browseEndpoint\":{\"browseId\":\"MPREb_YSzaOfn5zXc\",\"browseEndpointContextSupportedConfigs\":{\"browseEndpointContextMusicConfig\":{\"pageType\":\"MUSIC_PAGE_TYPE_ALBUM\"}}}},"
                + "\"thumbnailOverlay\":{\"musicItemThumbnailOverlayRenderer\":{\"content\":{\"musicPlayButtonRenderer\":{\"playNavigationEndpoint\":{\"watchEndpoint\":{\"videoId\":\"v7bnOxV4jAc\"}}}}}}"
                + "}");
        Widgets.Item parsedAlbum = YouTubeMusic.parseTwoRowItemAny(twoRowAlbum);
        assertNotNull(parsedAlbum);
        assertEquals("ytm_album", parsedAlbum.type);
        assertEquals("MPREb_YSzaOfn5zXc", parsedAlbum.id);
        assertEquals("ytm:browse:MPREb_YSzaOfn5zXc", parsedAlbum.uri);
        assertTrue("album must not have a direct track", parsedAlbum.track == null);

        // Album list row with overlay videoId also parses as ytm_album
        org.json.JSONObject rliAlbum = new org.json.JSONObject("{"
                + "\"navigationEndpoint\":{\"browseEndpoint\":{\"browseId\":\"MPREb_SjkPQ0Tt3Gn\",\"browseEndpointContextSupportedConfigs\":{\"browseEndpointContextMusicConfig\":{\"pageType\":\"MUSIC_PAGE_TYPE_ALBUM\"}}}},"
                + "\"flexColumns\":[{\"musicResponsiveListItemFlexColumnRenderer\":{\"text\":{\"runs\":[{\"text\":\"Love poem\"}]}}},{\"musicResponsiveListItemFlexColumnRenderer\":{\"text\":{\"runs\":[{\"text\":\"EP • 아이유\"}]}}}],"
                + "\"overlay\":{\"musicItemThumbnailOverlayRenderer\":{\"content\":{\"musicPlayButtonRenderer\":{\"playNavigationEndpoint\":{\"watchEndpoint\":{\"videoId\":\"D1PvIWdJ8xo\"}}}}}}"
                + "}");
        Widgets.Item parsedRliAlbum = YouTubeMusic.parseListItemAny(rliAlbum);
        assertNotNull(parsedRliAlbum);
        assertEquals("ytm_album", parsedRliAlbum.type);
        assertTrue(parsedRliAlbum.track == null);

        // Actual track row parses as ytm_track AND preserves linked album/artist browseIds
        org.json.JSONObject rliTrack = new org.json.JSONObject("{"
                + "\"playlistItemData\":{\"videoId\":\"v7bnOxV4jAc\"},"
                + "\"flexColumns\":["
                + "  {\"musicResponsiveListItemFlexColumnRenderer\":{\"text\":{\"runs\":[{\"text\":\"라일락\",\"navigationEndpoint\":{\"watchEndpoint\":{\"videoId\":\"v7bnOxV4jAc\"}}}]}}},"
                + "  {\"musicResponsiveListItemFlexColumnRenderer\":{\"text\":{\"runs\":[{\"text\":\"아이유\",\"navigationEndpoint\":{\"browseEndpoint\":{\"browseId\":\"UCTUR0sVEkD8T5MlSHqgaI_Q\",\"browseEndpointContextSupportedConfigs\":{\"browseEndpointContextMusicConfig\":{\"pageType\":\"MUSIC_PAGE_TYPE_ARTIST\"}}}}},{\"text\":\" • \"},{\"text\":\"LILAC\",\"navigationEndpoint\":{\"browseEndpoint\":{\"browseId\":\"MPREb_YSzaOfn5zXc\",\"browseEndpointContextSupportedConfigs\":{\"browseEndpointContextMusicConfig\":{\"pageType\":\"MUSIC_PAGE_TYPE_ALBUM\"}}}}}]}}}"
                + "]}");
        Widgets.Item parsedTrack = YouTubeMusic.parseListItemAny(rliTrack);
        assertNotNull(parsedTrack);
        assertEquals("ytm_track", parsedTrack.type);
        assertNotNull(parsedTrack.track);
        assertEquals("ytm:browse:MPREb_YSzaOfn5zXc", parsedTrack.track.albumUri);
        assertEquals("ytm:browse:UCTUR0sVEkD8T5MlSHqgaI_Q", parsedTrack.track.artistUri);

        // Verify MainActivity.openItem(parsedAlbum) opens YtmDetailPage without starting track playback
        YouTubeMusic.testFixtures = k -> java.util.Collections.singletonList(parsedTrack);
        try {
            Player.track = null;
            Player.playing = false;
            ActivityController<MainActivity> c = launch("ko-w393dp-h852dp-xxhdpi");
            MainActivity a = c.get();
            a.openItem(parsedAlbum);
            idle(500);
            assertTrue("openItem(ytm_album) must push YtmDetailPage", a.currentPage() instanceof Pages.YtmDetailPage);
            assertTrue("opening album must NOT auto-play a song", Player.track == null && !Player.playing);
            shot(a, "97_ytm_album_detail");
        } finally {
            YouTubeMusic.testFixtures = null;
            YouTubeMusic.stopWeb();
        }
    }

    @Test public void prism9527RegressionsAndUnifiedPlaylist() throws Exception {
        // 1) Home shelf header MUST strip strapline account name and moreContentButton ("더보기")
        org.json.JSONObject shelf = new org.json.JSONObject("{"
                + "\"header\":{\"musicCarouselShelfBasicHeaderRenderer\":{"
                + "  \"strapline\":{\"runs\":[{\"text\":\"Jiho\"}]},"
                + "  \"title\":{\"runs\":[{\"text\":\"다시 듣기\"}]},"
                + "  \"moreContentButton\":{\"buttonRenderer\":{\"text\":{\"runs\":[{\"text\":\"더보기\"}]}}}"
                + "}}}");
        assertEquals("다시 듣기", YouTubeMusic.extractShelfTitle(shelf));

        // 2) Album detail cover MUST come from primary thumbnail, NEVER straplineThumbnail (artist image!)
        org.json.JSONObject albumDetail = new org.json.JSONObject("{"
                + "\"header\":{\"musicResponsiveHeaderRenderer\":{"
                + "  \"title\":{\"runs\":[{\"text\":\"LILAC\"}]},"
                + "  \"straplineTextOne\":{\"runs\":[{\"text\":\"아이유\",\"navigationEndpoint\":{\"browseEndpoint\":{\"browseId\":\"UC_IU_ARTIST\"}}}]},"
                + "  \"thumbnail\":{\"musicThumbnailRenderer\":{\"thumbnail\":{\"thumbnails\":[{\"url\":\"https://lh3.googleusercontent.com/ALBUM_COVER=w226-h226\",\"width\":226}]}}},"
                + "  \"straplineThumbnail\":{\"musicThumbnailRenderer\":{\"thumbnail\":{\"thumbnails\":[{\"url\":\"https://lh3.googleusercontent.com/ARTIST_AVATAR=w544-h544\",\"width\":544}]}}}"
                + "}}}");
        YouTubeMusic.DetailData d = new YouTubeMusic.DetailData();
        d.type = "ytm_album";
        d.browseId = "MPREb_LILAC";
        YouTubeMusic.parseDetailResponse(albumDetail, d);
        assertTrue("Album image must NOT be overwritten by larger artist straplineThumbnail", d.image.contains("ALBUM_COVER"));
        assertEquals("UC_IU_ARTIST", d.artistBrowseId);

        // 3) Library artist MPLA prefix normalized to full public channel UC...
        assertEquals("UC1234567890", YouTubeMusic.normalizeArtistBrowseId("MPLAUC1234567890"));

        // 4) Cross-platform Unified Playlist (Spotify + YouTube Music mixed) + UI screenshot
        android.content.Context app = RuntimeEnvironment.getApplication();
        UnifiedPlaylist.resetCacheForTest();
        UnifiedPlaylist.Playlist upl = UnifiedPlaylist.create(app, "드라이브 믹스", "Spotify + YouTube Music");
        assertNotNull(upl);
        UnifiedPlaylist.Entry eSp = new UnifiedPlaylist.Entry();
        eSp.source = UnifiedPlaylist.SRC_SPOTIFY;
        eSp.id = "sp_1";
        eSp.uri = "spotify:track:sp_1";
        eSp.title = "Supernova";
        eSp.artist = "aespa";
        UnifiedPlaylist.Entry eYt = new UnifiedPlaylist.Entry();
        eYt.source = UnifiedPlaylist.SRC_YTM;
        eYt.id = "ytm:dQw4w9WgXcQ";
        eYt.uri = "ytm:track:dQw4w9WgXcQ";
        eYt.title = "ラピスラズリ (YouTube Music)";
        eYt.artist = "Morfonica";
        assertTrue(UnifiedPlaylist.addEntry(app, upl.id, eSp));
        assertTrue(UnifiedPlaylist.addEntry(app, upl.id, eYt));

        // Verify persistence reload
        UnifiedPlaylist.resetCacheForTest();
        UnifiedPlaylist.Playlist reloaded = UnifiedPlaylist.get(app, upl.id);
        assertNotNull(reloaded);
        assertEquals(2, reloaded.tracks.size());
        assertEquals(UnifiedPlaylist.SRC_SPOTIFY, reloaded.tracks.get(0).source);
        assertEquals(UnifiedPlaylist.SRC_YTM, reloaded.tracks.get(1).source);

        // Render Unified Playlist detail page screenshot
        ActivityController<MainActivity> ctrl = launch("ko-w393dp-h852dp-xxhdpi");
        MainActivity a = ctrl.get();
        a.push(new Pages.UnifiedPlaylistDetailPage(a, upl.id));
        idle(500);
        assertTrue(a.currentPage() instanceof Pages.UnifiedPlaylistDetailPage);
        shot(a, "98_unified_playlist_detail");
        UnifiedPlaylist.delete(app, upl.id);
    }

    private static Bitmap captureScaled(MainActivity act, float scale) {
        View root = act.getWindow().getDecorView();
        int w = Math.max(1, Math.round(root.getWidth() * scale));
        int h = Math.max(1, Math.round(root.getHeight() * scale));
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(scale, scale);
        root.draw(c);
        return b;
    }

    /** Encodes a list of Android Bitmaps into a looping animated GIF using JDK javax.imageio. */
    private static void writeGif(File outFile, java.util.List<Bitmap> frames, int delayMs) throws Exception {
        if (frames == null || frames.isEmpty()) return;
        javax.imageio.ImageWriter writer = javax.imageio.ImageIO.getImageWritersByFormatName("gif").next();
        try (javax.imageio.stream.ImageOutputStream ios = javax.imageio.ImageIO.createImageOutputStream(outFile)) {
            writer.setOutput(ios);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < frames.size(); i++) {
                Bitmap bm = frames.get(i);
                int w = bm.getWidth(), h = bm.getHeight();
                java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
                int[] px = new int[w * h];
                bm.getPixels(px, 0, w, 0, 0, w, h);
                bi.setRGB(0, 0, w, h, px, 0, w);
                javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
                javax.imageio.metadata.IIOMetadata meta = writer.getDefaultImageMetadata(
                        javax.imageio.ImageTypeSpecifier.createFromRenderedImage(bi), param);
                String fmt = meta.getNativeMetadataFormatName();
                javax.imageio.metadata.IIOMetadataNode root = (javax.imageio.metadata.IIOMetadataNode) meta.getAsTree(fmt);
                javax.imageio.metadata.IIOMetadataNode gce = new javax.imageio.metadata.IIOMetadataNode("GraphicControlExtension");
                gce.setAttribute("disposalMethod", "none");
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "FALSE");
                gce.setAttribute("delayTime", String.valueOf(Math.max(2, delayMs / 10)));
                gce.setAttribute("transparentColorIndex", "0");
                root.appendChild(gce);
                if (i == 0) {
                    javax.imageio.metadata.IIOMetadataNode appExts = new javax.imageio.metadata.IIOMetadataNode("ApplicationExtensions");
                    javax.imageio.metadata.IIOMetadataNode appExt = new javax.imageio.metadata.IIOMetadataNode("ApplicationExtension");
                    appExt.setAttribute("applicationID", "NETSCAPE");
                    appExt.setAttribute("authenticationCode", "2.0");
                    appExt.setUserObject(new byte[]{0x1, 0x0, 0x0});
                    appExts.appendChild(appExt);
                    root.appendChild(appExts);
                }
                meta.setFromTree(fmt, root);
                writer.writeToSequence(new javax.imageio.IIOImage(bi, null, meta), param);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }
}
