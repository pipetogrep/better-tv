package dev.bettertv.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** SF Symbols\u2013style line glyphs (settings gear, home, inputs), drawn in code (no image assets). */
final class Glyphs {
    private Glyphs() { }

    static Bitmap get(String name, int s, int color) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.085f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        float m = s / 2f;
        RectF r = new RectF();
        Path path = new Path();
        switch (name) {
            case "gear":
                p.setStyle(Paint.Style.FILL);
                MainActivity.drawGear(c, m, m, s * 0.3f, color, 0x00000000);
                Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
                hole.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
                c.drawCircle(m, m, s * 0.13f, hole);
                break;
            case "input":  // a screen with an arrow coming in from the left (Inputs)
                r.set(s * 0.3f, s * 0.2f, s * 0.88f, s * 0.8f);
                path.moveTo(s * 0.3f, s * 0.36f);
                path.lineTo(s * 0.3f, s * 0.28f);
                path.quadTo(s * 0.3f, s * 0.2f, s * 0.38f, s * 0.2f);
                path.lineTo(s * 0.8f, s * 0.2f);
                path.quadTo(s * 0.88f, s * 0.2f, s * 0.88f, s * 0.28f);
                path.lineTo(s * 0.88f, s * 0.72f);
                path.quadTo(s * 0.88f, s * 0.8f, s * 0.8f, s * 0.8f);
                path.lineTo(s * 0.38f, s * 0.8f);
                path.quadTo(s * 0.3f, s * 0.8f, s * 0.3f, s * 0.72f);
                path.lineTo(s * 0.3f, s * 0.64f);
                c.drawPath(path, p);
                c.drawLine(s * 0.1f, m, s * 0.6f, m, p);
                c.drawLine(s * 0.47f, s * 0.37f, s * 0.6f, m, p);
                c.drawLine(s * 0.47f, s * 0.63f, s * 0.6f, m, p);
                break;
            case "antenna":  // tuner: a mast with signal arcs
                c.drawLine(m, s * 0.42f, m, s * 0.86f, p);
                c.drawLine(s * 0.36f, s * 0.86f, s * 0.64f, s * 0.86f, p);
                for (int i = 0; i < 2; i++) {
                    float rad = s * (0.14f + 0.14f * i);
                    r.set(m - rad, s * 0.36f - rad, m + rad, s * 0.36f + rad);
                    c.drawArc(r, -150, 120, false, p);
                }
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(m, s * 0.36f, s * 0.06f, p);
                break;
            case "house":  // Home
                path.moveTo(s * 0.16f, s * 0.48f);
                path.lineTo(m, s * 0.16f);
                path.lineTo(s * 0.84f, s * 0.48f);
                c.drawPath(path, p);
                r.set(s * 0.26f, s * 0.42f, s * 0.74f, s * 0.84f);
                c.drawRoundRect(r, s * 0.04f, s * 0.04f, p);
                c.drawLine(m, s * 0.84f, m, s * 0.64f, p);
                break;
            case "hdmi": {  // HDMI port: wide top, tapered lower corners, contact strip
                path.moveTo(s * 0.12f, s * 0.34f);
                path.lineTo(s * 0.88f, s * 0.34f);
                path.lineTo(s * 0.88f, s * 0.52f);
                path.lineTo(s * 0.76f, s * 0.66f);
                path.lineTo(s * 0.24f, s * 0.66f);
                path.lineTo(s * 0.12f, s * 0.52f);
                path.close();
                c.drawPath(path, p);
                p.setStrokeWidth(s * 0.06f);
                c.drawLine(s * 0.3f, s * 0.47f, s * 0.7f, s * 0.47f, p);
                break;
            }
            case "av": {  // composite: three RCA jacks
                for (int i = 0; i < 3; i++) {
                    float x = s * (0.22f + i * 0.28f);
                    c.drawCircle(x, m, s * 0.11f, p);
                    Paint dot = new Paint(p);
                    dot.setStyle(Paint.Style.FILL);
                    c.drawCircle(x, m, s * 0.035f, dot);
                }
                break;
            }
            case "component": {  // component video: three jacks in a triangle
                float[][] at = {{0.5f, 0.3f}, {0.3f, 0.66f}, {0.7f, 0.66f}};
                for (float[] q : at) {
                    c.drawCircle(s * q[0], s * q[1], s * 0.12f, p);
                    Paint dot = new Paint(p);
                    dot.setStyle(Paint.Style.FILL);
                    c.drawCircle(s * q[0], s * q[1], s * 0.035f, dot);
                }
                break;
            }
            case "svideo": {  // S-Video: round mini-DIN with four pins
                c.drawCircle(m, m, s * 0.32f, p);
                Paint dot = new Paint(p);
                dot.setStyle(Paint.Style.FILL);
                float[][] pins = {{0.38f, 0.42f}, {0.62f, 0.42f}, {0.36f, 0.6f}, {0.64f, 0.6f}};
                for (float[] q : pins) c.drawCircle(s * q[0], s * q[1], s * 0.04f, dot);
                r.set(s * 0.45f, s * 0.68f, s * 0.55f, s * 0.76f);
                c.drawRect(r, dot);
                break;
            }
            case "vga": {  // VGA / DVI: D-sub with rows of pins
                path.moveTo(s * 0.1f, s * 0.3f);
                path.lineTo(s * 0.9f, s * 0.3f);
                path.lineTo(s * 0.8f, s * 0.7f);
                path.lineTo(s * 0.2f, s * 0.7f);
                path.close();
                c.drawPath(path, p);
                Paint dot = new Paint(p);
                dot.setStyle(Paint.Style.FILL);
                for (int row = 0; row < 2; row++) {
                    for (int i = 0; i < 5 - row; i++) {
                        c.drawCircle(s * (0.3f + row * 0.05f + i * 0.1f), s * (0.44f + row * 0.13f), s * 0.025f, dot);
                    }
                }
                break;
            }
            case "displayport": {  // DisplayPort: rectangle with one cut corner
                path.moveTo(s * 0.12f, s * 0.32f);
                path.lineTo(s * 0.88f, s * 0.32f);
                path.lineTo(s * 0.88f, s * 0.68f);
                path.lineTo(s * 0.26f, s * 0.68f);
                path.lineTo(s * 0.12f, s * 0.54f);
                path.close();
                c.drawPath(path, p);
                p.setStrokeWidth(s * 0.06f);
                c.drawLine(s * 0.32f, s * 0.5f, s * 0.72f, s * 0.5f, p);
                break;
            }
            case "scart": {  // SCART: wide connector with one slanted corner
                path.moveTo(s * 0.08f, s * 0.34f);
                path.lineTo(s * 0.92f, s * 0.34f);
                path.lineTo(s * 0.92f, s * 0.66f);
                path.lineTo(s * 0.2f, s * 0.66f);
                path.lineTo(s * 0.08f, s * 0.5f);
                path.close();
                c.drawPath(path, p);
                Paint dot = new Paint(p);
                dot.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 6; i++) c.drawCircle(s * (0.28f + i * 0.11f), m, s * 0.025f, dot);
                break;
            }
            case "usb": {  // USB trident
                c.drawLine(m, s * 0.86f, m, s * 0.18f, p);
                Paint fill = new Paint(p);
                fill.setStyle(Paint.Style.FILL);
                path.moveTo(m, s * 0.1f);
                path.lineTo(m - s * 0.08f, s * 0.22f);
                path.lineTo(m + s * 0.08f, s * 0.22f);
                path.close();
                c.drawPath(path, fill);
                c.drawCircle(m, s * 0.86f, s * 0.07f, fill);
                c.drawLine(m, s * 0.64f, s * 0.28f, s * 0.48f, p);
                c.drawLine(s * 0.28f, s * 0.48f, s * 0.28f, s * 0.38f, p);
                c.drawCircle(s * 0.28f, s * 0.33f, s * 0.055f, fill);
                c.drawLine(m, s * 0.54f, s * 0.72f, s * 0.4f, p);
                c.drawLine(s * 0.72f, s * 0.4f, s * 0.72f, s * 0.32f, p);
                r.set(s * 0.66f, s * 0.22f, s * 0.78f, s * 0.32f);
                c.drawRect(r, fill);
                break;
            }
            default:
                break;
        }
        return b;
    }
}
