package com.oneuibook.prism;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.DisplayCutout;
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
 * Full-screen AOD mode.
 * Freezes the expanded media player's artwork backdrop in place (dimmed gently instead of flat pure black),
 * mirrors the expanded player controls 1:1 with punch-hole safe clock placement, subtly drifts the entire
 * UI cluster to prevent burn-in, and exits immediately on a single power-button press (SCREEN_OFF),
 * double-tap, swipe-up, or Back.
 */
final class AodOverlay extends FrameLayout {
    private final MainActivity main;
    private final Cluster cluster;
    private final TextView clockView, titleView, artistView, timeL, timeR, hintView;
    private final Img.ArtView artView;
    private final Icons.IconView prevBtn, playBtn, nextBtn;
    private final ProgressBar bar;

    private boolean shown;
    private float dimP;
    private ValueAnimator dimAnim;
    private int shiftStep;
    private long lastTap;
    private float downX, downY;

    // Fallback frozen backdrop when AOD is launched while NowPlaying is closed
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix bgM = new Matrix();
    private Bitmap frozenArt;
    private int[] frozenPal = Img.palette(null);

    // 16-point smooth oval orbit inside ±6dp: each step moves only ~1.8–2.4dp over 1.8s (imperceptible)
    private static final float[] SHIFT_DX = {
            0f, 2.0f, 3.8f, 5.2f, 6.0f, 5.5f, 4.0f, 2.0f,
            0f, -2.0f, -4.0f, -5.5f, -6.0f, -5.2f, -3.8f, -2.0f
    };
    private static final float[] SHIFT_DY = {
            -6.0f, -5.4f, -4.0f, -2.0f, 0f, 2.2f, 4.2f, 5.6f,
            6.0f, 5.4f, 4.0f, 2.0f, 0f, -2.2f, -4.2f, -5.6f
    };

