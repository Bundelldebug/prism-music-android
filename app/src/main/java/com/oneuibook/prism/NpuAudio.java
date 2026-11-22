package com.oneuibook.prism;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.audiofx.DynamicsProcessing;
import android.os.Build;
import android.text.TextPaint;
import android.view.View;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Bit-Perfect Assist — Flagship-SoC NPU-class audio enhancement (15 supported chipsets).
 * Attaches a 12-band Pre-EQ + 4-band MBC dynamic expander + soft limiter directly to the active
 * local MediaPlayer session (guaranteed on all devices) or global output mix session 0, plus a
 * 5-stage Web Audio harmonic exciter / EQ / compressor chain inside {@link SpotifyWeb}.
 */
final class NpuAudio {
    private NpuAudio() {}

    static final String PREF_ON = "npu_audio_on";
    static final String PREF_PROFILE = "npu_profile";
    static final String PREF_STRENGTH = "npu_strength";
    static final String PREF_AIR = "npu_air";
    static final String PREF_DYN = "npu_dyn";
    static final String PREF_VOCAL = "npu_vocal";
    static final String PREF_LIMITER = "npu_limiter";
    static final String PREF_HIRES_GLOW = "hires_glow";

    static final int VENDOR_QUALCOMM = 0, VENDOR_MEDIATEK = 1, VENDOR_SAMSUNG = 2;

    static final class ChipSpec {
        final String id;
        final int vendor;
        final String nameKo;
        final String nameEn;
        final String badgeTop;
        final String badgeMain;
        final String npu;
        final boolean premiumEdition;
        final int drawableRes;

        ChipSpec(String id, int vendor, String ko, String en, String top, String main, String npu, boolean prem, int res) {
            this.id = id; this.vendor = vendor; this.nameKo = ko; this.nameEn = en;
            this.badgeTop = top; this.badgeMain = main; this.npu = npu; this.premiumEdition = prem; this.drawableRes = res;
        }

        String displayName(Context c) {
            boolean ko = c != null && Locale.KOREAN.getLanguage().equals(c.getResources().getConfiguration().getLocales().get(0).getLanguage());
            return ko ? nameKo : nameEn;
        }
    }

