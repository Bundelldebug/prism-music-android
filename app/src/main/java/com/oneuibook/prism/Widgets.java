package com.oneuibook.prism;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Card / row / chip builders shared by the pages. */
final class Widgets {
    private Widgets() {}

    static final LinearLayout.LayoutParams MW = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

    /** Generic list entity (playlist / album / artist / track / custom). */
    static final class Item {
        String type, id, uri, title, subtitle, caption, image, contextUri;
        int count = -1;
        int icon;
        int color;
        JSONObject raw;
        Runnable action;
        /** Set for on-device songs (no Spotify JSON). */
        Player.Track track;

        static Item of(JSONObject o) {
            if (o == null) return null;
            Item i = new Item();
            i.raw = o;
            i.type = o.optString("type");
            i.id = o.optString("id");
            i.uri = o.optString("uri");
            i.title = o.optString("name");
            i.image = Spotify.image(o, 300);
            switch (i.type) {
                case "playlist": {
                    JSONObject ow = o.optJSONObject("owner");
                    String desc = android.text.Html.fromHtml(o.optString("description", ""), 0).toString();
                    i.subtitle = desc.length() > 0 ? desc : (ow == null ? "" : ow.optString("display_name"));
                    i.count = Spotify.total(o);
                    break;
                }
                case "album":
                    i.subtitle = Spotify.artists(o);
                    i.count = o.optInt("total_tracks", -1);
                    break;
                case "artist":
                    i.subtitle = "";
                    break;
                case "track":
                case "episode":
                    i.subtitle = Spotify.artists(o);
                    break;
            }
            return i;
        }
    }

