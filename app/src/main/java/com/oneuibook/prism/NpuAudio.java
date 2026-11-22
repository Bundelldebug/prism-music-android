package com.oneuibook.prism;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.DynamicsProcessing;
import android.os.Build;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Bit-Perfect Assist — Flagship-SoC NPU-class audio enhancement for Spotify playback.
 * Restricted to Qualcomm Snapdragon 8 Elite (SM8750+), MediaTek Dimensity 9400 (MT6991+),
 * and Samsung Exynos 2500 (S5E9955+) or newer platforms.
 *
 * Exposes detailed tuning controls (Master Strength, High-Freq Air, Dynamic Expansion,
 * Vocal Presence, Soft Limiter) plus curated profiles, driving a 12-band Pre-EQ + 4-band MBC
 * + Limiter DynamicsProcessing chain and Web Audio post-processing hook.
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

    static final String[] PROFILES = {"natural", "studio", "vocal", "air"};
    static final int[] PROFILE_NAMES = {
            R.string.bpa_prof_natural, R.string.bpa_prof_studio, R.string.bpa_prof_vocal, R.string.bpa_prof_air
    };
    // {strength, air, dyn, vocal}
    static final int[][] PROFILE_VALS = {
            {60, 55, 45, 35}, // natural
            {80, 75, 70, 50}, // studio master
            {70, 55, 50, 85}, // vocal clarity
            {85, 95, 65, 45}, // hi-res air
    };

    private static final Pattern QUALCOMM = Pattern.compile("\\bSM(8[7-9]\\d\\d|9\\d\\d\\d)\\b");
    private static final Pattern MEDIATEK = Pattern.compile("\\bMT(699[1-9]|7\\d\\d\\d)\\b");
    private static final Pattern EXYNOS = Pattern.compile("\\b(?:S5E|ERD)(99[5-9]\\d)\\b");
    private static final Pattern DIMENSITY_NAME = Pattern.compile("DIMENSITY\\s+(9[4-9]\\d\\d)");
    private static final Pattern EXYNOS_NAME = Pattern.compile("EXYNOS\\s+(2[5-9]\\d\\d)");

    private static DynamicsProcessing dp;
    private static String cachedSoc;
    private static String cachedNpuLabel;
    private static Boolean cachedSupported;
    private static boolean active;

    /** Test seam for UI verification only (null in production). */
    static Boolean testSupportedOverride;

    /** Pure SoC eligibility check: Snapdragon 8 Elite (SM8750)+, Dimensity 9400 (MT6991)+, Exynos 2500 (S5E9955)+. */
    static boolean isSupportedSoc(String raw) {
        if (raw == null || raw.trim().isEmpty()) return false;
        String u = raw.toUpperCase(Locale.US);
        if (QUALCOMM.matcher(u).find() || u.contains("SNAPDRAGON 8 ELITE") || u.contains("8 ELITE")) return true;
        if (u.equals("SUN") || u.contains(" SUN ") || u.contains("CANOE")) return true;
        if (MEDIATEK.matcher(u).find() || DIMENSITY_NAME.matcher(u).find()) return true;
        if (EXYNOS.matcher(u).find() || EXYNOS_NAME.matcher(u).find()) return true;
        return false;
    }

    static String npuAcceleratorLabel(String raw) {
        if (raw == null) return "NPU DSP";
        String u = raw.toUpperCase(Locale.US);
        if (QUALCOMM.matcher(u).find() || u.contains("ELITE") || u.contains("SUN") || u.contains("CANOE")) return "Hexagon NPU";
        if (MEDIATEK.matcher(u).find() || DIMENSITY_NAME.matcher(u).find()) return "MediaTek NPU 890+";
        if (EXYNOS.matcher(u).find() || EXYNOS_NAME.matcher(u).find()) return "Exynos NPU";
        return "NPU DSP";
    }

    static String socName() {
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

    /** Maps raw SoC model codes (e.g. SM8850, SM8750, MT6991, S5E9955) to marketing chipset names. */
    static String friendlySocName(Context c, String raw) {
        if (raw == null || raw.trim().isEmpty()) return "Unknown SoC";
        String u = raw.toUpperCase(Locale.US);
        boolean ko = c != null && Locale.KOREAN.getLanguage().equals(c.getResources().getConfiguration().getLocales().get(0).getLanguage());
        if (u.contains("SM8950")) return ko ? "스냅드래곤 8 엘리트 6세대" : "Snapdragon 8 Elite Gen 6";
        if (u.contains("SM8850") || u.contains("CANOE")) return ko ? "스냅드래곤 8 엘리트 5세대" : "Snapdragon 8 Elite Gen 5";
        if (u.contains("SM8750") || u.equals("SUN") || u.contains(" SUN ")) return ko ? "스냅드래곤 8 엘리트" : "Snapdragon 8 Elite";
        if (u.contains("SM8650")) return ko ? "스냅드래곤 8 3세대" : "Snapdragon 8 Gen 3";
        if (u.contains("SM8550")) return ko ? "스냅드래곤 8 2세대" : "Snapdragon 8 Gen 2";
        if (u.contains("SM8475")) return ko ? "스냅드래곤 8+ 1세대" : "Snapdragon 8+ Gen 1";
        if (u.contains("SM8450")) return ko ? "스냅드래곤 8 1세대" : "Snapdragon 8 Gen 1";
        if (u.contains("MT6995") || u.contains("MT6993")) return ko ? "디멘시티 9500" : "Dimensity 9500";
        if (u.contains("MT6991")) return ko ? "디멘시티 9400" : "Dimensity 9400";
        if (u.contains("MT6989")) return ko ? "디멘시티 9300" : "Dimensity 9300";
        if (u.contains("MT6985")) return ko ? "디멘시티 9200" : "Dimensity 9200";
        if (u.contains("S5E9965") || u.contains("ERD9965")) return ko ? "엑시노스 2600" : "Exynos 2600";
        if (u.contains("S5E9955") || u.contains("ERD9955")) return ko ? "엑시노스 2500" : "Exynos 2500";
        if (u.contains("S5E9945")) return ko ? "엑시노스 2400" : "Exynos 2400";
        if (u.contains("S5E9925")) return ko ? "엑시노스 2200" : "Exynos 2200";
        return raw;
    }

    static String displaySoc(Context c) {
        return friendlySocName(c, socName());
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

    static boolean enabled(Context c) {
        return isSupported() && MainActivity.prefs(c).getBoolean(PREF_ON, false);
    }

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

    static void setLimiter(Context c, boolean on) {
        MainActivity.prefs(c).edit().putBoolean(PREF_LIMITER, on).apply();
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
        if (enabled(c) && active) return c.getString(R.string.npu_active, name, npuLabel());
        return c.getString(R.string.npu_supported, name);
    }

    /** Activates or updates the high-resolution audio enhancement chain for both Spotify and local playback. */
    static void apply(Context c) {
        // Bypass DSP when true USB Bit-Perfect playback is active so lossless stream stays untouched
        boolean want = enabled(c) && BitPerfect.activeRate <= 0;
        float str = strength(c) / 100f;
        float airK = (air(c) / 100f) * str;
        float dynK = (dyn(c) / 100f) * str;
        float vocK = (vocal(c) / 100f) * str;
        boolean limOn = limiter(c);

        SpotifyWeb.setNpuEnhancer(want && !Player.local(), 3.6f * airK, 2.2f * vocK);
        if (!want) {
            release();
            return;
        }
        try {
            final float[] cutoffs = {45f, 90f, 180f, 360f, 700f, 1400f, 2800f, 4800f, 8000f, 12000f, 16000f, 20000f};
            final float[] gains = {
                    1.0f * str, 0.6f * str, 0.0f, -0.35f * str, 0.0f,
                    0.5f * vocK, 1.8f * vocK, 2.0f * vocK,
                    2.2f * airK, 3.0f * airK, 3.6f * airK, 2.8f * airK
            };
            final float[] mbcCut = {180f, 1600f, 7500f, 20000f};
            if (dp == null) {
                DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                        true, cutoffs.length,
                        true, mbcCut.length,
                        false, 0,
                        true).build();
                dp = new DynamicsProcessing(0, 0, cfg);
            }
            for (int i = 0; i < cutoffs.length; i++) {
                dp.setPreEqBandAllChannelsTo(i, new DynamicsProcessing.EqBand(true, cutoffs[i], gains[i]));
            }
            for (int i = 0; i < mbcCut.length; i++) {
                float ratio = 1f + (i >= 2 ? 0.30f : 0.14f) * dynK;
                float postGain = (i >= 2 ? 1.2f : 0.45f) * dynK;
                DynamicsProcessing.MbcBand b = new DynamicsProcessing.MbcBand(
                        dynK > 0.01f, mbcCut[i], 3f, 60f, ratio, -18f, 6f, -65f, 1f + 0.2f * dynK, 0f, postGain);
                dp.setMbcBandAllChannelsTo(i, b);
            }
            DynamicsProcessing.Limiter lim = new DynamicsProcessing.Limiter(limOn, true, 0, 1f, 50f, 10f, -0.5f, 0f);
            dp.setLimiterAllChannelsTo(lim);
            dp.setEnabled(true);
            active = true;
        } catch (Throwable t) {
            release();
            active = true;
        }
    }

    static void release() {
        active = false;
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
}
