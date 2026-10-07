package com.iund.football;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * One textured-quad shader and a batched vertex array. Vertices carry clip-space (x, y, z, w), so world planes
 * get perspective-correct texturing from the GPU; screen-space quads use w = 1. Colours are premultiplied
 * (Android uploads bitmaps premultiplied), blended with ONE, ONE_MINUS_SRC_ALPHA.
 */
final class Gfx {
    private static final String VS = "attribute vec4 aPos; attribute vec2 aUV; attribute vec4 aCol;"
        + "varying vec2 vUV; varying vec4 vCol;"
        + "void main() { gl_Position = aPos; vUV = aUV; vCol = aCol; }";
    private static final String FS = "precision mediump float; uniform sampler2D uTex; varying vec2 vUV; varying vec4 vCol;"
        + "void main() { gl_FragColor = texture2D(uTex, vUV) * vCol; }";
    private static final int F = 10, MAX = 6 * 600;
    private static final int[] ORDER = {0, 1, 2, 1, 3, 2};

    int W, H, white;
    private int program, aPos, aUV, aCol, tex = -1, n;
    private final float[] v = new float[MAX * F];
    private final FloatBuffer buf = ByteBuffer.allocateDirect(MAX * F * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    /** Call on a fresh GL context. */
    void init() {
        program = ShaderUtil.buildProgram(VS, FS);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aUV = GLES20.glGetAttribLocation(program, "aUV");
        aCol = GLES20.glGetAttribLocation(program, "aCol");
        Bitmap b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        white = upload(b, false, false);
    }

    static int upload(Bitmap b, boolean mip, boolean repeat) {
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, mip ? GLES20.GL_LINEAR_MIPMAP_LINEAR : GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, repeat ? GLES20.GL_REPEAT : GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        if (mip) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        b.recycle();
        return id[0];
    }

    static void delete(int... ids) {
        for (int id : ids) if (id != 0) GLES20.glDeleteTextures(1, new int[]{id}, 0);
    }

    void begin(int w, int h, int clear) {
        W = w; H = h;
        GLES20.glViewport(0, 0, w, h);
        GLES20.glClearColor(Color.red(clear) / 255f, Color.green(clear) / 255f, Color.blue(clear) / 255f, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        tex = -1; n = 0;
    }

    void flush() {
        if (n == 0) return;
        buf.position(0);
        buf.put(v, 0, n * F);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        buf.position(0); GLES20.glVertexAttribPointer(aPos, 4, GLES20.GL_FLOAT, false, F * 4, buf); GLES20.glEnableVertexAttribArray(aPos);
        buf.position(4); GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, F * 4, buf); GLES20.glEnableVertexAttribArray(aUV);
        buf.position(6); GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, F * 4, buf); GLES20.glEnableVertexAttribArray(aCol);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, n);
        n = 0;
    }

    /**
     * Quad from four clip-space corners c = {TL, TR, BL, BR} × (x, y, z, w), texture rect u0..u1 × v0..v1,
     * tint argb × alpha (premultiplied here).
     */
    void quad(int t, float[] c, float u0, float v0, float u1, float v1, int argb, float alpha) {
        if (t != tex) { flush(); tex = t; }
        if (n + 6 > MAX) flush();
        float a = Color.alpha(argb) / 255f * alpha, r = Color.red(argb) / 255f * a, g = Color.green(argb) / 255f * a, b = Color.blue(argb) / 255f * a;
        for (int k : ORDER) {
            int o = n++ * F;
            v[o] = c[k * 4]; v[o + 1] = c[k * 4 + 1]; v[o + 2] = c[k * 4 + 2]; v[o + 3] = c[k * 4 + 3];
            v[o + 4] = (k & 1) == 0 ? u0 : u1; v[o + 5] = k < 2 ? v0 : v1;
            v[o + 6] = r; v[o + 7] = g; v[o + 8] = b; v[o + 9] = a;
        }
    }

    /** Quad from four screen-pixel corners (y down) {TLx, TLy, TRx, TRy, BLx, BLy, BRx, BRy}. */
    void quadPx(int t, float[] p, float u0, float v0, float u1, float v1, int argb, float alpha) {
        float[] c = new float[16];
        for (int k = 0; k < 4; k++) {
            c[k * 4] = 2 * p[k * 2] / W - 1; c[k * 4 + 1] = 1 - 2 * p[k * 2 + 1] / H; c[k * 4 + 3] = 1;
        }
        quad(t, c, u0, v0, u1, v1, argb, alpha);
    }

    void rect(int t, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int argb, float alpha) {
        quadPx(t, new float[]{x0, y0, x1, y0, x0, y1, x1, y1}, u0, v0, u1, v1, argb, alpha);
    }

    void image(int t, float x0, float y0, float x1, float y1, float alpha) { rect(t, x0, y0, x1, y1, 0, 0, 1, 1, Color.WHITE, alpha); }

    void fill(float x0, float y0, float x1, float y1, int argb) { rect(white, x0, y0, x1, y1, 0, 0, 1, 1, argb, 1); }

    /** Thick line segment in screen pixels. */
    void line(float ax, float ay, float bx, float by, float w, int argb) {
        float dx = bx - ax, dy = by - ay, l = (float) Math.sqrt(dx * dx + dy * dy);
        if (l < 1e-3f) return;
        float nx = -dy / l * w / 2, ny = dx / l * w / 2;
        quadPx(white, new float[]{ax + nx, ay + ny, bx + nx, by + ny, ax - nx, ay - ny, bx - nx, by - ny}, 0, 0, 1, 1, argb, 1);
    }
}
