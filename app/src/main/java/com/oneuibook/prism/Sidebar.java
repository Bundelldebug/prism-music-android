package com.oneuibook.prism;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Tablet sidebar: app name + close button, a search field, the four tabs as a list
 * with the current one highlighted, and a collapsible "Library" section that jumps straight into the library's lists.
 * While it is open the content moves to its right and the floating tab pill at the top steps aside.
 */
final class Sidebar extends FrameLayout {
    private final MainActivity m;
    private final List<LinearLayout> tabRows = new ArrayList<>();
    private final LinearLayout libBox;
    private final Icons.IconView libChevron;
    private boolean libOpen = true;

    static float widthFor(int W) { return Ui.clamp(W * 0.23f, Ui.dpf(232), Ui.dpf(276)); }

    Sidebar(MainActivity m) {
        super(m);
        this.m = m;
        setBackgroundColor(Ui.gStyle ? Ui.groupedBg() : (Ui.dark ? 0xFF151517 : 0xFFF3F3F7));
        setClickable(true);   // touches never fall through to the page beneath

        ScrollView sv = new ScrollView(m);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(OVER_SCROLL_NEVER);
        addView(sv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Desktop-style hairline right divider separating sidebar from the main content stage
        View divider = new View(m);
        divider.setBackgroundColor(Ui.separator());
        addView(divider, new LayoutParams(Math.max(1, Ui.dp(1)), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));

        LinearLayout col = new LinearLayout(m);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(11), Ui.statusInset + Ui.dp(12), Ui.dp(11), m.bottomPadding());
        sv.addView(col, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // title row: app name + compact desktop sidebar toggle button
        LinearLayout top = new LinearLayout(m);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(8), 0, Ui.dp(2), Ui.dp(12));
        top.addView(Ui.text(m, m.getString(R.string.app_name), 19, Ui.bold, Ui.text()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Icons.IconView close = new Icons.IconView(m, Icons.SIDEBAR, 19, Ui.text());
        android.graphics.drawable.GradientDrawable cb = Ui.round(Ui.card(), 100);
        cb.setStroke(Math.max(1, Ui.dp(0.8f)), Ui.dark ? 0x2BFFFFFF : 0x1A000000);
        close.setBackground(cb);
        close.setElevation(Ui.dpf(1.5f));
        close.setOnClickListener(v -> m.closeSidebar());
        Ui.press(close, 0.9f);
        top.addView(close, new LinearLayout.LayoutParams(Ui.dp(38), Ui.dp(38)));
        col.addView(top, Widgets.MW);

        // search field: compact PC-style search pill
        LinearLayout search = new LinearLayout(m);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        android.graphics.drawable.GradientDrawable sbg = Ui.round(Ui.dark ? 0xFF262629 : 0xFFE6E6EC, 100);
        sbg.setStroke(Math.max(1, Ui.dp(0.6f)), Ui.dark ? 0x1EFFFFFF : 0x10000000);
        search.setBackground(sbg);
        search.addView(new Icons.IconView(m, Icons.SEARCH, 16, Ui.text2()), new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)));
        TextView hint = Ui.text(m, m.getString(R.string.search), 14.5f, Ui.regular, Ui.text2());
        hint.setPadding(Ui.dp(8), 0, 0, 0);
        search.addView(hint);
        search.setOnClickListener(v -> m.sidebarSearch());
        Ui.press(search, 0.97f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(38));
        slp.bottomMargin = Ui.dp(14);
        col.addView(search, slp);

        // the four tabs
        int[] titles = {R.string.tab_home, R.string.tab_explore, R.string.tab_library, R.string.tab_settings};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout r = row(Dock.TAB_ICONS[i], m.getString(titles[i]), v -> m.sidebarTab(idx));
            tabRows.add(r);
            col.addView(r, rowLp());
        }

