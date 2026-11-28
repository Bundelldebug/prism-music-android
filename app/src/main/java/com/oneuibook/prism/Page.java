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
    Runnable onPullRefresh;
    private TextView pullBadge;

    final class Scroll extends ScrollView {
        private float pullStartY = -1f;
        private float pullDist = 0f;
        private boolean pulling = false;

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

        @Override public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
            if (onPullRefresh != null) {
                int act = ev.getActionMasked();
                if (act == android.view.MotionEvent.ACTION_DOWN) {
                    pullStartY = getScrollY() <= 2 ? ev.getRawY() : -1f;
                    pulling = false;
                    pullDist = 0f;
                } else if (act == android.view.MotionEvent.ACTION_MOVE) {
                    if (getScrollY() <= 2) {
                        if (pullStartY < 0f) pullStartY = ev.getRawY();
                        float dy = ev.getRawY() - pullStartY;
                        if (dy > Ui.dpf(14)) {
                            pulling = true;
                            pullDist = Math.min(Ui.dpf(104), (dy - Ui.dpf(14)) * 0.55f);
                            updatePullVisual(pullDist);
                        } else if (pulling) {
                            pullDist = 0f;
                            updatePullVisual(0f);
                        }
                    } else if (pulling) {
                        pulling = false;
                        pullDist = 0f;
                        updatePullVisual(0f);
                    }
                } else if (act == android.view.MotionEvent.ACTION_UP || act == android.view.MotionEvent.ACTION_CANCEL) {
                    if (pulling) {
                        boolean trigger = act == android.view.MotionEvent.ACTION_UP && pullDist >= Ui.dpf(56);
                        pulling = false;
                        pullStartY = -1f;
                        if (trigger) {
                            showRefreshingVisual();
                            onPullRefresh.run();
                        } else {
                            resetPullVisual();
                        }
                    }
                }
            }
            return super.dispatchTouchEvent(ev);
        }
    }

    private void ensurePullBadge() {
        if (pullBadge != null) return;
        pullBadge = Ui.text(getContext(), "당겨서 새로고침", 12.5f, Ui.semibold, Ui.ACCENT);
        pullBadge.setGravity(android.view.Gravity.CENTER);
        pullBadge.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(8));
        android.graphics.drawable.GradientDrawable bg = Ui.round(Ui.card(), 100);
        bg.setStroke(Math.max(1, Ui.dp(0.8f)), Ui.alpha(Ui.ACCENT, 0.35f));
        pullBadge.setBackground(bg);
        pullBadge.setElevation(Ui.dpf(4));
        pullBadge.setAlpha(0f);
        pullBadge.setVisibility(GONE);
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
        lp.topMargin = Ui.statusInset + Ui.dp(56);
        addView(pullBadge, lp);
    }

    private void updatePullVisual(float dist) {
        ensurePullBadge();
        content.setTranslationY(dist * 0.65f);
        float frac = Ui.clamp(dist / Ui.dpf(56), 0f, 1f);
        pullBadge.setVisibility(frac > 0.05f ? VISIBLE : GONE);
        pullBadge.setAlpha(frac);
        pullBadge.setTranslationY(dist * 0.45f - Ui.dpf(12) * (1f - frac));
        pullBadge.setText(frac >= 1f ? "놓으면 새로고침" : "당겨서 새로고침");
    }

    private void showRefreshingVisual() {
        ensurePullBadge();
        pullBadge.setVisibility(VISIBLE);
        pullBadge.setAlpha(1f);
        pullBadge.setText("새로고침 중...");
        content.animate().translationY(Ui.dpf(36)).setDuration(180).setInterpolator(Ui.EMPHASIZED).start();
        pullBadge.postDelayed(this::resetPullVisual, 550);
    }

    private void resetPullVisual() {
        content.animate().translationY(0f).setDuration(240).setInterpolator(Ui.EMPHASIZED).start();
        if (pullBadge != null) {
            pullBadge.animate().alpha(0f).translationY(-Ui.dpf(10)).setDuration(200).setInterpolator(Ui.EMPHASIZED)
                    .withEndAction(() -> pullBadge.setVisibility(GONE)).start();
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

    /** Root pages on a wide foldable's unfolded screen: small title at the top instead of the large one. */
    boolean compactRoot() { return !back && Dock.wideRailFor(Ui.screenW, Ui.screenH); }

    void addBigTitle() {
        // G style sub-pages (except long-title detail lists like LocalListPage) show their title cleanly in the top bar
        boolean gRoot = Ui.gStyle && !back;
        // wide foldable unfolded (Apple Music-like): root pages keep their title small at the top
        boolean gSubClean = (Ui.gStyle && back && !(this instanceof Pages.LocalListPage)) || compactRoot();
        bigTitle = Ui.text(getContext(), title, gRoot ? 34 : 36, Ui.bold, Ui.text());
        bigTitle.setLetterSpacing(Ui.gStyle ? -0.02f : -0.01f);
        if (gSubClean) {
            // keep bigTitle non-null for scroll helpers, collapsed invisibly so content starts right under "< Title"
            bigTitle.setVisibility(GONE);
            content.addView(bigTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
            return;
        }
        // long names (album titles…) shrink and wrap instead of being cut off: up to 3 lines, 36sp down to 24sp
        int avail = Ui.screenW - main.sideInset() - main.contentInsetPx() - Ui.dp(Ui.gStyle ? 44 : 34);
        float size = Math.min(gRoot ? 34f : 36f, fitTitleSize(title, avail));
        bigTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size);
        bigTitle.setSingleLine(false);
        bigTitle.setMaxLines(3);
        bigTitle.setLineSpacing(0, 1.05f);
        if (gRoot) {
            boolean wideScreen = main.sideInset() > 0 || main.tabletUi();
            // One UI 8.5/9.x Expanding App Bar: comfortable breathing room in G style
            bigTitle.setGravity(wideScreen ? android.view.Gravity.START : android.view.Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int topM = Ui.dp(wideScreen ? 14 : (Ui.cover() ? 20 : 34));
            int botM = Ui.dp(wideScreen ? 10 : (Ui.cover() ? 14 : 20));
            lp.setMargins(Ui.dp(24), topM, Ui.dp(24), botM);
            content.addView(bigTitle, lp);
            return;
        }
        boolean oneLine = lines(title, avail, size) <= 1;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                oneLine ? Ui.dp(50) : ViewGroup.LayoutParams.WRAP_CONTENT);
        // short 16:10 cover screens: tighter title spacing so more content fits
        lp.setMargins(Ui.dp(Ui.gStyle ? 24 : 18), Ui.dp(Ui.cover() ? 4 : 14), Ui.dp(Ui.gStyle ? 20 : 16), Ui.dp(Ui.cover() ? 0 : 6));
        content.addView(bigTitle, lp);
    }

    /** How many lines {@code s} takes at {@code sp} in {@code width} px with the big-title font. */
    private static int lines(String s, int width, float sp) {
        if (s == null || s.isEmpty() || width <= 0) return 1;
        android.text.TextPaint p = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Ui.bold);
        p.setTextSize(Ui.sp(sp));
        p.setLetterSpacing(-0.01f);
        return android.text.StaticLayout.Builder.obtain(s, 0, s.length(), p, width).build().getLineCount();
    }

    /** Largest size (36 → 24sp) at which the title fits in one line, else in two, else three lines at 24sp. */
    static float fitTitleSize(String s, int width) {
        for (int maxLines = 1; maxLines <= 2; maxLines++)
            for (float sp = 36f; sp >= 26f; sp -= 2f) if (lines(s, width, sp) <= maxLines) return sp;
        return 24f;
    }

    void setGrouped(boolean g) {
        grouped = g;
        setBackgroundColor(g ? Ui.groupedBg() : Ui.bg());
    }

    /** Called when the page becomes visible; builds content once. */
    void show() {
        if (!started) {
            started = true;
            if (Ui.gStyle && back && !(this instanceof Pages.LocalListPage) && bigTitle != null && bigTitle.getVisibility() == VISIBLE) {
                bigTitle.setVisibility(GONE);
                bigTitle.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
            }
            if (getBackground() == null) setBackgroundColor(grouped ? Ui.groupedBg() : Ui.bg());
            build();
        }
    }

    /** Where {@link #slot()} puts new blocks (a side column in two-pane layouts); null = the page itself. */
    LinearLayout slotHost;

    void rebuild() {
        started = false;
        slotHost = null;
        content.removeAllViews();
        if (bigTitle != null) addBigTitle();
        show();
    }

    abstract void build();

    void onScrolled(int y) {}

    /** 0 → large title fully visible, 1 → small header title shown. */
    float titleProgress() {
        int y = scroll.getScrollY();
        if (Ui.gStyle && back && !(this instanceof Pages.LocalListPage)) return 1f;
        if (compactRoot()) return 1f;
        if (bigTitle == null) return Ui.clamp((y - Ui.dp(140)) / (float) Ui.dp(24), 0f, 1f);
        float start = bigTitle.getTop() - Ui.statusInset - Ui.dp(Ui.gStyle ? 24 : 40);
        return Ui.clamp((y - start) / (float) Ui.dp(22), 0f, 1f);
    }

    float blurProgress() { return Ui.clamp(scroll.getScrollY() / (float) Ui.dp(20), 0f, 1f); }

    void scrollToTop() { scroll.smoothScrollTo(0, 0); }

    /** Adds a lazily-filled block so async sections keep their order. */
    LinearLayout slot() {
        LinearLayout s = new LinearLayout(getContext());
        s.setOrientation(LinearLayout.VERTICAL);
        (slotHost != null ? slotHost : content).addView(s,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }
}
