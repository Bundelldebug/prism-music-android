package com.oneuibook.prism;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.text.Editable;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

/**
 * Floating bottom chrome: glass tab pill with a draggable refracting lens, the detached search
 * bubble (metaball-joined while the lens is lifted), and the glass mini player. Scrolling down minimises
 * everything into one row; search mode morphs the search bubble into a field.
 */
final class Dock extends FrameLayout {
    interface Listener {
        void onTab(int index, boolean reselect);
        void onMini();
        void onSearch();
        void onExitSearch();
        void onQuery(String q, boolean submit);
        /** Tablet: the sidebar button at the start of the tab pill. */
        default void onSidebar() {}
        /** Tablet music bar's right-hand buttons: {@link #MINI_MORE}, {@link #MINI_LYRICS}, {@link #MINI_QUEUE}. */
        default void onMiniAction(int which, RectF anchor) {}
    }

    static final int MINI_MORE = 0, MINI_LYRICS = 1, MINI_QUEUE = 2;

    // ---- tablet sidebar ----
    /** Width the open sidebar takes from the left, and its 0..1 progress (content area = [sideW·sideP, W]). */
    private float sideW, sideP;

    void setSidebar(float widthPx, float progress) {
        sideW = widthPx;
        sideP = progress;
        invalidate();
    }

    private float contentLeft() { return tablet ? sideW * sideP : 0f; }
    /** Tablet tab pill: room for the sidebar button before the four tabs (folds away in search mode). */
    private float lead() { return tablet ? Ui.dpf(46) * (1f - search) : 0f; }

    static final int[] TAB_ICONS = {Icons.HOME, Icons.EXPLORE, Icons.LIBRARY, Icons.SETTINGS};

    private final Glass.Backdrop backdrop;
    private Listener listener;
    private final String[] labels;
    int selected;

    // geometry state
    float collapse, search;          // 0..1 animated
    private ValueAnimator collapseAnim, searchAnim;
    private int imeOffset;
    final RectF pill = new RectF(), circle = new RectF(), lens = new RectF(), mini = new RectF();
    private final RectF tmp = new RectF();

    private final Glass.Surface barGlass = new Glass.Surface();
    private final Glass.Surface lensGlass = new Glass.Surface();
    private final Glass.Surface miniGlass = new Glass.Surface();
    private final RenderNode contentNode = new RenderNode("dock-content");

    private final Ui.Spring lensX = new Ui.Spring(420f, 0.72f, 0.4f);
    private final Ui.Spring lift = new Ui.Spring(560f, 0.78f, 0.002f);
    private final Ui.Spring miniPress = new Ui.Spring(700f, 0.9f, 0.002f);
    private final Ui.Spring searchPress = new Ui.Spring(700f, 0.9f, 0.002f);
    private final Ui.Spring barPress = new Ui.Spring(520f, 0.85f, 0.002f);
    private float touchX, touchY;
    private VelocityTracker vt;
    private float lensVel;

    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint sub = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix artM = new Matrix();
    private Bitmap artBmp;
    private float artPop = 1f;
    private String artUrl;

    final EditText field;
    private boolean miniVisible;
    private float miniShow;          // animated 0..1
    private ValueAnimator miniShowAnim;

    // touch
    private static final int T_NONE = 0, T_TAB = 1, T_SEARCH = 2, T_MINI = 3, T_MINI_PLAY = 4, T_MINI_NEXT = 5, T_HOME_CIRCLE = 6,
            T_MINI_PREV = 7, T_MINI_SHUF = 8, T_MINI_REP = 9, T_SIDE = 10, T_MINI_MORE = 11, T_MINI_LYR = 12, T_MINI_QUEUE = 13;
    private int touch = T_NONE;
    private float downX, downY;
    private boolean dragging;