    private final BroadcastReceiver screenOffRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                closeImmediate();
            }
        }
    };
    private boolean screenOffRxRegistered;

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

        clockView = Ui.text(m, "12:00", 14.5f, Ui.semibold, 0xB8FFFFFF);
        clockView.setLetterSpacing(0.02f);
        clockView.setGravity(Gravity.CENTER);
        cluster.addView(clockView);

        artView = new Img.ArtView(m, 30);
        cluster.addView(artView);

        titleView = Ui.text(m, "", 22f, Ui.bold, 0xD9FFFFFF);
        enableMarquee(titleView);
        cluster.addView(titleView);

        artistView = Ui.text(m, "", 16.5f, Ui.regular, 0x8CFFFFFF);
        enableMarquee(artistView);
        cluster.addView(artistView);

        bar = new ProgressBar(m);
        cluster.addView(bar);

        timeL = Ui.text(m, "0:00", 12f, Ui.medium, 0x78FFFFFF);
        timeR = Ui.text(m, "-0:00", 12f, Ui.medium, 0x78FFFFFF);
        timeR.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        cluster.addView(timeL);
        cluster.addView(timeR);

        prevBtn = aodBtn(m, Icons.PREV, 26, v -> Player.prev());
        playBtn = aodBtn(m, Icons.PAUSE, 32, v -> Player.toggle());
        nextBtn = aodBtn(m, Icons.NEXT, 26, v -> Player.next());
        cluster.addView(prevBtn);
        cluster.addView(playBtn);
        cluster.addView(nextBtn);

        hintView = Ui.text(m, m.getString(R.string.aod_hint), 11.5f, Ui.regular, 0x66FFFFFF);
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
        Icons.IconView v = new Icons.IconView(m, ic, sizeDp, 0xCCFFFFFF);
        v.setBackground(Ui.round(0x18FFFFFF, 100));
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
        registerScreenOff();
        if (main.np != null) main.np.setAodFreeze(true, 0f);
        applyWindowMode(true);
        hintView.animate().cancel();
        hintView.setAlpha(0.75f);
        animateDim(1f, 520, null);
        removeCallbacks(tick);
        removeCallbacks(shift);
        post(tick);
        postDelayed(shift, 24000);
        hintView.animate().alpha(0.28f).setStartDelay(3200).setDuration(1000).start();
    }

    void close() {
        if (!shown) return;
        shown = false;
        removeCallbacks(tick);
        removeCallbacks(shift);
        cluster.animate().cancel();
        Player.listeners.remove(playerL);
        Player.trackListeners.remove(playerL);
        unregisterScreenOff();
        applyWindowMode(false);
        animateDim(0f, 380, () -> {
            if (!shown) {
                if (main.np != null) main.np.setAodFreeze(false, 0f);
                setVisibility(GONE);
            }
        });
    }

    /** Instant dismissal when the user presses the power button (SCREEN_OFF / onPause). */
    void closeImmediate() {
        if (!shown && getVisibility() != VISIBLE) return;
        shown = false;
        if (dimAnim != null) dimAnim.cancel();
        removeCallbacks(tick);
        removeCallbacks(shift);
        cluster.animate().cancel();
        Player.listeners.remove(playerL);
        Player.trackListeners.remove(playerL);
        unregisterScreenOff();
        applyWindowMode(false);
        dimP = 0f;
        if (main.np != null) main.np.setAodFreeze(false, 0f);
        setVisibility(GONE);
    }

    private void registerScreenOff() {
        if (screenOffRxRegistered) return;
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            if (Build.VERSION.SDK_INT >= 33) getContext().registerReceiver(screenOffRx, f, Context.RECEIVER_EXPORTED);
            else getContext().registerReceiver(screenOffRx, f);
            screenOffRxRegistered = true;
        } catch (Throwable ignored) {}
    }

    private void unregisterScreenOff() {
        if (!screenOffRxRegistered) return;
        try { getContext().unregisterReceiver(screenOffRx); } catch (Throwable ignored) {}
        screenOffRxRegistered = false;
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

    /** Smoothly dims live player controls out and settles the dimmed AOD controls over the frozen player backdrop. */
    private void applyDimVisuals() {
        float k = Ui.clamp(dimP, 0f, 1f);
        if (main.np != null) main.np.setAodFreeze(shown || k > 0.01f, k);
        cluster.setAlpha(Ui.clamp(k * 1.45f, 0f, 1f));
        float artA = Ui.lerp(0.95f, 0.72f, k);
        float artS = Ui.lerp(1f, 0.975f, k);
        artView.setAlpha(artA);
        artView.setScaleX(artS);
        artView.setScaleY(artS);
        float textA = Ui.lerp(1f, 0.85f, k);
        titleView.setAlpha(textA);
        artistView.setAlpha(textA);
        bar.setAlpha(Ui.lerp(0.95f, 0.80f, k));
        timeL.setAlpha(Ui.lerp(0.9f, 0.74f, k));
        timeR.setAlpha(Ui.lerp(0.9f, 0.74f, k));
        float ctrlA = Ui.lerp(0.95f, 0.76f, k);
        prevBtn.setAlpha(ctrlA);
        playBtn.setAlpha(ctrlA);
        nextBtn.setAlpha(ctrlA);
        clockView.setAlpha(Ui.clamp((k - 0.30f) / 0.70f, 0f, 1f) * 0.82f);
    }

    @Override protected void onDraw(Canvas c) {
        float k = Ui.clamp(dimP, 0f, 1f);
        if (k <= 0.001f) return;
        boolean playerOpenBehind = main.np != null && main.np.getVisibility() == VISIBLE && main.np.openP > 0.8f;
        if (playerOpenBehind) {
            // Keep the frozen player artwork backdrop visible underneath, gently dimmed (not flat pure black)
            int scrimA = Math.round(145 * k); // ~57% dim scrim
            c.drawColor(scrimA << 24);
        } else {
            // Launched while NowPlaying is closed: draw a frozen blurred artwork/palette backdrop + gentle dim
            int w = getWidth(), h = getHeight();
            int sc = c.saveLayerAlpha(0, 0, w, h, Math.round(255 * k));
            if (frozenArt != null) {
                float scale = Math.max(w / (float) frozenArt.getWidth(), h / (float) frozenArt.getHeight()) * 1.25f;
                bgM.reset();
                bgM.postTranslate(-frozenArt.getWidth() / 2f, -frozenArt.getHeight() / 2f);
                bgM.postScale(scale, scale);
                bgM.postTranslate(w / 2f, h / 2f);
                c.drawColor(frozenPal[0]);
                c.drawBitmap(frozenArt, bgM, bgPaint);
            } else {
                bgPaint.setShader(new LinearGradient(0, 0, w, h, frozenPal[0], frozenPal[3], Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, bgPaint);
                bgPaint.setShader(null);
            }
            c.drawColor(0x96000000);
            c.restoreToCount(sc);
        }
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
        for (Icons.IconView v : new Icons.IconView[]{prevBtn, nextBtn}) v.setBackground(o ? Ui.round(0x18FFFFFF, 100) : null);
        playBtn.setBackground(o ? Ui.round(0x28FFFFFF, 100) : null);
        cluster.requestLayout();
    }

    private void applyWindowMode(boolean enter) {
        Window w = main.getWindow();
        if (w == null) return;
        if (enter) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = w.getAttributes();
        boolean dim = Settings.pref(main, "aod_dim", true);
        lp.screenBrightness = (enter && dim) ? 0.14f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
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

    private int safeTopInset() {
        int top = Ui.statusInset;
        try {
            WindowInsets wi = getRootWindowInsets();
            if (wi != null) {
                DisplayCutout dc = wi.getDisplayCutout();
                if (dc != null) top = Math.max(top, dc.getSafeInsetTop());
            }
        } catch (Throwable ignored) {}
        return Math.max(top, Ui.dp(28));
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
            frozenArt = null;
            frozenPal = Img.palette(null);
        } else {
            artView.setVisibility(VISIBLE);
            bar.setVisibility(VISIBLE);
            String u = t.art != null ? t.art : t.artSmall;
            artView.setUrl(u);
            Img.load(u, b -> {
                if (b != null) {
                    Bitmap tiny = Bitmap.createScaledBitmap(b, 24, 24, true);
                    frozenArt = Bitmap.createScaledBitmap(tiny, 128, 128, true);
                    frozenPal = Img.palette(b);
                    invalidate();
                }
            });
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
                        hintView.animate().alpha(0.28f).setStartDelay(2000).setDuration(800).start();
                    }
                }
                return true;
        }
        return true;
    }

    /**
     * Exact geometric mirror of {@link NowPlaying}'s expanded player layout with punch-hole-safe clock clearance.
     */
    private final class Cluster extends FrameLayout {
        private final RectF artSlot = new RectF();
        private float clockY, titleY, seekY, timesY, ctrlY, bottomY, colX, colW;
        private boolean wide;

        Cluster(Context c) {
            super(c);
            setClipChildren(false);
        }

        private void compute(int W, int H) {
            boolean o = Ui.gStyle;
            float safeTop = safeTopInset();
            clockY = safeTop + Ui.dpf(10); // Always strictly below punch-hole / DisplayCutout!
            float minArtTop = clockY + Ui.dpf(32);
            wide = W / Ui.density >= 600 && H / Ui.density >= 480;
            if (wide) {
                bottomY = H - Ui.navInset - Ui.dpf(26);
                colW = Math.min(W * 0.44f, H * (o ? 0.48f : 0.52f));
                colX = (W - colW) / 2f;
                float extraH = o ? Ui.dpf(248) : Ui.dpf(236);
                float block = colW + extraH;
                float y0 = Math.max(minArtTop, (H - block) / 2f);
                artSlot.set(colX, y0, colX + colW, y0 + colW);
                if (o) {
                    float devY = artSlot.bottom + Ui.dpf(20);
                    titleY = devY + Ui.dpf(22);
                    seekY = titleY + Ui.dpf(74);
                    timesY = seekY + Ui.dpf(20);
                    ctrlY = timesY + Ui.dpf(54);
                } else {
                    titleY = artSlot.bottom + Ui.dpf(34);
                    seekY = titleY + Ui.dpf(64);
                    timesY = seekY + Ui.dpf(22);
                    ctrlY = timesY + Ui.dpf(58);
                }
                return;
            }
            float side = Ui.dpf(28);
            bottomY = H - Ui.navInset - Ui.dpf(Ui.cover() ? 30 : 38);
            if (o) {
                float s1 = Ui.dpf(24);
                float size = Math.min(W - s1 * 2, H * (Ui.cover() ? 0.36f : 0.40f));
                float artTop = Math.max(minArtTop, safeTop + Ui.dpf(Ui.cover() ? 40 : 52));
                artSlot.set((W - size) / 2f, artTop, (W + size) / 2f, artTop + size);
                float devY = artSlot.bottom + Ui.dpf(Ui.cover() ? 18 : 24);
                titleY = devY + Ui.dpf(24);
                seekY = titleY + Ui.dpf(Ui.cover() ? 66 : 78);
                timesY = seekY + Ui.dpf(22);
                ctrlY = Math.max(timesY + Ui.dpf(Ui.cover() ? 50 : 56), (timesY + bottomY) / 2f - Ui.dpf(24));
            } else {
                float size = Math.min(W - side * 2, H * (Ui.cover() ? 0.38f : 0.42f));
                float artTop = Math.max(minArtTop, safeTop + Ui.dpf(Ui.cover() ? 40 : 52));
                artSlot.set((W - size) / 2f, artTop, (W + size) / 2f, artTop + size);
                titleY = artSlot.bottom + Ui.dpf(Ui.cover() ? 30 : 36);
                seekY = titleY + Ui.dpf(Ui.cover() ? 58 : 64);
                timesY = seekY + Ui.dpf(20);
                float minCtrl = timesY + Ui.dpf(Ui.cover() ? 50 : 60);
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
            place(clockView, Ui.dp(20), (int) clockY);
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
            p.setColor(0x33FFFFFF);
            c.drawRoundRect(r, h / 2f, h / 2f, p);
            float w = getWidth() * Ui.clamp(frac, 0f, 1f);
            if (w > 0) {
                r.right = w;
                p.setColor(Ui.alpha(Ui.ACCENT, 0.78f));
                c.drawRoundRect(r, h / 2f, h / 2f, p);
            }
        }
    }
}
