package com.oneuibook.prism;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.BassBoost;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.Virtualizer;
import android.os.Build;
import android.text.TextPaint;
import android.util.SparseArray;
import android.view.View;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Bit-Perfect Assist — Flagship-SoC NPU-class audio enhancement (15 supported chipsets).
 * Supports real-time zero-latency parameter updates, instant A/B bypass comparison,
 * 12-band Pre-EQ + 4-band MBC dynamic expander + soft limiter + Virtualizer stereo soundstage,
 * harmonic exciter voicings (Studio / Crystal / Warm Tube), per-device preset memory,
 * and a live frequency-response curve preview.
 */
final class NpuAudio {
    private NpuAudio() {}

    static final String PREF_ON = "npu_audio_on";
    static final String PREF_PROFILE = "npu_profile";
    static final String PREF_STRENGTH = "npu_strength";
    static final String PREF_AIR = "npu_air";
    static final String PREF_DYN = "npu_dyn";
    static final String PREF_VOCAL = "npu_vocal";
    static final String PREF_BASS = "npu_bass";
    static final String PREF_SPACE = "npu_space";
    static final String PREF_HARMONICS = "npu_harmonics";
    static final String PREF_LIMITER = "npu_limiter";
    static final String PREF_PER_DEVICE = "npu_per_device";
    static final String PREF_HIRES_GLOW = "hires_glow";

    static final int VENDOR_QUALCOMM = 0, VENDOR_MEDIATEK = 1, VENDOR_SAMSUNG = 2, VENDOR_GOOGLE = 3;

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

