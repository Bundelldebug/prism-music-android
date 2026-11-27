package com.oneuibook.prism;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * In-app updates from the project's GitHub releases: checks the latest release, offers it, downloads the APK with
 * the system download manager and hands it to the package installer (the user confirms the install there; Android
 * asks once to allow installs from Prism). Releases are signed with the same key, so they install over this one.
 */
final class Updater {
    private Updater() {}

    static final String REPO = "Bundelldebug/prism-music-android";
    static final String PREF_AUTO = "update_auto";
    private static final long DAY = 24L * 60 * 60 * 1000;

    static final class Release {
        String version, notes, apkUrl, pageUrl;
    }

    interface Cb { void done(Release newer, String error); }

    /** Background check, at most once a day, only if the user leaves automatic checks on. */
    static void autoCheck(Activity a) {
        android.content.SharedPreferences p = MainActivity.prefs(a);
        if (!p.getBoolean(PREF_AUTO, true) || "robolectric".equals(android.os.Build.FINGERPRINT)) return;
        long now = System.currentTimeMillis();
        if (now - p.getLong("update_last", 0) < DAY) return;
        p.edit().putLong("update_last", now).apply();
        check((r, err) -> { if (r != null && !a.isFinishing() && !a.isDestroyed()) offer(a, r); });
    }