        // "Library" section with a chevron that folds it
        LinearLayout head = new LinearLayout(m);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(10), Ui.dp(22), Ui.dp(10), Ui.dp(6));
        head.addView(Ui.text(m, m.getString(R.string.tab_library), 13, Ui.medium, Ui.text2()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        libChevron = new Icons.IconView(m, Icons.CHEVRON, 14, Ui.text2());
        libChevron.setRotation(90);
        head.addView(libChevron, new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)));
        head.setOnClickListener(v -> toggleLibrary());
        col.addView(head, Widgets.MW);
        libBox = new LinearLayout(m);
        libBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(libBox, Widgets.MW);
        buildLibrary();
    }

    /** Library shortcuts follow the music source (Spotify lists or on-device songs). */
    void buildLibrary() {
        libBox.removeAllViews();
        if (m.isLocalSource()) {
            libBox.addView(row(Icons.RECAP, m.getString(R.string.recap_local), v -> m.push(new Pages.RecapPage(m, true))), rowLp());
            libBox.addView(row(Icons.NOTE, m.getString(R.string.all_songs), v -> m.openLibrarySection("all")), rowLp());
            libBox.addView(row(Icons.ALBUM, m.getString(R.string.lib_albums), v -> m.openLibrarySection("lalbums")), rowLp());
            libBox.addView(row(Icons.ARTIST, m.getString(R.string.lib_artists), v -> m.openLibrarySection("lartists")), rowLp());
            return;
        }
        libBox.addView(row(Icons.RECAP, m.getString(R.string.recap_spotify), v -> m.push(new Pages.RecapPage(m, false))), rowLp());
        libBox.addView(row(Icons.HEART, m.getString(R.string.lib_liked), v -> m.openLibrarySection("liked")), rowLp());
        libBox.addView(row(Icons.QUEUE, m.getString(R.string.lib_playlists), v -> m.openLibrarySection("playlists")), rowLp());
        libBox.addView(row(Icons.ALBUM, m.getString(R.string.lib_albums), v -> m.openLibrarySection("albums")), rowLp());
        libBox.addView(row(Icons.ARTIST, m.getString(R.string.lib_artists), v -> m.openLibrarySection("artists")), rowLp());
        libBox.addView(row(Icons.CLOCK, m.getString(R.string.lib_recent), v -> m.openLibrarySection("recent")), rowLp());
    }

    private void toggleLibrary() {
        libOpen = !libOpen;
        libChevron.animate().rotation(libOpen ? 90 : 0).setDuration(240).setInterpolator(Ui.EMPHASIZED).start();
        if (libOpen) {
            libBox.setVisibility(VISIBLE);
            libBox.setAlpha(0f);
            libBox.setTranslationY(-Ui.dpf(8));
            libBox.animate().alpha(1f).translationY(0).setDuration(260).setInterpolator(Ui.EMPHASIZED).withEndAction(null).start();
        } else {
            libBox.animate().alpha(0f).translationY(-Ui.dpf(8)).setDuration(180).setInterpolator(Ui.EMPHASIZED)
                    .withEndAction(() -> libBox.setVisibility(GONE)).start();
        }
    }

    private static LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(40));
        lp.bottomMargin = Ui.dp(2);
        return lp;
    }

    private LinearLayout row(int icon, String title, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(getContext());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(11), 0, Ui.dp(11), 0);
        r.addView(new Icons.IconView(getContext(), icon, 19, Ui.ACCENT), new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
        TextView t = Ui.text(getContext(), title, 15, Ui.regular, Ui.text());
        t.setPadding(Ui.dp(12), 0, 0, 0);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.setOnClickListener(click);
        r.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Ui.pressed()), null, Ui.round(0xFFFFFFFF, 100)));
        return r;
    }

    /** Highlights the current tab with a capsule (accent-tinted in G style, grey in A style). */
    void setSelected(int tab) {
        for (int i = 0; i < tabRows.size(); i++) {
            LinearLayout r = tabRows.get(i);
            boolean on = i == tab;
            int selCol = Ui.gStyle ? Ui.alpha(Ui.ACCENT, Ui.dark ? 0.22f : 0.13f) : (Ui.dark ? 0x33FFFFFF : 0x17000000);
            android.graphics.drawable.Drawable base = on ? Ui.round(selCol, 100) : null;
            r.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Ui.pressed()), base, Ui.round(0xFFFFFFFF, 100)));
            TextView t = (TextView) r.getChildAt(1);
            t.setTypeface(on ? Ui.semibold : Ui.regular);
            t.setTextColor(on && Ui.gStyle ? Ui.ACCENT : Ui.text());
        }
    }
}
