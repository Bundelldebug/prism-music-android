package com.oneuibook.prism;

import android.content.Context;
import android.content.SharedPreferences;
import android.icu.util.ULocale;
import android.os.CancellationSignal;
import android.util.SparseArray;
import android.view.translation.TranslationCapability;
import android.view.translation.TranslationContext;
import android.view.translation.TranslationManager;
import android.view.translation.TranslationRequest;
import android.view.translation.TranslationRequestValue;
import android.view.translation.TranslationResponseValue;
import android.view.translation.TranslationSpec;
import android.view.translation.Translator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Lyrics machine translation (9.5.31, off by default). Only lines of lyrics whose source has no translation of its
 * own are translated, into the app's language, and they are labelled as machine translation.
 *  • device — Android's on-device translation (android.view.translation, Android 12+). Whether a phone has a
 *             translation service, and for which languages, depends on the phone; nothing leaves the device.
 *  • deepl / google / papago — the official APIs with the user's own key (stored encrypted, sent only to that service).
 * YouTube Music's in-app lyrics translation has no public API, so it isn't one of the engines.
 */
final class LyricsTranslate {
    private LyricsTranslate() {}

    static final String PREF_ENGINE = "lyr_mt_engine";
    static final String OFF = "off", DEVICE = "device", DEEPL = "deepl", GOOGLE = "google", PAPAGO = "papago";
    static final String[] ENGINES = {OFF, DEVICE, DEEPL, GOOGLE, PAPAGO};
    static final String KEY_DEEPL = "mt_deepl_key", KEY_GOOGLE = "mt_google_key", KEY_PAPAGO_ID = "mt_papago_id", KEY_PAPAGO_SECRET = "mt_papago_secret";

    static String engine(Context c) {
        if (c == null) c = Img.appContext;
        if (c == null) return OFF;
        return MainActivity.prefs(c).getString(PREF_ENGINE, OFF);
    }

    static boolean enabled(Context c) { return !OFF.equals(engine(c)); }

    static String engineName(Context c, String e) {
        switch (e) {
            case DEVICE: return c.getString(R.string.mt_engine_device);
            case DEEPL: return "DeepL";
            case GOOGLE: return "Google Cloud Translation";
            case PAPAGO: return "Papago (NAVER Cloud)";
            default: return c.getString(R.string.mt_engine_off);
        }
    }

    /** "ko" / "en" / "ja" — the app's language (other languages: English). */
    static String targetLang(Context c) {
        String l = "en";
        try { l = c.getResources().getConfiguration().getLocales().get(0).getLanguage(); } catch (Throwable ignored) {}
        return "ko".equals(l) || "ja".equals(l) ? l : "en";
    }

    /** Translates r's lines in place (into {@code Line.trans}); true if anything was translated. Worker thread. */
    static boolean translate(Context c, Lyrics.Result r) {
        if (c == null) c = Img.appContext;
        if (c == null || r == null || r.lines.isEmpty()) return false;
        String e = engine(c), target = targetLang(c);
        // unique lines that aren't already in the target language
        Map<String, String> todo = new LinkedHashMap<>();
        for (Lyrics.Line l : r.lines) {
            String t = l.text == null ? "" : l.text.trim();
            if (t.length() < 2 || alreadyIn(t, target) || !hasLetters(t)) continue;
            todo.put(t, null);
        }
        if (todo.isEmpty()) return false;
        List<String> src = new ArrayList<>(todo.keySet());
        List<String> out;
        try {
            switch (e) {
                case DEVICE: out = device(c, src, target); break;
                case DEEPL: out = deepl(c, src, target); break;
                case GOOGLE: out = google(c, src, target); break;
                case PAPAGO: out = papago(c, src, target); break;
                default: return false;
            }
        } catch (Throwable t) { return false; }
        if (out == null || out.size() != src.size()) return false;
        for (int i = 0; i < src.size(); i++) todo.put(src.get(i), out.get(i));
        boolean any = false;
        for (Lyrics.Line l : r.lines) {
            String t = l.text == null ? "" : l.text.trim();
            String tr = todo.get(t);
            if (tr != null && !tr.trim().isEmpty() && !tr.trim().equalsIgnoreCase(t)) { l.trans = tr.trim(); any = true; }
        }
        if (any) r.transEngine = engineName(c, e);
        return any;
    }