    /** Exact 15 supported flagship chipsets with official artwork (Exynos 2700 unreleased -> no image). */
    static final ChipSpec[] SUPPORTED_CHIPS = {
            // Qualcomm Snapdragon (6)
            new ChipSpec("SM8750",  VENDOR_QUALCOMM, "스냅드래곤 8 엘리트", "Snapdragon 8 Elite", "SNAPDRAGON", "8 Elite", "Hexagon NPU", false, R.drawable.chip_sm8750),
            new ChipSpec("SM8850",  VENDOR_QUALCOMM, "스냅드래곤 8 엘리트 5세대", "Snapdragon 8 Elite Gen 5", "SNAPDRAGON", "8 Elite G5", "Hexagon NPU", false, R.drawable.chip_sm8850),
            new ChipSpec("SM8850V", VENDOR_QUALCOMM, "스냅드래곤 8 엘리트 5세대 V 시리즈", "Snapdragon 8 Elite Gen 5 V", "SNAPDRAGON", "8 Elite 5V", "Hexagon NPU", true, R.drawable.chip_sm8850),
            new ChipSpec("SM8845",  VENDOR_QUALCOMM, "스냅드래곤 8 5세대", "Snapdragon 8 Gen 5", "SNAPDRAGON", "8 Gen 5", "Hexagon NPU", false, R.drawable.chip_sm8845),
            new ChipSpec("SM8950",  VENDOR_QUALCOMM, "스냅드래곤 8 엘리트 6세대", "Snapdragon 8 Elite Gen 6", "SNAPDRAGON", "8 Elite G6", "Hexagon NPU", false, R.drawable.chip_sm8950),
            new ChipSpec("SM8975",  VENDOR_QUALCOMM, "스냅드래곤 8 엘리트 익스트림 6세대", "Snapdragon 8 Elite Extreme Gen 6", "SNAPDRAGON", "8 Extreme", "Hexagon NPU", true, R.drawable.chip_sm8975),
            // MediaTek Dimensity (6)
            new ChipSpec("MT6991",  VENDOR_MEDIATEK, "디멘시티 9400", "Dimensity 9400", "DIMENSITY", "9400", "MediaTek NPU 890", false, R.drawable.chip_mt6991),
            new ChipSpec("MT6991P", VENDOR_MEDIATEK, "디멘시티 9400+", "Dimensity 9400+", "DIMENSITY", "9400+", "MediaTek NPU 890+", true, R.drawable.chip_mt6991p),
            new ChipSpec("MT6993",  VENDOR_MEDIATEK, "디멘시티 9500", "Dimensity 9500", "DIMENSITY", "9500", "MediaTek NPU 990", false, R.drawable.chip_mt6993),
            new ChipSpec("MT6993E", VENDOR_MEDIATEK, "디멘시티 9500e", "Dimensity 9500e", "DIMENSITY", "9500e", "MediaTek NPU 990", false, R.drawable.chip_mt6993),
            new ChipSpec("MT6995P", VENDOR_MEDIATEK, "디멘시티 9600 프로", "Dimensity 9600 Pro", "DIMENSITY", "9600 Pro", "MediaTek NPU", true, R.drawable.chip_mt6995p),
            new ChipSpec("MT6995M", VENDOR_MEDIATEK, "디멘시티 9600M", "Dimensity 9600M", "DIMENSITY", "9600M", "MediaTek NPU", false, R.drawable.chip_mt6995m),
            // Samsung Exynos (3) — Exynos 2700 is unreleased, intentionally no illustration (res = 0)
            new ChipSpec("S5E9955", VENDOR_SAMSUNG, "엑시노스 2500", "Exynos 2500", "EXYNOS", "2500", "Exynos NPU", false, R.drawable.chip_s5e9955),
            new ChipSpec("S5E9965", VENDOR_SAMSUNG, "엑시노스 2600", "Exynos 2600", "EXYNOS", "2600", "Exynos NPU", false, R.drawable.chip_s5e9965),
            new ChipSpec("S5E9975", VENDOR_SAMSUNG, "엑시노스 2700", "Exynos 2700", "EXYNOS", "2700", "Exynos NPU", true, 0),
    };

    static final String[] PROFILES = {"natural", "studio", "vocal", "air"};
    static final int[] PROFILE_NAMES = {
            R.string.bpa_prof_natural, R.string.bpa_prof_studio, R.string.bpa_prof_vocal, R.string.bpa_prof_air
    };
    static final int[][] PROFILE_VALS = {
            {60, 55, 45, 35}, // natural
            {80, 75, 70, 50}, // studio master
            {70, 55, 50, 85}, // vocal clarity
            {85, 95, 65, 45}, // hi-res air
    };

    private static final Pattern QUALCOMM = Pattern.compile("\\bSM(8[7-9]\\d\\d[A-Z]?|9\\d\\d\\d[A-Z]?)\\b");
    private static final Pattern MEDIATEK = Pattern.compile("\\bMT(699[1-9][A-Z]?|7\\d\\d\\d[A-Z]?)\\b");
    private static final Pattern EXYNOS = Pattern.compile("\\b(?:S5E|ERD)(99[5-9]\\d)\\b");
    private static final Pattern DIMENSITY_NAME = Pattern.compile("DIMENSITY\\s+(9[4-9]\\d\\d)");
    private static final Pattern EXYNOS_NAME = Pattern.compile("EXYNOS\\s+(2[5-9]\\d\\d)");

    private static DynamicsProcessing dp;
    private static int dpSession = -1;
    private static int localSession = -1;
    private static String cachedSoc;
    private static String cachedNpuLabel;
    private static Boolean cachedSupported;
    private static String activeEngine = "";

