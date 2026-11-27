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
            String ytmWho = YouTubeMusic.signedIn(m) ? YouTubeMusic.accountLabel(m) : m.getString(R.string.not_connected);
            acc.row(Icons.YTM, 0xFFFF2D55, m.getString(R.string.source_ytm), ytmWho, true, v -> m.push(new YouTubeMusicAccountPage(m)));
            content.addView(acc, Widgets.MW);

            Widgets.Group app = new Widgets.Group(m, m.getString(R.string.set_application));
            app.row(Icons.SETTINGS, Ui.ACCENT, m.getString(R.string.set_general), null, true, v -> m.push(new GeneralPage(m)));
            app.row(Icons.APPEARANCE, Ui.ACCENT, m.getString(R.string.set_appearance), null, true, v -> m.push(new AppearancePage(m)));
            app.row(Icons.CONTENT, Ui.ACCENT, m.getString(R.string.set_content), null, true, v -> m.push(new ContentPage(m)));
            app.row(Icons.WAVE, Ui.ACCENT, m.getString(R.string.set_playback), null, true, v -> m.push(new PlaybackPage(m)));
            app.row(Icons.HEADPHONES, Ui.ACCENT, m.getString(R.string.bpa_title), NpuAudio.summary(m), true, v -> m.push(new BitPerfectAssistPage(m)));
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

    // ------------------------------------------------------------------ YouTube Music Account
    static final class YouTubeMusicAccountPage extends Sub {
        YouTubeMusicAccountPage(MainActivity m) { super(m, R.string.ytm_account); }

        @Override void build() {
            MainActivity m = main;
            Widgets.Group st = new Widgets.Group(m, m.getString(R.string.set_status));
            if (YouTubeMusic.signedIn(m)) {
                st.row(Icons.YTM, 0xFFFF2D55, YouTubeMusic.accountLabel(m), m.getString(R.string.connected), false, null);
                st.row(Icons.LOGOUT, Ui.ACCENT, m.getString(R.string.logout), null, false, v -> {
                    YouTubeMusic.signOut(m);
                    rebuild();
                });
            } else {
                st.row(Icons.YTM, 0xFFFF2D55, m.getString(R.string.not_connected), null, false, null);
                st.row(Icons.LINK, Ui.ACCENT, m.getString(R.string.ytm_sign_in), null, true, v ->
                        YouTubeMusic.showSignInDialog(m, () -> { m.reloadAll(); rebuild(); }));
            }
            content.addView(st, Widgets.MW);
            Widgets.Group pb = new Widgets.Group(m, m.getString(R.string.set_playback));
            toggleRow(pb, Icons.YTM, m.getString(R.string.ytm_adblock), YouTubeMusic.PREF_ADBLOCK, true, YouTubeMusic::syncAdBlock);
            content.addView(pb, Widgets.MW);
            content.addView(Widgets.caption(m, m.getString(R.string.ytm_adblock_note)));
            content.addView(Widgets.caption(m, m.getString(R.string.ytm_guest_note)));
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
            // app style: A (glass) or G (One UI 9.5 frosted translucent pills); motion is the same in both
            Widgets.Group sg = new Widgets.Group(m, m.getString(R.string.player_style));
            String curSt = MainActivity.prefs(m).getString("player_style", "prism");
            String[][] sts = {{"prism", m.getString(R.string.player_style_prism)}, {"oneui", m.getString(R.string.player_style_oneui)}};
            for (String[] o : sts) sg.option(o[1], o[0].equals(curSt), v -> {
                if (o[0].equals(MainActivity.prefs(m).getString("player_style", "prism"))) return;
                MainActivity.prefs(m).edit().putString("player_style", o[0]).apply();
                Ui.gStyle = "oneui".equals(o[0]);
                m.refreshAppearance();
            });
            content.addView(sg, Widgets.MW);
            content.addView(Widgets.caption(m, m.getString(R.string.style_note)));
            // accent color palette (8 presets including system dynamic color)
            Widgets.Group cg = new Widgets.Group(m, m.getString(R.string.set_accent));
            cg.card.addView(buildAccentPicker(m), Widgets.MW);
            content.addView(cg, Widgets.MW);
            content.addView(Widgets.caption(m, m.getString(R.string.accent_note)));
            // app launcher icon switcher (new Rainbow Prism default vs Classic Prism)
            Widgets.Group ig = new Widgets.Group(m, m.getString(R.string.set_app_icon));
            ig.card.addView(buildIconPicker(m), Widgets.MW);
            content.addView(ig, Widgets.MW);
            content.addView(Widgets.caption(m, m.getString(R.string.app_icon_note)));
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

        private View buildIconPicker(MainActivity m) {
            String cur = MainActivity.prefs(m).getString("app_icon", "classic");
            if (!"classic".equals(cur) && !"neon".equals(cur) && !"bloom".equals(cur)) cur = "classic";
            LinearLayout row = new LinearLayout(m);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(Ui.dp(12), Ui.dp(14), Ui.dp(12), Ui.dp(14));
            String[][] icons = {
                    {"classic", m.getString(R.string.app_icon_classic)},
                    {"neon", m.getString(R.string.app_icon_neon)},
                    {"bloom", m.getString(R.string.app_icon_bloom)}
            };
            int[] resIds = {R.mipmap.ic_launcher_classic, R.mipmap.ic_launcher_neon, R.mipmap.ic_launcher_bloom};
            for (int i = 0; i < icons.length; i++) {
                final String key = icons[i][0];
                final boolean sel = key.equals(cur);
                LinearLayout card = new LinearLayout(m);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                card.setPadding(Ui.dp(8), Ui.dp(13), Ui.dp(8), Ui.dp(11));
                android.graphics.drawable.GradientDrawable bg = Ui.round(sel ? Ui.alpha(Ui.ACCENT, 0.11f) : Ui.chip(), 18);
                bg.setStroke(Ui.dp(sel ? 2f : 0.8f), sel ? Ui.ACCENT : Ui.separator());
                card.setBackground(bg);

                android.widget.ImageView iv = new android.widget.ImageView(m);
                iv.setImageResource(resIds[i]);
                iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override public void getOutline(View v, android.graphics.Outline o) {
                        o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dpf(16));
                    }
                });
                iv.setClipToOutline(true);
                iv.setElevation(Ui.dpf(4));
                card.addView(iv, new LinearLayout.LayoutParams(Ui.dp(56), Ui.dp(56)));

                TextView lbl = Ui.text(m, icons[i][1], 12.5f, sel ? Ui.semibold : Ui.medium, sel ? Ui.ACCENT : Ui.text());
                lbl.setGravity(android.view.Gravity.CENTER);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                llp.topMargin = Ui.dp(8);
                card.addView(lbl, llp);

                card.setOnClickListener(v -> {
                    String saved = MainActivity.prefs(m).getString("app_icon", "classic");
                    if (key.equals(saved)) return;
                    applyAppIcon(m, key);
                    rebuild();
                });
                Ui.press(card, 0.96f);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (i < icons.length - 1) clp.rightMargin = Ui.dp(8);
                row.addView(card, clp);
            }
            return row;
        }

        static void applyAppIcon(Context c, String key) {
            if (!"neon".equals(key) && !"bloom".equals(key)) key = "classic";
            MainActivity.prefs(c).edit().putString("app_icon", key).apply();
            try {
                android.content.pm.PackageManager pm = c.getPackageManager();
                String pkg = c.getPackageName();
                String[][] map = {
                        {"classic", pkg + ".LauncherClassic"},
                        {"neon", pkg + ".LauncherNeon"},
                        {"bloom", pkg + ".LauncherBloom"}
                };
                // Enable target alias FIRST before disabling the others so Android never sees 0 launcher activities
                for (String[] m : map) {
                    if (m[0].equals(key)) {
                        pm.setComponentEnabledSetting(new android.content.ComponentName(pkg, m[1]),
                                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                                android.content.pm.PackageManager.DONT_KILL_APP);
                    }
                }
                for (String[] m : map) {
                    if (!m[0].equals(key)) {
                        pm.setComponentEnabledSetting(new android.content.ComponentName(pkg, m[1]),
                                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                android.content.pm.PackageManager.DONT_KILL_APP);
                    }
                }
                Toast.makeText(c, R.string.app_icon_changed, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        }

        private View buildAccentPicker(MainActivity m) {
            String curKey = Ui.accentKey(MainActivity.prefs(m));
            LinearLayout grid = new LinearLayout(m);
            grid.setOrientation(LinearLayout.VERTICAL);
            grid.setPadding(Ui.dp(10), Ui.dp(14), Ui.dp(10), Ui.dp(10));
            int cols = 4;
            LinearLayout row = null;
            for (int i = 0; i < Ui.ACCENT_KEYS.length; i++) {
                if (i % cols == 0) {
                    row = new LinearLayout(m);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    if (i > 0) rlp.topMargin = Ui.dp(10);
                    grid.addView(row, rlp);
                }
                final String key = Ui.ACCENT_KEYS[i];
                final int color = Ui.resolveAccent(m, key);
                final boolean sel = key.equals(curKey);
                LinearLayout cell = new LinearLayout(m);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                cell.setPadding(Ui.dp(2), Ui.dp(4), Ui.dp(2), Ui.dp(4));
                cell.setTag("accent:" + key);

                FrameLayout ring = new FrameLayout(m);
                if (sel) {
                    android.graphics.drawable.GradientDrawable rg = Ui.round(0x00000000, 100);
                    rg.setStroke(Ui.dp(2.2f), color);
                    ring.setBackground(rg);
                }
                FrameLayout dot = new FrameLayout(m);
                dot.setBackground(Ui.round(color, 100));
                if (sel) {
                    Icons.IconView ck = new Icons.IconView(m, Icons.CHECK, 18, 0xFFFFFFFF);
                    dot.addView(ck, new FrameLayout.LayoutParams(Ui.dp(20), Ui.dp(20), android.view.Gravity.CENTER));
                }
                ring.addView(dot, new FrameLayout.LayoutParams(Ui.dp(36), Ui.dp(36), android.view.Gravity.CENTER));
                cell.addView(ring, new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));

                TextView lbl = Ui.text(m, m.getString(Ui.ACCENT_LABELS[i]), 11.5f, sel ? Ui.semibold : Ui.regular, sel ? Ui.text() : Ui.text2());
                lbl.setGravity(android.view.Gravity.CENTER);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                llp.topMargin = Ui.dp(4);
                cell.addView(lbl, llp);

                cell.setOnClickListener(v -> {
                    if (key.equals(Ui.accentKey(MainActivity.prefs(m)))) return;
                    MainActivity.prefs(m).edit().putString("accent_color", key).apply();
                    Ui.ACCENT = Ui.resolveAccent(m, key);
                    m.refreshAppearance();
                });
                Ui.press(cell, 0.92f);
                row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            return grid;
        }
    }

    // ------------------------------------------------------------------ content
    static final class ContentPage extends Sub {
        ContentPage(MainActivity m) { super(m, R.string.set_content); }

        @Override void build() {
            MainActivity m = main;
            Widgets.Group g = new Widgets.Group(m, m.getString(R.string.set_home_sections));
            toggleRow(g, Icons.RECAP, m.getString(R.string.home_recap), "home_recap", true, m::reloadContent);
            toggleRow(g, Icons.ARTIST, m.getString(R.string.home_artists), "home_artists", true, m::reloadContent);
            content.addView(g, Widgets.MW);

            // device music: albums tagged "Name Disc 1", "Name Disc 2"… shown as one album
            Widgets.Group dg = new Widgets.Group(m, m.getString(R.string.source_local));
            toggleRow(dg, Icons.ALBUM, m.getString(R.string.merge_discs), "merge_discs", true, () -> LocalMusic.reload(m, m::reloadContent));
            content.addView(dg, Widgets.MW);
            content.addView(Widgets.caption(m, m.getString(R.string.merge_discs_note)));

            // home order: each section with up / down buttons
            Widgets.Group og = new Widgets.Group(m, m.getString(R.string.home_order));
            String[] order = Pages.homeOrder(m);
            for (int i = 0; i < order.length; i++) {
                final int idx = i;
                View r = og.row(0, 0, m.getString(Pages.homeTitle(order[i])), null, false, null);
                LinearLayout row = (LinearLayout) r;
                row.addView(moveButton(m, true, idx > 0, () -> moveHome(order, idx, -1)));
                row.addView(moveButton(m, false, idx < order.length - 1, () -> moveHome(order, idx, 1)));
            }
            content.addView(og, Widgets.MW);
            Widgets.Group rg = new Widgets.Group(m, null);
            rg.row(Icons.REPEAT, Ui.ACCENT, m.getString(R.string.home_order_reset), null, false, v -> {
                MainActivity.prefs(m).edit().remove("home_order").apply();
                m.reloadContent();
                rebuild();
            });
            content.addView(rg, Widgets.MW);
        }

        private void moveHome(String[] order, int i, int d) {
            int j = i + d;
            if (j < 0 || j >= order.length) return;
            String t = order[i]; order[i] = order[j]; order[j] = t;
            MainActivity.prefs(main).edit().putString("home_order", String.join(",", order)).apply();
            main.reloadContent();
            rebuild();
        }

        private static View moveButton(MainActivity m, boolean up, boolean enabled, Runnable r) {
            Icons.IconView b = new Icons.IconView(m, Icons.CHEVRON_UP, 18, enabled ? Ui.ACCENT : Ui.text3());
            if (!up) b.setRotation(180);
            b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(40)));
            if (enabled) {
                b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ui.pressed()), null, Ui.round(0xFFFFFFFF, 100)));
                b.setOnClickListener(v -> r.run());
            }
            return b;
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
            toggleRow(fx, Icons.YTM, m.getString(R.string.ytm_adblock), YouTubeMusic.PREF_ADBLOCK, true, YouTubeMusic::syncAdBlock);
            toggleRow(fx, Icons.ALBUM, m.getString(R.string.shrink_paused), "shrink_paused", true, null);
            toggleRow(fx, Icons.CLOCK, m.getString(R.string.show_total), "show_total", false, Player::notifyAll0);
            fx.row(Icons.WAVE, Ui.ACCENT, m.getString(R.string.eq_title), m.getString(Eq.on(m) ? R.string.eq_state_on : R.string.eq_state_off), true, v -> m.push(new EqPage(m)));
            content.addView(fx, Widgets.MW);
            Widgets.Group aodG = new Widgets.Group(m, m.getString(R.string.aod_title));
            aodG.row(Icons.AOD, Ui.ACCENT, m.getString(R.string.aod_open), null, true, v -> m.openAod());
            toggleRow(aodG, Icons.APPEARANCE, m.getString(R.string.aod_auto_bright), "aod_auto_bright", false, null);
            toggleRow(aodG, Icons.APPEARANCE, m.getString(R.string.aod_dim), "aod_dim", true, null);
            content.addView(aodG, Widgets.MW);
            content.addView(Widgets.caption(m, m.getString(R.string.aod_note)));
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
            Widgets.Group npu = new Widgets.Group(m, m.getString(R.string.npu_group));
            npu.row(Icons.HEADPHONES, Ui.ACCENT, m.getString(R.string.bpa_title), NpuAudio.summary(m), true, v -> m.push(new BitPerfectAssistPage(m)));
            content.addView(npu, Widgets.MW);
        }
    }

    // ------------------------------------------------------------------ Bit-Perfect Assist (Overhauled Audiophile + NPU Hub)
    static final class BitPerfectAssistPage extends Sub {
        BitPerfectAssistPage(MainActivity m) { super(m, R.string.bpa_title); }

        @Override void build() {
            MainActivity m = main;
            boolean supported = NpuAudio.isSupported();
            boolean active = NpuAudio.enabled(m);
            AudioInfo.OutputSpec spec = AudioInfo.current(m);
            int tier = AudioInfo.hiResTier(m, spec);

            // 1) Crisp Signal-Path Telemetry Card (Source -> Processing -> Output)
            Widgets.Group spg = new Widgets.Group(m, m.getString(R.string.bpa_signal_path));
            spg.card.addView(buildSignalPathCard(m, spec, tier, active), Widgets.MW);
            content.addView(spg, Widgets.MW);

            // 2) Flagship SoC NPU Enhancement Hero + Master LiquidToggle
            Widgets.Group mg = new Widgets.Group(m, m.getString(R.string.npu_group));
            LinearLayout hero = new LinearLayout(m);
            hero.setOrientation(LinearLayout.HORIZONTAL);
            hero.setGravity(android.view.Gravity.CENTER_VERTICAL);
            hero.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
            NpuAudio.ChipSpec curChip = NpuAudio.currentChipSpec();
            hero.addView(new NpuAudio.ChipArtView(m, curChip, NpuAudio.displaySoc(m)), new LinearLayout.LayoutParams(Ui.dp(56), Ui.dp(56)));
            LinearLayout hcol = new LinearLayout(m);
            hcol.setOrientation(LinearLayout.VERTICAL);
            hcol.setPadding(Ui.dp(14), 0, 0, 0);
            hcol.addView(Ui.text(m, NpuAudio.displaySoc(m), 16f, Ui.bold, Ui.text()), Widgets.MW);
            TextView stTv = Ui.text(m, NpuAudio.status(m), 12.5f, Ui.regular, supported ? Ui.ACCENT : Ui.text2());
            stTv.setSingleLine(false);
            LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stLp.topMargin = Ui.dp(3);
            hcol.addView(stTv, stLp);
            hero.addView(hcol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            mg.card.addView(hero, Widgets.MW);

            if (supported) {
                toggleRow(mg, Icons.HEADPHONES, m.getString(R.string.npu_title), NpuAudio.PREF_ON, false, () -> {
                    NpuAudio.apply(m);
                    Player.notifyAll0();
                    Spotify.MAIN.postDelayed(() -> { if (isAttachedToWindow()) rebuild(); }, 350);
                });
            } else {
                View r = mg.row(Icons.HEADPHONES, Ui.text3(), m.getString(R.string.npu_title), m.getString(R.string.eq_state_off), false, null);
                r.setAlpha(0.55f);
            }
            toggleRow(mg, Icons.APPEARANCE, m.getString(R.string.bpa_hires_glow), NpuAudio.PREF_HIRES_GLOW, true, Player::notifyAll0);
            mg.row(Icons.INFO, Ui.ACCENT, m.getString(R.string.bpa_supported_socs), m.getString(R.string.bpa_supported_socs_sub), true, v -> m.push(new SupportedChipsetsPage(m)));
            content.addView(mg, Widgets.MW);

            // 3) USB DAC & Hardware Output Suite (New audiophile controls!)
            Widgets.Group dacG = new Widgets.Group(m, m.getString(R.string.bpa_dac_section));
            dacG.row(Icons.DAC, Ui.ACCENT, m.getString(R.string.bpa_dac_specs), BitPerfect.dacProfileSummary(m), false, null);
            toggleRow(dacG, Icons.DEVICE, m.getString(R.string.bp_title), BitPerfect.PREF, false, () -> {
                if (!BitPerfect.enabled(m)) BitPerfect.clear(m);
                LocalMusic.reopen();
                Spotify.MAIN.postDelayed(() -> { if (isAttachedToWindow()) rebuild(); }, 400);
            });
            // Output Mode pills (Auto BP / USB Direct / Fixed Volume / 32-bit Float)
            dacG.card.addView(pillSectionLabel(m, m.getString(R.string.bpa_output_mode)), Widgets.MW);
            dacG.card.addView(pillSelectorRow(m, BitPerfect.OUTPUT_MODES, BitPerfect.OUTPUT_MODE_LABELS, BitPerfect.outputMode(m), true, val -> {
                MainActivity.prefs(m).edit().putString(BitPerfect.PREF_OUTPUT_MODE, val).apply();
                LocalMusic.reopen();
                rebuild();
            }), Widgets.MW);
            // Target Sample Rate horizontal scroll chips
            dacG.card.addView(pillSectionLabel(m, m.getString(R.string.bpa_sample_rate)), Widgets.MW);
            dacG.card.addView(sampleRateRow(m), Widgets.MW);
            // USB Buffer Latency selector
            dacG.card.addView(pillSectionLabel(m, m.getString(R.string.bpa_buffer)), Widgets.MW);
            String[] bufKeys = {"20", "45", "90"};
            dacG.card.addView(pillSelectorRow(m, bufKeys, BitPerfect.BUFFER_LABELS, String.valueOf(BitPerfect.bufferMs(m)), true, val -> {
                MainActivity.prefs(m).edit().putInt(BitPerfect.PREF_BUFFER_MS, Integer.parseInt(val)).apply();
                rebuild();
            }), Widgets.MW);
            content.addView(dacG, Widgets.MW);

            // 4) Audiophile DSP & Filter Suite (Dithering, Oversampling Filter, ReplayGain, Crossfeed, Mono, L/R Balance)
            Widgets.Group dspG = new Widgets.Group(m, m.getString(R.string.bpa_dsp_section));
            dspG.card.addView(pillSectionLabel(m, m.getString(R.string.bpa_dither)), Widgets.MW);
            dspG.card.addView(pillSelectorRow(m, BitPerfect.DITHERS, BitPerfect.DITHER_LABELS, BitPerfect.dither(m), true, val -> {
                MainActivity.prefs(m).edit().putString(BitPerfect.PREF_DITHER, val).apply();
                NpuAudio.apply(m, true);
                rebuild();
            }), Widgets.MW);
            dspG.card.addView(pillSectionLabel(m, m.getString(R.string.bpa_filter)), Widgets.MW);
            dspG.card.addView(pillSelectorRow(m, BitPerfect.FILTERS, BitPerfect.FILTER_LABELS, BitPerfect.filter(m), true, val -> {
                MainActivity.prefs(m).edit().putString(BitPerfect.PREF_FILTER, val).apply();
                NpuAudio.apply(m, true);
                rebuild();
            }), Widgets.MW);
            toggleRow(dspG, Icons.WAVE, m.getString(R.string.bpa_replaygain), BitPerfect.PREF_REPLAYGAIN, false, () -> {
                LocalMusic.applyChannelBalance();
                YouTubeMusic.applyChannelBalance();
            });
            toggleRow(dspG, Icons.HEADPHONES, m.getString(R.string.bpa_crossfeed), BitPerfect.PREF_CROSSFEED, false, () -> NpuAudio.apply(m, true));
            toggleRow(dspG, Icons.VOLUME, m.getString(R.string.bpa_mono), BitPerfect.PREF_MONO, false, () -> NpuAudio.apply(m, true));
            int curBal = BitPerfect.balance(m);
            View balRow = dspG.row(Icons.SLIDERS, Ui.ACCENT, m.getString(R.string.bpa_balance), formatBalance(m, curBal), false, null);
            Widgets.GlassSlider balSl = new Widgets.GlassSlider(m, (curBal + 100) / 200f, 20);
            balSl.change = v -> {
                int b = Math.round(v * 200f) - 100;
                if (Math.abs(b) < 6) b = 0;
                MainActivity.prefs(m).edit().putInt(BitPerfect.PREF_BALANCE, b).apply();
                Object tv = balRow.getTag();
                if (tv instanceof TextView) ((TextView) tv).setText(formatBalance(m, b));
                LocalMusic.applyChannelBalance();
                YouTubeMusic.applyChannelBalance();
            };
            dspG.card.addView(balSl, Widgets.MW);
            content.addView(dspG, Widgets.MW);

            // 5) Live Frequency Response Curve + Instant A/B Bypass Comparison
            NpuAudio.CurveView curveView = new NpuAudio.CurveView(m);
            Widgets.Group cg = new Widgets.Group(m, m.getString(R.string.bpa_curve));
            cg.card.addView(curveView, Widgets.MW);
            if (supported && active) {
                TextView abBtn = Ui.text(m, m.getString(R.string.bpa_ab_hold), 13.5f, Ui.semibold, Ui.ACCENT);
                abBtn.setClickable(true);
                abBtn.setGravity(android.view.Gravity.CENTER);
                abBtn.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
                abBtn.setBackground(Ui.round(Ui.alpha(Ui.ACCENT, 0.14f), 14));
                LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                abLp.setMargins(Ui.dp(14), Ui.dp(4), Ui.dp(14), Ui.dp(14));
                abBtn.setOnTouchListener((v, ev) -> {
                    int act = ev.getActionMasked();
                    if (act == android.view.MotionEvent.ACTION_DOWN) {
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                        NpuAudio.setBypassHold(m, true);
                        abBtn.setText(R.string.bpa_ab_bypassing);
                        abBtn.setBackground(Ui.round(Ui.alpha(0xFFFF9F0A, 0.28f), 14));
                        abBtn.setTextColor(0xFFFF9F0A);
                        stTv.setText(NpuAudio.status(m));
                        curveView.invalidate();
                        return true;
                    } else if (act == android.view.MotionEvent.ACTION_UP || act == android.view.MotionEvent.ACTION_CANCEL) {
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                        NpuAudio.setBypassHold(m, false);
                        abBtn.setText(R.string.bpa_ab_hold);
                        abBtn.setBackground(Ui.round(Ui.alpha(Ui.ACCENT, 0.14f), 14));
                        abBtn.setTextColor(Ui.ACCENT);
                        stTv.setText(NpuAudio.status(m));
                        curveView.invalidate();
                        return true;
                    }
                    return true;
                });
                cg.card.addView(abBtn, abLp);
            }
            content.addView(cg, Widgets.MW);

            // 6) Compact NPU Profile & Harmonic Exciter Selectors
            Widgets.Group pg = new Widgets.Group(m, m.getString(R.string.bpa_profile));
            pg.card.addView(pillSelectorRow(m, NpuAudio.PROFILES, NpuAudio.PROFILE_NAMES, NpuAudio.profile(m), supported, val -> {
                for (int i = 0; i < NpuAudio.PROFILES.length; i++) {
                    if (NpuAudio.PROFILES[i].equals(val)) {
                        NpuAudio.setProfile(m, i);
                        if (!NpuAudio.enabled(m)) NpuAudio.setEnabled(m, true);
                        rebuild();
                        return;
                    }
                }
            }), Widgets.MW);
            pg.card.addView(pillSectionLabel(m, m.getString(R.string.bpa_harmonics)), Widgets.MW);
            pg.card.addView(pillSelectorRow(m, NpuAudio.HARMONICS, NpuAudio.HARMONIC_NAMES, NpuAudio.harmonics(m), supported && active, val -> {
                NpuAudio.setHarmonics(m, val);
                rebuild();
            }), Widgets.MW);
            content.addView(pg, Widgets.MW);

            // 7) 6-Band Detailed NPU Tuning Sliders + Limiter + Per-Device Memory
            Widgets.Group tg = new Widgets.Group(m, m.getString(R.string.bpa_tuning));
            addTuningSlider(tg, m, R.string.bpa_strength, NpuAudio.PREF_STRENGTH, NpuAudio.strength(m), supported && active, curveView);
            addTuningSlider(tg, m, R.string.bpa_air, NpuAudio.PREF_AIR, NpuAudio.air(m), supported && active, curveView);
            addTuningSlider(tg, m, R.string.bpa_dyn, NpuAudio.PREF_DYN, NpuAudio.dyn(m), supported && active, curveView);
            addTuningSlider(tg, m, R.string.bpa_vocal, NpuAudio.PREF_VOCAL, NpuAudio.vocal(m), supported && active, curveView);
            addTuningSlider(tg, m, R.string.bpa_bass, NpuAudio.PREF_BASS, NpuAudio.bass(m), supported && active, curveView);
            addTuningSlider(tg, m, R.string.bpa_space, NpuAudio.PREF_SPACE, NpuAudio.space(m), supported && active, curveView);
            if (supported) {
                toggleRow(tg, Icons.WAVE, m.getString(R.string.bpa_limiter), NpuAudio.PREF_LIMITER, true, () -> NpuAudio.apply(m, true));
                toggleRow(tg, Icons.DEVICE, m.getString(R.string.bpa_per_device), NpuAudio.PREF_PER_DEVICE, true, null);
            }
            content.addView(tg, Widgets.MW);
            content.addView(Widgets.caption(m, m.getString(R.string.npu_note)));
        }

        private static String formatBalance(Context c, int b) {
            if (b == 0) return c.getString(R.string.bpa_balance_center);
            return b < 0 ? ("L " + (-b) + "%") : ("R " + b + "%");
        }

        private static View buildSignalPathCard(MainActivity m, AudioInfo.OutputSpec spec, int tier, boolean npuOn) {
            LinearLayout col = new LinearLayout(m);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));

            String srcFmt = BitPerfect.sourceRate > 0
                    ? (BitPerfect.sourceBits + "-bit / " + String.format(java.util.Locale.US, "%.1f kHz", BitPerfect.sourceRate / 1000f))
                    : (Player.local() ? m.getString(R.string.source_local) : (Player.ytm() ? "YouTube Music" : "Spotify"));
            String procLabel = BitPerfect.isActive()
                    ? (BitPerfect.activeBitPerfect ? m.getString(R.string.bpa_proc_bp) : m.getString(R.string.bpa_proc_direct))
                    : (npuOn ? ("NPU DSP · " + BitPerfect.filter(m).toUpperCase(java.util.Locale.US)) : m.getString(R.string.bpa_proc_default));
            String outName = spec.deviceName.isEmpty() ? m.getString(R.string.this_device) : spec.deviceName;
            String outFmt = spec.formatLine().isEmpty() ? (spec.bits + "bit / " + spec.khzText()) : spec.formatLine();

            int tierColor = tier == AudioInfo.TIER_MASTER ? 0xFFA855F7
                    : (tier == AudioInfo.TIER_BLUE ? 0xFF38BDF8
                    : (tier == AudioInfo.TIER_GOLD ? 0xFFD4A017 : Ui.ACCENT));

            col.addView(pathStep(m, Icons.NOTE, Ui.text2(), m.getString(R.string.bpa_step_source), srcFmt), Widgets.MW);
            col.addView(pathConnector(m), Widgets.MW);
            col.addView(pathStep(m, Icons.WAVE, tierColor, m.getString(R.string.bpa_step_engine), procLabel), Widgets.MW);
            col.addView(pathConnector(m), Widgets.MW);
            col.addView(pathStep(m, AudioInfo.deviceIcon(spec), Ui.ACCENT, m.getString(R.string.bpa_step_output), outName + " · " + outFmt), Widgets.MW);
            return col;
        }

        private static View pathStep(Context c, int icon, int color, String tag, String value) {
            LinearLayout r = new LinearLayout(c);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(android.view.Gravity.CENTER_VERTICAL);
            FrameLayout dot = new FrameLayout(c);
            dot.setBackground(Ui.round(Ui.alpha(color, 0.16f), 100));
            dot.addView(new Icons.IconView(c, icon, 15, color), new FrameLayout.LayoutParams(Ui.dp(17), Ui.dp(17), android.view.Gravity.CENTER));
            r.addView(dot, new LinearLayout.LayoutParams(Ui.dp(30), Ui.dp(30)));
            LinearLayout txt = new LinearLayout(c);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.setPadding(Ui.dp(11), 0, 0, 0);
            txt.addView(Ui.text(c, tag, 11f, Ui.semibold, Ui.text2()), Widgets.MW);
            txt.addView(Ui.text(c, value, 14f, Ui.semibold, Ui.text()), Widgets.MW);
            r.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return r;
        }

        private static View pathConnector(Context c) {
            View v = new View(c);
            v.setBackgroundColor(Ui.separator());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(2), Ui.dp(10));
            lp.leftMargin = Ui.dp(14);
            lp.topMargin = Ui.dp(2);
            lp.bottomMargin = Ui.dp(2);
            v.setLayoutParams(lp);
            return v;
        }

        private static View pillSectionLabel(Context c, String label) {
            TextView tv = Ui.text(c, label, 12.5f, Ui.semibold, Ui.text2());
            tv.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(4));
            return tv;
        }

        private static View pillSelectorRow(Context c, String[] values, int[] labelRes, String current, boolean enabled, java.util.function.Consumer<String> onPick) {
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(Ui.dp(14), Ui.dp(4), Ui.dp(14), Ui.dp(10));
            for (int i = 0; i < values.length; i++) {
                final String val = values[i];
                boolean sel = val.equals(current);
                TextView pill = Ui.text(c, c.getString(labelRes[i]), 13f, sel ? Ui.bold : Ui.medium, sel ? 0xFFFFFFFF : Ui.text());
                pill.setGravity(android.view.Gravity.CENTER);
                pill.setPadding(Ui.dp(13), Ui.dp(8), Ui.dp(13), Ui.dp(8));
                pill.setBackground(Widgets.chipBg(sel ? Ui.ACCENT : Ui.chip()));
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) plp.leftMargin = Ui.dp(8);
                if (enabled) {
                    pill.setOnClickListener(v -> onPick.accept(val));
                    Ui.press(pill, 0.94f);
                } else {
                    pill.setAlpha(0.5f);
                }
                row.addView(pill, plp);
            }
            hs.addView(row);
            return hs;
        }

        private View sampleRateRow(MainActivity m) {
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(m);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(m);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(Ui.dp(14), Ui.dp(4), Ui.dp(14), Ui.dp(10));
            int cur = BitPerfect.targetSampleRate(m);
            for (int i = 0; i < BitPerfect.SAMPLE_RATES.length; i++) {
                final int rate = BitPerfect.SAMPLE_RATES[i];
                boolean sel = rate == cur;
                TextView pill = Ui.text(m, BitPerfect.formatRateLabel(m, rate), 13f, sel ? Ui.bold : Ui.medium, sel ? 0xFFFFFFFF : Ui.text());
                pill.setGravity(android.view.Gravity.CENTER);
                pill.setPadding(Ui.dp(13), Ui.dp(8), Ui.dp(13), Ui.dp(8));
                pill.setBackground(Widgets.chipBg(sel ? Ui.ACCENT : Ui.chip()));
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) plp.leftMargin = Ui.dp(8);
                pill.setOnClickListener(v -> {
                    MainActivity.prefs(m).edit().putInt(BitPerfect.PREF_SAMPLE_RATE, rate).apply();
                    LocalMusic.reopen();
                    rebuild();
                });
                Ui.press(pill, 0.94f);
                row.addView(pill, plp);
            }
            hs.addView(row);
            return hs;
        }

        private void addTuningSlider(Widgets.Group g, MainActivity m, int titleRes, String key, int curPct, boolean interactive, NpuAudio.CurveView curve) {
            View row = g.row(0, 0, m.getString(titleRes), curPct + "%", false, null);
            Widgets.GlassSlider sl = new Widgets.GlassSlider(m, curPct / 100f, 20);
            if (!interactive) {
                row.setAlpha(0.5f);
                sl.setAlpha(0.45f);
                sl.setEnabled(false);
            } else {
                sl.change = v -> {
                    int pct = Math.round(v * 100f);
                    NpuAudio.setParam(m, key, pct);
                    Object tv = row.getTag();
                    if (tv instanceof TextView) ((TextView) tv).setText(pct + "%");
                    if (curve != null) curve.invalidate();
                };
            }
            g.card.addView(sl, Widgets.MW);
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            NpuAudio.setBypassHold(main, false);
        }
    }

    // ------------------------------------------------------------------ Supported Chipsets (15 Flagship SoCs)
    static final class SupportedChipsetsPage extends Sub {
        SupportedChipsetsPage(MainActivity m) { super(m, R.string.bpa_supported_socs); }

        @Override void build() {
            MainActivity m = main;
            NpuAudio.ChipSpec current = NpuAudio.currentChipSpec();
            addVendorGroup(m, "Qualcomm Snapdragon", NpuAudio.VENDOR_QUALCOMM, current);
            addVendorGroup(m, "MediaTek Dimensity", NpuAudio.VENDOR_MEDIATEK, current);
            addVendorGroup(m, "Samsung Exynos", NpuAudio.VENDOR_SAMSUNG, current);
            addVendorGroup(m, "Google Tensor", NpuAudio.VENDOR_GOOGLE, current);
        }

        private void addVendorGroup(MainActivity m, String title, int vendor, NpuAudio.ChipSpec current) {
            Widgets.Group g = new Widgets.Group(m, title);
            boolean first = true;
            for (NpuAudio.ChipSpec cs : NpuAudio.SUPPORTED_CHIPS) {
                if (cs.vendor != vendor) continue;
                if (!first) {
                    View div = new View(m);
                    div.setBackgroundColor(Ui.separator());
                    LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(0.6f)));
                    dlp.leftMargin = Ui.dp(76);
                    g.card.addView(div, dlp);
                }
                first = false;
                LinearLayout row = new LinearLayout(m);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(16), Ui.dp(10));
                boolean showArt = cs.drawableRes != 0 || !"S5E9975".equals(cs.id);
                if (showArt) {
                    row.addView(new NpuAudio.ChipArtView(m, cs, cs.id), new LinearLayout.LayoutParams(Ui.dp(50), Ui.dp(50)));
                }
                LinearLayout col = new LinearLayout(m);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(showArt ? Ui.dp(12) : Ui.dp(2), 0, Ui.dp(8), 0);
                col.addView(Ui.text(m, cs.displayName(m), 15f, Ui.semibold, Ui.text()), Widgets.MW);
                TextView sub = Ui.text(m, cs.id + " · " + cs.npu, 12f, Ui.regular, Ui.text2());
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                slp.topMargin = Ui.dp(2);
                col.addView(sub, slp);
                row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                if (current != null && current.id.equals(cs.id)) {
                    TextView tag = Ui.text(m, m.getString(R.string.bpa_current_device), 11.5f, Ui.semibold, Ui.ACCENT);
                    tag.setPadding(Ui.dp(9), Ui.dp(4), Ui.dp(9), Ui.dp(4));
                    tag.setBackground(Ui.round(Ui.alpha(Ui.ACCENT, 0.14f), 100));
                    row.addView(tag, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                g.card.addView(row, Widgets.MW);
            }
            content.addView(g, Widgets.MW);
        }
    }

    // ------------------------------------------------------------------ equalizer
    static final class EqPage extends Sub {
        EqPage(MainActivity m) { super(m, R.string.eq_title); }

        @Override void build() {
            MainActivity m = main;
            Widgets.Group g = new Widgets.Group(m, null);
            toggleRow(g, Icons.WAVE, m.getString(R.string.eq_use), Eq.PREF_ON, false, () -> { Eq.apply(m); rebuildSoon(); });
            content.addView(g, Widgets.MW);
            // the ten bands in a card
            Widgets.Group bg = new Widgets.Group(m, m.getString(R.string.eq_bands));
            Eq.Bands bands = new Eq.Bands(m);
            bands.setPadding(0, Ui.dp(8), 0, Ui.dp(8));
            bands.changed = this::rebuildSoon;
            bg.card.addView(bands, Widgets.MW);
            content.addView(bg, Widgets.MW);
            Widgets.Group pg = new Widgets.Group(m, m.getString(R.string.eq_presets));
            int cur = MainActivity.prefs(m).getInt(Eq.PREF_PRESET, 0);
            for (int i = 0; i < Eq.PRESET_NAMES.length; i++) {
                final int idx = i;
                pg.option(m.getString(Eq.PRESET_NAMES[i]), i == cur, v -> {
                    Eq.setPreset(m, idx);
                    if (!Eq.on(m)) Eq.setOn(m, true);
                    rebuild();
                });
            }
            if (cur < 0) pg.option(m.getString(R.string.eq_custom), true, null);
            content.addView(pg, Widgets.MW);
            content.addView(Widgets.caption(m, m.getString(R.string.eq_note)));
        }

        /** Refresh the preset ticks once a slider or switch has settled (not mid-gesture). */
        private void rebuildSoon() { Spotify.MAIN.postDelayed(() -> { if (isAttachedToWindow()) rebuild(); }, 350); }
    }

    // ------------------------------------------------------------------ lyrics
    static final class LyricsPage extends Sub {
        LyricsPage(MainActivity m) { super(m, R.string.set_lyrics); }

        @Override void build() {
            MainActivity m = main;
            Widgets.Group g = new Widgets.Group(m, null);
            toggleRow(g, Icons.LYRICS, m.getString(R.string.lyrics_online), "lyrics", true, null);
            toggleRow(g, Icons.GLOBE, m.getString(R.string.lyrics_ko_trans), Lyrics.PREF_KO_TRANS, true, () -> Lyrics.clearCache(m));
            toggleRow(g, Icons.LINK, m.getString(R.string.lyrics_namuwiki), Lyrics.PREF_NAMUWIKI, false, () -> Lyrics.clearCache(m));
            content.addView(g, Widgets.MW);
            content.addView(Widgets.caption(m, m.getString(R.string.lyrics_ko_trans_note)));
            content.addView(Widgets.caption(m, m.getString(R.string.lyrics_namuwiki_note)));

            Widgets.Group pg = new Widgets.Group(m, m.getString(R.string.lyrics_providers));
            for (int i = 0; i < Lyrics.PROVIDER_IDS.length; i++) {
                String id = Lyrics.PROVIDER_IDS[i];
                String label = Lyrics.PROVIDER_NAMES[i];
                toggleRow(pg, Icons.NOTE, label, Lyrics.providerPrefKey(id), true, () -> Lyrics.clearCache(m));
            }
            content.addView(pg, Widgets.MW);

            Widgets.Group cg = new Widgets.Group(m, null);
            cg.row(Icons.CLOSE, Ui.ACCENT, m.getString(R.string.lyrics_clear_cache), null, false, v -> {
                Lyrics.clearCache(m);
                Toast.makeText(m, R.string.cleared, Toast.LENGTH_SHORT).show();
            });
            content.addView(cg, Widgets.MW);
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
            g.row(Icons.NOTE, Ui.ACCENT, m.getString(R.string.music_service), "Spotify · YouTube Music · " + m.getString(R.string.source_local), false, null);
            g.row(Icons.LYRICS, Ui.ACCENT, m.getString(R.string.lyrics_source), "LRCLIB · NetEase · QQ · Kugou · SimpMusic · lyrics.ovh", false, null);
            content.addView(g, Widgets.MW);
            // updates from the GitHub releases
            Widgets.Group u = new Widgets.Group(m, m.getString(R.string.upd_group));
            View[] holder = new View[1];
            View checkRow = u.row(Icons.DOWNLOAD, Ui.ACCENT, m.getString(R.string.upd_check), MainActivity.VERSION, true, v -> {
                Object tv = holder[0].getTag();
                if (tv instanceof TextView) ((TextView) tv).setText(R.string.loading);
                Updater.check((r, err) -> {
                    String s = err != null ? m.getString(R.string.upd_failed) : r == null ? m.getString(R.string.upd_latest) : m.getString(R.string.upd_available, r.version);
                    if (tv instanceof TextView) ((TextView) tv).setText(s);
                    else Toast.makeText(m, s, Toast.LENGTH_SHORT).show();
                    if (r != null) Updater.offer(m, r);
                });
            });
            holder[0] = checkRow;
            toggleRow(u, Icons.CLOCK, m.getString(R.string.upd_auto), Updater.PREF_AUTO, true, null);
            content.addView(u, Widgets.MW);
            TextView t = (TextView) Widgets.caption(m, m.getString(R.string.about_note));
            content.addView(t);
        }
    }
}
