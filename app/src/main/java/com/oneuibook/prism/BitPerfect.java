package com.oneuibook.prism;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioMixerAttributes;
import android.media.AudioProfile;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;

import java.util.List;

/**
 * Bit-perfect & Direct Hi-Res USB DAC playback for on-device songs (Android 14+, USB DAC / USB headset such as
 * FiiO KA13 / KA17 / Moondrop / Apple USB-C): selects the best UAC2 USB audio endpoint, prefers true
 * MIXER_BEHAVIOR_BIT_PERFECT (exact rate -> integer multiple -> nearest supported rate), and gracefully falls back
 * to Direct Hi-Res USB mixer routing when a vendor HAL omits the BIT_PERFECT flag on a UAC2 DAC.
 */
final class BitPerfect {
    private BitPerfect() {}

    static final String PREF = "bit_perfect";
    static final String PREF_OUTPUT_MODE = "bp_output_mode"; // auto | direct | fixed | float
    static final String PREF_SAMPLE_RATE = "bp_sample_rate"; // 0 (auto) | 44100 | 48000 | 96000 | 192000 | 384000
    static final String PREF_BUFFER_MS = "bp_buffer_ms";     // 20 | 45 | 90
    static final String PREF_DITHER = "bp_dither";           // off | tpdf | ns
    static final String PREF_FILTER = "bp_filter";           // sharp | slow | min
    static final String PREF_REPLAYGAIN = "bp_replaygain";
    static final String PREF_CROSSFEED = "bp_crossfeed";
    static final String PREF_MONO = "bp_mono";
    static final String PREF_BALANCE = "bp_balance";         // -100..100 (0 = center)

    static final String[] OUTPUT_MODES = {"auto", "direct", "fixed", "float"};
    static final int[] OUTPUT_MODE_LABELS = {R.string.bpa_mode_auto, R.string.bpa_mode_direct, R.string.bpa_mode_fixed, R.string.bpa_mode_float};
    static final int[] SAMPLE_RATES = {0, 44100, 48000, 96000, 192000, 384000};
    static final int[] BUFFER_MS_OPTS = {20, 45, 90};
    static final int[] BUFFER_LABELS = {R.string.bpa_buf_low, R.string.bpa_buf_std, R.string.bpa_buf_safe};
    static final String[] DITHERS = {"off", "tpdf", "ns"};
    static final int[] DITHER_LABELS = {R.string.bpa_dither_off, R.string.bpa_dither_tpdf, R.string.bpa_dither_ns};
    static final String[] FILTERS = {"sharp", "slow", "min"};
    static final int[] FILTER_LABELS = {R.string.bpa_filter_sharp, R.string.bpa_filter_slow, R.string.bpa_filter_min};

    /** Last outcome, for the settings row. */
    static String state = "";
    /** Active USB stream format when running on a USB DAC (0 when inactive). */
    static int activeRate, activeBits;
    /** Source track format (0 when unknown). */
    static int sourceRate, sourceBits;
    /** True when Android accepted MIXER_BEHAVIOR_BIT_PERFECT; false when running in Direct Hi-Res USB fallback. */
    static boolean activeBitPerfect;
    /** The USB output device selected for the active playback session (passed to MediaPlayer.setPreferredDevice). */
    static AudioDeviceInfo activeDevice;

    static boolean supportedOs() { return Build.VERSION.SDK_INT >= 34; }

    static boolean enabled(Context c) { return Settings.pref(c, PREF, false); }
    static String outputMode(Context c) { return MainActivity.prefs(c).getString(PREF_OUTPUT_MODE, "auto"); }
    static int targetSampleRate(Context c) { return MainActivity.prefs(c).getInt(PREF_SAMPLE_RATE, 0); }
    static int bufferMs(Context c) { return MainActivity.prefs(c).getInt(PREF_BUFFER_MS, 45); }
    static String dither(Context c) { return MainActivity.prefs(c).getString(PREF_DITHER, "tpdf"); }
    static String filter(Context c) { return MainActivity.prefs(c).getString(PREF_FILTER, "sharp"); }
    static boolean replayGain(Context c) { return MainActivity.prefs(c).getBoolean(PREF_REPLAYGAIN, false); }
    static boolean crossfeed(Context c) { return MainActivity.prefs(c).getBoolean(PREF_CROSSFEED, false); }
    static boolean mono(Context c) { return MainActivity.prefs(c).getBoolean(PREF_MONO, false); }
    static int balance(Context c) { return Math.max(-100, Math.min(100, MainActivity.prefs(c).getInt(PREF_BALANCE, 0))); }

