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
            if (req.startsWith("GET /search")) return new org.json.JSONObject().put("playlists", new org.json.JSONObject().put("items", pls));
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
}