        /**
         * Silicon family = model number without the variant suffix: SM8850-1 → SM8850, MT6991Z / MT6991W → MT6991,
         * MT6899Z → MT6899. A chip whose model number isn't public yet has no family ("").
         */
        String family() {
            Matcher fm = FAMILY.matcher(id);
            return fm.find() ? fm.group(1) : "";
        }
    }

    private static final Pattern FAMILY = Pattern.compile("^((?:SM|MT|S5E|GS)\\d{3,4})");

    /** Supported chips of one vendor grouped by silicon family, in list order. */
    static java.util.List<java.util.List<ChipSpec>> chipFamilies(int vendor) {
        java.util.LinkedHashMap<String, java.util.List<ChipSpec>> m = new java.util.LinkedHashMap<>();
        for (ChipSpec cs : SUPPORTED_CHIPS) {
            if (cs.vendor != vendor) continue;
            String k = cs.family().isEmpty() ? cs.id : cs.family();
            java.util.List<ChipSpec> l = m.get(k);
            if (l == null) { l = new java.util.ArrayList<>(); m.put(k, l); }
            l.add(cs);
        }
        return new java.util.ArrayList<>(m.values());
    }

    /** Supported flagship / upper-flagship SoCs with NPU/DSP headroom, listed by silicon family (model number). */
    static final ChipSpec[] SUPPORTED_CHIPS = {
            // Qualcomm Snapdragon (10)
            new ChipSpec("SM8550",  VENDOR_QUALCOMM, "스냅드래곤 8 2세대", "Snapdragon 8 Gen 2", "SNAPDRAGON", "8 Gen 2", "Hexagon NPU", false, R.drawable.chip_sm8550),
            new ChipSpec("SM8635",  VENDOR_QUALCOMM, "스냅드래곤 8s 3세대", "Snapdragon 8s Gen 3", "SNAPDRAGON", "8s Gen 3", "Hexagon NPU", false, R.drawable.chip_sm8635),
            new ChipSpec("SM8650",  VENDOR_QUALCOMM, "스냅드래곤 8 3세대", "Snapdragon 8 Gen 3", "SNAPDRAGON", "8 Gen 3", "Hexagon NPU", false, R.drawable.chip_sm8650),
            new ChipSpec("SM8735",  VENDOR_QUALCOMM, "스냅드래곤 8s 4세대", "Snapdragon 8s Gen 4", "SNAPDRAGON", "8s Gen 4", "Hexagon NPU", false, R.drawable.chip_sm8735),
            new ChipSpec("SM8750",  VENDOR_QUALCOMM, "스냅드래곤 8 엘리트", "Snapdragon 8 Elite", "SNAPDRAGON", "8 Elite", "Hexagon NPU", false, R.drawable.chip_sm8750),
            new ChipSpec("SM8845",  VENDOR_QUALCOMM, "스냅드래곤 8 5세대", "Snapdragon 8 Gen 5", "SNAPDRAGON", "8 Gen 5", "Hexagon NPU", false, R.drawable.chip_sm8845),
            new ChipSpec("SM8850",  VENDOR_QUALCOMM, "스냅드래곤 8 엘리트 5세대", "Snapdragon 8 Elite Gen 5", "SNAPDRAGON", "8 Elite G5", "Hexagon NPU", false, R.drawable.chip_sm8850),
            new ChipSpec("SM8850-1",VENDOR_QUALCOMM, "스냅드래곤 8 엘리트 5세대 V 시리즈", "Snapdragon 8 Elite Gen 5 V", "SNAPDRAGON", "8 Elite 5V", "Hexagon NPU", true, R.drawable.chip_sm8850),
            new ChipSpec("SM8950",  VENDOR_QUALCOMM, "스냅드래곤 8 엘리트 6세대", "Snapdragon 8 Elite Gen 6", "SNAPDRAGON", "8 Elite G6", "Hexagon NPU", false, R.drawable.chip_sm8950),
            new ChipSpec("SM8975",  VENDOR_QUALCOMM, "스냅드래곤 8 엘리트 익스트림 6세대", "Snapdragon 8 Elite Extreme Gen 6", "SNAPDRAGON", "8 Extreme", "Hexagon NPU", true, R.drawable.chip_sm8975),
            // MediaTek Dimensity (9) — verified model codes (9600M is MT6993 silicon family with NPU 990)
            new ChipSpec("MT6899",  VENDOR_MEDIATEK, "디멘시티 8400", "Dimensity 8400", "DIMENSITY", "8400", "MediaTek NPU 880", false, R.drawable.chip_mt6899),
            // Dimensity 8500 = MT6899Z, the same MT6899 silicon as the 8400 (no official illustration: generated badge)
            new ChipSpec("MT6899Z", VENDOR_MEDIATEK, "디멘시티 8500", "Dimensity 8500", "DIMENSITY", "8500", "MediaTek NPU 880", false, 0),
            // Dimensity 8600: model number not public yet — detected by its marketing name
            new ChipSpec("D8600",   VENDOR_MEDIATEK, "디멘시티 8600", "Dimensity 8600", "DIMENSITY", "8600", "MediaTek NPU", false, 0),
            new ChipSpec("MT6989",  VENDOR_MEDIATEK, "디멘시티 9300", "Dimensity 9300", "DIMENSITY", "9300", "MediaTek NPU 790", false, R.drawable.chip_mt6989),
            new ChipSpec("MT6989T", VENDOR_MEDIATEK, "디멘시티 9300+", "Dimensity 9300+", "DIMENSITY", "9300+", "MediaTek NPU 790+", true, R.drawable.chip_mt6989t),
            new ChipSpec("MT6991",  VENDOR_MEDIATEK, "디멘시티 9400", "Dimensity 9400", "DIMENSITY", "9400", "MediaTek NPU 890", false, R.drawable.chip_mt6991),
            new ChipSpec("MT6991Z", VENDOR_MEDIATEK, "디멘시티 9400+", "Dimensity 9400+", "DIMENSITY", "9400+", "MediaTek NPU 890+", true, R.drawable.chip_mt6991p),
            new ChipSpec("MT6991W", VENDOR_MEDIATEK, "디멘시티 9500s", "Dimensity 9500s", "DIMENSITY", "9500s", "MediaTek NPU 890+", false, R.drawable.chip_mt6991s),
            new ChipSpec("MT6993",  VENDOR_MEDIATEK, "디멘시티 9500", "Dimensity 9500", "DIMENSITY", "9500", "MediaTek NPU 990", false, R.drawable.chip_mt6993),
            new ChipSpec("MT6993M", VENDOR_MEDIATEK, "디멘시티 9600M", "Dimensity 9600M", "DIMENSITY", "9600M", "MediaTek NPU 990", false, R.drawable.chip_mt6995m),
            new ChipSpec("MT6995",  VENDOR_MEDIATEK, "디멘시티 9600 프로", "Dimensity 9600 Pro", "DIMENSITY", "9600 Pro", "MediaTek NPU", true, R.drawable.chip_mt6995p),
            // Samsung Exynos (4) — Exynos 2700 is unreleased, intentionally no illustration (res = 0)
            new ChipSpec("S5E9945", VENDOR_SAMSUNG, "엑시노스 2400", "Exynos 2400", "EXYNOS", "2400", "Exynos NPU", false, R.drawable.chip_s5e9945),
            new ChipSpec("S5E9955", VENDOR_SAMSUNG, "엑시노스 2500", "Exynos 2500", "EXYNOS", "2500", "Exynos NPU", false, R.drawable.chip_s5e9955),
            new ChipSpec("S5E9965", VENDOR_SAMSUNG, "엑시노스 2600", "Exynos 2600", "EXYNOS", "2600", "Exynos NPU", false, R.drawable.chip_s5e9965),
            new ChipSpec("S5E9975", VENDOR_SAMSUNG, "엑시노스 2700", "Exynos 2700", "EXYNOS", "2700", "Exynos NPU", true, 0),
            // Google Tensor (2)
            new ChipSpec("GS401",   VENDOR_GOOGLE,  "구글 텐서 G4", "Google Tensor G4", "TENSOR", "G4", "Google TPU", false, R.drawable.chip_gs401),
            new ChipSpec("GS501",   VENDOR_GOOGLE,  "구글 텐서 G5", "Google Tensor G5", "TENSOR", "G5", "Google TPU", true, R.drawable.chip_gs501),
    };

    static final String[] PROFILES = {"natural", "studio", "vocal", "air"};
    static final int[] PROFILE_NAMES = {
            R.string.bpa_prof_natural, R.string.bpa_prof_studio, R.string.bpa_prof_vocal, R.string.bpa_prof_air
    };
    // {strength, air, dyn, vocal, bass, space}
    static final int[][] PROFILE_VALS = {
            {60, 55, 45, 35, 40, 25}, // natural
            {80, 75, 70, 50, 55, 40}, // studio master
            {70, 55, 50, 85, 35, 30}, // vocal clarity
            {85, 95, 65, 45, 50, 55}, // hi-res air
    };

    static final String[] HARMONICS = {"studio", "crystal", "tube"};
    static final int[] HARMONIC_NAMES = {
            R.string.bpa_harm_studio, R.string.bpa_harm_crystal, R.string.bpa_harm_tube
    };

    static final float[] EQ_CUTOFFS = {45f, 90f, 180f, 360f, 700f, 1400f, 2800f, 4800f, 8000f, 12000f, 16000f, 20000f};

    private static final Pattern QUALCOMM = Pattern.compile("\\bSM(8[5-9]\\d\\d(?:[-A-Z0-9]+)?|9\\d\\d\\d(?:[-A-Z0-9]+)?)\\b");
    private static final Pattern MEDIATEK = Pattern.compile("\\bMT(6899[A-Z]?|6989[A-Z]?|699[1-9][A-Z]?|7\\d\\d\\d[A-Z]?)\\b");
    private static final Pattern EXYNOS = Pattern.compile("\\b(?:S5E|ERD)(99[4-9]\\d)\\b");
    private static final Pattern TENSOR = Pattern.compile("\\b(GS[4-9]0\\d|ZUMAPRO|LAGUNA|TENSOR\\s+G[4-9])\\b");
    private static final Pattern DIMENSITY_NAME = Pattern.compile("DIMENSITY\\s+(8[4-9]\\d\\d[A-Z+]*|9[3-9]\\d\\d[A-Z+]*)");
    private static final Pattern EXYNOS_NAME = Pattern.compile("EXYNOS\\s+(2[4-9]\\d\\d)");

    private static DynamicsProcessing dp;
    private static Equalizer eqFx;
    private static BassBoost bassFx;
    private static LoudnessEnhancer loudFx;
    private static Virtualizer virt;
    private static int dpSession = -1;
    private static int localSession = -1;
    private static boolean usingFallbackFx;
    private static String cachedSoc;
    private static String cachedNpuLabel;
    private static Boolean cachedSupported;
    private static String activeEngine = "";
    private static String lastDeviceKey = "";
    private static long lastWebApplyMs;
    /** True while user holds the real-time A/B Bypass button to hear unprocessed audio. */
    static volatile boolean bypassHold;

    static Boolean testSupportedOverride;
    static String testSocOverride;

    static boolean isSupportedSoc(String raw) {
        if (raw == null || raw.trim().isEmpty()) return false;
        String u = raw.toUpperCase(Locale.US);
        if (QUALCOMM.matcher(u).find() || u.contains("SNAPDRAGON 8") || u.contains("8 ELITE")) return true;
        if (u.equals("SUN") || u.contains(" SUN ") || u.contains("CANOE") || u.contains("PINEAPPLE") || u.contains("KALAMA")) return true;
        if (MEDIATEK.matcher(u).find() || DIMENSITY_NAME.matcher(u).find()) return true;
        if (EXYNOS.matcher(u).find() || EXYNOS_NAME.matcher(u).find()) return true;
        if (TENSOR.matcher(u).find()) return true;
        // listed chips without a public model number (Dimensity 8600) are recognised by name
        return matchChipSpec(raw) != null;
    }

    private static ChipSpec findChip(String id) {
        for (ChipSpec cs : SUPPORTED_CHIPS) if (cs.id.equals(id)) return cs;
        return null;
    }

    static ChipSpec matchChipSpec(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String u = raw.toUpperCase(Locale.US);
        // Qualcomm Snapdragon
        if (u.contains("SM8975") || u.contains("SM8950X") || u.contains("EXTREME")) return findChip("SM8975");
        if (u.contains("SM8950")) return findChip("SM8950");
        if (u.contains("SM8850-1") || u.contains("SM8850V") || u.contains("8 ELITE GEN 5 V")) return findChip("SM8850-1");
        if (u.contains("SM8850") || u.contains("CANOE")) return findChip("SM8850");
        if (u.contains("SM8845") || u.contains("8 GEN 5")) return findChip("SM8845");
        if (u.contains("SM8750") || u.equals("SUN") || u.contains(" SUN ") || u.contains("8 ELITE")) return findChip("SM8750");
        if (u.contains("SM8735") || u.contains("8S GEN 4")) return findChip("SM8735");
        if (u.contains("SM8650") || u.contains("PINEAPPLE") || u.contains("8 GEN 3")) return findChip("SM8650");
        if (u.contains("SM8635") || u.contains("8S GEN 3")) return findChip("SM8635");
        if (u.contains("SM8550") || u.contains("KALAMA") || u.contains("8 GEN 2")) return findChip("SM8550");
        // MediaTek Dimensity (check 9600M MT6993M/W before base MT6993!)
        if (u.contains("MT6995") || u.contains("9600 PRO")) return findChip("MT6995");
        if (u.contains("MT6993M") || u.contains("MT6993W") || u.contains("MT6993Z") || u.contains("9600M")) return findChip("MT6993M");
        if (u.contains("MT6991W") || u.contains("MT6991S") || u.contains("9500S")) return findChip("MT6991W");
        if (u.contains("MT6993") || u.contains("9500")) return findChip("MT6993");
        if (u.contains("MT6991Z") || u.contains("MT6991+") || u.contains("MT6991P") || u.contains("9400+")) return findChip("MT6991Z");
        if (u.contains("MT6991") || u.contains("9400")) return findChip("MT6991");
        if (u.contains("MT6989T") || u.contains("MT6989+") || u.contains("9300+")) return findChip("MT6989T");
        if (u.contains("MT6989") || u.contains("9300")) return findChip("MT6989");
        if (u.contains("MT6899Z") || u.contains("DIMENSITY 8500")) return findChip("MT6899Z");
        if (u.contains("DIMENSITY 8600") || u.equals("D8600")) return findChip("D8600");
        if (u.contains("MT6899") || u.contains("8400")) return findChip("MT6899");
        // Samsung Exynos
        if (u.contains("S5E9975") || u.contains("ERD9975") || u.contains("2700")) return findChip("S5E9975");
        if (u.contains("S5E9965") || u.contains("ERD9965") || u.contains("2600")) return findChip("S5E9965");
        if (u.contains("S5E9955") || u.contains("ERD9955") || u.contains("2500")) return findChip("S5E9955");
        if (u.contains("S5E9945") || u.contains("ERD9945") || u.contains("2400")) return findChip("S5E9945");
        // Google Tensor
        if (u.contains("GS501") || u.contains("LAGUNA") || u.contains("TENSOR G5")) return findChip("GS501");
        if (u.contains("GS401") || u.contains("ZUMAPRO") || u.contains("TENSOR G4")) return findChip("GS401");
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
        if (TENSOR.matcher(u).find()) return "Google TPU";
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
    static boolean perDevice(Context c) { return MainActivity.prefs(c).getBoolean(PREF_PER_DEVICE, true); }

    static void setEnabled(Context c, boolean on) {
        if (!isSupported()) on = false;
        MainActivity.prefs(c).edit().putBoolean(PREF_ON, on).apply();
        apply(c, true);
        Player.notifyAll0();
    }

    static void setBypassHold(Context c, boolean hold) {
        if (bypassHold == hold) return;
        bypassHold = hold;
        apply(c, true);
    }

    static String profile(Context c) { return MainActivity.prefs(c).getString(PREF_PROFILE, "studio"); }
    static String harmonics(Context c) { return MainActivity.prefs(c).getString(PREF_HARMONICS, "studio"); }
    static int strength(Context c) { return clampPct(MainActivity.prefs(c).getInt(PREF_STRENGTH, 75)); }
    static int air(Context c) { return clampPct(MainActivity.prefs(c).getInt(PREF_AIR, 70)); }
    static int dyn(Context c) { return clampPct(MainActivity.prefs(c).getInt(PREF_DYN, 60)); }
    static int vocal(Context c) { return clampPct(MainActivity.prefs(c).getInt(PREF_VOCAL, 45)); }
    static int bass(Context c) { return clampPct(MainActivity.prefs(c).getInt(PREF_BASS, 50)); }
    static int space(Context c) { return clampPct(MainActivity.prefs(c).getInt(PREF_SPACE, 35)); }
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
                .putInt(PREF_BASS, v[4])
                .putInt(PREF_SPACE, v[5])
                .apply();
        savePerDevice(c);
        apply(c, true);
    }

    static void setHarmonics(Context c, String mode) {
        MainActivity.prefs(c).edit().putString(PREF_HARMONICS, mode).apply();
        savePerDevice(c);
        apply(c, true);
    }

    static void setParam(Context c, String key, int pct) {
        MainActivity.prefs(c).edit()
                .putInt(key, clampPct(pct))
                .putString(PREF_PROFILE, "custom")
                .apply();
        savePerDevice(c);
        apply(c, false);
    }

    /** Saves current tuning under the connected Bluetooth / USB DAC name when per-device memory is enabled. */
    private static void savePerDevice(Context c) {
        if (!perDevice(c)) return;
        String dk = devicePrefKey(c);
        if (dk.isEmpty()) return;
        String pack = profile(c) + "|" + strength(c) + "|" + air(c) + "|" + dyn(c) + "|" + vocal(c)
                + "|" + bass(c) + "|" + space(c) + "|" + harmonics(c);
        MainActivity.prefs(c).edit().putString("npu_dev_" + dk, pack).apply();
    }

    /** Restores saved tuning automatically whenever the user switches to another remembered output device. */
    static void syncPerDevice(Context c) {
        if (!perDevice(c)) return;
        String dk = devicePrefKey(c);
        if (dk.isEmpty() || dk.equals(lastDeviceKey)) return;
        lastDeviceKey = dk;
        String pack = MainActivity.prefs(c).getString("npu_dev_" + dk, null);
        if (pack == null) return;
        String[] p = pack.split("\\|");
        if (p.length < 8) return;
        try {
            MainActivity.prefs(c).edit()
                    .putString(PREF_PROFILE, p[0])
                    .putInt(PREF_STRENGTH, Integer.parseInt(p[1]))
                    .putInt(PREF_AIR, Integer.parseInt(p[2]))
                    .putInt(PREF_DYN, Integer.parseInt(p[3]))
                    .putInt(PREF_VOCAL, Integer.parseInt(p[4]))
                    .putInt(PREF_BASS, Integer.parseInt(p[5]))
                    .putInt(PREF_SPACE, Integer.parseInt(p[6]))
                    .putString(PREF_HARMONICS, p[7])
                    .apply();
        } catch (Throwable ignored) {}
    }

    private static String devicePrefKey(Context c) {
        AudioInfo.OutputSpec s = AudioInfo.current(c);
        if (!s.external || s.deviceName == null || s.deviceName.isEmpty()) return "";
        return Integer.toHexString(s.deviceName.toLowerCase(Locale.US).hashCode());
    }

    static String summary(Context c) {
        if (!isSupported()) return c.getString(R.string.bpa_sub_unsupported);
        if (!enabled(c)) return c.getString(R.string.bpa_sub_off);
        return c.getString(R.string.bpa_sub_on, strength(c) + "%");
    }

    static String status(Context c) {
        String name = displaySoc(c);
        if (!isSupported()) return c.getString(R.string.npu_unsupported, name);
        if (bypassHold) return c.getString(R.string.bpa_ab_bypassing);
        if (enabled(c) && !activeEngine.isEmpty()) return c.getString(R.string.npu_active, name, activeEngine);
        return c.getString(R.string.npu_supported, name);
    }

    /** Computes the 12-band Pre-EQ gain curve (dB) from current sliders + harmonic voicing mode. */
    static float[] computeBandGains(Context c) {
        float str = strength(c) / 100f;
        float airK = (air(c) / 100f) * str;
        float vocK = (vocal(c) / 100f) * str;
        float basK = (bass(c) / 100f) * str;
        String hm = harmonics(c);
        boolean crystal = "crystal".equals(hm);
        boolean tube = "tube".equals(hm);
        float airMul = crystal ? 1.32f : (tube ? 0.65f : 1.0f);
        float warmAdd = tube ? (2.6f * str) : (crystal ? -1.1f * str : 0f);
        // Oversampling filter voicing (Sharp / Slow / Minimum Phase) & headphone crossfeed low-mid compensation
        String flt = BitPerfect.filter(c);
        float flt16 = "slow".equals(flt) ? -0.9f : ("min".equals(flt) ? 0.45f : 0f);
        float flt20 = "slow".equals(flt) ? -1.8f : ("min".equals(flt) ? 0.35f : 0.4f);
        float xfeedWarm = BitPerfect.crossfeed(c) ? 0.65f : 0f;
        float ditherAir = "ns".equals(BitPerfect.dither(c)) ? 0.45f : 0f;
        return new float[]{
                clampDb(6.8f * basK + (tube ? 1.0f * str : 0f)),     // 45 Hz sub-bass
                clampDb(5.2f * basK + warmAdd * 0.4f + xfeedWarm),    // 90 Hz punch bass
                clampDb(1.8f * basK + warmAdd + xfeedWarm * 0.8f),    // 180 Hz warmth
                clampDb(-1.4f * str + warmAdd * 0.7f),                // 360 Hz mud cut / tube body
                clampDb(-0.6f * str + warmAdd * 0.35f),               // 700 Hz clean low-mid
                clampDb(1.8f * vocK),                                 // 1.4 kHz vocal body
                clampDb(4.8f * vocK),                                 // 2.8 kHz vocal presence
                clampDb(5.4f * vocK + (crystal ? 0.8f * str : 0f)),   // 4.8 kHz clarity
                clampDb(5.2f * airK * airMul),                        // 8 kHz brilliance
                clampDb(6.8f * airK * airMul + ditherAir),            // 12 kHz air
                clampDb(7.8f * airK * airMul + flt16 + ditherAir),    // 16 kHz ultra-air
                clampDb(6.2f * airK * airMul + flt20)                 // 20 kHz harmonic sheen
        };
    }

    private static float clampDb(float db) { return Math.max(-6f, Math.min(9.5f, db)); }

    static void attachLocalSession(Context c, int audioSessionId) {
        localSession = audioSessionId;
        apply(c, true);
    }

    static void apply(Context c) { apply(c, true); }

    /**
     * Real-time zero-latency DSP update:
     * 1) Commits full Eq / Mbc / Limiter objects to DynamicsProcessing (required on Samsung One UI HALs)
     *    and verifies {@code setEnabled(true) == AudioEffect.SUCCESS}.
     * 2) If DynamicsProcessing is unavailable or rejected on the target session, automatically attaches a
     *    5-band Equalizer + BassBoost + LoudnessEnhancer native DSP fallback chain so audible changes are real.
     * 3) During A/B bypassHold, immediately toggles all native effects off (0ms) without tearing down sessions.
     */
    static void apply(Context c, boolean immediateWeb) {
        if (c == null) return;
        syncPerDevice(c);
        boolean want = enabled(c) && !bypassHold && BitPerfect.activeRate <= 0;
        float str = strength(c) / 100f;
        float airK = (air(c) / 100f) * str;
        float dynK = (dyn(c) / 100f) * str;
        float vocK = (vocal(c) / 100f) * str;
        float basK = (bass(c) / 100f) * str;
        float spcK = (space(c) / 100f) * str;
        boolean limOn = limiter(c);

        long now = android.os.SystemClock.uptimeMillis();
        if (immediateWeb || now - lastWebApplyMs >= 45) {
            lastWebApplyMs = now;
            SpotifyWeb.setNpuEnhancer(want && !Player.local(), 6.8f * airK, 4.8f * vocK, 5.8f * basK, dynK);
        }

        if (!want) {
            setAllFxEnabled(false);
            if (!bypassHold) release();
            return;
        }
        int targetSession = (Player.local() && localSession > 0) ? localSession : 0;
        if (dpSession != -1 && dpSession != targetSession) release();

        float[] gains = computeBandGains(c);
        boolean ok = false;
        StringBuilder eng = new StringBuilder();

        // Path A: 12-band DynamicsProcessing (full object commit for Samsung HAL live updates)
        if (!usingFallbackFx) {
            try {
                final float[] mbcCut = {180f, 1600f, 7500f, 20000f};
                if (dp == null) {
                    DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                            true, EQ_CUTOFFS.length,
                            true, mbcCut.length,
                            false, 0,
                            true).build();
                    try {
                        dp = new DynamicsProcessing(100, targetSession, cfg);
                    } catch (Throwable prioErr) {
                        dp = new DynamicsProcessing(0, targetSession, cfg);
                    }
                    dpSession = targetSession;
                }
                DynamicsProcessing.Eq preEq = new DynamicsProcessing.Eq(true, true, EQ_CUTOFFS.length);
                for (int i = 0; i < EQ_CUTOFFS.length; i++) {
                    preEq.setBand(i, new DynamicsProcessing.EqBand(true, EQ_CUTOFFS[i], gains[i]));
                }
                dp.setPreEqAllChannelsTo(preEq);

                DynamicsProcessing.Mbc mbc = new DynamicsProcessing.Mbc(true, dynK > 0.01f, mbcCut.length);
                for (int i = 0; i < mbcCut.length; i++) {
                    float ratio = 1f + (i >= 2 ? 0.65f : 0.35f) * dynK;
                    float postGain = (i >= 2 ? 2.6f : 1.1f) * dynK;
                    mbc.setBand(i, new DynamicsProcessing.MbcBand(
                            dynK > 0.01f, mbcCut[i], 2.5f, 55f, ratio, -22f, 6f, -65f, 1f + 0.35f * dynK, 0f, postGain));
                }
                dp.setMbcAllChannelsTo(mbc);

                DynamicsProcessing.Limiter lim = new DynamicsProcessing.Limiter(limOn, true, 0, 1f, 45f, 10f, -0.4f, 0f);
                dp.setLimiterAllChannelsTo(lim);
                int rc = dp.setEnabled(true);
                if (rc == AudioEffect.SUCCESS && dp.getEnabled()) {
                    ok = true;
                    eng.append("DynamicsProcessing 12-Band");
                } else {
                    try { dp.setEnabled(false); dp.release(); } catch (Throwable ignored) {}
                    dp = null;
                    usingFallbackFx = true;
                }
            } catch (Throwable t) {
                try { if (dp != null) { dp.setEnabled(false); dp.release(); } } catch (Throwable ignored) {}
                dp = null;
                usingFallbackFx = true;
            }
        }

        // Path B: Universal native fallback (Equalizer + BassBoost + LoudnessEnhancer)
        if (!ok) {
            ok = applyFallbackFx(targetSession, gains, basK, dynK, eng);
        }

        // Virtualizer stereo soundstage width (works alongside both Path A and Path B)
        try {
            if (spcK > 0.02f) {
                if (virt == null) virt = new Virtualizer(100, targetSession);
                if (virt.getStrengthSupported()) {
                    virt.setStrength((short) Math.round(Ui.clamp(spcK, 0f, 1f) * 1000f));
                }
                if (virt.setEnabled(true) == AudioEffect.SUCCESS) {
                    if (eng.length() > 0) eng.append(" + ");
                    eng.append("Spatial");
                    ok = true;
                }
            } else if (virt != null) {
                virt.setEnabled(false);
            }
        } catch (Throwable ignored) {}

        if (ok) {
            dpSession = targetSession;
            activeEngine = eng + " · " + npuLabel();
        } else {
            activeEngine = npuLabel();
        }
    }

    private static boolean applyFallbackFx(int session, float[] gains12, float basK, float dynK, StringBuilder eng) {
        boolean any = false;
        // 1) Multi-band Equalizer mapped from our 12-band curve
        try {
            if (eqFx == null) eqFx = new Equalizer(100, session);
            short bands = eqFx.getNumberOfBands();
            short[] range = eqFx.getBandLevelRange();
            short minMb = range[0], maxMb = range[1];
            for (short b = 0; b < bands; b++) {
                float centerHz = eqFx.getCenterFreq(b) / 1000f;
                float gDb = sampleCurveDb(gains12, centerHz);
                short mb = (short) Math.max(minMb, Math.min(maxMb, Math.round(gDb * 100f)));
                eqFx.setBandLevel(b, mb);
            }
            if (eqFx.setEnabled(true) == AudioEffect.SUCCESS) {
                eng.append("EQ");
                any = true;
            }
        } catch (Throwable ignored) {}

        // 2) Sub-bass enhancer
        try {
            if (basK > 0.02f) {
                if (bassFx == null) bassFx = new BassBoost(100, session);
                if (bassFx.getStrengthSupported()) {
                    bassFx.setStrength((short) Math.round(Ui.clamp(basK, 0f, 1f) * 850f));
                }
                if (bassFx.setEnabled(true) == AudioEffect.SUCCESS) {
                    if (eng.length() > 0) eng.append("+");
                    eng.append("Bass");
                    any = true;
                }
            } else if (bassFx != null) {
                bassFx.setEnabled(false);
            }
        } catch (Throwable ignored) {}

        // 3) Dynamic range / loudness lift
        try {
            if (dynK > 0.02f) {
                if (loudFx == null) loudFx = new LoudnessEnhancer(session);
                loudFx.setTargetGain(Math.round(dynK * 380f)); // up to +3.8 dB crisp dynamic lift
                if (loudFx.setEnabled(true) == AudioEffect.SUCCESS) {
                    if (eng.length() > 0) eng.append("+");
                    eng.append("Dyn");
                    any = true;
                }
            } else if (loudFx != null) {
                loudFx.setEnabled(false);
            }
        } catch (Throwable ignored) {}
        return any;
    }

    private static float sampleCurveDb(float[] gains12, float hz) {
        if (hz <= EQ_CUTOFFS[0]) return gains12[0];
        for (int i = 1; i < EQ_CUTOFFS.length; i++) {
            if (hz <= EQ_CUTOFFS[i]) {
                float k = (hz - EQ_CUTOFFS[i - 1]) / Math.max(1f, EQ_CUTOFFS[i] - EQ_CUTOFFS[i - 1]);
                return Ui.lerp(gains12[i - 1], gains12[i], k);
            }
        }
        return gains12[gains12.length - 1];
    }

    private static void setAllFxEnabled(boolean on) {
        if (dp != null) { try { dp.setEnabled(on); } catch (Throwable ignored) {} }
        if (eqFx != null) { try { eqFx.setEnabled(on); } catch (Throwable ignored) {} }
        if (bassFx != null) { try { bassFx.setEnabled(on); } catch (Throwable ignored) {} }
        if (loudFx != null) { try { loudFx.setEnabled(on); } catch (Throwable ignored) {} }
        if (virt != null) { try { virt.setEnabled(on); } catch (Throwable ignored) {} }
    }

    static void release() {
        activeEngine = "";
        dpSession = -1;
        usingFallbackFx = false;
        if (dp != null) { try { dp.setEnabled(false); dp.release(); } catch (Throwable ignored) {} dp = null; }
        if (eqFx != null) { try { eqFx.setEnabled(false); eqFx.release(); } catch (Throwable ignored) {} eqFx = null; }
        if (bassFx != null) { try { bassFx.setEnabled(false); bassFx.release(); } catch (Throwable ignored) {} bassFx = null; }
        if (loudFx != null) { try { loudFx.setEnabled(false); loudFx.release(); } catch (Throwable ignored) {} loudFx = null; }
        if (virt != null) { try { virt.setEnabled(false); virt.release(); } catch (Throwable ignored) {} virt = null; }
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

    // ================================================================ Live Frequency Response Curve View
    /** Real-time interactive 12-band frequency response graph inside Bit-Perfect Assist. */
    static final class CurveView extends View {
        private final Paint gridP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint zeroP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lineP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint lblP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint badgeP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Path curve = new Path(), area = new Path();

        CurveView(Context c) {
            super(c);
            gridP.setStyle(Paint.Style.STROKE);
            gridP.setStrokeWidth(Math.max(1f, Ui.dpf(0.7f)));
            zeroP.setStyle(Paint.Style.STROKE);
            zeroP.setStrokeWidth(Math.max(1f, Ui.dpf(1.0f)));
            zeroP.setPathEffect(new android.graphics.DashPathEffect(new float[]{Ui.dpf(4), Ui.dpf(4)}, 0));
            lineP.setStyle(Paint.Style.STROKE);
            lineP.setStrokeWidth(Ui.dpf(2.6f));
            lineP.setStrokeCap(Paint.Cap.ROUND);
            lineP.setStrokeJoin(Paint.Join.ROUND);
            dotP.setStyle(Paint.Style.FILL);
            fillP.setStyle(Paint.Style.FILL);
            lblP.setTextSize(Ui.sp(9.5f));
            lblP.setTypeface(Ui.medium);
            lblP.setTextAlign(Paint.Align.CENTER);
            badgeP.setTextSize(Ui.sp(10.5f));
            badgeP.setTypeface(Ui.semibold);
            badgeP.setTextAlign(Paint.Align.RIGHT);
        }

        @Override protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(MeasureSpec.getSize(ws), Ui.dp(146));
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float padL = Ui.dpf(26), padR = Ui.dpf(16), padT = Ui.dpf(24), padB = Ui.dpf(24);
            float gw = w - padL - padR, gh = h - padT - padB;
            // dB range: -4 dB (bottom) to +10 dB (top) -> 0 dB sits at 10/14 = 71.4% down
            float minDb = -4f, maxDb = 10f;
            float zeroY = padT + gh * (maxDb / (maxDb - minDb));

            // Horizontal reference lines (+9, +4, 0, -4 dB)
            gridP.setColor(Ui.dark ? 0x1EFFFFFF : 0x14000000);
            zeroP.setColor(Ui.dark ? 0x44FFFFFF : 0x33000000);
            lblP.setColor(Ui.text3());
            lblP.setTextAlign(Paint.Align.RIGHT);
            for (int db : new int[]{9, 4, 0, -4}) {
                float y = padT + gh * ((maxDb - db) / (maxDb - minDb));
                c.drawLine(padL, y, w - padR, y, db == 0 ? zeroP : gridP);
                c.drawText((db > 0 ? "+" : "") + db, padL - Ui.dpf(5), y + Ui.dpf(3), lblP);
            }
            lblP.setTextAlign(Paint.Align.CENTER);
            String[] labels = {"45Hz", "200Hz", "1kHz", "4kHz", "16kHz"};
            float[] fracs = {0f, 0.25f, 0.50f, 0.75f, 1f};
            for (int i = 0; i < labels.length; i++) {
                float x = padL + gw * fracs[i];
                c.drawLine(x, padT, x, padT + gh, gridP);
                c.drawText(labels[i], x, h - Ui.dpf(6), lblP);
            }

            boolean bypassing = bypassHold;
            boolean on = enabled(getContext()) && !bypassing;
            float[] gains = on ? computeBandGains(getContext()) : new float[EQ_CUTOFFS.length];
            int n = gains.length;
            float[] xs = new float[n], ys = new float[n];
            float peakDb = 0f;
            for (int i = 0; i < n; i++) {
                if (gains[i] > peakDb) peakDb = gains[i];
                xs[i] = padL + gw * (i / (float) (n - 1));
                float db = Ui.clamp(gains[i], minDb, maxDb);
                ys[i] = padT + gh * ((maxDb - db) / (maxDb - minDb));
            }

            curve.reset();
            area.reset();
            curve.moveTo(xs[0], ys[0]);
            area.moveTo(xs[0], zeroY);
            area.lineTo(xs[0], ys[0]);
            for (int i = 0; i < n - 1; i++) {
                float mx = (xs[i] + xs[i + 1]) / 2f;
                curve.cubicTo(mx, ys[i], mx, ys[i + 1], xs[i + 1], ys[i + 1]);
                area.cubicTo(mx, ys[i], mx, ys[i + 1], xs[i + 1], ys[i + 1]);
            }
            area.lineTo(xs[n - 1], zeroY);
            area.close();

            int col = bypassing ? 0xFFFF9F0A : (on ? Ui.ACCENT : Ui.text3());
            fillP.setShader(new LinearGradient(0, padT, 0, padT + gh,
                    Ui.alpha(col, on ? 0.34f : 0.12f), 0x00000000, Shader.TileMode.CLAMP));
            c.drawPath(area, fillP);
            fillP.setShader(null);
            lineP.setColor(on || bypassing ? col : Ui.alpha(col, 0.55f));
            lineP.setPathEffect(bypassing ? new android.graphics.DashPathEffect(new float[]{Ui.dpf(6), Ui.dpf(4)}, 0) : null);
            c.drawPath(curve, lineP);
            lineP.setPathEffect(null);

            // Band anchor dots so every slider drag gives crisp visual feedback
            if (on) {
                dotP.setColor(col);
                for (int i = 0; i < n; i++) c.drawCircle(xs[i], ys[i], Ui.dpf(2.4f), dotP);
            }

            // Live readout badge at top-right
            badgeP.setColor(col);
            String readout = bypassing ? "BYPASS · 0.0 dB"
                    : (on ? String.format(Locale.US, "PEAK +%.1f dB · %s", peakDb, harmonics(getContext()).toUpperCase(Locale.US))
                    : "OFF · 0.0 dB");
            c.drawText(readout, w - padR, Ui.dpf(15), badgeP);
        }
    }

    // ================================================================ Official Chipset Artwork View
    private static final SparseArray<Bitmap> CHIP_BMP_CACHE = new SparseArray<>();

    private static Bitmap chipBitmap(Context c, int res) {
        if (res == 0 || c == null) return null;
        Bitmap b = CHIP_BMP_CACHE.get(res);
        if (b != null) return b;
        try {
            b = BitmapFactory.decodeResource(c.getResources(), res);
            if (b != null) CHIP_BMP_CACHE.put(res, b);
        } catch (Throwable ignored) {}
        return b;
    }

    static final class ChipArtView extends View {
        private final ChipSpec spec;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint borderP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path clipPath = new Path();

        ChipArtView(Context c, ChipSpec spec, String fallbackLabel) {
            super(c);
            this.spec = spec;
            borderP.setStyle(Paint.Style.STROKE);
            tp.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float s = Math.min(w, h);
            float ox = (w - s) / 2f, oy = (h - s) / 2f;
            float rad = s * 0.18f;
            r.set(ox, oy, ox + s, oy + s);

            Bitmap bmp = (spec != null && spec.drawableRes != 0) ? chipBitmap(getContext(), spec.drawableRes) : null;
            if (bmp != null) {
                p.setStyle(Paint.Style.FILL);
                p.setShader(null);
                p.setColor(0xFFFFFFFF);
                p.setAlpha(255);
                clipPath.reset();
                clipPath.addRoundRect(r, rad, rad, Path.Direction.CW);
                int sc = c.save();
                c.clipPath(clipPath);
                c.drawBitmap(bmp, null, r, p);
                c.restoreToCount(sc);
                borderP.setStrokeWidth(Math.max(1f, Ui.dpf(0.6f)));
                borderP.setColor(Ui.dark ? 0x26FFFFFF : 0x1A000000);
                c.drawRoundRect(r, rad, rad, borderP);
                return;
            }
            p.setStyle(Paint.Style.FILL);
            p.setAlpha(255);
            int c1 = 0xFF1F2229, c2 = 0xFF2E323D, accent = 0xFF94A3B8;
            if (spec != null) {
                switch (spec.vendor) {
                    case VENDOR_QUALCOMM: c1 = 0xFF2C0E17; c2 = 0xFF7A1228; accent = 0xFFFF6B81; break;
                    case VENDOR_MEDIATEK: c1 = 0xFF14151B; c2 = 0xFF2B2418; accent = 0xFFF5C469; break;
                    case VENDOR_SAMSUNG:  c1 = 0xFF0C1018; c2 = 0xFF142A52; accent = 0xFF38BDF8; break;
                    case VENDOR_GOOGLE:   c1 = 0xFF161B26; c2 = 0xFF26334A; accent = 0xFF8AB4F8; break;
                }
            }
            p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, c1, c2, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rad, rad, p);
            p.setShader(null);
            borderP.setStrokeWidth(Math.max(1f, Ui.dpf(0.8f)));
            borderP.setColor(Ui.alpha(accent, 0.45f));
            c.drawRoundRect(r, rad, rad, borderP);
            tp.setTypeface(Ui.semibold);
            tp.setTextSize(s * 0.125f);
            tp.setColor(accent);
            c.drawText(spec != null ? spec.badgeTop : "NPU", ox + s / 2f, oy + s * 0.40f, tp);
            tp.setTypeface(Ui.bold);
            tp.setTextSize(s * 0.19f);
            tp.setColor(0xFFFFFFFF);
            c.drawText(spec != null ? spec.badgeMain : "SoC", ox + s / 2f, oy + s * 0.67f, tp);
        }
    }
}
