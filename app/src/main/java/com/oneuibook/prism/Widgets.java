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
            if ((i.id == null || i.id.isEmpty()) && i.uri != null && i.uri.startsWith("spotify:")) {
                String[] parts = i.uri.split(":");
                if (parts.length >= 3) {
                    if (i.type == null || i.type.isEmpty()) i.type = parts[1];
                    i.id = parts[2];
                }
            }
            if ((i.uri == null || i.uri.isEmpty()) && i.id != null && !i.id.isEmpty() && i.type != null && !i.type.isEmpty()) {
                i.uri = "spotify:" + i.type + ":" + i.id;
            }
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
        TextView t = Ui.text(c, title, 20.5f, Ui.bold, Ui.text());
        t.setLetterSpacing(-0.022f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int side = Ui.dp(Ui.gStyle ? 22 : 18);
        lp.setMargins(side, Ui.dp(Ui.gStyle ? 28 : 24), side, Ui.dp(Ui.gStyle ? 14 : 11));
        t.setLayoutParams(lp);
        return t;
    }

    static TextView caption(Context c, String text) {
        TextView t = Ui.text(c, text, 13, Ui.regular, Ui.text2());
        t.setSingleLine(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int side = Ui.dp(Ui.gStyle ? 24 : 20);
        lp.setMargins(side, Ui.dp(Ui.gStyle ? 8 : 4), side, Ui.dp(Ui.gStyle ? 12 : 8));
        t.setLayoutParams(lp);
        return t;
    }

    /** Horizontal rail: returns the inner LinearLayout (already wrapped in a scroller added to parent). */
    static LinearLayout rail(LinearLayout parent, int spacingDp) {
        int gap = Ui.gStyle ? spacingDp + 4 : spacingDp;
        int pad = Ui.gStyle ? 20 : 16;
        HorizontalScrollView hs = new HorizontalScrollView(parent.getContext());
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setClipToPadding(false);
        LinearLayout row = new LinearLayout(parent.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(pad), 0, Ui.dp(Math.max(4, pad - gap)), 0);
        hs.addView(row);
        parent.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setTag(gap);
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

    /** Never taller than ~40% of the screen: on short-and-wide screens (unfolded foldable, landscape tablet) the rows below stay in view. */
    static int posterW() {
        boolean desktopTablet = Ui.screenW / Ui.density >= 840f;
        float ratioW = Ui.cover() ? 0.54f : (desktopTablet ? 0.34f : 0.585f);
        int capW = desktopTablet ? Ui.dp(248) : Ui.dp(300);
        return Math.min((int) (Ui.screenH * 0.40f / 1.33f), Math.min(capW, (int) (Math.min(Ui.screenW, Ui.dp(760)) * ratioW)));
    }
    static int squareDp() {
        boolean desktopTablet = Ui.screenW / Ui.density >= 840f;
        float ratioW = Ui.cover() ? 0.36f : (desktopTablet ? 0.22f : 0.38f);
        int capDp = desktopTablet ? 156 : 184;
        return (int) (Math.min(Ui.screenH * 0.25f, Math.min(Ui.dp(capDp), Math.min(Ui.screenW, Ui.dp(760)) * ratioW)) / Ui.density);
    }

    /** Tall poster card with cinematic multi-stop bottom gradient, translucent date pill, and crisp rim (home "greeting" row). */
    static View posterCard(Context c, Item it, View.OnClickListener click) {
        int w = posterW(), h = (int) (w * (Ui.cover() ? 1.08f : 1.30f));
        float rad = Ui.gStyle ? 24f : 20f;
        FrameLayout f = new FrameLayout(c);
        f.setElevation(Ui.dpf(Ui.dark ? 2f : 4f));
        f.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dpf(rad));
            }
        });
        f.setClipToOutline(true);
        Img.ArtView art = new Img.ArtView(c, rad);
        art.setUrl(it.raw != null ? Spotify.image(it.raw, 640) : it.image);
        f.addView(art, new FrameLayout.LayoutParams(w, h));
        View shade = new View(c) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
            final RectF r = new RectF();
            @Override protected void onDraw(Canvas cv) {
                float radPx = Ui.dpf(rad);
                p.setShader(new LinearGradient(0, getHeight() * 0.38f, 0, getHeight(),
                        new int[]{0x00000000, 0x1A000000, 0x70000000, 0xBF000000},
                        new float[]{0f, 0.36f, 0.72f, 1f}, Shader.TileMode.CLAMP));
                r.set(0, 0, getWidth(), getHeight());
                cv.drawRoundRect(r, radPx, radPx, p);
                rim.setStyle(Paint.Style.STROKE);
                rim.setStrokeWidth(Math.max(1f, Ui.dpf(0.85f)));
                rim.setColor(0x2EFFFFFF);
                float half = rim.getStrokeWidth() * 0.5f;
                r.inset(half, half);
                cv.drawRoundRect(r, Math.max(0f, radPx - half), Math.max(0f, radPx - half), rim);
            }
        };
        f.addView(shade, new FrameLayout.LayoutParams(w, h));
        LinearLayout txt = new LinearLayout(c);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(Ui.dp(15), 0, Ui.dp(14), Ui.dp(15));
        if (it.caption != null && !it.caption.isEmpty()) {
            LinearLayout capRow = new LinearLayout(c);
            capRow.setGravity(Gravity.CENTER_VERTICAL);
            capRow.setPadding(Ui.dp(8), Ui.dp(3.5f), Ui.dp(9), Ui.dp(3.5f));
            capRow.setBackground(Ui.round(0x40000000, 100));
            Icons.IconView ic = new Icons.IconView(c, it.icon != 0 ? it.icon : Icons.CLOCK, 11, 0xE6FFFFFF);
            capRow.addView(ic, new LinearLayout.LayoutParams(Ui.dp(13), Ui.dp(13)));
            TextView cap = Ui.text(c, it.caption, 11.5f, Ui.semibold, 0xEBFFFFFF);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = Ui.dp(4);
            capRow.addView(cap, clp);
            txt.addView(capRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        TextView t = Ui.text(c, it.title, 17f, Ui.bold, 0xFFFFFFFF);
        t.setLetterSpacing(-0.018f);
        t.setSingleLine(false);
        t.setMaxLines(2);
        t.setLineSpacing(0f, 1.08f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(6);
        txt.addView(t, tlp);
        f.addView(txt, new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        f.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        f.setOnClickListener(click);
        Ui.press(f, Ui.gStyle ? 0.98f : 0.96f);
        return f;
    }

    /** Square artwork with subtle framed border & refined title / subtitle / count (playlist & album rails). */
    static View squareCard(Context c, Item it, int sizeDp, View.OnClickListener click) {
        int s = Ui.dp(sizeDp);
        float rad = Ui.gStyle ? 20f : 13f;
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        FrameLayout frame = new FrameLayout(c);
        Img.ArtView art = new Img.ArtView(c, rad);
        art.circle = "artist".equals(it.type) || "lartist".equals(it.type) || "ytm_artist".equals(it.type);
        if (art.circle) art.placeholderIcon = Icons.ARTIST;
        art.setUrl(it.image);
        frame.addView(art, new FrameLayout.LayoutParams(s, s));
        if (!art.circle) {
            View border = new View(c);
            GradientDrawable bd = Ui.round(0x00000000, rad);
            bd.setStroke(Math.max(1, Ui.dp(0.7f)), Ui.dark ? 0x1EFFFFFF : 0x12000000);
            border.setBackground(bd);
            frame.addView(border, new FrameLayout.LayoutParams(s, s));
        }
        v.addView(frame, new LinearLayout.LayoutParams(s, s));
        TextView t = Ui.text(c, it.title, 13.5f, Ui.semibold, Ui.text());
        t.setLetterSpacing(-0.012f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(8);
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
            nl.leftMargin = Ui.dp(3);
            row.addView(n, nl);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(s, ViewGroup.LayoutParams.WRAP_CONTENT);
            rl.topMargin = Ui.dp(2);
            v.addView(row, rl);
        }
        v.setOnClickListener(click);
        Ui.press(v, Ui.gStyle ? 0.98f : 0.96f);
        return v;
    }

    static void addToRail(LinearLayout row, View card, int widthPx) {
        addToRail(row, card, widthPx, true);
    }

    static void addToRail(LinearLayout row, View card, int widthPx, boolean animate) {
        row.addView(card, railLp(row, widthPx));
        // cards rise in one after another; done per card so a row can't be left half-animated
        int i = row.getChildCount() - 1;
        if (animate && i < 8) {
            card.setAlpha(0f);
            card.setTranslationY(Ui.dpf(18));
            card.animate().alpha(1f).translationY(0f).setStartDelay(i * 35L).setDuration(380)
                    .setInterpolator(Ui.gStyle ? Ui.ONEUI_SINE_80 : Ui.EMPHASIZED)
                    .withEndAction(() -> { card.setAlpha(1f); card.setTranslationY(0f); }).start();
        }
    }

    /** Colourful gradient tile with subtle corner watermark & refined label (charts row). */
    static View gradientTile(Context c, String label, String caption, int c1, int c2, int sizeDp, View.OnClickListener click) {
        int s = Ui.dp(sizeDp);
        float rad = Ui.gStyle ? 20f : 13f;
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        FrameLayout tile = new FrameLayout(c);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        g.setCornerRadius(Ui.dpf(rad));
        g.setStroke(Math.max(1, Ui.dp(0.8f)), 0x2EFFFFFF);
        tile.setBackground(g);
        tile.setClipToOutline(true);
        Icons.IconView wm = new Icons.IconView(c, Icons.WAVE, 44, 0x24FFFFFF);
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(Ui.dp(52), Ui.dp(52), Gravity.TOP | Gravity.END);
        wlp.setMargins(0, Ui.dp(6), Ui.dp(6), 0);
        tile.addView(wm, wlp);
        TextView t = Ui.text(c, label, 16, Ui.heavy, 0xFFFFFFFF);
        t.setLetterSpacing(-0.015f);
        t.setSingleLine(false);
        t.setMaxLines(2);
        t.setGravity(Gravity.START | Gravity.BOTTOM);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        flp.setMargins(Ui.dp(12), 0, Ui.dp(12), Ui.dp(12));
        tile.addView(t, flp);
        v.addView(tile, new LinearLayout.LayoutParams(s, s));
        if (caption != null && !caption.isEmpty() && !caption.equals(label)) {
            TextView cap = Ui.text(c, caption, 13f, Ui.semibold, Ui.text());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(8);
            v.addView(cap, lp);
        }
        v.setOnClickListener(click);
        Ui.press(v, 0.96f);
        return v;
    }

    /** Track row: artwork, title, artist and a trailing "more" glyph. */
    static View trackRow(Context c, Item it, int index, View.OnClickListener click, View.OnClickListener more) {
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int hPad = Ui.dp(Ui.gStyle ? 20 : 16);
        int vPad = Ui.dp(Ui.gStyle ? 10 : 7);
        r.setPadding(hPad, vPad, Ui.dp(Ui.gStyle ? 10 : 6), vPad);
        if (index > 0) {
            TextView n = Ui.text(c, String.valueOf(index), 14, Ui.semibold, Ui.text2());
            n.setGravity(Gravity.CENTER);
            r.addView(n, new LinearLayout.LayoutParams(Ui.dp(28), Ui.dp(46)));
        } else {
            Img.ArtView art = new Img.ArtView(c, Ui.gStyle ? 13 : 8);
            art.setUrl(it.image);
            r.addView(art, new LinearLayout.LayoutParams(Ui.dp(46), Ui.dp(46)));
        }
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(Ui.gStyle ? 15 : 13), 0, Ui.dp(6), 0);
        TextView t = Ui.text(c, it.title, 15, Ui.medium, Ui.text());
        t.setLetterSpacing(-0.01f);
        col.addView(t, MW);
        TextView s = Ui.text(c, it.subtitle, 12.5f, Ui.regular, Ui.text2());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = Ui.dp(Ui.gStyle ? 3.5f : 2.5f);
        col.addView(s, sl);
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (more != null) {
            Icons.IconView m = new Icons.IconView(c, Icons.MORE, 18, Ui.text2());
            r.addView(m, new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(40)));
            m.setOnClickListener(more);
        } else {
            r.setPadding(hPad, vPad, hPad, vPad);
        }
        r.setBackground(ripple());
        r.setOnClickListener(click);
        if (Ui.gStyle) Ui.press(r, 0.99f);
        return r;
    }

    /** Card-framed columns of three track rows ("made for you" list; denser PC-like columns on wide tablets). */
    static void trackColumns(LinearLayout parent, List<Item> items, java.util.function.Consumer<Integer> play) {
        LinearLayout row = rail(parent, 10);
        boolean desktopTablet = Ui.screenW / Ui.density >= 840f;
        int colW = desktopTablet ? Ui.dp(344) : Math.min((int) (Ui.screenW * 0.85f), Ui.dp(420));
        LinearLayout col = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % 3 == 0) {
                col = new LinearLayout(parent.getContext());
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(0, Ui.dp(4), 0, Ui.dp(4));
                GradientDrawable bg = Ui.round(Ui.card(), Ui.gStyle ? 22f : 15f);
                bg.setStroke(Math.max(1, Ui.dp(0.6f)), Ui.dark ? 0x18FFFFFF : 0x0E000000);
                col.setBackground(bg);
                col.setClipToOutline(true);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(colW, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.rightMargin = Ui.dp(10);
                row.addView(col, clp);
            }
            final int idx = i;
            if (i % 3 != 0) {
                View sep = new View(parent.getContext());
                sep.setBackgroundColor(Ui.separator());
                LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(0.5f)));
                sl.leftMargin = Ui.dp(74);
                sl.rightMargin = Ui.dp(14);
                col.addView(sep, sl);
            }
            col.addView(trackRow(parent.getContext(), items.get(i), 0, v -> play.accept(idx), null), MW);
        }
    }

    static GradientDrawable chipBg(int color) {
        GradientDrawable g = Ui.round(color, 100);
        if (Ui.gStyle && color != Ui.ACCENT) g.setStroke(Math.max(1, Ui.dp(0.8f)), Ui.dark ? 0x29FFFFFF : 0x1A000000);
        return g;
    }

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
                TextView h = Ui.text(c, header, Ui.gStyle ? 13.5f : 14f, Ui.gStyle ? Ui.semibold : Ui.regular, Ui.text2());
                h.setPadding(Ui.dp(Ui.gStyle ? 28 : 33), Ui.dp(Ui.gStyle ? 26 : 24), Ui.dp(28), Ui.dp(Ui.gStyle ? 10 : 8));
                addView(h, MW);
            } else {
                View sp = new View(c);
                addView(sp, new LayoutParams(1, Ui.dp(Ui.gStyle ? 20 : 14)));
            }
            card = new LinearLayout(c);
            card.setOrientation(VERTICAL);
            if (Ui.gStyle) card.setPadding(0, Ui.dp(4), 0, Ui.dp(4));
            GradientDrawable cardBg = Ui.round(Ui.card(), Ui.cardRadius());
            cardBg.setStroke(Math.max(1, Ui.dp(0.6f)), Ui.dark ? 0x18FFFFFF : 0x0E000000);
            card.setBackground(cardBg);
            card.setClipToOutline(true);
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = lp.rightMargin = Ui.dp(Ui.gStyle ? 18 : 16);
            addView(card, lp);
        }

        View row(int icon, int iconColor, String title, String value, boolean chevron, View.OnClickListener click) {
            Context c = getContext();
            if (rows++ > 0) {
                View sep = new View(c);
                sep.setBackgroundColor(Ui.separator());
                LayoutParams sl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(0.5f)));
                sl.leftMargin = icon != 0 ? Ui.dp(Ui.gStyle ? 68 : 52) : Ui.dp(Ui.gStyle ? 22 : 16);
                if (Ui.gStyle) sl.rightMargin = Ui.dp(22);
                card.addView(sep, sl);
            }
            LinearLayout r = new LinearLayout(c);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(Ui.dp(Ui.gStyle ? 20 : 16), Ui.dp(Ui.gStyle ? 6 : 0), Ui.dp(Ui.gStyle ? 18 : 14), Ui.dp(Ui.gStyle ? 6 : 0));
            r.setMinimumHeight(Ui.dp(Ui.gStyle ? 60 : 53));
            if (icon != 0) {
                if (Ui.gStyle) {
                    FrameLayout badge = new FrameLayout(c);
                    badge.setBackground(Ui.round(Ui.alpha(iconColor, Ui.dark ? 0.22f : 0.12f), 100));
                    Icons.IconView iv = new Icons.IconView(c, icon, 18, iconColor);
                    badge.addView(iv, new FrameLayout.LayoutParams(Ui.dp(20), Ui.dp(20), Gravity.CENTER));
                    LayoutParams il = new LayoutParams(Ui.dp(34), Ui.dp(34));
                    il.rightMargin = Ui.dp(12);
                    r.addView(badge, il);
                } else {
                    Icons.IconView iv = new Icons.IconView(c, icon, 21, iconColor);
                    LayoutParams il = new LayoutParams(Ui.dp(22), Ui.dp(22));
                    il.rightMargin = Ui.dp(14);
                    r.addView(iv, il);
                }
            }
            TextView t = Ui.text(c, title, Ui.gStyle ? 16.5f : 17f, Ui.gStyle ? Ui.medium : Ui.regular, Ui.text());
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
                if (Ui.gStyle) Ui.press(r, 0.99f);
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
        // G style: a plain white knob (no clearing lens); the size spring stays
        g.tint = Ui.gStyle ? 0xFFFFFFFF : Ui.mix(0xFFFFFFFF, Ui.dark ? 0x26FFFFFF : 0x14FFFFFF, k);
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

    /**
     * Faithful port of Kyant0/AndroidLiquidGlass {@code LiquidToggle.kt}:
     * - 64dp × 28dp capsule track (accent #34C759 light / #30D158 dark; track #787878@20% light / #787880@36% dark)
     * - 40dp × 24dp horizontal capsule thumb (2dp padding, 20dp drag travel)
     * - Damped drag physics (initialScale=1f, pressedScale=1.45f, critically damped non-bouncy settle) with velocity droplet stretch
     * - Optical Liquid Glass thumb: opaque white at rest (alpha = 1 - progress) morphing into clear refractive lens
     *   (refract 5dp, edge 10dp, chromatic aberration, ambient highlight, inner & outer shadow) over the live track.
     */
    static final class Toggle extends View {
        boolean on;
        float pos;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint rimP = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF(), knob = new RectF();
        java.util.function.Consumer<Boolean> change;
        private final Ui.Spring press = new Ui.Spring(460f, 1.0f, 0.002f);
        private final Ui.Spring move = new Ui.Spring(380f, 1.0f, 0.002f);
        private final Glass.Surface glass = new Glass.Surface();
        private final Track track = new Track();
        private float downX;
        private boolean dragged;

        Toggle(Context c, boolean on) {
            super(c);
            this.on = on;
            pos = on ? 1f : 0f;
            move.snap(pos);
            press.snap(0f);
            press.onUpdate = this::invalidate;
            move.onUpdate = () -> { pos = move.value; invalidate(); };
            rimP.setStyle(Paint.Style.STROKE);
            glass.blur = true;
            glass.blurDp = 8f;
            glass.refractDp = 5f;
            glass.edgeDp = 10f;
            glass.chroma = 0.55f;
            glass.rim = 0.52f;
            glass.tint = 0xFFFFFFFF;
            glass.shadowAlpha = 0.14f;
            glass.strokeColor = 0x16000000;
        }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(Ui.dp(64), Ui.dp(36)); }

        void setChecked(boolean checked, boolean animate) {
            on = checked;
            if (animate) move.to(on ? 1f : 0f);
            else { pos = on ? 1f : 0f; move.snap(pos); }
            invalidate();
        }

        /** Test seam for capturing mid-press Liquid Glass refractive capsule frames in Robolectric. */
        void setPreviewState(float fraction, float pressProgress) {
            pos = Ui.clamp(fraction, 0f, 1f);
            move.snap(pos);
            press.snap(Ui.clamp(pressProgress, 0f, 1f));
            invalidate();
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            float dragWidth = Math.max(1f, Ui.dpf(20));
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    downX = e.getX();
                    dragged = false;
                    press.to(1f);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - downX;
                    if (Math.abs(dx) > Ui.dpf(3)) dragged = true;
                    if (dragged) move.to(Ui.clamp((on ? 1f : 0f) + dx / dragWidth, 0f, 1f));
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    boolean next = dragged ? pos >= 0.5f : !on;
                    if (e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) next = on;
                    boolean changed = next != on;
                    on = next;
                    move.to(on ? 1f : 0f);
                    postDelayed(() -> press.to(0f), 110);
                    if (changed && change != null) change.accept(on);
                    return true;
                }
            }
            return true;
        }

        private void drawTrack(Canvas c) {
            float trackH = Ui.dpf(28);
            float top = (getHeight() - trackH) * 0.5f;
            r.set(0f, top, getWidth(), top + trackH);
            int off = Ui.dark ? 0x5C787880 : 0x33787878;
            int onCol = Ui.gStyle ? Ui.ACCENT : (Ui.dark ? 0xFF30D158 : 0xFF34C759);
            p.setColor(Ui.mix(off, onCol, Ui.clamp(pos, 0f, 1f)));
            c.drawRoundRect(r, trackH * 0.5f, trackH * 0.5f, p);
        }

        @Override protected void onDraw(Canvas c) {
            float pr = Ui.clamp(press.value, 0f, 1f);
            float pad = Ui.dpf(2);
            float baseW = Ui.dpf(40), baseH = Ui.dpf(24);
            float dragW = Math.max(0f, getWidth() - baseW - pad * 2f);
            float cx = pad + baseW * 0.5f + dragW * Ui.clamp(pos, 0f, 1f);
            float cy = getHeight() * 0.5f;

            // Kyant0 DampedDragAnimation pressScale (1.0 -> 1.45) + horizontal velocity droplet stretch
            float sc = Ui.lerp(1f, 1.42f, pr);
            float vel = Ui.clamp(move.velocity * 0.04f, -0.2f, 0.2f);
            float sx = sc / (1f - Math.abs(vel) * 0.75f);
            float sy = sc * (1f - Math.abs(vel) * 0.25f);
            float kw = baseW * sx;
            float kh = baseH * sy;
            knob.set(cx - kw * 0.5f, cy - kh * 0.5f, cx + kw * 0.5f, cy + kh * 0.5f);

            if (c.isHardwareAccelerated() && Glass.shadersOk && Ui.glass && !Ui.gStyle) {
                android.graphics.RecordingCanvas rc = track.begin(getWidth(), getHeight());
                try { drawTrack(rc); } finally { track.end(); }
                c.drawRenderNode(track.node);

                glass.shapes(knob, kh * 0.5f, null, 0, null, 0);
                glass.alpha = 1f;
                glass.blur = pr < 0.95f;
                glass.blurDp = 8f * (1f - pr);
                glass.refractDp = 5f * pr;
                glass.edgeDp = 10f * Math.max(0.25f, pr);
                glass.outerDp = 4f;
                glass.outerRefractDp = 2.5f * pr;
                glass.chroma = 0.55f * pr;
                glass.rim = 0.24f + 0.42f * pr;
                glass.mag = 1f + 0.14f * pr;
                glass.magX = cx;
                glass.magY = cy;
                glass.tint = Ui.mix(0xFFFFFFFF, Ui.dark ? 0x1EFFFFFF : 0x14FFFFFF, pr);
                glass.shadowAlpha = 0.12f + 0.06f * pr;
                glass.extra = track.node;
                glass.draw(c, this, null);
                return;
            }
            drawTrack(c);
            // Software / G-style fallback matching Kyant0 capsule geometry + translucent refractive preview when pressed
            int fillCol = Ui.gStyle ? 0xFFFFFFFF : Ui.mix(0xFFFFFFFF, 0x55FFFFFF, pr);
            p.setColor(fillCol);
            p.setShadowLayer(Ui.dpf(3.5f), 0, Ui.dpf(1.2f), 0x24000000);
            c.drawRoundRect(knob, kh * 0.5f, kh * 0.5f, p);
            p.clearShadowLayer();
            if (pr > 0.02f && !Ui.gStyle) {
                rimP.setStrokeWidth(Ui.dpf(1.2f));
                rimP.setColor(Ui.alpha(0xFFFFFFFF, 0.78f * pr));
                c.drawRoundRect(knob, kh * 0.5f, kh * 0.5f, rimP);
            }
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
            float half = Ui.dpf(Ui.gStyle ? 3f : 2.5f);
            r.set(pad(), cy - half, getWidth() - pad(), cy + half);
            p.setColor(Ui.dark ? 0xFF3A3A3C : 0xFFE5E5EA);
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, p);
            r.right = x;
            p.setColor(Ui.ACCENT);
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, p);
        }

        @Override protected void onDraw(Canvas c) {
            float l = lens.value;
            float cx = pad() + (getWidth() - pad() * 2) * value, cy = getHeight() / 2f;
            if (Ui.gStyle) {
                // One UI 9.x SESL expand seekbar: track expands 5dp -> 13dp while dragging, thumb 9dp -> 12dp with 2dp accent stroke
                float half = Ui.dpf(Ui.lerp(2.5f, 6.5f, l));
                r.set(pad(), cy - half, getWidth() - pad(), cy + half);
                p.setColor(Ui.dark ? 0xFF3A3A3D : 0xFFE4E4E7);
                c.drawRoundRect(r, half, half, p);
                r.right = Math.max(pad() + half * 2f, cx);
                p.setColor(Ui.ACCENT);
                c.drawRoundRect(r, half, half, p);
                float rad = Ui.lerp(Ui.dpf(9.5f), Ui.dpf(12.5f), l);
                p.setColor(0xFFFFFFFF);
                p.setShadowLayer(Ui.dpf(4), 0, Ui.dpf(1.5f), 0x33000000);
                c.drawCircle(cx, cy, rad, p);
                p.clearShadowLayer();
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dpf(2f));
                p.setColor(Ui.ACCENT);
                c.drawCircle(cx, cy, rad - Ui.dpf(1f), p);
                p.setStyle(Paint.Style.FILL);
                return;
            }
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
            p.setColor(Ui.ACCENT);
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
        box.setPadding(Ui.dp(22), Ui.dp(26), Ui.dp(22), Ui.dp(24));
        GradientDrawable boxBg = Ui.round(Ui.card(), Ui.gStyle ? 26 : 22);
        boxBg.setStroke(Math.max(1, Ui.dp(0.75f)), Ui.dark ? 0x1AFFFFFF : 0x10000000);
        box.setBackground(boxBg);
        box.setElevation(Ui.dpf(1.5f));
        FrameLayout iconBadge = new FrameLayout(c);
        iconBadge.setBackground(Ui.round(Ui.alpha(Ui.ACCENT, Ui.dark ? 0.18f : 0.10f), 100));
        Icons.IconView iv = new Icons.IconView(c, icon, 26, Ui.ACCENT);
        iconBadge.addView(iv, new FrameLayout.LayoutParams(Ui.dp(28), Ui.dp(28), Gravity.CENTER));
        box.addView(iconBadge, new LinearLayout.LayoutParams(Ui.dp(52), Ui.dp(52)));
        TextView t = Ui.text(c, message, 14.5f, Ui.medium, Ui.text());
        t.setSingleLine(false);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(Ui.dpf(2), 1f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(14);
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
