package com.oneuibook.prism;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** glass context menu that grows out of the pill/button that opened it and shrinks back into it. */
final class GlassMenu extends FrameLayout {
    static final class Entry {
        final String title;
        final int icon;
        final boolean checked;
        final Runnable run;
        boolean checkable;

        Entry(String title, int icon, boolean checked, Runnable run) {
            this.title = title;
            this.icon = icon;
            this.checked = checked;
            this.run = run;
        }
    }

    private final MainActivity main;
    private final RectF anchor, target = new RectF(), cur = new RectF();
    private final Glass.Surface glass = new Glass.Surface();
    private final LinearLayout list;
    private final Ui.Spring p = new Ui.Spring(420f, 0.82f, 0.002f);
    private boolean closing;
    private final boolean dark;

    static GlassMenu show(MainActivity m, RectF anchor, List<Entry> entries) { return show(m, anchor, entries, false); }

    /** Bottom glass sheet that grows out of the button that opened it. */
    static GlassMenu sheet(MainActivity m, RectF anchor, String title, List<Entry> entries) { return sheet(m, anchor, title, entries, null); }

    /** As {@link #sheet}, but {@code source} (the tapped button) disappears into the sheet and reappears when it closes. */
    static GlassMenu sheet(MainActivity m, RectF anchor, String title, List<Entry> entries, View source) {
        GlassMenu g = new GlassMenu(m, anchor, entries, true);
        g.sheet = true;
        g.source = source;
        // the button itself is taken over by the drop: a copy of it sinks into the glass (see drawGlyph)
        if (source != null) { source.animate().cancel(); source.setAlpha(0f); source.setScaleX(1f); source.setScaleY(1f); }
        TextView t = Ui.text(m, title, 17, Ui.semibold, Ui.text());
        t.setGravity(Gravity.CENTER);
        g.list.addView(t, 0, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(52)));
        View grip = new View(m);
        grip.setBackground(Ui.round(Ui.text3(), 3));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(5));
        gl.gravity = Gravity.CENTER_HORIZONTAL;
        gl.topMargin = Ui.dp(4);
        g.list.addView(grip, 0, gl);
        m.root.addView(g, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return g;
    }

    private boolean sheet;
    private View source;

    static GlassMenu show(MainActivity m, RectF anchor, List<Entry> entries, boolean darkStyle) {
        GlassMenu g = new GlassMenu(m, anchor, entries, darkStyle);
        m.root.addView(g, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return g;
    }

    private GlassMenu(MainActivity m, RectF anchor, List<Entry> entries, boolean darkStyle) {
        super(m);
        main = m;
        dark = Ui.dark;
        this.anchor = new RectF(anchor);
        setWillNotDraw(false);
        // one material from the first frame to the last: the same blur as the bars, and every optical value
        // blended continuously from the button it grows out of (see onProgress) — no jump in frost or tint
        glass.blurDp = Ui.barBlur();
        glass.vibrance = 1.35f;
        glass.strokeColor = Ui.glassStroke();
        int fg = Ui.text();
        boolean anyCheck = false;
        for (Entry e : entries) anyCheck |= e.checked || e.checkable;

        list = new LinearLayout(m);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(6), 0, Ui.dp(6));
        for (Entry e : entries) {
            LinearLayout r = new LinearLayout(m);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(Ui.dp(anyCheck ? 12 : 18), 0, Ui.dp(18), 0);
            if (anyCheck) {
                Icons.IconView ck = new Icons.IconView(m, Icons.CHECK, 17, fg);
                ck.setVisibility(e.checked ? VISIBLE : INVISIBLE);
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22));
                cl.rightMargin = Ui.dp(8);
                r.addView(ck, cl);
            }
            TextView t = Ui.text(m, e.title, 16, Ui.regular, fg);
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (e.icon != 0) {
                LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22));
                il.leftMargin = Ui.dp(14);
                r.addView(new Icons.IconView(m, e.icon, 19, fg), il);
            }
            r.setBackground(Widgets.ripple());
            r.setOnClickListener(v -> { dismiss(); if (e.run != null) postDelayed(e.run, 140); });
            list.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(46)));
        }
        list.setAlpha(0f);
        addView(list, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        p.stiffness = 260f;
        p.damping = 0.68f;
        p.onUpdate = this::onProgress;
        p.onEnd = () -> {
            if (closing) {
                if (fromHeader) main.header.setPillHidden(false);
                restoreSource();
                main.root.removeView(this);
            }
        };
        cur.set(anchor);
        blobRadius = anchor.height() / 2f;
        // menus opened from the header pill take the pill's place while they are open
        fromHeader = RectF.intersects(anchor, headerPillInRoot(m));
        if (fromHeader) m.header.setPillHidden(true);
    }

    private boolean fromHeader;

    private static RectF headerPillInRoot(MainActivity m) {
        float[] o = Glass.abs(m.header);
        RectF r = new RectF(m.header.pill);
        r.offset(o[0], o[1]);
        return r;
    }

    @Override protected void onMeasure(int w, int h) {
        super.onMeasure(w, h);
        if (sheet) {
            int sw = Math.min(getMeasuredWidth() - Ui.dp(24), Ui.dp(560));
            list.measure(MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            return;
        }
        int maxW = Math.min(Ui.dp(280), getMeasuredWidth() - Ui.dp(32));
        list.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int mw = Math.max(Ui.dp(200), Math.min(maxW, list.getMeasuredWidth()));
        list.measure(MeasureSpec.makeMeasureSpec(mw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(list.getMeasuredHeight(), MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = list.getMeasuredWidth(), h = list.getMeasuredHeight();
        float W = getWidth(), H = getHeight();
        if (sheet) {
            float left = (W - w) / 2f, bottom = H - Ui.navInset - Ui.dpf(12);
            target.set(left, bottom - h, left + w, bottom);
            list.layout((int) target.left, (int) target.top, (int) target.right, (int) target.bottom);
            if (!closing && p.target == 0f && p.value == 0f) p.to(1f);
            return;
        }
        boolean below = anchor.centerY() < H * 0.55f;
        boolean alignRight = anchor.centerX() > W / 2f;
        float left = alignRight ? Math.min(anchor.right, W - Ui.dpf(16)) - w : Math.max(anchor.left, Ui.dpf(16));
        left = Ui.clamp(left, Ui.dpf(12), W - Ui.dpf(12) - w);
        float top = below ? anchor.top : anchor.bottom - h;
        top = Ui.clamp(top, Ui.statusInset + Ui.dpf(4), H - Ui.dpf(12) - h);
        target.set(left, top, left + w, top + h);
        list.layout((int) left, (int) top, (int) left + w, (int) top + h);
        if (!closing && p.target == 0f && p.value == 0f) p.to(1f);
    }

    /**
     * Drop-like morph: the pill first gathers into a round blob that drifts toward
     * the menu's centre, then the blob swells into the menu (the spring's overshoot gives the wobble).
     */
    private void onProgress() {
        float v = p.value;
        float gather = Ui.clamp(v / 0.42f, 0f, 1f);
        float swell = Ui.clamp((v - 0.28f) / 0.72f, 0f, 1.2f);
        gather = gather * gather * (3f - 2f * gather);
        float blob = anchor.height() * 1.18f;
        float bw = Ui.lerp(anchor.width(), blob, gather), bh = Ui.lerp(anchor.height(), blob, gather);
        float w = Ui.lerp(bw, target.width(), swell), h = Ui.lerp(bh, target.height(), swell);
        float t = Ui.clamp(v, 0f, 1.15f);
        float cx = Ui.lerp(anchor.centerX(), target.centerX(), t), cy = Ui.lerp(anchor.centerY(), target.centerY(), t);
        // stretch slightly along the direction of travel while moving fastest
        float travel = (float) Math.sin(Math.PI * Ui.clamp(v, 0f, 1f)) * 0.08f;
        w *= 1f + travel;
        h *= 1f - travel * 0.5f;
        cur.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        blobRadius = Ui.lerp(Math.min(w, h) * 0.5f, Ui.dpf(22), Ui.clamp(swell, 0f, 1f));
        applyMaterial(Ui.clamp(swell, 0f, 1f));
        float ca = Ui.clamp((v - 0.55f) / 0.45f, 0f, 1f);
        list.setAlpha(ca);
        float s = Ui.lerp(0.92f, 1f, ca);
        list.setPivotX(anchor.centerX() - target.left);
        list.setPivotY(anchor.centerY() - target.top);
        list.setScaleX(s);
        list.setScaleY(s);
        invalidate();
    }

    private float blobRadius;
    private boolean restored;
    private final android.graphics.RenderNode extraNode = new android.graphics.RenderNode("menu-extra");

    /** Draws what the menu grew out of (header pill icons or the tapped button), riding along with the drop. */
    private void drawGlyph(Canvas c, float a) {
        if (a <= 0.01f) return;
        float s = Ui.clamp(Math.min(cur.width() / Math.max(1f, anchor.width()), cur.height() / Math.max(1f, anchor.height())), 0.7f, 1.12f);
        c.save();
        c.translate(cur.centerX(), cur.centerY());
        c.scale(s, s);
        c.translate(-anchor.centerX(), -anchor.centerY());
        if (fromHeader) {
            main.header.drawGlyphs(c, anchor, a);
        } else if (source != null) {
            float[] o = Glass.abs(source), me = Glass.abs(this);
            int sc = c.saveLayerAlpha(null, (int) (255 * a));
            c.translate(o[0] - me[0], o[1] - me[1]);
            source.draw(c);
            c.restoreToCount(sc);
        }
        c.restore();
    }

    private void restoreSource() {
        if (restored || source == null) return;
        restored = true;
        // the drop has shrunk back onto the button with the button's copy inside it: swap in place
        source.animate().cancel();
        source.setAlpha(1f); source.setScaleX(1f); source.setScaleY(1f);
    }

    /** Over the player, rows sit on moving artwork, so the open sheet keeps a little more frost there. */
    private boolean overPlayer() { return main.np != null && main.np.getVisibility() == VISIBLE; }

    /**
     * k = 0: exactly the material of what it grew from (header pill = bar glass; a plain button = clear glass),
     * k = 1: the open menu. Everything in between is a straight blend, so frost and refraction never jump.
     */
    private void applyMaterial(float k) {
        // one material for the whole life of the menu: the same frost and tint folded, unfolding and open
        glass.tint = overPlayer() ? Ui.menuTint() : Ui.barTint();
        glass.blurDp = Ui.barBlur();
        // header pill optics → menu optics
        glass.refractDp = Ui.lerp(15f, 17f, k);
        glass.edgeDp = Ui.lerp(20f, 26f, k);
        glass.outerDp = Ui.lerp(5f, 7f, k);
        glass.outerRefractDp = Ui.lerp(11f, 12f, k);
        glass.rim = Ui.lerp(0.32f, 0.4f, k);
        glass.shadowAlpha = Ui.lerp(fromHeader ? 0.07f : 0f, 0.22f, k);
        glass.chroma = 0f;
        float v = p.value;
        // full-strength glass at every size; a drop from a plain button only fades in over its first few frames
        glass.alpha = fromHeader ? 1f : Ui.clamp(v / 0.1f, 0f, 1f);
    }

    @Override protected void dispatchDraw(Canvas c) {
        glass.shapes(cur, Math.min(blobRadius, Math.min(cur.width(), cur.height()) / 2f), null, 0, null, 0);
        // what the glass refracts: the player (when open over it) plus the button's own glyphs, which sink into
        // the drop and dissolve as it swells, and surface again as it shrinks back
        float v = p.value;
        float swell = Ui.clamp((v - 0.28f) / 0.72f, 0f, 1f);
        float inA = 1f - Ui.clamp(swell * 2.2f, 0f, 1f);
        boolean overNp = main.np != null && main.np.getVisibility() == VISIBLE && main.np.recorded;
        boolean glyph = inA > 0.01f && (fromHeader || source != null) && c.isHardwareAccelerated();
        if (glyph || overNp) {
            extraNode.setPosition(0, 0, getWidth(), getHeight());
            android.graphics.RecordingCanvas rc = extraNode.beginRecording(getWidth(), getHeight());
            try {
                if (overNp) rc.drawRenderNode(main.np.node);
                if (glyph) drawGlyph(rc, inA);
            } finally { extraNode.endRecording(); }
            glass.extra = extraNode;
        } else glass.extra = null;
        glass.draw(c, this, main.backdrop);
        // a crisp copy on top hands over to the refracted one within the first moments of the morph
        float topA = 1f - Ui.clamp(v / 0.3f, 0f, 1f);
        if (topA > 0.01f && (fromHeader || source != null)) drawGlyph(c, topA);
        c.save();
        c.clipRect(cur);
        super.dispatchDraw(c);
        c.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && !target.contains(e.getX(), e.getY())) {
            dismiss();
        }
        return true;
    }

    void dismiss() {
        if (closing) return;
        closing = true;
        p.stiffness = 380f;
        p.damping = 0.9f;
        p.to(0f);
    }

    static boolean dismissAny(ViewGroup root) {
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            View v = root.getChildAt(i);
            if (v instanceof GlassMenu) { ((GlassMenu) v).dismiss(); return true; }
        }
        return false;
    }
}
