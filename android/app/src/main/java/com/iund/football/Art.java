package com.iund.football;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import static com.iund.football.Match.R;
import static com.iund.football.Match.TAU;
import static com.iund.football.Match.clamp;
import static com.iund.football.Match.rnd;

/**
 * Every bitmap the broadcast shows, rasterised off the GL thread and uploaded once (ports of pitchTex / crowdTex /
 * boardTex / figure in mockup.html). Plane textures are power-of-two so they can be mipmapped on GLES 2.0 GPUs.
 */
final class Art {
    static final int RUNF = 12, WALKF = 8, IDLE = RUNF + WALKF, FRAMES = IDLE + 1, LOOKS_N = 4;
    /** Sprite box in figure units (feet at y = 46), SS px per unit. */
    static final float SW = 24, SH = 48, SS = 2;
    static final String[] SKINS = {"#f1c7a5", "#c68a5d", "#7a4a2c"};
    static final String[][] LOOKS = {{"#f1c7a5", "#2a1a10"}, {"#f1c7a5", "#b88a4a"}, {"#c68a5d", "#111111"}, {"#7a4a2c", "#111111"}};
    static final String[][] ADS = {{"NORTHSTAR BANK", "#0b3d91", "#ffffff"}, {"KICK COLA", "#d61f45", "#ffffff"},
        {"VOLTA ENERGY", "#111111", "#facc15"}, {"ORBIT AIR", "#f4f4f4", "#0b3d91"}, {"FIELDLINE", "#0f7a3a", "#ffffff"},
        {"PENSIVE TYRES", "#facc15", "#111111"}};
    static final double X0 = -62, X1 = 62, Y0 = -40, Y1 = 40, STAND = 34, SC = Math.cos(Math.toRadians(35)), SN = Math.sin(Math.toRadians(35));
    static final Typeface FONT = Typeface.create("sans-serif-condensed", Typeface.BOLD);

    static int col(String hex) { return Color.parseColor(hex); }
    static int col(String hex, double a) { return (col(hex) & 0xffffff) | ((int) Math.round(a * 255) << 24); }
    static int shade(String hex, double f) {
        int c = col(hex);
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    static Bitmap bitmap(int w, int h) { return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); }

    static Paint fill(int c) { Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(c); return p; }

    // ---- static textures

    /** World rect x0..x1 × y0..y1 into a w×h bitmap: the whole pitch, or a sharper crop of one goal end for the replay. */
    static Bitmap pitch(int w, int h, double x0, double x1, double y0, double y1) {
        Bitmap b = bitmap(w, h); Canvas c = new Canvas(b);
        c.scale((float) (w / (x1 - x0)), (float) (-h / (y1 - y0)));
        c.translate((float) -x0, (float) -y1);
        c.drawRect(r(X0, Y0, X1 - X0, Y1 - Y0), fill(col("#47983d")));
        Paint stripe = fill(col("#44943a"));
        for (int i = -2; i < 22; i++) if ((i & 1) != 0) c.drawRect(r(-52.5 + i * 5.25, Y0, 5.25, Y1 - Y0), stripe);
        Paint dim = fill(0x21000000);
        c.drawRect(r(X0, Y0, X1 - X0, -34 - Y0), dim); c.drawRect(r(X0, 34, X1 - X0, Y1 - 34), dim);
        c.drawRect(r(X0, -34, -52.5 - X0, 68), dim); c.drawRect(r(52.5, -34, X1 - 52.5, 68), dim);
        Paint dark = new Paint(); dark.setColor(0x12000000);
        Paint light = new Paint(); light.setColor(0x0dffffff);
        for (int i = (int) ((x1 - x0) * (y1 - y0) * 6); i > 0; i--) {
            float x = (float) R(x0, x1), y = (float) R(y0, y1);
            c.drawRect(x, y, x + 0.12f, y + 0.12f, rnd() < 0.5 ? dark : light);
        }
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(0.12f); line.setColor(0xedffffff);
        Paint spot = fill(Color.WHITE);
        c.drawRect(r(-52.5, -34, 105, 68), line);
        c.drawLine(0, -34, 0, 34, line);
        c.drawCircle(0, 0, 9.15f, line); c.drawCircle(0, 0, 0.15f, spot);
        double a = Math.toDegrees(Math.acos(5.5 / 9.15));
        for (int s = -1; s <= 1; s += 2) {
            c.drawRect(r(s > 0 ? 36 : -52.5, -20.16, 16.5, 40.32), line);
            c.drawRect(r(s > 0 ? 47 : -52.5, -9.16, 5.5, 18.32), line);
            c.drawCircle(s * 41.5f, 0, 0.15f, spot);
            // Arc angles run in world space (y up): same parameterisation as canvas arc(a, b) in the mockup.
            c.drawArc(r(s * 41.5 - 9.15, -9.15, 18.3, 18.3), (float) ((s > 0 ? 180 : 0) - a), (float) (2 * a), false, line);
            for (int t = -1; t <= 1; t += 2) {
                double a0 = Math.toDegrees(Math.atan2(-t, -s));
                c.drawArc(r(s * 52.5 - 1, t * 34 - 1, 2, 2), (float) (a0 - 45), 90, false, line);
            }
        }
        return b;
    }

