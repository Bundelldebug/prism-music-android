package com.oneuibook.prism;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class MainActivity extends Activity implements Dock.Listener, Header.Listener {
    static final String VERSION = "9.5.23";

    FrameLayout root;
    Glass.Backdrop backdrop;
    private FrameLayout pageHost;
    Header header;
    Dock dock;
    NowPlaying np;
    AodOverlay aod;
    private final NavStack[] tabs = new NavStack[4];
    private int tab;
    private Pages.SearchPage searchPage;
    private float playerP;

    private static boolean serviceHooked;

    static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences("prism", MODE_PRIVATE); }

    @Override protected void attachBaseContext(Context base) {
        String lang = prefs(base).getString("lang", "system");
        if (!"system".equals(lang)) {
            Configuration cfg = new Configuration(base.getResources().getConfiguration());
            cfg.setLocale(new Locale(lang));
            base = base.createConfigurationContext(cfg);
        }
        super.attachBaseContext(base);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences p = prefs(this);
        Ui.init(this, p);
        Spotify.init(p);
        AudioInfo.init(this);
        if (android.os.Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !p.getBoolean("asked_bt_perm", false)) {
            p.edit().putBoolean("asked_bt_perm", true).apply();
            requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT}, 42);
        }
        Img.init(this);
        Player.activity = this;
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);

        Window w = getWindow();
        w.setDecorFitsSystemWindows(false);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.setNavigationBarContrastEnforced(false);
        w.getDecorView().setBackgroundColor(Ui.bg());

        root = new FrameLayout(this);
        backdrop = new Glass.Backdrop(this);
        backdrop.setBackgroundColor(Ui.bg());
        root.addView(backdrop, match());
        pageHost = new FrameLayout(this);
        backdrop.addView(pageHost, match());

        header = new Header(this, backdrop);
        header.setListener(this);
        root.addView(header, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, headerHeight()));
        // the header's height depends on the screen shape (upright pill beside the foldable rail): keep it right
        // whenever the window changes size, not only when the status bar inset changes
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitHeader());

        dock = new Dock(this, backdrop, new String[]{getString(R.string.tab_home), getString(R.string.tab_explore),
                getString(R.string.tab_library), getString(R.string.tab_settings)});
        dock.setListener(this);
        root.addView(dock, match());

        np = new NowPlaying(this, dock);
        root.addView(np, match());

        aod = new AodOverlay(this);
        root.addView(aod, match());

        setContentView(root);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int st = insets.getInsets(WindowInsets.Type.statusBars()).top;
            int nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
            boolean changed = st != Ui.statusInset || nav != Ui.navInset;
            Ui.statusInset = st;
            Ui.navInset = nav;
            Ui.imeInset = ime;
            dock.setImeOffset(ime);
            // after a fold / unfold re-creation the inset is often unchanged, but this new screen may need another height
            fitHeader();
            if (changed) {
                for (NavStack s : tabs) if (s != null) s.applyInsets();
                if (searchPage != null) searchPage.applyInsets();
                np.requestLayout();
            }
            return WindowInsets.CONSUMED;
        });

        // glass surfaces re-sample the backdrop every frame something changed underneath them
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                updateScrollChrome(currentPage());
                // heat fix: glass re-samples the page only on frames where the page actually changed
                // (or while chrome is animating), instead of every frame anything on screen moves
                boolean pageChanged = backdrop.isDirty();
                if (pageChanged || header.animating()) header.invalidate();
                if (dock.getVisibility() == View.VISIBLE && (pageChanged || dock.animating())) dock.invalidate();
                for (int i = 0; i < root.getChildCount(); i++) {
                    View c = root.getChildAt(i);
                    if (c instanceof GlassMenu) c.invalidate();
                }
                if (np.getVisibility() == View.VISIBLE && playerP < 1f) np.invalidate();
                return true;
            }
        });

        LocalMusic.app = getApplicationContext();
        YouTubeMusic.app = getApplicationContext();
        tabs[0] = new NavStack(new Pages.HomePage(this));
        tabs[1] = new NavStack(new Pages.ExplorePage(this));
        tabs[2] = new NavStack(new Pages.LibraryPage(this));
        tabs[3] = new NavStack(new Settings.SettingsPage(this));
        for (NavStack s : tabs) { s.setVisibility(View.GONE); pageHost.addView(s, match()); }
        int start = state == null ? 0 : state.getInt("tab", 0);
        tab = start;
        tabs[tab].setVisibility(View.VISIBLE);
        tabs[tab].top().show();
        dock.select(tab, false);
        syncHeader();
        setLightStatus(true);

        if (Spotify.loggedIn() && (Spotify.userName == null || Spotify.userId().isEmpty())) Spotify.loadProfile(null);
        // pre-warm OAuth token off-thread right away, and defer WebView creation past the first home frame
        // so Chromium init never stalls the initial cover/track reveal
        if (Spotify.loggedIn()) Spotify.prewarm();
        // on-device library is read once in the background even while Spotify is the source, so switching is instant
        if (!LocalMusic.loaded && LocalMusic.hasPermission(this)) LocalMusic.load(this, () -> { if (isLocalSource()) reloadAll(); });
        root.postDelayed(this::startSpotifyPlayer, 220);
        // keep the media session / foreground service in step with every playback change
        LocalMusic.app = getApplicationContext();
        YouTubeMusic.app = getApplicationContext();
        LocalMusic.artChanged = this::onAlbumArtChanged;
        // once per process: the notification must follow playback even after this window is gone
        if (!serviceHooked) {
            serviceHooked = true;
            Context app = getApplicationContext();
            Player.listeners.add(() -> LocalMusic.PlaybackService.update(app));
        }
        handleIntent(getIntent());
        // new release on GitHub? (at most once a day, can be turned off in Settings → About)
        if (state == null) root.postDelayed(() -> Updater.autoCheck(this), 3000);
        // tablet: the sidebar stays open across rotation / fold re-creation (only where the tablet UI applies)
        if (sidebarWanted) root.post(() -> { if (tabletUi()) openSidebar(false); });
        if (state != null) {
            final Bundle st0 = state;
            root.post(() -> {
                screenChangeTransition(st0);
                if (st0.getBoolean("np_open") && Player.track != null) root.postDelayed(np::open, 180);
            });
        }
    }

    /** The foldable rail stands the header pill upright, so the header needs more height there. */
    private int headerHeight() {
        int W = root != null && root.getWidth() > 0 ? root.getWidth() : Ui.screenW;
        int H = root != null && root.getHeight() > 0 ? root.getHeight() : Ui.screenH;
        return Ui.statusInset + Ui.dp(Dock.railFor(W, H) ? 172 : 88);
    }

    private void fitHeader() {
        if (header == null) return;
        int h = headerHeight();
        if (header.getLayoutParams().height == h) return;
        header.getLayoutParams().height = h;
        header.post(header::requestLayout);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
        // fold / unfold re-creates the activity on the other screen: remember where the chrome was
        if (dock != null && dock.getWidth() > 0) out.putFloatArray("dock_geo", dock.snapshot());
        if (np != null) out.putBoolean("np_open", np.isOpen());
        if (header != null) { float[] hg = header.snapshot(); if (hg != null) out.putFloatArray("hdr_geo", hg); }
        // the last frame of this screen, faded out over the new one so folding / rotating never cuts hard
        lastFrame = isChangingConfigurations() ? captureFrame() : null;
    }

    /** Last frame before a fold / rotation re-creation (same process, so a static hand-over is enough). */
    private static android.graphics.Bitmap lastFrame;

    private android.graphics.Bitmap captureFrame() {
        try {
            int w = root.getWidth(), h = root.getHeight();
            if (w <= 0 || h <= 0) return null;
            float s = 0.5f;   // half resolution: it is only on screen for a quarter of a second while fading
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap((int) (w * s), (int) (h * s), android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            c.scale(s, s);
            root.draw(c);   // software canvas: glass falls back to its frosted fill for this one frame
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Fold ↔ unfold and rotation: old frame fades away, the page settles in, header and dock morph from their old shapes. */
    private void screenChangeTransition(Bundle state) {
        android.graphics.Bitmap frame = lastFrame;
        lastFrame = null;
        float[] g = state.getFloatArray("dock_geo");
        if (g == null) return;
        boolean grew = Ui.screenW > g[0] * 1.08f;
        boolean shrank = Ui.screenW < g[0] * 0.92f;
        boolean reshaped = Math.abs(Ui.screenH - g[1]) > g[1] * 0.08f;
        if (!grew && !shrank && !reshaped) return;
        if (frame != null) {
            android.widget.ImageView old = new android.widget.ImageView(this);
            old.setImageBitmap(frame);
            old.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            root.addView(old, match());
            old.animate().alpha(0f).scaleX(grew ? 1.06f : 0.96f).scaleY(grew ? 1.06f : 0.96f).setDuration(280).setInterpolator(Ui.DECEL)
                    .withEndAction(() -> { root.removeView(old); frame.recycle(); }).start();
        }
        float from = grew ? 0.92f : 1.06f;
        pageHost.setAlpha(0f);
        pageHost.setScaleX(from); pageHost.setScaleY(from);
        pageHost.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(Ui.EMPHASIZED).start();
        float[] hg = state.getFloatArray("hdr_geo");
        if (hg != null) header.morphFrom(hg);
        else {
            header.setAlpha(0f);
            header.animate().alpha(1f).setStartDelay(140).setDuration(380).setInterpolator(Ui.DECEL).start();
        }
        dock.morphFrom(g);
    }

    @Override protected void onResume() {
        super.onResume();
        Player.activity = this;
        Player.start(this);
    }

    @Override protected void onPause() {
        super.onPause();
        if (aod != null && aod.isOpen()) aod.closeImmediate();
        if (Player.playing && Player.track != null && Player.onThisPhone()) {
            LocalMusic.PlaybackService.claimControl(getApplicationContext());
        }
        Player.stop();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        Uri d = i == null ? null : i.getData();
        if (d == null || !"prismmusic".equals(d.getScheme())) return;
        Spotify.handleRedirect(d, () -> {
            Toast.makeText(this, getString(R.string.connected_as, Widgets.nonEmpty(Spotify.userName, "Spotify")), Toast.LENGTH_SHORT).show();
            reloadAll();
            startSpotifyPlayer();
            Player.refresh();
        }, err -> Toast.makeText(this, getString(R.string.login_failed, err), Toast.LENGTH_LONG).show());
    }

    // ------------------------------------------------------------------ navigation
    /** Per-tab stack of pages with slide-in push/pop and an edge swipe-back gesture. */
    final class NavStack extends FrameLayout {
        final List<Page> stack = new ArrayList<>();
        private float edgeDownX, edgeDownY;
        private boolean edgeDrag;
        private VelocityTracker vt;

        NavStack(Page rootPage) {
            super(MainActivity.this);
            stack.add(rootPage);
            addView(rootPage, match());
        }

        Page top() { return stack.get(stack.size() - 1); }

        void applyInsets() { for (Page p : stack) p.applyInsets(); }

        void push(Page p) {
            Page prev = top();
            stack.add(p);
            addView(p, match());
            p.show();
            int W = getWidth() > 0 ? getWidth() : Ui.screenW;
            p.setTranslationX(W);
            p.setElevation(Ui.dpf(1));
            android.animation.TimeInterpolator interp = Ui.gStyle ? Ui.ONEUI_SINE_90 : Ui.EMPHASIZED;
            p.animate().translationX(0).setDuration(Ui.gStyle ? 400 : 460).setInterpolator(interp).start();
            prev.animate().translationX(-W * 0.28f).alpha(0.9f).setDuration(Ui.gStyle ? 400 : 460).setInterpolator(interp).start();
            syncHeader();
        }

        boolean pop() {
            if (stack.size() <= 1) return false;
            Page p = stack.remove(stack.size() - 1);
            Page prev = top();
            int W = getWidth();
            android.animation.TimeInterpolator interp = Ui.gStyle ? Ui.ONEUI_SINE_80 : Ui.EMPHASIZED;
            p.animate().translationX(W).setDuration(Ui.gStyle ? 350 : 380).setInterpolator(interp).withEndAction(() -> removeView(p)).start();
            prev.animate().translationX(0).alpha(1f).setDuration(Ui.gStyle ? 350 : 380).setInterpolator(interp).start();
            syncHeader();
            return true;
        }

        void popToRoot() { while (stack.size() > 1) pop(); }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            if (stack.size() <= 1) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    edgeDownX = e.getX(); edgeDownY = e.getY();
                    edgeDrag = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (edgeDownX < Ui.dpf(28) && e.getX() - edgeDownX > Ui.dpf(12) && Math.abs(e.getY() - edgeDownY) < Ui.dpf(30)) {
                        edgeDrag = true;
                        if (vt != null) vt.recycle();
                        vt = VelocityTracker.obtain();
                        return true;
                    }
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!edgeDrag) return false;
            if (vt != null) vt.addMovement(e);
            Page p = top(), prev = stack.get(stack.size() - 2);
            int W = getWidth();
            float dx = Math.max(0, e.getX() - edgeDownX);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    p.setTranslationX(dx);
                    prev.setTranslationX(-W * 0.28f * (1f - dx / W));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float v = 0;
                    if (vt != null) { vt.computeCurrentVelocity(1000); v = vt.getXVelocity(); vt.recycle(); vt = null; }
                    edgeDrag = false;
                    if (dx > W / 3f || v > 1000) pop();
                    else {
                        p.animate().translationX(0).setDuration(300).setInterpolator(Ui.EMPHASIZED).start();
                        prev.animate().translationX(-W * 0.28f).setDuration(300).setInterpolator(Ui.EMPHASIZED).start();
                    }
                    return true;
                }
            }
            return true;
        }
    }

    private boolean searching;

    Page currentPage() {
        if (searchPage != null && searching) return searchPage;
        return tabs[tab].top();
    }

    void push(Page p) {
        if (searching) { onExitSearch(); }
        tabs[tab].push(p);
        dock.setCollapsed(false);
    }

    void pop() { tabs[tab].pop(); }

    /** Foldable inner screens keep the tab rail on the right: leave room for it. */
    int sideInset() { return Dock.railFor(Ui.screenW, Ui.screenH) ? Ui.dp(20 + 64 + 12) : 0; }

    int bottomPadding() { return dock == null ? Ui.dp(200) : dock.chromeHeight() + Ui.dp(12); }

    void syncHeader() {
        Page p = currentPage();
        header.configure(p.title, p.headerIcons, p.back);
        updateScrollChrome(p);
    }

    private void updateScrollChrome(Page p) {
        float t = p.titleProgress();
        header.setScroll(t, p.blurProgress());
        if (p.bigTitle != null) p.bigTitle.setAlpha(1f - t);
    }

    private float collapseAcc;

    void onPageScroll(Page p, int y, int dy) {
        if (p != currentPage()) return;
        updateScrollChrome(p);
        if (dock.inSearch()) { dock.hideIme(); return; }
        if ((dy > 0) != (collapseAcc > 0)) collapseAcc = 0;
        collapseAcc += dy;
        if (y < Ui.dp(60)) dock.setCollapsed(false);
        else if (collapseAcc > Ui.dp(28)) dock.setCollapsed(true);
        else if (collapseAcc < -Ui.dp(14)) dock.setCollapsed(false);
    }

    private void switchTab(int i) {
        if (i == tab) return;
        NavStack from = tabs[tab], to = tabs[i];
        tab = i;
        if (dock.selected != i) dock.select(i, true);
        from.animate().cancel();
        to.animate().cancel();
        from.animate().alpha(0f).setDuration(140).withEndAction(() -> { from.setVisibility(View.GONE); from.setAlpha(1f); }).start();
        to.setVisibility(View.VISIBLE);
        to.setAlpha(0f);
        to.setScaleX(0.985f);
        to.setScaleY(0.985f);
        to.top().show();
        to.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(60).setDuration(300).setInterpolator(Ui.EMPHASIZED).start();
        if (sidebar != null) sidebar.setSelected(i);
        dock.setCollapsed(false);
        syncHeader();
    }

    // ------------------------------------------------------------------ Dock.Listener
    @Override public void onTab(int index, boolean reselect) {
        if (searching) { onExitSearch(); dock.select(index, true); }
        if (reselect) {
            NavStack s = tabs[index];
            if (s.stack.size() > 1) s.popToRoot(); else s.top().scrollToTop();
            return;
        }
        switchTab(index);
    }

    @Override public void onMini() { np.open(); }

    @Override public void onMiniAction(int which, RectF anchor) {
        if (which == Dock.MINI_MORE) np.trackMenuAt(anchor);
        else np.openPanel(which == Dock.MINI_LYRICS ? 1 : 2);
    }

    // ------------------------------------------------------------------ tablet sidebar
    private Sidebar sidebar;
    /** Survives fold / rotation re-creation so the sidebar comes back where the user left it. */
    private static boolean sidebarWanted;
    private float sideP;
    private android.animation.ValueAnimator sideAnim;

    boolean tabletUi() { return Dock.tabletFor(root.getWidth() > 0 ? root.getWidth() : Ui.screenW, root.getHeight() > 0 ? root.getHeight() : Ui.screenH); }

    @Override public void onSidebar() { if (sideP > 0.5f) closeSidebar(); else openSidebar(true); }

    void openSidebar(boolean animate) {
        if (!tabletUi()) return;
        if (searching) onExitSearch();
        sidebarWanted = true;
        int W = root.getWidth() > 0 ? root.getWidth() : Ui.screenW;
        float sw = Sidebar.widthFor(W);
        if (sidebar == null) {
            sidebar = new Sidebar(this);
            // above the page, below the header / dock / player
            // right above the page backdrop (the in-app player WebView may sit before it at index 0)
            root.addView(sidebar, root.indexOfChild(backdrop) + 1, new FrameLayout.LayoutParams((int) sw, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        sidebar.getLayoutParams().width = (int) sw;
        sidebar.setSelected(tab);
        sidebar.setVisibility(View.VISIBLE);
        // content re-flows once into the narrower area, then slides over to its new place
        setContentInset((int) sw);
        animateSidebar(1f, animate);
    }

    void closeSidebar() {
        sidebarWanted = false;
        if (sidebar == null || sideP <= 0f) return;
        animateSidebar(0f, true);
    }

    private void animateSidebar(float to, boolean animate) {
        if (sideAnim != null) sideAnim.cancel();
        if (!animate) { applySidebar(to); if (to == 0f) finishClose(); return; }
        sideAnim = Ui.animate(sideP, to, to > 0 ? 460 : 380, Ui.EMPHASIZED, a -> applySidebar((float) a.getAnimatedValue()));
        sideAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean canceled;
            @Override public void onAnimationCancel(android.animation.Animator a) { canceled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) { if (!canceled && to == 0f) finishClose(); }
        });
    }

    /** One frame of the open / close slide: sidebar in from the left, content (already inset) slides along. */
    private void applySidebar(float p) {
        sideP = p;
        float sw = sidebar == null ? 0 : sidebar.getLayoutParams().width;
        if (sidebar != null) sidebar.setTranslationX(-sw * (1f - p));
        float dx = -sw * (1f - p);
        pageHost.setTranslationX(dx);
        header.setTranslationX(dx);
        dock.setSidebar(sw, p);
    }

    /** Closed: hide the sidebar and give the content its full width back. */
    private void finishClose() {
        sideP = 0f;
        if (sidebar != null) sidebar.setVisibility(View.GONE);
        setContentInset(0);
        pageHost.setTranslationX(0);
        header.setTranslationX(0);
        dock.setSidebar(0, 0);
    }

    private int contentInset = -1;

    private void setContentInset(int px) {
        if (px == contentInset) return;
        contentInset = px;
        ((FrameLayout.LayoutParams) pageHost.getLayoutParams()).leftMargin = px;
        pageHost.requestLayout();
        ((FrameLayout.LayoutParams) header.getLayoutParams()).leftMargin = px;
        header.requestLayout();
        // pages that size their grids from the content width lay out again for the new width
        for (NavStack s : tabs) for (Page p : s.stack) if (p instanceof Pages.ExplorePage) p.rebuild();
    }

    int contentInsetPx() { return Math.max(0, contentInset); }

    void sidebarSearch() {
        closeSidebar();
        root.postDelayed(this::onSearch, 300);
    }

    void sidebarTab(int i) {
        if (i == tab) { NavStack s = tabs[i]; if (s.stack.size() > 1) s.popToRoot(); else s.top().scrollToTop(); return; }
        onTab(i, false);
    }

    /** Sidebar library shortcuts: library tab, back to its root, then that list. */
    void openLibrarySection(String kind) {
        if (tab != 2) onTab(2, false);
        tabs[2].popToRoot();
        Page p;
        switch (kind) {
            case "all": { Widgets.Item all = new Widgets.Item(); all.title = getString(R.string.all_songs); p = new Pages.LocalListPage(this, all); break; }
            case "lalbums": p = new Pages.LocalGroupsPage(this, true); break;
            case "lartists": p = new Pages.LocalGroupsPage(this, false); break;
            default: {
                if (!Spotify.loggedIn()) { Player.toast(R.string.login_needed); return; }
                int title = "liked".equals(kind) ? R.string.lib_liked : "playlists".equals(kind) ? R.string.lib_playlists
                        : "albums".equals(kind) ? R.string.lib_albums : "artists".equals(kind) ? R.string.lib_artists : R.string.lib_recent;
                p = new Pages.ListPage(this, getString(title), kind);
            }
        }
        push(p);
    }

    @Override public void onSearch() {
        if (searchPage == null) {
            searchPage = new Pages.SearchPage(this);
            pageHost.addView(searchPage, match());
        }
        searching = true;
        searchPage.setVisibility(View.VISIBLE);
        searchPage.show();
        searchPage.setAlpha(0f);
        searchPage.setTranslationY(Ui.dp(16));
        searchPage.animate().alpha(1f).translationY(0).setDuration(320).setInterpolator(Ui.EMPHASIZED).start();
        dock.setSearchMode(true);
        syncHeader();
    }

    @Override public void onExitSearch() {
        searching = false;
        dock.setSearchMode(false);
        if (searchPage != null) {
            Pages.SearchPage s = searchPage;
            s.animate().alpha(0f).setDuration(200).withEndAction(() -> s.setVisibility(View.GONE)).start();
        }
        header.post(this::syncHeader);
    }

    @Override public void onQuery(String q, boolean submit) {
        if (searchPage != null) searchPage.query(q, submit);
    }

    // ------------------------------------------------------------------ Header.Listener
    @Override public void onHeaderButton(int icon, RectF anchor) {
        if (icon == Icons.QUEUE) {
            // music source picker: Local / Spotify / YouTube Music
            List<GlassMenu.Entry> e = new ArrayList<>();
            e.add(new GlassMenu.Entry(getString(R.string.source_local), Icons.NOTE, isLocalSource(), () -> setSource("local")));
            e.add(new GlassMenu.Entry("Spotify", Icons.WAVE, isSpotifySource(), () -> setSource("spotify")));
            e.add(new GlassMenu.Entry(getString(R.string.source_ytm), Icons.YTM, isYtmSource(), () -> setSource("ytm")));
            if (isSpotifySource() && Spotify.loggedIn())
                e.add(new GlassMenu.Entry(getString(R.string.choose_device), Icons.DEVICE, false, () -> np.deviceMenuAt(anchor)));
            GlassMenu.show(this, anchor, e);
        } else if (icon == Icons.PROFILE) {
            List<GlassMenu.Entry> e = new ArrayList<>();
            if (Spotify.loggedIn()) {
                e.add(new GlassMenu.Entry(Widgets.nonEmpty(Spotify.userName, getString(R.string.connected)), Icons.PROFILE, false, () -> push(new Settings.AccountPage(this))));
                e.add(new GlassMenu.Entry(getString(R.string.logout), Icons.LOGOUT, false, () -> {
                    Spotify.logout();
                    Player.track = null;
                    Player.notifyAll0();
                    reloadAll();
                }));
            } else {
                e.add(new GlassMenu.Entry(getString(R.string.connect_spotify), Icons.LINK, false, () -> {
                    if (Spotify.configured()) Spotify.login(this); else push(new Settings.AccountPage(this));
                }));
            }
            GlassMenu.show(this, anchor, e);
        } else if (icon == Icons.MORE) {
            Page p = currentPage();
            if (p instanceof Pages.DetailPage) ((Pages.DetailPage) p).openMenu(anchor);
        }
    }

    @Override public void onBack() { onBackPressed(); }

    /** Starts the in-app Spotify player, or asks to reconnect once if the old login lacks "streaming". */
    void startSpotifyPlayer() {
        if (!Spotify.loggedIn()) return;
        if (!Spotify.hasScope("streaming")) {
            if (!prefs(this).getBoolean("asked_streaming", false)) {
                prefs(this).edit().putBoolean("asked_streaming", true).apply();
                Toast.makeText(this, R.string.relogin_streaming, Toast.LENGTH_LONG).show();
            }
            return;
        }
        SpotifyWeb.start(this, root);
    }

    @Override protected void onDestroy() {
        if (Player.activity == this) Player.activity = null;
        if (isFinishing() && !Player.playing) NpuAudio.release();
        // closing the app (Recents swipe / back) while Spotify plays in-app: the player keeps running in this process
        // under the media service, like any music app. Paused, or a re-creation: just let go of this window.
        if (isFinishing() && !(Player.playing && LocalMusic.PlaybackService.inApp())) {
            SpotifyWeb.stop();
            if (!Player.playing) LocalMusic.PlaybackService.stopNow();
        } else {
            SpotifyWeb.detach(this);
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ music source
    static String source(Context c) {
        return prefs(c).getString("source", Spotify.loggedIn() ? "spotify" : "local");
    }

    /** On-device music is the default until a Spotify account is connected. */
    static boolean isLocalSource(Context c) { return "local".equals(source(c)); }
    static boolean isYtmSource(Context c) { return "ytm".equals(source(c)); }

    boolean isLocalSource() { return isLocalSource(this); }
    boolean isYtmSource() { return isYtmSource(this); }
    boolean isSpotifySource() { return !isLocalSource() && !isYtmSource(); }

    // ------------------------------------------------------------------ custom album & playlist covers
    private String coverAlbum;
    private java.util.function.Consumer<Uri> playlistCoverCb;

    void pickCover(String albumKey) {
        coverAlbum = albumKey;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        try { startActivityForResult(i, 77); } catch (Exception e) { Toast.makeText(this, R.string.action_failed, Toast.LENGTH_SHORT).show(); }
    }

    void pickPlaylistCover(java.util.function.Consumer<Uri> cb) {
        playlistCoverCb = cb;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        try { startActivityForResult(i, 78); } catch (Exception e) { Toast.makeText(this, R.string.action_failed, Toast.LENGTH_SHORT).show(); }
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (code == 78 && playlistCoverCb != null) {
            java.util.function.Consumer<Uri> cb = playlistCoverCb;
            playlistCoverCb = null;
            cb.accept(data.getData());
            return;
        }
        if (code != 77 || coverAlbum == null) return;
        String album = coverAlbum;
        Uri img = data.getData();
        Spotify.IO.execute(() -> {
            // decode + save off the main thread; the swap on screen happens through LocalMusic.artChanged
            boolean ok = LocalMusic.setCover(album, img);
            if (!ok) Spotify.MAIN.post(() -> Toast.makeText(this, R.string.action_failed, Toast.LENGTH_SHORT).show());
        });
    }

    /**
     * An album's cover changed: swap the images in place on every page (home, library, album, mini bar…) and redo
     * only the page in front (its cover buttons change). Rebuilding every page at once is what froze the home screen.
     */
    void onAlbumArtChanged(String from, String to) {
        Img.replaceArt(root, from, to);
        Page p = currentPage();
        if (p instanceof Pages.LocalListPage) p.rebuild();
    }

    void setSource(String s) {
        prefs(this).edit().putString("source", s).apply();
        if ("local".equals(s) && !LocalMusic.hasPermission(this)) LocalMusic.requestPermission(this);
        // not read yet (e.g. permission granted after start): read it now and show it as soon as it is in
        else if ("local".equals(s) && !LocalMusic.loaded) LocalMusic.load(this, () -> { if (isLocalSource()) reloadAll(); });
        reloadAll();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == 41) LocalMusic.load(this, this::reloadAll);
        if (code == 42) AudioInfo.init(this, true);
    }

    void openAod() {
        if (aod != null) aod.open();
    }

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        int code = event.getKeyCode();
        if (code == android.view.KeyEvent.KEYCODE_VOLUME_UP || code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                || code == android.view.KeyEvent.KEYCODE_VOLUME_MUTE) {
            boolean handled = super.dispatchKeyEvent(event);
            if (np != null) {
                np.syncVolumeNow();
                root.post(np::syncVolumeNow);
            }
            return handled;
        }
        return super.dispatchKeyEvent(event);
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (aod != null && aod.isOpen()) { aod.close(); return; }
        if (GlassMenu.dismissAny(root)) return;
        if (np.getVisibility() == View.VISIBLE && playerP > 0.5f) { np.close(); return; }
        if (searching) { onExitSearch(); return; }
        if (tabs[tab].pop()) return;
        if (tab != 0) { dock.select(0, true); switchTab(0); return; }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ player chrome
    void onPlayerProgress(float p) {
        playerP = p;
        header.setContentAlpha(Ui.clamp(1f - p * 1.6f, 0f, 1f));
        float da = Ui.clamp(1f - p * 1.8f, 0f, 1f);
        dock.setAlpha(da);
        dock.setTranslationY(Ui.dpf(30) * Ui.clamp(p * 1.4f, 0f, 1f));
        dock.invalidate(); // moved: its glass must re-sample at the new offset
        header.invalidate();
        dock.setVisibility(p >= 0.999f ? View.INVISIBLE : View.VISIBLE);
        header.setVisibility(p >= 0.999f ? View.INVISIBLE : View.VISIBLE);
        setLightStatus(p < 0.45f);
    }

    private Boolean lightStatus;

    private void setLightStatus(boolean light) {
        boolean want = light && !Ui.dark;
        if (lightStatus != null && want == lightStatus && getWindow().getInsetsController() != null) return;
        lightStatus = want;
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        c.setSystemBarsAppearance(want ? mask : 0, mask);
    }

    void invalidateChrome() {
        header.invalidate();
        dock.invalidate();
    }

    /** Re-applies style (A / G) or accent color across all chrome, sidebar, player, and pages without losing position. */
    void refreshAppearance() {
        getWindow().getDecorView().setBackgroundColor(Ui.bg());
        backdrop.setBackgroundColor(Ui.bg());
        np.applyStyle();
        dock.select(tab, false);
        dock.requestLayout();
        invalidateChrome();
        if (sidebar != null) {
            boolean wasOpen = sidebar.getVisibility() == View.VISIBLE && sideP > 0.5f;
            root.removeView(sidebar);
            sidebar = null;
            if (wasOpen && tabletUi()) openSidebar(false);
        }
        Page cur = currentPage();
        int keepY = cur == null ? 0 : cur.scroll.getScrollY();
        for (NavStack s : tabs) {
            if (s == null) continue;
            for (Page p : s.stack) {
                p.setGrouped(p.grouped);
                p.rebuild();
            }
        }
        if (searchPage != null) {
            searchPage.setGrouped(searchPage.grouped);
            searchPage.rebuild();
        }
        if (cur != null && keepY > 0) cur.scroll.post(() -> cur.scroll.scrollTo(0, keepY));
    }

    void reloadAll() {
        for (NavStack s : tabs) {
            while (s.stack.size() > 1) { Page p = s.stack.remove(s.stack.size() - 1); s.removeView(p); }
            s.top().setTranslationX(0);
            s.top().setAlpha(1f);
            s.top().rebuild();
        }
        if (sidebar != null) sidebar.buildLibrary();
        syncHeader();
    }

    /** Refreshes the content tabs but leaves any stack showing settings where it is (a settings toggle must not throw
     *  the user back to the settings root). */
    void reloadContent() {
        for (NavStack s : tabs) {
            boolean inSettings = false;
            for (Page p : s.stack) if (p instanceof Settings.SettingsPage || p instanceof Settings.Sub) { inSettings = true; break; }
            if (inSettings) continue;
            while (s.stack.size() > 1) { Page p = s.stack.remove(s.stack.size() - 1); s.removeView(p); }
            s.top().setTranslationX(0);
            s.top().setAlpha(1f);
            s.top().rebuild();
        }
        syncHeader();
    }

    // ------------------------------------------------------------------ actions
    void openItem(Widgets.Item it) {
        if (it == null) return;
        if (it.action != null) { it.action.run(); return; }
        if ("lalbum".equals(it.type) || "lartist".equals(it.type)) { push(new Pages.LocalListPage(this, it)); return; }
        if ("track".equals(it.type) || "ltrack".equals(it.type) || "ytm_track".equals(it.type)) {
            List<Widgets.Item> one = new ArrayList<>();
            one.add(it);
            playList(one, 0, null);
            return;
        }
        push(new Pages.DetailPage(this, it));
    }

    /** Navigates directly to the currently playing playlist / album / source list (from Expanded Player Queue). */
    void openCurrentPlayingContext() {
        Player.Track t = Player.track;
        if (t == null) return;
        if (np != null && np.isOpen()) np.close();
        String src = Player.playingSourceUri;
        if ((src == null || src.isEmpty()) && Player.contextUri != null && !Player.contextUri.isEmpty()) {
            src = Player.contextUri;
        }
        if (Player.local()) {
            if (src != null && (src.startsWith("lalbum") || src.startsWith("lartist:"))) {
                openUri(src, Player.playingSourceTitle != null ? Player.playingSourceTitle : t.album);
            } else if (t.albumUri != null && !t.albumUri.isEmpty()) {
                openUri(t.albumUri, t.album);
            } else {
                Widgets.Item all = new Widgets.Item();
                all.title = getString(R.string.all_songs);
                push(new Pages.LocalListPage(this, all));
            }
            return;
        }
        if (Player.ytm()) {
            if (!isYtmSource()) setSource("ytm");
            if (tab != 0) { dock.select(0, true); switchTab(0); }
            tabs[0].popToRoot();
            return;
        }
        if ("liked".equals(src)) {
            openLibrarySection("liked");
            return;
        }
        if (src != null && src.startsWith("spotify:")) {
            openUri(src, Player.playingSourceTitle != null ? Player.playingSourceTitle : t.album);
            return;
        }
        if (t.albumUri != null && !t.albumUri.isEmpty()) {
            openUri(t.albumUri, t.album);
            return;
        }
        openLibrarySection("playlists");
    }

    /** Opens spotify:album:… / spotify:artist:… / spotify:playlist:… after fetching its header data. */
    void openUri(String uri, String name) {
        if (uri == null) return;
        if (uri.startsWith("lalbum") || uri.startsWith("lartist:")) {
            List<Player.Track> l = LocalMusic.tracksFor(uri);
            if (!l.isEmpty()) openItem(LocalMusic.group(uri, l, uri.startsWith("lalbum")));
            return;
        }
        String[] p = uri.split(":");
        if (p.length < 3) return;
        String type = p[1], id = p[2];
        Spotify.get("/" + type + "s/" + id, new Spotify.Cb() {
            @Override public void ok(JSONObject o) { openItem(Widgets.Item.of(o)); }
            @Override public void err(int c, String m) {
                try { openItem(Widgets.Item.of(new JSONObject().put("type", type).put("id", id).put("uri", uri).put("name", name))); } catch (Exception ignored) {}
            }
        });
    }

    void playList(List<Widgets.Item> items, int idx, String ctx) {
        if (items == null || items.isEmpty()) return;
        idx = Math.max(0, Math.min(idx, items.size() - 1));
        if (items.get(0).track != null) {
            List<Player.Track> l = new ArrayList<>();
            for (Widgets.Item i : items) if (i.track != null) l.add(i.track);
            if (!l.isEmpty() && YouTubeMusic.isYtm(l.get(0))) {
                YouTubeMusic.play(l, idx, ctx);
                return;
            }
            String srcTitle = (!l.isEmpty() && idx < l.size()) ? l.get(idx).album : null;
            LocalMusic.play(l, idx, ctx, srcTitle);
            return;
        }
        Widgets.Item cur = items.get(idx);
        Player.playingSourceUri = ctx;
        Player.playingSourceTitle = cur != null ? cur.title : null;
        int from = Math.max(0, idx - 15), to = Math.min(items.size(), idx + 50);
        List<String> uris = new ArrayList<>();
        List<Player.Track> trackSnap = new ArrayList<>();
        int targetPos = -1;
        for (int i = from; i < to; i++) {
            Widgets.Item it = items.get(i);
            String u = it == null ? null : it.uri;
            // Only send valid Spotify playable URIs: a single local/null URI makes PUT /me/player/play fail with 400!
            if (u == null || !(u.startsWith("spotify:track:") || u.startsWith("spotify:episode:"))) continue;
            if (i == idx) targetPos = uris.size();
            uris.add(u);
            Player.Track tr = it.raw != null ? Player.Track.from(it.raw) : null;
            if (tr == null) {
                tr = new Player.Track();
                tr.id = it.id; tr.uri = it.uri; tr.name = it.title; tr.artist = it.subtitle; tr.art = it.image; tr.artSmall = it.image;
            }
            trackSnap.add(tr);
        }
        if (targetPos < 0) {
            String cu = cur == null ? null : cur.uri;
            if (cu != null && (cu.startsWith("spotify:track:") || cu.startsWith("spotify:episode:"))) {
                uris.clear();
                uris.add(cu);
                trackSnap.clear();
                Player.Track tr = cur.raw != null ? Player.Track.from(cur.raw) : null;
                if (tr != null) trackSnap.add(tr);
                targetPos = 0;
            }
        }
        if (uris.isEmpty()) { Player.toast(R.string.playback_failed); return; }
        Player.setActiveQueue(trackSnap);
        Player.playUris(uris, Math.max(0, targetPos), Player.Track.from(cur.raw));
    }

    void shuffleList(List<Widgets.Item> items) {
        List<Widgets.Item> l = new ArrayList<>(items);
        Collections.shuffle(l, new Random());
        Spotify.send("PUT", "/me/player/shuffle?state=true", null, null);
        Player.shuffle = true;
        playList(l, 0, null);
    }

    void trackMenu(Widgets.Item it, View anchor) {
        float[] o = Glass.abs(anchor);
        RectF a = new RectF(o[0], o[1], o[0] + anchor.getWidth(), o[1] + anchor.getHeight());
        List<GlassMenu.Entry> e = new ArrayList<>();
        e.add(new GlassMenu.Entry(getString(R.string.play), Icons.PLAY_SMALL, false, () -> {
            List<Widgets.Item> one = new ArrayList<>();
            one.add(it);
            playList(one, 0, null);
        }));
        e.add(new GlassMenu.Entry(getString(R.string.add_to_queue), Icons.LIST, false, () ->
                Spotify.send("POST", "/me/player/queue?uri=" + Spotify.enc(it.uri), null, new Spotify.Cb() {
                    @Override public void ok(JSONObject o2) { Player.toast(R.string.added_to_queue); }
                    @Override public void err(int c, String m) { Player.toast(R.string.action_failed); }
                })));
        if (it.uri != null && it.uri.startsWith("spotify:track:")) {
            e.add(new GlassMenu.Entry(getString(R.string.pl_add_track), Icons.QUEUE, false, () -> addToPlaylistDialog(it.uri)));
        }
        JSONObject al = it.raw == null ? null : it.raw.optJSONObject("album");
        if (al != null && al.optString("uri", null) != null)
            e.add(new GlassMenu.Entry(getString(R.string.go_album), Icons.ALBUM, false, () -> openUri(al.optString("uri"), al.optString("name"))));
        org.json.JSONArray ar = it.raw == null ? null : it.raw.optJSONArray("artists");
        if (ar != null && ar.length() > 0)
            e.add(new GlassMenu.Entry(getString(R.string.go_artist), Icons.ARTIST, false, () -> openUri(ar.optJSONObject(0).optString("uri"), ar.optJSONObject(0).optString("name"))));
        e.add(new GlassMenu.Entry(getString(R.string.open_in_spotify), Icons.LINK, false, () -> Player.openInSpotify(it.uri)));
        GlassMenu.show(this, a, e);
    }

    /** Builds the clickable cover-image picker row shared by createPlaylistDialog & editPlaylistDialog. */
    private android.widget.LinearLayout playlistCoverRow(String existingUrl, Uri[] pickedOut) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(4), 0, Ui.dp(12));

        android.widget.FrameLayout thumbBox = new android.widget.FrameLayout(this);
        thumbBox.setBackground(Ui.round(Ui.chip(), 14));
        thumbBox.setClipToOutline(true);
        Img.ArtView artPrev = new Img.ArtView(this, 14);
        artPrev.placeholderIcon = Icons.ALBUM;
        if (existingUrl != null && !existingUrl.isEmpty()) artPrev.setUrl(existingUrl);
        android.widget.ImageView localPrev = new android.widget.ImageView(this);
        localPrev.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        localPrev.setVisibility(View.GONE);
        thumbBox.addView(artPrev, new FrameLayout.LayoutParams(Ui.dp(68), Ui.dp(68)));
        thumbBox.addView(localPrev, new FrameLayout.LayoutParams(Ui.dp(68), Ui.dp(68)));
        row.addView(thumbBox, new android.widget.LinearLayout.LayoutParams(Ui.dp(68), Ui.dp(68)));

        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(14), 0, 0, 0);
        android.widget.TextView btn = Ui.text(this, getString(existingUrl != null && !existingUrl.isEmpty()
                ? R.string.pl_cover_change : R.string.pl_cover_pick), 14, Ui.semibold, Ui.ACCENT);
        btn.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8));
        btn.setBackground(Ui.round(Ui.alpha(Ui.ACCENT, 0.12f), 100));
        col.addView(btn, new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        android.widget.TextView sub = Ui.text(this, getString(R.string.pl_cover_hint), 12, Ui.regular, Ui.text2());
        sub.setSingleLine(false);
        android.widget.LinearLayout.LayoutParams sl = new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = Ui.dp(5);
        col.addView(sub, sl);
        row.addView(col, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View.OnClickListener pickClick = v -> pickPlaylistCover(uri -> {
            pickedOut[0] = uri;
            localPrev.setImageURI(uri);
            localPrev.setVisibility(View.VISIBLE);
            sub.setText(getString(R.string.pl_cover_selected));
            btn.setText(getString(R.string.pl_cover_change));
        });
        thumbBox.setOnClickListener(pickClick);
        btn.setOnClickListener(pickClick);
        return row;
    }

    /** Creates a new Spotify playlist (with optional custom cover image upload), then calls {@code onCreated}. */
    void createPlaylistDialog(Runnable onCreated) {
        if (!Spotify.loggedIn()) { Player.toast(R.string.login_needed); return; }
        final Uri[] pickedCover = {null};
        android.widget.LinearLayout form = new android.widget.LinearLayout(this);
        form.setOrientation(android.widget.LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(22), Ui.dp(12), Ui.dp(22), Ui.dp(4));
        form.addView(playlistCoverRow(null, pickedCover), Widgets.MW);
        android.widget.EditText nameIn = new android.widget.EditText(this);
        nameIn.setHint(R.string.pl_name_hint);
        nameIn.setSingleLine(true);
        form.addView(nameIn, Widgets.MW);
        android.widget.EditText descIn = new android.widget.EditText(this);
        descIn.setHint(R.string.pl_desc_hint);
        form.addView(descIn, Widgets.MW);
        android.widget.CheckBox pub = new android.widget.CheckBox(this);
        pub.setText(R.string.pl_public);
        pub.setChecked(false);
        form.addView(pub, Widgets.MW);
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.pl_create)
                .setView(form)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.create, (d, w) -> {
                    String name = nameIn.getText().toString().trim();
                    if (name.isEmpty()) return;
                    String desc = descIn.getText().toString().trim();
                    Spotify.createPlaylist(name, desc, pub.isChecked(), new Spotify.Cb() {
                        @Override public void ok(JSONObject o) {
                            String newId = o == null ? "" : o.optString("id", "");
                            if (pickedCover[0] != null && !newId.isEmpty()) {
                                Spotify.uploadPlaylistImage(MainActivity.this, newId, pickedCover[0], new Spotify.Cb() {
                                    @Override public void ok(JSONObject r) {
                                        Player.toast(R.string.pl_created);
                                        if (onCreated != null) onCreated.run();
                                    }
                                    @Override public void err(int c, String m) {
                                        Player.toast(c == 401 || c == 403 ? R.string.pl_cover_relogin : R.string.pl_cover_failed);
                                        if (onCreated != null) onCreated.run();
                                    }
                                });
                            } else {
                                Player.toast(R.string.pl_created);
                                if (onCreated != null) onCreated.run();
                            }
                        }
                        @Override public void err(int c, String m) {
                            Player.toast(c == 403 ? R.string.pl_relogin_scope : R.string.action_failed);
                        }
                    });
                }).show();
    }

    /** Edits an existing Spotify playlist's name, description, and optional custom cover image. */
    void editPlaylistDialog(String playlistId, String curName, String curDesc, String curImage, java.util.function.BiConsumer<String, String> onUpdated) {
        if (!Spotify.loggedIn()) { Player.toast(R.string.login_needed); return; }
        final Uri[] pickedCover = {null};
        android.widget.LinearLayout form = new android.widget.LinearLayout(this);
        form.setOrientation(android.widget.LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(22), Ui.dp(12), Ui.dp(22), Ui.dp(4));
        form.addView(playlistCoverRow(curImage, pickedCover), Widgets.MW);
        android.widget.EditText nameIn = new android.widget.EditText(this);
        nameIn.setHint(R.string.pl_name_hint);
        nameIn.setSingleLine(true);
        if (curName != null) nameIn.setText(curName);
        form.addView(nameIn, Widgets.MW);
        android.widget.EditText descIn = new android.widget.EditText(this);
        descIn.setHint(R.string.pl_desc_hint);
        if (curDesc != null) descIn.setText(curDesc);
        form.addView(descIn, Widgets.MW);
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.pl_edit)
                .setView(form)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String name = nameIn.getText().toString().trim();
                    if (name.isEmpty()) return;
                    String desc = descIn.getText().toString().trim();
                    Spotify.editPlaylist(playlistId, name, desc, new Spotify.Cb() {
                        @Override public void ok(JSONObject o) {
                            if (pickedCover[0] != null) {
                                Spotify.uploadPlaylistImage(MainActivity.this, playlistId, pickedCover[0], new Spotify.Cb() {
                                    @Override public void ok(JSONObject r) {
                                        Player.toast(R.string.pl_updated);
                                        if (onUpdated != null) onUpdated.accept(name, desc);
                                    }
                                    @Override public void err(int c, String m) {
                                        Player.toast(c == 401 || c == 403 ? R.string.pl_cover_relogin : R.string.pl_cover_failed);
                                        if (onUpdated != null) onUpdated.accept(name, desc);
                                    }
                                });
                            } else {
                                Player.toast(R.string.pl_updated);
                                if (onUpdated != null) onUpdated.accept(name, desc);
                            }
                        }
                        @Override public void err(int c, String m) {
                            Player.toast(c == 403 ? R.string.pl_relogin_scope : R.string.action_failed);
                        }
                    });
                }).show();
    }

    /** Shows the user's Spotify playlists to add {@code trackUri} (with a shortcut to create a new playlist). */
    void addToPlaylistDialog(String trackUri) {
        if (!Spotify.loggedIn() || trackUri == null || trackUri.isEmpty()) { Player.toast(R.string.login_needed); return; }
        Spotify.IO.execute(() -> {
            JSONObject res = null;
            try { res = Spotify.call("GET", "/me/playlists?limit=50", null); }
            catch (Exception e) {
                try { res = Spotify.callCached("/me/playlists?limit=50"); } catch (Exception ignored) {}
            }
            final JSONObject o = res;
            Spotify.MAIN.post(() -> {
                if (o == null) { Player.toast(R.string.load_failed); return; }
                org.json.JSONArray arr = o.optJSONArray("items");
                List<String> names = new ArrayList<>();
                List<String> ids = new ArrayList<>();
                names.add("+ " + getString(R.string.pl_create));
                ids.add(null);
                String myUid = Spotify.userId();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject pl = arr.optJSONObject(i);
                        if (pl == null) continue;
                        JSONObject owner = pl.optJSONObject("owner");
                        String oid = owner == null ? "" : owner.optString("id", "");
                        boolean collab = pl.optBoolean("collaborative", false);
                        if (!myUid.isEmpty() && !oid.isEmpty() && !oid.equals(myUid) && !collab) continue;
                        names.add(pl.optString("name"));
                        ids.add(pl.optString("id"));
                    }
                }
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.pl_add_track)
                        .setItems(names.toArray(new String[0]), (d, which) -> {
                            String pid = ids.get(which);
                            if (pid == null) {
                                createPlaylistDialog(() -> addToPlaylistDialog(trackUri));
                                return;
                            }
                            List<String> uris = new ArrayList<>();
                            uris.add(trackUri);
                            Spotify.addTracksToPlaylist(pid, uris, new Spotify.Cb() {
                                @Override public void ok(JSONObject r) { Player.toast(R.string.pl_added); }
                                @Override public void err(int c, String m) {
                                    Player.toast(c == 401 || c == 403 ? R.string.pl_relogin_scope : R.string.action_failed);
                                }
                            });
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            });
        });
    }
}
