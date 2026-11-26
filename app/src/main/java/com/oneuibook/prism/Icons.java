package com.oneuibook.prism;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Hand-written 24x24 vector icons (no third-party assets). */
final class Icons {
    private Icons() {}

    static final int HOME = 1, EXPLORE = 2, LIBRARY = 3, SETTINGS = 4, SEARCH = 5, QUEUE = 6, PROFILE = 7,
            PLAY = 8, PAUSE = 9, NEXT = 10, PREV = 11, STAR = 12, STAR_FILL = 13, MORE = 14, LYRICS = 15,
            SHUFFLE = 16, LIST = 17, CHEVRON = 18, BACK = 19, CHECK = 20, REPEAT = 21, DEVICE = 22, NOTE = 23,
            CLOCK = 24, HEART = 25, HEART_FILL = 26, GLOBE = 27, APPEARANCE = 28, CONTENT = 29, WAVE = 30,
            DOWNLOAD = 31, STORAGE = 32, INFO = 33, LOGOUT = 34, ALBUM = 35, ARTIST = 36, CLOSE = 37,
            REPEAT_ONE = 38, PLAY_SMALL = 39, HEADPHONES = 40, KEY = 41, LINK = 42, CHEVRON_UP = 43, INFINITY = 44, DRAG = 45, SIDEBAR = 46, VOLUME = 47, VOLUME_LOW = 48, OUTPUT = 49, AOD = 50,
            BUDS = 51, EARBUDS = 52, DAC = 53, BUDS4 = 54, BUDS_ON = 55, BUDS2 = 56, BUDS_LIVE = 57,
            VIDEO = 58, RECAP = 59, YTM = 60, SLIDERS = 61, PLAYLIST_GO = 62;

    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final CornerPathEffect ROUND2 = new CornerPathEffect(2.2f);
    private static final CornerPathEffect ROUND1 = new CornerPathEffect(1.2f);
    private static final Path T = new Path(), T2 = new Path();
    private static final RectF R = new RectF();

    static void draw(Canvas c, int id, float cx, float cy, float size, int color) {
        c.save();
        c.translate(cx - size / 2f, cy - size / 2f);
        c.scale(size / 24f, size / 24f);
        P.reset();
        P.setAntiAlias(true);
        P.setColor(color);
        P.setStrokeCap(Paint.Cap.ROUND);
        P.setStrokeJoin(Paint.Join.ROUND);
        render(c, id);
        c.restore();
    }

    private static void stroke(float w) { P.setStyle(Paint.Style.STROKE); P.setStrokeWidth(w); P.setPathEffect(null); }
    private static void fill() { P.setStyle(Paint.Style.FILL); P.setPathEffect(null); }

