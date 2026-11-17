package com.oneuibook.prism;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/** Settings screens (grouped inset lists). */
final class Settings {
    private Settings() {}

    static boolean pref(Context c, String k, boolean def) { return MainActivity.prefs(c).getBoolean(k, def); }

    /** Has a hinge (Galaxy Z Fold…), or is showing a near-square unfolded screen right now. */
    static boolean isFoldable(Context c) {
        if (c.getPackageManager().hasSystemFeature("android.hardware.sensor.hinge_angle")) return true;
        float wd = Ui.screenW / Ui.density, hd = Ui.screenH / Ui.density;
        return Math.min(wd, hd) >= 600 && Math.max(wd, hd) / Math.max(1f, Math.min(wd, hd)) < 1.35f;
    }

    static String glassLabel(Context c, float t) {
        if (t <= 0.005f) return c.getString(R.string.glass_opaque);
        if (Math.abs(t - 0.5f) < 0.005f) return c.getString(R.string.glass_default);
        return Math.round(t * 100) + "%";
    }

    static View toggleRow(Widgets.Group g, int icon, String title, String key, boolean def, Runnable after) {
        View r = g.row(icon, Ui.ACCENT, title, null, false, null);
        Widgets.Toggle t = new Widgets.Toggle(g.getContext(), pref(g.getContext(), key, def));
        t.change = on -> {
            MainActivity.prefs(g.getContext()).edit().putBoolean(key, on).apply();
            if (after != null) after.run();
        };
        ((LinearLayout) r).addView(t);
        return r;
    }

    // ------------------------------------------------------------------ root
    static final class SettingsPage extends Page {
        SettingsPage(MainActivity m) {
            super(m, m.getString(R.string.tab_settings), true);
            headerIcons = new int[]{Icons.PROFILE};
            setGrouped(true);
        }

        @Override void build() {
            MainActivity m = main;
            Widgets.Group acc = new Widgets.Group(m, m.getString(R.string.set_account));
            String who = Spotify.loggedIn() ? Widgets.nonEmpty(Spotify.userName, m.getString(R.string.connected)) : m.getString(R.string.not_connected);
            acc.row(Icons.PROFILE, Ui.ACCENT, "Spotify", who, true, v -> m.push(new AccountPage(m)));
            content.addView(acc, Widgets.MW);

            Widgets.Group app = new Widgets.Group(m, m.getString(R.string.set_application));
            app.row(Icons.SETTINGS, Ui.ACCENT, m.getString(R.string.set_general), null, true, v -> m.push(new GeneralPage(m)));
            app.row(Icons.APPEARANCE, Ui.ACCENT, m.getString(R.string.set_appearance), null, true, v -> m.push(new AppearancePage(m)));
            app.row(Icons.CONTENT, Ui.ACCENT, m.getString(R.string.set_content), null, true, v -> m.push(new ContentPage(m)));
            app.row(Icons.WAVE, Ui.ACCENT, m.getString(R.string.set_playback), null, true, v -> m.push(new PlaybackPage(m)));
            app.row(Icons.LYRICS, Ui.ACCENT, m.getString(R.string.set_lyrics), null, true, v -> m.push(new LyricsPage(m)));
            app.row(Icons.STORAGE, Ui.ACCENT, m.getString(R.string.set_storage), null, true, v -> m.push(new StoragePage(m)));
            content.addView(app, Widgets.MW);

            Widgets.Group about = new Widgets.Group(m, null);
            about.row(Icons.INFO, Ui.ACCENT, m.getString(R.string.set_about), MainActivity.VERSION, true, v -> m.push(new AboutPage(m)));
            content.addView(about, Widgets.MW);
        }
    }

    abstract static class Sub extends Page {
        Sub(MainActivity m, int title) {
            super(m, m.getString(title), true);
            back = true;
            headerIcons = new int[0];
            setGrouped(true);
        }
    }

    // ------------------------------------------------------------------ account
    static final class AccountPage extends Sub {
        AccountPage(MainActivity m) { super(m, R.string.set_spotify_account); }

