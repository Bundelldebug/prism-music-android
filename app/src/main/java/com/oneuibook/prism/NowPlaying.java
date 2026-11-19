package com.oneuibook.prism;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen player. Opens with a container transform out of the glass mini player (the frosted card
 * grows, the artwork flies to its slot, the artwork-coloured mesh gradient fades in) and closes the same
 * way, including from an interactive swipe-down.
 */
final class NowPlaying extends FrameLayout {
    private final MainActivity main;
    private final Dock dock;

    // live geometry (root coordinates)
    private final RectF rect = new RectF(), art = new RectF(), miniR = new RectF(), miniArt = new RectF();
    private final RectF full = new RectF(), tmp = new RectF();
    float openP;          // 0 = collapsed into mini, 1 = fully open
    private float dragY;
    private float mode;   // 0 = artwork layout, 1 = panel (lyrics/queue) layout
    private int panel;    // 0 none, 1 lyrics, 2 queue
    private ValueAnimator openAnim, modeAnim, dragAnim;
    private final Ui.Spring artScale = new Ui.Spring(260f, 0.62f, 0.001f);

    // layout slots (full-screen coordinates)
    private final RectF artBig = new RectF(), artSmall = new RectF(), panelRect = new RectF();
    private float titleBigY, titleSmallY, ctrlY, seekY, timesY, bottomY;

    private final Glass.Surface glass = new Glass.Surface();
    private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final Matrix artM = new Matrix();
    private Bitmap artBmp;
    private String artUrl;
    private int[] pal = Img.palette(null), palFrom = Img.palette(null);
    private float palMix = 1f;
    private boolean lightArt;