    private static void render(Canvas c, int id) {
        Path p = T;
        p.reset();
        switch (id) {
            case VOLUME:
            case VOLUME_LOW: {
                // speaker cone, plus sound waves for the loud end
                fill();
                P.setPathEffect(ROUND1);
                p.moveTo(3f, 9f); p.lineTo(7f, 9f); p.lineTo(12f, 4.5f); p.lineTo(12f, 19.5f); p.lineTo(7f, 15f); p.lineTo(3f, 15f); p.close();
                c.drawPath(p, P);
                stroke(1.9f);
                R.set(10.5f, 8.5f, 17.5f, 15.5f);
                c.drawArc(R, -50, 100, false, P);
                if (id == VOLUME) { R.set(10f, 5f, 21.5f, 19f); c.drawArc(R, -52, 104, false, P); }
                break;
            }
            case OUTPUT: {
                // output device: speaker box with a play triangle cut into a ring (media output)
                stroke(1.9f);
                c.drawCircle(12f, 12f, 8.6f, P);
                fill();
                p.moveTo(10f, 8.2f); p.lineTo(16f, 12f); p.lineTo(10f, 15.8f); p.close();
                c.drawPath(p, P);
                break;
            }
            case AOD: {
                // crescent moon + small sparkle (Always-On Display mode)
                stroke(1.8f);
                p.moveTo(18.6f, 14.8f);
                p.cubicTo(16.9f, 18.2f, 12.8f, 20.1f, 8.8f, 18.8f);
                p.cubicTo(4.7f, 17.5f, 2.7f, 13.0f, 4.1f, 8.8f);
                p.cubicTo(5.1f, 5.7f, 7.8f, 3.8f, 10.9f, 3.6f);
                p.cubicTo(9.2f, 6.2f, 9.3f, 9.8f, 11.5f, 12.2f);
                p.cubicTo(13.5f, 14.3f, 16.3f, 15.1f, 18.6f, 14.8f);
                p.close();
                c.drawPath(p, P);
                fill();
                c.drawCircle(18.2f, 6.4f, 1.3f, P);
                break;
            }
            case SIDEBAR: {
                // window with a left pane (sidebar toggle)
                stroke(1.9f);
                R.set(2.8f, 4.6f, 21.2f, 19.4f);
                c.drawRoundRect(R, 3.6f, 3.6f, P);
                c.drawLine(9.2f, 5f, 9.2f, 19f, P);
                break;
            }
            case HOME: {
                fill();
                P.setPathEffect(ROUND1);
                p.moveTo(12f, 2.6f); p.lineTo(22.4f, 11.6f); p.lineTo(19.6f, 11.6f); p.lineTo(19.6f, 21f);
                p.lineTo(14.4f, 21f); p.lineTo(14.4f, 15.2f); p.lineTo(9.6f, 15.2f); p.lineTo(9.6f, 21f);
                p.lineTo(4.4f, 21f); p.lineTo(4.4f, 11.6f); p.lineTo(1.6f, 11.6f); p.close();
                c.drawPath(p, P);
                break;
            }
            case EXPLORE: {
                fill();
                p.setFillType(Path.FillType.EVEN_ODD);
                p.addCircle(12, 12, 10f, Path.Direction.CW);
                p.moveTo(16.6f, 7.4f); p.lineTo(13.9f, 13.9f); p.lineTo(7.4f, 16.6f); p.lineTo(10.1f, 10.1f); p.close();
                c.drawPath(p, P);
                p.setFillType(Path.FillType.WINDING);
                break;
            }
            case LIBRARY: {
                fill();
                R.set(4.5f, 8f, 19.5f, 21.5f);
                p.addRoundRect(R, 2.6f, 2.6f, Path.Direction.CW);
                T2.reset();
                T2.addCircle(10.4f, 17.2f, 2.1f, Path.Direction.CW);
                R.set(11.3f, 10.6f, 12.9f, 17.2f); T2.addRect(R, Path.Direction.CW);
                R.set(11.3f, 10.6f, 15.6f, 12.4f); T2.addRect(R, Path.Direction.CW);
                Path note = new Path(T2);
                note.op(new Path(), Path.Op.UNION);
                p.op(note, Path.Op.DIFFERENCE);
                c.drawPath(p, P);
                R.set(6.5f, 5f, 17.5f, 6.5f); c.drawRoundRect(R, .75f, .75f, P);
                R.set(8.5f, 2.2f, 15.5f, 3.7f); c.drawRoundRect(R, .75f, .75f, P);
                break;
            }
            case SETTINGS: {
                stroke(1.8f);
                int teeth = 12;
                for (int i = 0; i < teeth * 2; i++) {
                    double a = Math.PI * 2 * i / (teeth * 2) - Math.PI / 2;
                    float r = (i % 2 == 0) ? 10.2f : 8.6f;
                    double a1 = a - Math.PI / (teeth * 2) * 0.55, a2 = a + Math.PI / (teeth * 2) * 0.55;
                    float x1 = 12 + (float) Math.cos(a1) * r, y1 = 12 + (float) Math.sin(a1) * r;
                    float x2 = 12 + (float) Math.cos(a2) * r, y2 = 12 + (float) Math.sin(a2) * r;
                    if (i == 0) p.moveTo(x1, y1); else p.lineTo(x1, y1);
                    p.lineTo(x2, y2);
                }
                p.close();
                c.drawPath(p, P);
                for (int i = 0; i < 3; i++) {
                    double a = -Math.PI / 2 + i * Math.PI * 2 / 3 + Math.PI / 3;
                    c.drawLine(12, 12, 12 + (float) Math.cos(a) * 8.2f, 12 + (float) Math.sin(a) * 8.2f, P);
                }
                break;
            }
            case SEARCH: {
                stroke(2.5f);
                c.drawCircle(10.4f, 10.4f, 6.6f, P);
                P.setStrokeWidth(3f);
                c.drawLine(15.4f, 15.4f, 20.6f, 20.6f, P);
                break;
            }
            case QUEUE: {
                stroke(2.1f);
                c.drawLine(3f, 6f, 11.5f, 6f, P);
                c.drawLine(3f, 10.6f, 11.5f, 10.6f, P);
                c.drawLine(3f, 15.2f, 8.5f, 15.2f, P);
                P.setStrokeWidth(2.3f);
                c.drawLine(16.4f, 3.8f, 16.4f, 17.2f, P);
                c.drawLine(16.4f, 3.8f, 20.6f, 5.4f, P);
                fill();
                c.drawCircle(13.9f, 17.6f, 2.8f, P);
                break;
            }
            case PROFILE: {
                stroke(2f);
                c.drawCircle(12, 12, 9.8f, P);
                fill();
                c.drawCircle(12, 9.6f, 3.6f, P);
                R.set(5.2f, 14.6f, 18.8f, 25f);
                p.addOval(R, Path.Direction.CW);
                T2.reset();
                T2.addCircle(12, 12, 8.6f, Path.Direction.CW);
                p.op(T2, Path.Op.INTERSECT);
                c.drawPath(p, P);
                break;
            }
            case PLAY: case PLAY_SMALL: {
                fill();
                P.setPathEffect(ROUND2);
                p.moveTo(6.5f, 3.8f); p.lineTo(20.4f, 12f); p.lineTo(6.5f, 20.2f); p.close();
                c.drawPath(p, P);
                break;
            }
            case PAUSE: {
                fill();
                R.set(5.6f, 4f, 9.9f, 20f); c.drawRoundRect(R, 1.3f, 1.3f, P);
                R.set(14.1f, 4f, 18.4f, 20f); c.drawRoundRect(R, 1.3f, 1.3f, P);
                break;
            }
            case NEXT: case PREV: {
                fill();
                if (id == PREV) { c.scale(-1, 1, 12, 12); }
                P.setPathEffect(ROUND1);
                p.moveTo(2.2f, 5.6f); p.lineTo(12f, 12f); p.lineTo(2.2f, 18.4f); p.close();
                p.moveTo(11.8f, 5.6f); p.lineTo(21.6f, 12f); p.lineTo(11.8f, 18.4f); p.close();
                c.drawPath(p, P);
                break;
            }
            case STAR: case STAR_FILL: {
                if (id == STAR) stroke(1.7f); else fill();
                if (id == STAR_FILL) P.setPathEffect(ROUND1);
                for (int i = 0; i < 10; i++) {
                    double a = -Math.PI / 2 + i * Math.PI / 5;
                    float r = i % 2 == 0 ? 9.6f : 4.1f;
                    float x = 12 + (float) Math.cos(a) * r, y = 12.6f + (float) Math.sin(a) * r;
                    if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                }
                p.close();
                c.drawPath(p, P);
                break;
            }
            case MORE: {
                fill();
                c.drawCircle(4.6f, 12, 1.9f, P);
                c.drawCircle(12, 12, 1.9f, P);
                c.drawCircle(19.4f, 12, 1.9f, P);
                break;
            }
            case LYRICS: {
                stroke(1.7f);
                R.set(3f, 3.6f, 21f, 17f);
                p.addRoundRect(R, 4.5f, 4.5f, Path.Direction.CW);
                c.drawPath(p, P);
                fill();
                p.reset();
                p.moveTo(7.6f, 16.2f); p.lineTo(7.2f, 21f); p.lineTo(12.4f, 16.2f); p.close();
                c.drawPath(p, P);
                c.drawCircle(9.2f, 10.1f, 1.6f, P);
                c.drawCircle(14.8f, 10.1f, 1.6f, P);
                stroke(1.3f);
                c.drawLine(10.6f, 10.1f, 9.6f, 13f, P);
                c.drawLine(16.2f, 10.1f, 15.2f, 13f, P);
                break;
            }
            case SHUFFLE: {
                stroke(1.8f);
                p.moveTo(3f, 17f); p.lineTo(6.5f, 17f); p.cubicTo(11f, 17f, 12.5f, 7f, 17f, 7f); p.lineTo(20.5f, 7f);
                p.moveTo(3f, 7f); p.lineTo(6.5f, 7f); p.cubicTo(8.6f, 7f, 9.9f, 9.2f, 10.9f, 11.2f);
                p.moveTo(13.1f, 12.8f); p.cubicTo(14.1f, 14.8f, 15.4f, 17f, 17f, 17f); p.lineTo(20.5f, 17f);
                c.drawPath(p, P);
                p.reset();
                p.moveTo(18f, 4.4f); p.lineTo(20.8f, 7f); p.lineTo(18f, 9.6f);
                p.moveTo(18f, 14.4f); p.lineTo(20.8f, 17f); p.lineTo(18f, 19.6f);
                c.drawPath(p, P);
                break;
            }
            case LIST: {
                fill();
                c.drawCircle(4.4f, 6f, 1.4f, P); c.drawCircle(4.4f, 12f, 1.4f, P); c.drawCircle(4.4f, 18f, 1.4f, P);
                stroke(1.8f);
                c.drawLine(8.6f, 6f, 20.6f, 6f, P); c.drawLine(8.6f, 12f, 20.6f, 12f, P); c.drawLine(8.6f, 18f, 20.6f, 18f, P);
                break;
            }
            case CHEVRON: {
                stroke(2f);
                p.moveTo(9.2f, 5f); p.lineTo(16f, 12f); p.lineTo(9.2f, 19f);
                c.drawPath(p, P);
                break;
            }
            case BACK: {
                stroke(2.4f);
                p.moveTo(15.2f, 4.6f); p.lineTo(7.8f, 12f); p.lineTo(15.2f, 19.4f);
                c.drawPath(p, P);
                break;
            }
            case CHECK: {
                stroke(2.2f);
                p.moveTo(4.4f, 12.6f); p.lineTo(9.4f, 17.6f); p.lineTo(19.6f, 6.6f);
                c.drawPath(p, P);
                break;
            }
            case REPEAT: case REPEAT_ONE: {
                stroke(1.8f);
                p.moveTo(4f, 12.5f); p.lineTo(4f, 10f); p.cubicTo(4f, 8.3f, 5.3f, 7f, 7f, 7f); p.lineTo(19.5f, 7f);
                p.moveTo(17f, 4.5f); p.lineTo(19.5f, 7f); p.lineTo(17f, 9.5f);
                p.moveTo(20f, 11.5f); p.lineTo(20f, 14f); p.cubicTo(20f, 15.7f, 18.7f, 17f, 17f, 17f); p.lineTo(4.5f, 17f);
                p.moveTo(7f, 14.5f); p.lineTo(4.5f, 17f); p.lineTo(7f, 19.5f);
                c.drawPath(p, P);
                if (id == REPEAT_ONE) {
                    fill();
                    P.setTextSize(7.5f);
                    P.setTextAlign(Paint.Align.CENTER);
                    P.setFakeBoldText(true);
                    c.drawText("1", 12f, 14.6f, P);
                }
                break;
            }
            case DEVICE: {
                stroke(1.8f);
                R.set(5.5f, 2.5f, 18.5f, 21.5f);
                c.drawRoundRect(R, 2.8f, 2.8f, P);
                c.drawCircle(12f, 14.6f, 3.4f, P);
                fill();
                c.drawCircle(12f, 6.8f, 1.3f, P);
                break;
            }
            case NOTE: {
                stroke(2.6f);
                c.drawLine(14.6f, 3.6f, 14.6f, 16.4f, P);
                c.drawLine(14.6f, 3.6f, 19.4f, 5.6f, P);
                fill();
                c.drawCircle(11.4f, 16.8f, 3.4f, P);
                break;
            }
            case CLOCK: {
                stroke(1.8f);
                c.drawCircle(12, 12, 9.2f, P);
                p.moveTo(12f, 6.8f); p.lineTo(12f, 12f); p.lineTo(15.6f, 14.2f);
                c.drawPath(p, P);
                break;
            }
            case HEART: case HEART_FILL: {
                if (id == HEART) stroke(1.8f); else fill();
                p.moveTo(12f, 20.4f);
                p.cubicTo(5f, 15.6f, 2.4f, 12.2f, 2.4f, 8.6f);
                p.cubicTo(2.4f, 5.6f, 4.8f, 3.6f, 7.4f, 3.6f);
                p.cubicTo(9.4f, 3.6f, 11f, 4.8f, 12f, 6.6f);
                p.cubicTo(13f, 4.8f, 14.6f, 3.6f, 16.6f, 3.6f);
                p.cubicTo(19.2f, 3.6f, 21.6f, 5.6f, 21.6f, 8.6f);
                p.cubicTo(21.6f, 12.2f, 19f, 15.6f, 12f, 20.4f);
                p.close();
                c.drawPath(p, P);
                break;
            }
            case GLOBE: {
                stroke(1.7f);
                c.drawCircle(12, 12, 9.2f, P);
                R.set(8f, 2.8f, 16f, 21.2f);
                c.drawOval(R, P);
                c.drawLine(3f, 12f, 21f, 12f, P);
                break;
            }
            case APPEARANCE: {
                stroke(1.7f);
                p.moveTo(14.5f, 3.4f); p.lineTo(20.6f, 9.5f); p.lineTo(11.5f, 18.6f); p.lineTo(5.4f, 12.5f); p.close();
                c.drawPath(p, P);
                fill();
                p.reset();
                p.moveTo(5.6f, 15.2f); p.lineTo(8.8f, 18.4f); p.cubicTo(7.6f, 20.4f, 5f, 21.2f, 3f, 21f);
                p.cubicTo(3.6f, 19.4f, 3.4f, 16.8f, 5.6f, 15.2f); p.close();
                c.drawPath(p, P);
                break;
            }
            case CONTENT: {
                stroke(1.8f);
                R.set(3.4f, 4f, 20.6f, 10.4f); c.drawRoundRect(R, 1.8f, 1.8f, P);
                R.set(3.4f, 13.6f, 20.6f, 20f); c.drawRoundRect(R, 1.8f, 1.8f, P);
                break;
            }
            case WAVE: {
                stroke(1.9f);
                float[] h = {3f, 7f, 11f, 6f, 9f, 4f};
                for (int i = 0; i < h.length; i++) {
                    float x = 3f + i * 3.6f;
                    c.drawLine(x, 12 - h[i] / 2, x, 12 + h[i] / 2, P);
                }
                break;
            }
            case DOWNLOAD: {
                stroke(1.8f);
                c.drawCircle(12, 12, 9.2f, P);
                c.drawLine(12f, 6.8f, 12f, 16f, P);
                p.moveTo(8.4f, 12.6f); p.lineTo(12f, 16.2f); p.lineTo(15.6f, 12.6f);
                c.drawPath(p, P);
                break;
            }
            case STORAGE: {
                fill();
                R.set(2.6f, 11f, 21.4f, 20.4f); c.drawRoundRect(R, 2.6f, 2.6f, P);
                P.setPathEffect(ROUND1);
                p.moveTo(5.4f, 4f); p.lineTo(18.6f, 4f); p.lineTo(21.2f, 10f); p.lineTo(2.8f, 10f); p.close();
                c.drawPath(p, P);
                break;
            }
            case INFO: {
                stroke(1.8f);
                c.drawCircle(12, 12, 9.2f, P);
                P.setStrokeWidth(2.1f);
                c.drawLine(12f, 11f, 12f, 16.6f, P);
                fill();
                c.drawCircle(12f, 7.6f, 1.3f, P);
                break;
            }
            case LOGOUT: {
                stroke(1.8f);
                p.moveTo(10f, 3.6f); p.lineTo(5.6f, 3.6f); p.cubicTo(4.4f, 3.6f, 3.6f, 4.4f, 3.6f, 5.6f);
                p.lineTo(3.6f, 18.4f); p.cubicTo(3.6f, 19.6f, 4.4f, 20.4f, 5.6f, 20.4f); p.lineTo(10f, 20.4f);
                p.moveTo(9.6f, 12f); p.lineTo(20.4f, 12f);
                p.moveTo(16.4f, 8f); p.lineTo(20.4f, 12f); p.lineTo(16.4f, 16f);
                c.drawPath(p, P);
                break;
            }
            case ALBUM: {
                stroke(1.8f);
                c.drawCircle(12, 12, 9.2f, P);
                c.drawCircle(12, 12, 3f, P);
                fill();
                c.drawCircle(12, 12, 0.9f, P);
                break;
            }
            case ARTIST: {
                stroke(1.8f);
                c.drawCircle(12f, 8f, 4.2f, P);
                p.moveTo(4f, 20.6f); p.cubicTo(4.6f, 16.4f, 8f, 14.2f, 12f, 14.2f); p.cubicTo(16f, 14.2f, 19.4f, 16.4f, 20f, 20.6f);
                c.drawPath(p, P);
                break;
            }
            case CLOSE: {
                stroke(2.2f);
                c.drawLine(6f, 6f, 18f, 18f, P);
                c.drawLine(18f, 6f, 6f, 18f, P);
                break;
            }
            case HEADPHONES: {
                stroke(1.8f);
                p.moveTo(4f, 15f); p.lineTo(4f, 12f); p.cubicTo(4f, 7.4f, 7.6f, 3.8f, 12f, 3.8f);
                p.cubicTo(16.4f, 3.8f, 20f, 7.4f, 20f, 12f); p.lineTo(20f, 15f);
                c.drawPath(p, P);
                fill();
                R.set(3f, 13f, 8f, 20.6f); c.drawRoundRect(R, 2f, 2f, P);
                R.set(16f, 13f, 21f, 20.6f); c.drawRoundRect(R, 2f, 2f, P);
                break;
            }
            case KEY: {
                stroke(1.8f);
                c.drawCircle(8f, 15.6f, 4.4f, P);
                p.moveTo(11.2f, 12.4f); p.lineTo(20.4f, 3.4f);
                p.moveTo(16.8f, 7f); p.lineTo(19.4f, 9.6f);
                p.moveTo(14.6f, 9.2f); p.lineTo(16.6f, 11.2f);
                c.drawPath(p, P);
                break;
            }
            case LINK: {
                stroke(1.9f);
                p.moveTo(10.4f, 13.6f); p.cubicTo(11.6f, 15f, 13.8f, 15.2f, 15.2f, 13.8f); p.lineTo(18.6f, 10.4f);
                p.cubicTo(20f, 9f, 20f, 6.8f, 18.6f, 5.4f); p.cubicTo(17.2f, 4f, 15f, 4f, 13.6f, 5.4f); p.lineTo(12.4f, 6.6f);
                p.moveTo(13.6f, 10.4f); p.cubicTo(12.4f, 9f, 10.2f, 8.8f, 8.8f, 10.2f); p.lineTo(5.4f, 13.6f);
                p.cubicTo(4f, 15f, 4f, 17.2f, 5.4f, 18.6f); p.cubicTo(6.8f, 20f, 9f, 20f, 10.4f, 18.6f); p.lineTo(11.6f, 17.4f);
                c.drawPath(p, P);
                break;
            }
            case CHEVRON_UP: {
                stroke(2f);
                c.drawLine(12f, 20f, 12f, 5f, P);
                p.moveTo(5.5f, 11f); p.lineTo(12f, 4.5f); p.lineTo(18.5f, 11f);
                c.drawPath(p, P);
                break;
            }
            case INFINITY: {
                stroke(2f);
                p.moveTo(12f, 12f);
                p.cubicTo(9.5f, 8.5f, 4f, 8.2f, 4f, 12f); p.cubicTo(4f, 15.8f, 9.5f, 15.5f, 12f, 12f);
                p.cubicTo(14.5f, 8.5f, 20f, 8.2f, 20f, 12f); p.cubicTo(20f, 15.8f, 14.5f, 15.5f, 12f, 12f);
                c.drawPath(p, P);
                break;
            }
            case DRAG: {
                stroke(1.8f);
                c.drawLine(5f, 8.5f, 19f, 8.5f, P); c.drawLine(5f, 12f, 19f, 12f, P); c.drawLine(5f, 15.5f, 19f, 15.5f, P);
                break;
            }
            case BUDS: {
                // Official Samsung One UI Galaxy Buds 3 icon:
                // outlined mirrored pair, outer ear-tip dome, vertical blade stem with V-point bottom & inner vent dash
                stroke(1.55f);
                // Left earbud head arc (from right stem junction counterclockwise around top to left stem junction)
                R.set(3.95f, 5.15f, 10.85f, 12.05f);
                c.drawArc(R, 64f, -320f, false, P);
                // Left earbud vertical blade stem (left crease extends slightly into head, V-point bottom)
                p.moveTo(6.5f, 9.7f);
                p.lineTo(6.5f, 19.25f);
                p.lineTo(7.65f, 20.2f);
                p.lineTo(8.8f, 19.25f);
                p.lineTo(8.8f, 11.75f);
                c.drawPath(p, P);
                // Left earbud outer ear-tip dome
                p.reset();
                p.moveTo(4.05f, 8.2f);
                p.cubicTo(1.75f, 8.7f, 1.75f, 11.85f, 3.65f, 12.75f);
                p.cubicTo(4.65f, 13.2f, 5.65f, 12.65f, 6.15f, 11.9f);
                c.drawPath(p, P);
                // Left earbud tiny diagonal vent dash
                c.drawLine(7.85f, 8.05f, 8.75f, 7.25f, P);

                // Right earbud (exact horizontal mirror across x = 12)
                R.set(13.15f, 5.15f, 20.05f, 12.05f);
                c.drawArc(R, 116f, 320f, false, P);
                p.reset();
                p.moveTo(17.5f, 9.7f);
                p.lineTo(17.5f, 19.25f);
                p.lineTo(16.35f, 20.2f);
                p.lineTo(15.2f, 19.25f);
                p.lineTo(15.2f, 11.75f);
                c.drawPath(p, P);
                p.reset();
                p.moveTo(19.95f, 8.2f);
                p.cubicTo(22.25f, 8.7f, 22.25f, 11.85f, 20.35f, 12.75f);
                p.cubicTo(19.35f, 13.2f, 18.35f, 12.65f, 17.85f, 11.9f);
                c.drawPath(p, P);
                c.drawLine(16.15f, 8.05f, 15.25f, 7.25f, P);
                break;
            }
            case BUDS4: {
                // Official Samsung One UI Galaxy Buds 4 icon:
                // outlined single tilted earbud with rounded capsule blade stem, top pill slot & left ear-tip dome
                stroke(1.6f);
                // Main rounded head contour (open where foreground tilted stem crosses)
                p.moveTo(9.7f, 12.75f);
                p.cubicTo(7.45f, 11.35f, 7.35f, 7.35f, 9.45f, 5.45f);
                p.cubicTo(11.65f, 3.55f, 16.45f, 3.65f, 18.35f, 6.55f);
                p.cubicTo(19.85f, 8.85f, 18.95f, 12.25f, 16.05f, 13.45f);
                p.cubicTo(15.25f, 13.8f, 14.35f, 13.95f, 13.65f, 13.9f);
                c.drawPath(p, P);
                // Left ear-tip dome hugging the upper-left cheek
                p.reset();
                p.moveTo(9.45f, 5.45f);
                p.cubicTo(6.35f, 5.15f, 4.65f, 7.45f, 4.95f, 10.05f);
                p.cubicTo(5.2f, 12.0f, 7.15f, 13.15f, 9.55f, 12.75f);
                c.drawPath(p, P);
                // Tilted foreground flat capsule stem + upper pill slot
                c.save();
                c.rotate(24.5f, 11.4f, 14.0f);
                R.set(9.4f, 6.25f, 13.4f, 21.15f);
                c.drawRoundRect(R, 2.0f, 2.0f, P);
                stroke(1.65f);
                c.drawLine(11.4f, 8.55f, 11.4f, 9.55f, P);
                c.restore();
                break;
            }
            case BUDS_ON: {
                // Official Samsung Galaxy Buds On / Able open-ear icon:
                // curved arch bridge + twin open-ear pods (bottom-left vertical notch & right crescent cutout)
                fill();
                // 1) Curved top C-arch bridge
                p.moveTo(4.7f, 10.35f);
                p.cubicTo(7.9f, 5.9f, 13.4f, 2.95f, 17.4f, 3.95f);
                p.cubicTo(20.3f, 4.7f, 21.1f, 8.1f, 20.5f, 11.55f);
                p.lineTo(17.55f, 10.65f);
                p.cubicTo(17.9f, 7.85f, 16.85f, 6.7f, 14.8f, 6.6f);
                p.cubicTo(11.6f, 6.45f, 8.4f, 8.35f, 6.85f, 10.65f);
                p.close();
                c.drawPath(p, P);
                // 2) Left open-ear pod with bottom-left vertical notch
                p.reset();
                p.moveTo(7.25f, 20.35f);
                p.cubicTo(10.05f, 20.25f, 11.55f, 18.25f, 11.45f, 15.6f);
                p.cubicTo(11.35f, 13.0f, 9.65f, 11.35f, 7.25f, 11.55f);
                p.cubicTo(4.75f, 11.75f, 3.35f, 13.45f, 3.85f, 16.05f);
                p.cubicTo(4.15f, 17.85f, 4.8f, 19.35f, 5.95f, 19.95f);
                p.lineTo(5.65f, 17.85f);
                p.cubicTo(5.55f, 17.1f, 6.55f, 17.0f, 6.75f, 17.75f);
                p.lineTo(7.25f, 20.35f);
                p.close();
                c.drawPath(p, P);
                // 3) Right open-ear pod (main rounded body left of crescent cutout)
                p.reset();
                p.moveTo(16.75f, 11.6f);
                p.cubicTo(14.1f, 11.7f, 12.35f, 13.8f, 12.55f, 16.5f);
                p.cubicTo(12.75f, 19.0f, 14.45f, 20.7f, 16.85f, 20.8f);
                p.cubicTo(16.75f, 18.0f, 17.65f, 14.3f, 18.95f, 12.1f);
                p.cubicTo(18.25f, 11.7f, 17.55f, 11.55f, 16.75f, 11.6f);
                p.close();
                c.drawPath(p, P);
                // 4) Right outer curved crescent rim
                p.reset();
                p.moveTo(19.9f, 12.8f);
                p.cubicTo(18.9f, 14.8f, 18.2f, 17.8f, 18.2f, 20.4f);
                p.cubicTo(18.2f, 20.9f, 18.75f, 21.0f, 19.05f, 20.5f);
                p.cubicTo(20.4f, 18.6f, 21.0f, 15.6f, 20.5f, 13.0f);
                p.cubicTo(20.4f, 12.5f, 20.1f, 12.45f, 19.9f, 12.8f);
                p.close();
                c.drawPath(p, P);
                break;
            }
            case BUDS2: {
                // Samsung One UI Galaxy Buds2 / Buds2 Pro / Buds Pro / Buds FE / Buds+ / Buds silhouette:
                // mirrored stemless rounded pebble housings tilted slightly inward + outer silicone ear-tip domes + subtle vent slot
                stroke(1.55f);
                // Left pebble housing
                c.save();
                c.rotate(-14f, 7.6f, 12.1f);
                R.set(4.15f, 7.25f, 10.65f, 16.75f);
                c.drawRoundRect(R, 3.25f, 3.85f, P);
                // Subtle outer acoustic vent dash on upper pebble body
                c.drawLine(7.65f, 10.15f, 7.65f, 11.35f, P);
                c.restore();
                // Left outward silicone ear-tip dome
                p.reset();
                p.moveTo(4.35f, 11.05f);
                p.cubicTo(1.75f, 11.55f, 1.75f, 15.15f, 4.15f, 15.85f);
                p.cubicTo(4.95f, 16.08f, 5.75f, 15.75f, 6.2f, 15.25f);
                c.drawPath(p, P);

                // Right pebble housing (horizontal mirror across x = 12)
                c.save();
                c.rotate(14f, 16.4f, 12.1f);
                R.set(13.35f, 7.25f, 19.85f, 16.75f);
                c.drawRoundRect(R, 3.25f, 3.85f, P);
                c.drawLine(16.35f, 10.15f, 16.35f, 11.35f, P);
                c.restore();
                // Right outward silicone ear-tip dome
                p.reset();
                p.moveTo(19.65f, 11.05f);
                p.cubicTo(22.25f, 11.55f, 22.25f, 15.15f, 19.85f, 15.85f);
                p.cubicTo(19.05f, 16.08f, 18.25f, 15.75f, 17.8f, 15.25f);
                c.drawPath(p, P);
                break;
            }
            case BUDS_LIVE: {
                // Samsung One UI Galaxy Buds Live ("Galaxy Beans") silhouette:
                // mirrored pair of ergonomic vertical kidney-bean bodies with inward concave waist & upper mic dot
                stroke(1.55f);
                // Left bean
                p.moveTo(7.15f, 5.2f);
                p.cubicTo(4.15f, 5.2f, 2.75f, 8.15f, 3.05f, 11.9f);
                p.cubicTo(3.35f, 15.8f, 5.25f, 18.85f, 7.95f, 18.75f);
                p.cubicTo(10.05f, 18.65f, 10.85f, 16.55f, 9.75f, 14.55f);
                p.cubicTo(8.95f, 13.15f, 8.95f, 10.95f, 9.85f, 9.55f);
                p.cubicTo(10.9f, 7.85f, 9.65f, 5.2f, 7.15f, 5.2f);
                p.close();
                c.drawPath(p, P);
                // Right bean (horizontal mirror across x = 12)
                p.reset();
                p.moveTo(16.85f, 5.2f);
                p.cubicTo(19.85f, 5.2f, 21.25f, 8.15f, 20.95f, 11.9f);
                p.cubicTo(20.65f, 15.8f, 18.75f, 18.85f, 16.05f, 18.75f);
                p.cubicTo(13.95f, 18.65f, 13.15f, 16.55f, 14.25f, 14.55f);
                p.cubicTo(15.05f, 13.15f, 15.05f, 10.95f, 14.15f, 9.55f);
                p.cubicTo(13.1f, 7.85f, 14.35f, 5.2f, 16.85f, 5.2f);
                p.close();
                c.drawPath(p, P);
                // Upper acoustic sensor / mic dot on each bean
                fill();
                c.drawCircle(6.55f, 8.35f, 0.9f, P);
                c.drawCircle(17.45f, 8.35f, 0.9f, P);
                break;
            }
            case EARBUDS: {
                // Generic wireless earbuds silhouette: twin circular buds with slender stems
                stroke(1.75f);
                c.drawCircle(7.2f, 8.0f, 3.4f, P);
                c.drawLine(8.6f, 11.2f, 8.6f, 19.2f, P);
                c.drawCircle(16.8f, 8.0f, 3.4f, P);
                c.drawLine(15.4f, 11.2f, 15.4f, 19.2f, P);
                break;
            }
            case DAC: {
                // Direct USB DAC dongle silhouette: plug + amp body + headphone jack
                stroke(1.7f);
                R.set(5.5f, 7.2f, 18.5f, 17.8f);
                c.drawRoundRect(R, 2.8f, 2.8f, P);
                R.set(9.4f, 3.2f, 14.6f, 7.2f);
                c.drawRoundRect(R, 1.1f, 1.1f, P);
                c.drawLine(12f, 17.8f, 12f, 21f, P);
                P.setStyle(Paint.Style.FILL);
                c.drawCircle(12f, 12.5f, 1.5f, P);
                break;
            }
            case VIDEO: {
                // Sleek rounded screen frame with inner play triangle (Switch to video pill)
                stroke(1.8f);
                R.set(2.8f, 4.8f, 21.2f, 19.2f);
                c.drawRoundRect(R, 3.2f, 3.2f, P);
                fill();
                P.setPathEffect(ROUND1);
                p.moveTo(10.0f, 8.8f); p.lineTo(15.6f, 12.0f); p.lineTo(10.0f, 15.2f); p.close();
                c.drawPath(p, P);
                break;
            }
            case RECAP: {
                // Bar-chart + sparkle badge (Recap statistics)
                fill();
                R.set(4.0f, 12.6f, 7.2f, 19.6f); c.drawRoundRect(R, 1.4f, 1.4f, P);
                R.set(10.4f, 7.8f, 13.6f, 19.6f); c.drawRoundRect(R, 1.4f, 1.4f, P);
                R.set(16.8f, 4.4f, 20.0f, 19.6f); c.drawRoundRect(R, 1.4f, 1.4f, P);
                break;
            }
            case YTM: {
                // Concentric disc with play glyph (YouTube Music source icon)
                stroke(1.8f);
                c.drawCircle(12f, 12f, 9.2f, P);
                c.drawCircle(12f, 12f, 5.6f, P);
                fill();
                p.moveTo(10.4f, 9.4f); p.lineTo(15.2f, 12f); p.lineTo(10.4f, 14.6f); p.close();
                c.drawPath(p, P);
                break;
            }
            case SLIDERS: {
                // Horizontal audio tuning faders
                stroke(1.8f);
                c.drawLine(3.5f, 7f, 20.5f, 7f, P);
                c.drawLine(3.5f, 12f, 20.5f, 12f, P);
                c.drawLine(3.5f, 17f, 20.5f, 17f, P);
                fill();
                c.drawCircle(8.5f, 7f, 2.2f, P);
                c.drawCircle(15.5f, 12f, 2.2f, P);
                c.drawCircle(10.5f, 17f, 2.2f, P);
                break;
            }
            case PLAYLIST_GO: {
                // Playlist lines + arrow-right jump indicator
                stroke(1.85f);
                c.drawLine(3.5f, 6.5f, 14.5f, 6.5f, P);
                c.drawLine(3.5f, 11.5f, 12.0f, 11.5f, P);
                c.drawLine(3.5f, 16.5f, 10.5f, 16.5f, P);
                c.drawLine(14.0f, 14.5f, 20.4f, 14.5f, P);
                p.moveTo(17.4f, 11.4f); p.lineTo(20.6f, 14.5f); p.lineTo(17.4f, 17.6f);
                c.drawPath(p, P);
                break;
            }
            default:
                break;
        }
    }

    /** Small reusable icon view; supports an animated swap (scale-through) between two icons. */
    static final class IconView extends View {
        int icon, color;
        float sizeDp;
        private int pendingIcon = -1;
        private float swap = 1f;

        IconView(Context c, int icon, float sizeDp, int color) {
            super(c);
            this.icon = icon;
            this.sizeDp = sizeDp;
            this.color = color;
        }

        void set(int newIcon, boolean animate) {
            if (newIcon == icon && pendingIcon < 0) return;
            if (!animate || !isAttachedToWindow()) { icon = newIcon; pendingIcon = -1; invalidate(); return; }
            pendingIcon = newIcon;
            Ui.animate(0f, 1f, 260, Ui.STANDARD, a -> {
                float t = (float) a.getAnimatedValue();
                if (t >= 0.5f && pendingIcon >= 0) { icon = pendingIcon; pendingIcon = -1; }
                swap = t < 0.5f ? 1f - t * 2f * 0.6f : 0.4f + (t - 0.5f) * 2f * 0.6f;
                invalidate();
            });
        }

        void setColor(int c) { color = c; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float s = Ui.dpf(sizeDp) * swap;
            Icons.draw(c, icon, getWidth() / 2f, getHeight() / 2f, s, color);
        }
    }
}
