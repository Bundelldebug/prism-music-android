package com.oneuibook.prism;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.SystemClock;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Built-in YouTube Music Regional VPN / Split-Tunnel Relay Controller (US & Japan) + Korea IP Geo-Block Detector:
 * - Supports 3 modes: OFF ("off"), United States ("us"), and Japan ("jp").
 * - Probes real egress IP geolocation (https://api.country.is/ with https://ipwho.is/ fallback) to accurately
 *   detect South Korea ("KR") IP restrictions on YouTube Music free tier vs. network/other errors.
 * - Connects to verified TLS-CONNECT-capable US/JP relays (user custom proxy or live public relays) with real
 *   region + YouTube Music 204 health verification before activating.
 * - Runs a strictly loopback-only (127.0.0.1) split-tunnel CONNECT forwarder that tunnels ONLY YouTube Music
 *   domains (music.youtube.com, m.youtube.com, www.youtube.com, *.googlevideo.com, *.ytimg.com) while keeping
 *   Spotify, Google Sign-In, and all other traffic direct.
 * - Registers the loopback proxy on Chromium WebView via WebView Support Library ProxyController when available,
 *   and also provides native java.net.Proxy routing for all InnerTube HttpURLConnection calls.
 * - Detects active external system VPNs (TRANSPORT_VPN) and offers 1-tap external VPN launch + auto-reload.
 */
final class YtmVpn {
    private YtmVpn() {}

    static final String REGION_OFF = "off";
    static final String REGION_US = "us";
    static final String REGION_JP = "jp";

    static final String PREF_REGION = "ytm_vpn_region";
    static final String PREF_CUSTOM_US = "ytm_vpn_custom_us";
    static final String PREF_CUSTOM_JP = "ytm_vpn_custom_jp";

    static final String[] VPN_PACKAGES = {
            "com.nordvpn.android",
            "ch.protonvpn.android",
            "net.mullvad.mullvadvpn",
            "com.wireguard.android",
            "com.cloudflare.onedotonedotonedotone"
    };

    static final class RelayNode {
        final String host;
        final int port;
        final java.net.Proxy.Type type;
        final String user;
        final String pass;
        final String country;
        final long latencyMs;
        final String exitIp;

        RelayNode(String host, int port, java.net.Proxy.Type type, String user, String pass, String country, long latencyMs, String exitIp) {
            this.host = host;
            this.port = port;
            this.type = type;
            this.user = user == null ? "" : user;
            this.pass = pass == null ? "" : pass;
            this.country = country == null ? "" : country;
            this.latencyMs = latencyMs;
            this.exitIp = exitIp == null ? "" : exitIp;
        }

        java.net.Proxy toJavaProxy() {
            return new java.net.Proxy(type, new InetSocketAddress(host, port));
        }

        String basicAuthHeader() {
            if (user.isEmpty()) return null;
            String raw = user + ":" + pass;
            return "Basic " + android.util.Base64.encodeToString(raw.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        }
    }

    static final class RegionInfo {
        final String country;      // e.g. "KR", "US", "JP", ""
        final String ip;
        final boolean externalVpn;
        final boolean builtInVpn;
        final long checkedAtMs;

        RegionInfo(String country, String ip, boolean externalVpn, boolean builtInVpn, long checkedAtMs) {
            this.country = country == null ? "" : country.toUpperCase(Locale.US);
            this.ip = ip == null ? "" : ip;
            this.externalVpn = externalVpn;
            this.builtInVpn = builtInVpn;
            this.checkedAtMs = checkedAtMs;
        }

        boolean isKorea() { return "KR".equalsIgnoreCase(country); }
    }

    static final class Status {
        final String region;        // off / us / jp
        final boolean connecting;
        final boolean connected;
        final boolean externalVpn;
        final boolean webViewProxied;
        final String detectedCountry;
        final String exitIp;
        final long latencyMs;
        final String error;

        Status(String region, boolean connecting, boolean connected, boolean externalVpn, boolean webViewProxied,
               String detectedCountry, String exitIp, long latencyMs, String error) {
            this.region = region;
            this.connecting = connecting;
            this.connected = connected;
            this.externalVpn = externalVpn;
            this.webViewProxied = webViewProxied;
            this.detectedCountry = detectedCountry == null ? "" : detectedCountry;
            this.exitIp = exitIp == null ? "" : exitIp;
            this.latencyMs = latencyMs;
            this.error = error == null ? "" : error;
        }
    }

    /** Test seam for Robolectric unit tests (avoids real network calls). */
    static volatile RegionInfo testRegionOverride;

    private static final ExecutorService NET = Executors.newFixedThreadPool(6);
    private static final ExecutorService TUN = Executors.newCachedThreadPool();
    static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private static volatile boolean connecting;
    private static volatile RelayNode activeRelay;
    private static final List<RelayNode> backupRelays = new CopyOnWriteArrayList<>();
    private static volatile RegionInfo cachedRegion;
    private static volatile String lastError = "";
    private static volatile boolean webViewProxyApplied;
    private static volatile LocalSplitProxy localProxy;
    private static volatile long lastNoticeAtMs;
    private static ConnectivityManager.NetworkCallback vpnWatch;

    static String region(Context c) {
        Context ctx = c != null ? c : YouTubeMusic.app;
        if (ctx == null) return REGION_OFF;
        String r = MainActivity.prefs(ctx).getString(PREF_REGION, REGION_OFF);
        if (REGION_US.equals(r) || REGION_JP.equals(r)) return r;
        return REGION_OFF;
    }

    static boolean isEnabled(Context c) {
        return !REGION_OFF.equals(region(c));
    }

    static boolean isBuiltInConnected() {
        return activeRelay != null;
    }

    static String countryCodeForRegion(String r) {
        if (REGION_US.equals(r)) return "US";
        if (REGION_JP.equals(r)) return "JP";
        return "";
    }

    /** Returns effective YouTube Music InnerTube gl parameter (US/JP when built-in VPN is enabled, else device locale). */
    static String effectiveGl(Context c) {
        String r = region(c);
        if (REGION_US.equals(r)) return "US";
        if (REGION_JP.equals(r)) return "JP";
        String def = Locale.getDefault().getCountry();
        return (def == null || def.isEmpty()) ? "KR" : def.toUpperCase(Locale.US);
    }

    static String customProxy(Context c, String region) {
        Context ctx = c != null ? c : YouTubeMusic.app;
        if (ctx == null) return "";
        String key = REGION_JP.equals(region) ? PREF_CUSTOM_JP : PREF_CUSTOM_US;
        String v = SecureVault.get(MainActivity.prefs(ctx), key);
        return v == null ? "" : v.trim();
    }

    static void setCustomProxy(Context c, String region, String rawSpec) {
        Context ctx = c != null ? c : YouTubeMusic.app;
        if (ctx == null) return;
        String key = REGION_JP.equals(region) ? PREF_CUSTOM_JP : PREF_CUSTOM_US;
        String clean = rawSpec == null ? "" : rawSpec.trim();
        SharedPreferences sp = MainActivity.prefs(ctx);
        if (clean.isEmpty()) sp.edit().remove(key).apply();
        else SecureVault.put(sp, key, clean);
    }

    static Status status(Context c) {
        String reg = region(c);
        boolean ext = isExternalVpnActive(c);
        RelayNode rn = activeRelay;
        RegionInfo ri = testRegionOverride != null ? testRegionOverride : cachedRegion;
        String cc = rn != null && !rn.country.isEmpty() ? rn.country : (ri != null ? ri.country : "");
        String ip = rn != null && !rn.exitIp.isEmpty() ? rn.exitIp : (ri != null ? ri.ip : "");
        long lat = rn != null ? rn.latencyMs : 0L;
        return new Status(reg, connecting, rn != null || ext, ext, webViewProxyApplied, cc, ip, lat, lastError);
    }

    static String summaryText(Context c) {
        if (c == null) return "";
        Status st = status(c);
        if (st.connecting) {
            return c.getString(R.string.ytm_vpn_connecting, regionLabel(c, st.region));
        }
        if (!REGION_OFF.equals(st.region)) {
            if (activeRelay != null) {
                String lbl = regionLabel(c, st.region);
                return st.latencyMs > 0
                        ? c.getString(R.string.ytm_vpn_connected_ms, lbl, st.latencyMs)
                        : c.getString(R.string.ytm_vpn_connected, lbl);
            }
            if (!st.error.isEmpty()) return st.error;
            return c.getString(R.string.ytm_vpn_disconnected);
        }
        if (st.externalVpn) {
            return st.detectedCountry.isEmpty()
                    ? c.getString(R.string.ytm_vpn_external_active)
                    : c.getString(R.string.ytm_vpn_external_country, st.detectedCountry);
        }
        if ("KR".equalsIgnoreCase(st.detectedCountry)) {
            return c.getString(R.string.ytm_vpn_kr_detected);
        }
        if (!st.detectedCountry.isEmpty()) {
            return c.getString(R.string.ytm_vpn_direct_country, st.detectedCountry);
        }
        return c.getString(R.string.ytm_vpn_off);
    }

    static String regionLabel(Context c, String reg) {
        if (REGION_US.equals(reg)) return c.getString(R.string.ytm_vpn_us);
        if (REGION_JP.equals(reg)) return c.getString(R.string.ytm_vpn_jp);
        return c.getString(R.string.ytm_vpn_off);
    }

    private static void notifyChanged() {
        Spotify.MAIN.post(() -> {
            for (Runnable r : listeners) {
                try { r.run(); } catch (Throwable ignored) {}
            }
        });
    }

    /** Switches built-in YouTube Music VPN mode (off / us / jp), connecting or tearing down the split-tunnel relay. */
    static void setRegion(Context c, String newRegion, Consumer<Boolean> onDone) {
        Context ctx = (c != null ? c : YouTubeMusic.app);
        if (ctx == null) { if (onDone != null) onDone.accept(false); return; }
        final Context app = ctx.getApplicationContext();
        final String target = (REGION_US.equals(newRegion) || REGION_JP.equals(newRegion)) ? newRegion : REGION_OFF;
        MainActivity.prefs(app).edit().putString(PREF_REGION, target).apply();
        cachedRegion = null;
        lastError = "";

        if (REGION_OFF.equals(target)) {
            disconnectInternal();
            notifyChanged();
            checkEgressRegion(app, true, ri -> {
                notifyChanged();
                if (onDone != null) onDone.accept(true);
            });
            return;
        }

        connecting = true;
        notifyChanged();
        NET.execute(() -> {
            RelayNode found = findWorkingRelay(app, target);
            if (!target.equals(region(app))) {
                // User changed selection while probing
                return;
            }
            if (found == null) {
                connecting = false;
                activeRelay = null;
                backupRelays.clear();
                stopLocalProxy();
                clearWebViewProxy();
                lastError = app.getString(R.string.ytm_vpn_connect_failed, regionLabel(app, target));
                notifyChanged();
                Spotify.MAIN.post(() -> { if (onDone != null) onDone.accept(false); });
                return;
            }
            activeRelay = found;
            lastError = "";
            cachedRegion = new RegionInfo(found.country, found.exitIp, isExternalVpnActive(app), true, SystemClock.elapsedRealtime());
            int port = startLocalProxy();
            if (port > 0) {
                Spotify.MAIN.post(() -> {
                    webViewProxyApplied = applyWebViewProxy(port);
                    connecting = false;
                    YouTubeMusic.stopWeb(); // Reset WebView sockets so next play uses the new US/JP exit IP
                    notifyChanged();
                    if (onDone != null) onDone.accept(true);
                });
            } else {
                connecting = false;
                webViewProxyApplied = false;
                notifyChanged();
                Spotify.MAIN.post(() -> { if (onDone != null) onDone.accept(true); });
            }
        });
    }

    /** Restores a previously enabled US/JP YTM VPN mode at app startup without blocking the UI thread. */
    static void init(Context c) {
        if (c == null) return;
        Context app = c.getApplicationContext();
        String reg = region(app);
        if (REGION_US.equals(reg) || REGION_JP.equals(reg)) {
            if (activeRelay == null && !connecting && YouTubeMusic.testFixtures == null) {
                setRegion(app, reg, null);
            }
        }
    }

    static void shutdown() {
        disconnectInternal();
    }

    private static void disconnectInternal() {
        connecting = false;
        activeRelay = null;
        backupRelays.clear();
        stopLocalProxy();
        Spotify.MAIN.post(() -> {
            clearWebViewProxy();
            YouTubeMusic.stopWeb();
        });
    }

    /** Opens an HttpURLConnection routed through the active US/JP YTM relay when enabled, or direct otherwise. */
    static HttpURLConnection openConnection(String rawUrl) throws IOException {
        URL u = new URL(rawUrl);
        RelayNode rn = activeRelay;
        if (rn != null && shouldTunnelHost(u.getHost())) {
            HttpURLConnection conn = (HttpURLConnection) u.openConnection(rn.toJavaProxy());
            String auth = rn.basicAuthHeader();
            if (auth != null && rn.type == java.net.Proxy.Type.HTTP) {
                conn.setRequestProperty("Proxy-Authorization", auth);
            }
            return conn;
        }
        return (HttpURLConnection) u.openConnection();
    }

    /** Only YouTube Music & IP-verification hosts pass through the YTM split-tunnel relay; Spotify/Google Auth stay direct. */
    static boolean shouldTunnelHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.US);
        return h.equals("music.youtube.com")
                || h.equals("m.youtube.com")
                || h.equals("www.youtube.com")
                || h.equals("youtube.com")
                || h.equals("youtubei.googleapis.com")
                || h.equals("googlevideo.com") || h.endsWith(".googlevideo.com")
                || h.equals("ytimg.com") || h.endsWith(".ytimg.com")
                || h.equals("api.country.is")
                || h.equals("ipwho.is");
    }

    // ---------------------------------------------------------------- Region & Korea IP Detection
    static boolean isExternalVpnActive(Context c) {
        Context ctx = c != null ? c : YouTubeMusic.app;
        if (ctx == null) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network active = cm.getActiveNetwork();
            if (active != null) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(active);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    static RegionInfo cachedRegionInfo() {
        return testRegionOverride != null ? testRegionOverride : cachedRegion;
    }

    static void checkEgressRegion(Context c, boolean force, Consumer<RegionInfo> cb) {
        if (testRegionOverride != null) {
            RegionInfo ri = testRegionOverride;
            if (cb != null) Spotify.MAIN.post(() -> cb.accept(ri));
            return;
        }
        RegionInfo cur = cachedRegion;
        long now = SystemClock.elapsedRealtime();
        if (!force && cur != null && (now - cur.checkedAtMs) < 300_000L && !cur.country.isEmpty()) {
            if (cb != null) Spotify.MAIN.post(() -> cb.accept(cur));
            return;
        }
        final Context app = c != null ? c.getApplicationContext() : YouTubeMusic.app;
        NET.execute(() -> {
            RegionInfo ri = probeRegionSync(app, activeRelay);
            if (ri != null && !ri.country.isEmpty()) cachedRegion = ri;
            final RegionInfo out = ri != null ? ri : new RegionInfo("", "", isExternalVpnActive(app), activeRelay != null, SystemClock.elapsedRealtime());
            notifyChanged();
            if (cb != null) Spotify.MAIN.post(() -> cb.accept(out));
        });
    }

    private static RegionInfo probeRegionSync(Context app, RelayNode relay) {
        boolean ext = isExternalVpnActive(app);
        boolean bi = relay != null;
        // 1) Primary tiny HTTPS JSON endpoint: {"ip":"...","country":"KR"}
        try {
            String body = httpGetViaRelay("https://api.country.is/", relay, 4500);
            if (body != null && body.startsWith("{")) {
                JSONObject o = new JSONObject(body);
                String cc = o.optString("country", "").trim();
                String ip = o.optString("ip", "").trim();
                if (!cc.isEmpty()) return new RegionInfo(cc, ip, ext, bi, SystemClock.elapsedRealtime());
            }
        } catch (Exception ignored) {}
        // 2) Fallback HTTPS endpoint: ipwho.is
        try {
            String body = httpGetViaRelay("https://ipwho.is/", relay, 4500);
            if (body != null && body.startsWith("{")) {
                JSONObject o = new JSONObject(body);
                String cc = o.optString("country_code", "").trim();
                String ip = o.optString("ip", "").trim();
                if (!cc.isEmpty()) return new RegionInfo(cc, ip, ext, bi, SystemClock.elapsedRealtime());
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Accurately identifies YouTube Music geo-restriction / Korea free-tier Premium-only messages. */
    static boolean isKoreaIpBlockedMessage(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        String s = raw.toLowerCase(Locale.ROOT);
        return s.contains("youtube music premium")
                || s.contains("music_premium")
                || s.contains("not available in your country")
                || s.contains("not available in your area")
                || s.contains("not available in your region")
                || s.contains("available in your country")
                || s.contains("country is not supported")
                || s.contains("uploader has not made this video available in your country")
                || s.contains("국가에서")
                || s.contains("지역에서는")
                || s.contains("거주 국가")
                || s.contains("대한민국")
                || s.contains("premium 회원")
                || s.contains("프리미엄 회원")
                || s.contains("무료 버전을 이용할 수 없")
                || s.contains("geo_blocked");
    }

    /** Inspects an InnerTube JSON response for explicit YouTube Music geo/Premium block payloads. */
    static String extractGeoBlockReason(JSONObject res) {
        if (res == null) return "";
        try {
            JSONObject ps = res.optJSONObject("playabilityStatus");
            if (ps != null) {
                String st = ps.optString("status", "");
                String reason = ps.optString("reason", "");
                if (("UNPLAYABLE".equals(st) || "ERROR".equals(st) || "LOGIN_REQUIRED".equals(st))
                        && isKoreaIpBlockedMessage(reason)) {
                    return reason;
                }
            }
            // Check top-level error / upsell alerts returned by music.youtube.com in KR without Premium
            String text = res.toString();
            if (text.length() < 60_000) {
                String low = text.toLowerCase(Locale.ROOT);
                if ((low.contains("musicpremium") || low.contains("youtube music premium"))
                        && !low.contains("musicresponsivelistitemrenderer")
                        && !low.contains("musictworowitemrenderer")) {
                    return "YouTube Music Premium required in this region";
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /**
     * Returns true when a YouTube Music failure is attributable to a Korean IP restriction
     * (either an explicit YouTube geo/Premium message OR confirmed KR egress IP without an active VPN).
     */
    static boolean isKoreaIpRestriction(Context c, String errorReason) {
        if (isKoreaIpBlockedMessage(errorReason)) return true;
        RegionInfo ri = cachedRegionInfo();
        if (ri != null && ri.isKorea() && !ri.builtInVpn && !ri.externalVpn) {
            return true;
        }
        return false;
    }

    /**
     * Evaluates an error asynchronously (probing egress IP if not yet cached) and invokes {@code onResult}
     * with {@code true} ONLY if a Korea IP / regional restriction is confirmed.
     */
    static void diagnosePlaybackFailure(Context c, String reason, Consumer<Boolean> onResult) {
        if (isKoreaIpBlockedMessage(reason)) {
            if (onResult != null) Spotify.MAIN.post(() -> onResult.accept(true));
            return;
        }
        checkEgressRegion(c, false, ri -> {
            boolean krBlocked = ri != null && ri.isKorea() && !ri.builtInVpn && !ri.externalVpn;
            if (onResult != null) onResult.accept(krBlocked);
        });
    }

    /** Shows an accurate Korea IP restriction guidance dialog with 1-tap US / JP VPN activation. */
    static void showKoreaIpDialog(Activity a, String detail, Runnable onVpnConnected) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastNoticeAtMs < 15_000L) return;
        lastNoticeAtMs = now;
        RegionInfo ri = cachedRegionInfo();
        String locLine = (ri != null && !ri.country.isEmpty())
                ? a.getString(R.string.ytm_kr_block_loc, ri.country, ri.ip.isEmpty() ? "-" : ri.ip)
                : a.getString(R.string.ytm_kr_block_loc_unknown);
        String msg = a.getString(R.string.ytm_kr_block_body) + "\n\n" + locLine
                + (detail != null && !detail.isEmpty() ? ("\n(" + detail + ")") : "");
        new AlertDialog.Builder(a,
                Ui.dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle(R.string.ytm_kr_block_title)
                .setMessage(msg)
                .setPositiveButton(R.string.ytm_vpn_connect_us, (d, w) -> {
                    Toast.makeText(a, a.getString(R.string.ytm_vpn_connecting, a.getString(R.string.ytm_vpn_us)), Toast.LENGTH_SHORT).show();
                    setRegion(a, REGION_US, ok -> {
                        Toast.makeText(a, ok ? a.getString(R.string.ytm_vpn_connected, a.getString(R.string.ytm_vpn_us))
                                : a.getString(R.string.ytm_vpn_connect_failed, a.getString(R.string.ytm_vpn_us)), Toast.LENGTH_LONG).show();
                        if (ok && onVpnConnected != null) onVpnConnected.run();
                    });
                })
                .setNeutralButton(R.string.ytm_vpn_connect_jp, (d, w) -> {
                    Toast.makeText(a, a.getString(R.string.ytm_vpn_connecting, a.getString(R.string.ytm_vpn_jp)), Toast.LENGTH_SHORT).show();
                    setRegion(a, REGION_JP, ok -> {
                        Toast.makeText(a, ok ? a.getString(R.string.ytm_vpn_connected, a.getString(R.string.ytm_vpn_jp))
                                : a.getString(R.string.ytm_vpn_connect_failed, a.getString(R.string.ytm_vpn_jp)), Toast.LENGTH_LONG).show();
                        if (ok && onVpnConnected != null) onVpnConnected.run();
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Launches an installed external VPN app or Android VPN settings and auto-runs callback once VPN comes up. */
    static void openExternalVpn(Context c, Runnable onConnected) {
        if (c == null) return;
        watchExternalVpn(c, onConnected);
        for (String pkg : VPN_PACKAGES) {
            try {
                Intent launch = c.getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(launch);
                    return;
                }
            } catch (Throwable ignored) {}
        }
        try {
            Intent vpnSettings = new Intent("android.settings.VPN_SETTINGS");
            vpnSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(vpnSettings);
        } catch (Throwable ignored) {}
    }

    static synchronized void watchExternalVpn(Context c, Runnable onConnected) {
        if (c == null || onConnected == null) return;
        Context app = c.getApplicationContext();
        stopWatchingExternalVpn(app);
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            AtomicBoolean fired = new AtomicBoolean(false);
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();
            vpnWatch = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    if (fired.compareAndSet(false, true)) {
                        stopWatchingExternalVpn(app);
                        cachedRegion = null;
                        Spotify.MAIN.post(onConnected);
                    }
                }
            };
            cm.registerNetworkCallback(req, vpnWatch);
        } catch (Throwable ignored) {}
    }

    static synchronized void stopWatchingExternalVpn(Context c) {
        if (vpnWatch == null || c == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(vpnWatch);
        } catch (Throwable ignored) {}
        vpnWatch = null;
    }

    // ---------------------------------------------------------------- Relay Discovery & Verification
    static RelayNode parseProxySpec(String spec, String wantCountry) {
        if (spec == null) return null;
        String s = spec.trim();
        if (s.isEmpty()) return null;
        java.net.Proxy.Type type = java.net.Proxy.Type.HTTP;
        if (s.startsWith("socks5://") || s.startsWith("socks://")) {
            type = java.net.Proxy.Type.SOCKS;
            s = s.substring(s.indexOf("://") + 3);
        } else if (s.startsWith("http://") || s.startsWith("https://")) {
            s = s.substring(s.indexOf("://") + 3);
        }
        String user = "", pass = "";
        int at = s.lastIndexOf('@');
        if (at > 0) {
            String auth = s.substring(0, at);
            s = s.substring(at + 1);
            int cIdx = auth.indexOf(':');
            if (cIdx >= 0) {
                user = auth.substring(0, cIdx);
                pass = auth.substring(cIdx + 1);
            } else {
                user = auth;
            }
        }
        int slash = s.indexOf('/');
        if (slash > 0) s = s.substring(0, slash);
        int colon = s.lastIndexOf(':');
        if (colon <= 0 || colon >= s.length() - 1) return null;
        String host = s.substring(0, colon).trim();
        int port;
        try { port = Integer.parseInt(s.substring(colon + 1).trim()); } catch (Exception e) { return null; }
        if (host.isEmpty() || port <= 0 || port > 65535) return null;
        return new RelayNode(host, port, type, user, pass, wantCountry, 0L, "");
    }

    private static RelayNode findWorkingRelay(Context app, String targetRegion) {
        String wantCountry = countryCodeForRegion(targetRegion);
        // 1) User-configured custom proxy takes immediate priority
        String custom = customProxy(app, targetRegion);
        if (!custom.isEmpty()) {
            RelayNode parsed = parseProxySpec(custom, wantCountry);
            if (parsed != null) {
                RelayNode verified = verifyRelay(parsed, wantCountry, true);
                if (verified != null) {
                    backupRelays.clear();
                    backupRelays.add(verified);
                    return verified;
                }
            }
        }
        // 2) Fetch live HTTPS-CONNECT capable US/JP public relay candidates
        List<RelayNode> cands = fetchPublicCandidates(wantCountry);
        if (cands.isEmpty()) return null;

        List<Future<RelayNode>> futs = new ArrayList<>();
        int maxProbe = Math.min(10, cands.size());
        for (int i = 0; i < maxProbe; i++) {
            final RelayNode cand = cands.get(i);
            futs.add(NET.submit(() -> verifyRelay(cand, wantCountry, false)));
        }
        List<RelayNode> okList = new ArrayList<>();
        for (Future<RelayNode> f : futs) {
            try {
                RelayNode r = f.get(6, TimeUnit.SECONDS);
                if (r != null) okList.add(r);
            } catch (Exception ignored) {}
        }
        if (okList.isEmpty()) return null;
        okList.sort((a, b) -> Long.compare(a.latencyMs, b.latencyMs));
        backupRelays.clear();
        backupRelays.addAll(okList);
        return okList.get(0);
    }

    private static List<RelayNode> fetchPublicCandidates(String cc) {
        List<RelayNode> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        // Source 1: ProxyScrape v2 HTTPS-CONNECT capable nodes filtered strictly by country (US / JP)
        try {
            String u1 = "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=4000&country="
                    + cc + "&ssl=yes&anonymity=all";
            String body = httpGetViaRelay(u1, null, 5000);
            if (body != null) {
                for (String line : body.split("\\r?\\n")) {
                    String t = line.trim();
                    if (t.isEmpty() || !seen.add(t)) continue;
                    RelayNode n = parseProxySpec(t, cc);
                    if (n != null) out.add(n);
                    if (out.size() >= 12) break;
                }
            }
        } catch (Exception ignored) {}
        // Source 2: GeoNode open proxy API (HTTP & SOCKS5 in US / JP)
        if (out.size() < 6) {
            try {
                String u2 = "https://proxylist.geonode.com/api/proxy-list?limit=15&page=1&sort_by=lastChecked&sort_type=desc&country="
                        + cc + "&protocols=http,socks5";
                String body = httpGetViaRelay(u2, null, 5000);
                if (body != null && body.startsWith("{")) {
                    JSONArray data = new JSONObject(body).optJSONArray("data");
                    if (data != null) {
                        for (int i = 0; i < data.length() && out.size() < 16; i++) {
                            JSONObject o = data.optJSONObject(i);
                            if (o == null) continue;
                            String ip = o.optString("ip", "").trim();
                            int port = o.optInt("port", 0);
                            if (ip.isEmpty() || port <= 0) continue;
                            String key = ip + ":" + port;
                            if (!seen.add(key)) continue;
                            JSONArray protos = o.optJSONArray("protocols");
                            java.net.Proxy.Type ptype = java.net.Proxy.Type.HTTP;
                            if (protos != null && protos.length() > 0 && "socks5".equalsIgnoreCase(protos.optString(0))) {
                                ptype = java.net.Proxy.Type.SOCKS;
                            }
                            out.add(new RelayNode(ip, port, ptype, "", "", cc, 0L, ""));
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** Verifies real end-to-end TLS CONNECT tunneling, confirms exit country matches US/JP, and tests YouTube Music reachability. */
    private static RelayNode verifyRelay(RelayNode cand, String wantCountry, boolean allowAnyNonKr) {
        long t0 = SystemClock.elapsedRealtime();
        try {
            // 1) Confirm TLS CONNECT + real exit country
            String geoBody = httpGetViaRelay("https://api.country.is/", cand, 4000);
            if (geoBody == null || !geoBody.startsWith("{")) return null;
            JSONObject geo = new JSONObject(geoBody);
            String cc = geo.optString("country", "").trim().toUpperCase(Locale.US);
            String ip = geo.optString("ip", "").trim();
            if (cc.isEmpty() || "KR".equals(cc)) return null;
            if (!allowAnyNonKr && !wantCountry.equals(cc)) return null;

            // 2) Confirm music.youtube.com is reachable through this relay
            HttpURLConnection yt = (HttpURLConnection) new URL("https://music.youtube.com/generate_204").openConnection(cand.toJavaProxy());
            yt.setInstanceFollowRedirects(false);
            yt.setConnectTimeout(4000);
            yt.setReadTimeout(4000);
            String auth = cand.basicAuthHeader();
            if (auth != null && cand.type == java.net.Proxy.Type.HTTP) yt.setRequestProperty("Proxy-Authorization", auth);
            int code;
            try { code = yt.getResponseCode(); } finally { yt.disconnect(); }
            if (code != 204 && code != 200) return null;
            long ms = Math.max(1L, SystemClock.elapsedRealtime() - t0);
            return new RelayNode(cand.host, cand.port, cand.type, cand.user, cand.pass, cc, ms, ip);
        } catch (Exception e) {
            return null;
        }
    }

    private static String httpGetViaRelay(String rawUrl, RelayNode relay, int timeoutMs) throws Exception {
        URL u = new URL(rawUrl);
        HttpURLConnection c = relay != null
                ? (HttpURLConnection) u.openConnection(relay.toJavaProxy())
                : (HttpURLConnection) u.openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("User-Agent", "Prism/9.5 (Android)");
        if (relay != null && relay.type == java.net.Proxy.Type.HTTP) {
            String auth = relay.basicAuthHeader();
            if (auth != null) c.setRequestProperty("Proxy-Authorization", auth);
        }
        try {
            if (c.getResponseCode() != 200) return null;
            return Spotify.read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- Loopback Split-Tunnel HTTP CONNECT Forwarder
    private static synchronized int startLocalProxy() {
        stopLocalProxy();
        try {
            LocalSplitProxy p = new LocalSplitProxy();
            p.start();
            localProxy = p;
            return p.port;
        } catch (IOException e) {
            return -1;
        }
    }

    private static synchronized void stopLocalProxy() {
        if (localProxy != null) {
            localProxy.stop();
            localProxy = null;
        }
    }

    /**
     * Strictly loopback-bound (127.0.0.1) HTTP CONNECT split-tunnel proxy for YtmWebView:
     * - Tunnels ONLY YouTube Music / GoogleVideo domains through the active US/JP relay (with failover).
     * - Passes all other hosts (Spotify, Google Auth, local) directly so non-YTM traffic is untouched.
     */
    private static final class LocalSplitProxy {
        private final ServerSocket server;
        final int port;
        private volatile boolean running = true;

        LocalSplitProxy() throws IOException {
            server = new ServerSocket(0, 32, InetAddress.getLoopbackAddress());
            port = server.getLocalPort();
        }

        void start() {
            TUN.execute(() -> {
                while (running) {
                    try {
                        Socket client = server.accept();
                        TUN.execute(() -> handleClient(client));
                    } catch (IOException e) {
                        if (!running) break;
                    }
                }
            });
        }

        void stop() {
            running = false;
            try { server.close(); } catch (IOException ignored) {}
        }

        private void handleClient(Socket client) {
            try (Socket c = client) {
                c.setSoTimeout(30_000);
                BufferedInputStream in = new BufferedInputStream(c.getInputStream(), 8192);
                OutputStream out = c.getOutputStream();
                String reqLine = readHeaderLine(in);
                if (reqLine == null || reqLine.isEmpty()) return;
                // Drain remaining request headers
                String line;
                while ((line = readHeaderLine(in)) != null && !line.isEmpty()) { /* consume */ }
                String[] parts = reqLine.split(" ");
                if (parts.length < 2 || !"CONNECT".equalsIgnoreCase(parts[0])) {
                    out.write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    return;
                }
                String hp = parts[1];
                int col = hp.lastIndexOf(':');
                String targetHost = col > 0 ? hp.substring(0, col) : hp;
                int targetPort = 443;
                if (col > 0) {
                    try { targetPort = Integer.parseInt(hp.substring(col + 1)); } catch (Exception ignored) {}
                }
                Socket upstream = openUpstream(targetHost, targetPort);
                if (upstream == null) {
                    out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    return;
                }
                try (Socket up = upstream) {
                    out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    Future<?> f = TUN.submit(() -> pipe(in, up));
                    pipe(up.getInputStream(), c);
                    try { f.get(2, TimeUnit.SECONDS); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }

        private Socket openUpstream(String host, int port) {
            if (!shouldTunnelHost(host) || activeRelay == null) {
                try {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(host, port), 8000);
                    return s;
                } catch (IOException e) {
                    return null;
                }
            }
            // Try active relay, then verified backup relays on transient failure
            List<RelayNode> tries = new ArrayList<>();
            RelayNode primary = activeRelay;
            if (primary != null) tries.add(primary);
            for (RelayNode b : backupRelays) if (b != primary) tries.add(b);
            for (RelayNode rn : tries) {
                Socket s = connectThroughRelay(rn, host, port);
                if (s != null) {
                    activeRelay = rn;
                    return s;
                }
            }
            return null;
        }

        private Socket connectThroughRelay(RelayNode rn, String targetHost, int targetPort) {
            try {
                if (rn.type == java.net.Proxy.Type.SOCKS) {
                    Socket s = new Socket(rn.toJavaProxy());
                    s.connect(InetSocketAddress.createUnresolved(targetHost, targetPort), 7000);
                    return s;
                }
                Socket s = new Socket();
                s.connect(new InetSocketAddress(rn.host, rn.port), 6000);
                s.setSoTimeout(10_000);
                StringBuilder req = new StringBuilder();
                req.append("CONNECT ").append(targetHost).append(':').append(targetPort).append(" HTTP/1.1\r\n");
                req.append("Host: ").append(targetHost).append(':').append(targetPort).append("\r\n");
                String auth = rn.basicAuthHeader();
                if (auth != null) req.append("Proxy-Authorization: ").append(auth).append("\r\n");
                req.append("\r\n");
                OutputStream os = s.getOutputStream();
                os.write(req.toString().getBytes(StandardCharsets.US_ASCII));
                os.flush();
                BufferedInputStream bis = new BufferedInputStream(s.getInputStream(), 2048);
                String status = readHeaderLine(bis);
                if (status == null || !status.contains(" 200")) {
                    s.close();
                    return null;
                }
                String h;
                while ((h = readHeaderLine(bis)) != null && !h.isEmpty()) { /* drain response headers */ }
                s.setSoTimeout(30_000);
                return s;
            } catch (Exception e) {
                return null;
            }
        }

        private static String readHeaderLine(InputStream in) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') break;
                if (b != '\r') baos.write(b);
                if (baos.size() > 4096) break;
            }
            if (b == -1 && baos.size() == 0) return null;
            return new String(baos.toByteArray(), StandardCharsets.US_ASCII);
        }

        private static void pipe(InputStream in, Socket dst) {
            try {
                OutputStream out = dst.getOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
            } finally {
                try { dst.shutdownOutput(); } catch (Exception ignored) {}
            }
        }
    }

    // ---------------------------------------------------------------- Chromium WebView ProxyController Glue
    /**
     * Registers our loopback split-tunnel proxy with Android System WebView's ProxyController boundary interface
     * (exact same mechanism androidx.webkit.ProxyController uses) so YtmWebView routes YouTube requests through US/JP.
     * Gracefully returns false if unavailable on the host environment (e.g. Robolectric).
     */
    private static boolean applyWebViewProxy(int localPort) {
        try {
            Object ctrl = getChromiumProxyController();
            if (ctrl == null) return false;
            String[][] proxyRules = new String[][]{{"*", "http://127.0.0.1:" + localPort}};
            String[] bypassRules = new String[]{
                    "127.0.0.1", "localhost", "*.local",
                    "*.spotify.com", "*.scdn.co", "*.spotifycdn.com",
                    "accounts.google.com"
            };
            Runnable done = () -> {};
            java.util.concurrent.Executor exec = Runnable::run;
            for (Method m : ctrl.getClass().getMethods()) {
                if ("setProxyOverride".equals(m.getName())) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length == 5) {
                        m.invoke(ctrl, proxyRules, bypassRules, done, exec, false);
                        return true;
                    } else if (pts.length == 4) {
                        m.invoke(ctrl, proxyRules, bypassRules, done, exec);
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void clearWebViewProxy() {
        webViewProxyApplied = false;
        try {
            Object ctrl = getChromiumProxyController();
            if (ctrl == null) return;
            Runnable done = () -> {};
            java.util.concurrent.Executor exec = Runnable::run;
            for (Method m : ctrl.getClass().getMethods()) {
                if ("clearProxyOverride".equals(m.getName()) && m.getParameterTypes().length == 2) {
                    m.invoke(ctrl, done, exec);
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    private static Object getChromiumProxyController() {
        try {
            ClassLoader cl = android.webkit.WebView.getWebViewClassLoader();
            if (cl == null) return null;
            Class<?> glue = Class.forName("org.chromium.support_lib_glue.SupportLibReflectionUtil", false, cl);
            Method create = glue.getDeclaredMethod("createWebViewProviderFactory");
            Object invHandler = create.invoke(null);
            if (!(invHandler instanceof java.lang.reflect.InvocationHandler)) return null;
            java.lang.reflect.InvocationHandler ih = (java.lang.reflect.InvocationHandler) invHandler;
            // Invoke getProxyController() through the boundary InvocationHandler
            Method dummy = FactoryStub.class.getMethod("getProxyController");
            Object proxyHandler = ih.invoke(null, dummy, null);
            if (proxyHandler instanceof java.lang.reflect.InvocationHandler) {
                return Proxy.newProxyInstance(YtmVpn.class.getClassLoader(),
                        new Class<?>[]{ProxyCtrlStub.class}, (java.lang.reflect.InvocationHandler) proxyHandler);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private interface FactoryStub {
        Object getProxyController();
    }

    private interface ProxyCtrlStub {
        void setProxyOverride(String[][] proxyRules, String[] bypassRules, Runnable listener, java.util.concurrent.Executor executor);
        void setProxyOverride(String[][] proxyRules, String[] bypassRules, Runnable listener, java.util.concurrent.Executor executor, boolean reverseBypass);
        void clearProxyOverride(Runnable listener, java.util.concurrent.Executor executor);
    }
}
