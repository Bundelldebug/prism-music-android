package com.oneuibook.prism;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioMixerAttributes;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;

import java.util.List;

/**
 * Bit-perfect playback for on-device songs (Android 14+, USB DAC / USB headset): asks Android to run the USB output
 * in MIXER_BEHAVIOR_BIT_PERFECT at the song's own sample rate and bit depth, so the audio reaches the DAC without
 * resampling, mixing or volume processing. Spotify (in-app player = DRM web audio) can't be bit-perfect.
 */
final class BitPerfect {
    private BitPerfect() {}

    static final String PREF = "bit_perfect";
    /** Last outcome, for the settings row. */
    static String state = "";
    /** Active bit-perfect stream format when running on a USB DAC (0 when inactive). */
    static int activeRate, activeBits;

    static boolean supportedOs() { return Build.VERSION.SDK_INT >= 34; }

    static boolean enabled(Context c) { return Settings.pref(c, PREF, false); }

    static AudioAttributes media() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    }

    /** The connected USB output, or null. */
    static AudioDeviceInfo usbOut(AudioManager am) {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET) return d;
        }
        return null;
    }

    /** Short status for settings: OS too old / no DAC / DAC without bit-perfect / ready. */
    static String status(Context c) {
        if (!supportedOs()) return c.getString(R.string.bp_needs_14);
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo d = usbOut(am);
        if (d == null) return c.getString(R.string.bp_no_dac);
        if (bitPerfectModes(am, d) == 0) return c.getString(R.string.bp_unsupported, name(d));
        return state.isEmpty() ? c.getString(R.string.bp_ready, name(d)) : state;
    }

    private static String name(AudioDeviceInfo d) {
        CharSequence n = d.getProductName();
        return n == null || n.length() == 0 ? "USB DAC" : n.toString();
    }

    private static int bitPerfectModes(AudioManager am, AudioDeviceInfo d) {
        if (!supportedOs()) return 0;
        int n = 0;
        for (AudioMixerAttributes a : am.getSupportedMixerAttributes(d))
            if (a.getMixerBehavior() == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) n++;
        return n;
    }

    /**
     * Called before a local song starts. Picks the bit-perfect mixer format that matches the file (same sample rate,
     * closest bit depth, stereo); clears the preference when off, unsupported or nothing matches.
     */
    static void prepare(Context c, Uri song) {
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
        AudioMixerAttributes pick = null;
        int pickScore = -1;
        List<AudioMixerAttributes> all = am.getSupportedMixerAttributes(d);
        for (AudioMixerAttributes a : all) {
            if (a.getMixerBehavior() != AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) continue;
            AudioFormat f = a.getFormat();
            if (rate > 0 && f.getSampleRate() != rate) continue;
            int b = bitsOf(f.getEncoding());
            int score = (f.getChannelCount() == 2 ? 100 : 0) + (b == bits ? 50 : (b > bits ? 20 : 0)) + b;
            if (score > pickScore) { pickScore = score; pick = a; }
        }
        if (pick == null) {
            am.clearPreferredMixerAttributes(media(), d);
            state = c.getString(R.string.bp_no_match, rate > 0 ? khz(rate) : "?");
            return;
        }
        boolean ok = am.setPreferredMixerAttributes(media(), d, pick);
        AudioFormat f = pick.getFormat();
        activeRate = ok ? f.getSampleRate() : 0;
        activeBits = ok ? bitsOf(f.getEncoding()) : 0;
        state = ok ? c.getString(R.string.bp_active, khz(f.getSampleRate()), bitsOf(f.getEncoding()) + "bit")
                : c.getString(R.string.bp_failed);
    }

    static void clear(Context c) {
        state = "";
        activeRate = 0;
        activeBits = 0;
        if (!supportedOs()) return;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo d = usbOut(am);
        if (d != null) try { am.clearPreferredMixerAttributes(media(), d); } catch (Exception ignored) {}
    }

    private static String khz(int hz) {
        return (hz % 1000 == 0 ? String.valueOf(hz / 1000) : String.format(java.util.Locale.ROOT, "%.1f", hz / 1000f)) + " kHz";
    }

    private static int bitsOf(int enc) {
        switch (enc) {
            case AudioFormat.ENCODING_PCM_8BIT: return 8;
            case AudioFormat.ENCODING_PCM_24BIT_PACKED: return 24;
            case AudioFormat.ENCODING_PCM_32BIT:
            case AudioFormat.ENCODING_PCM_FLOAT: return 32;
            default: return 16;
        }
    }
}
