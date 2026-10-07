package com.iund.football;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLSurfaceView;
import android.util.Log;

import java.util.Arrays;
import java.util.Comparator;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import static com.iund.football.Art.SC;
import static com.iund.football.Art.SN;
import static com.iund.football.Art.STAND;
import static com.iund.football.Match.TAU;
import static com.iund.football.Match.clamp;
import static com.iund.football.Match.hyp;
import static com.iund.football.Match.sgn;

/**
 * The broadcast as a GLES 2.0 renderer (port of macos/Saver/Broadcast.swift and render() in mockup.html).
 * Shared by the live wallpaper engine and MainActivity's GLSurfaceView preview. Bitmaps are built on background
 * threads and only uploaded here; nothing is rasterised per frame except the HUD text (at most once a second).
 */
final class Broadcast implements GLSurfaceView.Renderer {
    static final String TAG = "Football";

    /** A textured world rectangle: texture (0,0) at O, (1,0) at O+U, (0,1) at O+V. type 0 main, 1 end stand/board, 2 replay crop. */
    private static final class Plane {
        final double[] O, U, V; final int type, side, role; final double len;
        Plane(int role, int type, int side, double len, double[] O, double[] U, double[] V) {
            this.role = role; this.type = type; this.side = side; this.len = len; this.O = O; this.U = U; this.V = V;
        }
        boolean shown(double s) { return type == 0 ? s == 0 : type == 1 ? s == 0 || s == side : s == side; }
        double[][] corners() {
            return new double[][]{O, add(O, U), add(O, V), add(add(O, U), V)};
        }
    }

    private static final int CROWD_FAR = 0, CROWD_END = 1, PITCH = 2, CLOSE = 3, BOARD = 4;
    private static final Plane[] PLANES = {
        new Plane(CROWD_FAR, 0, 0, 0, v(-75, 40 + STAND * SC, STAND * SN), v(150, 0, 0), v(0, -SC * STAND, -SN * STAND)),
        new Plane(CROWD_END, 1, -1, 0, v(-62 - STAND * SC, -40, STAND * SN), v(0, 80, 0), v(SC * STAND, 0, -SN * STAND)),
        new Plane(CROWD_END, 1, 1, 0, v(62 + STAND * SC, 40, STAND * SN), v(0, -80, 0), v(-SC * STAND, 0, -SN * STAND)),
        new Plane(PITCH, 0, 0, 0, v(Art.X0, Art.Y1, 0), v(Art.X1 - Art.X0, 0, 0), v(0, Art.Y0 - Art.Y1, 0)),
        new Plane(CLOSE, 2, -1, 0, v(-62, 40, 0), v(22, 0, 0), v(0, -68, 0)),
        new Plane(CLOSE, 2, 1, 0, v(40, 40, 0), v(22, 0, 0), v(0, -68, 0)),
        new Plane(BOARD, 0, 0, 120, v(-60, 37.5, 0.9), v(120, 0, 0), v(0, 0, -0.9)),
        new Plane(BOARD, 1, -1, 48, v(-57, -24, 0.9), v(0, 48, 0), v(0, 0, -0.9)),
        new Plane(BOARD, 1, 1, 48, v(57, 24, 0.9), v(0, -48, 0), v(0, 0, -0.9)),
    };

    private static double[] v(double x, double y, double z) { return new double[]{x, y, z}; }
    private static double[] add(double[] a, double[] b) { return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    private static double[] unit(double[] a) { double l = Math.sqrt(dot(a, a)); return new double[]{a[0] / l, a[1] / l, a[2] / l}; }
    private static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }

    private final Gfx g = new Gfx();
    private final Hud hud;
    private Match match = new Match(true), pending;
    private volatile Bitmap[] staticBmps, matchBmps;
    private volatile Match builtFor;
    private int texPitch, texBoard, texShadow, texBall, texDisc;
    private final int[] texClose = new int[2], texSprites = new int[5], texCrowdFar = new int[2], texCrowdEnd = new int[2];
    private boolean staticReady, artReady;
    private int W, H;
    private double F0 = 1, F = 1, camX, camY, camZ = 1, camRY, acc, boardT;
    private int boardOff;
    private double[] C = {0, -60, 25}, fwd = {0, 1, 0}, right = {1, 0, 0}, up = {0, 0, 1};
    private long lastNanos;

