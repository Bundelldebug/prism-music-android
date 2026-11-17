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
            int n;
            while ((n = s.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
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

    /** "New version" dialog: update now (download + install) or later. */
    static void offer(Activity a, Release r) {
        String notes = r.notes == null ? "" : r.notes.replaceAll("(?m)^#+\\s*", "").trim();
        if (notes.length() > 600) notes = notes.substring(0, 600) + "…";
        new AlertDialog.Builder(a, Ui.dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle(a.getString(R.string.upd_available, r.version))
                .setMessage(notes)
                .setPositiveButton(R.string.upd_now, (d, w) -> install(a, r))
                .setNegativeButton(R.string.upd_later, null)
                .show();
    }

    private static long pending = -1;

    /** Downloads the release APK, then opens the system installer on it. */
    static void install(Activity a, Release r) {
        if (r.apkUrl == null || r.apkUrl.isEmpty()) {
            // no APK attached: the release page still lets the user get it
            try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(r.pageUrl))); } catch (Exception ignored) {}
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
        String name = "Prism-" + r.version + ".apk";
        java.io.File old = new java.io.File(a.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), name);
        if (old.exists()) old.delete();
        DownloadManager.Request q = new DownloadManager.Request(Uri.parse(r.apkUrl))
                .setTitle(a.getString(R.string.app_name) + " " + r.version)
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