    Dock(Context c, Glass.Backdrop backdrop, String[] labels) {
        super(c);
        this.backdrop = backdrop;
        this.labels = labels;
        setWillNotDraw(false);
        setClipChildren(false);
        label.setTypeface(Ui.semibold);
        label.setTextSize(Ui.sp(10.5f));
        label.setTextAlign(Paint.Align.CENTER);
        title.setTypeface(Ui.semibold);
        title.setTextSize(Ui.sp(14f));
        sub.setTypeface(Ui.regular);
        sub.setTextSize(Ui.sp(12f));

        // the tab pill is mostly white glass with only a light frost, so text behind stays legible
        barGlass.blurDp = 2.5f;
        barGlass.refractDp = 17f;
        barGlass.edgeDp = 26f;
        barGlass.outerDp = 7f;
        barGlass.outerRefractDp = 12f;
        barGlass.chroma = 0f;  // dispersion only on the lens: 5 extra samples per pixel across the whole bar ran hot
        barGlass.vibrance = 1.35f;
        barGlass.rim = 0.32f;
        // the music bar is far clearer and lets the colours behind come through vividly
        miniGlass.blurDp = 2f;
        miniGlass.refractDp = 17f;
        miniGlass.edgeDp = 24f;
        miniGlass.outerDp = 7f;
        miniGlass.outerRefractDp = 12f;
        miniGlass.chroma = 0f;
        miniGlass.vibrance = 1.7f;
        miniGlass.rim = 0.32f;
        lensGlass.blur = false;
        lensGlass.refractDp = 22f;
        lensGlass.edgeDp = 28f;
        lensGlass.outerDp = 8f;
        lensGlass.outerRefractDp = 14f;
        lensGlass.chroma = 0.9f;
        lensGlass.rim = 0.6f;
        lensGlass.tint = Ui.dark ? 0x0FFFFFFF : 0x0AFFFFFF;
        lensGlass.shadowAlpha = 0.16f;
        lensGlass.strokeColor = Ui.dark ? 0x40FFFFFF : 0x1A000000;

        Runnable inv = this::invalidate;
        lensX.onUpdate = inv;
        lift.onUpdate = inv;
        miniPress.onUpdate = inv;
        searchPress.onUpdate = inv;
        barPress.onUpdate = inv;

        field = new EditText(c);
        field.setBackground(null);
        field.setSingleLine(true);
        field.setHint(R.string.search_hint);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        field.setTextColor(Ui.text());
        field.setHintTextColor(Ui.text2());
        field.setTypeface(Ui.regular);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(0, 0, 0, 0);
        field.setVisibility(GONE);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void afterTextChanged(Editable e) { if (listener != null) listener.onQuery(e.toString(), false); }
        });
        field.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                if (listener != null) listener.onQuery(field.getText().toString(), true);
                hideIme();
                return true;
            }
            return false;
        });
        addView(field, new LayoutParams(1, 1));
        Player.listeners.add(playerListener);
        onPlayer();
    }

    private final Runnable playerListener = this::onPlayer;

    void setListener(Listener l) { listener = l; }

    void select(int i, boolean animate) {
        selected = i;
        layoutGeometry();
        float cx = slotCenter(i);
        if (animate) lensX.to(cx); else lensX.snap(cx);
        invalidate();
    }

    void setImeOffset(int px) {
        if (px == imeOffset) return;
        imeOffset = px;
        invalidate();
    }

    // ---- state animations ----
    private float collapseTarget;

    /** True while the dock's own animations run (redraw needed even when the page is idle). */
    boolean animating() {
        return (morphAnim != null && morphAnim.isRunning()) || lensX.running || lift.running || miniPress.running || searchPress.running || barPress.running
                || (collapseAnim != null && collapseAnim.isRunning()) || (searchAnim != null && searchAnim.isRunning())
                || (miniShowAnim != null && miniShowAnim.isRunning()) || artPop < 1f;
    }

    private boolean unifiedG() { return Ui.gStyle && !rail && !tablet; }
    private int slotCount() { return unifiedG() ? 5 : 4; }

    void setCollapsed(boolean c) {
        // G style keeps its unified floating bottom bar clean and steady like One UI 8.5/9.x
        float target = (c && miniVisible && !rail && !tablet && !unifiedG() && !Settings.pref(getContext(), "keep_expanded", false)) ? 1f : 0f;
        // slow scrolls call this on every scroll event: never restart an animation already heading there
        if (target == collapseTarget && (collapseAnim != null && collapseAnim.isRunning() || Math.abs(collapse - target) < 0.001f)) return;
        collapseTarget = target;
        if (collapseAnim != null) collapseAnim.cancel();
        long ms = (long) (460 * Math.abs(collapse - target));
        collapseAnim = Ui.animate(collapse, target, Math.max(120, ms), new android.view.animation.LinearInterpolator(), a -> { collapse = (float) a.getAnimatedValue(); invalidate(); });
    }

    boolean isCollapsed() { return collapse > 0.5f; }

    void setSearchMode(boolean on) {
        if (searchAnim != null) searchAnim.cancel();
        if (on) {
            field.setVisibility(VISIBLE);
            field.requestFocus();
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            postDelayed(() -> imm.showSoftInput(field, 0), 120);
        } else {
            hideIme();
            field.setText("");
        }
        searchAnim = Ui.animate(search, on ? 1f : 0f, 480, Ui.EMPHASIZED, a -> {
            search = (float) a.getAnimatedValue();
            // only hide the field once the closing morph has finished (not on the opening's first frame)
            if (!on && search <= 0.001f) field.setVisibility(GONE);
            invalidate();
        });
    }

    boolean inSearch() { return search > 0.5f; }

    void hideIme() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
        field.clearFocus();
    }

    private void onPlayer() {
        boolean vis = Player.track != null;
        if (vis != miniVisible) {
            miniVisible = vis;
            if (miniShowAnim != null) miniShowAnim.cancel();
            miniShowAnim = Ui.animate(miniShow, vis ? 1f : 0f, 420, Ui.EMPHASIZED, a -> { miniShow = (float) a.getAnimatedValue(); invalidate(); });
            if (!vis) setCollapsed(false);
        }
        String u = Player.track == null ? null : Player.track.artSmall;
        if (u != null && !u.equals(artUrl)) {
            artUrl = u;
            Img.load(u, b -> {
                if (!u.equals(artUrl)) return;
                if (artBmp != null && b != null) {
                    // brief scale-through so the mini cover visibly changes with the song
                    Ui.animate(0f, 1f, 360, Ui.EMPHASIZED, an -> { artPop = (float) an.getAnimatedValue(); invalidate(); });
                }
                artBmp = b;
                if (b != null) art.setShader(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                invalidate();
            });
        }
        invalidate();
    }

    /** Height of the chrome above the bottom edge, for page bottom padding. */
    int chromeHeight() { return Ui.navInset + (int) raise() + Ui.dp(64 + 7 + 50 + 16); }

    // ---- geometry (proportions tuned by hand) ----
    /** Foldable inner screens: tabs + search stand vertically on the right; the music bar stays at the bottom. */
    boolean rail;
    /** Tablet text tab pill at the top centre, wide player bar at the bottom centre. */
    boolean tablet;

    static boolean tabletFor(int wPx, int hPx) { return !railFor(wPx, hPx) && wPx / Ui.density >= 600 && hPx / Ui.density >= 480; }

    static boolean railFor(int wPx, int hPx) {
        float wd = wPx / Ui.density, hd = hPx / Ui.density;
        float aspect = Math.max(wd, hd) / Math.max(1f, Math.min(wd, hd));
        return !Ui.unfoldTablet && wd >= 600 && aspect < 1.35f;
    }

    private static float ease(float t) { return Ui.EMPHASIZED.getInterpolation(Ui.clamp(t, 0f, 1f)); }
    /** Collapse is staggered: the tab pill folds first, the music bar follows. */
    private float pillK() { return ease(collapse / 0.7f); }
    private float miniK() { return ease((collapse - 0.25f) / 0.75f); }

    private float barH() { return Ui.lerp(Ui.dpf(64), Ui.dpf(48), Math.max(pillK(), search)); }
    /** "Raise bottom floating controls" setting (default 8dp above the navigation area). */
    static final int RAISE_DEFAULT = 18;
    private float raise() { return Ui.dpf(MainActivity.prefs(getContext()).getInt("raise_dp", RAISE_DEFAULT)); }
    private float bottomY() { return getHeight() - Math.max(Ui.navInset + raise(), imeOffset + Ui.dpf(10)); }

    // ---- fold / unfold: the new screen's chrome grows out of where it was on the previous screen ----
    private float[] morphFrom;
    private float morph = 1f;
    private ValueAnimator morphAnim;

    /** Geometry in screen fractions, saved across the activity re-creation that a fold/unfold causes. */
    float[] snapshot() {
        float W = Math.max(1, getWidth()), H = Math.max(1, getHeight());
        return new float[]{W, H, pill.left / W, pill.top / H, pill.right / W, pill.bottom / H,
                circle.left / W, circle.top / H, circle.right / W, circle.bottom / H,
                mini.left / W, mini.top / H, mini.right / W, mini.bottom / H};
    }

    /** Starts the bar → rail (or rail → bar, cover ↔ tablet…) morph from a {@link #snapshot()} of the other screen. */
    void morphFrom(float[] g) {
        if (g == null || g.length < 14) return;
        morphFrom = g;
        if (morphAnim != null) morphAnim.cancel();
        morph = 0f;
        morphAnim = Ui.animate(0f, 1f, 620, Ui.EMPHASIZED, a -> { morph = (float) a.getAnimatedValue(); invalidate(); });
        morphAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { morph = 1f; morphFrom = null; layoutGeometry(); lensX.snap(slotCenter(selected)); invalidate(); }
        });
        invalidate();
    }

    private void morphRect(RectF r, int i) {
        float W = getWidth(), H = getHeight();
        float l = morphFrom[i] * W, t = morphFrom[i + 1] * H, rr = morphFrom[i + 2] * W, b = morphFrom[i + 3] * H;
        if (rr - l < 1 || b - t < 1) return;   // wasn't on screen before (e.g. no music bar)
        r.set(Ui.lerp(l, r.left, morph), Ui.lerp(t, r.top, morph), Ui.lerp(rr, r.right, morph), Ui.lerp(b, r.bottom, morph));
    }

    private void layoutGeometry() {
        layoutGeometry0();
        if (morphFrom != null && morph < 1f) {
            morphRect(pill, 2);
            morphRect(circle, 6);
            morphRect(mini, 10);
        }
    }

    private void layoutGeometry0() {
        float W = getWidth(), H = getHeight();
        if (W <= 0) return;
        rail = railFor((int) W, (int) H);
        tablet = tabletFor((int) W, (int) H);
        float gap = Ui.dpf(8);
        if (tablet) {
            // everything centres on the content area, which starts right of the sidebar while it is open
            float cl = contentLeft(), cw = W - cl;
            float h = Ui.dpf(46);
            // keep safe clearance from the top-right header pill on narrower unfolded foldables (e.g. 690dp)
            float maxPw = Math.max(Ui.dpf(220), cw - Ui.dpf(260));
            float pw = Math.min(Math.min(W * 0.42f, Ui.dpf(440)) + lead(), maxPw);
            float left = cl + (cw - pw - gap - h) / 2f, top = Ui.statusInset + Ui.dpf(14);
            float maxRight = W - Ui.dpf(130);
            if (left + pw + gap + h > maxRight) left = Math.max(cl + Ui.dpf(16), maxRight - (pw + gap + h));
            // search: the tab pill and the search bubble melt into one field spanning both
            float fr = left + pw + gap + h;
            pill.set(left, top, Ui.lerp(left + pw, fr, search), top + h);
            circle.set(Ui.lerp(left + pw + gap, left, search), top, fr, top + h);
            float mw = Math.min(cw - Ui.dpf(40), Ui.dpf(760)), bottom = bottomY();
            mini.set(cl + (cw - mw) / 2f, bottom - Ui.dpf(56), cl + (cw + mw) / 2f, bottom);
            return;
        }
        // tablets: keep the bar a comfortable width, centred
        float m = rail ? Ui.dpf(20) : Math.max(Ui.dpf(20), (W - Ui.dpf(560)) / 2f);
        float cp = pillK(), cm = miniK();
        float bottom = bottomY();
        float mh = Ui.dpf(50);
        if (rail) {
            // icon-only vertical tab pill, centred on the screen's height
            float rw = Ui.dpf(60), len = Ui.dpf(SLOT_PAD * 2 + 4 * 54);
            float right = W - Ui.dpf(20), top = (H - len) / 2f;
            pill.set(right - rw, top, right, top + len);
            // search stays at the bottom right, beside the music bar; it widens into the field
            float cs = Ui.dpf(60);   // same 60dp column as the rail and the header pill
            RectF railCircle = tmp;
            railCircle.set(W - m - cs, bottom - cs, W - m, bottom);
            circle.set(Ui.lerp(railCircle.left, m, search), railCircle.top, railCircle.right, railCircle.bottom);
            mini.set(m, bottom - Ui.dpf(56), W - m - cs - gap, bottom - Ui.dpf(2));
            if (search > 0) mini.offset(0, Ui.dpf(30) * search);
            return;
        }
        if (unifiedG()) {
            // One UI 8.5/9.x: one centred floating pill holding tabs + search; search morphs the pill itself into a search bar
            float barW = Math.min(W - Ui.dpf(32), Ui.dpf(420));
            float h = Ui.lerp(Ui.dpf(60), Ui.dpf(54), search);
            float left = (W - barW) / 2f, top = bottom - h;
            pill.set(left, top, left + barW, bottom);
            circle.setEmpty();
            float gmh = Ui.dpf(54);
            float expBottom = bottomY() - Ui.dpf(60) - Ui.dpf(8);
            mini.set(left, expBottom - gmh, left + barW, expBottom);
            if (search > 0) mini.offset(0, Ui.dpf(34) * search);
            return;
        }
        float h = barH();
        bottom += Ui.dpf(4) * cp; // the minimised row sits a little lower
        float top = bottom - h;
        float expandedRight = W - m - Ui.dpf(64) - gap;
        float pillRight = Ui.lerp(expandedRight, m + h, Math.max(cp, search));
        pill.set(m, top, pillRight, bottom);
        float searchLeftNormal = W - m - h;
        float searchLeft = Ui.lerp(searchLeftNormal, m + h + gap, search);
        circle.set(searchLeft, top, W - m, bottom);
        // music bar: slides down-right into the gap between the two circles
        float expBottom = bottomY() - Ui.dpf(64) - Ui.dpf(7);
        RectF e = tmp;
        e.set(m, expBottom - mh, W - m, expBottom);
        float cl = m + h + gap, cr = W - m - h - gap;
        mini.set(Ui.lerp(e.left, cl, cm), Ui.lerp(e.top, top, cm), Ui.lerp(e.right, cr, cm), Ui.lerp(e.bottom, bottom, cm));
        if (search > 0) mini.offset(0, Ui.dpf(30) * search);
    }

    private static final float SLOT_PAD = 14f;
    private float slotPadPx() { return Ui.dpf(unifiedG() ? 5f : SLOT_PAD); }
    private float axisStart() { return rail ? pill.top : pill.left + lead(); }
    private float axisLen() { return rail ? pill.height() : pill.width() - lead(); }
    private float slotW() { return (axisLen() - slotPadPx() * 2f) / (float) slotCount(); }
    private float slotCenter(int i) { return axisStart() + slotPadPx() + slotW() * (i + 0.5f); }
    private float axisOf(float x, float y) { return rail ? y : x; }

    private int slotAt(float a) {
        int i = (int) ((a - axisStart() - slotPadPx()) / slotW());
        return Math.max(0, Math.min(slotCount() - 1, i));
    }

    private void computeLens() {
        float k = rail ? search : Math.max(pillK(), search);
        float sw = slotW();
        float l = lift.value;
        float thick = rail ? pill.width() : pill.height();
        float stretch = Math.min(Math.abs(lensVel) / 5000f, 0.22f) * l;
        // G style unified bar: capsule matches one exact slot width so every tab sits dead-centre with symmetric margins
        float restLen = unifiedG() ? sw : Math.min(sw * 1.32f, sw + Ui.dpf(22));
        float restThick = thick - Ui.dpf(10);
        float len = Ui.lerp(restLen, unifiedG() ? sw + Ui.dpf(6) : Math.min(sw * 1.62f, sw + Ui.dpf(46)), l) * (1f + stretch);
        float th = Ui.lerp(restThick, thick + Ui.dpf(16), l) * (1f - stretch * 0.35f);
        // the selection rides along with the morphing bar
        float a = morph < 1f && lift.value < 0.01f ? slotCenter(selected) : lensX.value;
        if (rail) {
            float cx = pill.centerX();
            lens.set(cx - th / 2f, a - len / 2f, cx + th / 2f, a + len / 2f);
            if (l < 0.5f) clampInside(lens, pill, Ui.dpf(5));
            return;
        }
        float cy = pill.centerY();
        if (k > 0) { a = Ui.lerp(a, pill.centerX(), k); len = Ui.lerp(len, pill.height() - Ui.dpf(8), k); }
        lens.set(a - len / 2f, cy - th / 2f, a + len / 2f, cy + th / 2f);
        if (l < 0.5f) clampInside(lens, pill, Ui.dpf(5));
    }

    private static void clampInside(RectF r, RectF bounds, float inset) {
        float dx = 0, dy = 0;
        if (r.left < bounds.left + inset) dx = bounds.left + inset - r.left;
        else if (r.right > bounds.right - inset) dx = bounds.right - inset - r.right;
        if (r.top < bounds.top + inset) dy = bounds.top + inset - r.top;
        else if (r.bottom > bounds.bottom - inset) dy = bounds.bottom - inset - r.bottom;
        r.offset(dx, dy);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutGeometry();
        lensX.snap(slotCenter(selected));
    }

    /** Search field geometry for the fully-morphed search bubble. */
    private int sideMargin(int W, int H) { return railFor(W, H) ? Ui.dp(20) : Math.max(Ui.dp(20), (W - Ui.dp(560)) / 2); }
    private static int tabletFieldSpan(int W) { return (int) (Math.min(W * 0.42f, Ui.dpf(440)) + Ui.dpf(8 + 46)); }
    private int fieldLeft(int W, int H) {
        if (tabletFor(W, H)) return (W - tabletFieldSpan(W)) / 2 + Ui.dp(46);
        if (Ui.gStyle && !railFor(W, H)) {
            int barW = Math.min(W - Ui.dp(32), Ui.dp(420));
            return (W - barW) / 2 + Ui.dp(46);
        }
        return sideMargin(W, H) + Ui.dp(railFor(W, H) ? 44 : 48 + 8 + 44);
    }
    private int fieldWidth(int W, int H) {
        if (tabletFor(W, H)) return Math.max(1, tabletFieldSpan(W) - Ui.dp(46 + 18));
        if (Ui.gStyle && !railFor(W, H)) {
            int barW = Math.min(W - Ui.dp(32), Ui.dp(420));
            return Math.max(1, barW - Ui.dp(46 + 44));
        }
        return Math.max(1, W - sideMargin(W, H) - fieldLeft(W, H) - Ui.dp(18));
    }

    @Override protected void onMeasure(int ws, int hs) {
        int W = MeasureSpec.getSize(ws), H = MeasureSpec.getSize(hs);
        setMeasuredDimension(W, H);
        field.measure(MeasureSpec.makeMeasureSpec(fieldWidth(W, H), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Ui.dp(48), MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int W = r - l, H = b - t;
        int top = H - Ui.navInset - Ui.dp(8) - Ui.dp(48);
        int left = fieldLeft(W, H);
        field.layout(left, top, left + field.getMeasuredWidth(), top + field.getMeasuredHeight());
        layoutGeometry();
        if (!lensX.running && lift.value < 0.01f) lensX.snap(slotCenter(selected));
    }

    // ---- drawing ----
    @Override protected void dispatchDraw(Canvas canvas) {
        layoutGeometry();
        computeLens();
        float k = rail ? search : Math.max(pillK(), search);
        // field follows the morphing bubble (or the unified pill in G style)
        if (field.getVisibility() == VISIBLE) {
            RectF host = unifiedG() ? pill : circle;
            field.setTranslationY(host.centerY() - (field.getTop() + field.getHeight() / 2f));
            if (tablet || unifiedG()) field.setTranslationX(host.left + Ui.dpf(46) - field.getLeft());
            field.setAlpha(Ui.clamp((search - 0.4f) / 0.6f, 0f, 1f));
        }

        drawMini(canvas);

        // tablet sidebar open: its own tab list replaces the top pill, which fades out in place
        float barA = tablet ? 1f - Ui.clamp(sideP * 1.6f, 0f, 1f) : 1f;
        if (barA <= 0.01f) { super.dispatchDraw(canvas); return; }
        int barSave = barA < 0.99f ? canvas.saveLayerAlpha(0, 0, getWidth(), pill.bottom + Ui.dpf(40), (int) (255 * barA)) : -1;
        drawBarAndLens(canvas, k);
        if (barSave >= 0) canvas.restoreToCount(barSave);
        super.dispatchDraw(canvas);
    }

    private void drawBarAndLens(Canvas canvas, float k) {
        boolean hw = canvas.isHardwareAccelerated();
        float l = lift.value * (1f - k);
        // G style has no lens: the selection capsule just grows and springs with the same lift (drawBar)
        if (hw && l > 0.01f && !Ui.gStyle) {
            contentNode.setPosition(0, 0, getWidth(), getHeight());
            RecordingCanvas rc = contentNode.beginRecording(getWidth(), getHeight());
            try { drawBar(rc, k, l); } finally { contentNode.endRecording(); }
            canvas.drawRenderNode(contentNode);
            // lifted lens samples page + bar + icons and magnifies them
            lensGlass.shapes(lens, lens.height() / 2f, null, 0, null, 0);
            lensGlass.alpha = l;
            lensGlass.mag = 1f + 0.14f * l;
            lensGlass.magX = lens.centerX();
            lensGlass.magY = lens.centerY();
            lensGlass.tint = Ui.dark ? Ui.alpha(0xFFFFFFFF, 0.06f) : Ui.alpha(0xFFFFFFFF, 0.05f);
            drawLens(canvas);
        } else {
            drawBar(canvas, k, l);
        }
    }

    private void drawLens(Canvas canvas) {
        // the lens refracts the page beneath plus this dock's freshly recorded glass and glyphs
        lensGlass.extra = contentNode;
        lensGlass.draw(canvas, this, backdrop);
    }

    private void drawBar(Canvas c, float k, float l) {
        // glass: pill + search bubble (+ lens bulge while lifted), smooth-unioned only while lifted
        RectF second = circle.isEmpty() ? null : circle;
        barGlass.shapes(pill, pill.height() / 2f, second, second == null ? 0 : circle.height() / 2f, l > 0.02f && !Ui.gStyle ? lens : null, lens.height() / 2f);
        barGlass.smoothK = Ui.dpf(16) * l;
        barGlass.tint = Ui.barTint();
        barGlass.blurDp = Ui.barBlur();
        // touch glow blooms under the finger inside the glass
        barGlass.pressX = touchX; barGlass.pressY = touchY;
        barGlass.pressR = Ui.dpf(44);
        barGlass.pressAmt = barPress.value * 0.85f;
        barGlass.draw(c, this, backdrop);

        // resting selection capsule
        float restA = Ui.gStyle ? (1f - k) : (1f - l) * (1f - k);
        if (restA > 0.01f) {
            // G style: One UI 8.5/9.x crisp pill capsule (soft accent tint); A style: light-grey capsule
            int capCol = Ui.gStyle ? Ui.alpha(Ui.ACCENT, Ui.dark ? 0.24f : 0.14f) : (Ui.dark ? 0x2EFFFFFF : 0x17000000);
            fill.setColor(Ui.alpha(capCol, restA));
            c.drawRoundRect(lens, lens.height() / 2f, lens.height() / 2f, fill);
        }

        drawTabs(c, k, Ui.text(), false);
        // accent copy of the tab glyphs, visible only under the lens / selection capsule
        if (1f - k > 0.01f) {
            c.save();
            c.clipRect(lens);
            drawTabs(c, k, Ui.ACCENT, true);
            c.restore();
        }

        // tablet: sidebar button at the start of the tab pill
        float ld = lead();
        if (ld > Ui.dpf(4)) {
            float a = Ui.clamp(1f - search * 2.2f, 0f, 1f);
            Icons.draw(c, Icons.SIDEBAR, pill.left + Ui.dpf(8) + ld / 2f, pill.centerY(), Ui.dpf(21), Ui.alpha(Ui.text(), a));
        }

        if (unifiedG()) {
            // G style search mode: search icon on the left + close (X) on the right inside the unified pill
            float sa = Ui.clamp((search - 0.25f) / 0.75f, 0f, 1f);
            if (sa > 0.01f) {
                Icons.draw(c, Icons.SEARCH, pill.left + Ui.dpf(25), pill.centerY(), Ui.dpf(20), Ui.alpha(Ui.text2(), sa));
                Icons.draw(c, Icons.CLOSE, pill.right - Ui.dpf(24), pill.centerY(), Ui.dpf(18), Ui.alpha(Ui.text2(), sa));
            }
            return;
        }

        // search bubble glyph (A style / rail / tablet)
        float sp = searchPress.value;
        float iconCx = Ui.lerp(circle.centerX(), circle.left + Ui.dpf(26), search);
        Icons.draw(c, Icons.SEARCH, iconCx, circle.centerY(), Ui.dpf(25) * (1f - 0.08f * sp) * Ui.lerp(1f, 0.82f, search),
                search > 0.5f ? Ui.text2() : Ui.text());
        if (sp > 0.01f && !Glass.shadersOk) {
            fill.setColor(Ui.alpha(Ui.pressed(), sp));
            c.drawRoundRect(circle, circle.height() / 2f, circle.height() / 2f, fill);
        }
    }

    private void drawTabs(Canvas c, float k, int color, boolean accentPass) {
        boolean ug = unifiedG();
        int count = slotCount();
        float iconSize = Ui.dpf(ug ? 23 : 27);
        float labelA = Ui.clamp(1f - k * 2.2f, 0f, 1f);
        for (int i = 0; i < count; i++) {
            float s = slotCenter(i);
            float cx = rail ? pill.centerX() : s;
            float cy = rail ? s : pill.centerY();
            float a;
            if (ug) {
                a = Ui.clamp(1f - search * 2.4f, 0f, 1f);
            } else if (rail) {
                a = 1f;
            } else if (i == selected) {
                cx = Ui.lerp(cx, pill.centerX(), k);
                a = 1f;
            } else {
                // the other tabs slide toward the selected one as the pill folds, fading out
                cx = Ui.lerp(cx, pill.centerX(), k * 0.85f);
                a = Ui.clamp(1f - k * 1.6f, 0f, 1f);
            }
            // while the bar re-shapes itself for the other screen, its glyphs dip out and come back in place
            if (morph < 1f) a *= Ui.clamp(Math.abs(morph * 2f - 1f) * 1.3f - 0.15f, 0f, 1f);
            if (a <= 0.01f) continue;
            if (tablet) {
                a *= Ui.clamp(1f - search * 2.2f, 0f, 1f);
                if (a <= 0.01f) continue;
                tabLabel.setColor(Ui.alpha(color, a));
                tabLabel.setTypeface(accentPass ? Ui.bold : Ui.medium);
                c.drawText(labels[i], cx, cy + tabLabel.getTextSize() * 0.36f, tabLabel);
                continue;
            }
            int iconId = i < 4 ? TAB_ICONS[i] : Icons.SEARCH;
            String text = i < 4 ? labels[i] : getContext().getString(R.string.search);
            float iy = rail ? cy : cy - Ui.dpf(ug ? 7.5f : 7f) * (1f - k);
            int col = Ui.alpha(color, a);
            Icons.draw(c, iconId, cx, iy, iconSize * Ui.lerp(1f, 0.92f, k), col);
            if (labelA > 0.01f && !rail) {
                label.setColor(Ui.alpha(color, a * labelA));
                label.setTypeface(accentPass ? Ui.bold : Ui.medium);
                c.drawText(text, cx, cy + Ui.dpf(ug ? 18.5f : 19.5f), label);
            }
        }
    }

    private final android.text.TextPaint tabLabel = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    { tabLabel.setTextSize(Ui.sp(15)); tabLabel.setTextAlign(Paint.Align.CENTER); }

    /** Tablet bar: shuffle, previous, play, next, repeat on the left. Centres in px. */
    private float tabletBtn(int i) { return mini.left + Ui.dpf(30) + Ui.dpf(44) * i; }
    /** Tablet bar, right end: more · lyrics · queue, centres in px; i = 0 is the queue at the far right. */
    private float tabletRBtn(int i) { return mini.right - Ui.dpf(32) - Ui.dpf(46) * i; }
    /** Right-hand buttons that fit: all three on a wide bar, only the queue when the bar is narrow (sidebar open on a small screen). */
    private int rCount() { return mini.width() >= Ui.dpf(600) ? 3 : 1; }
    /** Left edge of the cover in the music bar — the player opens out of / closes into exactly this square. */
    private float miniArtX() { return tablet ? tabletBtn(4) + Ui.dpf(34) : mini.left + Ui.dpf(15); }

    private void drawMini(Canvas c) {
        float show = miniShow * (tablet ? 1f : 1f - search);
        if (show <= 0.01f || miniHidden) return;
        miniGlass.shapes(mini, mini.height() / 2f, null, 0, null, 0);
        miniGlass.alpha = show;
        miniGlass.tint = Ui.barTint();
        miniGlass.blurDp = Ui.barBlur();
        miniGlass.pressX = touchX; miniGlass.pressY = touchY;
        miniGlass.pressR = Ui.dpf(60);
        miniGlass.pressAmt = miniPress.value * 0.85f;
        miniGlass.draw(c, this, backdrop);
        int save = c.save();
        c.clipRect(mini);
        float pad = Ui.dpf(13);
        float artS = mini.height() - Ui.dpf(20);
        float ax = mini.left + pad + Ui.dpf(2), ay = mini.centerY() - artS / 2f;
        if (tablet) {
            int fg = Ui.alpha(Ui.text(), show), dim = Ui.alpha(Ui.text(), show * 0.55f);
            boolean pl = Player.playing;
            Icons.draw(c, Icons.SHUFFLE, tabletBtn(0), mini.centerY(), Ui.dpf(20), Player.shuffle ? Ui.alpha(Ui.ACCENT, show) : dim);
            Icons.draw(c, Icons.PREV, tabletBtn(1), mini.centerY(), Ui.dpf(22), fg);
            Icons.draw(c, pl ? Icons.PAUSE : Icons.PLAY, tabletBtn(2), mini.centerY(), Ui.dpf(pl ? 22 : 21), fg);
            Icons.draw(c, Icons.NEXT, tabletBtn(3), mini.centerY(), Ui.dpf(22), fg);
            Icons.draw(c, "track".equals(Player.repeat) ? Icons.REPEAT_ONE : Icons.REPEAT, tabletBtn(4), mini.centerY(), Ui.dpf(20),
                    "off".equals(Player.repeat) ? dim : Ui.alpha(Ui.ACCENT, show));
            if (rCount() > 2) Icons.draw(c, Icons.MORE, tabletRBtn(2), mini.centerY(), Ui.dpf(22), fg);
            if (rCount() > 1) Icons.draw(c, Icons.LYRICS, tabletRBtn(1), mini.centerY(), Ui.dpf(22), fg);
            Icons.draw(c, Icons.LIST, tabletRBtn(0), mini.centerY(), Ui.dpf(22), fg);
            ax = miniArtX();
        }
        if (artBmp != null) {
            float pop = 0.82f + 0.18f * artPop;
            float ps = artS * pop, pax = ax + (artS - ps) / 2f, pay = ay + (artS - ps) / 2f;
            float sc = Math.max(ps / artBmp.getWidth(), ps / artBmp.getHeight());
            artM.setScale(sc, sc);
            artM.postTranslate(pax, pay);
            art.getShader().setLocalMatrix(artM);
            art.setAlpha((int) (255 * show * Math.min(1f, 0.3f + artPop)));
            tmp.set(pax, pay, pax + ps, pay + ps);
            c.drawRoundRect(tmp, Ui.dpf(7), Ui.dpf(7), art);
        } else {
            fill.setColor(Ui.alpha(Ui.placeholder(), show));
            tmp.set(ax, ay, ax + artS, ay + artS);
            c.drawRoundRect(tmp, Ui.dpf(7), Ui.dpf(7), fill);
            Icons.draw(c, Icons.NOTE, tmp.centerX(), tmp.centerY(), artS * 0.5f, Ui.alpha(Ui.text3(), show));
        }
        float nextA = rail || tablet ? 1f : 1f - miniK();
        float btnW = Ui.dpf(40);
        if (tablet) {
            // right side of the tablet bar shows only the title block; controls live on the left
            float tx = ax + artS + Ui.dpf(10), avail = tabletRBtn(rCount() - 1) - Ui.dpf(28) - tx;
            if (Player.track != null) {
                title.setColor(Ui.alpha(Ui.text(), show));
                sub.setColor(Ui.alpha(Ui.text2(), show));
                CharSequence t = TextUtils.ellipsize(Player.track.name, title, avail, TextUtils.TruncateAt.END);
                CharSequence s = TextUtils.ellipsize(Player.track.artist, sub, avail, TextUtils.TruncateAt.END);
                c.drawText(t, 0, t.length(), tx, mini.centerY() - Ui.dpf(2.5f), title);
                c.drawText(s, 0, s.length(), tx, mini.centerY() + Ui.dpf(13.5f), sub);
            }
            c.restoreToCount(save);
            return;
        }
        float playCx = mini.right - Ui.dpf(14) - btnW * (0.5f + nextA);
        float nextCx = mini.right - Ui.dpf(14) - btnW * 0.5f;
        boolean playing = Player.playing;
        Icons.draw(c, playing ? Icons.PAUSE : Icons.PLAY, playCx, mini.centerY(), Ui.dpf(playing ? 22 : 21), Ui.alpha(Ui.text(), show));
        if (nextA > 0.01f) Icons.draw(c, Icons.NEXT, nextCx, mini.centerY(), Ui.dpf(25), Ui.alpha(Ui.text(), show * nextA));
        float tx = ax + artS + Ui.dpf(10);
        float avail = playCx - btnW / 2f - tx - Ui.dpf(4);
        if (avail > Ui.dpf(20) && Player.track != null) {
            title.setColor(Ui.alpha(Ui.text(), show));
            sub.setColor(Ui.alpha(Ui.text2(), show));
            CharSequence t = TextUtils.ellipsize(Player.track.name, title, avail, TextUtils.TruncateAt.END);
            CharSequence s = TextUtils.ellipsize(Player.track.artist, sub, avail, TextUtils.TruncateAt.END);
            c.drawText(t, 0, t.length(), tx, mini.centerY() - Ui.dpf(2.5f), title);
            c.drawText(s, 0, s.length(), tx, mini.centerY() + Ui.dpf(13.5f), sub);
        }
        if (miniPress.value > 0.01f && !Glass.shadersOk) {
            fill.setColor(Ui.alpha(Ui.pressed(), miniPress.value));
            c.drawRoundRect(mini, mini.height() / 2f, mini.height() / 2f, fill);
        }
        c.restoreToCount(save);
    }

    // ---- touch ----
    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                touch = hit(x, y);
                if (touch == T_NONE) return false;
                downX = x; downY = y;
                touchX = x; touchY = y;
                dragging = false;
                if (touch == T_TAB || touch == T_SEARCH || touch == T_HOME_CIRCLE) barPress.to(1f);
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                if (touch == T_TAB) {
                    lift.to(1f);
                    lensX.to(clampLens(axisOf(x, y)));
                } else if (touch == T_MINI) {
                    miniPress.to(1f);
                } else if (touch == T_SEARCH) {
                    searchPress.to(1f);
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (vt != null) vt.addMovement(e);
                touchX = x; touchY = y;
                if (touch == T_TAB) {
                    dragging |= Math.abs(axisOf(x, y) - axisOf(downX, downY)) > Ui.dpf(6);
                    lensX.to(clampLens(axisOf(x, y)));
                    if (vt != null) { vt.computeCurrentVelocity(1000); lensVel = rail ? vt.getYVelocity() : vt.getXVelocity(); }
                    invalidate();
                } else if (touch == T_MINI && downY - y > Ui.dpf(24)) {
                    miniPress.to(0f);
                    touch = T_NONE;
                    if (listener != null) listener.onMini();
                } else if (touch == T_MINI && Math.hypot(x - downX, y - downY) > Ui.dpf(12)) {
                    miniPress.to(0f);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                int t = touch;
                touch = T_NONE;
                lensVel = 0;
                barPress.to(0f);
                if (t == T_TAB) {
                    int target = up ? slotAt(dragging ? lensX.value : axisOf(x, y)) : selected;
                    if (unifiedG() && target == 4) {
                        lensX.to(slotCenter(selected));
                        postDelayed(() -> lift.to(0f), 120);
                        if (up && listener != null) listener.onSearch();
                    } else {
                        boolean re = target == selected;
                        selected = target;
                        lensX.to(slotCenter(target));
                        postDelayed(() -> lift.to(0f), dragging ? 60 : 200);
                        if (up && listener != null) listener.onTab(target, re);
                    }
                } else if (t == T_MINI) {
                    miniPress.to(0f);
                    if (up && Math.hypot(x - downX, y - downY) < Ui.dpf(12) && listener != null) listener.onMini();
                } else if (t == T_MINI_PLAY && up) {
                    Player.toggle();
                } else if (t == T_MINI_NEXT && up) {
                    Player.next();
                } else if (t == T_MINI_PREV && up) {
                    Player.prev();
                } else if (t == T_MINI_SHUF && up) {
                    Player.toggleShuffle();
                } else if (t == T_MINI_REP && up) {
                    Player.cycleRepeat();
                } else if ((t == T_MINI_MORE || t == T_MINI_LYR || t == T_MINI_QUEUE) && up && listener != null) {
                    int i = t == T_MINI_QUEUE ? 0 : t == T_MINI_LYR ? 1 : 2;
                    float cx = tabletRBtn(i);
                    listener.onMiniAction(t == T_MINI_MORE ? MINI_MORE : t == T_MINI_LYR ? MINI_LYRICS : MINI_QUEUE,
                            new RectF(cx - Ui.dpf(22), mini.top, cx + Ui.dpf(22), mini.bottom));
                } else if (t == T_SIDE && up && listener != null) {
                    listener.onSidebar();
                } else if (t == T_SEARCH) {
                    searchPress.to(0f);
                    if (up && !inSearch() && listener != null) listener.onSearch();
                } else if (t == T_HOME_CIRCLE && up) {
                    if (inSearch()) { if (listener != null) listener.onExitSearch(); }
                    else { setCollapsed(false); if (listener != null) listener.onTab(selected, false); }
                }
                if (vt != null) { vt.recycle(); vt = null; }
                invalidate();
                return true;
            }
        }
        return touch != T_NONE;
    }

    private float clampLens(float x) {
        float half = slotW() * 0.5f;
        return Ui.clamp(x, axisStart() + half - Ui.dpf(6), axisStart() + axisLen() - half + Ui.dpf(6));
    }

    private int hit(float x, float y) {
        float slop = Ui.dpf(4);
        if (miniShow * (tablet ? 1f : 1f - search) > 0.5f && x >= mini.left - slop && x <= mini.right + slop && y >= mini.top - slop && y <= mini.bottom + slop) {
            if (tablet) {
                int[] kinds = {T_MINI_SHUF, T_MINI_PREV, T_MINI_PLAY, T_MINI_NEXT, T_MINI_REP};
                for (int i = 0; i < 5; i++) if (Math.abs(x - tabletBtn(i)) < Ui.dpf(22)) return kinds[i];
                int[] right = {T_MINI_QUEUE, T_MINI_LYR, T_MINI_MORE};
                for (int i = 0; i < rCount(); i++) if (Math.abs(x - tabletRBtn(i)) < Ui.dpf(22)) return right[i];
                return T_MINI;
            }
            float btnW = Ui.dpf(40);
            float nextA = rail ? 1f : 1f - miniK();
            float nextL = mini.right - Ui.dpf(14) - btnW;
            float playL = mini.right - Ui.dpf(14) - btnW * (1f + nextA);
            if (nextA > 0.5f && x >= nextL) return T_MINI_NEXT;
            if (x >= playL && x < playL + btnW) return T_MINI_PLAY;
            return T_MINI;
        }
        // tablet with the sidebar open: the top pill is hidden, so it takes no touches
        if (tablet && sideP > 0.5f) return T_NONE;
        if (tablet && !inSearch() && y >= pill.top - slop && y <= pill.bottom + slop && x >= pill.left && x < pill.left + lead() + Ui.dpf(6))
            return T_SIDE;
        if (unifiedG() && inSearch() && (pill.contains(x, y) || (y >= pill.top - slop && y <= pill.bottom + slop && x >= pill.left && x <= pill.right))) {
            return x >= pill.right - Ui.dpf(48) ? T_HOME_CIRCLE : T_NONE;
        }
        if (!circle.isEmpty() && circle.contains(x, y) && !inSearch()) return T_SEARCH;
        if (!circle.isEmpty() && inSearch() && circle.contains(x, y)) return T_NONE;
        if (pill.contains(x, y) || (y >= pill.top - slop && y <= pill.bottom + slop && x >= pill.left && x <= pill.right)) {
            return (rail ? search : Math.max(pillK(), search)) > 0.5f ? T_HOME_CIRCLE : T_TAB;
        }
        return T_NONE;
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Player.listeners.remove(playerListener);
    }

    RectF miniRect() { layoutGeometry(); return new RectF(mini); }
    RectF miniArtRect() {
        RectF m = miniRect();
        float s = m.height() - Ui.dpf(20);
        float ax = miniArtX(), ay = m.centerY() - s / 2f;
        return new RectF(ax, ay, ax + s, ay + s);
    }

    void setMiniHidden(boolean hidden) {
        miniHidden = hidden;
        invalidate();
    }

    boolean miniHidden;
}