    /** Computes [left, right] MediaPlayer volume gains from channel balance (-100..+100) + ReplayGain headroom. */
    static float[] channelGains(Context c) {
        if (c == null) return new float[]{1f, 1f};
        float bal = balance(c) / 100f;
        float rg = replayGain(c) ? 0.86f : 1.0f;
        float l = bal > 0f ? (1f - bal) : 1f;
        float r = bal < 0f ? (1f + bal) : 1f;
        return new float[]{l * rg, r * rg};
    }

    /** Human-readable hardware capability summary of the connected USB DAC (for Bit-Perfect Assist diagnostics). */
    static String dacProfileSummary(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo d = usbOut(am);
        if (d == null) return c.getString(R.string.bp_no_dac);
        int maxR = maxSampleRate(am, d);
        int maxB = maxBits(am, d);
        int bpN = bitPerfectModes(am, d);
        StringBuilder sb = new StringBuilder(name(d));
        if (maxR > 0) sb.append(" · 최대 ").append(khz(maxR));
        if (maxB > 0) sb.append(" / ").append(maxB).append("-bit");
        if (bpN > 0) sb.append(" · BP ").append(bpN).append("모드");
        else sb.append(" · UAC2 Direct");
        return sb.toString();
    }

    static String formatRateLabel(Context c, int rate) {
        return rate <= 0 ? c.getString(R.string.bpa_rate_auto) : khz(rate);
    }

    /** True when bit-perfect or direct USB routing is currently active (effects like EQ/NPU must be bypassed). */
    static boolean isActive() { return activeRate > 0 && activeDevice != null; }