    /** Fetches the latest release; calls back on the main thread with it if it is newer than this build. */
    static void check(Cb cb) {
        new Thread(() -> {
            Release r = null;
            String err = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "Prism-Android-Updater");
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                JSONObject o = new JSONObject(read(c.getInputStream()));
                String tag = o.optString("tag_name").replaceFirst("^[vV]", "");
                if (newer(tag, MainActivity.VERSION)) {
                    r = new Release();
                    r.version = tag;
                    r.notes = o.optString("body");
                    r.pageUrl = o.optString("html_url");
                    JSONArray as = o.optJSONArray("assets");
                    if (as != null) for (int i = 0; i < as.length(); i++) {
                        JSONObject x = as.optJSONObject(i);
                        if (x != null && x.optString("name").toLowerCase().endsWith(".apk")) { r.apkUrl = x.optString("browser_download_url"); break; }
                    }
                }
            } catch (Exception e) {
                err = String.valueOf(e.getMessage());
            }
            final Release fr = r;
            final String fe = err;
            Spotify.MAIN.post(() -> cb.done(fr, fe));
        }, "prism-update").start();
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream s = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = s.read(buf)) > 0) {
                total += n;
                if (total > 1024 * 1024) throw new IllegalStateException("Response too large");
                b.write(buf, 0, n);
            }
            return b.toString("UTF-8");
        }
    }

    /** Validates that release download URLs strictly belong to this project's GitHub Releases over HTTPS. */
    static boolean isTrustedReleaseUrl(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost();
            if (host == null) return false;
            String h = host.toLowerCase(java.util.Locale.ROOT);
            if ("github.com".equals(h)) {
                String p = u.getPath();
                return p != null && p.toLowerCase(java.util.Locale.ROOT).startsWith("/" + REPO.toLowerCase(java.util.Locale.ROOT) + "/");
            }
            return "objects.githubusercontent.com".equals(h) || "release-assets.githubusercontent.com".equals(h);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Verifies downloaded APK package name and signing certificate SHA-256 against the currently installed app. */
    static boolean verifyDownloadedApk(Context app, Uri apkUri, java.io.File fallbackFile) {
        try {
            android.content.pm.PackageManager pm = app.getPackageManager();
            String path = null;
            if (fallbackFile != null && fallbackFile.isFile()) {
                path = fallbackFile.getAbsolutePath();
            } else if (apkUri != null && "file".equals(apkUri.getScheme())) {
                path = apkUri.getPath();
            }
            if (path == null) return false;
            int flags = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES;
            android.content.pm.PackageInfo arch = pm.getPackageArchiveInfo(path, flags);
            if (arch == null || !app.getPackageName().equals(arch.packageName)) return false;
            android.content.pm.PackageInfo cur = pm.getPackageInfo(app.getPackageName(), flags);
            java.util.Set<String> expected = certDigests(cur);
            java.util.Set<String> actual = certDigests(arch);
            if (expected.isEmpty() || actual.isEmpty()) return false;
            for (String d : actual) {
                if (expected.contains(d)) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static java.util.Set<String> certDigests(android.content.pm.PackageInfo pi) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (pi == null || pi.signingInfo == null) return out;
        android.content.pm.Signature[] sigs = pi.signingInfo.hasMultipleSigners()
                ? pi.signingInfo.getApkContentsSigners()
                : pi.signingInfo.getSigningCertificateHistory();
        if (sigs == null) return out;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            for (android.content.pm.Signature s : sigs) {
                byte[] d = md.digest(s.toByteArray());
                StringBuilder sb = new StringBuilder();
                for (byte b : d) sb.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
                out.add(sb.toString());
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** "9.5.1" > "9.5.0"; non-numeric parts count as 0. */
    static boolean newer(String a, String b) {
        String[] x = a.split("[.\\-+]"), y = b.split("[.\\-+]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (Exception e) { return 0; }
    }

    /** Converts GitHub release Markdown into clean, styled Android text without raw #, **, `, or [link](url) syntax. */
    static CharSequence formatReleaseNotes(String raw) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (raw == null || raw.trim().isEmpty()) return sb;
        String text = raw.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (text.length() > 4000) text = text.substring(0, 4000) + "…";
        String[] lines = text.split("\n");
        boolean lastBlank = true;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                if (!lastBlank && sb.length() > 0) { sb.append('\n'); lastBlank = true; }
                continue;
            }
            // Skip horizontal rules (---, ***, ___)
            if (t.matches("^[-*_]{3,}$")) continue;
            if (sb.length() > 0) sb.append('\n');
            lastBlank = false;

            // Heading: # / ## / ### / ####
            int hLevel = 0;
            while (hLevel < t.length() && t.charAt(hLevel) == '#') hLevel++;
            if (hLevel > 0 && hLevel < t.length() && t.charAt(hLevel) == ' ') {
                String heading = t.substring(hLevel + 1).trim();
                if (sb.length() > 1 && sb.charAt(sb.length() - 2) != '\n') sb.append('\n');
                int start = sb.length();
                appendInlineMarkdown(sb, heading);
                int end = sb.length();
                if (end > start) {
                    sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new android.text.style.RelativeSizeSpan(hLevel <= 2 ? 1.12f : 1.05f), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                continue;
            }

            // Bullet list item: "- ", "* ", "+ " (supports nested indentation)
            int indent = 0;
            while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) indent++;
            String afterIndent = line.substring(indent);
            if (afterIndent.length() >= 2
                    && (afterIndent.charAt(0) == '-' || afterIndent.charAt(0) == '*' || afterIndent.charAt(0) == '+')
                    && afterIndent.charAt(1) == ' ') {
                boolean nested = indent >= 2;
                int start = sb.length();
                sb.append(nested ? "◦  " : "•  ");
                appendInlineMarkdown(sb, afterIndent.substring(2).trim());
                int end = sb.length();
                int lead = Ui.dp(nested ? 18 : 6);
                int rest = Ui.dp(nested ? 28 : 16);
                sb.setSpan(new android.text.style.LeadingMarginSpan.Standard(lead, rest), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }

            // Numbered list item: "1. ..."
            java.util.regex.Matcher numM = java.util.regex.Pattern.compile("^(\\d+)[.)]\\s+(.*)$").matcher(afterIndent);
            if (numM.matches()) {
                int start = sb.length();
                sb.append(numM.group(1)).append(". ");
                appendInlineMarkdown(sb, numM.group(2).trim());
                int end = sb.length();
                sb.setSpan(new android.text.style.LeadingMarginSpan.Standard(Ui.dp(4), Ui.dp(18)), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }

            // Blockquote: "> ..."
            if (t.startsWith("> ")) {
                int start = sb.length();
                appendInlineMarkdown(sb, t.substring(2).trim());
                int end = sb.length();
                sb.setSpan(new android.text.style.LeadingMarginSpan.Standard(Ui.dp(12)), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.ITALIC), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }

            appendInlineMarkdown(sb, t);
        }
        return sb;
    }

    /** Parses inline Markdown (**bold**, __bold__, *italic*, `code`, ~~strike~~, [label](url)) into spans. */
    private static void appendInlineMarkdown(android.text.SpannableStringBuilder sb, String s) {
        int i = 0, n = s.length();
        while (i < n) {
            char ch = s.charAt(i);
            // Escaped punctuation \* \_ \` etc.
            if (ch == '\\' && i + 1 < n) {
                sb.append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            // Link [label](url) -> label
            if (ch == '[') {
                int closeBr = s.indexOf(']', i + 1);
                if (closeBr > i + 1 && closeBr + 1 < n && s.charAt(closeBr + 1) == '(') {
                    int closePar = s.indexOf(')', closeBr + 2);
                    if (closePar > closeBr + 2) {
                        int st = sb.length();
                        appendInlineMarkdown(sb, s.substring(i + 1, closeBr));
                        int en = sb.length();
                        if (en > st) sb.setSpan(new android.text.style.UnderlineSpan(), st, en, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i = closePar + 1;
                        continue;
                    }
                }
            }
            // Inline code `...`
            if (ch == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i + 1) {
                    int st = sb.length();
                    sb.append(s, i + 1, end);
                    int en = sb.length();
                    sb.setSpan(new android.text.style.TypefaceSpan("monospace"), st, en, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new android.text.style.RelativeSizeSpan(0.92f), st, en, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            // Bold **...** or __...__
            if ((ch == '*' || ch == '_') && i + 1 < n && s.charAt(i + 1) == ch) {
                String delim = ch == '*' ? "**" : "__";
                int end = s.indexOf(delim, i + 2);
                if (end > i + 2) {
                    int st = sb.length();
                    appendInlineMarkdown(sb, s.substring(i + 2, end));
                    int en = sb.length();
                    if (en > st) sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, en, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            // Strikethrough ~~...~~
            if (ch == '~' && i + 1 < n && s.charAt(i + 1) == '~') {
                int end = s.indexOf("~~", i + 2);
                if (end > i + 2) {
                    int st = sb.length();
                    appendInlineMarkdown(sb, s.substring(i + 2, end));
                    int en = sb.length();
                    if (en > st) sb.setSpan(new android.text.style.StrikethroughSpan(), st, en, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            // Italic *...* (only when not surrounded by spaces)
            if (ch == '*' && i + 1 < n && s.charAt(i + 1) != ' ') {
                int end = s.indexOf('*', i + 1);
                if (end > i + 1 && s.charAt(end - 1) != ' ') {
                    int st = sb.length();
                    appendInlineMarkdown(sb, s.substring(i + 1, end));
                    int en = sb.length();
                    if (en > st) sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.ITALIC), st, en, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            sb.append(ch);
            i++;
        }
    }

    /** "New version" dialog: update now (download + install) or later, with formatted release notes. */
    static void offer(Activity a, Release r) {
        CharSequence notes = formatReleaseNotes(r.notes);
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        android.widget.TextView tv = new android.widget.TextView(a);
        tv.setText(notes);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setLineSpacing(0f, 1.32f);
        tv.setTextColor(Ui.dark ? 0xFFEAEAF0 : 0xFF1C1C1E);
        tv.setPadding(Ui.dp(24), Ui.dp(12), Ui.dp(24), Ui.dp(8));
        sv.addView(tv);
        new AlertDialog.Builder(a, Ui.dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle(a.getString(R.string.upd_available, r.version))
                .setView(sv)
                .setPositiveButton(R.string.upd_now, (d, w) -> install(a, r))
                .setNegativeButton(R.string.upd_later, null)
                .show();
    }

    private static long pending = -1;

    /** Downloads the release APK, verifies package + signing certificate, then opens the system installer. */
    static void install(Activity a, Release r) {
        if (!isTrustedReleaseUrl(r.apkUrl)) {
            String safePage = isTrustedReleaseUrl(r.pageUrl) ? r.pageUrl : ("https://github.com/" + REPO + "/releases");
            try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(safePage))); } catch (Exception ignored) {}
            return;
        }
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            // Android asks once per app: "allow installs from this source"
            Toast.makeText(a, R.string.upd_allow, Toast.LENGTH_LONG).show();
            try {
                a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            } catch (Exception ignored) {}
            return;
        }
        DownloadManager dm = a.getSystemService(DownloadManager.class);
        if (dm == null) { Toast.makeText(a, R.string.upd_failed, Toast.LENGTH_LONG).show(); return; }
        String safeVer = r.version == null ? "latest" : r.version.replaceAll("[^A-Za-z0-9._-]", "");
        String name = "Prism-" + safeVer + ".apk";
        final java.io.File destFile = new java.io.File(a.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), name);
        if (destFile.exists()) destFile.delete();
        DownloadManager.Request q = new DownloadManager.Request(Uri.parse(r.apkUrl))
                .setTitle(a.getString(R.string.app_name) + " " + safeVer)
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(a, android.os.Environment.DIRECTORY_DOWNLOADS, name);
        pending = dm.enqueue(q);
        Toast.makeText(a, R.string.upd_downloading, Toast.LENGTH_SHORT).show();
        Context app = a.getApplicationContext();
        BroadcastReceiver done = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != pending) return;
                try { app.unregisterReceiver(this); } catch (Exception ignored) {}
                Uri apk = dm.getUriForDownloadedFile(id);
                if (apk == null) { Toast.makeText(app, R.string.upd_failed, Toast.LENGTH_LONG).show(); return; }
                if (!verifyDownloadedApk(app, apk, destFile)) {
                    try { destFile.delete(); } catch (Exception ignored) {}
                    Toast.makeText(app, "업데이트 APK 서명 검증에 실패해 설치를 중단했습니다.", Toast.LENGTH_LONG).show();
                    return;
                }
                Intent in = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(apk, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                try { app.startActivity(in); } catch (Exception e) { Toast.makeText(app, R.string.upd_failed, Toast.LENGTH_LONG).show(); }
            }
        };
        // the download manager is a system app, so its broadcast needs an exported receiver
        app.registerReceiver(done, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);
    }
}
