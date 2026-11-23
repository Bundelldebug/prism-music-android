package com.oneuibook.prism;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioProfile;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/**
 * Inspects active Bluetooth A2DP/LE Audio (including Samsung SSC / SSC-UHQ) or direct USB DAC output
 * using Android 12+ public AudioProfile API, BluetoothA2dp proxy, BluetoothDevice names, and HAL/vendor
 * detection. Evaluates the 4-tier Hi-Res playback visual tier:
 *   - TIER_NONE   (0): 0 conditions
 *   - TIER_GOLD   (1): 1 condition  (Bit-Perfect Assist OR bit > 16 OR kHz >= 96) -> Gold glow
 *   - TIER_BLUE   (2): 2 conditions -> Luminous Blue glow
 *   - TIER_MASTER (3): all 3 conditions OR active USB Bit-Perfect playback -> Rainbow glow + luxury backdrop
 */
final class AudioInfo {
    private AudioInfo() {}

    static final int TIER_NONE = 0;
    static final int TIER_GOLD = 1;
    static final int TIER_BLUE = 2;
    static final int TIER_MASTER = 3;

    static final class OutputSpec {
        String deviceName = "";
        String codec = "";
        int bits = 16;
        float khz = 48f;
        boolean external; // true for Bluetooth or USB DAC
        boolean isBt;
        boolean isUsbDac;
        boolean isWired;
        boolean isBitPerfect;

        String khzText() {
            if (khz <= 0) return "";
            if (Math.abs(khz - Math.round(khz)) < 0.05f) return Math.round(khz) + "kHz";
            return String.format(Locale.US, "%.1fkHz", khz);
        }

        /** Compact format string, e.g. "SSC-UHQ · 24bit / 96kHz" or "USB Bit-Perfect · 24bit / 192kHz". */
        String formatLine() {
            if (!external && bits <= 16 && khz < 96f) return "";
            StringBuilder sb = new StringBuilder();
            if (codec != null && !codec.isEmpty()) sb.append(codec);
            if (bits > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(bits).append("bit");
            }
            if (khz > 0) {
                if (sb.length() > 0) sb.append(bits > 0 ? " / " : " · ");
                sb.append(khzText());
            }
            return sb.toString();
        }
    }

    /** Test seam for UI verification only (null in production). */
    static OutputSpec testSpec;

    private static BluetoothA2dp a2dpProxy;
    private static boolean proxyRequested;

    static void init(Context c) { init(c, false); }