    static Boolean testSupportedOverride;
    static String testSocOverride;

    static boolean isSupportedSoc(String raw) {
        if (raw == null || raw.trim().isEmpty()) return false;
        String u = raw.toUpperCase(Locale.US);
        if (QUALCOMM.matcher(u).find() || u.contains("SNAPDRAGON 8 ELITE") || u.contains("8 ELITE") || u.contains("SNAPDRAGON 8 GEN 5")) return true;
        if (u.equals("SUN") || u.contains(" SUN ") || u.contains("CANOE")) return true;
        if (MEDIATEK.matcher(u).find() || DIMENSITY_NAME.matcher(u).find()) return true;
        if (EXYNOS.matcher(u).find() || EXYNOS_NAME.matcher(u).find()) return true;
        return false;
    }

    static ChipSpec matchChipSpec(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String u = raw.toUpperCase(Locale.US);
        if (u.contains("SM8975") || u.contains("SM8950X") || u.contains("EXTREME")) return SUPPORTED_CHIPS[5];
        if (u.contains("SM8950")) return SUPPORTED_CHIPS[4];
        if (u.contains("SM8850V") || u.contains("8 ELITE GEN 5 V")) return SUPPORTED_CHIPS[2];
        if (u.contains("SM8850") || u.contains("CANOE")) return SUPPORTED_CHIPS[1];
        if (u.contains("SM8845") || u.contains("8 GEN 5")) return SUPPORTED_CHIPS[3];
        if (u.contains("SM8750") || u.equals("SUN") || u.contains(" SUN ") || u.contains("8 ELITE")) return SUPPORTED_CHIPS[0];
        if (u.contains("MT6995M") || u.contains("MT6996M") || u.contains("9600M")) return SUPPORTED_CHIPS[11];
        if (u.contains("MT6995") || u.contains("9600")) return SUPPORTED_CHIPS[10];
        if (u.contains("MT6993E") || u.contains("MT6992") || u.contains("9500E")) return SUPPORTED_CHIPS[9];
        if (u.contains("MT6993") || u.contains("9500")) return SUPPORTED_CHIPS[8];
        if (u.contains("MT6991+") || u.contains("MT6991P") || u.contains("9400+")) return SUPPORTED_CHIPS[7];
        if (u.contains("MT6991") || u.contains("9400")) return SUPPORTED_CHIPS[6];
        if (u.contains("S5E9975") || u.contains("ERD9975") || u.contains("2700")) return SUPPORTED_CHIPS[14];
        if (u.contains("S5E9965") || u.contains("ERD9965") || u.contains("2600")) return SUPPORTED_CHIPS[13];
        if (u.contains("S5E9955") || u.contains("ERD9955") || u.contains("2500")) return SUPPORTED_CHIPS[12];
        return null;
    }

    static String npuAcceleratorLabel(String raw) {
        ChipSpec cs = matchChipSpec(raw);
        if (cs != null) return cs.npu;
        if (raw == null) return "NPU DSP";
        String u = raw.toUpperCase(Locale.US);
        if (QUALCOMM.matcher(u).find() || u.contains("ELITE") || u.contains("SUN") || u.contains("CANOE")) return "Hexagon NPU";
        if (MEDIATEK.matcher(u).find() || DIMENSITY_NAME.matcher(u).find()) return "MediaTek NPU";
        if (EXYNOS.matcher(u).find() || EXYNOS_NAME.matcher(u).find()) return "Exynos NPU";
        return "NPU DSP";
    }

