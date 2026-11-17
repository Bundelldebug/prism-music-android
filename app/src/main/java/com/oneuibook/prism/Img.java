package com.oneuibook.prism;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Tiny image pipeline: memory LRU + disk cache + de-duplicated network fetches. */
final class Img {
    private Img() {}

    static File dir;
    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(
            (int) Math.min(Runtime.getRuntime().maxMemory() / 6, 96L * 1024 * 1024)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final Map<String, List<Consumer<Bitmap>>> PENDING = new HashMap<>();

    static Context appContext;
    /** Images get their own threads so artwork never queues behind API calls. */
    private static final java.util.concurrent.ExecutorService POOL = java.util.concurrent.Executors.newFixedThreadPool(6);

    static void init(Context c) {
        appContext = c.getApplicationContext();
        dir = new File(c.getCacheDir(), "img");
        dir.mkdirs();
    }

    static Bitmap cached(String url) { return url == null ? null : MEM.get(url); }

    static void load(String url, Consumer<Bitmap> cb) {
        if (url == null || url.isEmpty()) { cb.accept(null); return; }
        Bitmap b = MEM.get(url);
        if (b != null) { cb.accept(b); return; }
        synchronized (PENDING) {
            List<Consumer<Bitmap>> l = PENDING.get(url);
            if (l != null) { l.add(cb); return; }
            l = new ArrayList<>();
            l.add(cb);
            PENDING.put(url, l);
        }
        POOL.execute(() -> {
            Bitmap bm = null;
            if (url.startsWith("content://")) {
                // on-device song: embedded album art via MediaStore thumbnails
                try { bm = appContext.getContentResolver().loadThumbnail(android.net.Uri.parse(url), new android.util.Size(640, 640), null); }
                catch (Exception ignored) {}
                final Bitmap res = bm;
                if (res != null) MEM.put(url, res);
                Spotify.MAIN.post(() -> {
                    List<Consumer<Bitmap>> l;
                    synchronized (PENDING) { l = PENDING.remove(url); }
                    if (l != null) for (Consumer<Bitmap> c : l) c.accept(res);
                });
                return;
            }
            try {
                File f = new File(dir, hash(url));
                if (!f.exists()) {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(20000);
                    File tmp = new File(dir, f.getName() + ".part");
                    try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    tmp.renameTo(f);
                }
                // decode no larger than ~640px: smaller bitmaps upload faster and keep scrolling smooth
                BitmapFactory.Options probe = new BitmapFactory.Options();
                probe.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getAbsolutePath(), probe);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                o.inSampleSize = 1;
                while (Math.max(probe.outWidth, probe.outHeight) / (o.inSampleSize * 2) >= 640) o.inSampleSize *= 2;
                bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            } catch (Exception ignored) {}
            final Bitmap res = bm;
            if (res != null) MEM.put(url, res);
            Spotify.MAIN.post(() -> {
                List<Consumer<Bitmap>> l;
                synchronized (PENDING) { l = PENDING.remove(url); }
                if (l != null) for (Consumer<Bitmap> c : l) c.accept(res);
            });
        });
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** Four representative colours (TL, TR, BL, BR) used for the now-playing gradient. */
    static int[] palette(Bitmap b) {
        int[] out = new int[4];
        if (b == null) { out[0] = 0xFF5B4B8A; out[1] = 0xFF8A4B7A; out[2] = 0xFF3B5B8A; out[3] = 0xFF6A3B6A; return out; }
        Bitmap s = Bitmap.createScaledBitmap(b, 2, 2, true);
        for (int i = 0; i < 4; i++) {
            int c = s.getPixel(i % 2, i / 2);
            float[] hsv = new float[3];
            Color.colorToHSV(c, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.15f + 0.05f);
            hsv[2] = Math.max(0.28f, Math.min(0.72f, hsv[2]));
            out[i] = Color.HSVToColor(hsv);
        }
        return out;
    }

    static boolean isDark(Bitmap b) {
        if (b == null) return true;
        Bitmap s = Bitmap.createScaledBitmap(b, 1, 1, true);
        int c = s.getPixel(0, 0);
        return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 < 150;
    }

    /** Rounded, center-cropped artwork with a placeholder and a short fade-in. */
    static class ArtView extends View {
        private Bitmap bmp;
        private String url;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint ph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Matrix m = new Matrix();
        float radius;
        boolean circle;
        int placeholderIcon = Icons.NOTE;
        private long fadeStart;

        ArtView(Context c, float radiusDp) {
            super(c);
            radius = Ui.dpf(radiusDp);
            ph.setColor(Ui.placeholder());
        }

        void setUrl(String u) {
            if (u != null && u.equals(url) && bmp != null) return;
            url = u;
            Bitmap cached = cached(u);
            if (cached != null) { setBitmap(cached, false); return; }
            setBitmap(null, false);
            final String want = u;
            load(u, b -> { if (want != null && want.equals(url)) setBitmap(b, true); });
        }

        void setBitmap(Bitmap b, boolean fade) {
            bmp = b;
            if (b != null) {
                paint.setShader(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                fadeStart = fade ? SystemClock.uptimeMillis() : 0;
            } else paint.setShader(null);
            invalidate();
        }

        Bitmap bitmap() { return bmp; }

        @Override protected void onDraw(Canvas c) {
            r.set(0, 0, getWidth(), getHeight());
            float rad = circle ? getWidth() / 2f : radius;
            float a = 1f;
            if (fadeStart > 0) {
                a = Math.min(1f, (SystemClock.uptimeMillis() - fadeStart) / 220f);
                if (a < 1f) postInvalidateOnAnimation(); else fadeStart = 0;
            }
            if (bmp == null || a < 1f) {
                c.drawRoundRect(r, rad, rad, ph);
                if (bmp == null && placeholderIcon != 0)
                    Icons.draw(c, placeholderIcon, getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()) * 0.36f, Ui.text3());
            }
            if (bmp != null) {
                float sc = Math.max(getWidth() / (float) bmp.getWidth(), getHeight() / (float) bmp.getHeight());
                m.setScale(sc, sc);
                m.postTranslate((getWidth() - bmp.getWidth() * sc) / 2f, (getHeight() - bmp.getHeight() * sc) / 2f);
                paint.getShader().setLocalMatrix(m);
                paint.setAlpha((int) (255 * a));
                c.drawRoundRect(r, rad, rad, paint);
            }
        }
    }
}