    static void init(Context c, boolean force) {
        if ((proxyRequested && !force) || c == null) return;
        proxyRequested = true;
        try {
            BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return;
            adapter.getProfileProxy(c.getApplicationContext(), new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.A2DP && proxy instanceof BluetoothA2dp) {
                        a2dpProxy = (BluetoothA2dp) proxy;
                        NpuAudio.syncPerDevice(c.getApplicationContext());
                        NpuAudio.apply(c.getApplicationContext(), true);
                        Player.notifyAll0();
                    }
                }
                @Override public void onServiceDisconnected(int profile) {
                    if (profile == BluetoothProfile.A2DP) a2dpProxy = null;
                }
            }, BluetoothProfile.A2DP);
        } catch (Throwable ignored) {}
    }

    static int evaluateHiResTier(boolean assistOn, int bits, float khz, boolean isBitPerfect) {
        if (isBitPerfect) return TIER_MASTER;
        int count = (assistOn ? 1 : 0) + (bits > 16 ? 1 : 0) + (khz >= 96f ? 1 : 0);
        if (count >= 3) return TIER_MASTER;
        if (count == 2) return TIER_BLUE;
        if (count == 1) return TIER_GOLD;
        return TIER_NONE;
    }

    static int hiResTier(Context c, OutputSpec spec) {
        if (spec == null) spec = current(c);
        boolean assist = NpuAudio.enabled(c);
        boolean bp = spec.isBitPerfect || BitPerfect.activeRate > 0;
        return evaluateHiResTier(assist, spec.bits, spec.khz, bp);
    }

    static int deviceIcon(OutputSpec spec) {
        if (spec == null) return Icons.DEVICE;
        return deviceIconForName(spec.deviceName, spec.isBt, spec.isUsbDac, spec.isWired);
    }

    static int deviceIconForName(String name, boolean isBt, boolean isUsbDac, boolean isWired) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        if (n.contains("buds on") || n.contains("budson") || n.contains("buds able")
                || n.contains("budsable") || n.contains("버즈 온") || n.contains("버즈온")
                || n.contains("버즈 에이블") || n.contains("버즈에이블")) {
            return Icons.BUDS_ON;
        }
        if (n.contains("buds4") || n.contains("buds 4") || n.contains("버즈4") || n.contains("버즈 4")
                || n.contains("sm-r640") || n.contains("sm-r540")) {
            return Icons.BUDS4;
        }
        if (n.contains("galaxy buds") || n.contains("buds3") || n.contains("buds 3")
                || n.contains("buds2") || n.contains("buds 2") || n.contains("buds pro")
                || n.contains("buds fe") || n.contains("buds live") || n.contains("buds+")
                || n.contains("sm-r") || n.contains("갤럭시 버즈") || n.contains("버즈")) {
            return Icons.BUDS;
        }
        if (n.contains("headphone") || n.contains("wh-1000") || n.contains("wh-")
                || n.contains("quietcomfort") || n.contains("airpods max")
                || (n.contains("momentum") && !n.contains("tw") && !n.contains("true wireless"))
                || n.contains("hd 6") || n.contains("hd 8") || n.contains("hd 5")
                || n.contains("dt 7") || n.contains("dt 9") || n.contains("dt 19")
                || n.contains("ath-") || n.contains("bose nc") || n.contains("bose 700")
                || n.contains("marshall") || n.contains("beats studio") || n.contains("beats solo")
                || n.contains("space one") || n.contains("space q") || n.contains("q30") || n.contains("q45")
                || n.contains("xm4") || n.contains("xm5") || n.contains("akg") || n.contains("meze")
                || n.contains("focal") || n.contains("hifiman") || n.contains("sundara")
                || n.contains("헤드폰") || n.contains("헤드셋")) {
            return Icons.HEADPHONES;
        }
        if (n.contains("airpods") || n.contains("earbuds") || n.contains("wf-")
                || n.contains("freebuds") || n.contains("pixel buds") || n.contains("nothing ear")
                || n.contains("ear (") || n.contains("beats fit") || n.contains("studio buds")
                || n.contains("jabra") || n.contains("linkbuds") || n.contains("az80") || n.contains("az60")
                || n.contains("tour pro") || n.contains("liberty") || n.contains("pods")
                || n.contains("buds") || n.contains("earphone") || n.contains("iem") || n.contains("tws")
                || n.contains("이어폰") || n.contains("이어버드")) {
            return Icons.EARBUDS;
        }
        if (isUsbDac) return Icons.DAC;
        if (isWired) return Icons.HEADPHONES;
        if (isBt) return Icons.EARBUDS;
        return Icons.DEVICE;
    }

    static OutputSpec current(Context c) {
        if (testSpec != null) return testSpec;
        OutputSpec s = new OutputSpec();
        if (c == null) return s;
        init(c);
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return s;
            AudioDeviceInfo usb = null, bt = null, wired = null;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                    if (usb == null) usb = d;
                } else if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    if (bt == null) bt = d;
                } else if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) {
                    if (wired == null) wired = d;
                }
            }
            if (usb != null) {
                fillUsb(s, usb);
                return s;
            }
            if (bt != null) {
                fillBluetooth(c, s, bt, am);
                return s;
            }
            if (wired != null) {
                s.isWired = true;
                CharSequence pn = wired.getProductName();
                s.deviceName = (pn == null || pn.length() == 0) ? "Wired" : pn.toString();
            }
        } catch (Throwable ignored) {}
        return s;
    }

    private static void fillUsb(OutputSpec s, AudioDeviceInfo d) {
        s.external = true;
        s.isUsbDac = true;
        CharSequence pn = d.getProductName();
        s.deviceName = (pn == null || pn.length() == 0) ? "USB DAC" : pn.toString();
        if (BitPerfect.activeRate > 0 && BitPerfect.activeBits > 0) {
            s.isBitPerfect = true;
            s.codec = "USB Bit-Perfect";
            s.bits = BitPerfect.activeBits;
            s.khz = BitPerfect.activeRate / 1000f;
            return;
        }
        s.codec = "USB PCM";
        s.bits = maxBitsFromEncodings(d.getEncodings(), 24);
        s.khz = maxKhzFromRates(d.getSampleRates(), 48f);
    }

    private static void fillBluetooth(Context c, OutputSpec s, AudioDeviceInfo d, AudioManager am) {
        s.external = true;
        s.isBt = true;
        CharSequence pn = d.getProductName();
        String productName = (pn == null || pn.length() == 0) ? "" : pn.toString().trim();
        s.deviceName = productName.isEmpty() ? "Bluetooth" : productName;
        boolean ble = d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET;
        s.codec = ble ? "LC3" : "";
        s.bits = maxBitsFromEncodings(d.getEncodings(), 16);
        s.khz = maxKhzFromRates(d.getSampleRates(), 48f);

        // 1) Always prefer the real BluetoothDevice name/alias from BluetoothA2dp / BluetoothManager
        BluetoothDevice activeDev = findConnectedA2dpDevice(c);
        String btRawName = "";
        boolean samsungBudsUuid = false;
        if (activeDev != null) {
            try {
                String dn = activeDev.getName();
                if (dn != null && !dn.trim().isEmpty()) {
                    btRawName = dn.trim();
                    s.deviceName = btRawName;
                }
            } catch (Throwable ignored) {}
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    String al = activeDev.getAlias();
                    if (al != null && !al.trim().isEmpty()) {
                        s.deviceName = al.trim();
                    }
                }
            } catch (Throwable ignored) {}
            samsungBudsUuid = hasSamsungBudsUuid(activeDev);
        }

        // 2) Public API 31+ AudioDeviceInfo.getAudioProfiles():
        //    Read sample rates & bit depths (and LC3 for BLE), NEVER lock codec to AAC here because
        //    Android reports ENCODING_AAC_LC as a generic supported profile even while SSC-UHQ / LDAC is active!
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                List<AudioProfile> profs = d.getAudioProfiles();
                if (profs != null) {
                    for (AudioProfile ap : profs) {
                        int fmt = ap.getFormat();
                        String cName = encodingToCodec(fmt);
                        if ("LC3".equals(cName) && ble) s.codec = "LC3";
                        float maxR = maxKhzFromRates(ap.getSampleRates(), 0f);
                        if (maxR > s.khz) s.khz = maxR;
                        int b = bitsFromEncoding(fmt);
                        if (b > s.bits) s.bits = b;
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 3) Live A2DP codec status via BluetoothA2dp proxy reflection (standard + Samsung semGetCodecStatus)
        boolean resolvedVendor = false;
        if (a2dpProxy != null && activeDev != null) {
            for (String mName : new String[]{"getCodecStatus", "semGetCodecStatus"}) {
                try {
                    Method getStatus = a2dpProxy.getClass().getMethod(mName, BluetoothDevice.class);
                    Object status = getStatus.invoke(a2dpProxy, activeDev);
                    if (status != null) {
                        Method getCfg = status.getClass().getMethod("getCodecConfig");
                        Object cfg = getCfg.invoke(status);
                        if (cfg != null && applyCodecConfig(s, cfg)) {
                            resolvedVendor = codecPriority(s.codec) >= 8;
                            break;
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }

        // 4) Always probe AudioManager vendor HAL parameters unless a high-res vendor codec was already confirmed
        if (!resolvedVendor && am != null) {
            try {
                String p = am.getParameters("g_bt_codec;bt_codec;a2dp_codec;bt_a2dp_codec;l_bt_codec;sem_bt_codec");
                if (p != null && !p.isEmpty()) {
                    String mapped = codecTypeName(-1, p, s.bits, s.khz);
                    if (!mapped.isEmpty() && codecPriority(mapped) > codecPriority(s.codec)) {
                        s.codec = mapped;
                        if (codecPriority(mapped) >= 8) resolvedVendor = true;
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 5) Samsung One UI + Galaxy Buds vs third-party LDAC / aptX / LHDC resolution
        String allNames = (s.deviceName + " " + btRawName + " " + productName).toLowerCase(Locale.US);
        boolean buds = samsungBudsUuid || isGalaxyBuds(allNames);
        resolveBluetoothCodec(s, isSamsungDevice(), allNames, buds, ble);
    }

    /** Resolves final BT codec without ever mislabeling non-Buds LDAC/aptX devices as SSC-UHQ. */
    static void resolveBluetoothCodec(OutputSpec s, boolean samsungHost, String allNames, boolean buds, boolean ble) {
        String n = allNames == null ? "" : allNames.toLowerCase(Locale.US);
        if (samsungHost && buds) {
            boolean uhqModel = isSamsungUhqModel(n);
            boolean hiRes24Model = uhqModel || n.contains("buds2 pro") || n.contains("buds 2 pro")
                    || n.contains("버즈2 프로") || n.contains("버즈 2 프로") || n.contains("sm-r510");
            if (s.codec.isEmpty() || "SBC".equals(s.codec) || "AAC".equals(s.codec) || "LC3".equals(s.codec)) {
                if (uhqModel || (s.bits >= 24 && s.khz >= 96f)) {
                    s.codec = "SSC-UHQ";
                    if (s.bits < 24) s.bits = 24;
                    if (s.khz < 96f) s.khz = 96f;
                } else {
                    s.codec = "SSC";
                    if (hiRes24Model && s.bits < 24) s.bits = 24;
                }
            } else if ("SSC".equals(s.codec) && (uhqModel || (s.bits >= 24 && s.khz >= 96f))) {
                s.codec = "SSC-UHQ";
                if (s.bits < 24) s.bits = 24;
                if (s.khz < 96f) s.khz = 96f;
            }
            return;
        }
        // Only upgrade explicit "SSC" (never empty codec!) on non-named Samsung SSC sinks
        if (samsungHost && "SSC".equals(s.codec) && s.bits >= 24 && s.khz >= 96f) {
            s.codec = "SSC-UHQ";
            return;
        }
        if (s.codec == null || s.codec.isEmpty()) {
            if (n.contains("airpods") || n.contains("beats")) {
                s.codec = "AAC";
            } else if (s.khz >= 96f || s.bits >= 24) {
                if (n.contains("aptx") || n.contains("sennheiser") || n.contains("momentum")
                        || n.contains("bose") || n.contains("bowers") || n.contains("denon")) {
                    s.codec = "aptX Adaptive";
                } else if (n.contains("lhdc") || n.contains("nothing") || n.contains("oneplus")
                        || n.contains("xiaomi") || n.contains("redmi")) {
                    s.codec = "LHDC";
                } else {
                    s.codec = "LDAC";
                }
            } else if (ble) {
                s.codec = "LC3";
            } else {
                s.codec = "AAC";
            }
        }
    }

    private static boolean hasSamsungBudsUuid(BluetoothDevice dev) {
        try {
            android.os.ParcelUuid[] uuids = dev.getUuids();
            if (uuids == null) return false;
            for (android.os.ParcelUuid u : uuids) {
                if (u == null) continue;
                String s = u.toString().toLowerCase(Locale.US);
                // Samsung Galaxy Buds Wearable SPP / Continuity service UUID prefixes
                if (s.startsWith("a23d00bc-") || s.startsWith("f8620674-") || s.startsWith("2e73a4ad-") || s.startsWith("0000fd69-")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isSamsungUhqModel(String lowerName) {
        if (lowerName == null) return false;
        return lowerName.contains("buds3") || lowerName.contains("buds 3")
                || lowerName.contains("buds4") || lowerName.contains("buds 4")
                || lowerName.contains("버즈3") || lowerName.contains("버즈 3")
                || lowerName.contains("버즈4") || lowerName.contains("버즈 4")
                || lowerName.contains("sm-r630") || lowerName.contains("sm-r530")
                || lowerName.contains("sm-r640") || lowerName.contains("sm-r540");
    }

    private static BluetoothDevice findConnectedA2dpDevice(Context c) {
        if (a2dpProxy != null) {
            try {
                try {
                    Method getActive = a2dpProxy.getClass().getMethod("getActiveDevice");
                    Object r = getActive.invoke(a2dpProxy);
                    if (r instanceof BluetoothDevice) return (BluetoothDevice) r;
                } catch (Throwable ignored) {}
                List<BluetoothDevice> list = a2dpProxy.getConnectedDevices();
                if (list != null && !list.isEmpty()) return list.get(0);
            } catch (Throwable ignored) {}
        }
        try {
            BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm != null) {
                List<BluetoothDevice> list = bm.getConnectedDevices(BluetoothProfile.A2DP);
                if (list != null && !list.isEmpty()) return list.get(0);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String encodingToCodec(int enc) {
        switch (enc) {
            case AudioFormat.ENCODING_AAC_LC:
            case AudioFormat.ENCODING_AAC_HE_V1:
            case AudioFormat.ENCODING_AAC_HE_V2:
            case AudioFormat.ENCODING_AAC_ELD:
            case AudioFormat.ENCODING_AAC_XHE: return "AAC";
            case AudioFormat.ENCODING_OPUS: return "Opus";
            default:
                // API 33+ Bluetooth encodings (checked by int constants for compatibility)
                if (enc == 24) return "LC3"; // ENCODING_LC3
                return "";
        }
    }

    private static int codecPriority(String c) {
        if (c == null || c.isEmpty()) return 0;
        if ("SSC-UHQ".equals(c) || "LDAC".equals(c) || "aptX Lossless".equals(c)) return 10;
        if ("SSC".equals(c) || "aptX Adaptive".equals(c) || "aptX HD".equals(c) || "LHDC".equals(c)) return 8;
        if ("aptX".equals(c) || "LC3".equals(c) || "Opus".equals(c)) return 6;
        if ("AAC".equals(c)) return 4;
        return 2;
    }

    private static int bitsFromEncoding(int e) {
        if (e == AudioFormat.ENCODING_PCM_32BIT || e == AudioFormat.ENCODING_PCM_FLOAT) return 32;
        if (e == AudioFormat.ENCODING_PCM_24BIT_PACKED) return 24;
        if (e == AudioFormat.ENCODING_PCM_16BIT) return 16;
        return 0;
    }

    private static boolean isSamsungDevice() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.US);
        return m.contains("samsung");
    }

    private static boolean isGalaxyBuds(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        return n.contains("galaxy buds") || n.contains("buds3") || n.contains("buds 3")
                || n.contains("buds2") || n.contains("buds 2") || n.contains("buds pro")
                || n.contains("buds fe") || n.contains("buds live") || n.contains("buds+")
                || n.contains("sm-r") || n.contains("갤럭시 버즈") || n.contains("버즈");
    }

    private static boolean applyCodecConfig(OutputSpec s, Object cfg) {
        boolean gotCodec = false;
        try {
            try {
                Method mRate = cfg.getClass().getMethod("getSampleRate");
                Object r = mRate.invoke(cfg);
                if (r instanceof Integer) {
                    float khz = sampleRateMaskToKhz((Integer) r);
                    if (khz > 0) s.khz = khz;
                }
            } catch (Throwable ignored) {}
            try {
                Method mBits = cfg.getClass().getMethod("getBitsPerSample");
                Object b = mBits.invoke(cfg);
                if (b instanceof Integer) {
                    int bits = bitsMaskToInt((Integer) b);
                    if (bits > 0) s.bits = bits;
                }
            } catch (Throwable ignored) {}

            String name = null;
            try {
                Method mName = cfg.getClass().getMethod("getCodecName");
                Object n = mName.invoke(cfg);
                if (n instanceof String && !((String) n).isEmpty()) name = (String) n;
            } catch (Throwable ignored) {}
            int type = -1;
            try {
                Method mType = cfg.getClass().getMethod("getCodecType");
                Object t = mType.invoke(cfg);
                if (t instanceof Integer) type = (Integer) t;
            } catch (Throwable ignored) {}
            long spec1 = 0;
            try {
                Method mS1 = cfg.getClass().getMethod("getCodecSpecific1");
                Object v1 = mS1.invoke(cfg);
                if (v1 instanceof Long) spec1 = (Long) v1;
            } catch (Throwable ignored) {}

            String mapped = codecTypeName(type, name, s.bits, s.khz);
            if (mapped.isEmpty() && isSamsungDevice() && isGalaxyBuds(s.deviceName) && type >= 5) {
                mapped = (s.khz >= 96f || (spec1 & 0xFF) == 96) ? "SSC-UHQ" : "SSC";
            }
            if (!mapped.isEmpty()) {
                s.codec = mapped;
                gotCodec = true;
            }
        } catch (Throwable ignored) {}
        return gotCodec;
    }

    static String codecTypeName(int type, String rawName, int bits, float khz) {
        if (rawName != null && !rawName.isEmpty() && !rawName.startsWith("UNKNOWN")) {
            String u = rawName.toUpperCase(Locale.US);
            if (u.contains("SSC") || u.contains("SCALABLE") || u.contains("SEAMLESS")) {
                if (u.contains("UHQ") || u.contains("96") || (bits >= 24 && khz >= 96f)) return "SSC-UHQ";
                return "SSC";
            }
            if (u.contains("LDAC")) return "LDAC";
            if (u.contains("LHDC")) return "LHDC";
            if (u.contains("APTX") && u.contains("LOSSLESS")) return "aptX Lossless";
            if (u.contains("APTX") && u.contains("ADAPTIVE")) return "aptX Adaptive";
            if (u.contains("APTX") && u.contains("HD")) return "aptX HD";
            if (u.contains("APTX")) return "aptX";
            if (u.contains("LC3")) return "LC3";
            if (u.contains("OPUS")) return "Opus";
            if (u.contains("AAC")) return "AAC";
            if (u.contains("SBC")) return "SBC";
        }
        switch (type) {
            case 0: return "SBC";
            case 1: return "AAC";
            case 2: return "aptX";
            case 3: return "aptX HD";
            case 4: return "LDAC";
            case 5: return "LC3";
            case 6: return "Opus";
            case 7:
            case 8: return (bits >= 24 && khz >= 96f) ? "SSC-UHQ" : "SSC";
            default: return "";
        }
    }

    private static float sampleRateMaskToKhz(int mask) {
        if ((mask & 0x20) != 0) return 192f;
        if ((mask & 0x10) != 0) return 176.4f;
        if ((mask & 0x08) != 0) return 96f;
        if ((mask & 0x04) != 0) return 88.2f;
        if ((mask & 0x02) != 0) return 48f;
        if ((mask & 0x01) != 0) return 44.1f;
        return 0f;
    }

    private static int bitsMaskToInt(int mask) {
        if ((mask & 0x04) != 0) return 32;
        if ((mask & 0x02) != 0) return 24;
        if ((mask & 0x01) != 0) return 16;
        return 0;
    }

    private static int maxBitsFromEncodings(int[] encs, int fallback) {
        if (encs == null || encs.length == 0) return fallback;
        int best = 0;
        for (int e : encs) {
            int b = bitsFromEncoding(e);
            if (b > best) best = b;
        }
        return best > 0 ? best : fallback;
    }

    private static float maxKhzFromRates(int[] rates, float fallback) {
        if (rates == null || rates.length == 0) return fallback;
        int best = 0;
        for (int r : rates) if (r > best && r <= 384000) best = r;
        return best > 0 ? best / 1000f : fallback;
    }
}