    static TextView section(Context c, String title) {
        TextView t = Ui.text(c, title, 22, Ui.bold, Ui.text());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(16), Ui.dp(30), Ui.dp(16), Ui.dp(14));
        t.setLayoutParams(lp);
        return t;
    }

    static TextView caption(Context c, String text) {
        TextView t = Ui.text(c, text, 13, Ui.regular, Ui.text2());
        t.setSingleLine(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(20), Ui.dp(4), Ui.dp(20), Ui.dp(8));
        t.setLayoutParams(lp);
        return t;
    }

    /** Horizontal rail: returns the inner LinearLayout (already wrapped in a scroller added to parent). */
    static LinearLayout rail(LinearLayout parent, int spacingDp) {
        HorizontalScrollView hs = new HorizontalScrollView(parent.getContext());
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setClipToPadding(false);
        LinearLayout row = new LinearLayout(parent.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(16), 0, Ui.dp(16 - spacingDp), 0);
        hs.addView(row);
        parent.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setTag(spacingDp);
        return row;
    }

    private static LinearLayout.LayoutParams railLp(LinearLayout row, int w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(row.getTag() instanceof Integer ? (Integer) row.getTag() : 8);
        return lp;
    }

    /** Card sizes: poster ~58% of the width, squares ~38%. */
    /** Width pages can actually use: the screen minus the foldable's right-hand rail. */
    static int contentW(MainActivity m) { return Ui.screenW - (m == null ? 0 : m.sideInset() + m.contentInsetPx()); }

    static int posterW() { return Math.min(Ui.dp(300), (int) (Math.min(Ui.screenW, Ui.dp(700)) * (Ui.cover() ? 0.5f : 0.585f))); }
    static int squareDp() { return (int) (Math.min(Ui.dp(180), Math.min(Ui.screenW, Ui.dp(700)) * (Ui.cover() ? 0.32f : 0.38f)) / Ui.density); }

    /** Tall poster card with dark bottom gradient and caption (home "greeting" row). */
    static View posterCard(Context c, Item it, View.OnClickListener click) {
        int w = posterW(), h = (int) (w * (Ui.cover() ? 1.05f : 1.33f));
        FrameLayout f = new FrameLayout(c);
        Img.ArtView art = new Img.ArtView(c, 18);
        art.setUrl(it.raw != null ? Spotify.image(it.raw, 640) : it.image);
        f.addView(art, new FrameLayout.LayoutParams(w, h));
        View shade = new View(c) {
            final Paint p = new Paint();
            final RectF r = new RectF();
            @Override protected void onDraw(Canvas cv) {
                // soft, light fade (not a heavy black band)
                p.setShader(new LinearGradient(0, getHeight() * 0.55f, 0, getHeight(), new int[]{0x00000000, 0x33000000, 0x8C000000},
                        new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                r.set(0, 0, getWidth(), getHeight());
                cv.drawRoundRect(r, Ui.dpf(14), Ui.dpf(14), p);
            }
        };
        f.addView(shade, new FrameLayout.LayoutParams(w, h));
        LinearLayout txt = new LinearLayout(c);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(Ui.dp(16), 0, Ui.dp(14), Ui.dp(16));
        LinearLayout capRow = new LinearLayout(c);
        capRow.setGravity(Gravity.CENTER_VERTICAL);
        Icons.IconView ic = new Icons.IconView(c, it.icon != 0 ? it.icon : Icons.CLOCK, 12, 0xB3FFFFFF);
        capRow.addView(ic, new LinearLayout.LayoutParams(Ui.dp(14), Ui.dp(14)));
        TextView cap = Ui.text(c, it.caption == null ? "" : it.caption, 14, Ui.medium, 0xB3FFFFFF);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = Ui.dp(3);
        capRow.addView(cap, clp);
        txt.addView(capRow);
        TextView t = Ui.text(c, it.title, 16, Ui.semibold, 0xFFFFFFFF);
        t.setSingleLine(false);
        t.setMaxLines(2);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(4);
        txt.addView(t, tlp);
        f.addView(txt, new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        f.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        f.setOnClickListener(click);
        Ui.press(f, 0.96f);
        return f;
    }

    /** Square artwork with title / subtitle / count (playlist rails). */
    static View squareCard(Context c, Item it, int sizeDp, View.OnClickListener click) {
        int s = Ui.dp(sizeDp);
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        Img.ArtView art = new Img.ArtView(c, 9);
        art.circle = "artist".equals(it.type);
        if (art.circle) art.placeholderIcon = Icons.ARTIST;
        art.setUrl(it.image);
        v.addView(art, new LinearLayout.LayoutParams(s, s));
        TextView t = Ui.text(c, it.title, 12.5f, Ui.medium, Ui.text());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(7);
        if (art.circle) t.setGravity(Gravity.CENTER);
        v.addView(t, lp);
        if (it.subtitle != null && it.subtitle.length() > 0) {
            TextView st = Ui.text(c, it.subtitle, 11.5f, Ui.regular, Ui.text2());
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(s, ViewGroup.LayoutParams.WRAP_CONTENT);
            sl.topMargin = Ui.dp(2);
            v.addView(st, sl);
        }
        if (it.count >= 0) {
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(new Icons.IconView(c, Icons.HEADPHONES, 10, Ui.text2()), new LinearLayout.LayoutParams(Ui.dp(12), Ui.dp(12)));
            TextView n = Ui.text(c, c.getString(R.string.n_tracks, it.count), 11, Ui.regular, Ui.text2());
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nl.leftMargin = Ui.dp(2);
            row.addView(n, nl);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(s, ViewGroup.LayoutParams.WRAP_CONTENT);
            rl.topMargin = Ui.dp(2);
            v.addView(row, rl);
        }
        v.setOnClickListener(click);
        Ui.press(v, 0.96f);
        return v;
    }

    static void addToRail(LinearLayout row, View card, int widthPx) {
        row.addView(card, railLp(row, widthPx));
        // cards rise in one after another; done per card so a row can't be left half-animated
        int i = row.getChildCount() - 1;
        if (i < 8) {
            card.setAlpha(0f);
            card.setTranslationY(Ui.dpf(18));
            card.animate().alpha(1f).translationY(0f).setStartDelay(i * 35L).setDuration(380).setInterpolator(Ui.EMPHASIZED)
                    .withEndAction(() -> { card.setAlpha(1f); card.setTranslationY(0f); }).start();
        }
    }

    /** Colourful gradient tile with a centred label (charts row). */
    static View gradientTile(Context c, String label, String caption, int c1, int c2, int sizeDp, View.OnClickListener click) {
        int s = Ui.dp(sizeDp);
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        FrameLayout tile = new FrameLayout(c);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        g.setCornerRadius(Ui.dpf(9));
        tile.setBackground(g);
        TextView t = Ui.text(c, label, 16, Ui.heavy, 0xFFFFFFFF);
        t.setSingleLine(false);
        t.setMaxLines(2);
        t.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        flp.leftMargin = flp.rightMargin = Ui.dp(8);
        tile.addView(t, flp);
        v.addView(tile, new LinearLayout.LayoutParams(s, s));
        TextView cap = Ui.text(c, caption, 12, Ui.medium, Ui.text());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(7);
        v.addView(cap, lp);
        v.setOnClickListener(click);
        Ui.press(v, 0.96f);
        return v;
    }

    /** Track row: artwork, title, artist and a trailing "more" glyph. */
    static View trackRow(Context c, Item it, int index, View.OnClickListener click, View.OnClickListener more) {
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(16), Ui.dp(6), Ui.dp(6), Ui.dp(6));
        if (index > 0) {
            TextView n = Ui.text(c, String.valueOf(index), 14, Ui.medium, Ui.text2());
            n.setGravity(Gravity.CENTER);
            r.addView(n, new LinearLayout.LayoutParams(Ui.dp(28), Ui.dp(44)));
        } else {
            Img.ArtView art = new Img.ArtView(c, 6);
            art.setUrl(it.image);
            r.addView(art, new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
        }
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(12), 0, Ui.dp(4), 0);
        TextView t = Ui.text(c, it.title, 15, Ui.regular, Ui.text());
        col.addView(t, MW);
        TextView s = Ui.text(c, it.subtitle, 12.5f, Ui.regular, Ui.text2());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = Ui.dp(3);
        col.addView(s, sl);
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Icons.IconView m = new Icons.IconView(c, Icons.MORE, 18, Ui.text2());
        r.addView(m, new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(40)));
        if (more != null) m.setOnClickListener(more);
        r.setBackground(ripple());
        r.setOnClickListener(click);
        return r;
    }

    /** Paged columns of three track rows ("made for you" list). */
    static void trackColumns(LinearLayout parent, List<Item> items, java.util.function.Consumer<Integer> play) {
        LinearLayout row = rail(parent, 0);
        row.setPadding(0, 0, Ui.dp(16), 0);
        // song columns: most of a phone's width, but capped so unfolded screens show several side by side
        int colW = Math.min((int) (Ui.screenW * 0.88f), Ui.dp(460));
        LinearLayout col = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % 3 == 0) {
                col = new LinearLayout(parent.getContext());
                col.setOrientation(LinearLayout.VERTICAL);
                row.addView(col, new LinearLayout.LayoutParams(colW, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final int idx = i;
            col.addView(trackRow(parent.getContext(), items.get(i), 0, v -> play.accept(idx), null), MW);
        }
    }

    static GradientDrawable chipBg(int color) { return Ui.round(color, 100); }

    /** Selectable pill chips (explore filter row). */
    static final class Chips extends HorizontalScrollView {
        interface OnPick { void pick(int i); }
        final LinearLayout row;
        final List<TextView> views = new ArrayList<>();
        int selected;

        Chips(Context c, String[] labels, OnPick pick) {
            super(c);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            row = new LinearLayout(c);
            row.setPadding(Ui.dp(16), 0, Ui.dp(8), 0);
            addView(row);
            for (int i = 0; i < labels.length; i++) {
                TextView t = Ui.text(c, labels[i], 14, Ui.medium, Ui.text());
                t.setGravity(Gravity.CENTER);
                t.setPadding(Ui.dp(16), 0, Ui.dp(16), 0);
                t.setMinWidth(Ui.dp(62));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(37));
                lp.rightMargin = Ui.dp(8);
                final int idx = i;
                t.setOnClickListener(v -> { set(idx, true); pick.pick(idx); });
                Ui.press(t, 0.94f);
                row.addView(t, lp);
                views.add(t);
            }
            set(0, false);
        }

        void set(int i, boolean animate) {
            int prev = selected;
            selected = i;
            for (int k = 0; k < views.size(); k++) {
                TextView t = views.get(k);
                boolean on = k == i;
                if (animate && (k == i || k == prev)) {
                    final int from = k == prev ? Ui.ACCENT : Ui.chip(), to = on ? Ui.ACCENT : Ui.chip();
                    final int tf = k == prev ? 0xFFFFFFFF : Ui.text(), tt = on ? 0xFFFFFFFF : Ui.text();
                    Ui.animate(0f, 1f, 220, Ui.STANDARD, a -> {
                        float f = (float) a.getAnimatedValue();
                        t.setBackground(chipBg(Ui.mix(from, to, f)));
                        t.setTextColor(Ui.mix(tf, tt, f));
                    });
                } else {
                    t.setBackground(chipBg(on ? Ui.ACCENT : Ui.chip()));
                    t.setTextColor(on ? 0xFFFFFFFF : Ui.text());
                }
                t.setTypeface(on ? Ui.semibold : Ui.medium);
            }
        }
    }

    /** Grouped (inset) list section like the settings screen. */
    static final class Group extends LinearLayout {
        final LinearLayout card;
        int rows;

        Group(Context c, String header) {
            super(c);
            setOrientation(VERTICAL);
            if (header != null) {
                TextView h = Ui.text(c, header, 14, Ui.regular, Ui.text2());
                h.setPadding(Ui.dp(33), Ui.dp(24), Ui.dp(32), Ui.dp(8));
                addView(h, MW);
            } else {
                View sp = new View(c);
                addView(sp, new LayoutParams(1, Ui.dp(14)));
            }
            card = new LinearLayout(c);
            card.setOrientation(VERTICAL);
            card.setBackground(Ui.round(Ui.card(), 16));
            card.setClipToOutline(true);
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = lp.rightMargin = Ui.dp(16);
            addView(card, lp);
        }

        View row(int icon, int iconColor, String title, String value, boolean chevron, View.OnClickListener click) {
            Context c = getContext();
            if (rows++ > 0) {
                View sep = new View(c);
                sep.setBackgroundColor(Ui.separator());
                LayoutParams sl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(0.5f)));
                sl.leftMargin = icon != 0 ? Ui.dp(52) : Ui.dp(16);
                card.addView(sep, sl);
            }
            LinearLayout r = new LinearLayout(c);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(Ui.dp(16), 0, Ui.dp(14), 0);
            r.setMinimumHeight(Ui.dp(53));
            if (icon != 0) {
                Icons.IconView iv = new Icons.IconView(c, icon, 21, iconColor);
                LayoutParams il = new LayoutParams(Ui.dp(22), Ui.dp(22));
                il.rightMargin = Ui.dp(14);
                r.addView(iv, il);
            }
            TextView t = Ui.text(c, title, 17, Ui.regular, Ui.text());
            r.addView(t, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (value != null) {
                TextView v = Ui.text(c, value, 15, Ui.regular, Ui.text2());
                v.setMaxWidth(Ui.dp(180));
                LayoutParams vl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                vl.leftMargin = Ui.dp(8);
                r.addView(v, vl);
                r.setTag(v);
            }
            if (chevron) {
                Icons.IconView ch = new Icons.IconView(c, Icons.CHEVRON, 15, Ui.text3());
                LayoutParams cl = new LayoutParams(Ui.dp(18), Ui.dp(18));
                cl.leftMargin = Ui.dp(6);
                r.addView(ch, cl);
            }
            if (click != null) {
                r.setBackground(ripple());
                r.setOnClickListener(click);
            }
            card.addView(r, MW);
            return r;
        }

        /** Row with a trailing checkmark (radio-style option). */
        View option(String title, boolean checked, View.OnClickListener click) {
            View r = row(0, 0, title, null, false, click);
            Icons.IconView ck = new Icons.IconView(getContext(), Icons.CHECK, 18, Ui.ACCENT);
            ck.setVisibility(checked ? VISIBLE : INVISIBLE);
            ((LinearLayout) r).addView(ck, new LayoutParams(Ui.dp(22), Ui.dp(22)));
            return r;
        }
    }

    static android.graphics.drawable.Drawable ripple() {
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ui.pressed()), null,
                new android.graphics.drawable.ColorDrawable(0xFFFFFFFF));
    }

    /** Records a view's own drawing so a glass thumb can refract it. */
    private static final class Track {
        final android.graphics.RenderNode node = new android.graphics.RenderNode("track");
        android.graphics.RecordingCanvas begin(int w, int h) { node.setPosition(0, 0, w, h); return node.beginRecording(w, h); }
        void end() { node.endRecording(); }
    }

    /**
     * Switch whose thumb turns into a glass lens while pressed/dragged
     * magnifying and refracting the track beneath it, then settles back to a white knob.
     */
    /**
     * One glass knob for switches and sliders, lift l 0 → 1: opaque white glass (the resting knob, same shape and
     * shadow) → a clear magnifying lens. Every value blends; the glass is never faded in or out.
     * Sized for a ~26–46dp knob: no dispersion (it showed as a coloured ring at this size), modest bend.
     */
    static void liftKnob(Glass.Surface g, RectF knob, float l, float magMax) {
        float k = Ui.clamp(l, 0f, 1f);
        g.shapes(knob, knob.height() / 2f, null, 0, null, 0);
        g.alpha = 1f;
        g.tint = Ui.mix(0xFFFFFFFF, Ui.dark ? 0x26FFFFFF : 0x14FFFFFF, k);
        g.refractDp = 2f + 6f * k;
        g.edgeDp = 8f + 4f * k;
        g.outerDp = 4f;
        g.outerRefractDp = 1f + 1.5f * k;
        g.chroma = 0f;
        g.rim = 0.3f + 0.25f * k;
        g.mag = 1f + magMax * l;
        g.magX = knob.centerX();
        g.magY = knob.centerY();
        g.shadowAlpha = 0.16f + 0.06f * k;
    }

    static final class Toggle extends View {
        boolean on;
        float pos;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF(), knob = new RectF();
        java.util.function.Consumer<Boolean> change;
        private final Ui.Spring lens = new Ui.Spring(520f, 0.72f, 0.002f);
        private final Ui.Spring move = new Ui.Spring(420f, 0.78f, 0.002f);
        private final Glass.Surface glass = new Glass.Surface();
        private final Track track = new Track();
        private float downX;
        private boolean dragged;

        Toggle(Context c, boolean on) {
            super(c);
            this.on = on;
            pos = on ? 1 : 0;
            move.snap(pos);
            lens.onUpdate = this::invalidate;
            move.onUpdate = () -> { pos = move.value; invalidate(); };
            glass.blur = false;
            glass.refractDp = 8f;
            glass.edgeDp = 10f;
            glass.chroma = 0.6f;
            glass.rim = 0.55f;
            glass.tint = 0x14FFFFFF;
            glass.shadowAlpha = 0.18f;
            glass.strokeColor = 0x1A000000;
        }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(Ui.dp(56), Ui.dp(34)); }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    downX = e.getX();
                    dragged = false;
                    lens.to(1f);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - downX;
                    if (Math.abs(dx) > Ui.dpf(4)) dragged = true;
                    if (dragged) move.snap(Ui.clamp((on ? 1f : 0f) + dx / (getWidth() - getHeight()), 0f, 1f));
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    boolean next = dragged ? pos > 0.5f : !on;
                    if (e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) next = on;
                    boolean changed = next != on;
                    on = next;
                    move.to(on ? 1f : 0f);
                    postDelayed(() -> lens.to(0f), 120);
                    if (changed && change != null) change.accept(on);
                    return true;
                }
            }
            return true;
        }

        private void drawTrack(Canvas c) {
            r.set(Ui.dpf(2), Ui.dpf(2), getWidth() - Ui.dpf(2), getHeight() - Ui.dpf(2));
            p.setColor(Ui.mix(Ui.dark ? 0xFF39393D : 0xFFE9E9EA, 0xFF34C759, pos));
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, p);
        }

        @Override protected void onDraw(Canvas c) {
            float l = lens.value;
            float d = getHeight() - Ui.dpf(8);
            float w = d * Ui.lerp(1f, 1.45f, l), h = d * Ui.lerp(1f, 1.22f, l);
            float cx = Ui.lerp(Ui.dpf(4) + d / 2f, getWidth() - Ui.dpf(4) - d / 2f, pos);
            knob.set(cx - w / 2f, getHeight() / 2f - h / 2f, cx + w / 2f, getHeight() / 2f + h / 2f);
            if (c.isHardwareAccelerated() && Glass.shadersOk && Ui.glass) {
                android.graphics.RecordingCanvas rc = track.begin(getWidth(), getHeight());
                try { drawTrack(rc); } finally { track.end(); }
                c.drawRenderNode(track.node);
                // the knob IS one glass surface: an opaque white glass knob at rest that clears into a lens
                // as it lifts (never a white disc fading out while a separate glass fades in)
                liftKnob(glass, knob, l, 0.12f);
                glass.extra = track.node;
                glass.draw(c, this, null);
                return;
            }
            drawTrack(c);
            p.setColor(0xFFFFFFFF);
            p.setShadowLayer(Ui.dpf(3), 0, Ui.dpf(1.5f), 0x33000000);
            c.drawRoundRect(knob, knob.height() / 2, knob.height() / 2, p);
            p.clearShadowLayer();
        }
    }

    /** Slider with a white pill thumb that becomes a glass lens while dragging. */
    static final class GlassSlider extends View {
        float value;
        int steps;
        java.util.function.Consumer<Float> change;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF(), knob = new RectF();
        private final Ui.Spring lens = new Ui.Spring(520f, 0.72f, 0.002f);
        private final Glass.Surface glass = new Glass.Surface();
        private final Track track = new Track();

        GlassSlider(Context c, float value, int steps) {
            super(c);
            this.value = value;
            this.steps = steps;
            lens.onUpdate = this::invalidate;
            glass.blur = false;
            glass.refractDp = 10f;
            glass.edgeDp = 12f;
            glass.chroma = 0.7f;
            glass.rim = 0.55f;
            glass.tint = 0x10FFFFFF;
            glass.shadowAlpha = 0.18f;
            glass.strokeColor = 0x1A000000;
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(40));
        }

        private float pad() { return Ui.dpf(18); }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            float v = Ui.clamp((e.getX() - pad()) / (getWidth() - pad() * 2), 0f, 1f);
            if (steps > 0) v = Math.round(v * steps) / (float) steps;
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    lens.to(1f);
                    // fall through
                case android.view.MotionEvent.ACTION_MOVE:
                    if (v != value) { value = v; if (change != null) change.accept(value); }
                    invalidate();
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    lens.to(0f);
                    return true;
            }
            return true;
        }

        private void drawTrack(Canvas c) {
            float cy = getHeight() / 2f, x = pad() + (getWidth() - pad() * 2) * value;
            r.set(pad(), cy - Ui.dpf(2.5f), getWidth() - pad(), cy + Ui.dpf(2.5f));
            p.setColor(Ui.dark ? 0xFF3A3A3C : 0xFFE5E5EA);
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, p);
            r.right = x;
            p.setColor(Ui.ACCENT);
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, p);
        }

        @Override protected void onDraw(Canvas c) {
            float l = lens.value;
            float cx = pad() + (getWidth() - pad() * 2) * value, cy = getHeight() / 2f;
            float w = Ui.lerp(Ui.dpf(30), Ui.dpf(46), l), h = Ui.lerp(Ui.dpf(20), Ui.dpf(32), l);
            knob.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
            if (c.isHardwareAccelerated() && Glass.shadersOk && Ui.glass) {
                android.graphics.RecordingCanvas rc = track.begin(getWidth(), getHeight());
                try { drawTrack(rc); } finally { track.end(); }
                c.drawRenderNode(track.node);
                liftKnob(glass, knob, l, 0.16f);
                glass.extra = track.node;
                glass.draw(c, this, null);
                return;
            }
            drawTrack(c);
            p.setColor(0xFFFFFFFF);
            p.setShadowLayer(Ui.dpf(3), 0, Ui.dpf(1), 0x33000000);
            c.drawRoundRect(knob, h / 2, h / 2, p);
            p.clearShadowLayer();
        }
    }
    /** Accent arc spinner. */
    static final class Spinner extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        Spinner(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(Ui.dpf(2.6f));
            p.setColor(Ui.ACCENT);
        }
        @Override protected void onDraw(Canvas c) {
            float s = Ui.dpf(22);
            r.set(getWidth() / 2f - s / 2, getHeight() / 2f - s / 2, getWidth() / 2f + s / 2, getHeight() / 2f + s / 2);
            long t = android.os.SystemClock.uptimeMillis();
            float start = (t % 900) / 900f * 360f;
            float sweep = 40 + 200 * (float) Math.abs(Math.sin(t / 600.0));
            c.drawArc(r, start, sweep, false, p);
            if (isShown()) postInvalidateOnAnimation();
        }
    }

    static View spinner(Context c, int heightDp) {
        Spinner s = new Spinner(c);
        s.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(heightDp)));
        return s;
    }

    /** Centred message card with an icon and optional button (sign-in prompts, errors). */
    static View messageCard(Context c, int icon, String message, String button, View.OnClickListener click) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(20), Ui.dp(24), Ui.dp(20), Ui.dp(22));
        box.setBackground(Ui.round(Ui.dark ? 0xFF1C1C1E : 0xFFFFFFFF, 20));
        box.setElevation(Ui.dpf(1));
        Icons.IconView iv = new Icons.IconView(c, icon, 30, Ui.text());
        box.addView(iv, new LinearLayout.LayoutParams(Ui.dp(34), Ui.dp(34)));
        TextView t = Ui.text(c, message, 14, Ui.regular, Ui.text());
        t.setSingleLine(false);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(12);
        box.addView(t, tl);
        if (button != null) {
            TextView b = Ui.text(c, button, 15, Ui.semibold, 0xFFFFFFFF);
            b.setGravity(Gravity.CENTER);
            b.setPadding(Ui.dp(22), 0, Ui.dp(22), 0);
            b.setBackground(Ui.round(Ui.ACCENT, 100));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(42));
            bl.topMargin = Ui.dp(16);
            box.addView(b, bl);
            b.setOnClickListener(click);
            Ui.press(b, 0.95f);
        }
        FrameLayout wrap = new FrameLayout(c);
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(Math.min(Ui.dp(300), Ui.screenW - Ui.dp(64)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
        wrap.addView(box, wl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(24);
        wrap.setLayoutParams(lp);
        return wrap;
    }

    static String nonEmpty(String s, String alt) { return TextUtils.isEmpty(s) ? alt : s; }
}
