package com.oneuibook.prism;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Content screens. */
final class Pages {
    private Pages() {}

    static List<Widgets.Item> items(JSONArray a) {
        List<Widgets.Item> l = new ArrayList<>();
        if (a == null) return l;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            JSONObject t;
            if (o.has("track") || o.has("item")) t = Spotify.itemTrack(o);
            else if (!o.has("type") && o.optJSONObject("album") != null) t = o.optJSONObject("album");
            else t = o;
            Widgets.Item it = Widgets.Item.of(t);
            if (it != null && it.id != null && it.id.length() > 0) l.add(it);
        }
        return l;
    }

    static List<String> uris(List<Widgets.Item> l) {
        List<String> u = new ArrayList<>();
        for (Widgets.Item i : l) u.add(i.uri);
        return u;
    }

    // ------------------------------------------------------------------ on-device music
    static List<Widgets.Item> localSongs() {
        List<Widgets.Item> l = new ArrayList<>();
        for (Player.Track t : LocalMusic.songs) l.add(LocalMusic.item(t));
        return l;
    }

    static List<Widgets.Item> localGroups(boolean album) {
        List<Widgets.Item> l = new ArrayList<>();
        for (Map.Entry<String, List<Player.Track>> e : LocalMusic.group(album).entrySet())
            l.add(LocalMusic.group(e.getKey(), e.getValue(), album));
        return l;
    }

    /** Returns true (and fills {@code into}) when local mode can't show music yet. */
    static boolean localBlocked(Page p, LinearLayout into) {
        MainActivity m = p.main;
        if (!LocalMusic.hasPermission(m)) {
            into.addView(Widgets.messageCard(m, Icons.NOTE, m.getString(R.string.local_permission), m.getString(R.string.allow),
                    v -> LocalMusic.requestPermission(m)));
            return true;
        }
        if (!LocalMusic.loaded) { into.addView(Widgets.spinner(m, 200)); return true; }
        if (LocalMusic.songs.isEmpty()) { into.addView(Widgets.messageCard(m, Icons.NOTE, m.getString(R.string.local_empty), null, null)); return true; }
        return false;
    }

    static void localHome(Page p) {
        MainActivity m = p.main;
        LinearLayout c = p.content;
        if (localBlocked(p, c)) return;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        c.addView(Widgets.section(m, m.getString(h >= 5 && h < 12 ? R.string.greet_morning : (h >= 12 && h < 18 ? R.string.greet_afternoon : R.string.greet_evening))));
        List<Widgets.Item> albums = localGroups(true);
        LinearLayout row = Widgets.rail(c, 8);
        for (int i = 0; i < Math.min(10, albums.size()); i++) {
            Widgets.Item x = albums.get(i);
            x.caption = m.getString(R.string.recently_added);
            x.icon = Icons.CLOCK;
            Widgets.addToRail(row, Widgets.posterCard(m, x, v -> m.openItem(x)), Widgets.posterW());
        }
        c.addView(Widgets.section(m, m.getString(R.string.lib_albums)));
        LinearLayout r2 = Widgets.rail(c, 8);
        // rails stay short (the full lists live in the library): thousands of cards made the home screen hang
        for (Widgets.Item x : albums.subList(0, Math.min(40, albums.size()))) Widgets.addToRail(r2, Widgets.squareCard(m, x, Widgets.squareDp(), v -> m.openItem(x)), Ui.dp(Widgets.squareDp()));
        List<Widgets.Item> songs = localSongs();
        c.addView(Widgets.section(m, m.getString(R.string.all_songs)));
        Widgets.trackColumns(c, songs.subList(0, Math.min(30, songs.size())), i -> m.playList(songs, i, null));
        c.addView(Widgets.section(m, m.getString(R.string.lib_artists)));
        LinearLayout r3 = Widgets.rail(c, 8);
        List<Widgets.Item> artists = localGroups(false);
        for (Widgets.Item x : artists.subList(0, Math.min(40, artists.size()))) {
            x.type = "lartist";
            Widgets.addToRail(r3, Widgets.squareCard(m, x, Widgets.squareDp(), v -> m.openItem(x)), Ui.dp(Widgets.squareDp()));
        }
    }

    /** Album / artist / all-songs list for on-device music. */
    static final class LocalListPage extends Page {
        final Widgets.Item group;

        LocalListPage(MainActivity m, Widgets.Item group) {
            super(m, group.title, true);
            this.group = group;
            back = true;
            headerIcons = new int[0];
        }

        private boolean isAlbum() { return group.uri != null && group.uri.startsWith("lalbum"); }

        /** Sort for this kind of list: albums default to track order, everything else to recently added. */
        private String sortKey() { return isAlbum() ? "lsort_album" : "lsort_list"; }
        private String sort() { return MainActivity.prefs(main).getString(sortKey(), isAlbum() ? "track" : "added"); }

        private List<Player.Track> sorted() {
            List<Player.Track> l = new ArrayList<>(group.uri == null ? LocalMusic.songs : LocalMusic.tracksFor(group.uri));
            final java.text.Collator col = java.text.Collator.getInstance();
            switch (sort()) {
                case "track":
                    java.util.Collections.sort(l, (a, b) -> {
                        if (a.disc != b.disc) return Integer.compare(a.disc, b.disc);
                        // songs without a number go after the numbered ones, by title
                        int ta = a.trackNo == 0 ? Integer.MAX_VALUE : a.trackNo, tb = b.trackNo == 0 ? Integer.MAX_VALUE : b.trackNo;
                        return ta != tb ? Integer.compare(ta, tb) : col.compare(a.name, b.name);
                    });
                    break;
                case "title": java.util.Collections.sort(l, (a, b) -> col.compare(a.name, b.name)); break;
                case "artist": java.util.Collections.sort(l, (a, b) -> col.compare(a.artist, b.artist)); break;
                case "duration": java.util.Collections.sort(l, (a, b) -> Long.compare(a.duration, b.duration)); break;
                default: java.util.Collections.sort(l, (a, b) -> Long.compare(b.added, a.added));
            }
            return l;
        }

        @Override void build() {
            List<Player.Track> tracks = sorted();
            List<Widgets.Item> l = new ArrayList<>();
            for (Player.Track t : tracks) l.add(LocalMusic.item(t));
            LinearLayout row = new LinearLayout(main);
            row.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(10));
            row.addView(DetailPage.pillButton(main, Icons.PLAY_SMALL, main.getString(R.string.play), true, v -> main.playList(l, 0, null)),
                    new LinearLayout.LayoutParams(0, Ui.dp(48), 1f));
            row.addView(new View(main), new LinearLayout.LayoutParams(Ui.dp(12), 1));
            row.addView(DetailPage.pillButton(main, Icons.SHUFFLE, main.getString(R.string.shuffle), false, v -> {
                Player.shuffle = true;
                main.playList(l, (int) (Math.random() * l.size()), null);
            }), new LinearLayout.LayoutParams(0, Ui.dp(48), 1f));
            content.addView(row, Widgets.MW);
            content.addView(sortBar(), Widgets.MW);
            boolean numbers = isAlbum() && "track".equals(sort());
            // several discs (merged "Disc 1 / Disc 2" albums, or disc tags): a heading before each disc
            java.util.Set<Integer> discs = new java.util.TreeSet<>();
            for (Player.Track t : tracks) discs.add(t.disc);
            boolean discHeads = numbers && discs.size() > 1;
            int lastDisc = Integer.MIN_VALUE;
            for (int i = 0; i < l.size(); i++) {
                final int idx = i;
                if (discHeads && tracks.get(i).disc != lastDisc) {
                    lastDisc = tracks.get(i).disc;
                    TextView dh = Ui.text(main, lastDisc > 0 ? main.getString(R.string.disc_n, lastDisc) : main.getString(R.string.disc_other), 15, Ui.semibold, Ui.text2());
                    dh.setPadding(Ui.dp(20), Ui.dp(i == 0 ? 6 : 18), Ui.dp(20), Ui.dp(6));
                    content.addView(dh, Widgets.MW);
                }
                LinearLayout r = (LinearLayout) Widgets.trackRow(main, l.get(i), 0, v -> main.playList(l, idx, null), null);
                // album in track order: the track number sits at the very left, before the cover
                Player.Track t = tracks.get(i);
                if (numbers) {
                    TextView n = Ui.text(main, t.trackNo > 0 ? String.valueOf(t.trackNo) : "–", 14, Ui.medium, Ui.text2());
                    n.setGravity(Gravity.CENTER);
                    r.addView(n, 0, new LinearLayout.LayoutParams(Ui.dp(26), Ui.dp(44)));
                    r.setPadding(Ui.dp(8), r.getPaddingTop(), r.getPaddingRight(), r.getPaddingBottom());
                }
                content.addView(r, Widgets.MW);
            }
        }

        /** Sort chips, plus "set cover" / "reset cover" for albums. */
        private View sortBar() {
            MainActivity m = main;
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(m);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout bar = new LinearLayout(m);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(Ui.dp(16), Ui.dp(2), Ui.dp(16), Ui.dp(8));
            hs.addView(bar);
            String[][] opts = isAlbum()
                    ? new String[][]{{"track", m.getString(R.string.sort_track)}, {"title", m.getString(R.string.sort_title)}, {"added", m.getString(R.string.sort_added)}, {"duration", m.getString(R.string.sort_duration)}}
                    : new String[][]{{"added", m.getString(R.string.sort_added)}, {"title", m.getString(R.string.sort_title)}, {"artist", m.getString(R.string.sort_artist)}, {"duration", m.getString(R.string.sort_duration)}};
            String cur = sort();
            for (String[] o : opts) bar.addView(chip(o[1], o[0].equals(cur), v -> {
                MainActivity.prefs(m).edit().putString(sortKey(), o[0]).apply();
                rebuild();
            }), chipLp());
            if (isAlbum()) {
                String albumId = group.uri;
                bar.addView(chip(m.getString(R.string.cover_set), false, v -> m.pickCover(albumId)), chipLp());
                if (LocalMusic.hasCover(albumId)) bar.addView(chip(m.getString(R.string.cover_reset), false, v -> {
                    LocalMusic.clearCover(albumId);
                }), chipLp());
            }
            return hs;
        }

        private static LinearLayout.LayoutParams chipLp() {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(34));
            lp.rightMargin = Ui.dp(8);
            return lp;
        }

        private TextView chip(String label, boolean on, View.OnClickListener click) {
            TextView t = Ui.text(main, label, 14, on ? Ui.semibold : Ui.medium, on ? 0xFFFFFFFF : Ui.text());
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
            t.setBackground(Ui.round(on ? Ui.ACCENT : Ui.chip(), 100));
            t.setOnClickListener(click);
            Ui.press(t, 0.95f);
            return t;
        }
    }

    static final class LocalGroupsPage extends Page {
        final boolean album;

        LocalGroupsPage(MainActivity m, boolean album) {
            super(m, m.getString(album ? R.string.lib_albums : R.string.lib_artists), true);
            this.album = album;
            back = true;
            headerIcons = new int[0];
        }

        @Override void build() {
            for (Widgets.Item x : localGroups(album)) {
                if (!album) x.type = "lartist";
                content.addView(entityRow(main, x), Widgets.MW);
            }
        }
    }

    static List<Widgets.Item> localSearch(String q) {
        String s = q.toLowerCase(Locale.ROOT);
        List<Widgets.Item> l = new ArrayList<>();
        for (Player.Track t : LocalMusic.songs)
            if (t.name.toLowerCase(Locale.ROOT).contains(s) || t.artist.toLowerCase(Locale.ROOT).contains(s) || t.album.toLowerCase(Locale.ROOT).contains(s))
                l.add(LocalMusic.item(t));
        return l;
    }

    /** Shown when Spotify is not connected yet. */
    static void connectPrompt(Page p, LinearLayout into) {
        MainActivity m = p.main;
        boolean conf = Spotify.configured();
        into.addView(Widgets.messageCard(m, Icons.PROFILE,
                m.getString(conf ? R.string.connect_message : R.string.setup_message),
                m.getString(conf ? R.string.connect_spotify : R.string.setup_spotify),
                v -> { if (Spotify.configured()) Spotify.login(m); else m.push(new Settings.AccountPage(m)); }));
    }

    // ------------------------------------------------------------------ Home
    /** Default home order: "My playlists" sits right above "My charts". */
    static final String[] HOME_DEFAULT = {"recent", "foryou", "artists", "playlists", "charts", "new"};
    static final int[] HOME_TITLES = {R.string.home_recent, R.string.home_for_you, R.string.home_artists, R.string.home_playlists, R.string.home_charts, R.string.home_new};

    /** The saved order, or the default if it is missing / from an older version (all six keys, each once). */
    static String[] homeOrder(Context c) {
        String s = MainActivity.prefs(c).getString("home_order", null);
        if (s != null) {
            String[] o = s.split(",");
            java.util.Set<String> seen = new java.util.HashSet<>(java.util.Arrays.asList(o));
            if (o.length == HOME_DEFAULT.length && seen.size() == o.length && seen.containsAll(java.util.Arrays.asList(HOME_DEFAULT))) return o;
        }
        return HOME_DEFAULT.clone();
    }

    static int homeTitle(String key) {
        for (int i = 0; i < HOME_DEFAULT.length; i++) if (HOME_DEFAULT[i].equals(key)) return HOME_TITLES[i];
        return R.string.tab_home;
    }

    static final class HomePage extends Page {
        HomePage(MainActivity m) { super(m, m.getString(R.string.tab_home), true); }

        @Override void build() {
            MainActivity m = main;
            if (m.isLocalSource()) { localHome(this); return; }
            if (!Spotify.loggedIn()) { connectPrompt(this, content); return; }
            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            int greet = h >= 5 && h < 12 ? R.string.greet_morning : (h >= 12 && h < 18 ? R.string.greet_afternoon : R.string.greet_evening);
            // sections in the user's order (Settings → Content → Home order)
            Map<String, LinearLayout> slots = new java.util.HashMap<>();
            for (String k : homeOrder(m)) slots.put(k, slot());
            LinearLayout s1 = slots.get("recent"), s2 = slots.get("playlists"), s3 = slots.get("foryou"),
                    s4 = slots.get("artists"), s5 = slots.get("charts"), s6 = slots.get("new");
            s1.addView(Widgets.section(m, m.getString(greet)));
            View sp = Widgets.spinner(m, 192);
            s1.addView(sp);
            Spotify.get("/me/player/recently-played?limit=50", new Spotify.Cb() {
                @Override public void ok(JSONObject o) {
                    s1.removeView(sp);
                    JSONArray a = o.optJSONArray("items");
                    Map<String, Widgets.Item> albums = new LinkedHashMap<>();
                    SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                    in.setTimeZone(TimeZone.getTimeZone("UTC"));
                    SimpleDateFormat out = new SimpleDateFormat("MM-dd", Locale.getDefault());
                    if (a != null) for (int i = 0; i < a.length() && albums.size() < 12; i++) {
                        JSONObject it = a.optJSONObject(i);
                        JSONObject tr = it == null ? null : it.optJSONObject("track");
                        JSONObject al = tr == null ? null : tr.optJSONObject("album");
                        if (al == null || albums.containsKey(al.optString("id"))) continue;
                        Widgets.Item x = Widgets.Item.of(al);
                        try {
                            String pa = it.optString("played_at");
                            Date d = in.parse(pa.length() > 19 ? pa.substring(0, 19) : pa);
                            x.caption = out.format(d);
                        } catch (Exception e) { x.caption = ""; }
                        x.icon = Icons.CLOCK;
                        albums.put(al.optString("id"), x);
                    }
                    if (albums.isEmpty()) { s1.setVisibility(GONE); return; }
                    LinearLayout row = Widgets.rail(s1, 8);
                    for (Widgets.Item x : albums.values())
                        Widgets.addToRail(row, Widgets.posterCard(m, x, v -> m.openItem(x)), Widgets.posterW());
                }
                @Override public void err(int code, String msg) { s1.setVisibility(GONE); }
            });

            loadRail(s2, R.string.home_playlists, "/me/playlists?limit=30", "items", 92);

            s3.addView(Widgets.section(m, m.getString(R.string.home_for_you)));
            Spotify.get("/me/top/tracks?limit=18&time_range=short_term", new Spotify.Cb() {
                @Override public void ok(JSONObject o) {
                    List<Widgets.Item> l = items(o.optJSONArray("items"));
                    if (l.isEmpty()) { s3.setVisibility(GONE); return; }
                    Widgets.trackColumns(s3, l, i -> m.playList(l, i, null));
                }
                @Override public void err(int c, String e) { s3.setVisibility(GONE); }
            });

            if (Settings.pref(m, "home_artists", true))
                loadRail(s4, R.string.home_artists, "/me/top/artists?limit=15&time_range=medium_term", "items", 92);

            s5.addView(Widgets.section(m, m.getString(R.string.home_charts)));
            LinearLayout charts = Widgets.rail(s5, 8);
            String[][] ranges = {{"short_term", m.getString(R.string.chart_short)}, {"medium_term", m.getString(R.string.chart_medium)}, {"long_term", m.getString(R.string.chart_long)}};
            int[][] cols = {{0xFF32D74B, 0xFF8BE36A}, {0xFF5E3BEE, 0xFF2E2A8F}, {0xFFFF375F, 0xFFFF8A65}};
            for (int i = 0; i < ranges.length; i++) {
                final String range = ranges[i][0], label = ranges[i][1];
                Widgets.addToRail(charts, Widgets.gradientTile(m, label, label, cols[i][0], cols[i][1], Widgets.squareDp(),
                        v -> m.push(new ListPage(m, label, "top:" + range))), Ui.dp(Widgets.squareDp()));
            }

            if (Settings.pref(m, "home_new", true)) {
                s6.addView(Widgets.section(m, m.getString(R.string.home_new)));
                Spotify.get("/browse/new-releases?limit=20", new Spotify.Cb() {
                    @Override public void ok(JSONObject o) {
                        JSONObject al = o.optJSONObject("albums");
                        List<Widgets.Item> l = items(al == null ? null : al.optJSONArray("items"));
                        if (l.isEmpty()) { s6.setVisibility(GONE); return; }
                        LinearLayout row = Widgets.rail(s6, 8);
                        for (Widgets.Item x : l) Widgets.addToRail(row, Widgets.squareCard(m, x, Widgets.squareDp(), v -> m.openItem(x)), Ui.dp(Widgets.squareDp()));
                    }
                    @Override public void err(int c, String e) { s6.setVisibility(GONE); }
                });
            }
        }

        private void loadRail(LinearLayout slot, int titleRes, String path, String key, int size) {
            MainActivity m = main;
            slot.addView(Widgets.section(m, m.getString(titleRes)));
            Spotify.get(path, new Spotify.Cb() {
                @Override public void ok(JSONObject o) {
                    List<Widgets.Item> l = items(o.optJSONArray(key));
                    if (l.isEmpty()) { slot.setVisibility(GONE); return; }
                    LinearLayout row = Widgets.rail(slot, 8);
                    for (Widgets.Item x : l) Widgets.addToRail(row, Widgets.squareCard(m, x, Widgets.squareDp(), v -> m.openItem(x)), Ui.dp(Widgets.squareDp()));
                }
                @Override public void err(int c, String e) { slot.setVisibility(GONE); }
            });
        }
    }

    // ------------------------------------------------------------------ Explore
    static final class ExplorePage extends Page {
        static final String[] QUERIES = {"top hits", "k-pop", "pop", "hip hop", "r&b", "j-pop", "rock", "indie", "jazz", "classical", "ballad", "electronic"};
        private LinearLayout body;
        private int req;
        private int currentIdx = 0;
        private int seedSalt = 0;

        ExplorePage(MainActivity m) { super(m, m.getString(R.string.tab_explore), true); }

        @Override void build() {
            MainActivity m = main;
            if (m.isLocalSource()) { buildLocal(); return; }
            if (!Spotify.loggedIn()) { connectPrompt(this, content); return; }

            // Replace first genre chip with dynamic personalized "For you" feed
            String[] rawGenres = m.getResources().getStringArray(R.array.genres);
            String[] labels = new String[rawGenres.length];
            labels[0] = m.getString(R.string.exp_for_you);
            for (int i = 1; i < rawGenres.length; i++) labels[i] = rawGenres[i];

            LinearLayout bar = new LinearLayout(m);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.topMargin = Ui.dp(10);
            bl.bottomMargin = Ui.dp(10);

            Widgets.Chips chips = new Widgets.Chips(m, labels, idx -> { currentIdx = idx; load(idx); });
            bar.addView(chips, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            View refreshBtn = makeRefreshChip(m, () -> { seedSalt++; load(currentIdx); });
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(34));
            rl.leftMargin = Ui.dp(6);
            rl.rightMargin = Ui.dp(16);
            bar.addView(refreshBtn, rl);
            content.addView(bar, bl);

            body = new LinearLayout(m);
            body.setOrientation(LinearLayout.VERTICAL);
            content.addView(body, Widgets.MW);
            load(currentIdx);
        }

        private static View makeRefreshChip(Context c, Runnable onClick) {
            LinearLayout b = new LinearLayout(c);
            b.setOrientation(LinearLayout.HORIZONTAL);
            b.setGravity(Gravity.CENTER);
            b.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
            b.setBackground(Ui.round(Ui.chip(), 100));
            b.addView(new Icons.IconView(c, Icons.REPEAT, 14, Ui.ACCENT), new LinearLayout.LayoutParams(Ui.dp(16), Ui.dp(16)));
            TextView t = Ui.text(c, c.getString(R.string.exp_refresh), 12, Ui.semibold, Ui.ACCENT);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.leftMargin = Ui.dp(5);
            b.addView(t, tl);
            b.setOnClickListener(v -> onClick.run());
            Ui.press(b, 0.94f);
            return b;
        }

        private void load(int idx) {
            final int my = ++req;
            final int salt = seedSalt;
            MainActivity m = main;
            body.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                if (my != req) return;
                body.removeAllViews();
                body.addView(Widgets.spinner(m, 260));
                body.setAlpha(1f);
                if (idx == 0) {
                    loadForYou(my, salt);
                } else {
                    loadGenre(idx, my, salt);
                }
            }).start();
        }

        private void loadForYou(int my, int salt) {
            MainActivity m = main;
            Spotify.IO.execute(() -> {
                java.util.Random rng = new java.util.Random(System.nanoTime() ^ ((long) (salt + 1) * 0x9E3779B97F4A7C15L));
                java.util.Set<String> recentTrackIds = new java.util.HashSet<>();
                java.util.LinkedHashMap<String, JSONObject> seedArtists = new java.util.LinkedHashMap<>();
                java.util.LinkedHashMap<String, JSONObject> fallbackAlbums = new java.util.LinkedHashMap<>();
                java.util.List<String> seedGenres = new java.util.ArrayList<>();

                // 1) Fetch recently-played & top-artists IN PARALLEL (usually instant cache hits from Home!)
                java.util.concurrent.Future<JSONObject> fRec = Spotify.IO.submit(() -> {
                    try { return Spotify.callCached("/me/player/recently-played?limit=20"); } catch (Exception e) { return null; }
                });
                java.util.concurrent.Future<JSONObject> fTop = Spotify.IO.submit(() -> {
                    try { return Spotify.callCached("/me/top/artists?limit=15&time_range=medium_term"); } catch (Exception e) { return null; }
                });
                try {
                    JSONObject recObj = fRec.get(6, java.util.concurrent.TimeUnit.SECONDS);
                    JSONArray rec = recObj == null ? null : recObj.optJSONArray("items");
                    if (rec != null) {
                        for (int i = 0; i < rec.length(); i++) {
                            JSONObject tr = rec.optJSONObject(i);
                            if (tr != null && tr.optJSONObject("track") != null) tr = tr.optJSONObject("track");
                            if (tr == null) continue;
                            String tid = tr.optString("id");
                            if (!tid.isEmpty()) recentTrackIds.add(tid);
                            JSONObject al = tr.optJSONObject("album");
                            if (al != null && !al.optString("id").isEmpty()) fallbackAlbums.putIfAbsent(al.optString("id"), al);
                            JSONArray ars = tr.optJSONArray("artists");
                            if (ars != null && ars.length() > 0) {
                                JSONObject ar = ars.optJSONObject(0);
                                if (ar != null && !ar.optString("id").isEmpty()) seedArtists.putIfAbsent(ar.optString("id"), ar);
                            }
                        }
                    }
                } catch (Exception ignored) {}
                try {
                    JSONObject topObj = fTop.get(6, java.util.concurrent.TimeUnit.SECONDS);
                    JSONArray ta = topObj == null ? null : topObj.optJSONArray("items");
                    if (ta != null) {
                        for (int i = 0; i < ta.length(); i++) {
                            JSONObject ar = ta.optJSONObject(i);
                            if (ar == null) continue;
                            String id = ar.optString("id");
                            if (!id.isEmpty()) seedArtists.put(id, ar);
                            JSONArray gs = ar.optJSONArray("genres");
                            if (gs != null) {
                                for (int j = 0; j < gs.length(); j++) {
                                    String g = gs.optString(j, "").trim();
                                    if (!g.isEmpty()) seedGenres.add(g);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}

                java.util.List<JSONObject> artistList = new java.util.ArrayList<>(seedArtists.values());
                java.util.Collections.shuffle(artistList, rng);
                java.util.Collections.shuffle(seedGenres, rng);

                String becauseArtistName = null;
                String favId = null;
                if (!artistList.isEmpty()) {
                    JSONObject fav = artistList.get(0);
                    becauseArtistName = fav.optString("name", "");
                    favId = fav.optString("id", "");
                }

                // 2) Build at most 2 discovery queries & run them IN PARALLEL with the favorite artist's albums!
                java.util.List<String> queries = new java.util.ArrayList<>();
                java.util.Set<String> usedGenres = new java.util.HashSet<>();
                for (String g : seedGenres) {
                    if (usedGenres.add(g.toLowerCase(java.util.Locale.ROOT))) {
                        queries.add("genre:\"" + g + "\"");
                        if (queries.size() >= 1) break;
                    }
                }
                for (int i = 0; i < Math.min(2, artistList.size()) && queries.size() < 2; i++) {
                    String nm = artistList.get(i).optString("name", "").trim();
                    if (!nm.isEmpty()) queries.add(nm);
                }
                if (queries.isEmpty()) {
                    queries.add(GENRE_QUERY[1 + rng.nextInt(GENRE_QUERY.length - 1)]);
                }

                final String fid = favId;
                java.util.concurrent.Future<JSONObject> fFavAlbums = (fid != null && !fid.isEmpty())
                        ? Spotify.IO.submit(() -> {
                            try { return Spotify.callCached("/artists/" + fid + "/albums?include_groups=album,single&limit=10"); }
                            catch (Exception e) { return null; }
                        }) : null;

                final int off = (salt % 2) * 10;
                java.util.List<java.util.concurrent.Future<JSONObject>> fSearches = new java.util.ArrayList<>();
                for (String q : queries) {
                    final String fq = q;
                    fSearches.add(Spotify.IO.submit(() -> {
                        try {
                            JSONObject r = Spotify.callCached("/search?type=track&limit=10&offset=" + off + "&q=" + Spotify.enc(fq));
                            JSONObject trs = r == null ? null : r.optJSONObject("tracks");
                            JSONArray a = trs == null ? null : trs.optJSONArray("items");
                            if ((a == null || a.length() == 0) && off > 0) {
                                r = Spotify.callCached("/search?type=track&limit=10&offset=0&q=" + Spotify.enc(fq));
                            }
                            return r;
                        } catch (Exception e) { return null; }
                    }));
                }

                java.util.LinkedHashMap<String, JSONObject> becauseAlbums = new java.util.LinkedHashMap<>();
                java.util.LinkedHashMap<String, JSONObject> freshTracksMap = new java.util.LinkedHashMap<>();
                java.util.LinkedHashMap<String, JSONObject> discoverAlbums = new java.util.LinkedHashMap<>();

                if (fFavAlbums != null) {
                    try {
                        JSONObject favRes = fFavAlbums.get(8, java.util.concurrent.TimeUnit.SECONDS);
                        JSONArray al = favRes == null ? null : favRes.optJSONArray("items");
                        java.util.Set<String> seenNames = new java.util.HashSet<>();
                        if (al != null) {
                            for (int i = 0; i < al.length(); i++) {
                                JSONObject a = al.optJSONObject(i);
                                if (a != null && seenNames.add(a.optString("name").toLowerCase(java.util.Locale.ROOT))) {
                                    becauseAlbums.put(a.optString("id"), a);
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }

                for (java.util.concurrent.Future<JSONObject> fs : fSearches) {
                    try {
                        JSONObject r = fs.get(8, java.util.concurrent.TimeUnit.SECONDS);
                        JSONObject t = r == null ? null : r.optJSONObject("tracks");
                        JSONArray a = t == null ? null : t.optJSONArray("items");
                        if (a == null) continue;
                        for (int i = 0; i < a.length(); i++) {
                            JSONObject tr = a.optJSONObject(i);
                            if (tr == null) continue;
                            String tid = tr.optString("id");
                            if (!tid.isEmpty() && !recentTrackIds.contains(tid)) freshTracksMap.putIfAbsent(tid, tr);
                            JSONObject al = tr.optJSONObject("album");
                            if (al != null) {
                                String aid = al.optString("id");
                                if (!aid.isEmpty() && !becauseAlbums.containsKey(aid)) discoverAlbums.putIfAbsent(aid, al);
                            }
                        }
                    } catch (Exception ignored) {}
                }

                if (discoverAlbums.isEmpty() && !fallbackAlbums.isEmpty()) discoverAlbums.putAll(fallbackAlbums);

                java.util.List<JSONObject> fTracks = new java.util.ArrayList<>(freshTracksMap.values());
                java.util.Collections.shuffle(fTracks, rng);
                java.util.List<JSONObject> dAlbums = new java.util.ArrayList<>(discoverAlbums.values());
                java.util.Collections.shuffle(dAlbums, rng);
                java.util.List<JSONObject> bAlbums = new java.util.ArrayList<>(becauseAlbums.values());

                JSONArray trackArr = new JSONArray();
                for (int i = 0; i < Math.min(8, fTracks.size()); i++) trackArr.put(fTracks.get(i));
                JSONArray discArr = new JSONArray();
                for (int i = 0; i < Math.min(18, dAlbums.size()); i++) discArr.put(dAlbums.get(i));
                JSONArray becArr = new JSONArray();
                for (JSONObject o : bAlbums) becArr.put(o);

                final String finalBecause = becauseArtistName;
                Spotify.MAIN.post(() -> {
                    if (my != req) return;
                    body.removeAllViews();
                    List<Widgets.Item> tracks = items(trackArr);
                    List<Widgets.Item> disc = items(discArr);
                    List<Widgets.Item> bec = items(becArr);
                    if (disc.isEmpty() && tracks.isEmpty() && bec.isEmpty()) {
                        body.addView(Widgets.caption(m, m.getString(R.string.load_failed)));
                        return;
                    }
                    Widgets.Item heroItem = !disc.isEmpty() ? disc.get(0) : (!bec.isEmpty() ? bec.get(0) : null);
                    if (heroItem != null) body.addView(hero(heroItem));

                    if (!tracks.isEmpty()) {
                        body.addView(Widgets.section(m, m.getString(R.string.exp_fresh_tracks)));
                        addTrackCard(body, tracks, Math.min(6, tracks.size()));
                    }
                    if (!bec.isEmpty() && finalBecause != null && !finalBecause.isEmpty()) {
                        body.addView(Widgets.section(m, m.getString(R.string.exp_because, finalBecause)));
                        LinearLayout row = Widgets.rail(body, 10);
                        int sz = Widgets.squareDp();
                        for (Widgets.Item x : bec) {
                            Widgets.addToRail(row, Widgets.squareCard(m, x, sz, v -> m.openItem(x)), Ui.dp(sz));
                        }
                    }
                    if (disc.size() > 1) {
                        body.addView(Widgets.section(m, m.getString(R.string.exp_discover_albums)));
                        addGrid(body, disc, 1);
                    }
                    animateBodyIn();
                });
            });
        }

        private void loadGenre(int idx, int my, int salt) {
            MainActivity m = main;
            fetchAlbumsAndTracks(GENRE_QUERY[idx % GENRE_QUERY.length], salt, (albums, tracks, err) -> {
                if (my != req) return;
                body.removeAllViews();
                if (err != null && albums.isEmpty() && tracks.isEmpty()) {
                    body.addView(Widgets.caption(m, m.getString(R.string.load_failed)));
                    return;
                }
                if (albums.isEmpty() && tracks.isEmpty()) {
                    body.addView(Widgets.caption(m, m.getString(R.string.empty)));
                    return;
                }
                if (!albums.isEmpty()) body.addView(hero(albums.get(0)));
                if (!tracks.isEmpty()) {
                    body.addView(Widgets.section(m, m.getString(R.string.exp_fresh_tracks)));
                    addTrackCard(body, tracks, Math.min(5, tracks.size()));
                }
                if (albums.size() > 1) {
                    body.addView(Widgets.section(m, m.getString(R.string.exp_discover_albums)));
                    addGrid(body, albums, 1);
                }
                animateBodyIn();
            });
        }

        private void animateBodyIn() {
            body.setAlpha(0f);
            body.setTranslationY(Ui.dp(10));
            body.animate().alpha(1f).translationY(0).setDuration(320).setInterpolator(Ui.EMPHASIZED).start();
        }

        private void addTrackCard(LinearLayout parent, List<Widgets.Item> tracks, int limit) {
            MainActivity m = main;
            LinearLayout card = new LinearLayout(m);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.round(Ui.card(), Ui.gStyle ? 26 : 14));
            card.setClipToOutline(true);
            for (int i = 0; i < limit; i++) {
                final int idx = i;
                Widgets.Item it = tracks.get(i);
                card.addView(Widgets.trackRow(m, it, 0, v -> m.playList(tracks, idx, null),
                        m.isLocalSource() ? null : (v -> m.trackMenu(it, v))), Widgets.MW);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = lp.rightMargin = Ui.dp(16);
            parent.addView(card, lp);
        }

        interface GenreResult { void onDone(List<Widgets.Item> albums, List<Widgets.Item> tracks, Exception err); }

        /** Search filters per chip: fresh tracks in the genre (albums are always readable, unlike others' playlists). */
        static final String[] GENRE_QUERY = {"year:2025-2026", "genre:\"k-pop\" year:2024-2026", "genre:pop year:2024-2026",
                "genre:\"hip hop\" year:2024-2026", "genre:r&b year:2024-2026", "genre:\"j-pop\" year:2024-2026", "genre:rock year:2024-2026",
                "genre:indie year:2024-2026", "genre:jazz", "genre:classical", "genre:\"k-ballad\"", "genre:edm year:2024-2026"};

        /** Dynamic genre exploration: 2 cached pages fetched IN PARALLEL for fast 1-RTT display. */
        private void fetchAlbumsAndTracks(String q, int salt, GenreResult cb) {
            Spotify.IO.execute(() -> {
                java.util.Random rng = new java.util.Random(System.nanoTime() ^ ((long) (salt + 3) * 0x517CC1B727220A95L));
                java.util.LinkedHashMap<String, JSONObject> albums = new java.util.LinkedHashMap<>();
                java.util.LinkedHashMap<String, JSONObject> trackMap = new java.util.LinkedHashMap<>();
                java.util.Map<String, Integer> pop = new java.util.HashMap<>();
                final Exception[] first = {null};
                final int startOff = (salt % 2) * 10;
                java.util.List<java.util.concurrent.Future<JSONObject>> fPages = new java.util.ArrayList<>();
                for (int page = 0; page < 2; page++) {
                    final int p = page;
                    final int off = startOff + p * 10;
                    fPages.add(Spotify.IO.submit(() -> {
                        try {
                            JSONObject r = Spotify.callCached("/search?type=track&limit=10&offset=" + off + "&q=" + Spotify.enc(q));
                            JSONObject t = r == null ? null : r.optJSONObject("tracks");
                            JSONArray a = t == null ? null : t.optJSONArray("items");
                            if ((a == null || a.length() == 0) && off > 0 && p == 0) {
                                r = Spotify.callCached("/search?type=track&limit=10&offset=0&q=" + Spotify.enc(q));
                            }
                            return r;
                        } catch (Exception e) {
                            if (p == 0) first[0] = e;
                            return null;
                        }
                    }));
                }
                for (java.util.concurrent.Future<JSONObject> fp : fPages) {
                    try {
                        JSONObject r = fp.get(8, java.util.concurrent.TimeUnit.SECONDS);
                        JSONObject t = r == null ? null : r.optJSONObject("tracks");
                        JSONArray a = t == null ? null : t.optJSONArray("items");
                        if (a == null) continue;
                        for (int i = 0; i < a.length(); i++) {
                            JSONObject tr = a.optJSONObject(i);
                            if (tr == null) continue;
                            trackMap.putIfAbsent(tr.optString("id"), tr);
                            JSONObject al = tr.optJSONObject("album");
                            if (al == null) continue;
                            String id = al.optString("id");
                            if (!albums.containsKey(id)) albums.put(id, al);
                            int jitter = rng.nextInt(18);
                            pop.put(id, Math.max(pop.containsKey(id) ? pop.get(id) : 0, tr.optInt("popularity") + jitter));
                        }
                    } catch (Exception ignored) {}
                }
                java.util.List<JSONObject> alList = new java.util.ArrayList<>(albums.values());
                alList.sort((x, y) -> Integer.compare(pop.getOrDefault(y.optString("id"), 0), pop.getOrDefault(x.optString("id"), 0)));
                java.util.List<JSONObject> trList = new java.util.ArrayList<>(trackMap.values());
                java.util.Collections.shuffle(trList, rng);
                JSONArray alOut = new JSONArray();
                for (JSONObject o : alList) alOut.put(o);
                JSONArray trOut = new JSONArray();
                for (JSONObject o : trList) trOut.put(o);
                final Exception fe = first[0];
                Spotify.MAIN.post(() -> cb.onDone(items(alOut), items(trOut), fe));
            });
        }

        /** Local explore: refreshable spotlight hero + rediscover tracks + album grid. */
        private void buildLocal() {
            if (localBlocked(this, content)) return;
            MainActivity m = main;
            LinearLayout topBar = new LinearLayout(m);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            topBar.setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(16), Ui.dp(8));
            body = new LinearLayout(m);
            body.setOrientation(LinearLayout.VERTICAL);
            topBar.addView(makeRefreshChip(m, () -> { seedSalt++; renderLocalBody(); }),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(34)));
            content.addView(topBar, Widgets.MW);
            content.addView(body, Widgets.MW);
            renderLocalBody();
        }

        private void renderLocalBody() {
            MainActivity m = main;
            body.removeAllViews();
            List<Widgets.Item> l = new ArrayList<>(localGroups(true));
            if (l.isEmpty()) { body.addView(Widgets.caption(m, m.getString(R.string.empty))); return; }
            java.util.Random rng = new java.util.Random(System.nanoTime() ^ ((long) (seedSalt + 1) * 0x9E3779B97F4A7C15L));
            if (seedSalt > 0) java.util.Collections.shuffle(l, rng);
            body.addView(hero(l.get(0)));
            if (!LocalMusic.songs.isEmpty()) {
                List<Player.Track> pool = new ArrayList<>(LocalMusic.songs);
                java.util.Collections.shuffle(pool, rng);
                List<Widgets.Item> picks = new ArrayList<>();
                for (int i = 0; i < Math.min(5, pool.size()); i++) picks.add(LocalMusic.item(pool.get(i)));
                body.addView(Widgets.section(m, m.getString(R.string.exp_fresh_tracks)));
                addTrackCard(body, picks, picks.size());
            }
            if (l.size() > 1) {
                body.addView(Widgets.section(m, m.getString(R.string.exp_discover_albums)));
                addGrid(body, l, 1);
            }
        }

        /** Cards after the hero: 2 columns on a phone, more on unfolded / tablet screens (never stretched huge). */
        private void addGrid(LinearLayout parent, List<Widgets.Item> l, int startIdx) {
            int avail = Widgets.contentW(main) - Ui.dp(32);
            int cols = Math.max(2, Math.min(5, avail / Ui.dp(210)));
            int gap = Ui.dp(12), colW = (avail - gap * (cols - 1)) / cols;
            LinearLayout grid = null;
            for (int i = startIdx; i < l.size(); i++) {
                int k = (i - startIdx) % cols;
                if (k == 0) {
                    grid = new LinearLayout(main);
                    LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    gl.setMargins(Ui.dp(16), Ui.dp(12), Ui.dp(16), 0);
                    parent.addView(grid, gl);
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(colW, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (k < cols - 1) lp.rightMargin = gap;
                grid.addView(gridCard(l.get(i), colW), lp);
            }
        }

        private View hero(Widgets.Item it) {
            MainActivity m = main;
            // phone: tall 1:0.89 card; unfolded / tablet: a wide banner that leaves room for the grid below
            int w = Widgets.contentW(m) - Ui.dp(32);
            boolean wideScreen = w / Ui.density >= 560;
            int h = wideScreen ? (int) Math.min(w * 0.42f, Ui.screenH * 0.42f) : (int) Math.min(w * 0.89f, Ui.screenH * 0.62f);
            FrameLayout f = new FrameLayout(m);
            Img.ArtView art = new Img.ArtView(m, 20);
            art.setUrl(it.raw != null ? Spotify.image(it.raw, 640) : it.image);
            f.addView(art, new FrameLayout.LayoutParams(w, h));
            f.addView(new Shade(m, 20, 0.38f), new FrameLayout.LayoutParams(w, h));
            Icons.IconView note = new Icons.IconView(m, Icons.NOTE, 30, 0xFFFFFFFF);
            FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(Ui.dp(36), Ui.dp(36));
            nl.setMargins(Ui.dp(18), Ui.dp(18), 0, 0);
            f.addView(note, nl);
            LinearLayout col = new LinearLayout(m);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(Ui.dp(20), 0, Ui.dp(20), Ui.dp(18));
            TextView d = Ui.text(m, Widgets.nonEmpty(it.subtitle, ""), 12, Ui.regular, 0xCCFFFFFF);
            col.addView(d, Widgets.MW);
            TextView t = Ui.text(m, it.title, 24, Ui.bold, 0xFFFFFFFF);
            t.setSingleLine(false);
            t.setMaxLines(2);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.topMargin = Ui.dp(6);
            col.addView(t, tl);
            if (it.count >= 0) {
                TextView n = Ui.text(m, "▶  " + m.getString(R.string.n_tracks, it.count), 15, Ui.regular, 0xE6FFFFFF);
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ll.topMargin = Ui.dp(8);
                col.addView(n, ll);
            }
            f.addView(col, new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.leftMargin = Ui.dp(16);
            f.setLayoutParams(lp);
            f.setOnClickListener(v -> m.openItem(it));
            Ui.press(f, 0.97f);
            return f;
        }

        private View gridCard(Widgets.Item it, int w) {
            MainActivity m = main;
            LinearLayout v = new LinearLayout(m);
            v.setOrientation(LinearLayout.VERTICAL);
            FrameLayout f = new FrameLayout(m);
            int h = (int) (w * 0.92f);
            Img.ArtView art = new Img.ArtView(m, 16);
            art.setUrl(it.image);
            f.addView(art, new FrameLayout.LayoutParams(w, h));
            if (it.count >= 0) {
                TextView chip = Ui.text(m, "▶ " + m.getString(R.string.n_tracks, it.count), 12, Ui.semibold, 0xFFFFFFFF);
                chip.setPadding(Ui.dp(8), 0, Ui.dp(9), 0);
                chip.setBackground(Ui.round(0x66000000, 100));
                FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(24), Gravity.END);
                cl.setMargins(0, Ui.dp(8), Ui.dp(8), 0);
                f.addView(chip, cl);
            }
            v.addView(f, new LinearLayout.LayoutParams(w, h));
            TextView t = Ui.text(m, it.title, 13, Ui.medium, Ui.text());
            t.setSingleLine(false);
            t.setMaxLines(2);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.topMargin = Ui.dp(7);
            v.addView(t, tl);
            v.setOnClickListener(x -> m.openItem(it));
            Ui.press(v, 0.96f);
            return v;
        }
    }

    /** Bottom darkening gradient for text over artwork. */
    static final class Shade extends View {
        final float r, from;
        final Paint p = new Paint();
        final RectF rect = new RectF();
        Shade(Context c, float radiusDp, float from) { super(c); r = Ui.dpf(radiusDp); this.from = from; }
        @Override protected void onDraw(Canvas c) {
            p.setShader(new LinearGradient(0, getHeight() * from, 0, getHeight(), 0x00000000, 0xC0000000, Shader.TileMode.CLAMP));
            rect.set(0, 0, getWidth(), getHeight());
            c.drawRoundRect(rect, r, r, p);
        }
    }

    // ------------------------------------------------------------------ Library
    static final class LibraryPage extends Page {
        LibraryPage(MainActivity m) {
            super(m, m.getString(R.string.tab_library), true);
            setGrouped(true);
        }

        @Override void build() {
            MainActivity m = main;
            if (m.isLocalSource()) {
                Widgets.Group lg = new Widgets.Group(m, null);
                lg.row(Icons.NOTE, Ui.ACCENT, m.getString(R.string.all_songs), null, true, v -> { Widgets.Item all = new Widgets.Item(); all.title = m.getString(R.string.all_songs); m.push(new LocalListPage(m, all)); });
                lg.row(Icons.ALBUM, Ui.ACCENT, m.getString(R.string.lib_albums), null, true, v -> m.push(new LocalGroupsPage(m, true)));
                lg.row(Icons.ARTIST, Ui.ACCENT, m.getString(R.string.lib_artists), null, true, v -> m.push(new LocalGroupsPage(m, false)));
                content.addView(lg, Widgets.MW);
                LinearLayout s = slot();
                if (!localBlocked(this, s)) s.addView(Widgets.caption(m, m.getString(R.string.n_tracks, LocalMusic.songs.size())));
                return;
            }
            Widgets.Group g = new Widgets.Group(m, null);
            g.row(Icons.HEART, Ui.ACCENT, m.getString(R.string.lib_liked), null, true, v -> open(R.string.lib_liked, "liked"));
            g.row(Icons.QUEUE, Ui.ACCENT, m.getString(R.string.lib_playlists), null, true, v -> open(R.string.lib_playlists, "playlists"));
            g.row(Icons.ALBUM, Ui.ACCENT, m.getString(R.string.lib_albums), null, true, v -> open(R.string.lib_albums, "albums"));
            g.row(Icons.ARTIST, Ui.ACCENT, m.getString(R.string.lib_artists), null, true, v -> open(R.string.lib_artists, "artists"));
            g.row(Icons.CLOCK, Ui.ACCENT, m.getString(R.string.lib_recent), null, true, v -> open(R.string.lib_recent, "recent"));
            content.addView(g, Widgets.MW);
            if (!Spotify.loggedIn()) {
                View card = Widgets.messageCard(m, Icons.PROFILE, m.getString(R.string.lib_signin), m.getString(R.string.connect_spotify),
                        v -> { if (Spotify.configured()) Spotify.login(m); else m.push(new Settings.AccountPage(m)); });
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
                lp.topMargin = Math.max(Ui.dp(40), Ui.screenH / 2 - Ui.dp(330));
                content.addView(card);
                return;
            }
            content.addView(Widgets.section(m, m.getString(R.string.lib_recently_saved)));
            LinearLayout list = slot();
            list.addView(Widgets.spinner(m, 120));
            Spotify.get("/me/tracks?limit=20", new Spotify.Cb() {
                @Override public void ok(JSONObject o) {
                    list.removeAllViews();
                    List<Widgets.Item> l = items(o.optJSONArray("items"));
                    if (l.isEmpty()) { list.addView(Widgets.caption(m, m.getString(R.string.empty))); return; }
                    LinearLayout card = new LinearLayout(m);
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setBackground(Ui.round(Ui.card(), Ui.gStyle ? 26 : 13));
                    card.setClipToOutline(true);
                    for (int i = 0; i < l.size(); i++) {
                        final int idx = i;
                        card.addView(Widgets.trackRow(m, l.get(i), 0, v -> m.playList(l, idx, null), v -> m.trackMenu(l.get(idx), v)), Widgets.MW);
                    }
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.leftMargin = lp.rightMargin = Ui.dp(16);
                    list.addView(card, lp);
                }
                @Override public void err(int c, String e) {
                    list.removeAllViews();
                    list.addView(Widgets.caption(m, m.getString(R.string.load_failed)));
                }
            });
        }

        private void open(int title, String kind) {
            if (!Spotify.loggedIn()) { Player.toast(R.string.login_needed); return; }
            main.push(new ListPage(main, main.getString(title), kind));
        }
    }

    // ------------------------------------------------------------------ Generic list
    static final class ListPage extends Page {
        final String kind;
        private String next;
        private boolean loading;
        private final List<Widgets.Item> all = new ArrayList<>();
        private LinearLayout list;

        ListPage(MainActivity m, String title, String kind) {
            super(m, title, true);
            this.kind = kind;
            back = true;
            headerIcons = new int[0];
        }

        @Override void build() {
            list = slot();
            String path;
            switch (kind) {
                case "liked": path = "/me/tracks?limit=50"; break;
                case "playlists": path = "/me/playlists?limit=50"; break;
                case "albums": path = "/me/albums?limit=50"; break;
                case "artists": path = "/me/following?type=artist&limit=50"; break;
                case "recent": path = "/me/player/recently-played?limit=50"; break;
                default: path = "/me/top/tracks?limit=50&time_range=" + kind.substring(4); break;
            }
            if (isTracks()) addPlayButtons();
            else if ("playlists".equals(kind)) addCreatePlaylistButton();
            more(path);
        }

        private void addCreatePlaylistButton() {
            LinearLayout row = new LinearLayout(main);
            row.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(10));
            row.addView(DetailPage.pillButton(main, Icons.QUEUE, main.getString(R.string.pl_create), true,
                    v -> main.createPlaylistDialog(this::rebuild)), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(46)));
            content.addView(row, 0 + (bigTitle != null ? 1 : 0));
        }

        private boolean isTracks() { return kind.equals("liked") || kind.equals("recent") || kind.startsWith("top:"); }

        private void addPlayButtons() {
            LinearLayout row = new LinearLayout(main);
            row.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(10));
            row.addView(DetailPage.pillButton(main, Icons.PLAY_SMALL, main.getString(R.string.play), true, v -> { if (!all.isEmpty()) main.playList(all, 0, "liked".equals(kind) ? "liked" : null); }),
                    new LinearLayout.LayoutParams(0, Ui.dp(48), 1f));
            View gap = new View(main);
            row.addView(gap, new LinearLayout.LayoutParams(Ui.dp(12), 1));
            row.addView(DetailPage.pillButton(main, Icons.SHUFFLE, main.getString(R.string.shuffle), false, v -> {
                if (all.isEmpty()) return;
                main.shuffleList(all);
            }), new LinearLayout.LayoutParams(0, Ui.dp(48), 1f));
            content.addView(row, 0 + (bigTitle != null ? 1 : 0));
        }

        private void more(String path) {
            if (path == null || loading) return;
            loading = true;
            View sp = Widgets.spinner(main, 90);
            list.addView(sp);
            Spotify.get(path, new Spotify.Cb() {
                @Override public void ok(JSONObject o) {
                    loading = false;
                    list.removeView(sp);
                    JSONObject src = o.optJSONObject("artists") != null ? o.optJSONObject("artists") : o;
                    JSONArray arr = src.optJSONArray("items");
                    String n = src.optString("next", null);
                    next = n == null || "null".equals(n) ? null : n;
                    List<Widgets.Item> l = items(arr);
                    int base = all.size();
                    all.addAll(l);
                    if (all.isEmpty()) { list.addView(Widgets.caption(main, main.getString(R.string.empty))); return; }
                    for (int i = 0; i < l.size(); i++) {
                        final int idx = base + i;
                        Widgets.Item it = l.get(i);
                        if (isTracks()) {
                            list.addView(Widgets.trackRow(main, it, 0, v -> main.playList(all, idx, "liked".equals(kind) ? "liked" : null), v -> main.trackMenu(it, v)), Widgets.MW);
                        } else {
                            list.addView(entityRow(main, it), Widgets.MW);
                        }
                    }
                }
                @Override public void err(int c, String e) {
                    loading = false;
                    list.removeView(sp);
                    list.addView(Widgets.caption(main, main.getString(R.string.load_failed)));
                }
            });
        }

        @Override void onScrolled(int y) {
            if (next != null && !loading && y + scroll.getHeight() > content.getHeight() - Ui.dp(600)) more(next);
        }
    }

    static View entityRow(MainActivity m, Widgets.Item it) {
        LinearLayout r = new LinearLayout(m);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(16), Ui.dp(7), Ui.dp(16), Ui.dp(7));
        Img.ArtView art = new Img.ArtView(m, 8);
        art.circle = "artist".equals(it.type);
        if (art.circle) art.placeholderIcon = Icons.ARTIST;
        art.setUrl(it.image);
        r.addView(art, new LinearLayout.LayoutParams(Ui.dp(58), Ui.dp(58)));
        LinearLayout col = new LinearLayout(m);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(14), 0, Ui.dp(6), 0);
        col.addView(Ui.text(m, it.title, 16, Ui.regular, Ui.text()), Widgets.MW);
        String sub = it.subtitle;
        if ("artist".equals(it.type)) sub = m.getString(R.string.artist);
        else if (it.count >= 0) sub = (sub == null || sub.isEmpty() ? "" : sub + " · ") + m.getString(R.string.n_tracks, it.count);
        TextView s = Ui.text(m, Widgets.nonEmpty(sub, ""), 13, Ui.regular, Ui.text2());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = Ui.dp(3);
        col.addView(s, sl);
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(new Icons.IconView(m, Icons.CHEVRON, 15, Ui.text3()), new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)));
        r.setBackground(Widgets.ripple());
        r.setOnClickListener(v -> m.openItem(it));
        return r;
    }

    // ------------------------------------------------------------------ Detail (playlist / album / artist)
    static final class DetailPage extends Page {
        final Widgets.Item item;
        private final List<Widgets.Item> tracks = new ArrayList<>();
        private LinearLayout list, albumsSlot;

        DetailPage(MainActivity m, Widgets.Item it) {
            super(m, it.title, false);
            item = it;
            back = true;
            headerIcons = new int[]{Icons.MORE};
        }

        @Override boolean largeTop() { return false; }

        static View pillButton(Context c, int icon, String label, boolean accent, View.OnClickListener click) {
            LinearLayout b = new LinearLayout(c);
            b.setGravity(Gravity.CENTER);
            b.setBackground(Ui.round(accent ? Ui.ACCENT : Ui.chip(), 100));
            int fg = accent ? 0xFFFFFFFF : Ui.ACCENT;
            b.addView(new Icons.IconView(c, icon, 17, fg), new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)));
            TextView t = Ui.text(c, label, 16, Ui.semibold, fg);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.leftMargin = Ui.dp(6);
            b.addView(t, tl);
            b.setOnClickListener(click);
            Ui.press(b, 0.96f);
            return b;
        }

        @Override void applyInsets() {
            super.applyInsets();
            // the artist photo runs edge-to-edge under the status bar and header
            // (on wide screens the photo is a card in the left column, below the header)
            if ("artist".equals(item == null ? null : item.type) && !wideArtist()) content.setPadding(0, 0, main.sideInset(), main.bottomPadding());
        }

        @Override void build() {
            MainActivity m = main;
            boolean artist = "artist".equals(item.type);
            if (artist) { applyInsets(); buildArtist(); return; }
            LinearLayout head = new LinearLayout(m);
            head.setOrientation(LinearLayout.VERTICAL);
            head.setGravity(Gravity.CENTER_HORIZONTAL);
            int s = Math.min(Ui.dp(250), Ui.screenW - Ui.dp(120));
            Img.ArtView art = new Img.ArtView(m, 12);
            art.circle = artist;
            art.setElevation(Ui.dpf(10));
            art.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) {
                    if (artist) o.setOval(0, 0, v.getWidth(), v.getHeight());
                    else o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dpf(12));
                }
            });
            art.setUrl(Spotify.image(item.raw, 640));
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(s, s);
            al.topMargin = Ui.dp(8);
            head.addView(art, al);
            TextView t = Ui.text(m, item.title, 22, Ui.bold, Ui.text());
            t.setSingleLine(false);
            t.setMaxLines(2);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.setMargins(Ui.dp(24), Ui.dp(20), Ui.dp(24), 0);
            head.addView(t, tl);
            TextView sub = Ui.text(m, artist ? m.getString(R.string.artist) : Widgets.nonEmpty(item.subtitle, ""), 18, Ui.regular, Ui.ACCENT);
            sub.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sl.setMargins(Ui.dp(24), Ui.dp(4), Ui.dp(24), 0);
            head.addView(sub, sl);
            LinearLayout row = new LinearLayout(m);
            row.setPadding(Ui.dp(16), Ui.dp(18), Ui.dp(16), Ui.dp(10));
            row.addView(pillButton(m, Icons.PLAY_SMALL, m.getString(R.string.play), true, v -> playAt(0, false)), new LinearLayout.LayoutParams(0, Ui.dp(48), 1f));
            row.addView(new View(m), new LinearLayout.LayoutParams(Ui.dp(12), 1));
            row.addView(pillButton(m, Icons.SHUFFLE, m.getString(R.string.shuffle), false, v -> playAt(0, true)), new LinearLayout.LayoutParams(0, Ui.dp(48), 1f));
            head.addView(row, Widgets.MW);
            content.addView(head, Widgets.MW);
            list = slot();
            albumsSlot = slot();
            list.addView(Widgets.spinner(m, 140));
            switch (item.type) {
                case "album": loadAlbum(); break;
                case "artist": loadArtistTracks(); break;
                default: loadPlaylist(); break;
            }
        }

        private void showTracks(List<Widgets.Item> l, boolean numbered) {
            list.removeAllViews();
            tracks.clear();
            tracks.addAll(l);
            if (l.isEmpty()) { list.addView(Widgets.caption(main, main.getString(R.string.empty))); return; }
            boolean isPl = "playlist".equals(item.type);
            for (int i = 0; i < l.size(); i++) {
                final int idx = i;
                Widgets.Item it = l.get(i);
                View.OnClickListener moreClick = isPl
                        ? (v -> playlistTrackMenu(it, idx, v))
                        : (v -> main.trackMenu(it, v));
                list.addView(Widgets.trackRow(main, it, numbered ? i + 1 : 0, v -> playAt(idx, false), moreClick), Widgets.MW);
            }
            long total = 0;
            for (Widgets.Item it : l) if (it.raw != null) total += it.raw.optLong("duration_ms");
            list.addView(Widgets.caption(main, main.getString(R.string.n_tracks, l.size()) + " · " + main.getString(R.string.n_minutes, total / 60000)));
        }

        /** Track menu inside a Spotify playlist: includes remove & reorder actions for owned/editable playlists. */
        private void playlistTrackMenu(Widgets.Item it, int idx, View anchor) {
            float[] o = Glass.abs(anchor);
            android.graphics.RectF a = new android.graphics.RectF(o[0], o[1], o[0] + anchor.getWidth(), o[1] + anchor.getHeight());
            List<GlassMenu.Entry> e = new ArrayList<>();
            e.add(new GlassMenu.Entry(main.getString(R.string.play), Icons.PLAY_SMALL, false, () -> playAt(idx, false)));
            e.add(new GlassMenu.Entry(main.getString(R.string.add_to_queue), Icons.LIST, false, () ->
                    Spotify.send("POST", "/me/player/queue?uri=" + Spotify.enc(it.uri), null, new Spotify.Cb() {
                        @Override public void ok(JSONObject o2) { Player.toast(R.string.added_to_queue); }
                        @Override public void err(int c, String m) { Player.toast(R.string.action_failed); }
                    })));
            if (it.uri != null && it.uri.startsWith("spotify:track:")) {
                e.add(new GlassMenu.Entry(main.getString(R.string.pl_add_track), Icons.QUEUE, false, () -> main.addToPlaylistDialog(it.uri)));
                if (idx > 0) {
                    e.add(new GlassMenu.Entry(main.getString(R.string.pl_move_up), Icons.CHEVRON_UP, false, () ->
                            Spotify.reorderPlaylistTrack(item.id, idx, idx - 1, new Spotify.Cb() {
                                @Override public void ok(JSONObject r) { loadPlaylist(); }
                                @Override public void err(int c, String m) { Player.toast(c == 403 ? R.string.pl_relogin_scope : R.string.action_failed); }
                            })));
                }
                if (idx < tracks.size() - 1) {
                    e.add(new GlassMenu.Entry(main.getString(R.string.pl_move_down), Icons.CHEVRON, false, () ->
                            Spotify.reorderPlaylistTrack(item.id, idx, idx + 2, new Spotify.Cb() {
                                @Override public void ok(JSONObject r) { loadPlaylist(); }
                                @Override public void err(int c, String m) { Player.toast(c == 403 ? R.string.pl_relogin_scope : R.string.action_failed); }
                            })));
                }
                e.add(new GlassMenu.Entry(main.getString(R.string.pl_remove_track), Icons.CLOSE, false, () ->
                        Spotify.removeTrackFromPlaylist(item.id, it.uri, new Spotify.Cb() {
                            @Override public void ok(JSONObject r) { Player.toast(R.string.pl_removed); loadPlaylist(); }
                            @Override public void err(int c, String m) { Player.toast(c == 403 ? R.string.pl_relogin_scope : R.string.action_failed); }
                        })));
            }
            JSONObject al = it.raw == null ? null : it.raw.optJSONObject("album");
            if (al != null && al.optString("uri", null) != null)
                e.add(new GlassMenu.Entry(main.getString(R.string.go_album), Icons.ALBUM, false, () -> main.openUri(al.optString("uri"), al.optString("name"))));
            JSONArray ar = it.raw == null ? null : it.raw.optJSONArray("artists");
            if (ar != null && ar.length() > 0)
                e.add(new GlassMenu.Entry(main.getString(R.string.go_artist), Icons.ARTIST, false, () -> main.openUri(ar.optJSONObject(0).optString("uri"), ar.optJSONObject(0).optString("name"))));
            e.add(new GlassMenu.Entry(main.getString(R.string.open_in_spotify), Icons.LINK, false, () -> Player.openInSpotify(it.uri)));
            GlassMenu.show(main, a, e);
        }

        private void failed() {
            if ("playlist".equals(item.type)) {
                // never show it again; Spotify refuses this playlist for apps
                Spotify.block(item.id);
                Player.toast(R.string.hidden_playlist);
                main.pop();
                return;
            }
            list.removeAllViews();
            list.addView(Widgets.messageCard(main, Icons.LINK, main.getString(R.string.tracks_unavailable), main.getString(R.string.open_in_spotify),
                    v -> Player.openInSpotify(item.uri)));
        }

        private void loadPlaylist() {
            Spotify.get("/playlists/" + item.id, new Spotify.Cb() {
                @Override public void ok(JSONObject o) {
                    JSONObject t = o.optJSONObject("items");
                    if (t == null) t = o.optJSONObject("tracks");
                    JSONArray a = t == null ? null : t.optJSONArray("items");
                    if (a == null) { fallback(); return; }
                    showTracks(items(a), false);
                }
                @Override public void err(int c, String e) { fallback(); }
            });
        }

        private void fallback() {
            Spotify.getFirst(new Spotify.Cb() {
                @Override public void ok(JSONObject o) { showTracks(items(o.optJSONArray("items")), false); }
                @Override public void err(int c, String e) { failed(); }
            }, "/playlists/" + item.id + "/items?limit=100", "/playlists/" + item.id + "/tracks?limit=100");
        }

        private void loadAlbum() {
            Spotify.get("/albums/" + item.id, new Spotify.Cb() {
                @Override public void ok(JSONObject o) {
                    JSONObject t = o.optJSONObject("tracks");
                    JSONArray a = t == null ? null : t.optJSONArray("items");
                    List<Widgets.Item> l = items(a);
                    String img = Spotify.image(o, 120);
                    for (Widgets.Item it : l) {
                        it.image = img;
                        try { it.raw.put("album", new JSONObject().put("name", o.optString("name")).put("uri", o.optString("uri")).put("images", o.optJSONArray("images"))); } catch (Exception ignored) {}
                    }
                    showTracks(l, true);
                }
                @Override public void err(int c, String e) { failed(); }
            });
        }

        // ------------------------------------------------------------ artist
        private LinearLayout topSlot;
        private boolean following;
        private boolean followBusy;
        private LinearLayout followBtn;
        private Icons.IconView followIcon;
        private TextView followLabel;

        /** Unfolded foldable / tablet: photo + name + buttons in a left column, songs and releases on the right. */
        private boolean wideArtist() { return (Ui.screenW - main.sideInset() - main.contentInsetPx()) / Ui.density >= 600; }

        private View makeFollowButton(MainActivity m) {
            followBtn = new LinearLayout(m);
            followBtn.setGravity(Gravity.CENTER);
            followIcon = new Icons.IconView(m, Icons.HEART, 17, Ui.ACCENT);
            followBtn.addView(followIcon, new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)));
            followLabel = Ui.text(m, m.getString(R.string.follow), 15, Ui.semibold, Ui.ACCENT);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.leftMargin = Ui.dp(6);
            followBtn.addView(followLabel, tl);
            syncFollowUi();
            followBtn.setOnClickListener(v -> toggleFollow());
            Ui.press(followBtn, 0.96f);
            Spotify.isFollowingArtist(item.id, f -> {
                following = f != null && f;
                syncFollowUi();
            });
            return followBtn;
        }

        private void syncFollowUi() {
            if (followBtn == null) return;
            int fg = following ? 0xFFFFFFFF : Ui.ACCENT;
            followBtn.setBackground(Ui.round(following ? Ui.ACCENT : Ui.chip(), 100));
            followIcon.icon = following ? Icons.HEART_FILL : Icons.HEART;
            followIcon.color = fg;
            followIcon.invalidate();
            followLabel.setText(main.getString(following ? R.string.following : R.string.follow));
            followLabel.setTextColor(fg);
        }

        private void toggleFollow() {
            if (followBusy || !Spotify.loggedIn()) {
                if (!Spotify.loggedIn()) Player.toast(R.string.login_needed);
                return;
            }
            if (!Spotify.hasScope("user-library-modify") && !Spotify.hasScope("user-follow-modify") && Spotify.fixtures == null) {
                Player.toast(R.string.follow_relogin);
                return;
            }
            followBusy = true;
            final boolean target = !following;
            Spotify.setFollowArtist(item.id, target, new Spotify.Cb() {
                @Override public void ok(JSONObject o) {
                    followBusy = false;
                    following = target;
                    syncFollowUi();
                }
                @Override public void err(int c, String e) {
                    followBusy = false;
                    Player.toast(c == 401 || c == 403 ? R.string.follow_relogin : R.string.action_failed);
                }
            });
        }

        private void loadArtistEvents(LinearLayout slot) {
            MainActivity m = main;
            Spotify.fetchArtistEvents(item.title, arr -> {
                slot.removeAllViews();
                slot.addView(Widgets.section(m, m.getString(R.string.artist_events)));
                if (arr == null || arr.length() == 0) {
                    LinearLayout emptyCard = new LinearLayout(m);
                    emptyCard.setOrientation(LinearLayout.HORIZONTAL);
                    emptyCard.setGravity(Gravity.CENTER_VERTICAL);
                    emptyCard.setPadding(Ui.dp(18), Ui.dp(16), Ui.dp(18), Ui.dp(16));
                    emptyCard.setBackground(Ui.round(Ui.card(), Ui.gStyle ? 24 : 14));
                    emptyCard.addView(new Icons.IconView(m, Icons.CLOCK, 18, Ui.text3()), new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)));
                    TextView msg = Ui.text(m, m.getString(R.string.artist_no_events), 14, Ui.regular, Ui.text2());
                    msg.setSingleLine(false);
                    LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    ml.leftMargin = Ui.dp(12);
                    emptyCard.addView(msg, ml);
                    LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    cl.leftMargin = cl.rightMargin = Ui.dp(16);
                    slot.addView(emptyCard, cl);
                    return;
                }
                LinearLayout card = new LinearLayout(m);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackground(Ui.round(Ui.card(), Ui.gStyle ? 26 : 14));
                card.setClipToOutline(true);
                int count = Math.min(6, arr.length());
                for (int i = 0; i < count; i++) {
                    JSONObject ev = arr.optJSONObject(i);
                    if (ev == null) continue;
                    if (i > 0) {
                        View div = new View(m);
                        div.setBackgroundColor(Ui.separator());
                        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
                        dl.leftMargin = Ui.dp(16);
                        card.addView(div, dl);
                    }
                    String dt = ev.optString("datetime", "");
                    String dateStr = dt.length() >= 10 ? dt.substring(0, 10).replace('-', '.') : "";
                    JSONObject vObj = ev.optJSONObject("venue");
                    String venueName = vObj == null ? "" : vObj.optString("name", "");
                    String city = vObj == null ? "" : vObj.optString("city", "");
                    String country = vObj == null ? "" : vObj.optString("country", "");
                    String loc = city.isEmpty() ? country : (country.isEmpty() ? city : city + ", " + country);
                    String title = ev.optString("title", "").trim();
                    if (title.isEmpty()) title = venueName.isEmpty() ? item.title : venueName;
                    String sub = venueName.isEmpty() || venueName.equals(title) ? loc : (loc.isEmpty() ? venueName : venueName + " · " + loc);
                    final String evUrl = ev.optString("url", "");

                    LinearLayout row = new LinearLayout(m);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(Ui.dp(16), Ui.dp(13), Ui.dp(14), Ui.dp(13));
                    row.setBackground(Widgets.ripple());

                    if (!dateStr.isEmpty()) {
                        TextView badge = Ui.text(m, dateStr, 11, Ui.semibold, Ui.ACCENT);
                        badge.setGravity(Gravity.CENTER);
                        badge.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(6));
                        badge.setBackground(Ui.round(Ui.alpha(Ui.ACCENT, 0.14f), 100));
                        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        bl.rightMargin = Ui.dp(12);
                        row.addView(badge, bl);
                    }
                    LinearLayout col = new LinearLayout(m);
                    col.setOrientation(LinearLayout.VERTICAL);
                    TextView tv = Ui.text(m, title, 15, Ui.semibold, Ui.text());
                    col.addView(tv, Widgets.MW);
                    if (!sub.isEmpty()) {
                        TextView sv = Ui.text(m, sub, 13, Ui.regular, Ui.text2());
                        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        sl.topMargin = Ui.dp(2);
                        col.addView(sv, sl);
                    }
                    row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    if (!evUrl.isEmpty()) {
                        row.addView(new Icons.IconView(m, Icons.CHEVRON, 15, Ui.text3()), new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)));
                        row.setOnClickListener(x -> {
                            try {
                                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(evUrl));
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                                m.startActivity(intent);
                            } catch (Exception ignored) {}
                        });
                    }
                    card.addView(row, Widgets.MW);
                }
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cl.leftMargin = cl.rightMargin = Ui.dp(16);
                slot.addView(card, cl);
            });
        }

        private void buildArtistWide() {
            MainActivity m = main;
            int W = Ui.screenW - m.sideInset() - m.contentInsetPx();
            int colW = (int) Math.min(W * 0.36f, Math.min(Ui.dp(400), Ui.screenH * 0.55f));
            LinearLayout split = new LinearLayout(m);
            split.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout left = new LinearLayout(m);
            left.setOrientation(LinearLayout.VERTICAL);
            left.setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(8), 0);
            FrameLayout card = new FrameLayout(m);
            Img.ArtView photo = new Img.ArtView(m, 24);
            photo.placeholderIcon = Icons.ARTIST;
            photo.setUrl(Spotify.image(item.raw, 640));
            card.addView(photo, new FrameLayout.LayoutParams(colW, colW));
            card.setElevation(Ui.dpf(4));
            card.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dpf(24)); }
            });
            left.addView(card, new LinearLayout.LayoutParams(colW, colW));
            TextView name = Ui.text(m, item.title, 30, Ui.heavy, Ui.text());
            name.setSingleLine(false);
            name.setMaxLines(3);
            name.setPadding(Ui.dp(4), Ui.dp(18), 0, Ui.dp(14));
            left.addView(name, new LinearLayout.LayoutParams(colW, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout btns = new LinearLayout(m);
            btns.addView(pillButton(m, Icons.PLAY_SMALL, m.getString(R.string.play), true, v -> playAt(0, false)), new LinearLayout.LayoutParams(0, Ui.dp(46), 1f));
            btns.addView(new View(m), new LinearLayout.LayoutParams(Ui.dp(10), 1));
            btns.addView(pillButton(m, Icons.SHUFFLE, m.getString(R.string.shuffle), false, v -> playAt(0, true)), new LinearLayout.LayoutParams(0, Ui.dp(46), 1f));
            left.addView(btns, new LinearLayout.LayoutParams(colW, Ui.dp(46)));
            LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(colW, Ui.dp(44));
            fl.topMargin = Ui.dp(10);
            left.addView(makeFollowButton(m), fl);
            split.addView(left, new LinearLayout.LayoutParams(colW + Ui.dp(28), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout right = new LinearLayout(m);
            right.setOrientation(LinearLayout.VERTICAL);
            split.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            content.addView(split, Widgets.MW);
            // every section below lands in the right-hand column
            slotHost = right;
            topSlot = slot();
            topSlot.addView(Widgets.section(m, m.getString(R.string.popular_songs)));
            list = slot();
            list.addView(Widgets.spinner(m, 120));
            String[][] groups = {{"album", m.getString(R.string.albums)}, {"single", m.getString(R.string.singles_eps)},
                    {"appears_on", m.getString(R.string.appears_on)}, {"compilation", m.getString(R.string.compilations)}};
            for (String[] g : groups) loadGroup(slot(), g[0], g[1]);
            loadArtistEvents(slot());
            loadArtistTracks();
        }

        private void buildArtist() {
            if (wideArtist()) { buildArtistWide(); return; }
            MainActivity m = main;
            int W = Ui.screenW - m.sideInset() - m.contentInsetPx();
            int H = Math.min((int) (W * 0.92f), Ui.dp(480));
            FrameLayout hero = new FrameLayout(m);
            Img.ArtView photo = new Img.ArtView(m, 0);
            photo.placeholderIcon = Icons.ARTIST;
            photo.setUrl(Spotify.image(item.raw, 640));
            hero.addView(photo, new FrameLayout.LayoutParams(W, H));
            hero.addView(new Shade(m, 0, 0.45f), new FrameLayout.LayoutParams(W, H));
            TextView name = Ui.text(m, item.title, 36, Ui.heavy, 0xFFFFFFFF);
            name.setSingleLine(false);
            name.setMaxLines(2);
            FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(W - Ui.dp(110), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            nl.setMargins(Ui.dp(20), 0, 0, Ui.dp(22));
            hero.addView(name, nl);
            FrameLayout play = new FrameLayout(m);
            play.setBackground(Ui.round(Ui.ACCENT, 100));
            play.setElevation(Ui.dpf(6));
            play.addView(new Icons.IconView(m, Icons.PLAY_SMALL, 24, 0xFFFFFFFF), new FrameLayout.LayoutParams(Ui.dp(56), Ui.dp(56)));
            FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(Ui.dp(56), Ui.dp(56), Gravity.BOTTOM | Gravity.END);
            pl.setMargins(0, 0, Ui.dp(20), Ui.dp(20));
            hero.addView(play, pl);
            play.setOnClickListener(v -> playAt(0, false));
            Ui.press(play, 0.9f);
            content.addView(hero, new LinearLayout.LayoutParams(W, H));

            LinearLayout row = new LinearLayout(m);
            row.setPadding(Ui.dp(16), Ui.dp(16), Ui.dp(16), 0);
            row.addView(pillButton(m, Icons.SHUFFLE, m.getString(R.string.shuffle), false, v -> playAt(0, true)), new LinearLayout.LayoutParams(0, Ui.dp(44), 1f));
            row.addView(new View(m), new LinearLayout.LayoutParams(Ui.dp(10), 1));
            row.addView(makeFollowButton(m), new LinearLayout.LayoutParams(0, Ui.dp(44), 1f));
            content.addView(row, Widgets.MW);

            topSlot = slot();
            topSlot.addView(Widgets.section(m, m.getString(R.string.popular_songs)));
            list = slot();
            list.addView(Widgets.spinner(m, 120));
            String[][] groups = {{"album", m.getString(R.string.albums)}, {"single", m.getString(R.string.singles_eps)},
                    {"appears_on", m.getString(R.string.appears_on)}, {"compilation", m.getString(R.string.compilations)}};
            for (String[] g : groups) loadGroup(slot(), g[0], g[1]);
            loadArtistEvents(slot());
            loadArtistTracks();
        }

        private void loadArtistTracks() {
            Spotify.IO.execute(() -> {
                // /artists/{id}/top-tracks was removed in Spotify's Feb 2026 dev-mode update -> go straight to 1 cached search!
                JSONArray tracks = new JSONArray();
                try {
                    String q = "/search?type=track&limit=10&q=" + Spotify.enc("artist:\"" + item.title + "\"");
                    JSONObject t = Spotify.callCached(q).optJSONObject("tracks");
                    JSONArray a = t == null ? null : t.optJSONArray("items");
                    if (a != null) {
                        for (int i = 0; i < a.length(); i++) {
                            JSONObject tr = a.optJSONObject(i);
                            if (tr != null) tracks.put(tr);
                        }
                    }
                } catch (Exception ignored) {}
                final JSONArray ft = tracks;
                Spotify.MAIN.post(() -> showTopTracks(items(ft)));
            });
        }

        /** Top 5 rows, with a "show all" row that expands in place. */
        private void showTopTracks(List<Widgets.Item> l) {
            list.removeAllViews();
            tracks.clear();
            tracks.addAll(l);
            if (l.isEmpty()) { topSlot.setVisibility(GONE); list.setVisibility(GONE); return; }
            int shown = Math.min(5, l.size());
            for (int i = 0; i < l.size(); i++) {
                final int idx = i;
                Widgets.Item it = l.get(i);
                View r = Widgets.trackRow(main, it, 0, v -> playAt(idx, false), v -> main.trackMenu(it, v));
                if (i >= shown) r.setVisibility(GONE);
                list.addView(r, Widgets.MW);
            }
            if (l.size() > shown) {
                TextView more = Ui.text(main, main.getString(R.string.show_all), 15, Ui.semibold, Ui.ACCENT);
                more.setPadding(Ui.dp(20), Ui.dp(10), Ui.dp(20), Ui.dp(10));
                more.setOnClickListener(v -> {
                    for (int i = 0; i < list.getChildCount(); i++) list.getChildAt(i).setVisibility(VISIBLE);
                    more.setVisibility(GONE);
                });
                list.addView(more, Widgets.MW);
            }
        }

        /** 9.5.7 release rail UI: one horizontal rail per release type (album / single / appears_on / compilation). */
        private void loadGroup(LinearLayout slot, String group, String title) {
            Spotify.IO.execute(() -> {
                JSONArray all = new JSONArray();
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (int off = 0; off < 100; off += 10) {
                    try {
                        JSONObject o = Spotify.callCached("/artists/" + item.id + "/albums?include_groups=" + group + "&limit=10&offset=" + off);
                        JSONArray a = o.optJSONArray("items");
                        if (a == null || a.length() == 0) break;
                        for (int i = 0; i < a.length(); i++) {
                            JSONObject al = a.optJSONObject(i);
                            if (al != null && seen.add(al.optString("name").toLowerCase(java.util.Locale.ROOT))) all.put(al);
                        }
                        String next = o.optString("next", null);
                        if (next == null || "null".equals(next)) break;
                    } catch (Exception e) { break; }
                }
                Spotify.MAIN.post(() -> {
                    List<Widgets.Item> l = items(all);
                    if (l.isEmpty()) return;
                    slot.addView(Widgets.section(main, title + "  " + l.size()));
                    LinearLayout row = Widgets.rail(slot, 10);
                    int size = Widgets.squareDp();
                    for (Widgets.Item x : l) {
                        String date = x.raw.optString("release_date", "");
                        x.subtitle = date.length() >= 4 ? date.substring(0, 4) : "";
                        x.count = -1;
                        Widgets.addToRail(row, Widgets.squareCard(main, x, size, v -> main.openItem(x)), Ui.dp(size));
                    }
                });
            });
        }
        private void playAt(int idx, boolean shuffle) {
            if (shuffle) {
                Spotify.send("PUT", "/me/player/shuffle?state=true", null, null);
                Player.shuffle = true;
            }
            Widgets.Item first = tracks.isEmpty() ? null : tracks.get(Math.min(idx, tracks.size() - 1));
            Player.Track preview = first == null ? null : Player.Track.from(first.raw);
            if ("artist".equals(item.type)) {
                if (!tracks.isEmpty()) main.playList(tracks, idx, null);
                else Player.playContext(item.uri, null, null);
            } else {
                Player.playContext(item.uri, shuffle || first == null ? null : first.uri, shuffle ? null : preview);
            }
        }

        @Override void onScrolled(int y) {}

        void openMenu(android.graphics.RectF anchor) {
            List<GlassMenu.Entry> e = new ArrayList<>();
            if ("artist".equals(item.type)) {
                e.add(new GlassMenu.Entry(main.getString(following ? R.string.following : R.string.follow),
                        following ? Icons.HEART_FILL : Icons.HEART, following, this::toggleFollow));
            } else if ("playlist".equals(item.type)) {
                e.add(new GlassMenu.Entry(main.getString(R.string.pl_edit), Icons.APPEARANCE, false, () -> {
                    String curDesc = item.raw == null ? "" : item.raw.optString("description", "");
                    main.editPlaylistDialog(item.id, item.title, curDesc, (newName, newDesc) -> {
                        item.title = newName;
                        title = newName;
                        if (item.raw != null) {
                            try { item.raw.put("name", newName).put("description", newDesc); } catch (Exception ignored) {}
                        }
                        main.syncHeader();
                        rebuild();
                    });
                }));
            }
            e.add(new GlassMenu.Entry(main.getString(R.string.open_in_spotify), Icons.LINK, false, () -> Player.openInSpotify(item.uri)));
            e.add(new GlassMenu.Entry(main.getString(R.string.shuffle), Icons.SHUFFLE, false, () -> playAt(0, true)));
            GlassMenu.show(main, anchor, e);
        }
    }

    // ------------------------------------------------------------------ Search
    static final class SearchPage extends Page {
        private LinearLayout results;
        private String pending = "";
        private int req;
        private final Runnable run = this::doSearch;

        SearchPage(MainActivity m) {
            super(m, m.getString(R.string.search), true);
            headerIcons = new int[0];
        }

        @Override void build() {
            results = slot();
            showBrowse();
        }

        private void showBrowse() {
            MainActivity m = main;
            results.removeAllViews();
            results.addView(Widgets.section(m, m.getString(R.string.browse_genres)));
            String[] g = m.getResources().getStringArray(R.array.genres);
            int[][] cols = {{0xFFFF375F, 0xFFFF9F0A}, {0xFF5E5CE6, 0xFFBF5AF2}, {0xFF30B0C7, 0xFF32D74B}, {0xFFFF9F0A, 0xFFFFD60A},
                    {0xFF0A84FF, 0xFF64D2FF}, {0xFFBF5AF2, 0xFFFF375F}, {0xFF8E8E93, 0xFF3A3A3C}, {0xFF34C759, 0xFF30D158},
                    {0xFFAC8E68, 0xFF5E4B3C}, {0xFF1C1C1E, 0xFF5E5CE6}, {0xFFFF6482, 0xFFFFB3C1}, {0xFF00C7BE, 0xFF0A84FF}};
            int avail = Widgets.contentW(m) - Ui.dp(32);
            int gcols = Math.max(2, Math.min(4, avail / Ui.dp(200)));
            int colW = (avail - Ui.dp(12) * (gcols - 1)) / gcols;
            LinearLayout grid = null;
            for (int i = 1; i < g.length; i++) {
                if ((i - 1) % gcols == 0) {
                    grid = new LinearLayout(m);
                    LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    gl.setMargins(Ui.dp(16), 0, Ui.dp(16), Ui.dp(12));
                    results.addView(grid, gl);
                }
                final String label = g[i];
                final String q = ExplorePage.QUERIES[i];
                TextView tile = Ui.text(m, label, 17, Ui.bold, 0xFFFFFFFF);
                tile.setGravity(Gravity.BOTTOM | Gravity.START);
                tile.setPadding(Ui.dp(14), 0, Ui.dp(14), Ui.dp(12));
                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{cols[i % cols.length][0], cols[i % cols.length][1]});
                gd.setCornerRadius(Ui.dpf(12));
                tile.setBackground(gd);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(colW, Ui.dp(96));
                if ((i - 1) % gcols < gcols - 1) lp.rightMargin = Ui.dp(12);
                tile.setOnClickListener(v -> { main.dock.field.setText(q); main.dock.field.setSelection(q.length()); });
                Ui.press(tile, 0.96f);
                grid.addView(tile, lp);
            }
        }

        void query(String q, boolean now) {
            pending = q == null ? "" : q.trim();
            removeCallbacks(run);
            if (pending.isEmpty()) { req++; if (results != null) showBrowse(); return; }
            postDelayed(run, now ? 0 : 350);
        }

        private void doSearch() {
            final int my = ++req;
            final String q = pending;
            MainActivity m = main;
            results.removeAllViews();
            if (m.isLocalSource()) {
                List<Widgets.Item> tr = localSearch(q);
                if (tr.isEmpty()) { results.addView(Widgets.caption(m, m.getString(R.string.no_results))); return; }
                results.addView(Widgets.section(m, m.getString(R.string.songs)));
                for (int i = 0; i < Math.min(100, tr.size()); i++) {
                    final int idx = i;
                    results.addView(Widgets.trackRow(m, tr.get(i), 0, v -> m.playList(tr, idx, null), null), Widgets.MW);
                }
                return;
            }
            results.addView(Widgets.spinner(m, 160));
            Spotify.get("/search?type=track,artist,album,playlist&limit=8&q=" + Spotify.enc(q), new Spotify.Cb() {
                @Override public void ok(JSONObject o) {
                    if (my != req) return;
                    results.removeAllViews();
                    List<Widgets.Item> tr = items(sub(o, "tracks"));
                    List<Widgets.Item> ar = items(sub(o, "artists"));
                    List<Widgets.Item> al = items(sub(o, "albums"));
                    List<Widgets.Item> pl = items(sub(o, "playlists"));
                    if (tr.isEmpty() && ar.isEmpty() && al.isEmpty() && pl.isEmpty()) {
                        results.addView(Widgets.caption(m, m.getString(R.string.no_results)));
                        return;
                    }
                    if (!tr.isEmpty()) {
                        results.addView(Widgets.section(m, m.getString(R.string.songs)));
                        for (int i = 0; i < tr.size(); i++) {
                            final int idx = i;
                            results.addView(Widgets.trackRow(m, tr.get(i), 0, v -> m.playList(tr, idx, null), v -> m.trackMenu(tr.get(idx), v)), Widgets.MW);
                        }
                    }
                    rail(R.string.artists, ar);
                    rail(R.string.albums, al);
                    rail(R.string.playlists, pl);
                }
                @Override public void err(int c, String e) {
                    if (my != req) return;
                    results.removeAllViews();
                    results.addView(Widgets.caption(m, m.getString(Spotify.loggedIn() ? R.string.load_failed : R.string.login_needed)));
                }
            });
        }

        private JSONArray sub(JSONObject o, String k) {
            JSONObject x = o.optJSONObject(k);
            return x == null ? null : x.optJSONArray("items");
        }

        private void rail(int title, List<Widgets.Item> l) {
            if (l.isEmpty()) return;
            results.addView(Widgets.section(main, main.getString(title)));
            LinearLayout row = Widgets.rail(results, 10);
            for (Widgets.Item x : l) Widgets.addToRail(row, Widgets.squareCard(main, x, 120, v -> main.openItem(x)), Ui.dp(120));
        }
    }
}