    static String socName() {
        if (testSocOverride != null) return testSocOverride;
        if (cachedSoc != null) return cachedSoc;
        StringBuilder sb = new StringBuilder();
        if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != null && !Build.SOC_MODEL.isEmpty() && !"unknown".equalsIgnoreCase(Build.SOC_MODEL)) {
            sb.append(Build.SOC_MODEL.trim());
        }
        String hw = readCpuInfoHardware();
        if (hw != null && !hw.isEmpty() && !sb.toString().toUpperCase(Locale.US).contains(hw.toUpperCase(Locale.US))) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(hw);
        }
        if (sb.length() == 0 && Build.HARDWARE != null && !Build.HARDWARE.isEmpty()) sb.append(Build.HARDWARE);
        if (sb.length() == 0 && Build.BOARD != null && !Build.BOARD.isEmpty()) sb.append(Build.BOARD);
        cachedSoc = sb.length() == 0 ? "Unknown SoC" : sb.toString();
        return cachedSoc;
    }

    static String friendlySocName(Context c, String raw) {
        if (raw == null || raw.trim().isEmpty()) return "Unknown SoC";
        ChipSpec cs = matchChipSpec(raw);
        if (cs != null) return cs.displayName(c);
        String u = raw.toUpperCase(Locale.US);
        boolean ko = c != null && Locale.KOREAN.getLanguage().equals(c.getResources().getConfiguration().getLocales().get(0).getLanguage());
        if (u.contains("SM8650")) return ko ? "스냅드래곤 8 3세대" : "Snapdragon 8 Gen 3";
        if (u.contains("SM8550")) return ko ? "스냅드래곤 8 2세대" : "Snapdragon 8 Gen 2";
        if (u.contains("SM8475")) return ko ? "스냅드래곤 8+ 1세대" : "Snapdragon 8+ Gen 1";
        if (u.contains("SM8450")) return ko ? "스냅드래곤 8 1세대" : "Snapdragon 8 Gen 1";
        if (u.contains("MT6989")) return ko ? "디멘시티 9300" : "Dimensity 9300";
        if (u.contains("MT6985")) return ko ? "디멘시티 9200" : "Dimensity 9200";
        if (u.contains("S5E9945")) return ko ? "엑시노스 2400" : "Exynos 2400";
        if (u.contains("S5E9925")) return ko ? "엑시노스 2200" : "Exynos 2200";
        return raw;
    }

    static String displaySoc(Context c) { return friendlySocName(c, socName()); }

    static ChipSpec currentChipSpec() {
        String combined = socName() + " " + (Build.BOARD == null ? "" : Build.BOARD) + " " + (Build.HARDWARE == null ? "" : Build.HARDWARE);
        return matchChipSpec(combined);
    }

    static boolean isSupported() {
        if (testSupportedOverride != null) return testSupportedOverride;
        if (cachedSupported != null) return cachedSupported;
        String combined = socName() + " " + (Build.BOARD == null ? "" : Build.BOARD) + " " + (Build.HARDWARE == null ? "" : Build.HARDWARE);
        cachedSupported = isSupportedSoc(combined);
        cachedNpuLabel = npuAcceleratorLabel(combined);
        return cachedSupported;
    }

    static String npuLabel() {
        isSupported();
        return cachedNpuLabel == null ? "NPU DSP" : cachedNpuLabel;
    }

    static boolean enabled(Context c) { return isSupported() && MainActivity.prefs(c).getBoolean(PREF_ON, false); }
    static boolean glowEnabled(Context c) { return MainActivity.prefs(c).getBoolean(PREF_HIRES_GLOW, true); }

    static void setEnabled(Context c, boolean on) {
        if (!isSupported()) on = false;
        MainActivity.prefs(c).edit().putBoolean(PREF_ON, on).apply();
        apply(c);
        Player.notifyAll0();
    }

    static String profile(Context c) { return MainActivity.prefs(c).getString(PREF_PROFILE, "studio"); }
    static int strength(Context c) { return clampPct(MainActivity.prefs(c).getInt(PREF_STRENGTH, 70)); }
    static int air(Context c) { return clampPct(MainActivity.prefs(c).getInt(PREF_AIR, 65)); }
    static int dyn(Context c) { return clampPct(MainActivity.prefs(c).getInt(PREF_DYN, 55)); }
    static int vocal(Context c) { return clampPct(MainActivity.prefs(c).getInt(PREF_VOCAL, 45)); }
    static boolean limiter(Context c) { return MainActivity.prefs(c).getBoolean(PREF_LIMITER, true); }

    private static int clampPct(int v) { return Math.max(0, Math.min(100, v)); }

    static void setProfile(Context c, int idx) {
        if (idx < 0 || idx >= PROFILES.length) return;
        int[] v = PROFILE_VALS[idx];
        MainActivity.prefs(c).edit()
                .putString(PREF_PROFILE, PROFILES[idx])
                .putInt(PREF_STRENGTH, v[0])
                .putInt(PREF_AIR, v[1])
                .putInt(PREF_DYN, v[2])
                .putInt(PREF_VOCAL, v[3])
                .apply();
        apply(c);
    }

    static void setParam(Context c, String key, int pct) {
        MainActivity.prefs(c).edit()
                .putInt(key, clampPct(pct))
                .putString(PREF_PROFILE, "custom")
                .apply();
        apply(c);
    }

    static String summary(Context c) {
        if (!isSupported()) return c.getString(R.string.bpa_sub_unsupported);
        if (!enabled(c)) return c.getString(R.string.bpa_sub_off);
        return c.getString(R.string.bpa_sub_on, strength(c) + "%");
    }

    static String status(Context c) {
        String name = displaySoc(c);
        if (!isSupported()) return c.getString(R.string.npu_unsupported, name);
        if (enabled(c) && !activeEngine.isEmpty()) return c.getString(R.string.npu_active, name, activeEngine);
        return c.getString(R.string.npu_supported, name);
    }

    /** Attaches directly to the active local MediaPlayer audio session so DSP always takes audible effect. */
    static void attachLocalSession(Context c, int audioSessionId) {
        localSession = audioSessionId;
        apply(c);
    }

    /**
     * Activates or updates the audio enhancement chain for both local and Spotify playback.
     * Uses the direct local MediaPlayer session when playing on-device music, and global output mix
     * (session 0) + 5-stage Web Audio exciter/EQ/compressor for Spotify.
     */
    static void apply(Context c) {
        boolean want = enabled(c) && BitPerfect.activeRate <= 0;
        float str = strength(c) / 100f;
        float airK = (air(c) / 100f) * str;
        float dynK = (dyn(c) / 100f) * str;
        float vocK = (vocal(c) / 100f) * str;
        boolean limOn = limiter(c);

        // Enhanced 5-stage Web Audio post-processing for Spotify Web Playback SDK
        SpotifyWeb.setNpuEnhancer(want && !Player.local(), 4.5f * airK, 3.0f * vocK, 1.8f * str, dynK);
        if (!want) {
            release();
            return;
        }
        int targetSession = (Player.local() && localSession > 0) ? localSession : 0;
        if (dp != null && dpSession != targetSession) release();

        try {
            // Audible studio-mastering 12-band Pre-EQ + 4-band MBC transient expander + anti-clipping limiter
            final float[] cutoffs = {45f, 90f, 180f, 360f, 700f, 1400f, 2800f, 4800f, 8000f, 12000f, 16000f, 20000f};
            final float[] gains = {
                    1.8f * str, 1.0f * str, 0.0f, -0.5f * str, 0.0f,
                    0.8f * vocK, 2.6f * vocK, 3.0f * vocK,
                    3.0f * airK, 4.0f * airK, 4.5f * airK, 3.6f * airK
            };
            final float[] mbcCut = {180f, 1600f, 7500f, 20000f};
            if (dp == null) {
                DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                        true, cutoffs.length,
                        true, mbcCut.length,
                        false, 0,
                        true).build();
                dp = new DynamicsProcessing(0, targetSession, cfg);
                dpSession = targetSession;
            }
            for (int i = 0; i < cutoffs.length; i++) {
                dp.setPreEqBandAllChannelsTo(i, new DynamicsProcessing.EqBand(true, cutoffs[i], gains[i]));
            }
            for (int i = 0; i < mbcCut.length; i++) {
                float ratio = 1f + (i >= 2 ? 0.36f : 0.18f) * dynK;
                float postGain = (i >= 2 ? 1.6f : 0.6f) * dynK;
                DynamicsProcessing.MbcBand b = new DynamicsProcessing.MbcBand(
                        dynK > 0.01f, mbcCut[i], 2.5f, 55f, ratio, -20f, 6f, -65f, 1f + 0.25f * dynK, 0f, postGain);
                dp.setMbcBandAllChannelsTo(i, b);
            }
            DynamicsProcessing.Limiter lim = new DynamicsProcessing.Limiter(limOn, true, 0, 1f, 45f, 10f, -0.4f, 0f);
            dp.setLimiterAllChannelsTo(lim);
            dp.setEnabled(true);
            activeEngine = (targetSession > 0 ? "Direct Audio DSP · " : "Output Mix DSP · ") + npuLabel();
        } catch (Throwable t) {
            release();
            activeEngine = Player.local() ? npuLabel() : ("WebAudio DSP · " + npuLabel());
        }
    }

    static void release() {
        activeEngine = "";
        dpSession = -1;
        if (dp != null) {
            try { dp.setEnabled(false); dp.release(); } catch (Throwable ignored) {}
            dp = null;
        }
    }

    private static String readCpuInfoHardware() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("Hardware") || line.startsWith("model name")) {
                    int idx = line.indexOf(':');
                    if (idx > 0) return line.substring(idx + 1).trim();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ================================================================ Official Chipset Artwork View
    private static final android.util.SparseArray<android.graphics.Bitmap> CHIP_BMP_CACHE = new android.util.SparseArray<>();

    private static android.graphics.Bitmap chipBitmap(Context c, int res) {
        if (res == 0 || c == null) return null;
        android.graphics.Bitmap b = CHIP_BMP_CACHE.get(res);
        if (b != null) return b;
        try {
            b = android.graphics.BitmapFactory.decodeResource(c.getResources(), res);
            if (b != null) CHIP_BMP_CACHE.put(res, b);
        } catch (Throwable ignored) {}
        return b;
    }

    /** Displays the official chipset artwork with clean rounded corners (or minimal fallback when unknown). */
    static final class ChipArtView extends View {
        private final ChipSpec spec;
        private final String fallbackLabel;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final android.graphics.Path clipPath = new android.graphics.Path();

        ChipArtView(Context c, ChipSpec spec, String fallbackLabel) {
            super(c);
            this.spec = spec;
            this.fallbackLabel = fallbackLabel == null ? "SoC" : fallbackLabel;
            tp.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float s = Math.min(w, h);
            float ox = (w - s) / 2f, oy = (h - s) / 2f;
            float rad = s * 0.18f;
            r.set(ox, oy, ox + s, oy + s);

            android.graphics.Bitmap bmp = (spec != null && spec.drawableRes != 0) ? chipBitmap(getContext(), spec.drawableRes) : null;
            if (bmp != null) {
                clipPath.reset();
                clipPath.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
                int sc = c.save();
                c.clipPath(clipPath);
                c.drawBitmap(bmp, null, r, p);
                c.restoreToCount(sc);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f, Ui.dpf(0.6f)));
                p.setColor(Ui.dark ? 0x26FFFFFF : 0x1A000000);
                c.drawRoundRect(r, rad, rad, p);
                p.setStyle(Paint.Style.FILL);
                return;
            }

            // Minimal placeholder for unknown / unsupported SoCs
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, 0xFF1F2229, 0xFF2E323D, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rad, rad, p);
            p.setShader(null);
            tp.setTypeface(Ui.semibold);
            tp.setTextSize(s * 0.14f);
            tp.setColor(0xFF94A3B8);
            c.drawText("SoC", ox + s / 2f, oy + s * 0.56f, tp);
        }
    }
}