    static boolean alreadyIn(String s, String lang) {
        int letters = 0, hit = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!Character.isLetter(ch)) continue;
            letters++;
            if ("ko".equals(lang) && ch >= 0xAC00 && ch <= 0xD7A3) hit++;
            else if ("ja".equals(lang) && ((ch >= 0x3040 && ch <= 0x30FF) || (ch >= 0x4E00 && ch <= 0x9FFF))) hit++;
            else if ("en".equals(lang) && ch < 0x250) hit++;
        }
        return letters > 0 && hit * 2 > letters;
    }

    private static boolean hasLetters(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) return true;
        return false;
    }

    /** Script of the source text, for the on-device translator (which needs a source language). */
    static String guessLang(List<String> lines) {
        int kana = 0, hangul = 0, han = 0, latin = 0;
        for (String s : lines) for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0x3040 && ch <= 0x30FF) kana++;
            else if (ch >= 0xAC00 && ch <= 0xD7A3) hangul++;
            else if (ch >= 0x4E00 && ch <= 0x9FFF) han++;
            else if (Character.isLetter(ch) && ch < 0x250) latin++;
        }
        if (kana > 0 && kana + han >= latin) return "ja";
        if (hangul > latin && hangul > han) return "ko";
        if (han > latin) return "zh";
        return "en";
    }

    // ---------------------------------------------------------------- on-device (Android 12+)
    /** Does this phone have an on-device translator for source → target? (null: unknown / no service) */
    static boolean deviceSupports(Context c, String source, String target) {
        try {
            TranslationManager tm = c.getSystemService(TranslationManager.class);
            if (tm == null) return false;
            Set<TranslationCapability> caps = tm.getOnDeviceTranslationCapabilities(TranslationSpec.DATA_FORMAT_TEXT, TranslationSpec.DATA_FORMAT_TEXT);
            for (TranslationCapability cap : caps) {
                if (cap.getState() == TranslationCapability.STATE_NOT_AVAILABLE) continue;
                if (cap.getSourceSpec().getLocale().getLanguage().equals(source) && cap.getTargetSpec().getLocale().getLanguage().equals(target)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** True if the phone's translation service offers any language pair at all. */
    static boolean deviceAvailable(Context c) {
        try {
            TranslationManager tm = c.getSystemService(TranslationManager.class);
            if (tm == null) return false;
            return !tm.getOnDeviceTranslationCapabilities(TranslationSpec.DATA_FORMAT_TEXT, TranslationSpec.DATA_FORMAT_TEXT).isEmpty();
        } catch (Throwable t) { return false; }
    }

    private static List<String> device(Context c, List<String> src, String target) throws Exception {
        String source = guessLang(src);
        if (source.equals(target)) return null;
        TranslationManager tm = c.getSystemService(TranslationManager.class);
        if (tm == null) return null;
        TranslationContext tc = new TranslationContext.Builder(new TranslationSpec(new ULocale(source), TranslationSpec.DATA_FORMAT_TEXT),
                new TranslationSpec(new ULocale(target), TranslationSpec.DATA_FORMAT_TEXT)).build();
        final Translator[] tr = {null};
        CountDownLatch made = new CountDownLatch(1);
        tm.createOnDeviceTranslator(tc, Runnable::run, t -> { tr[0] = t; made.countDown(); });
        if (!made.await(8, TimeUnit.SECONDS) || tr[0] == null) return null;
        try {
            List<TranslationRequestValue> vals = new ArrayList<>();
            for (String s : src) vals.add(TranslationRequestValue.forText(s));
            final List<String> out = new ArrayList<>();
            CountDownLatch done = new CountDownLatch(1);
            tr[0].translate(new TranslationRequest.Builder().setTranslationRequestValues(vals).build(), new CancellationSignal(), Runnable::run, resp -> {
                SparseArray<TranslationResponseValue> rv = resp.getTranslationResponseValues();
                for (int i = 0; i < src.size(); i++) {
                    TranslationResponseValue v = rv.get(i);
                    CharSequence t = v == null ? null : v.getText();
                    out.add(t == null ? "" : t.toString());
                }
                done.countDown();
            });
            if (!done.await(20, TimeUnit.SECONDS)) return null;
            return out;
        } finally {
            try { tr[0].destroy(); } catch (Throwable ignored) {}
        }
    }

    // ---------------------------------------------------------------- cloud APIs (user's own keys)
    private static String key(Context c, String k) {
        String v = SecureVault.get(MainActivity.prefs(c), k);
        return v == null ? "" : v.trim();
    }

    private static List<String> deepl(Context c, List<String> src, String target) throws Exception {
        String k = key(c, KEY_DEEPL);
        if (k.isEmpty()) return null;
        String host = k.endsWith(":fx") ? "https://api-free.deepl.com" : "https://api.deepl.com";
        List<String> out = new ArrayList<>();
        for (int i = 0; i < src.size(); i += 50) {
            List<String> part = src.subList(i, Math.min(src.size(), i + 50));
            JSONObject body = new JSONObject().put("text", new JSONArray(part)).put("target_lang", target.toUpperCase(Locale.ROOT));
            JSONObject res = new JSONObject(post(host + "/v2/translate", "application/json", body.toString(), "Authorization", "DeepL-Auth-Key " + k, null, null));
            JSONArray a = res.getJSONArray("translations");
            for (int j = 0; j < a.length(); j++) out.add(a.getJSONObject(j).optString("text"));
        }
        return out;
    }

    private static List<String> google(Context c, List<String> src, String target) throws Exception {
        String k = key(c, KEY_GOOGLE);
        if (k.isEmpty()) return null;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < src.size(); i += 100) {
            List<String> part = src.subList(i, Math.min(src.size(), i + 100));
            JSONObject body = new JSONObject().put("q", new JSONArray(part)).put("target", target).put("format", "text");
            // the key goes in a header, not the URL
            JSONObject res = new JSONObject(post("https://translation.googleapis.com/language/translate/v2", "application/json", body.toString(), "X-Goog-Api-Key", k, null, null));
            JSONArray a = res.getJSONObject("data").getJSONArray("translations");
            for (int j = 0; j < a.length(); j++) out.add(a.getJSONObject(j).optString("translatedText"));
        }
        return out;
    }

    /** NAVER Cloud Papago: one text per call, so the lines go as one newline-joined text (≤ 5000 characters per call). */
    private static List<String> papago(Context c, List<String> src, String target) throws Exception {
        String id = key(c, KEY_PAPAGO_ID), secret = key(c, KEY_PAPAGO_SECRET);
        if (id.isEmpty() || secret.isEmpty()) return null;
        String source = guessLang(src);
        if ("zh".equals(source)) source = "zh-CN";
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < src.size()) {
            StringBuilder sb = new StringBuilder();
            int start = i;
            while (i < src.size() && sb.length() + src.get(i).length() + 1 < 4800) { if (sb.length() > 0) sb.append('\n'); sb.append(src.get(i)); i++; }
            if (i == start) { sb.append(src.get(i), 0, Math.min(4800, src.get(i).length())); i++; }
            String form = "source=" + Spotify.enc(source) + "&target=" + Spotify.enc(target) + "&text=" + Spotify.enc(sb.toString());
            JSONObject res = new JSONObject(post("https://papago.apigw.ntruss.com/nmt/v1/translation", "application/x-www-form-urlencoded; charset=UTF-8", form,
                    "X-NCP-APIGW-API-KEY-ID", id, "X-NCP-APIGW-API-KEY", secret));
            String text = res.getJSONObject("message").getJSONObject("result").optString("translatedText");
            String[] lines = text.split("\n", -1);
            if (lines.length != i - start) return null;   // line count changed: don't misalign translations
            for (String l : lines) out.add(l);
        }
        return out;
    }

    private static String post(String url, String type, String body, String h1, String v1, String h2, String v2) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setInstanceFollowRedirects(false);
        h.setConnectTimeout(6000);
        h.setReadTimeout(15000);
        h.setDoOutput(true);
        h.setRequestMethod("POST");
        h.setRequestProperty("Content-Type", type);
        h.setRequestProperty("User-Agent", "Prism/9.5 (Android)");
        if (h1 != null) h.setRequestProperty(h1, v1);
        if (h2 != null) h.setRequestProperty(h2, v2);
        try {
            try (OutputStream o = h.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
            int code = h.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            return Spotify.read(h.getInputStream());
        } finally { h.disconnect(); }
    }

    /** For the settings screen: is the chosen engine ready (key entered / service present)? */
    static boolean ready(Context c, String e) {
        SharedPreferences p = MainActivity.prefs(c);
        switch (e) {
            case DEVICE: return deviceAvailable(c);
            case DEEPL: return SecureVault.has(p, KEY_DEEPL);
            case GOOGLE: return SecureVault.has(p, KEY_GOOGLE);
            case PAPAGO: return SecureVault.has(p, KEY_PAPAGO_ID) && SecureVault.has(p, KEY_PAPAGO_SECRET);
            default: return true;
        }
    }
}
