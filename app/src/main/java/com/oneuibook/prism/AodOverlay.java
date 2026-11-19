package com.oneuibook.prism;

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
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Built-in full-screen OLED Always-On Display (AOD) mode.
 * Pure black background, dim brightness option, immersive system bars, periodic burn-in pixel shift,
 * live clock/date, rounded artwork, centred marquee title/artist, subtle accent progress bar,
 * and minimal playback controls. Exits on double-tap, swipe-up, or Back.
 */
final class AodOverlay extends FrameLayout {
    private final MainActivity main;
    private final LinearLayout box;
    private final TextView clockView, dateView, titleView, artistView, timeL, timeR, hintView;
    private final Img.ArtView artView;
    private final Icons.IconView prevBtn, playBtn, nextBtn;
    private final ProgressBar bar;

    private boolean shown;
    private int shiftStep;
    private long lastTap;
    private float downX, downY;

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
            // gentle burn-in pixel shift within ±12dp every 45 seconds
            float[] dx = {0, 7, -8, 10, -6, 4, -10, 8};
            float[] dy = {0, -9, 6, 8, -10, 5, -4, 9};
            shiftStep = (shiftStep + 1) % dx.length;
            box.animate().translationX(Ui.dpf(dx[shiftStep])).translationY(Ui.dpf(dy[shiftStep]))
                    .setDuration(900).setInterpolator(Ui.ONEUI_SINE_80).start();
            postDelayed(this, 45000);
        }
    };

    private final Runnable playerL = this::syncTrackAndState;

    AodOverlay(MainActivity m) {
        super(m);
        main = m;
        setBackgroundColor(0xFF000000);
        setClickable(true);
        setVisibility(GONE);
        setAlpha(0f);

        box = new LinearLayout(m);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int padH = Ui.dp(32);
        box.setPadding(padH, Ui.dp(24), padH, Ui.dp(24));

        clockView = Ui.text(m, "12:00", 64, Ui.medium, 0xEBFFFFFF);
        clockView.setLetterSpacing(-0.03f);
        clockView.setGravity(Gravity.CENTER);
        box.addView(clockView, wrapCenter(0));

        dateView = Ui.text(m, "", 14.5f, Ui.regular, 0x94FFFFFF);
        dateView.setGravity(Gravity.CENTER);
        box.addView(dateView, wrapCenter(4));

        int artSize = Math.min(Ui.dp(172), (int) (Math.min(Ui.screenW, Ui.screenH) * 0.42f));
        artView = new Img.ArtView(m, 24);
        artView.setAlpha(0.88f);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(artSize, artSize);
        alp.topMargin = Ui.dp(Ui.cover() ? 18 : 34);
        box.addView(artView, alp);

        titleView = Ui.text(m, "", 20, Ui.semibold, 0xF0FFFFFF);
        titleView.setGravity(Gravity.CENTER);
        enableMarquee(titleView);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(Math.min(Ui.dp(340), Ui.screenW - padH * 2), ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(Ui.cover() ? 16 : 26);
        box.addView(titleView, tlp);

        artistView = Ui.text(m, "", 14.5f, Ui.regular, 0x99FFFFFF);
        artistView.setGravity(Gravity.CENTER);
        enableMarquee(artistView);
        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(Math.min(Ui.dp(320), Ui.screenW - padH * 2), ViewGroup.LayoutParams.WRAP_CONTENT);
        arlp.topMargin = Ui.dp(6);
        box.addView(artistView, arlp);

        bar = new ProgressBar(m);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Math.min(Ui.dp(280), Ui.screenW - padH * 2), Ui.dp(10));
        blp.topMargin = Ui.dp(Ui.cover() ? 14 : 22);
        box.addView(bar, blp);

        LinearLayout times = new LinearLayout(m);
        times.setOrientation(LinearLayout.HORIZONTAL);
        timeL = Ui.text(m, "0:00", 11.5f, Ui.regular, 0x73FFFFFF);
        timeR = Ui.text(m, "0:00", 11.5f, Ui.regular, 0x73FFFFFF);
        timeR.setGravity(Gravity.END);
        times.addView(timeL, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(timeR, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams tmlp = new LinearLayout.LayoutParams(Math.min(Ui.dp(280), Ui.screenW - padH * 2), ViewGroup.LayoutParams.WRAP_CONTENT);
        tmlp.topMargin = Ui.dp(4);
        box.addView(times, tmlp);

        // minimal OLED playback controls
        LinearLayout ctrls = new LinearLayout(m);
        ctrls.setOrientation(LinearLayout.HORIZONTAL);
        ctrls.setGravity(Gravity.CENTER);
        prevBtn = aodBtn(m, Icons.PREV, 24, v -> Player.prev());
        playBtn = aodBtn(m, Icons.PAUSE, 28, v -> Player.toggle());
        nextBtn = aodBtn(m, Icons.NEXT, 24, v -> Player.next());
        ctrls.addView(prevBtn, btnLp(0));
        ctrls.addView(playBtn, btnLp(20));
        ctrls.addView(nextBtn, btnLp(20));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(Ui.cover() ? 14 : 24);
        box.addView(ctrls, clp);

        LayoutParams flp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        addView(box, flp);

        hintView = Ui.text(m, m.getString(R.string.aod_hint), 12, Ui.regular, 0x66FFFFFF);
        hintView.setGravity(Gravity.CENTER);
        LayoutParams hlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = Ui.dp(34);
        addView(hintView, hlp);
    }

    private static void enableMarquee(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        t.setMarqueeRepeatLimit(-1);
        t.setHorizontalFadingEdgeEnabled(true);
        t.setFadingEdgeLength(Ui.dp(18));
        t.setSelected(true);
    }

    private static LinearLayout.LayoutParams wrapCenter(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (topDp > 0) lp.topMargin = Ui.dp(topDp);
        return lp;
    }

    private static LinearLayout.LayoutParams btnLp(int leftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(52), Ui.dp(52));
        if (leftDp > 0) lp.leftMargin = Ui.dp(leftDp);
        return lp;
    }

    private Icons.IconView aodBtn(MainActivity m, int ic, float sizeDp, OnClickListener click) {
        Icons.IconView v = new Icons.IconView(m, ic, sizeDp, 0xCCFFFFFF);
        v.setBackground(Ui.round(0x14FFFFFF, 100));
        v.setOnClickListener(click);
        Ui.press(v, 0.90f);
        return v;
    }

    boolean isOpen() { return shown; }

    void open() {
        if (shown) return;
        shown = true;
        setVisibility(VISIBLE);
        bringToFront();
        box.setTranslationX(0f);
        box.setTranslationY(0f);
        hintView.setAlpha(1f);
        syncClock();
        syncTrackAndState();
        Player.listeners.add(playerL);
        Player.trackListeners.add(playerL);
        applyWindowMode(true);
        animate().alpha(1f).setDuration(320).setInterpolator(Ui.ONEUI_SINE_80).withEndAction(null).start();
        removeCallbacks(tick);
        removeCallbacks(shift);
        post(tick);
        postDelayed(shift, 45000);
        hintView.animate().alpha(0.35f).setStartDelay(3500).setDuration(900).start();
    }

    void close() {
        if (!shown) return;
        shown = false;
        removeCallbacks(tick);
        removeCallbacks(shift);
        Player.listeners.remove(playerL);
        Player.trackListeners.remove(playerL);
        applyWindowMode(false);
        animate().alpha(0f).setDuration(240).setInterpolator(Ui.ONEUI_SINE_80)
                .withEndAction(() -> setVisibility(GONE)).start();
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
        clockView.setText(new SimpleDateFormat("HH:mm", loc).format(now));
        String pat = Locale.KOREAN.getLanguage().equals(loc.getLanguage()) ? "M월 d일 EEEE"
                : Locale.JAPANESE.getLanguage().equals(loc.getLanguage()) ? "M月d日 (E)" : "EEE, MMM d";
        dateView.setText(new SimpleDateFormat(pat, loc).format(now));
    }

    private void syncTrackAndState() {
        Player.Track t = Player.track;
        if (t == null) {
            artView.setVisibility(GONE);
            titleView.setText(main.getString(R.string.nothing_playing));
            artistView.setText("");
            bar.setVisibility(GONE);
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
                        hintView.setAlpha(0.85f);
                        hintView.animate().alpha(0.35f).setStartDelay(2000).setDuration(800).start();
                    }
                }
                return true;
        }
        return true;
    }

    private static final class ProgressBar extends View {
        float frac;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        ProgressBar(android.content.Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            float cy = getHeight() / 2f, h = Ui.dpf(3f);
            r.set(0, cy - h / 2f, getWidth(), cy + h / 2f);
            p.setColor(0x2EFFFFFF);
            c.drawRoundRect(r, h / 2f, h / 2f, p);
            if (frac > 0.001f) {
                r.right = Math.max(h, getWidth() * frac);
                p.setColor(Ui.alpha(Ui.ACCENT, 0.85f));
                c.drawRoundRect(r, h / 2f, h / 2f, p);
            }
        }
    }
}