    static AudioAttributes media() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    }

    /** Picks the best connected USB DAC output (prefers BIT_PERFECT-capable & higher-res UAC2 endpoints). */
    static AudioDeviceInfo usbOut(AudioManager am) {
        if (am == null) return null;
        AudioDeviceInfo best = null;
        int bestScore = -1;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t != AudioDeviceInfo.TYPE_USB_DEVICE && t != AudioDeviceInfo.TYPE_USB_HEADSET) continue;
            int score = 10;
            if (t == AudioDeviceInfo.TYPE_USB_DEVICE) score += 20;
            int bp = bitPerfectModes(am, d);
            if (bp > 0) score += 1000 + Math.min(200, bp * 10);
            int maxRate = maxSampleRate(am, d);
            if (maxRate >= 96000) score += 100;
            if (maxRate >= 192000) score += 50;
            if (maxBits(am, d) >= 24) score += 40;
            if (score > bestScore) { bestScore = score; best = d; }
        }
        return best;
    }

    /** Short status for settings: OS too old / no DAC / DAC bit-perfect or USB direct ready. */
    static String status(Context c) {
        if (!supportedOs()) return c.getString(R.string.bp_needs_14);
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo d = usbOut(am);
        if (d == null) return c.getString(R.string.bp_no_dac);
        if (!state.isEmpty()) return state;
        if (bitPerfectModes(am, d) > 0) return c.getString(R.string.bp_ready, name(d));
        if (usbDirectCapable(am, d)) return c.getString(R.string.bp_ready_direct, name(d));
        return c.getString(R.string.bp_unsupported, name(d));
    }

    private static String name(AudioDeviceInfo d) {
        CharSequence n = d.getProductName();
        return n == null || n.length() == 0 ? "USB DAC" : n.toString();
    }

    private static int bitPerfectModes(AudioManager am, AudioDeviceInfo d) {
        if (!supportedOs() || am == null || d == null) return 0;
        int n = 0;
        try {
            List<AudioMixerAttributes> list = am.getSupportedMixerAttributes(d);
            if (list != null) {
                for (AudioMixerAttributes a : list)
                    if (a.getMixerBehavior() == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) n++;
            }
        } catch (Throwable ignored) {}
        return n;
    }

    /** True if this USB DAC exposes PCM profiles or mixer attributes usable for Direct Hi-Res USB output (e.g. FiiO KA13). */
    private static boolean usbDirectCapable(AudioManager am, AudioDeviceInfo d) {
        if (am == null || d == null) return false;
        try {
            List<AudioMixerAttributes> list = am.getSupportedMixerAttributes(d);
            if (list != null && !list.isEmpty()) return true;
        } catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                List<AudioProfile> profs = d.getAudioProfiles();
                if (profs != null && !profs.isEmpty()) return true;
            } catch (Throwable ignored) {}
        }
        int[] rates = d.getSampleRates();
        if (rates != null && rates.length > 0) return true;
        int[] encs = d.getEncodings();
        return encs != null && encs.length > 0;
    }

    static int maxSampleRate(AudioManager am, AudioDeviceInfo d) {
        int best = 0;
        if (supportedOs() && am != null) {
            try {
                List<AudioMixerAttributes> list = am.getSupportedMixerAttributes(d);
                if (list != null) {
                    for (AudioMixerAttributes a : list) {
                        int r = a.getFormat().getSampleRate();
                        if (r > best && r <= 768000) best = r;
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                List<AudioProfile> profs = d.getAudioProfiles();
                if (profs != null) {
                    for (AudioProfile ap : profs) {
                        int[] rs = ap.getSampleRates();
                        if (rs != null) for (int r : rs) if (r > best && r <= 768000) best = r;
                    }
                }
            } catch (Throwable ignored) {}
        }
        int[] rs = d.getSampleRates();
        if (rs != null) for (int r : rs) if (r > best && r <= 768000) best = r;
        return best;
    }

    static int maxBits(AudioManager am, AudioDeviceInfo d) {
        int best = 0;
        if (supportedOs() && am != null) {
            try {
                List<AudioMixerAttributes> list = am.getSupportedMixerAttributes(d);
                if (list != null) {
                    for (AudioMixerAttributes a : list) {
                        int b = bitsOf(a.getFormat().getEncoding());
                        if (b > best) best = b;
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                List<AudioProfile> profs = d.getAudioProfiles();
                if (profs != null) {
                    for (AudioProfile ap : profs) {
                        int b = bitsOf(ap.getFormat());
                        if (b > best) best = b;
                    }
                }
            } catch (Throwable ignored) {}
        }
        int[] es = d.getEncodings();
        if (es != null) for (int e : es) { int b = bitsOf(e); if (b > best) best = b; }
        return best;
    }

    /**
     * Scores a candidate (behavior, sampleRate, bits, channels) for a track of (wantRate, wantBits).
     * Priority:
     *   1) MIXER_BEHAVIOR_BIT_PERFECT exact sample rate
     *   2) MIXER_BEHAVIOR_BIT_PERFECT integer-multiple sample rate (e.g. 44.1 -> 88.2/176.4, 48 -> 96/192/384)
     *   3) MIXER_BEHAVIOR_BIT_PERFECT nearest rate >= wantRate (or highest available)
     *   4) MIXER_BEHAVIOR_DEFAULT exact sample rate (Direct Hi-Res USB fallback for DACs like FiiO KA13)
     *   5) MIXER_BEHAVIOR_DEFAULT integer-multiple / nearest high-res rate
     */
    static int scoreCandidate(boolean bitPerfect, int candRate, int candBits, int candChannels, int wantRate, int wantBits) {
        if (candRate <= 0) return -1;
        int score = bitPerfect ? 100_000 : 10_000;
        if (wantRate > 0) {
            if (candRate == wantRate) {
                score += 40_000;
            } else if (candRate > wantRate && candRate % wantRate == 0) {
                // Exact integer multiple (zero fractional resampling distortion): prefer smallest clean multiple (2x over 4x)
                int mult = candRate / wantRate;
                score += 28_000 - Math.min(8_000, mult * 500);
            } else if (candRate > wantRate) {
                score += 15_000 - Math.min(10_000, (candRate - wantRate) / 50);
            } else {
                score += 4_000 - Math.min(3_500, (wantRate - candRate) / 50);
            }
        } else {
            score += Math.min(20_000, candRate / 20);
        }
        if (candChannels == 2) score += 2_000;
        if (candBits == wantBits) score += 800;
        else if (candBits > wantBits) score += 600 + candBits; // lossless zero-padding (e.g. 16-bit into 24/32-bit DAC slot)
        else score += candBits;
        return score;
    }

    /** Picks the best AudioMixerAttributes from a DAC's supported list for the given track format. */
    static AudioMixerAttributes pickBestMixer(List<AudioMixerAttributes> all, int wantRate, int wantBits) {
        if (all == null || all.isEmpty()) return null;
        AudioMixerAttributes pick = null;
        int best = -1;
        for (AudioMixerAttributes a : all) {
            if (a == null || a.getFormat() == null) continue;
            AudioFormat f = a.getFormat();
            boolean bp = a.getMixerBehavior() == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT;
            int ch = f.getChannelCount();
            int b = bitsOf(f.getEncoding());
            int sc = scoreCandidate(bp, f.getSampleRate(), b, ch, wantRate, wantBits);
            if (sc > best) { best = sc; pick = a; }
        }
        return pick;
    }

    /** Builds a fallback AudioMixerAttributes from the DAC's AudioProfiles when getSupportedMixerAttributes is empty/limited. */
    private static AudioMixerAttributes buildFromProfiles(AudioDeviceInfo d, int wantRate, int wantBits, boolean bitPerfect) {
        if (!supportedOs() || d == null) return null;
        int bestRate = 0, bestEnc = 0, bestScore = -1;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                List<AudioProfile> profs = d.getAudioProfiles();
                if (profs != null) {
                    for (AudioProfile ap : profs) {
                        int enc = ap.getFormat();
                        if (!isPcmEncoding(enc)) continue;
                        int b = bitsOf(enc);
                        int[] rates = ap.getSampleRates();
                        if (rates == null || rates.length == 0) rates = new int[]{wantRate > 0 ? wantRate : 48000};
                        for (int r : rates) {
                            int sc = scoreCandidate(bitPerfect, r, b, 2, wantRate, wantBits);
                            if (sc > bestScore) { bestScore = sc; bestRate = r; bestEnc = enc; }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (bestRate <= 0) {
            int[] rates = d.getSampleRates();
            int[] encs = d.getEncodings();
            if (rates != null && rates.length > 0) {
                int enc = AudioFormat.ENCODING_PCM_24BIT_PACKED;
                if (encs != null) {
                    int maxB = 0;
                    for (int e : encs) {
                        if (!isPcmEncoding(e)) continue;
                        int b = bitsOf(e);
                        if (b >= wantBits && (maxB < wantBits || b < maxB)) { maxB = b; enc = e; }
                        else if (maxB < wantBits && b > maxB) { maxB = b; enc = e; }
                    }
                }
                for (int r : rates) {
                    int sc = scoreCandidate(bitPerfect, r, bitsOf(enc), 2, wantRate, wantBits);
                    if (sc > bestScore) { bestScore = sc; bestRate = r; bestEnc = enc; }
                }
            }
        }
        if (bestRate <= 0 || bestEnc == 0) return null;
        try {
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(bestEnc)
                    .setSampleRate(bestRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build();
            return new AudioMixerAttributes.Builder(fmt)
                    .setMixerBehavior(bitPerfect ? AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT : AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT)
                    .build();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isPcmEncoding(int enc) {
        return enc == AudioFormat.ENCODING_PCM_16BIT
                || enc == AudioFormat.ENCODING_PCM_24BIT_PACKED
                || enc == AudioFormat.ENCODING_PCM_32BIT
                || enc == AudioFormat.ENCODING_PCM_FLOAT;
    }

    /**
     * Called before a local song starts. Picks the best bit-perfect mixer format (exact -> integer multiple -> nearest),
     * or falls back cleanly to Direct Hi-Res USB mixer on DACs like FiiO KA13 whose OEM HAL omits the BIT_PERFECT flag.
     */
    static void prepare(Context c, Uri song) {
        activeRate = 0;
        activeBits = 0;
        activeBitPerfect = false;
        activeDevice = null;
        if (!supportedOs()) return;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo d = usbOut(am);
        if (d == null) { state = ""; return; }
        if (!enabled(c)) { clear(c); return; }
        int rate = 0, bits = 16;
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(c, song, null);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("audio/")) continue;
                if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if (f.containsKey("bits-per-sample")) bits = f.getInteger("bits-per-sample");
                else if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) bits = bitsOf(f.getInteger(MediaFormat.KEY_PCM_ENCODING));
                break;
            }
        } catch (Exception ignored) {
        } finally { ex.release(); }
        sourceRate = rate;
        sourceBits = bits;
        int lockRate = targetSampleRate(c);
        if (lockRate > 0) rate = lockRate;
        String mode = outputMode(c);
        boolean preferBp = "auto".equals(mode) || "fixed".equals(mode);
        if ("float".equals(mode)) bits = 32;

        List<AudioMixerAttributes> all = null;
        try { all = am.getSupportedMixerAttributes(d); } catch (Throwable ignored) {}
        AudioMixerAttributes pick = preferBp ? pickBestMixer(all, rate, bits) : buildFromProfiles(d, rate, bits, false);

        // Try the best supported mixer attribute first
        boolean ok = false;
        if (pick != null) {
            try { ok = am.setPreferredMixerAttributes(media(), d, pick); } catch (Throwable ignored) {}
        }
        // If HAL didn't list a bit-perfect attribute (e.g., FiiO KA13 UAC2 on Samsung HAL), try building from AudioProfiles
        if (!ok || (pick != null && pick.getMixerBehavior() != AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)) {
            AudioMixerAttributes synthBp = buildFromProfiles(d, rate, bits, true);
            if (synthBp != null) {
                try {
                    if (am.setPreferredMixerAttributes(media(), d, synthBp)) {
                        pick = synthBp;
                        ok = true;
                    }
                } catch (Throwable ignored) {}
            }
        }
        // Final fallback: explicit Direct Hi-Res USB mixer matching the track's sample rate & bit depth
        if (!ok) {
            AudioMixerAttributes synthDirect = buildFromProfiles(d, rate, bits, false);
            if (synthDirect != null) {
                try {
                    if (am.setPreferredMixerAttributes(media(), d, synthDirect)) {
                        pick = synthDirect;
                        ok = true;
                    }
                } catch (Throwable ignored) {}
            }
        }

        if (ok && pick != null && pick.getFormat() != null) {
            AudioFormat f = pick.getFormat();
            activeRate = f.getSampleRate();
            activeBits = bitsOf(f.getEncoding());
            activeBitPerfect = pick.getMixerBehavior() == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT;
            activeDevice = d;
            state = c.getString(activeBitPerfect ? R.string.bp_active : R.string.bp_active_direct,
                    khz(activeRate), activeBits + "bit");
            return;
        }
        // Even if setPreferredMixerAttributes was rejected by OEM policy, still route directly to the USB DAC without EQ/NPU
        if (usbDirectCapable(am, d)) {
            int effRate = rate > 0 ? rate : Math.max(48000, maxSampleRate(am, d));
            int effBits = Math.max(bits, Math.min(24, maxBits(am, d)));
            activeRate = effRate;
            activeBits = effBits > 0 ? effBits : 24;
            activeBitPerfect = false;
            activeDevice = d;
            state = c.getString(R.string.bp_active_direct, khz(activeRate), activeBits + "bit");
            return;
        }
        try { am.clearPreferredMixerAttributes(media(), d); } catch (Throwable ignored) {}
        state = c.getString(R.string.bp_no_match, rate > 0 ? khz(rate) : "?");
    }

    static void clear(Context c) {
        state = "";
        activeRate = 0;
        activeBits = 0;
        activeBitPerfect = false;
        activeDevice = null;
        if (!supportedOs()) return;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                try { am.clearPreferredMixerAttributes(media(), d); } catch (Exception ignored) {}
            }
        }
    }

    private static String khz(int hz) {
        return (hz % 1000 == 0 ? String.valueOf(hz / 1000) : String.format(java.util.Locale.ROOT, "%.1f", hz / 1000f)) + " kHz";
    }

    static int bitsOf(int enc) {
        switch (enc) {
            case AudioFormat.ENCODING_PCM_8BIT: return 8;
            case AudioFormat.ENCODING_PCM_24BIT_PACKED: return 24;
            case AudioFormat.ENCODING_PCM_32BIT:
            case AudioFormat.ENCODING_PCM_FLOAT: return 32;
            default: return 16;
        }
    }
}