        @Override void build() {
            MainActivity m = main;
            Widgets.Group st = new Widgets.Group(m, m.getString(R.string.set_status));
            if (Spotify.loggedIn()) {
                String plan = Spotify.product == null ? "" : ("premium".equals(Spotify.product) ? "Premium" : "Free");
                st.row(Icons.PROFILE, Ui.ACCENT, Widgets.nonEmpty(Spotify.userName, m.getString(R.string.connected)), plan, false, null);
                st.row(Icons.LINK, Ui.ACCENT, m.getString(R.string.reconnect), Spotify.hasScope("streaming") ? null : m.getString(R.string.needed_for_inapp), true, v -> Spotify.login(m));
                st.row(Icons.LOGOUT, Ui.ACCENT, m.getString(R.string.logout), null, false, v -> {
                    Spotify.logout();
                    Player.track = null;
                    Player.notifyAll0();
                    m.reloadAll();
                    rebuild();
                });
            } else {
                st.row(Icons.PROFILE, Ui.ACCENT, m.getString(R.string.not_connected), null, false, null);
                st.row(Icons.LINK, Ui.ACCENT, m.getString(R.string.connect_spotify), null, true, v -> {
                    if (!Spotify.configured()) { editClientId(); return; }
                    Spotify.login(m);
                });
            }
            content.addView(st, Widgets.MW);
            if (Spotify.loggedIn() && !Spotify.isPremium())
                content.addView(Widgets.caption(m, m.getString(R.string.free_note)));

            Widgets.Group dev = new Widgets.Group(m, m.getString(R.string.set_developer));
            String id = Spotify.clientId();
            dev.row(Icons.KEY, Ui.ACCENT, "Client ID", id.isEmpty() ? m.getString(R.string.not_set) : mask(id), true, v -> editClientId());
            dev.row(Icons.LINK, Ui.ACCENT, "Redirect URI", Spotify.REDIRECT, false, v -> {
                ClipboardManager cm = (ClipboardManager) m.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Redirect URI", Spotify.REDIRECT));
                Toast.makeText(m, R.string.copied, Toast.LENGTH_SHORT).show();
            });
            dev.row(Icons.GLOBE, Ui.ACCENT, m.getString(R.string.open_dashboard), null, true, v -> {
                try { m.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.spotify.com/dashboard"))); } catch (Exception ignored) {}
            });
            content.addView(dev, Widgets.MW);
            content.addView(Widgets.caption(m, m.getString(R.string.setup_steps, Spotify.REDIRECT, m.getPackageName())));
        }

        private static String mask(String s) { return s.length() <= 8 ? s : s.substring(0, 4) + "…" + s.substring(s.length() - 4); }

        private void editClientId() {
            MainActivity m = main;
            EditText e = new EditText(m);
            e.setSingleLine(true);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            e.setText(Spotify.clientId());
            e.setHint("0123456789abcdef…");
            FrameLayout f = new FrameLayout(m);
            f.setPadding(Ui.dp(22), Ui.dp(8), Ui.dp(22), 0);
            f.addView(e);
            new AlertDialog.Builder(m)
                    .setTitle("Spotify Client ID")
                    .setMessage(R.string.client_id_help)
                    .setView(f)
                    .setPositiveButton(R.string.save, (d, w) -> {
                        String v = e.getText().toString().trim().replaceAll("[^A-Za-z0-9]", "");
                        MainActivity.prefs(m).edit().putString("client_id", v).apply();
                        rebuild();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }
    }

    // ------------------------------------------------------------------ general (language)
    static final class GeneralPage extends Sub {
        GeneralPage(MainActivity m) { super(m, R.string.set_general); }

        @Override void build() {
            MainActivity m = main;
            String cur = MainActivity.prefs(m).getString("lang", "system");
            Widgets.Group g = new Widgets.Group(m, m.getString(R.string.set_language));
            String[][] opts = {{"system", m.getString(R.string.lang_system)}, {"ko", "한국어"}, {"en", "English"}, {"ja", "日本語"}};
            for (String[] o : opts) g.option(o[1], o[0].equals(cur), v -> {
                MainActivity.prefs(m).edit().putString("lang", o[0]).apply();
                m.recreate();
            });
            content.addView(g, Widgets.MW);
        }
    }

    // ------------------------------------------------------------------ appearance
    static final class AppearancePage extends Sub {
        AppearancePage(MainActivity m) { super(m, R.string.set_appearance); }

        @Override void build() {
            MainActivity m = main;
            String cur = MainActivity.prefs(m).getString("theme", "system");
            Widgets.Group g = new Widgets.Group(m, m.getString(R.string.set_theme));
            String[][] opts = {{"system", m.getString(R.string.theme_system)}, {"light", m.getString(R.string.theme_light)}, {"dark", m.getString(R.string.theme_dark)}};
            for (String[] o : opts) g.option(o[1], o[0].equals(cur), v -> {
                MainActivity.prefs(m).edit().putString("theme", o[0]).apply();
                m.recreate();
            });
            content.addView(g, Widgets.MW);
            if (isFoldable(m)) {
                // unfolded inner screen: the foldable layout (right-hand tab rail) or the tablet one (top tabs + sidebar)
                Widgets.Group uf = new Widgets.Group(m, m.getString(R.string.unfold_ui));
                String curUf = MainActivity.prefs(m).getString("unfold_ui", "fold");
                String[][] ufs = {{"fold", m.getString(R.string.unfold_fold)}, {"tablet", m.getString(R.string.unfold_tablet)}};
                for (String[] o : ufs) uf.option(o[1], o[0].equals(curUf), v -> {
                    if (o[0].equals(MainActivity.prefs(m).getString("unfold_ui", "fold"))) return;
                    MainActivity.prefs(m).edit().putString("unfold_ui", o[0]).apply();
                    m.recreate();
                });
                content.addView(uf, Widgets.MW);
                content.addView(Widgets.caption(m, m.getString(R.string.unfold_note)));
            }
            Widgets.Group ui = new Widgets.Group(m, m.getString(R.string.set_interface));
            toggleRow(ui, Icons.CONTENT, m.getString(R.string.keep_expanded), "keep_expanded", false, () -> m.dock.setCollapsed(false));
            int raise = MainActivity.prefs(m).getInt("raise_dp", Dock.RAISE_DEFAULT);
            View rr = ui.row(Icons.CHEVRON_UP, Ui.ACCENT, m.getString(R.string.raise_controls), raise + " dp", false, null);
            Widgets.GlassSlider sl = new Widgets.GlassSlider(m, raise / 24f, 12);
            sl.change = v -> {
                int dp = Math.round(v * 24);
                MainActivity.prefs(m).edit().putInt("raise_dp", dp).apply();
                Object tv = rr.getTag();
                if (tv instanceof TextView) ((TextView) tv).setText(dp + " dp");
                m.dock.requestLayout();
                m.dock.invalidate();
            };
            ui.card.addView(sl, Widgets.MW);
            content.addView(ui, Widgets.MW);
            content.addView(Widgets.caption(m, m.getString(R.string.raise_note)));
            Widgets.Group lv = new Widgets.Group(m, null);
            View lr = lv.row(Icons.APPEARANCE, Ui.ACCENT, m.getString(R.string.glass_level), glassLabel(m, Ui.glassT), false, null);
            // 0 = opaque, middle = default, 1 = the old "high" transparency; snaps to the middle near it
            Widgets.GlassSlider gs = new Widgets.GlassSlider(m, Ui.glassT, 10);
            gs.change = v -> {
                float t = Math.abs(v - 0.5f) < 0.03f ? 0.5f : v;
                Ui.glassT = t;
                MainActivity.prefs(m).edit().putFloat("glass_t", t).apply();
                Object tv = lr.getTag();
                if (tv instanceof TextView) ((TextView) tv).setText(glassLabel(m, t));
                m.invalidateChrome();
            };
            lv.card.addView(gs, Widgets.MW);
            content.addView(lv, Widgets.MW);
            Widgets.Group fx = new Widgets.Group(m, m.getString(R.string.set_effects));
            toggleRow(fx, Icons.APPEARANCE, m.getString(R.string.glass_effect), "glass", true, () -> { Ui.glass = pref(m, "glass", true); m.invalidateChrome(); });
            content.addView(fx, Widgets.MW);
            content.addView(Widgets.caption(m, m.getString(R.string.glass_note)));
        }
    }

    // ------------------------------------------------------------------ content
    static final class ContentPage extends Sub {
        ContentPage(MainActivity m) { super(m, R.string.set_content); }

        @Override void build() {
            MainActivity m = main;
            Widgets.Group g = new Widgets.Group(m, m.getString(R.string.set_home_sections));
            toggleRow(g, Icons.ARTIST, m.getString(R.string.home_artists), "home_artists", true, m::reloadContent);
            toggleRow(g, Icons.ALBUM, m.getString(R.string.home_new), "home_new", true, m::reloadContent);
            content.addView(g, Widgets.MW);
        }
    }

    // ------------------------------------------------------------------ playback (devices)
    static final class PlaybackPage extends Sub {
        PlaybackPage(MainActivity m) { super(m, R.string.set_playback); }

        @Override void build() {
            MainActivity m = main;
            Widgets.Group g = new Widgets.Group(m, m.getString(R.string.devices));
            content.addView(g, Widgets.MW);
            View sp = Widgets.spinner(m, 80);
            content.addView(sp);
            if (!Spotify.loggedIn()) { content.removeView(sp); g.row(0, 0, m.getString(R.string.login_needed), null, false, null); }
            else Player.devices(new Spotify.Cb() {
                @Override public void ok(JSONObject o) {
                    content.removeView(sp);
                    JSONArray ds = o.optJSONArray("devices");
                    if (ds == null || ds.length() == 0) g.row(0, 0, m.getString(R.string.no_devices), null, false, null);
                    else for (int i = 0; i < ds.length(); i++) {
                        JSONObject d = ds.optJSONObject(i);
                        g.option(d.optString("name") + "  ·  " + d.optString("type"), d.optBoolean("is_active"), v -> { Player.transfer(d.optString("id")); m.pop(); });
                    }
                }
                @Override public void err(int c, String e) { content.removeView(sp); g.row(0, 0, m.getString(R.string.load_failed), null, false, null); }
            });
            Widgets.Group a = new Widgets.Group(m, null);
            a.row(Icons.LINK, Ui.ACCENT, m.getString(R.string.open_spotify_app), null, true, v -> Player.openInSpotify("spotify:"));
            content.addView(a, Widgets.MW);
            Widgets.Group fx = new Widgets.Group(m, m.getString(R.string.set_player_screen));
            toggleRow(fx, Icons.ALBUM, m.getString(R.string.shrink_paused), "shrink_paused", true, null);
            content.addView(fx, Widgets.MW);
            content.addView(Widgets.caption(m, m.getString(R.string.devices_note)));
            Widgets.Group bp = new Widgets.Group(m, m.getString(R.string.bp_group));
            View bpRow = toggleRow(bp, Icons.DEVICE, m.getString(R.string.bp_title), BitPerfect.PREF, false, () -> {
                if (!BitPerfect.enabled(m)) BitPerfect.clear(m);
                // the open stream keeps its format: reopen the current song where it is
                LocalMusic.reopen();
                // refresh the status line once the switch has finished its own animation
                Spotify.MAIN.postDelayed(() -> { if (isAttachedToWindow()) rebuild(); }, 450);
            });
            bp.row(0, 0, BitPerfect.status(m), null, false, null);
            content.addView(bp, Widgets.MW);
            content.addView(Widgets.caption(m, m.getString(R.string.bp_note)));
        }
    }

    // ------------------------------------------------------------------ lyrics
    static final class LyricsPage extends Sub {
        LyricsPage(MainActivity m) { super(m, R.string.set_lyrics); }

        @Override void build() {
            MainActivity m = main;
            Widgets.Group g = new Widgets.Group(m, null);
            toggleRow(g, Icons.LYRICS, m.getString(R.string.lyrics_online), "lyrics", true, null);
            content.addView(g, Widgets.MW);
            content.addView(Widgets.caption(m, m.getString(R.string.lyrics_note)));
        }
    }

    // ------------------------------------------------------------------ storage
    static final class StoragePage extends Sub {
        StoragePage(MainActivity m) { super(m, R.string.set_storage); }

        @Override void build() {
            MainActivity m = main;
            Widgets.Group g = new Widgets.Group(m, m.getString(R.string.image_cache));
            View r = g.row(Icons.STORAGE, Ui.ACCENT, m.getString(R.string.cache_size), size(Img.dir), false, null);
            g.row(Icons.CLOSE, Ui.ACCENT, m.getString(R.string.clear_cache), null, false, v -> {
                File[] fs = Img.dir.listFiles();
                if (fs != null) for (File f : fs) f.delete();
                Object tv = r.getTag();
                if (tv instanceof TextView) ((TextView) tv).setText(size(Img.dir));
                Toast.makeText(m, R.string.cleared, Toast.LENGTH_SHORT).show();
            });
            content.addView(g, Widgets.MW);
        }

        static String size(File dir) {
            long n = 0;
            File[] fs = dir == null ? null : dir.listFiles();
            if (fs != null) for (File f : fs) n += f.length();
            return n < 1024 * 1024 ? (n / 1024) + " KB" : String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0);
        }
    }

    // ------------------------------------------------------------------ about
    static final class AboutPage extends Sub {
        AboutPage(MainActivity m) { super(m, R.string.set_about); }

        @Override void build() {
            MainActivity m = main;
            Widgets.Group g = new Widgets.Group(m, null);
            g.row(Icons.INFO, Ui.ACCENT, m.getString(R.string.version), MainActivity.VERSION, false, null);
            g.row(Icons.NOTE, Ui.ACCENT, m.getString(R.string.music_service), "Spotify Web API", false, null);
            g.row(Icons.LYRICS, Ui.ACCENT, m.getString(R.string.lyrics_source), "LRCLIB", false, null);
            content.addView(g, Widgets.MW);
            TextView t = (TextView) Widgets.caption(m, m.getString(R.string.about_note));
            content.addView(t);
        }
    }
}