    // children
    private final Content content;
    private final View handle;
    private final TextView title, artist, elapsed, remain, chipText;
    private final LinearLayout chip;
    private final Icons.IconView star, more, aodBtn, prev, play, next, lyricsBtn, shuffleBtn, repeatBtn, queueBtn;
    private final Seek seek;
    private final Icons.IconView outBtn;
    private final VolumeBar vol;
    /** Player style setting: false = Prism (default), true = One UI (media player card look). */
    private boolean oneui;
    private final FrameLayout panelHost;
    private final LyricsView lyrics;
    private final ScrollView queueScroll;
    private final LinearLayout queueList;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            tick();
            if (openP > 0) postDelayed(this, 250);
        }
    };

    NowPlaying(MainActivity m, Dock dock) {
        super(m);
        main = m;
        this.dock = dock;
        setWillNotDraw(false);
        setVisibility(GONE);
        glass.blurDp = 16;
        glass.refractDp = 10;
        glass.edgeDp = 20;
        glass.rim = 0.25f;
        glass.shadowAlpha = 0.18f;
        shadow.setColor(0x01000000);

        content = new Content(m);
        addView(content, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        handle = new View(m);
        handle.setBackground(Ui.round(0x66FFFFFF, 3));
        content.addView(handle);

        title = Ui.text(m, "", 21, Ui.bold, 0xFFFFFFFF);
        artist = Ui.text(m, "", 19, Ui.regular, 0xB3FFFFFF);
        content.addView(title);
        content.addView(artist);
        artist.setOnClickListener(v -> goArtist());

        star = icon(Icons.STAR, 25, 0xFFFFFFFF, v -> Player.toggleLike());
        more = icon(Icons.MORE, 24, 0xFFFFFFFF, v -> moreMenu(v));
        aodBtn = icon(Icons.AOD, 22, 0xE6FFFFFF, v -> main.openAod());
        aodBtn.setVisibility(GONE);

        seek = new Seek(m);
        content.addView(seek);
        elapsed = Ui.text(m, "0:00", 12, Ui.medium, 0x99FFFFFF);
        remain = Ui.text(m, "-0:00", 12, Ui.medium, 0x99FFFFFF);
        remain.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        content.addView(elapsed);
        content.addView(remain);
        chip = new LinearLayout(m);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(Ui.dp(8), 0, Ui.dp(10), 0);
        chip.setBackground(Ui.round(0x24FFFFFF, 6));
        chip.addView(new Icons.IconView(m, Icons.DEVICE, 12, 0xB3FFFFFF), new LinearLayout.LayoutParams(Ui.dp(14), Ui.dp(14)));
        chipText = Ui.text(m, "Spotify", 11, Ui.medium, 0xB3FFFFFF);
        LinearLayout.LayoutParams ctl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctl.leftMargin = Ui.dp(4);
        chip.addView(chipText, ctl);
        chip.setOnClickListener(v -> deviceMenu(v));
        content.addView(chip);

        prev = icon(Icons.PREV, 40, 0xFFFFFFFF, v -> Player.prev());
        play = icon(Icons.PAUSE, 46, 0xFFFFFFFF, v -> Player.toggle());
        next = icon(Icons.NEXT, 40, 0xFFFFFFFF, v -> Player.next());
        lyricsBtn = icon(Icons.LYRICS, 24, 0xB3FFFFFF, v -> setPanel(panel == 1 ? 0 : 1));
        shuffleBtn = icon(Icons.SHUFFLE, 24, 0xB3FFFFFF, v -> Player.toggleShuffle());
        repeatBtn = icon(Icons.REPEAT, 24, 0xB3FFFFFF, v -> { Player.cycleRepeat(); Player.toast("track".equals(Player.repeat) ? R.string.repeat_one : ("context".equals(Player.repeat) ? R.string.repeat_all : R.string.repeat_off)); });
        queueBtn = icon(Icons.LIST, 24, 0xB3FFFFFF, v -> setPanel(panel == 2 ? 0 : 2));
        for (View v : new View[]{prev, play, next}) Ui.press(v, 0.82f);
        // One UI style: output-device button at the end of the device row
        outBtn = icon(Icons.OUTPUT, 24, 0xE6FFFFFF, v -> deviceMenu(chip));
        outBtn.setVisibility(GONE);
        vol = new VolumeBar(m);
        content.addView(vol);

        panelHost = new FrameLayout(m);
        panelHost.setAlpha(0f);
        panelHost.setVisibility(INVISIBLE);
        content.addView(panelHost);
        lyrics = new LyricsView(m);
        panelHost.addView(lyrics, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        queueScroll = new ScrollView(m);
        queueScroll.setVerticalScrollBarEnabled(false);
        queueScroll.setVerticalFadingEdgeEnabled(true);
        queueScroll.setFadingEdgeLength(Ui.dp(24));
        queueList = new LinearLayout(m);
        queueList.setOrientation(LinearLayout.VERTICAL);
        queueScroll.addView(queueList);
        queueScroll.setVisibility(GONE);
        panelHost.addView(queueScroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        artScale.value = artScale.target = 1f;
        artScale.onUpdate = this::invalidate;

        Player.listeners.add(playerL);
        Player.trackListeners.add(trackL);
    }

    private final Runnable playerL = this::onPlayer;
    private final Runnable trackL = this::onTrack;

    private Icons.IconView icon(int ic, float size, int color, OnClickListener click) {
        Icons.IconView v = new Icons.IconView(getContext(), ic, size, color);
        v.setOnClickListener(click);
        content.addView(v);
        return v;
    }

    boolean isOpen() { return getVisibility() == VISIBLE && (openAnim == null || !openAnim.isRunning() || openTarget > 0.5f); }
    private float openTarget;

    // ---------------------------------------------------------------- open / close
    void open() {
        if (Player.track == null) { Player.toast(R.string.nothing_playing); return; }
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
            dragY = 0;
            onTrack();
            onPlayer();
        }
        miniR.set(dock.miniRect());
        miniArt.set(dock.miniArtRect());
        dock.setMiniHidden(true);
        Player.setFast(true);
        // tablet / unfolded: the right pane is part of the layout and opens on the queue
        View host = (View) getParent();   // this view is still GONE (0×0) on the first open
        if (host != null && host.getWidth() / Ui.density >= 600 && host.getHeight() / Ui.density >= 480 && panel == 0) post(() -> setPanel(2));
        removeCallbacks(ticker);
        post(ticker);
        animateOpen(1f, 560, Ui.DECEL);
    }

    /** Tablet bar's lyrics / queue buttons: open the player straight onto that pane (1 = lyrics, 2 = queue). */
    void openPanel(int p) {
        boolean wasOpen = isOpen();
        open();
        if (Player.track == null) return;
        if (wasOpen) { setPanel(p); return; }
        // open() may post the default queue pane; this runs after it
        post(() -> post(() -> setPanel(p)));
    }

    /** Tablet bar's "more": the song's actions in a glass menu growing out of the button. */
    void trackMenuAt(RectF anchor) {
        Context c = getContext();
        Player.Track t = Player.track;
        if (t == null) { Player.toast(R.string.nothing_playing); return; }
        List<GlassMenu.Entry> e = new ArrayList<>();
        if (t.albumUri != null) e.add(new GlassMenu.Entry(c.getString(R.string.go_album), Icons.ALBUM, false, () -> main.openUri(t.albumUri, t.album)));
        if (t.artistUri != null) e.add(new GlassMenu.Entry(c.getString(R.string.go_artist), Icons.ARTIST, false, () -> main.openUri(t.artistUri, t.artist)));
        if (!Player.local()) {
            e.add(new GlassMenu.Entry(c.getString(Player.liked ? R.string.unlike : R.string.like), Player.liked ? Icons.STAR_FILL : Icons.STAR, false, Player::toggleLike));
            e.add(new GlassMenu.Entry(c.getString(R.string.open_in_spotify), Icons.LINK, false, () -> Player.openInSpotify(t.uri)));
        }
        e.add(new GlassMenu.Entry(c.getString(R.string.up_next), Icons.LIST, false, () -> openPanel(2)));
        e.add(new GlassMenu.Entry(c.getString(R.string.set_lyrics), Icons.LYRICS, false, () -> openPanel(1)));
        GlassMenu.show(main, anchor, e);
    }

    void close() {
        if (getVisibility() != VISIBLE) return;
        miniR.set(dock.miniRect());
        miniArt.set(dock.miniArtRect());
        Player.setFast(false);
        animateOpen(0f, 460, new PathInterpolator(0.3f, 0f, 0.1f, 1f));
    }

    private void animateOpen(float to, long ms, android.animation.TimeInterpolator in) {
        if (openAnim != null) openAnim.cancel();
        openTarget = to;
        openAnim = Ui.animate(openP, to, ms, in, a -> setOpenP((float) a.getAnimatedValue()));
        openAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean canceled;
            @Override public void onAnimationCancel(android.animation.Animator a) { canceled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (canceled) return;
                if (to == 0f) {
                    setVisibility(GONE);
                    dock.setMiniHidden(false);
                    dragY = 0;
                    if (panel != 0) { panel = 0; mode = 0; updatePanelVisibility(); }
                }
            }
        });
    }

    private void setOpenP(float v) {
        openP = v;
        main.onPlayerProgress(v);
        invalidate();
        content.invalidate();
    }

    // ---------------------------------------------------------------- layout
    /** Unfolded foldable / tablet: artwork + controls on the left, lyrics or queue always on the right. */
    boolean wide;
    private float colX, colW;

    private void computeSlots(int W, int H) {
        float top = Ui.statusInset;
        wide = W / Ui.density >= 600 && H / Ui.density >= 480;
        if (wide) {
            bottomY = H - Ui.navInset - Ui.dpf(34);
            colW = Math.min(W * 0.38f, H * (oneui ? 0.43f : 0.48f));
            colX = Math.max(Ui.dpf(36), W * 0.055f);
            float extraH = oneui ? Ui.dpf(268) : Ui.dpf(250);
            float block = colW + extraH;
            float y0 = Math.max(top + Ui.dpf(24), (H - block) / 2f);
            artBig.set(colX, y0, colX + colW, y0 + colW);
            artSmall.set(artBig);
            if (oneui) {
                float devY = artBig.bottom + Ui.dpf(22);
                titleBigY = devY + Ui.dpf(24);
                titleSmallY = titleBigY;
                seekY = titleBigY + Ui.dpf(78);
                timesY = seekY + Ui.dpf(20);
                ctrlY = timesY + Ui.dpf(56);
            } else {
                titleBigY = artBig.bottom + Ui.dpf(28);
                titleSmallY = titleBigY;
                seekY = titleBigY + Ui.dpf(68);
                timesY = seekY + Ui.dpf(22);
                ctrlY = timesY + Ui.dpf(64);
            }
            float px = colX + colW + Math.max(Ui.dpf(40), W * 0.055f);
            panelRect.set(px, top + Ui.dpf(36), W - Ui.dpf(36), bottomY - Ui.dpf(36));
            return;
        }
        float side = Ui.dpf(28);
        bottomY = H - Ui.navInset - Ui.dpf(38);
        if (oneui) {
            // G style: rounded cover → output-device + action row → centred marquee title/artist → wave bar → controls → volume
            float s1 = Ui.dpf(24);
            float size = Math.min(W - s1 * 2, H * (Ui.cover() ? 0.34f : 0.40f));
            artBig.set((W - size) / 2f, top + Ui.dpf(Ui.cover() ? 28 : 40), (W + size) / 2f, top + Ui.dpf(Ui.cover() ? 28 : 40) + size);
            float sm = Ui.dpf(64);
            artSmall.set(side, top + Ui.dpf(40), side + sm, top + Ui.dpf(40) + sm);
            float devY = artBig.bottom + Ui.dpf(Ui.cover() ? 20 : 24);
            titleBigY = devY + Ui.dpf(24);
            titleSmallY = artSmall.centerY() - Ui.dpf(23);
            seekY = titleBigY + Ui.dpf(Ui.cover() ? 72 : 80);
            timesY = seekY + Ui.dpf(22);
            ctrlY = Math.max(timesY + Ui.dpf(56), (timesY + bottomY) / 2f - Ui.dpf(24));
            panelRect.set(0, artSmall.bottom + Ui.dpf(18), W, seekY - Ui.dpf(26));
            return;
        }
        float size = Math.min(W - side * 2, H * 0.44f);
        artBig.set((W - size) / 2f, top + Ui.dpf(46), (W + size) / 2f, top + Ui.dpf(46) + size);
        float sm = Ui.dpf(64);
        artSmall.set(side, top + Ui.dpf(40), side + sm, top + Ui.dpf(40) + sm);
        titleBigY = artBig.bottom + Ui.dpf(34);
        titleSmallY = artSmall.centerY() - Ui.dpf(23);
        seekY = titleBigY + Ui.dpf(66);
        timesY = seekY + Ui.dpf(22);
        float minCtrl = timesY + Ui.dpf(64);
        ctrlY = Math.max(minCtrl, (timesY + Ui.dpf(16) + bottomY) / 2f - Ui.dpf(6));
        panelRect.set(0, artSmall.bottom + Ui.dpf(18), W, seekY - Ui.dpf(18));
    }

    /** Lays out the controls; positions depend on {@link #mode} so the lyrics/queue morph is animated. */
    private final class Content extends FrameLayout {
        Content(Context c) { super(c); setClipChildren(false); }

        @Override protected void onMeasure(int ws, int hs) {
            int W = MeasureSpec.getSize(ws), H = MeasureSpec.getSize(hs);
            setMeasuredDimension(W, H);
            computeSlots(W, H);
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                v.measure(MeasureSpec.makeMeasureSpec(W, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(H, MeasureSpec.AT_MOST));
            }
            int side = Ui.dp(28);
            int colWi = wide ? (int) colW : W - side * 2;
            exact(handle, Ui.dp(36), Ui.dp(5));
            int titleW = oneui ? colWi : Math.max(Ui.dp(80), colWi - Ui.dp(84));
            exact(title, titleW, Ui.dp(30));
            exact(artist, titleW, Ui.dp(24));
            for (View v : new View[]{star, more, aodBtn}) exact(v, Ui.dp(38), Ui.dp(38));
            exact(seek, colWi + Ui.dp(16), Ui.dp(26));
            exact(elapsed, Ui.dp(70), Ui.dp(16));
            exact(remain, Ui.dp(70), Ui.dp(16));
            chip.measure(MeasureSpec.makeMeasureSpec(Ui.dp(180), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(Ui.dp(20), MeasureSpec.EXACTLY));
            if (oneui) {
                // round translucent buttons; scale down smoothly on narrower unfolded foldable columns so 5 buttons never collide
                int btnS = wide ? Math.min(Ui.dp(54), Math.max(Ui.dp(40), (int) (colWi * 0.20f))) : Ui.dp(60);
                int playS = wide ? Math.min(Ui.dp(62), Math.max(Ui.dp(46), (int) (colWi * 0.24f))) : Ui.dp(68);
                exact(prev, btnS, btnS);
                exact(play, playS, playS);
                exact(next, btnS, btnS);
                // the wave needs height above the line
                exact(seek, colWi + Ui.dp(16), Ui.dp(40));
                chip.measure(MeasureSpec.makeMeasureSpec(Math.max(Ui.dp(96), colWi - Ui.dp(124)), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(Ui.dp(30), MeasureSpec.EXACTLY));
            } else {
                int btnS = wide ? Math.min(Ui.dp(64), Math.max(Ui.dp(44), (int) (colWi * 0.22f))) : Ui.dp(72);
                int playS = wide ? Math.min(Ui.dp(74), Math.max(Ui.dp(52), (int) (colWi * 0.26f))) : Ui.dp(84);
                exact(prev, btnS, btnS);
                exact(play, playS, playS);
                exact(next, btnS, btnS);
            }
            exact(outBtn, Ui.dp(40), Ui.dp(40));
            exact(vol, colWi, Ui.dp(36));
            int smallBtn = wide ? Math.min(Ui.dp(44), Math.max(Ui.dp(36), (int) (colWi * 0.16f))) : Ui.dp(48);
            for (View v : new View[]{lyricsBtn, shuffleBtn, repeatBtn, queueBtn}) exact(v, smallBtn, smallBtn);
            exact(panelHost, wide ? (int) panelRect.width() : W, (int) panelRect.height());
        }

        private void exact(View v, int w, int h) {
            v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }

        @Override protected void onLayout(boolean ch, int l, int t, int r, int b) {
            int W = r - l, H = b - t;
            computeSlots(W, H);
            outBtn.setVisibility(GONE);
            aodBtn.setVisibility(oneui ? VISIBLE : GONE);
            if (wide) { layoutWide(W); applyMode(); return; }
            if (oneui) { layoutOneUi(W); applyMode(); return; }
            int side = Ui.dp(28);
            place(handle, (W - handle.getMeasuredWidth()) / 2, Ui.statusInset + Ui.dp(14));
            place(title, side, (int) titleBigY);
            place(artist, side, (int) titleBigY + Ui.dp(30));
            place(star, W - side - Ui.dp(76), (int) titleBigY + Ui.dp(6));
            place(more, W - side - Ui.dp(36), (int) titleBigY + Ui.dp(6));
            place(seek, side - Ui.dp(8), (int) seekY - Ui.dp(13));
            place(elapsed, side, (int) timesY);
            place(remain, W - side - Ui.dp(70), (int) timesY);
            place(chip, (W - chip.getMeasuredWidth()) / 2, (int) timesY - Ui.dp(2));
            int cy = (int) ctrlY;
            int gap = Math.min(Ui.dp(108), W / 4);
            place(prev, W / 2 - gap - Ui.dp(36), cy - Ui.dp(36));
            place(play, W / 2 - Ui.dp(42), cy - Ui.dp(42));
            place(next, W / 2 + gap - Ui.dp(36), cy - Ui.dp(36));
            int by = (int) bottomY - Ui.dp(24);
            // lyrics · shuffle · repeat · queue, evenly spaced
            int span = Math.min(Ui.dp(300), W - Ui.dp(80));
            int step = span / 3, x0 = (W - span) / 2;
            place(lyricsBtn, x0 - Ui.dp(24), by);
            place(shuffleBtn, x0 + step - Ui.dp(24), by);
            place(repeatBtn, x0 + step * 2 - Ui.dp(24), by);
            place(queueBtn, x0 + span - Ui.dp(24), by);
            placeVolume(side, (int) (ctrlY + Ui.dpf(48)), by);
            place(panelHost, 0, (int) panelRect.top);
            applyMode();
        }

        /** Volume bar between the controls and the bottom row, if there is room for it. */
        private void placeVolume(int x, int minTop, int bottomRowTop) {
            int h = vol.getMeasuredHeight();
            int y = Math.max(minTop, (minTop + bottomRowTop - h) / 2);
            boolean fits = y + h <= bottomRowTop - Ui.dp(4);
            vol.setVisibility(fits ? VISIBLE : INVISIBLE);
            place(vol, x, y);
        }

        /** One UI 9.x (G style) media player: cover → device chip + AOD/star/more row → centred marquee title/artist → wave → controls → volume. */
        private void layoutOneUi(int W) {
            int side = Ui.dp(28);
            place(handle, (W - handle.getMeasuredWidth()) / 2, Ui.statusInset + Ui.dp(14));
            // output-device pill on left, AOD / like / more on right
            int devY = (int) (artBig.bottom + Ui.dpf(Ui.cover() ? 20 : 24));
            place(chip, side, devY - chip.getMeasuredHeight() / 2);
            int rightX = W - side;
            place(more, rightX - more.getMeasuredWidth(), devY - more.getMeasuredHeight() / 2);
            rightX -= more.getMeasuredWidth() + Ui.dp(2);
            if (!Player.local()) {
                place(star, rightX - star.getMeasuredWidth(), devY - star.getMeasuredHeight() / 2);
                rightX -= star.getMeasuredWidth() + Ui.dp(2);
            } else {
                place(star, rightX - star.getMeasuredWidth(), devY - star.getMeasuredHeight() / 2);
            }
            place(aodBtn, rightX - aodBtn.getMeasuredWidth(), devY - aodBtn.getMeasuredHeight() / 2);
            // centred full-width title & artist (with marquee auto-scroll for long strings)
            place(title, side, (int) titleBigY);
            place(artist, side, (int) titleBigY + Ui.dp(32));
            place(seek, side - Ui.dp(8), (int) seekY - seek.getMeasuredHeight() + Ui.dp(10));
            place(elapsed, side, (int) timesY);
            place(remain, W - side - Ui.dp(70), (int) timesY);
            int cy = (int) ctrlY, mid = W / 2;
            int step = Math.min(Ui.dp(86), (W - side * 2) / 4);
            place(shuffleBtn, side - Ui.dp(4), cy - shuffleBtn.getMeasuredHeight() / 2);
            place(prev, mid - step - prev.getMeasuredWidth() / 2, cy - prev.getMeasuredHeight() / 2);
            place(play, mid - play.getMeasuredWidth() / 2, cy - play.getMeasuredHeight() / 2);
            place(next, mid + step - next.getMeasuredWidth() / 2, cy - next.getMeasuredHeight() / 2);
            place(repeatBtn, W - side - repeatBtn.getMeasuredWidth() + Ui.dp(4), cy - repeatBtn.getMeasuredHeight() / 2);
            int by = (int) bottomY - Ui.dp(24);
            place(lyricsBtn, side - Ui.dp(4), by);
            place(queueBtn, W - side - queueBtn.getMeasuredWidth() + Ui.dp(4), by);
            placeVolume(side, cy + play.getMeasuredHeight() / 2 + Ui.dp(10), by);
            place(panelHost, 0, (int) panelRect.top);
        }

        private void place(View v, int x, int y) { v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight()); }

        /** Unfolded foldable / tablet two-pane layout: cover + controls on left, lyrics/queue on right. */
        private void layoutWide(int W) {
            int x = (int) colX, cw = (int) colW;
            place(handle, (int) (panelRect.centerX() - handle.getMeasuredWidth() / 2f), Ui.statusInset + Ui.dp(14));
            if (oneui) {
                int devY = (int) (artBig.bottom + Ui.dpf(22));
                place(chip, x, devY - chip.getMeasuredHeight() / 2);
                int rx = x + cw;
                place(more, rx - more.getMeasuredWidth(), devY - more.getMeasuredHeight() / 2);
                rx -= more.getMeasuredWidth() + Ui.dp(2);
                if (!Player.local()) {
                    place(star, rx - star.getMeasuredWidth(), devY - star.getMeasuredHeight() / 2);
                    rx -= star.getMeasuredWidth() + Ui.dp(2);
                } else {
                    place(star, rx - star.getMeasuredWidth(), devY - star.getMeasuredHeight() / 2);
                }
                place(aodBtn, rx - aodBtn.getMeasuredWidth(), devY - aodBtn.getMeasuredHeight() / 2);
                place(title, x, (int) titleBigY);
                place(artist, x, (int) titleBigY + Ui.dp(32));
                place(seek, x - Ui.dp(8), (int) seekY - seek.getMeasuredHeight() + Ui.dp(10));
            } else {
                place(title, x, (int) titleBigY);
                place(artist, x, (int) titleBigY + Ui.dp(30));
                place(star, x + cw - Ui.dp(80), (int) titleBigY + Ui.dp(6));
                place(more, x + cw - Ui.dp(38), (int) titleBigY + Ui.dp(6));
                place(seek, x - Ui.dp(8), (int) seekY - seek.getMeasuredHeight() / 2);
                place(chip, x + (cw - chip.getMeasuredWidth()) / 2, (int) timesY - Ui.dp(2));
            }
            place(elapsed, x, (int) timesY);
            place(remain, x + cw - Ui.dp(70), (int) timesY);
            int cy = (int) ctrlY, mid = x + cw / 2;
            int step = Math.min(Ui.dp(88), Math.max(Ui.dp(46), (cw - Ui.dp(16)) / 4));
            place(shuffleBtn, Math.max(x - Ui.dp(4), mid - step * 2 - shuffleBtn.getMeasuredWidth() / 2), cy - shuffleBtn.getMeasuredHeight() / 2);
            place(prev, mid - step - prev.getMeasuredWidth() / 2, cy - prev.getMeasuredHeight() / 2);
            place(play, mid - play.getMeasuredWidth() / 2, cy - play.getMeasuredHeight() / 2);
            place(next, mid + step - next.getMeasuredWidth() / 2, cy - next.getMeasuredHeight() / 2);
            place(repeatBtn, Math.min(x + cw - repeatBtn.getMeasuredWidth() + Ui.dp(4), mid + step * 2 - repeatBtn.getMeasuredWidth() / 2), cy - repeatBtn.getMeasuredHeight() / 2);
            int by = (int) bottomY - Ui.dp(24);
            place(lyricsBtn, W - Ui.dp(36) - lyricsBtn.getMeasuredWidth() * 2 - Ui.dp(10), by);
            place(queueBtn, W - Ui.dp(36) - queueBtn.getMeasuredWidth(), by);
            placeVolume(x, cy + play.getMeasuredHeight() / 2 + Ui.dp(12), by);
            place(panelHost, (int) panelRect.left, (int) panelRect.top);
        }

        @Override protected void dispatchDraw(Canvas c) {
            // follow the container: uniform scale + translation (only visible when content alpha > 0)
            float W = getWidth();
            float s = W > 0 ? rect.width() / W : 1f;
            float a = Ui.clamp((openP - 0.55f) / 0.45f, 0f, 1f);
            if (a <= 0.01f) return;
            int sc = c.saveLayerAlpha(0, 0, getWidth(), getHeight(), (int) (255 * a));
            c.translate(rect.left, rect.top + (1f - a) * Ui.dpf(26));
            c.scale(s, s);
            super.dispatchDraw(c);
            c.restoreToCount(sc);
        }
    }

    /** Positions that interpolate between the artwork layout and the panel layout. */
    private void applyMode() {
        int wantGrav = oneui ? Gravity.CENTER : (Gravity.START | Gravity.CENTER_VERTICAL);
        if (wide) {
            title.setGravity(wantGrav);
            artist.setGravity(wantGrav);
            for (View v : new View[]{title, artist, star, more, aodBtn}) { v.setTranslationX(0); v.setTranslationY(0); v.setScaleX(1f); v.setScaleY(1f); }
            chip.setAlpha(1f); chip.setVisibility(VISIBLE);
            aodBtn.setAlpha(1f);
            panelHost.setTranslationY(0);
            panelHost.setAlpha(panel == 0 ? 0f : 1f);
            invalidate();
            return;
        }
        float k = mode;
        // G style: the device/action row under the cover steps aside while lyrics / the queue are open
        float rowA = oneui ? 1f - Ui.clamp(k * 1.6f, 0f, 1f) : 1f;
        chip.setAlpha(rowA);
        outBtn.setAlpha(rowA);
        aodBtn.setAlpha(rowA);
        chip.setVisibility(rowA < 0.02f ? INVISIBLE : VISIBLE);
        int curGrav = (oneui && k < 0.35f) ? Gravity.CENTER : (Gravity.START | Gravity.CENTER_VERTICAL);
        if (title.getGravity() != curGrav) {
            title.setGravity(curGrav);
            artist.setGravity(curGrav);
        }
        float tx = Ui.lerp(0, artSmall.right + Ui.dpf(14) - Ui.dpf(28), k);
        float ty = Ui.lerp(0, titleSmallY - titleBigY, k);
        title.setPivotX(oneui && k < 0.35f ? title.getWidth() / 2f : 0); title.setPivotY(0);
        artist.setPivotX(oneui && k < 0.35f ? artist.getWidth() / 2f : 0); artist.setPivotY(0);
        float s = Ui.lerp(1f, 0.82f, k);
        title.setScaleX(s); title.setScaleY(s);
        artist.setScaleX(s); artist.setScaleY(s);
        title.setTranslationX(tx); title.setTranslationY(ty);
        artist.setTranslationX(tx); artist.setTranslationY(ty - Ui.dpf(6) * k);
        if (!oneui) {
            star.setTranslationY(ty + Ui.dpf(2) * k);
            more.setTranslationY(ty + Ui.dpf(2) * k);
        } else {
            star.setAlpha(rowA);
            more.setAlpha(rowA);
            star.setTranslationY(0);
            more.setTranslationY(0);
        }
        panelHost.setAlpha(Ui.clamp((k - 0.35f) / 0.65f, 0f, 1f));
        panelHost.setTranslationY((1f - k) * Ui.dpf(30));
        invalidate();
    }

    // ---------------------------------------------------------------- drawing
    /** Mirror of this screen, so glass menus opened over the player can refract it (like Backdrop). */
    final android.graphics.RenderNode node = new android.graphics.RenderNode("now-playing");
    boolean recorded;

    @Override public void draw(Canvas canvas) {
        if (!canvas.isHardwareAccelerated()) { super.draw(canvas); recorded = false; return; }
        node.setPosition(0, 0, getWidth(), getHeight());
        android.graphics.RecordingCanvas rc = node.beginRecording(getWidth(), getHeight());
        try { super.draw(rc); } finally { node.endRecording(); }
        recorded = true;
        canvas.drawRenderNode(node);
    }

    @Override protected void dispatchDraw(Canvas c) {
        int W = getWidth(), H = getHeight();
        if (W == 0) return;
        float p = openP;
        // dragged full-screen rect
        float dragK = Ui.clamp(dragY / H, 0f, 1f);
        float s = 1f - 0.12f * dragK;
        full.set(W * (1 - s) / 2f, dragY, W - W * (1 - s) / 2f, dragY + H * s);
        rect.set(Ui.lerp(miniR.left, full.left, p), Ui.lerp(miniR.top, full.top, p),
                Ui.lerp(miniR.right, full.right, p), Ui.lerp(miniR.bottom, full.bottom, p));
        float endRadius = Ui.clamp(dragY / Ui.dpf(40), 0f, 1f) * Ui.dpf(38);
        float radius = Ui.lerp(miniR.height() / 2f, Math.max(endRadius, Ui.dpf(38) * (1f - p) * 1.4f), Ui.clamp(p * 1.2f, 0f, 1f));
        if (p >= 0.999f) radius = endRadius;

        // artwork target in full-screen coordinates, then mapped into the (possibly dragged) container
        float sc = W > 0 ? rect.width() / W : 1f;
        tmp.set(Ui.lerp(artBig.left, artSmall.left, mode), Ui.lerp(artBig.top, artSmall.top, mode),
                Ui.lerp(artBig.right, artSmall.right, mode), Ui.lerp(artBig.bottom, artSmall.bottom, mode));
        float ps = Ui.lerp(artScale.value, 1f, mode);
        float cx = tmp.centerX(), cy = tmp.centerY(), hw = tmp.width() / 2f * ps, hh = tmp.height() / 2f * ps;
        tmp.set(cx - hw, cy - hh, cx + hw, cy + hh);
        RectF mapped = new RectF(rect.left + tmp.left * sc, rect.top + tmp.top * sc, rect.left + tmp.right * sc, rect.top + tmp.bottom * sc);
        float ap = Ui.clamp(p * 1.08f, 0f, 1f);
        art.set(Ui.lerp(miniArt.left, mapped.left, ap), Ui.lerp(miniArt.top, mapped.top, ap),
                Ui.lerp(miniArt.right, mapped.right, ap), Ui.lerp(miniArt.bottom, mapped.bottom, ap));
        float artRadius = Ui.lerp(Ui.dpf(7), Ui.dpf(mode > 0.5f ? 8 : (oneui && !wide ? 30 : 12)), ap);

        // soft shadow of the whole card while it is morphing
        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        int save = c.save();
        c.clipPath(clip);
        float bgMix = Ui.clamp((p - 0.12f) / 0.5f, 0f, 1f);
        if (bgMix < 1f) {
            glass.shapes(rect, radius, null, 0, null, 0);
            // starts as exactly the music bar's glass and thickens as it grows, instead of switching material
            float gk = Ui.clamp(p / 0.4f, 0f, 1f);
            gk = gk * gk * (3f - 2f * gk);
            glass.tint = Ui.mix(Ui.barTint(), Ui.dark ? 0xA61C1C1E : 0xB8FFFFFF, gk);
            glass.blurDp = Ui.lerp(Ui.barBlur(), 16f, gk);
            glass.refractDp = Ui.lerp(17f, 10f, gk);
            glass.edgeDp = Ui.lerp(24f, 20f, gk);
            glass.rim = Ui.lerp(0.32f, 0.25f, gk);
            glass.alpha = 1f;
            glass.draw(c, this, main.backdrop);
        }
        if (bgMix > 0f) drawBackground(c, bgMix);
        // artwork
        drawArt(c, artRadius);
        c.restoreToCount(save);
        super.dispatchDraw(c);
        // morphing: every frame; fully open: the slow mesh-gradient drift only needs ~30 fps
        if (p > 0 && p < 1 || dragY > 0) postInvalidateOnAnimation();
        // the cover background drifts slowly: ~15 fps is visually identical and far cooler than 30
        else if (bgMix > 0) postInvalidateDelayed(66);
    }

    private final Paint artBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix artBgM = new Matrix();
    private Bitmap softArt;
    private Bitmap softFor;
    private RadialGradient vignette;
    private float vignetteW, vignetteH;

    /** Pre-blurred cover, built once per song: shrink hard, grow back with filtering (cheap, smooth). */
    private Bitmap soft() {
        if (artBmp == null) return null;
        if (softFor == artBmp && softArt != null) return softArt;
        Bitmap tiny = Bitmap.createScaledBitmap(artBmp, 24, 24, true);
        Bitmap mid = Bitmap.createScaledBitmap(tiny, 96, 96, true);
        Bitmap tiny2 = Bitmap.createScaledBitmap(mid, 32, 32, true);
        softArt = Bitmap.createScaledBitmap(tiny2, 160, 160, true);
        softFor = artBmp;
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(1.45f);
        float k = 1.12f, b = -0.06f * 255f;
        cm.postConcat(new android.graphics.ColorMatrix(new float[]{k, 0, 0, 0, b, 0, k, 0, 0, b, 0, 0, k, 0, b, 0, 0, 0, 1, 0}));
        artBgPaint.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
        return softArt;
    }

    /**
     * Living artwork background: the cover, heavily zoomed and slowly rotating about an off-centre
     * pivot, made more vivid and vignetted. Only a matrix changes per frame.
     */
    private boolean drawArtBackground(Canvas c, float alpha) {
        Bitmap src = soft();
        if (src == null) return false;
        float w = rect.width(), h = rect.height();
        if (w <= 0 || h <= 0) return false;
        double time = SystemClock.uptimeMillis() / 1000.0;
        int sc = c.saveLayerAlpha(rect.left, rect.top, rect.right, rect.bottom, (int) (255 * alpha));
        c.clipRect(rect);
        float cover = Math.max(w, h) / src.getWidth() * 1.9f;
        artBgM.reset();
        artBgM.postTranslate(-src.getWidth() / 2f, -src.getHeight() / 2f);
        artBgM.postScale(cover, cover);
        artBgM.postRotate((float) Math.toDegrees(time * 0.11));
        // off-centre pivot so the colours sweep across the screen rather than spin in place
        artBgM.postTranslate(rect.left + w * 0.42f + (float) Math.cos(time * 0.07) * w * 0.08f,
                rect.top + h * 0.4f + (float) Math.sin(time * 0.05) * h * 0.06f);
        c.drawColor(pal[0]);
        c.drawBitmap(src, artBgM, artBgPaint);
        drawPrismLight(c, time);
        if (vignette == null || vignetteW != w || vignetteH != h) {
            vignetteW = w; vignetteH = h;
            vignette = new RadialGradient(w / 2f, h / 2f, (float) Math.hypot(w, h) * 0.55f, new int[]{0x00000000, 0x00000000, 0x66000000},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        }
        c.save();
        c.translate(rect.left, rect.top);
        bgPaint.setColor(0xFF000000);
        bgPaint.setShader(vignette);
        c.drawRect(0, 0, w, h, bgPaint);
        c.restore();
        bgPaint.setShader(null);
        bgPaint.setColor(lightArt ? 0x40000000 : 0x1F000000);
        c.drawRect(rect, bgPaint);
        c.restoreToCount(sc);
        return true;
    }
    private void drawBackground(Canvas c, float alpha) {
        if (drawArtBackground(c, alpha)) return;
        long t = SystemClock.uptimeMillis();
        int[] p = new int[4];
        for (int i = 0; i < 4; i++) p[i] = Ui.mix(palFrom[i], pal[i], palMix);
        float W = rect.width(), H = rect.height();
        int sc = c.saveLayerAlpha(rect.left, rect.top, rect.right, rect.bottom, (int) (255 * alpha));
        bgPaint.setColor(0xFF000000); // paint alpha multiplies into shaders: reset after last frame's overlay
        bgPaint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom, p[0], p[3], Shader.TileMode.CLAMP));
        c.drawRect(rect, bgPaint);
        double time = t / 1000.0;
        float[][] blob = {{0.15f, 0.12f, 0.31f, 0f}, {0.85f, 0.3f, 0.23f, 1.7f}, {0.2f, 0.75f, 0.19f, 3.1f}, {0.8f, 0.88f, 0.27f, 4.4f}};
        for (int i = 0; i < 4; i++) {
            float bx = rect.left + W * (blob[i][0] + 0.18f * (float) Math.cos(time * blob[i][2] + blob[i][3]));
            float by = rect.top + H * (blob[i][1] + 0.12f * (float) Math.sin(time * blob[i][2] * 0.8 + blob[i][3]));
            float r = Math.max(W, H) * 0.62f;
            int col = p[(i + 1) % 4];
            bgPaint.setShader(new RadialGradient(bx, by, r, new int[]{Ui.alpha(col, 0.95f), Ui.alpha(col, 0.45f), Ui.alpha(col, 0f)},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(rect, bgPaint);
        }
        bgPaint.setShader(null);
        drawPrismLight(c, time);
        bgPaint.setColor(lightArt ? 0x4D000000 : 0x29000000);
        c.drawRect(rect, bgPaint);
        c.restoreToCount(sc);
        if (palMix < 1f) palMix = Math.min(1f, palMix + 0.03f);
    }

    private void drawArt(Canvas c, float radius) {
        if (art.width() <= 1) return;
        float bgMix = Ui.clamp((openP - 0.3f) / 0.5f, 0f, 1f);
        if (bgMix > 0.01f && mode < 0.99f) {
            shadow.setShadowLayer(Ui.dpf(26), 0, Ui.dpf(12), Ui.alpha(0xFF000000, 0.35f * bgMix * (1f - mode)));
            c.drawRoundRect(art, radius, radius, shadow);
            shadow.clearShadowLayer();
        }
        if (artBmp != null) {
            if (swap < 1f && prevBmp != null && prevPaint.getShader() != null) {
                drawCover(c, prevBmp, prevPaint, Ui.lerp(1f, 0.88f, swap), 1f - swap, radius);
                drawCover(c, artBmp, artPaint, Ui.lerp(1.08f, 1f, swap), swap, radius);
            } else {
                drawCover(c, artBmp, artPaint, 1f, 1f, radius);
            }
        } else {
            bgPaint.setShader(null);
            bgPaint.setColor(0x33FFFFFF);
            c.drawRoundRect(art, radius, radius, bgPaint);
            Icons.draw(c, Icons.NOTE, art.centerX(), art.centerY(), art.width() * 0.4f, 0x99FFFFFF);
        }
        // One UI card: a soft dark fade at the bottom of the cover so the title on it stays readable
        float sh = 0f;   // G style: the title sits below the cover now, no shade on the art
        if (sh > 0.01f) {
            float top = art.top + art.height() * 0.5f;
            bgPaint.setShader(new LinearGradient(0, top, 0, art.bottom, 0x00000000, Ui.alpha(0xB3000000, sh), Shader.TileMode.CLAMP));
            c.save();
            c.clipRect(art.left, top, art.right, art.bottom);
            c.drawRoundRect(art, radius, radius, bgPaint);
            c.restore();
            bgPaint.setShader(null);
        }
    }

    // ---------------------------------------------------------------- prism light
    private final Paint prismPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix prismM = new Matrix();
    private android.graphics.SweepGradient prismSweep;
    private static final int[] SPECTRUM = {0xFFFF3B6B, 0xFFFF9F0A, 0xFFFFE14D, 0xFF34E89E, 0xFF22C3FF, 0xFF5E5CE6, 0xFFC86BFF, 0xFFFF3B6B};

    /**
     * What makes the moving background look like light through a prism: a slowly turning spectrum wash, plus three
     * rainbow light shafts fanning out from one point and swinging gently, all added on top of the blurred cover.
     */
    private void drawPrismLight(Canvas c, double time) {
        float w = rect.width(), h = rect.height();
        if (w <= 0 || h <= 0) return;
        if (prismSweep == null) prismSweep = new android.graphics.SweepGradient(0, 0, SPECTRUM, null);
        prismPaint.setBlendMode(android.graphics.BlendMode.SCREEN);
        // 1) spectrum wash, rotating about a point above the screen
        float cx = rect.left + w * (0.5f + 0.18f * (float) Math.cos(time * 0.09)), cy = rect.top - h * 0.15f;
        prismM.setRotate((float) Math.toDegrees(time * 0.12));
        prismM.postTranslate(cx, cy);
        prismSweep.setLocalMatrix(prismM);
        prismPaint.setShader(prismSweep);
        prismPaint.setAlpha(lightArt ? 40 : 56);
        c.drawRect(rect, prismPaint);
        // 2) three dispersed beams leaving the "prism" at the upper left
        float ox = rect.left - w * 0.1f, oy = rect.top + h * 0.22f;
        float len = (float) Math.hypot(w, h) * 1.3f;
        for (int i = 0; i < 3; i++) {
            double ang = Math.toRadians(14 + i * 13 + 6 * Math.sin(time * 0.21 + i * 1.7));
            float spread = (float) Math.toRadians(5.5 + 1.5 * Math.sin(time * 0.33 + i));
            float ex1 = ox + (float) Math.cos(ang - spread) * len, ey1 = oy + (float) Math.sin(ang - spread) * len;
            float ex2 = ox + (float) Math.cos(ang + spread) * len, ey2 = oy + (float) Math.sin(ang + spread) * len;
            Path beam = new Path();
            beam.moveTo(ox, oy);
            beam.lineTo(ex1, ey1);
            beam.lineTo(ex2, ey2);
            beam.close();
            // colour runs across the beam (red edge → violet edge), fading out along its length
            float mx = (ex1 + ex2) / 2f, my = (ey1 + ey2) / 2f;
            prismPaint.setShader(new LinearGradient(ex1, ey1, ex2, ey2, SPECTRUM, null, Shader.TileMode.CLAMP));
            prismPaint.setAlpha((int) ((lightArt ? 36 : 54) * (0.75f + 0.25f * (float) Math.sin(time * 0.5 + i * 2.1))));
            c.save();
            c.clipPath(beam);
            Paint fade = prismPaint;
            c.drawRect(rect, fade);
            c.restore();
            beam.reset();
        }
        prismPaint.setShader(null);
        prismPaint.setBlendMode(null);
    }

    // ---------------------------------------------------------------- state
    private void onTrack() {
        Player.Track t = Player.track;
        if (t == null) return;
        if (Settings.pref(getContext(), "lyrics", true)) Lyrics.prefetch(t);
        crossfade(title, t.name);
        crossfade(artist, t.artist);
        Bitmap small = Img.cached(t.artSmall);
        if (small != null && (artBmp == null || t.art == null || !t.art.equals(artUrl))) setArt(small);
        final String u = t.art;
        artUrl = u;
        Img.load(u, b -> {
            if (u == null || !u.equals(artUrl) || b == null) return;
            setArt(b);
            palFrom = currentPalette();
            pal = Img.palette(b);
            lightArt = !Img.isDark(b);
            palMix = 0f;
            invalidate();
        });
        if (panel == 1) loadLyrics();
        if (panel == 2) loadQueue();
    }

    private int[] currentPalette() {
        int[] p = new int[4];
        for (int i = 0; i < 4; i++) p[i] = Ui.mix(palFrom[i], pal[i], palMix);
        return p;
    }

    // ---- artwork swap when the song changes: old cover sinks back and fades, new one settles in ----
    private Bitmap prevBmp;
    private final Paint prevPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private float swap = 1f;
    private ValueAnimator swapAnim;
    private String artTrackId;

    private void setArt(Bitmap b) {
        String id = Player.track == null ? null : Player.track.id;
        boolean newSong = artBmp != null && id != null && !id.equals(artTrackId);
        if (newSong && getVisibility() == VISIBLE && openP > 0.5f) {
            prevBmp = artBmp;
            prevPaint.setShader(artPaint.getShader());
            if (swapAnim != null) swapAnim.cancel();
            swapAnim = Ui.animate(0f, 1f, 520, Ui.EMPHASIZED, a -> { swap = (float) a.getAnimatedValue(); invalidate(); });
        }
        artTrackId = id;
        artBmp = b;
        artPaint.setShader(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        invalidate();
    }

    private void drawCover(Canvas c, Bitmap bmp, Paint paint, float scale, float alpha, float radius) {
        float cx = art.centerX(), cy = art.centerY();
        tmp.set(cx - art.width() / 2f * scale, cy - art.height() / 2f * scale, cx + art.width() / 2f * scale, cy + art.height() / 2f * scale);
        float s = Math.max(tmp.width() / bmp.getWidth(), tmp.height() / bmp.getHeight());
        artM.setScale(s, s);
        artM.postTranslate(tmp.left, tmp.top);
        paint.getShader().setLocalMatrix(artM);
        paint.setAlpha((int) (255 * alpha));
        c.drawRoundRect(tmp, radius * scale, radius * scale, paint);
        paint.setAlpha(255);
    }

    private void crossfade(TextView v, String s) {
        if (s == null || s.contentEquals(v.getText())) return;
        if (getVisibility() != VISIBLE || openP < 0.5f) { v.setText(s); if (oneui) v.setSelected(true); return; }
        v.animate().alpha(0f).setDuration(120).withEndAction(() -> { v.setText(s); if (oneui) v.setSelected(true); v.animate().alpha(1f).setDuration(220).start(); }).start();
    }

    /** Switches between the Prism (A) and One UI 9.5 (G) player looks. */
    void applyStyle() {
        boolean o = "oneui".equals(MainActivity.prefs(getContext()).getString("player_style", "prism"));
        boolean changed = o != oneui || outBtn.getTag() == null;
        outBtn.setTag(Boolean.TRUE);
        oneui = o;
        seek.oneui = o;
        outBtn.setVisibility(GONE);
        aodBtn.setVisibility(o ? VISIBLE : GONE);
        if (changed) {
            // One UI 9.5: prominent play circle, softer prev/next circles, pill output chip, centred marquee title/artist
            for (Icons.IconView v : new Icons.IconView[]{prev, next}) v.setBackground(o ? Ui.round(0x21FFFFFF, 100) : null);
            play.setBackground(o ? Ui.round(0x3BFFFFFF, 100) : null);
            prev.sizeDp = next.sizeDp = o ? 26 : 40;
            play.sizeDp = o ? 32 : 46;
            chip.setBackground(o ? Ui.round(0x24FFFFFF, 100) : Ui.round(0x24FFFFFF, 6));
            chip.setPadding(Ui.dp(o ? 12 : 8), Ui.dp(o ? 5 : 3), Ui.dp(o ? 12 : 8), Ui.dp(o ? 5 : 3));
            chip.getChildAt(0).setVisibility(VISIBLE);
            chipText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, o ? 13.5f : 11f);
            chipText.setTextColor(o ? 0xF2FFFFFF : 0xB3FFFFFF);
            chipText.setTypeface(o ? Ui.semibold : Ui.medium);
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, o ? 22 : 21);
            artist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, o ? 16.5f : 19);
            artist.setTextColor(0xB3FFFFFF);
            for (TextView tv : new TextView[]{title, artist}) {
                tv.setSingleLine(true);
                tv.setGravity(o ? Gravity.CENTER : (Gravity.START | Gravity.CENTER_VERTICAL));
                tv.setEllipsize(o ? android.text.TextUtils.TruncateAt.MARQUEE : android.text.TextUtils.TruncateAt.END);
                tv.setMarqueeRepeatLimit(o ? -1 : 0);
                tv.setHorizontalFadingEdgeEnabled(o);
                if (o) tv.setFadingEdgeLength(Ui.dp(18));
                tv.setSelected(o);
            }
            Player.Track t = Player.track;
            if (t != null) { title.setText(t.name); artist.setText(t.artist); }
            content.requestLayout();
        }
        star.setColor(Player.liked ? Ui.ACCENT : 0xFFFFFFFF);
        int activeTint = o ? Ui.alpha(Ui.ACCENT, 0.32f) : 0x26FFFFFF;
        int activeFg = o ? Ui.mix(Ui.ACCENT, 0xFFFFFFFF, 0.45f) : 0xFFFFFFFF;
        shuffleBtn.setColor(Player.shuffle ? activeFg : 0x8CFFFFFF);
        shuffleBtn.setBackground(Player.shuffle ? Ui.round(activeTint, 100) : null);
        boolean rep = !"off".equals(Player.repeat);
        repeatBtn.setColor(rep ? activeFg : 0x8CFFFFFF);
        repeatBtn.setBackground(rep ? Ui.round(activeTint, 100) : null);
        seek.invalidate();
        if (vol != null) vol.invalidate();
        invalidate();
    }

    private void onPlayer() {
        applyStyle();
        play.set(Player.playing ? Icons.PAUSE : Icons.PLAY, getVisibility() == VISIBLE);
        star.set(Player.liked ? Icons.STAR_FILL : Icons.STAR, getVisibility() == VISIBLE);
        repeatBtn.set("track".equals(Player.repeat) ? Icons.REPEAT_ONE : Icons.REPEAT, getVisibility() == VISIBLE);
        star.setVisibility(Player.local() ? INVISIBLE : VISIBLE);
        chipText.setText(Player.outputName(getContext()));
        showTotal = Settings.pref(getContext(), "show_total", false);
        boolean shrink = Settings.pref(getContext(), "shrink_paused", true);
        artScale.to(Player.playing || !shrink ? 1f : 0.86f);
        tick();
    }

    /** Player-screen setting: show the full length on the right instead of the time left. */
    private boolean showTotal;

    private void tick() {
        Player.Track t = Player.track;
        if (t == null) return;
        // hardware volume keys change the level too: follow it while the player is open
        if (vol != null) vol.sync();
        long pos = Player.position();
        if (!seek.scrubbing) {
            seek.frac = t.duration > 0 ? pos / (float) t.duration : 0f;
            seek.invalidate();
            elapsed.setText(Ui.time(pos));
            // either the time left (-2:48) or the song's full length (3:14), per the player-screen setting
            remain.setText(showTotal ? Ui.time(t.duration) : "-" + Ui.time(Math.max(0, t.duration - pos)));
        }
        if (panel == 1) lyrics.setPosition(pos);
    }

    // ---------------------------------------------------------------- panels
    private void setPanel(int p) {
        int old = panel;
        panel = p;
        lyricsBtn.setColor(p == 1 ? 0xFFFFFFFF : 0xB3FFFFFF);
        lyricsBtn.setBackground(p == 1 ? Ui.round(0x26FFFFFF, 24) : null);
        queueBtn.setColor(p == 2 ? 0xFFFFFFFF : 0xB3FFFFFF);
        queueBtn.setBackground(p == 2 ? Ui.round(0x26FFFFFF, 24) : null);
        if (p == 1) loadLyrics();
        if (p == 2) loadQueue();
        if (p != 0) updatePanelVisibility();
        if (wide) {
            // two-pane: the right pane just cross-fades between lyrics and the queue
            mode = 0f;
            updatePanelVisibility();
            panelHost.setAlpha(0f);
            panelHost.animate().alpha(p == 0 ? 0f : 1f).setDuration(260).start();
            applyMode();
            return;
        }
        float to = p == 0 ? 0f : 1f;
        if (modeAnim != null) modeAnim.cancel();
        if (old != 0 && p != 0) {
            panelHost.setAlpha(0f);
            panelHost.animate().alpha(1f).setDuration(220).start();
            return;
        }
        modeAnim = Ui.animate(mode, to, 520, Ui.EMPHASIZED, a -> { mode = (float) a.getAnimatedValue(); applyMode(); });
        modeAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { if (panel == 0) updatePanelVisibility(); }
        });
    }

    private void updatePanelVisibility() {
        panelHost.setVisibility(panel == 0 ? INVISIBLE : VISIBLE);
        lyrics.setVisibility(panel == 1 ? VISIBLE : GONE);
        queueScroll.setVisibility(panel == 2 ? VISIBLE : GONE);
    }

    private void loadLyrics() {
        lyrics.set(null, getContext().getString(R.string.loading));   // cached lyrics replace this synchronously
        if (!Settings.pref(getContext(), "lyrics", true)) { lyrics.set(null, getContext().getString(R.string.lyrics_off)); return; }
        final Player.Track t = Player.track;
        Lyrics.fetch(t, r -> {
            if (t != Player.track) return;
            lyrics.set(r, getContext().getString(R.string.no_lyrics));
            tick();
        });
    }

    private View queueRow(Context c, String name, String artist, String art) {
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(28), Ui.dp(6), Ui.dp(28), Ui.dp(6));
        Img.ArtView a = new Img.ArtView(c, 6);
        a.setUrl(art);
        r.addView(a, new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(12), 0, 0, 0);
        col.addView(Ui.text(c, name, 15, Ui.medium, 0xFFFFFFFF), Widgets.MW);
        col.addView(Ui.text(c, artist, 13, Ui.regular, 0x99FFFFFF), Widgets.MW);
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(new Icons.IconView(c, Icons.DRAG, 20, 0x66FFFFFF), new LinearLayout.LayoutParams(Ui.dp(28), Ui.dp(28)));
        return r;
    }

    /** Queue rows are tappable: a soft highlight while pressed, then that song plays. */
    private static void tapToPlay(View row, Runnable play) {
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, Ui.round(0xFFFFFFFF, 14)));
        row.setClickable(true);
        row.setOnClickListener(v -> play.run());
    }

    private void loadQueue() {
        Context c = getContext();
        queueList.removeAllViews();
        // row of wide translucent toggle capsules (shuffle / repeat / autoplay) queue view
        LinearLayout toggles = new LinearLayout(c);
        toggles.setPadding(Ui.dp(24), 0, Ui.dp(24), Ui.dp(14));
        boolean rep = !"off".equals(Player.repeat);
        boolean auto = Settings.pref(c, "autoplay", true);
        int[][] spec = {{Icons.SHUFFLE, Player.shuffle ? 1 : 0}, {"track".equals(Player.repeat) ? Icons.REPEAT_ONE : Icons.REPEAT, rep ? 1 : 0}, {Icons.INFINITY, auto ? 1 : 0}};
        for (int i = 0; i < spec.length; i++) {
            final int which = i;
            Icons.IconView b = new Icons.IconView(c, spec[i][0], 20, spec[i][1] == 1 ? 0xFF1C1C1E : 0xFFFFFFFF);
            b.setBackground(Ui.round(spec[i][1] == 1 ? 0xE6FFFFFF : 0x29FFFFFF, 100));
            b.setOnClickListener(v -> {
                if (which == 0) Player.toggleShuffle();
                else if (which == 1) Player.cycleRepeat();
                else MainActivity.prefs(c).edit().putBoolean("autoplay", !Settings.pref(c, "autoplay", true)).apply();
                loadQueue();
                onPlayer();
            });
            Ui.press(b, 0.92f);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, Ui.dp(36), 1f);
            if (i > 0) bl.leftMargin = Ui.dp(10);
            toggles.addView(b, bl);
        }
        queueList.addView(toggles, Widgets.MW);
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(Ui.dp(28), 0, Ui.dp(20), Ui.dp(8));
        head.addView(Ui.text(c, c.getString(R.string.continue_playing), 17, Ui.bold, 0xFFFFFFFF), Widgets.MW);
        Player.Track cur = Player.track;
        if (cur != null && cur.album != null && !cur.album.isEmpty())
            head.addView(Ui.text(c, cur.album, 13, Ui.regular, 0x99FFFFFF), Widgets.MW);
        queueList.addView(head, Widgets.MW);
        if (Player.local()) {
            List<Player.Track> up = LocalMusic.upNext();
            if (up.isEmpty()) queueList.addView(Ui.text(c, c.getString(R.string.queue_empty), 14, Ui.regular, 0x99FFFFFF));
            for (int i = 0; i < up.size(); i++) {
                Player.Track tr = up.get(i);
                final int at = i;
                View row = queueRow(c, tr.name, tr.artist, tr.artSmall);
                tapToPlay(row, () -> { LocalMusic.jumpUpNext(at); Spotify.MAIN.postDelayed(this::loadQueue, 120); });
                queueList.addView(row, Widgets.MW);
            }
            return;
        }
        View sp = Widgets.spinner(c, 80);
        queueList.addView(sp);
        Player.queue(new Spotify.Cb() {
            @Override public void ok(JSONObject o) {
                queueList.removeView(sp);
                JSONArray q = o.optJSONArray("queue");
                if (q == null || q.length() == 0) { queueList.addView(Ui.text(c, c.getString(R.string.queue_empty), 14, Ui.regular, 0x99FFFFFF)); return; }
                for (int i = 0; i < Math.min(30, q.length()); i++) {
                    JSONObject tr = q.optJSONObject(i);
                    if (tr == null) continue;
                    LinearLayout r = new LinearLayout(c);
                    r.setGravity(Gravity.CENTER_VERTICAL);
                    r.setPadding(Ui.dp(28), Ui.dp(6), Ui.dp(28), Ui.dp(6));
                    Img.ArtView a = new Img.ArtView(c, 6);
                    a.setUrl(Spotify.image(tr, 120));
                    r.addView(a, new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
                    LinearLayout col = new LinearLayout(c);
                    col.setOrientation(LinearLayout.VERTICAL);
                    col.setPadding(Ui.dp(12), 0, 0, 0);
                    col.addView(Ui.text(c, tr.optString("name"), 15, Ui.medium, 0xFFFFFFFF), Widgets.MW);
                    col.addView(Ui.text(c, Spotify.artists(tr), 13, Ui.regular, 0x99FFFFFF), Widgets.MW);
                    r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    final int at = i;
                    tapToPlay(r, () -> { Player.playFromQueue(q, at); Spotify.MAIN.postDelayed(NowPlaying.this::loadQueue, 1500); });
                    queueList.addView(r, Widgets.MW);
                }
            }
            @Override public void err(int code, String m) {
                queueList.removeView(sp);
                queueList.addView(Ui.text(c, c.getString(R.string.load_failed), 14, Ui.regular, 0x99FFFFFF));
            }
        });
    }

    // ---------------------------------------------------------------- menus
    private RectF anchorOf(View v) {
        float[] o = Glass.abs(v);
        return new RectF(o[0], o[1], o[0] + v.getWidth(), o[1] + v.getHeight());
    }

    private void moreMenu(View v) {
        Context c = getContext();
        List<GlassMenu.Entry> e = new ArrayList<>();
        Player.Track t = Player.track;
        if (t == null) return;
        if (t.albumUri != null) e.add(new GlassMenu.Entry(c.getString(R.string.go_album), Icons.ALBUM, false, () -> { close(); main.openUri(t.albumUri, t.album); }));
        if (t.artistUri != null) e.add(new GlassMenu.Entry(c.getString(R.string.go_artist), Icons.ARTIST, false, () -> { close(); main.openUri(t.artistUri, t.artist); }));
        e.add(new GlassMenu.Entry(c.getString(Player.liked ? R.string.unlike : R.string.like), Player.liked ? Icons.STAR_FILL : Icons.STAR, false, Player::toggleLike));
        if (!Player.local()) e.add(new GlassMenu.Entry(c.getString(R.string.open_in_spotify), Icons.LINK, false, () -> Player.openInSpotify(t.uri)));
        e.add(new GlassMenu.Entry(c.getString(R.string.up_next), Icons.LIST, false, () -> setPanel(2)));
        e.add(new GlassMenu.Entry(c.getString(R.string.set_lyrics), Icons.LYRICS, false, () -> setPanel(1)));
        e.add(new GlassMenu.Entry(c.getString(R.string.aod_title), Icons.AOD, false, main::openAod));
        e.add(new GlassMenu.Entry(c.getString(R.string.eq_title), Icons.WAVE, false, () -> { close(); main.push(new Settings.EqPage(main)); }));
        GlassMenu.sheet(main, anchorOf(v), c.getString(R.string.more_actions), e, v);
    }

    private void goArtist() {
        Player.Track t = Player.track;
        if (t != null && t.artistUri != null) { close(); main.openUri(t.artistUri, t.artist); }
    }

    /** Output-device chip opens the same glass sheet, growing out of the chip itself. */
    void deviceMenu(View v) {
        if (Player.local()) { Player.toast(R.string.this_device); return; }
        RectF a = anchorOf(v);
        Player.devices(new Spotify.Cb() {
            @Override public void ok(JSONObject o) {
                List<GlassMenu.Entry> e = new ArrayList<>();
                JSONArray ds = o.optJSONArray("devices");
                if (ds != null) for (int i = 0; i < ds.length(); i++) {
                    JSONObject d = ds.optJSONObject(i);
                    GlassMenu.Entry en = new GlassMenu.Entry(d.optString("name") + "  ·  " + d.optString("type"), Icons.DEVICE, d.optBoolean("is_active"), () -> Player.transfer(d.optString("id")));
                    en.checkable = true;
                    e.add(en);
                }
                e.add(new GlassMenu.Entry(getContext().getString(R.string.open_spotify_app), Icons.LINK, false, () -> Player.openInSpotify("spotify:")));
                GlassMenu.sheet(main, a, getContext().getString(R.string.devices), e, v);
            }
            @Override public void err(int c, String m) { Player.toast(R.string.load_failed); }
        });
    }

    void deviceMenuAt(RectF a) { deviceMenuAt(a, false); }

    void deviceMenuAt(RectF a, boolean darkStyle) {
        Player.devices(new Spotify.Cb() {
            @Override public void ok(JSONObject o) {
                List<GlassMenu.Entry> e = new ArrayList<>();
                JSONArray ds = o.optJSONArray("devices");
                if (ds != null) for (int i = 0; i < ds.length(); i++) {
                    JSONObject d = ds.optJSONObject(i);
                    GlassMenu.Entry en = new GlassMenu.Entry(d.optString("name"), Icons.DEVICE, d.optBoolean("is_active"), () -> Player.transfer(d.optString("id")));
                    en.checkable = true;
                    e.add(en);
                }
                e.add(new GlassMenu.Entry(getContext().getString(R.string.open_spotify_app), Icons.LINK, false, () -> Player.openInSpotify("spotify:")));
                GlassMenu.show(main, a, e, darkStyle);
            }
            @Override public void err(int c, String m) { Player.toast(R.string.load_failed); }
        });
    }

    // ---------------------------------------------------------------- swipe down to dismiss
    private float downX, downY;
    private boolean dragging;
    private VelocityTracker vt;
    private final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY(); dragging = false;
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (vt != null) vt.addMovement(e);
                float dy = e.getY() - downY, dx = e.getX() - downX;
                boolean inPanel = panel != 0 && downY > panelRect.top && downY < panelRect.bottom;
                if (!dragging && !seek.scrubbing && !inPanel && dy > slop * 1.5f && dy > Math.abs(dx) * 1.3f && openP > 0.95f) {
                    dragging = true;
                    downY = e.getY();
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (vt != null) vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && e.getY() - downY > slop) { dragging = true; downY = e.getY(); }
                if (dragging) {
                    if (dragAnim != null) dragAnim.cancel();
                    dragY = Math.max(0, e.getY() - downY);
                    main.onPlayerProgress(openP * (1f - Ui.clamp(dragY / getHeight(), 0f, 1f) * 0.6f));
                    invalidate();
                    content.invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (dragging) {
                    float vy = 0;
                    if (vt != null) { vt.computeCurrentVelocity(1000); vy = vt.getYVelocity(); }
                    dragging = false;
                    if (dragY > getHeight() * 0.16f || vy > 1400) {
                        close();
                    } else {
                        dragAnim = Ui.animate(dragY, 0f, 380, Ui.EMPHASIZED, a -> { dragY = (float) a.getAnimatedValue(); main.onPlayerProgress(openP); invalidate(); content.invalidate(); });
                    }
                }
                if (vt != null) { vt.recycle(); vt = null; }
                return true;
            }
        }
        return true;
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Player.listeners.remove(playerL);
        Player.trackListeners.remove(trackL);
    }

    // ================================================================ seek bar
    private static final class Seek extends View {
        float frac;
        boolean scrubbing;
        private final Ui.Spring thick = new Ui.Spring(600f, 0.8f, 0.002f);
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        NowPlaying np;

        Seek(Context c) {
            super(c);
            thick.onUpdate = this::invalidate;
        }

        boolean oneui;

        @Override protected void onDraw(Canvas c) {
            if (oneui) { drawOneUi(c); return; }
            float k = thick.value;
            float pad = Ui.dpf(8) - Ui.dpf(6) * k;
            float h = Ui.lerp(Ui.dpf(6), Ui.dpf(11), k);
            float cy = getHeight() / 2f;
            r.set(pad, cy - h / 2, getWidth() - pad, cy + h / 2);
            p.setColor(0x3DFFFFFF);
            c.drawRoundRect(r, h / 2, h / 2, p);
            c.save();
            c.clipRect(r.left, r.top, r.left + r.width() * Ui.clamp(frac, 0f, 1f), r.bottom);
            p.setColor(scrubbing ? 0xFFFFFFFF : 0xD9FFFFFF);
            c.drawRoundRect(r, h / 2, h / 2, p);
            c.restore();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float pad = Ui.dpf(oneui ? 10 : 8);
            float f = Ui.clamp((e.getX() - pad) / (getWidth() - pad * 2), 0f, 1f);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    scrubbing = true;
                    thick.to(1f);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    frac = f;
                    if (Player.track != null) updateTimes();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    frac = f;
                    if (Player.track != null) Player.seek((long) (f * Player.track.duration));
                    // fall through
                case MotionEvent.ACTION_CANCEL:
                    scrubbing = false;
                    thick.to(0f);
                    return true;
            }
            return true;
        }

        // ---- G style: wave seek bar (port of OneTaskBar 9.0 MediaWave) ----
        private float waveK;                 // wave strength 0..1, eases with play / pause (0.26 s)
        private long waveLast;
        private final long waveT0 = SystemClock.uptimeMillis();
        private final Path wavePath = new Path();

        /** Height of one wave layer at t (0..1 along the played part) and its slope, as in MediaWave.Height. */
        private static float waveHeight(double t, double phase, int layer, float[] slope) {
            double angle = Math.PI * t, frequency = Math.PI * (4.2 + layer * .6);
            double osc = frequency * t - phase * (1 + layer * .12) + layer * 1.8;
            double env = Math.sin(angle), level = .71 + .29 * Math.sin(osc);
            slope[0] = (float) (Math.PI * Math.sin(2 * angle) * level + env * env * .29 * frequency * Math.cos(osc));
            return (float) (env * env * level);
        }

        /** Closed wave shape over [x0, x0+length], rising from the baseline, cubic segments keeping tangents. */
        private void waveShape(Path out, float x0, float length, float base, float amp, double phase, int layer) {
            out.reset();
            int samples = Math.max(16, Math.min(64, (int) Math.ceil(length / Ui.dpf(20))));
            float step = 1f / samples;
            float[] s = new float[1];
            waveHeight(0, phase, layer, s);
            float prevSlope = s[0], px = x0, py = base;
            out.moveTo(px, py);
            for (int i = 1; i <= samples; i++) {
                float t = i * step;
                float lv = waveHeight(t, phase, layer, s);
                float nx = x0 + length * t, ny = base - amp * lv;
                float c1x = px + length * step / 3f, c1y = Ui.clamp(py - amp * prevSlope * step / 3f, base - amp, base);
                float c2x = nx - length * step / 3f, c2y = Ui.clamp(ny + amp * s[0] * step / 3f, base - amp, base);
                out.cubicTo(c1x, c1y, c2x, c2y, nx, ny);
                px = nx; py = ny; prevSlope = s[0];
            }
            out.close();
        }

        /** G style bar: grey track, accent played part with three rolling translucent waves, glowing round thumb. */
        private void drawOneUi(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float dt = waveLast == 0 ? 0 : (now - waveLast) / 1000f;
            waveLast = now;
            float target = Player.playing && !scrubbing ? 1f : 0f;
            waveK += (target - waveK) * Math.min(1f, dt / 0.26f * 1.6f);
            if (Math.abs(target - waveK) < 0.002f) waveK = target;
            float pad = Ui.dpf(10);
            float span = Math.max(0, getWidth() - pad * 2);
            float y = Math.max(Ui.dpf(6), getHeight() - Ui.dpf(7));
            float len = span * Ui.clamp(frac, 0f, 1f), x = pad + len;
            int acc = Ui.ACCENT;
            int light = Ui.mix(acc, 0xFFFFFFFF, 0.5f);
            // track
            p.setColor(0x608E9695);
            r.set(pad, y - Ui.dpf(1.6f), pad + span, y + Ui.dpf(1.6f));
            c.drawRoundRect(r, Ui.dpf(1.6f), Ui.dpf(1.6f), p);
            // waves over the played part
            if (waveK > 0.001f && len > 0) {
                double phase = (now - waveT0) / 1000.0 * 2.7;
                int[] cols = {Ui.alpha(light, 112 / 255f), Ui.alpha(0xFFFFFFFF, 144 / 255f), Ui.alpha(acc, 144 / 255f)};
                for (int layer = 0; layer < 3; layer++) {
                    float amp = Math.max(0, y - Ui.dpf(3)) * (1 - layer * .14f) * waveK;
                    waveShape(wavePath, pad, len, y, amp, phase, layer);
                    p.setColor(cols[layer]);
                    c.drawPath(wavePath, p);
                }
            }
            if (len > 0) {
                p.setColor(acc);
                r.set(pad, y - Ui.dpf(1.8f), x, y + Ui.dpf(1.8f));
                c.drawRoundRect(r, Ui.dpf(1.8f), Ui.dpf(1.8f), p);
            }
            // thumb: soft glow rings, white disc, accent core
            float grow = 1f + 0.25f * thick.value;
            for (int i = 0; i < 3; i++) {
                p.setColor(Ui.alpha(acc, (i == 0 ? 15 : i == 1 ? 36 : 64) / 255f));
                c.drawCircle(x, y, Ui.dpf(10 - i * 2) * grow, p);
            }
            p.setColor(0xFFFFFFFF);
            c.drawCircle(x, y, Ui.dpf(6) * grow, p);
            p.setColor(acc);
            c.drawCircle(x, y, Ui.dpf(4.4f) * grow, p);
            // keep rolling while playing (or while the waves settle), only when on screen
            if ((waveK > 0.001f || target > 0) && isShown()) postInvalidateOnAnimation();
            else waveLast = 0;
        }

        private void updateTimes() {
            ViewGroup parent = (ViewGroup) getParent();
            if (parent == null) return;
            NowPlaying owner = (NowPlaying) parent.getParent();
            long pos = (long) (frac * Player.track.duration);
            owner.elapsed.setText(Ui.time(pos));
            owner.remain.setText(owner.showTotal ? Ui.time(Player.track.duration) : "-" + Ui.time(Player.track.duration - pos));
        }
    }

    // ================================================================ volume bar
    /**
     * Media volume: this phone's media stream (device music, in-app Spotify) or the Spotify Connect device's volume.
     * Quiet speaker · bar · loud speaker, drag or tap anywhere on it.
     */
    private final class VolumeBar extends View {
        private float value = -1f;
        private boolean dragging;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        VolumeBar(Context c) { super(c); }

        void sync() {
            if (dragging) return;
            float v = Player.volume01(getContext());
            if (v != value) { value = v; invalidate(); }
        }

        private float left() { return Ui.dpf(34); }
        private float right() { return getWidth() - Ui.dpf(34); }

        @Override protected void onDraw(Canvas c) {
            float cy = getHeight() / 2f;
            boolean known = value >= 0;
            int dim = known ? 0xB3FFFFFF : 0x4DFFFFFF;
            Icons.draw(c, Icons.VOLUME_LOW, Ui.dpf(12), cy, Ui.dpf(18), dim);
            Icons.draw(c, Icons.VOLUME, getWidth() - Ui.dpf(12), cy, Ui.dpf(20), dim);
            float h = Ui.dpf(dragging ? 7 : 5);
            r.set(left(), cy - h / 2, right(), cy + h / 2);
            p.setColor(0x3DFFFFFF);
            c.drawRoundRect(r, h / 2, h / 2, p);
            if (!known) return;
            r.right = left() + (right() - left()) * value;
            p.setColor(oneui ? Ui.ACCENT : 0xD9FFFFFF);
            c.drawRoundRect(r, h / 2, h / 2, p);
            if (oneui || dragging) {
                p.setColor(0xFFFFFFFF);
                c.drawCircle(r.right, cy, Ui.dpf(dragging ? 10 : 8), p);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (value < 0) return false;
            float v = Ui.clamp((e.getX() - left()) / (right() - left()), 0f, 1f);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    dragging = true;
                    // fall through
                case MotionEvent.ACTION_MOVE:
                    value = v;
                    Player.setVolume01(getContext(), v, false);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    value = v;
                    Player.setVolume01(getContext(), v, true);
                    dragging = false;
                    invalidate();
                    return true;
            }
            return true;
        }
    }

    // ================================================================ lyrics
    private final class LyricsView extends View {
        private Lyrics.Result res;
        private String message = "";
        private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final List<StaticLayout> layouts = new ArrayList<>();
        private int laidW = -1;
        private int current = -1;
        private final Ui.Spring offset = new Ui.Spring(160f, 0.9f, 0.5f);
        private float manual;
        private long manualUntil;
        private float lastY, startY;
        private final Paint fade = new Paint();
        private final float gap = Ui.dpf(22);

        LyricsView(Context c) {
            super(c);
            tp.setTypeface(Ui.heavy);
            tp.setTextSize(Ui.sp(26));
            tp.setColor(0xFFFFFFFF);
            fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            offset.onUpdate = this::invalidate;
        }

        void set(Lyrics.Result r, String msg) {
            res = r;
            message = msg;
            laidW = -1;
            current = -1;
            manual = 0;
            offset.snap(0);
            invalidate();
        }

        void setPosition(long pos) {
            if (res == null || !res.synced) return;
            int idx = -1;
            for (int i = 0; i < res.lines.size(); i++) if (res.lines.get(i).time <= pos + 250) idx = i;
            if (idx != current) {
                current = idx;
                ensureLayouts();
                if (SystemClock.uptimeMillis() > manualUntil) { manual = 0; offset.to(targetOffset()); }
                invalidate();
            }
        }

        private void ensureLayouts() {
            int w = getWidth() - Ui.dp(56);
            if (w <= 0 || w == laidW || res == null) return;
            laidW = w;
            layouts.clear();
            for (Lyrics.Line l : res.lines) {
                String s = l.text.isEmpty() ? "♪" : l.text;
                layouts.add(StaticLayout.Builder.obtain(s, 0, s.length(), tp, w).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0, 1.05f).build());
            }
        }

        private float targetOffset() {
            float y = 0;
            for (int i = 0; i < Math.max(0, current) && i < layouts.size(); i++) y += layouts.get(i).getHeight() + gap;
            return y - getHeight() * 0.22f;
        }

        @Override protected void onDraw(Canvas c) {
            if (res == null) {
                tp.setTextSize(Ui.sp(18));
                tp.setColor(0x99FFFFFF);
                c.drawText(message, Ui.dpf(28), Ui.dpf(40), tp);
                tp.setTextSize(Ui.sp(26));
                return;
            }
            ensureLayouts();
            int sc = c.saveLayer(0, 0, getWidth(), getHeight(), null);
            float y = -offset.value - manual + Ui.dpf(16);
            for (int i = 0; i < layouts.size(); i++) {
                StaticLayout l = layouts.get(i);
                if (y + l.getHeight() > 0 && y < getHeight()) {
                    boolean on = !res.synced || i == current;
                    tp.setColor(on ? 0xFFFFFFFF : 0x59FFFFFF);
                    c.save();
                    c.translate(Ui.dpf(28), y);
                    l.draw(c);
                    c.restore();
                }
                y += l.getHeight() + gap;
            }
            fade.setShader(new LinearGradient(0, 0, 0, getHeight(), new int[]{0, 0xFF000000, 0xFF000000, 0},
                    new float[]{0f, 0.08f, 0.85f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, getWidth(), getHeight(), fade);
            c.restoreToCount(sc);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastY = startY = e.getY();
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    manual += lastY - e.getY();
                    lastY = e.getY();
                    manualUntil = SystemClock.uptimeMillis() + 3000;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (Math.abs(e.getY() - startY) < Ui.dpf(8) && res != null && res.synced) {
                        float y = -offset.value - manual + Ui.dpf(16);
                        for (int i = 0; i < layouts.size(); i++) {
                            float h = layouts.get(i).getHeight();
                            if (e.getY() >= y && e.getY() <= y + h) {
                                manualUntil = 0;
                                Player.seek(res.lines.get(i).time);
                                break;
                            }
                            y += h + gap;
                        }
                    }
                    return true;
            }
            return true;
        }
    }

    static String ellipsize(String s, TextPaint p, float w) { return TextUtils.ellipsize(s, p, w, TextUtils.TruncateAt.END).toString(); }
}
