package com.oneuibook.prism;

import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A scrolling screen with an optional large title that collapses into the fixed header. */
abstract class Page extends FrameLayout {
    final MainActivity main;
    final Scroll scroll;
    final LinearLayout content;
    TextView bigTitle;
    String title;
    int[] headerIcons = {Icons.QUEUE, Icons.PROFILE};
    boolean back;
    boolean grouped;
    private boolean started;

    final class Scroll extends ScrollView {
        Scroll() {
            super(Page.this.getContext());
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
            setClipToPadding(false);
        }

        @Override protected void onScrollChanged(int l, int t, int ol, int ot) {
            super.onScrollChanged(l, t, ol, ot);
            main.onPageScroll(Page.this, t, t - ot);
            Page.this.onScrolled(t);
        }
    }

    Page(MainActivity m, String title, boolean largeTitle) {
        super(m);
        main = m;
        this.title = title;
        scroll = new Scroll();
        content = new LinearLayout(m);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyInsets();
        if (largeTitle) addBigTitle();
    }

    void applyInsets() {
        int top = Ui.statusInset + Ui.dp(largeTop() ? 64 : 60);
        content.setPadding(0, top, main.sideInset(), main.bottomPadding());
    }

    boolean largeTop() { return true; }

    void addBigTitle() {
        bigTitle = Ui.text(getContext(), title, 36, Ui.bold, Ui.text());
        bigTitle.setLetterSpacing(-0.01f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(50));
        // short 16:10 cover screens: tighter title spacing so more content fits
        lp.setMargins(Ui.dp(18), Ui.dp(Ui.cover() ? 4 : 14), Ui.dp(16), Ui.dp(Ui.cover() ? 0 : 6));
        content.addView(bigTitle, lp);
    }

    void setGrouped(boolean g) {
        grouped = g;
        setBackgroundColor(g ? Ui.groupedBg() : Ui.bg());
    }

    /** Called when the page becomes visible; builds content once. */
    void show() {
        if (!started) {
            started = true;
            if (getBackground() == null) setBackgroundColor(grouped ? Ui.groupedBg() : Ui.bg());
            build();
        }
    }

    void rebuild() {
        started = false;
        content.removeAllViews();
        if (bigTitle != null) addBigTitle();
        show();
    }

    abstract void build();

    void onScrolled(int y) {}

    /** 0 → large title fully visible, 1 → small header title shown. */
    float titleProgress() {
        int y = scroll.getScrollY();
        if (bigTitle == null) return Ui.clamp((y - Ui.dp(140)) / (float) Ui.dp(24), 0f, 1f);
        float start = bigTitle.getTop() - Ui.statusInset - Ui.dp(40);
        return Ui.clamp((y - start) / (float) Ui.dp(18), 0f, 1f);
    }

    float blurProgress() { return Ui.clamp(scroll.getScrollY() / (float) Ui.dp(20), 0f, 1f); }

    void scrollToTop() { scroll.smoothScrollTo(0, 0); }

    /** Adds a lazily-filled block so async sections keep their order. */
    LinearLayout slot() {
        LinearLayout s = new LinearLayout(getContext());
        s.setOrientation(LinearLayout.VERTICAL);
        s.setLayoutTransition(Ui.appear());
        content.addView(s, Widgets.MW);
        return s;
    }
}