    Broadcast(Context ctx) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int top = 0;
        int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0 && android.os.Build.VERSION.SDK_INT >= 14) top = ctx.getResources().getDimensionPixelSize(id);
        hud = new Hud(density, top);
    }

    // ---- background art

    private void buildStatic() {
        new Thread(new Runnable() {
            @Override public void run() {
                staticBmps = new Bitmap[]{Art.pitch(2048, 1024, Art.X0, Art.X1, Art.Y0, Art.Y1),
                    Art.pitch(512, 2048, -62, -40, -28, 40), Art.pitch(512, 2048, 40, 62, -28, 40),
                    Art.board(), Art.shadow(), Art.ball(), Art.disc()};
            }
        }, "FootballStaticArt").start();
    }

    private void buildArt(final Match m) {
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap[] b = new Bitmap[9];
                for (int i = 0; i < 5; i++) b[i] = Art.sprites(m.kits[i]);
                String[] k2 = {m.kits[0][0], m.kits[1][0]};
                b[5] = Art.crowd(1024, 256, 150, STAND, k2, false);
                b[6] = Art.crowd(1024, 256, 150, STAND, k2, true);
                b[7] = Art.crowd(512, 256, 80, STAND, k2, false);
                b[8] = Art.crowd(512, 256, 80, STAND, k2, true);
                builtFor = m;
                matchBmps = b;
            }
        }, "FootballMatchArt").start();
    }

    private void uploadPending() {
        Bitmap[] s = staticBmps;
        if (s != null) {
            staticBmps = null;
            texPitch = Gfx.upload(s[0], true, false);
            texClose[0] = Gfx.upload(s[1], true, false); texClose[1] = Gfx.upload(s[2], true, false);
            texBoard = Gfx.upload(s[3], true, true);
            texShadow = Gfx.upload(s[4], false, false); texBall = Gfx.upload(s[5], false, false); texDisc = Gfx.upload(s[6], false, false);
            staticReady = true;
        }
        Bitmap[] b = matchBmps;
        if (b != null) {
            matchBmps = null;
            Gfx.delete(texSprites); Gfx.delete(texCrowdFar); Gfx.delete(texCrowdEnd);
            for (int i = 0; i < 5; i++) texSprites[i] = Gfx.upload(b[i], false, false);
            texCrowdFar[0] = Gfx.upload(b[5], true, false); texCrowdFar[1] = Gfx.upload(b[6], true, false);
            texCrowdEnd[0] = Gfx.upload(b[7], true, false); texCrowdEnd[1] = Gfx.upload(b[8], true, false);
            match = builtFor; pending = null; artReady = true;
            hud.reset();
        }
    }

    // ---- GLSurfaceView.Renderer

    @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        // A new context: every texture id from the last one is gone, so rebuild everything.
        g.init();
        staticReady = artReady = false;
        Arrays.fill(texSprites, 0); Arrays.fill(texCrowdFar, 0); Arrays.fill(texCrowdEnd, 0);
        hud.contextLost();
        buildStatic();
        buildArt(pending != null ? pending : match);
        lastNanos = 0;
        Log.i(TAG, "GL context created");
    }

    @Override public void onSurfaceChanged(GL10 unused, int w, int h) {
        W = w; H = h;
        F0 = Math.max(w, h * 1.3) / (2 * Math.tan(Math.toRadians(19)));
        Log.i(TAG, "surface " + w + "x" + h);
    }

    @Override public void onDrawFrame(GL10 unused) {
        uploadPending();
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 1.0 / 60 : Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;
        g.begin(W, H, 0xff6fb3e6);
        if (!staticReady || !artReady || W == 0) { g.flush(); return; }
        acc += dt;
        while (acc >= 1.0 / 60) {
            match.step(1.0 / 60); acc -= 1.0 / 60;
            if (match.finished && pending == null) { pending = new Match(false); buildArt(pending); }
        }
        boardT += dt;
        if (boardT > 10) { boardT = 0; boardOff++; }
        render(dt);
        hud.draw(g, match, dt, texDisc);
        g.flush();
    }

    // ---- camera

    private void basis(double[] c, double[] t) {
        C = c; fwd = unit(sub(t, c)); right = unit(cross(fwd, new double[]{0, 0, 1})); up = cross(right, fwd);
    }

    private boolean inFront() {
        for (Plane p : PLANES) if (p.shown(0)) for (double[] q : p.corners()) if (dot(sub(q, C), fwd) <= 4) return false;
        return true;
    }

    private void view() {
        double s = match.replaySide();
        if (s != 0) { basis(v(s * 31, -5, 2.4), v(s * 53, camRY, 1)); F = F0 * 1.5; return; }
        double[] c = v(camX * 0.55, -60, 25), t = v(camX, camY + 5 - 9 * clamp((double) H / W - 0.7, 0, 1), 0);
        basis(c, t);
        double hi = 1;
        // Keep every plane corner well in front of the lens (w <= 0 breaks perspective texturing).
        for (int i = 0; i < 12 && !inFront(); i++) { hi /= 2; basis(c, v(c[0] + (t[0] - c[0]) * hi, t[1], 0)); }
        F = F0 * camZ;
    }

    /** Screen pixels (y down) and camera depth. */
    private double[] proj(double x, double y, double z) {
        double[] d = {x - C[0], y - C[1], z - C[2]};
        double zc = dot(d, fwd);
        return new double[]{W / 2.0 + F * dot(d, right) / zc, H / 2.0 - F * dot(d, up) / zc, zc};
    }

    /** Clip-space (x, y, 0, w) of a world point: perspective divide left to the GPU. */
    private void clip(double[] p, float[] out, int k) {
        double[] d = sub(p, C);
        out[k * 4] = (float) (2 * F * dot(d, right) / W);
        out[k * 4 + 1] = (float) (2 * F * dot(d, up) / H);
        out[k * 4 + 2] = 0;
        out[k * 4 + 3] = (float) dot(d, fwd);
    }

    // ---- frame

    private void render(double dt) {
        Match M = match; Match.Ball b = M.ball; boolean rp = M.phase == Match.Phase.REPLAY;
        double fx = b.x, fy = b.y, fvx = b.vx;
        if (M.phase == Match.Phase.PAUSE) { fx = 0; fy = 0; fvx = 0; }
        if (M.phase == Match.Phase.GOAL && M.scorer != null) { fx = M.scorer.x; fy = M.scorer.y; fvx = M.scorer.vx; }
        double tx = clamp(fx + fvx * 0.6, -41, 41), ty = clamp(fy * 0.55, -16, 16), k = 1 - Math.exp(-dt * 1.8);
        if (M.snapCam) { M.snapCam = false; camRY = clamp(b.y * 0.3, -2, 2); }
        camRY += (clamp(b.y * 0.3, -2, 2) - camRY) * (1 - Math.exp(-dt * 2));
        if (!rp) { camX += (tx - camX) * k; camY += (ty - camY) * k * 0.6; }
        double zt = rp ? camZ : M.phase == Match.Phase.PAUSE ? 0.8 : (b.z > 3 || hyp(b.vx, b.vy) > 16) ? 0.9 : M.phase == Match.Phase.GOAL ? 1.12 : 1;
        camZ += (zt - camZ) * (1 - Math.exp(-dt * 0.9));
        view();

        double side = M.replaySide();
        int cheer = M.crowdCheering() && (((int) (M.clock * 4)) & 1) == 1 ? 1 : 0;
        float[] c = new float[16];
        for (Plane p : PLANES) {
            if (!p.shown(side)) continue;
            double[][] q = p.corners();
            for (int i = 0; i < 4; i++) clip(q[i], c, i);
            float u0 = 0, u1 = 1;
            int t;
            switch (p.role) {
                case CROWD_FAR: t = texCrowdFar[cheer]; break;
                case CROWD_END: t = texCrowdEnd[cheer]; break;
                case PITCH: t = texPitch; break;
                case CLOSE: t = texClose[p.side < 0 ? 0 : 1]; break;
                default: t = texBoard; u0 = (boardOff % 6) / 6f; u1 = u0 + (float) (p.len / 48); break;
            }
            g.quad(t, c, u0, 0, u1, 1, 0xffffffff, 1);
        }

        // Shadows first (all on the ground), then players, ball and goals far to near.
        Match.Player[] actors = M.actors;
        double[][] pf = new double[actors.length][];
        for (int i = 0; i < actors.length; i++) {
            Match.Player p = actors[i];
            pf[i] = proj(p.x, p.y, 0);
            if (pf[i][2] <= 1) continue;
            float sc = (float) ((pf[i][1] - proj(p.x, p.y, 1.85)[1]) / 43);
            float x = (float) pf[i][0], y = (float) pf[i][1];
            g.image(texShadow, x - 18 * sc, y - 2.5f * sc, x + 18 * sc, y + 2.5f * sc, 1);
        }
        double[] bp = proj(b.x, b.y, b.z + 0.11), bs = proj(b.x, b.y, 0);
        double br = Math.max(1.3, F * 0.11 / bp[2]);
        if (bp[2] > 1) g.rect(texDisc, (float) (bs[0] - 1.1 * br), (float) (bs[1] - 0.4 * br), (float) (bs[0] + 1.1 * br), (float) (bs[1] + 0.4 * br),
            0, 0, 1, 1, 0xff000000, (float) (0.35 / (1 + b.z * 0.4)));

        Integer[] order = new Integer[actors.length + 3];
        final double[] depth = new double[order.length];
        for (int i = 0; i < actors.length; i++) { order[i] = i; depth[i] = pf[i][2]; }
        order[actors.length] = actors.length; depth[actors.length] = bp[2] - 0.3;
        order[actors.length + 1] = actors.length + 1; depth[actors.length + 1] = proj(-53, 0, 0)[2];
        order[actors.length + 2] = actors.length + 2; depth[actors.length + 2] = proj(53, 0, 0)[2];
        Arrays.sort(order, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer z) { return Double.compare(depth[z], depth[a]); }
        });
        for (int idx : order) {
            if (depth[idx] <= 1) continue;
            if (idx < actors.length) drawPlayer(actors[idx], pf[idx], b);
            else if (idx == actors.length) g.image(texBall, (float) (bp[0] - br), (float) (bp[1] - br), (float) (bp[0] + br), (float) (bp[1] + br), 1);
            else drawGoal(idx == actors.length + 1 ? -1 : 1, depth[idx]);
        }
    }

    private void drawPlayer(Match.Player p, double[] pf, Match.Ball b) {
        float sc = (float) ((pf[1] - proj(p.x, p.y, 1.85)[1]) / 43);
        double sp = hyp(p.vx, p.vy), sv = p.vx * right[0] + p.vy * right[1];
        if (sp > 0.6 && Math.abs(sv) > 0.3) p.face = sgn(sv);
        else if (sp <= 0.6) p.face = sgn((b.x - p.x) * right[0] + (b.y - p.y) * right[1]);
        int fr = sp < 0.5 ? Art.IDLE : sp < 2.4 ? Art.RUNF + cyc(p.ph, Art.WALKF) : cyc(p.ph, Art.RUNF);
        float u0 = (float) fr / Art.FRAMES, u1 = (float) (fr + 1) / Art.FRAMES;
        float v0 = (float) p.look / Art.LOOKS_N, v1 = (float) (p.look + 1) / Art.LOOKS_N;
        if (p.face < 0) { float t = u0; u0 = u1; u1 = t; }
        float x = (float) pf[0], y = (float) pf[1], w = Art.SW * sc;
        g.rect(texSprites[p.kit], x - w / 2, y - 46 * sc, x + w / 2, y + 2 * sc, u0, v0, u1, v1, 0xffffffff, 1);
    }

    private static int cyc(double ph, int n) { return (((int) (ph / TAU * n)) % n + n) % n; }

    private void drawGoal(double s, double d) {
        double x0 = s * 52.5, x1 = s * 54.5;
        float net = (float) Math.max(0.6, F * 0.025 / d), bar = (float) Math.max(0.6, F * 0.12 / d);
        for (double y = -3.66; y <= 3.67; y += 0.61) poly(new double[][]{{x0, y, 2.44}, {x1, y, 1.9}, {x1, y, 0}}, net, 0x52ffffff);
        for (double z = 0; z <= 2; z += 0.4)
            poly(new double[][]{{x0, -3.66, Math.min(2.44, z * 1.22)}, {x1, -3.66, z}, {x1, 3.66, z}, {x0, 3.66, Math.min(2.44, z * 1.22)}}, net, 0x4dffffff);
        poly(new double[][]{{x0, -3.66, 0}, {x0, -3.66, 2.44}, {x0, 3.66, 2.44}, {x0, 3.66, 0}}, bar, 0xffffffff);
    }

    private void poly(double[][] pts, float w, int argb) {
        double[] prev = null;
        for (double[] p : pts) {
            double[] q = proj(p[0], p[1], p[2]);
            if (q[2] <= 0.5) return;
            if (prev != null) g.line((float) prev[0], (float) prev[1], (float) q[0], (float) q[1], w, argb);
            prev = q;
        }
    }
}