    static RectF r(double x, double y, double w, double h) { return new RectF((float) x, (float) y, (float) (x + w), (float) (y + h)); }

    /** 48 m of LED board (one cycle of the six ads), repeated along each board by GL_REPEAT. */
    static Bitmap board() {
        int w = 1024, h = 32; float pw = w / 6f;
        Bitmap b = bitmap(w, h); Canvas c = new Canvas(b);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTypeface(FONT); t.setTextSize(20); t.setTextAlign(Paint.Align.CENTER);
        // 8 m × 0.9 m panels are stored at 170×32 px, so the board stretches text 1.67× horizontally: pre-squeeze it.
        t.setTextScaleX(0.6f);
        for (int i = 0; i < 6; i++) {
            c.drawRect(i * pw, 0, (i + 1) * pw, h, fill(col(ADS[i][1])));
            t.setColor(col(ADS[i][2]));
            c.drawText(ADS[i][0], (i + 0.5f) * pw, h / 2f - (t.ascent() + t.descent()) / 2, t);
        }
        c.drawRect(0, h - 2, w, h, fill(0x59000000));
        return b;
    }

    static Bitmap shadow() {
        Bitmap b = bitmap(144, 20);
        new Canvas(b).drawOval(new RectF(96 - 44, 10 - 8.8f, 96 + 44, 10 + 8.8f), fill(0x4d000000));
        return b;
    }

