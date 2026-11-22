package com.oneuibook.prism;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Full-screen OLED Always-On Display (AOD) mode.
 * Mirrors the expanded media player geometry 1:1 so entering AOD feels like the live player
 * smoothly dimming into pure OLED black, then subtly drifts the entire UI cluster in tiny
 * imperceptible steps (±1.5–2.5 dp over 1.8 s every 24 s) to prevent OLED burn-in.
 */
final class AodOverlay extends FrameLayout {
    private final MainActivity main;
    private final Cluster cluster;
    private final TextView clockView, titleView, artistView, timeL, timeR, hintView;
    private final Img.ArtView artView;
    private final Icons.IconView prevBtn, playBtn, nextBtn;
    private final ProgressBar bar;

    private boolean shown;
    private float dimP; // 0 = transparent (live player visible), 1 = full dimmed OLED AOD
    private ValueAnimator dimAnim;
    private int shiftStep;
    private long lastTap;
    private float downX, downY;

    // 16-point smooth oval orbit inside ±7dp: each step moves only ~1.8–2.5dp over 1.8s (imperceptible)
    private static final float[] SHIFT_DX = {
            0f, 2.0f, 3.8f, 5.2f, 6.0f, 5.5f, 4.0f, 2.0f,
            0f, -2.0f, -4.0f, -5.5f, -6.0f, -5.2f, -3.8f, -2.0f
    };
    private static final float[] SHIFT_DY = {
            -6.0f, -5.4f, -4.0f, -2.0f, 0f, 2.2f, 4.2f, 5.6f,
            6.0f, 5.4f, 4.0f, 2.0f, 0f, -2.2f, -4.2f, -5.6f
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!shown) return;
            syncClock();
            syncProgress();
            postDelayed(this, 1000);
        }
    };

    private final Runnable shift = new Runnable() {
        @Override public void run() {
            if (!shown) return;
            shiftStep = (shiftStep + 1) % SHIFT_DX.length;
            cluster.animate()
                    .translationX(Ui.dpf(SHIFT_DX[shiftStep]))
                    .translationY(Ui.dpf(SHIFT_DY[shiftStep]))
                    .setDuration(1800)
                    .setInterpolator(Ui.ONEUI_SINE_80)
                    .start();
            postDelayed(this, 24000);
        }
    };

    private final Runnable playerL = this::syncTrackAndState;

    AodOverlay(MainActivity m) {
        super(m);
        main = m;
        setWillNotDraw(false);
        setClickable(true);
        setVisibility(GONE);

        cluster = new Cluster(m);
        addView(cluster, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        clockView = Ui.text(m, "12:00", 14f, Ui.medium, 0x80FFFFFF);
        clockView.setLetterSpacing(0.02f);
        clockView.setGravity(Gravity.CENTER);
        cluster.addView(clockView);

        artView = new Img.ArtView(m, 30);
        cluster.addView(artView);

        titleView = Ui.text(m, "", 22f, Ui.bold, 0xCCFFFFFF);
        enableMarquee(titleView);
        cluster.addView(titleView);

        artistView = Ui.text(m, "", 16.5f, Ui.regular, 0x7AFFFFFF);
        enableMarquee(artistView);
        cluster.addView(artistView);

        bar = new ProgressBar(m);
        cluster.addView(bar);

        timeL = Ui.text(m, "0:00", 12f, Ui.medium, 0x66FFFFFF);
        timeR = Ui.text(m, "-0:00", 12f, Ui.medium, 0x66FFFFFF);
        timeR.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        cluster.addView(timeL);
        cluster.addView(timeR);

        prevBtn = aodBtn(m, Icons.PREV, 26, v -> Player.prev());
        playBtn = aodBtn(m, Icons.PAUSE, 32, v -> Player.toggle());
        nextBtn = aodBtn(m, Icons.NEXT, 26, v -> Player.next());
        cluster.addView(prevBtn);
        cluster.addView(playBtn);
        cluster.addView(nextBtn);

        hintView = Ui.text(m, m.getString(R.string.aod_hint), 11.5f, Ui.regular, 0x52FFFFFF);
        hintView.setGravity(Gravity.CENTER);
        cluster.addView(hintView);
    }

    private static void enableMarquee(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        t.setMarqueeRepeatLimit(-1);
        t.setHorizontalFadingEdgeEnabled(true);
        t.setFadingEdgeLength(Ui.dp(18));
        t.setSelected(true);
    }

    private Icons.IconView aodBtn(MainActivity m, int ic, float sizeDp, OnClickListener click) {
        Icons.IconView v = new Icons.IconView(m, ic, sizeDp, 0xB3FFFFFF);
        v.setBackground(Ui.round(0x14FFFFFF, 100));
        v.setOnClickListener(click);
        Ui.press(v, 0.88f);
        return v;
    }

    boolean isOpen() { return shown; }

    void open() {
        if (shown) return;
        shown = true;
        setVisibility(VISIBLE);
        bringToFront();
        shiftStep = 0;
        cluster.animate().cancel();
        cluster.setTranslationX(0f);
        cluster.setTranslationY(0f);
        applyPlayerLook();
        syncClock();
        syncTrackAndState();
        Player.listeners.add(playerL);
        Player.trackListeners.add(playerL);
        applyWindowMode(true);
        hintView.animate().cancel();
        hintView.setAlpha(0.75f);
        animateDim(1f, 560, null);
        removeCallbacks(tick);
        removeCallbacks(shift);
        post(tick);
        postDelayed(shift, 24000);
        hintView.animate().alpha(0.26f).setStartDelay(3200).setDuration(1000).start();
    }

    void close() {
        if (!shown) return;
        shown = false;
        removeCallbacks(tick);
        removeCallbacks(shift);
        cluster.animate().cancel();
        Player.listeners.remove(playerL);
        Player.trackListeners.remove(playerL);
        applyWindowMode(false);
        animateDim(0f, 420, () -> {
            if (!shown) setVisibility(GONE);
        });
    }

    private void animateDim(float to, long ms, Runnable end) {
        if (dimAnim != null) dimAnim.cancel();
        dimAnim = Ui.animate(dimP, to, ms, Ui.ONEUI_SINE_80, a -> {
            dimP = (float) a.getAnimatedValue();
            applyDimVisuals();
            invalidate();
        });
        if (end != null) {
            dimAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                boolean canceled;
                @Override public void onAnimationCancel(android.animation.Animator a) { canceled = true; }
                @Override public void onAnimationEnd(android.animation.Animator a) { if (!canceled) end.run(); }
            });
        }
    }

    /** Smoothly dims the mirrored player chrome as the pure-black OLED backdrop settles in. */
    private void applyDimVisuals() {
        float k = Ui.clamp(dimP, 0f, 1f);
        cluster.setAlpha(Ui.clamp(k * 1.45f, 0f, 1f));
        // Subtle OLED dim + calm settle scale so entering AOD feels seamless from the live player
        float artA = Ui.lerp(0.95f, 0.56f, k);
        float artS = Ui.lerp(1f, 0.965f, k);
        artView.setAlpha(artA);
        artView.setScaleX(artS);
        artView.setScaleY(artS);
        float textA = Ui.lerp(1f, 0.78f, k);
        titleView.setAlpha(textA);
        artistView.setAlpha(textA);
        bar.setAlpha(Ui.lerp(0.95f, 0.72f, k));
        timeL.setAlpha(Ui.lerp(0.9f, 0.65f, k));
        timeR.setAlpha(Ui.lerp(0.9f, 0.65f, k));
        float ctrlA = Ui.lerp(0.95f, 0.68f, k);
        prevBtn.setAlpha(ctrlA);
        playBtn.setAlpha(ctrlA);
        nextBtn.setAlpha(ctrlA);
        clockView.setAlpha(Ui.clamp((k - 0.35f) / 0.65f, 0f, 1f) * 0.68f);
    }

    @Override protected void onDraw(Canvas c) {
        int a = Math.round(255 * Ui.clamp(dimP, 0f, 1f));
        c.drawColor(a << 24);
    }

    private void applyPlayerLook() {
        boolean o = Ui.gStyle;
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, o ? 22f : 21f);
        artistView.setTextSize(TypedValue.COMPLEX_UNIT_SP, o ? 16.5f : 19f);
        int grav = o ? Gravity.CENTER : (Gravity.START | Gravity.CENTER_VERTICAL);
        titleView.setGravity(grav);
        artistView.setGravity(grav);
        prevBtn.sizeDp = nextBtn.sizeDp = o ? 26f : 36f;
        playBtn.sizeDp = o ? 32f : 42f;
        for (Icons.IconView v : new Icons.IconView[]{prevBtn, nextBtn}) v.setBackground(o ? Ui.round(0x14FFFFFF, 100) : null);
        playBtn.setBackground(o ? Ui.round(0x21FFFFFF, 100) : null);
        cluster.requestLayout();
    }

    private void applyWindowMode(boolean enter) {
        Window w = main.getWindow();
        if (w == null) return;
        if (enter) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = w.getAttributes();
        boolean dim = Settings.pref(main, "aod_dim", true);
        lp.screenBrightness = (enter && dim) ? 0.08f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        w.setAttributes(lp);
        WindowInsetsController c = w.getInsetsController();
        if (c != null) {
            if (enter) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            } else {
                c.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        }
    }

    private void syncClock() {
        Date now = new Date();
        Locale loc = getResources().getConfiguration().getLocales().get(0);
        String hm = new SimpleDateFormat("HH:mm", loc).format(now);
        String dpat = Locale.KOREAN.getLanguage().equals(loc.getLanguage()) ? "M월 d일 EEEE"
                : Locale.JAPANESE.getLanguage().equals(loc.getLanguage()) ? "M月d日 (E)" : "EEE, MMM d";
        clockView.setText(hm + "  ·  " + new SimpleDateFormat(dpat, loc).format(now));
    }

    private void syncTrackAndState() {
        Player.Track t = Player.track;
        if (t == null) {
            artView.setVisibility(INVISIBLE);
            titleView.setText(main.getString(R.string.nothing_playing));
            artistView.setText("");
            bar.setVisibility(INVISIBLE);
            timeL.setText("");
            timeR.setText("");
        } else {
            artView.setVisibility(VISIBLE);
            bar.setVisibility(VISIBLE);
            artView.setUrl(t.art != null ? t.art : t.artSmall);
            titleView.setText(t.name);
            artistView.setText(t.artist);
            titleView.setSelected(true);
            artistView.setSelected(true);
        }
        playBtn.set(Player.playing ? Icons.PAUSE : Icons.PLAY, false);
        syncProgress();
    }

    private void syncProgress() {
        Player.Track t = Player.track;
        if (t == null || t.duration <= 0) { bar.frac = 0f; bar.invalidate(); return; }
        long pos = Player.position();
        bar.frac = Ui.clamp(pos / (float) t.duration, 0f, 1f);
        bar.invalidate();
        timeL.setText(Ui.time(pos));
        boolean showTotal = Settings.pref(main, "show_total", false);
        timeR.setText(showTotal ? Ui.time(t.duration) : "-" + Ui.time(Math.max(0, t.duration - pos)));
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (downY - e.getY() > Ui.dpf(72) && Math.abs(e.getX() - downX) < Ui.dpf(80)) {
                    close();
                    return true;
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (Math.hypot(e.getX() - downX, e.getY() - downY) < Ui.dpf(18)) {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastTap < 340) {
                        lastTap = 0;
                        close();
                    } else {
                        lastTap = now;
                        hintView.animate().cancel();
                        hintView.setAlpha(0.75f);
                        hintView.animate().alpha(0.26f).setStartDelay(2000).setDuration(800).start();
                    }
                }
                return true;
        }
        return true;
    }

    /**
     * Exact geometric mirror of {@link NowPlaying}'s expanded player layout (portrait + wide foldable/tablet),
     * translated as a single cluster during burn-in pixel shift.
     */
    private final class Cluster extends FrameLayout {
        private final RectF artSlot = new RectF();
        private float titleY, seekY, timesY, ctrlY, bottomY, colX, colW;
        private boolean wide;

        Cluster(Context c) {
            super(c);
            setClipChildren(false);
        }

        private void compute(int W, int H) {
            boolean o = Ui.gStyle;
            float top = Ui.statusInset;
            wide = W / Ui.density >= 600 && H / Ui.density >= 480;
            if (wide) {
                bottomY = H - Ui.navInset - Ui.dpf(34);
                colW = Math.min(W * 0.38f, H * (o ? 0.43f : 0.48f));
                // centre the dimmed player block on OLED wide screens, or match left column if player is open
                colX = (W - colW) / 2f;
                float extraH = o ? Ui.dpf(268) : Ui.dpf(250);
                float block = colW + extraH;
                float y0 = Math.max(top + Ui.dpf(36), (H - block) / 2f);
                artSlot.set(colX, y0, colX + colW, y0 + colW);
                if (o) {
                    float devY = artSlot.bottom + Ui.dpf(22);
                    titleY = devY + Ui.dpf(24);
                    seekY = titleY + Ui.dpf(78);
                    timesY = seekY + Ui.dpf(20);
                    ctrlY = timesY + Ui.dpf(56);
                } else {
                    titleY = artSlot.bottom + Ui.dpf(28);
                    seekY = titleY + Ui.dpf(68);
                    timesY = seekY + Ui.dpf(22);
                    ctrlY = timesY + Ui.dpf(64);
                }
                return;
            }
            float side = Ui.dpf(28);
            bottomY = H - Ui.navInset - Ui.dpf(38);
            if (o) {
                float s1 = Ui.dpf(24);
                float size = Math.min(W - s1 * 2, H * (Ui.cover() ? 0.34f : 0.40f));
                artSlot.set((W - size) / 2f, top + Ui.dpf(Ui.cover() ? 28 : 40), (W + size) / 2f, top + Ui.dpf(Ui.cover() ? 28 : 40) + size);
                float devY = artSlot.bottom + Ui.dpf(Ui.cover() ? 20 : 24);
                titleY = devY + Ui.dpf(24);
                seekY = titleY + Ui.dpf(Ui.cover() ? 72 : 80);
                timesY = seekY + Ui.dpf(22);
                ctrlY = Math.max(timesY + Ui.dpf(56), (timesY + bottomY) / 2f - Ui.dpf(24));
            } else {
                float size = Math.min(W - side * 2, H * 0.44f);
                artSlot.set((W - size) / 2f, top + Ui.dpf(46), (W + size) / 2f, top + Ui.dpf(46) + size);
                titleY = artSlot.bottom + Ui.dpf(34);
                seekY = titleY + Ui.dpf(66);
                timesY = seekY + Ui.dpf(22);
                float minCtrl = timesY + Ui.dpf(64);
                ctrlY = Math.max(minCtrl, (timesY + Ui.dpf(16) + bottomY) / 2f - Ui.dpf(6));
            }
        }

        private void exact(View v, int w, int h) {
            v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }

        @Override protected void onMeasure(int ws, int hs) {
            int W = MeasureSpec.getSize(ws), H = MeasureSpec.getSize(hs);
            setMeasuredDimension(W, H);
            compute(W, H);
            boolean o = Ui.gStyle;
            int side = Ui.dp(28);
            int colWi = wide ? (int) colW : W - side * 2;
            exact(clockView, W - Ui.dp(40), Ui.dp(22));
            exact(artView, (int) artSlot.width(), (int) artSlot.height());
            exact(titleView, colWi, Ui.dp(30));
            exact(artistView, colWi, Ui.dp(24));
            exact(bar, colWi, Ui.dp(14));
            exact(timeL, Ui.dp(70), Ui.dp(16));
            exact(timeR, Ui.dp(70), Ui.dp(16));
            int btnS = o ? (wide ? Math.min(Ui.dp(54), Math.max(Ui.dp(40), (int) (colWi * 0.20f))) : Ui.dp(60))
                    : (wide ? Math.min(Ui.dp(64), Math.max(Ui.dp(44), (int) (colWi * 0.22f))) : Ui.dp(72));
            int playS = o ? (wide ? Math.min(Ui.dp(62), Math.max(Ui.dp(46), (int) (colWi * 0.24f))) : Ui.dp(68))
                    : (wide ? Math.min(Ui.dp(74), Math.max(Ui.dp(52), (int) (colWi * 0.26f))) : Ui.dp(84));
            exact(prevBtn, btnS, btnS);
            exact(playBtn, playS, playS);
            exact(nextBtn, btnS, btnS);
            exact(hintView, W - Ui.dp(40), Ui.dp(20));
        }

        private void place(View v, int x, int y) {
            v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
        }

        @Override protected void onLayout(boolean ch, int l, int t, int r, int b) {
            int W = r - l, H = b - t;
            compute(W, H);
            boolean o = Ui.gStyle;
            int side = Ui.dp(28);
            int x = wide ? (int) colX : side;
            int cw = wide ? (int) colW : W - side * 2;
            place(clockView, Ui.dp(20), Math.max(Ui.dp(10), (int) artSlot.top - Ui.dp(28)));
            place(artView, (int) artSlot.left, (int) artSlot.top);
            place(titleView, x, (int) titleY);
            place(artistView, x, (int) titleY + Ui.dp(o ? 32 : 30));
            place(bar, x, (int) seekY - bar.getMeasuredHeight() / 2);
            place(timeL, x, (int) timesY);
            place(timeR, x + cw - timeR.getMeasuredWidth(), (int) timesY);
            int cy = (int) ctrlY;
            int mid = wide ? x + cw / 2 : W / 2;
            int step = o ? Math.min(Ui.dp(86), cw / 4) : Math.min(Ui.dp(108), W / 4);
            place(prevBtn, mid - step - prevBtn.getMeasuredWidth() / 2, cy - prevBtn.getMeasuredHeight() / 2);
            place(playBtn, mid - playBtn.getMeasuredWidth() / 2, cy - playBtn.getMeasuredHeight() / 2);
            place(nextBtn, mid + step - nextBtn.getMeasuredWidth() / 2, cy - nextBtn.getMeasuredHeight() / 2);
            place(hintView, Ui.dp(20), (int) bottomY);
        }
    }

    private static final class ProgressBar extends View {
        float frac;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        ProgressBar(Context c) { super(c); }

        @Override protected void onDraw(Canvas c) {
            float cy = getHeight() / 2f;
            float h = Ui.dpf(3.2f);
            r.set(0, cy - h / 2f, getWidth(), cy + h / 2f);
            p.setColor(0x28FFFFFF);
            c.drawRoundRect(r, h / 2f, h / 2f, p);
            float w = getWidth() * Ui.clamp(frac, 0f, 1f);
            if (w > 0) {
                r.right = w;
                p.setColor(Ui.alpha(Ui.ACCENT, 0.72f));
                c.drawRoundRect(r, h / 2f, h / 2f, p);
            }
        }
    }
}
