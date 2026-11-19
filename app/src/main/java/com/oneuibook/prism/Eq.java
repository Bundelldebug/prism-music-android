package com.oneuibook.prism;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.audiofx.DynamicsProcessing;
import android.view.MotionEvent;
import android.view.View;

/**
 * 10-band graphic equalizer for on-device playback (31 Hz … 16 kHz, ±12 dB), built on DynamicsProcessing so the
 * band count doesn't depend on the phone's own Equalizer (often only 5 bands). Attached to the MediaPlayer's audio
 * session. Spotify plays as DRM-protected web audio inside the WebView, which audio effects can't reach.
 */
final class Eq {
    private Eq() {}

    static final int[] FREQ = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    static final float MAX_DB = 12f;
    static final String PREF_ON = "eq_on", PREF_PRESET = "eq_preset";

    /** Presets (dB per band). Index 0 = flat; the last one ("custom") holds the user's own curve. */
    static final int[] PRESET_NAMES = {R.string.eq_flat, R.string.eq_bass, R.string.eq_treble, R.string.eq_vocal,
            R.string.eq_rock, R.string.eq_pop, R.string.eq_jazz, R.string.eq_classical, R.string.eq_electronic};
    static final float[][] PRESETS = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {6, 5, 4, 2, 0, 0, 0, 0, 0, 0},
            {0, 0, 0, 0, 0, 1, 2, 4, 5, 6},
            {-2, -2, -1, 1, 3, 4, 3, 1, 0, -1},
            {5, 4, 2, 0, -1, -1, 1, 3, 4, 5},
            {-1, 0, 2, 4, 4, 2, 0, -1, -1, -1},
            {3, 2, 1, 2, -1, -1, 0, 1, 2, 3},
            {4, 3, 2, 1, -1, -1, 0, 2, 3, 4},
            {5, 4, 1, 0, -2, 1, 0, 2, 4, 5},
    };

    private static DynamicsProcessing dp;
    private static int session = -1;

    static SharedPreferences prefs(Context c) { return MainActivity.prefs(c); }
    static boolean on(Context c) { return prefs(c).getBoolean(PREF_ON, false); }
    static float gain(Context c, int band) { return prefs(c).getFloat("eq_g" + band, 0f); }

    static void setGain(Context c, int band, float db) {
        prefs(c).edit().putFloat("eq_g" + band, Ui.clamp(db, -MAX_DB, MAX_DB)).putInt(PREF_PRESET, -1).apply();
        apply(c);
    }

    static void setPreset(Context c, int i) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (int b = 0; b < FREQ.length; b++) e.putFloat("eq_g" + b, PRESETS[i][b]);
        e.putInt(PREF_PRESET, i).apply();
        apply(c);
    }

    static void setOn(Context c, boolean v) {
        prefs(c).edit().putBoolean(PREF_ON, v).apply();
        apply(c);
    }

    /** Hooks the equalizer onto a new MediaPlayer session (called for every on-device song). */
    static void attach(Context c, int audioSession) {
        if (audioSession == session && dp != null) { apply(c); return; }
        release();
        session = audioSession;
        apply(c);
    }

    static void release() {
        if (dp != null) { try { dp.release(); } catch (Exception ignored) {} dp = null; }
    }

    /** Builds the effect on first use, then pushes the current curve; disabled = no processing at all. */
    static void apply(Context c) {
        if (session <= 0) return;
        boolean enable = on(c);
        try {
            if (dp == null) {
                if (!enable) return;   // nothing to do until it's switched on
                DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                        true, FREQ.length, false, 0, false, 0, true).build();
                dp = new DynamicsProcessing(0, session, cfg);
                // a gentle limiter keeps boosted bands from clipping
                DynamicsProcessing.Limiter lim = new DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f);
                dp.setLimiterAllChannelsTo(lim);
            }
            for (int b = 0; b < FREQ.length; b++) {
                // pre-EQ bands are defined by their upper edge: halfway (geometrically) to the next centre
                float cutoff = b == FREQ.length - 1 ? 20000f : (float) Math.sqrt(FREQ[b] * (double) FREQ[b + 1]);
                dp.setPreEqBandAllChannelsTo(b, new DynamicsProcessing.EqBand(true, cutoff, enable ? gain(c, b) : 0f));
            }
            dp.setEnabled(enable);
        } catch (Throwable t) {
            // devices without DynamicsProcessing support for this session: play unprocessed
            release();
        }
    }

    static String label(int hz) { return hz >= 1000 ? (hz / 1000) + "k" : String.valueOf(hz); }

    // ================================================================ UI: ten vertical sliders
    static final class Bands extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                knob = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), mid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int drag = -1;
        Runnable changed;

        Bands(Context c) {
            super(c);
            track.setColor(Ui.dark ? 0x33FFFFFF : 0x14000000);
            fill.setColor(Ui.ACCENT);
            knob.setColor(0xFFFFFFFF);
            knob.setShadowLayer(Ui.dpf(3), 0, Ui.dpf(1), 0x40000000);
            setLayerType(LAYER_TYPE_SOFTWARE, null);   // the knob's shadow layer
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Ui.medium);
            mid.setColor(Ui.dark ? 0x26FFFFFF : 0x1A000000);
            mid.setStrokeWidth(Ui.dpf(1));
        }

        @Override protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(MeasureSpec.getSize(ws), Ui.dp(270));
        }

        private float colX(int i) { float w = getWidth() - Ui.dpf(24); return Ui.dpf(12) + w * (i + 0.5f) / FREQ.length; }
        private float top() { return Ui.dpf(30); }
        private float bottom() { return getHeight() - Ui.dpf(34); }
        private float yOf(float db) { return Ui.lerp(bottom(), top(), (db + MAX_DB) / (2 * MAX_DB)); }
        private float dbOf(float y) { return Ui.clamp((bottom() - y) / (bottom() - top()) * 2 * MAX_DB - MAX_DB, -MAX_DB, MAX_DB); }

        @Override protected void onDraw(Canvas c) {
            boolean en = on(getContext());
            fill.setColor(Ui.ACCENT);
            float zero = yOf(0);
            c.drawLine(Ui.dpf(12), zero, getWidth() - Ui.dpf(12), zero, mid);
            for (int i = 0; i < FREQ.length; i++) {
                float x = colX(i), g = gain(getContext(), i), y = yOf(g);
                float tw = Ui.dpf(5);
                r.set(x - tw / 2, top(), x + tw / 2, bottom());
                c.drawRoundRect(r, tw, tw, track);
                fill.setAlpha(en ? 255 : 90);
                r.set(x - tw / 2, Math.min(y, zero), x + tw / 2, Math.max(y, zero));
                c.drawRoundRect(r, tw, tw, fill);
                float kw = Ui.dpf(drag == i ? 30 : 26), kh = Ui.dpf(drag == i ? 20 : 17);
                r.set(x - kw / 2, y - kh / 2, x + kw / 2, y + kh / 2);
                c.drawRoundRect(r, kh / 2, kh / 2, knob);
                text.setTextSize(Ui.sp(11));
                text.setColor(Ui.text2());
                c.drawText(label(FREQ[i]), x, getHeight() - Ui.dpf(12), text);
                text.setColor(drag == i ? Ui.ACCENT : Ui.text3());
                c.drawText((g > 0 ? "+" : "") + Math.round(g), x, Ui.dpf(16), text);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    float best = Float.MAX_VALUE;
                    for (int i = 0; i < FREQ.length; i++) { float d = Math.abs(e.getX() - colX(i)); if (d < best) { best = d; drag = i; } }
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                // fall through
                case MotionEvent.ACTION_MOVE:
                    if (drag >= 0) {
                        // whole-dB steps
                        setGain(getContext(), drag, Math.round(dbOf(e.getY())));
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    drag = -1;
                    invalidate();
                    if (changed != null) changed.run();
                    return true;
            }
            return true;
        }
    }
}