    static Bitmap ball() {
        Bitmap b = bitmap(32, 32);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(11, 11, 18, Color.WHITE, col("#b9bec6"), Shader.TileMode.CLAMP));
        new Canvas(b).drawCircle(16, 16, 15.5f, p);
        return b;
    }

    static Bitmap disc() {
        Bitmap b = bitmap(32, 32);
        new Canvas(b).drawOval(new RectF(1, 1, 31, 31), fill(Color.WHITE));
        return b;
    }

    // ---- per-match textures

    /** w×h bitmap of wm × hm metres of seated crowd, drawn at 8 px/m and scaled to fit. */
    static Bitmap crowd(int w, int h, double wm, double hm, String[] kits, boolean jump) {
        double P = 8, rh = 0.85;
        String[] cols = {"#d9d9d9", "#3b3f4a", "#23395d", "#555555", "#c9b27c", "#2d2d2d", "#6b2020", "#1f3b2a"};
        Bitmap b = bitmap(w, h); Canvas c = new Canvas(b);
        c.scale((float) (w / (wm * P)), (float) (h / (hm * P)));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int row = 0; row * rh < hm; row++) {
            double y = row * rh * P;
            p.setColor(col((row & 1) == 1 ? "#2b2f39" : "#262a33"));
            c.drawRect(r(0, y, wm * P, rh * P), p);
            for (int s = 0; s * 0.55 < wm; s++) {
                if (rnd() < 0.09) continue;
                double px = (s * 0.55 + R(-0.08, 0.08)) * P, up = jump && rnd() < 0.6 ? 0.18 * P : 0, v = rnd();
                p.setColor(col(v < 0.3 ? kits[0] : v < 0.45 ? kits[1] : cols[(int) (rnd() * cols.length)]));
                c.drawRect(r(px - 0.2 * P, y + 0.36 * P - up, 0.4 * P, 0.45 * P), p);
                p.setColor(col(SKINS[(int) (rnd() * 3)]));
                c.drawCircle((float) px, (float) (y + 0.26 * P - up), (float) (0.12 * P), p);
                if (up > 0 && rnd() < 0.5) c.drawRect(r(px - 0.28 * P, y + 0.02 * P - up, 0.06 * P, 0.3 * P), p);
            }
        }
        Paint g = new Paint();
        g.setShader(new LinearGradient(0, 0, 0, (float) (hm * P), new int[]{0x59000000, 0x14000000, 0}, new float[]{0, 0.35f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(r(0, 0, wm * P, hm * P), g);
        return b;
    }

    /** One kit's sprite atlas: FRAMES columns × LOOKS_N rows of SW×SH figure units at SS px/unit. */
    static Bitmap sprites(String[] kit) {
        int fw = (int) (SW * SS), fh = (int) (SH * SS);
        Bitmap b = bitmap(fw * FRAMES, fh * LOOKS_N); Canvas c = new Canvas(b);
        for (int l = 0; l < LOOKS_N; l++) for (int f = 0; f < FRAMES; f++) {
            c.save();
            c.translate(f * fw, l * fh); c.clipRect(0, 0, fw, fh); c.scale(SS, SS);
            figure(c, kit, LOOKS[l][0], LOOKS[l][1], f);
            c.restore();
        }
        return b;
    }

    /** One leg's {thigh, knee flex} in radians (+ = forward) at cycle position u, 0 = foot strike. */
    static double[] gait(double u, boolean run) {
        double ST = run ? 0.38 : 0.6, T0 = run ? 0.45 : 0.32, T1 = run ? -0.55 : -0.3;
        if (u < ST) { double s = u / ST; return new double[]{T0 + (T1 - T0) * s, (run ? 0.3 : 0.08) + (run ? 0.28 : 0.1) * Math.sin(s * Math.PI)}; }
        double s = (u - ST) / (1 - ST), sm = (1 - Math.cos(Math.PI * s)) / 2;
        return new double[]{T1 + (T0 - T1) * sm + (run ? 0.4 : 0.08) * Math.pow(Math.sin(Math.PI * s), 2),
            (run ? 0.3 : 0.08) + (run ? 1.55 : 0.9) * Math.pow(Math.sin(Math.PI * Math.pow(s, 0.75)), 1.4)};
    }

    private static void seg(Canvas c, Paint p, double ax, double ay, double bx, double by, int color, double w) {
        p.setColor(color); p.setStrokeWidth((float) w);
        c.drawLine((float) ax, (float) ay, (float) bx, (float) by, p);
    }

    /** Side-view footballer facing +x in figure units (y down). */
    static void figure(Canvas c, String[] k, String skin, String hair, int fr) {
        boolean run = fr < RUNF, walk = fr >= RUNF && fr < IDLE;
        double u = run ? (double) fr / RUNF : (double) (fr - RUNF) / WALKF, L = 8.6;
        double[][] legs = new double[2][];  // back, ps, kneeX, kneeY, footX, footY
        for (int i = 0; i < 2; i++) {
            boolean back = i == 0;
            double[] g = run || walk ? gait((u + (back ? 0.5 : 0)) % 1, run) : new double[]{back ? -0.1 : 0.1, 0.12};
            double ps = g[0] - g[1], kx = L * Math.sin(g[0]), ky = L * Math.cos(g[0]);
            legs[i] = new double[]{back ? 1 : 0, ps, kx, ky, kx + L * Math.sin(ps), ky + L * Math.cos(ps)};
        }
        // Lowest foot on the ground (stance knee flex gives the bob), plus a short flight phase between running strides.
        double u2 = (u * 2) % 1;
        double hy = 46 - Math.max(legs[0][5], legs[1][5]) - (run && u2 > 0.76 ? 1.1 * Math.sin((u2 - 0.76) / 0.24 * Math.PI) : 0);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
        c.translate(12, (float) hy);
        float lean = (float) Math.toDegrees(run ? 0.14 : walk ? 0.03 : 0);
        c.save(); c.rotate(lean); arm(c, p, true, run, walk, u, k, skin); c.restore();
        for (double[] l : legs) {
            boolean back = l[0] == 1; double f = back ? 0.72 : 1;
            boolean grounded = l[5] + hy > 45.6; double al = grounded ? 0 : clamp(-l[1] * 0.9, -0.3, 1), dx = Math.cos(al), dy = Math.sin(al);
            seg(c, p, 0, 0, l[2], l[3], shade(skin, f), 3.4);
            seg(c, p, l[2], l[3], l[4], l[5], shade(k[2], f), 2.9);
            seg(c, p, l[4] - 0.8 * dx, l[5] - 0.8 * dy, l[4] + 2.4 * dx, l[5] + 2.4 * dy, col(back ? "#000000" : "#161616"), 2.3);
        }
        c.rotate(lean);
        Paint s = fill(col(k[1]));
        c.drawRect(-4, -4.5f, 4, 1.5f, s);
        s.setColor(col(k[0]));
        c.drawRoundRect(new RectF(-4.5f, -18.5f, 4.5f, -3.5f), 2.5f, 2.5f, s);
        s.setColor(0x2e000000);
        c.drawRect(-4.5f, -18.5f, -2.5f, -3.5f, s);
        arm(c, p, false, run, walk, u, k, skin);
        s.setColor(col(skin)); c.drawCircle(0.5f, -22.3f, 3.6f, s);
        s.setColor(col(hair));
        c.drawArc(new RectF(0.3f - 3.7f, -22.9f - 3.7f, 0.3f + 3.7f, -22.9f + 3.7f), 171, 207, false, s);
    }

    private static void arm(Canvas c, Paint p, boolean back, boolean run, boolean walk, double u, String[] k, String skin) {
        double sg = back ? -1 : 1, f = back ? 0.72 : 1;
        double a = run ? -0.6 * Math.cos(u * TAU) * sg + 0.1 : walk ? -0.3 * Math.cos(u * TAU) * sg : 0.08;
        double ex = 5.5 * Math.sin(a), ey = -16 + 5.5 * Math.cos(a), be = a + (run ? 1.45 : walk ? 0.5 : 0.15);
        seg(c, p, 0, -16, ex, ey, shade(skin, f), 2.4);
        seg(c, p, ex, ey, ex + 5 * Math.sin(be), ey + 5 * Math.cos(be), shade(skin, f), 2.2);
        seg(c, p, 0, -16, 2.6 * Math.sin(a), -16 + 2.6 * Math.cos(a), shade(k[0], f), 3);
    }
}
