package com.iund.football;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import static com.iund.football.Art.col;
import static com.iund.football.Match.clamp;

/**
 * TV graphics (port of macos/Saver/HUD.swift): score bug, LIVE/REPLAY tag, GOAL lower third, half/full-time card
 * and the REPLAY wipe. Each is a small bitmap re-rasterised only when its text changes (the clock: once a second);
 * per frame only positions and alpha move.
 */
final class Hud {
    private final float dp, top;
    private int texBug, texTag, texLower, texCard, texWipe;
    private int bugW, bugH, tagW, tagH, lowerW, lowerH, cardW, cardH, wipeW, wipeH;
    private String bugKey = "", tagKey = "", lowerKey = "", cardKey = "", wipeKey = "";
    private double lowerE, cardE, wipeT = 1, blink;
    private int wipes;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), box = new Paint();
    private static final int NAVY = col("#0a1226", 0.92), RED = col("#d61f45");

    Hud(float density, int topInset) {
        dp = density; top = topInset;
        text.setTypeface(Art.FONT);
    }

    void reset() { bugKey = tagKey = lowerKey = cardKey = wipeKey = ""; }

    /** Texture ids from a lost context must not be deleted, only forgotten. */
    void contextLost() { texBug = texTag = texLower = texCard = texWipe = 0; reset(); }

    private float tw(String s, float size) { text.setTextSize(size); return text.measureText(s); }

    private void txt(Canvas c, String s, float size, int color, float x, float mid, float centreW) {
        text.setTextSize(size); text.setColor(color);
        float w = text.measureText(s);
        c.drawText(s, centreW > 0 ? x + (centreW - w) / 2 : x, mid - (text.ascent() + text.descent()) / 2, text);
    }

    private void rect(Canvas c, float x, float y, float w, float h, int color) {
        box.setColor(color);
        c.drawRect(x, y, x + w, y + h, box);
    }

    private int replace(int old, Bitmap b) {
        Gfx.delete(old);
        return Gfx.upload(b, false, false);
    }

    void draw(Gfx g, Match m, double dt, int disc) {
        float W = g.W, H = g.H;
        float u = (float) clamp(W / dp * 0.0145, 13, 24) * dp, bh = 1.9f * u, pad = 0.55f * u;
        float topY = top + Math.max(0.035f * H, 12 * dp);
        Match.Team t0 = m.teams[0], t1 = m.teams[1];
        int s = (int) m.time; boolean over = m.time >= m.half * 45 * 60;
        String clock = String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60), size = W + "x" + H;

        String bk = t0.def.code + t1.def.code + t0.score + "|" + t1.score + clock + m.added + over + size;
        if (!bk.equals(bugKey)) {
            bugKey = bk;
            String score = t0.score + " – " + t1.score, add = "+" + m.added;
            float c0 = tw(t0.def.code, u), c1 = tw(t1.def.code, u);
            float w0 = pad + 0.68f * u + c0 + pad, w1 = Math.max(2.9f * u, tw(score, u) + 2 * pad), w2 = pad + c1 + 0.68f * u + pad;
            float w3 = Math.max(3 * u, tw(clock, u) + 2 * pad), w4 = over ? tw(add, u) + 2 * pad : 0;
            bugW = (int) Math.ceil(w0 + w1 + w2 + w3 + w4); bugH = (int) Math.ceil(bh);
            Bitmap b = Art.bitmap(bugW, bugH); Canvas c = new Canvas(b);
            float x = 0, mid = bh / 2;
            rect(c, x, 0, w0, bh, NAVY); rect(c, x + pad, mid - 0.575f * u, 0.28f * u, 1.15f * u, col(t0.def.kit[0]));
            txt(c, t0.def.code, u, 0xffffffff, x + pad + 0.68f * u, mid, 0); x += w0;
            rect(c, x, 0, w1, bh, col("#f1f3f8")); txt(c, score, u, col("#0a1226"), x, mid, w1); x += w1;
            rect(c, x, 0, w2, bh, NAVY); txt(c, t1.def.code, u, 0xffffffff, x + pad, mid, 0);
            rect(c, x + pad + c1 + 0.4f * u, mid - 0.575f * u, 0.28f * u, 1.15f * u, col(t1.def.kit[0])); x += w2;
            rect(c, x, 0, w3, bh, col("#1c2a4f")); txt(c, clock, u, 0xffffffff, x, mid, w3); x += w3;
            if (over) { rect(c, x, 0, w4, bh, RED); txt(c, add, u, 0xffffffff, x, mid, w4); }
            texBug = replace(texBug, b);
        }
        g.image(texBug, 0.03f * W, topY, 0.03f * W + bugW, topY + bugH, 1);

        boolean rp = m.phase == Match.Phase.REPLAY;
        String tk = rp + size;
        if (!tk.equals(tagKey)) {
            tagKey = tk;
            String label = rp ? "REPLAY" : "LIVE";
            tagW = (int) Math.ceil(1.55f * u + tw(label, u) + 0.6f * u); tagH = (int) Math.ceil(bh);
            Bitmap b = Art.bitmap(tagW, tagH); Canvas c = new Canvas(b);
            rect(c, 0, 0, tagW, bh, rp ? RED : col("#0a1226", 0.75));
            txt(c, label, u, 0xffffffff, 1.55f * u, bh / 2, 0);
            texTag = replace(texTag, b);
        }
        float tagX = 0.97f * W - tagW;
        g.image(texTag, tagX, topY, tagX + tagW, topY + tagH, 1);
        blink += dt;
        float dot = 0.55f * u, dx = tagX + 0.6f * u, dy = topY + bh / 2 - dot / 2;
        g.rect(disc, dx, dy, dx + dot, dy + dot, 0, 0, 1, 1, rp ? 0xffffffff : col("#e11d48"),
            rp ? 1 : (float) (0.25 + 0.75 * Math.abs(Math.cos(Math.PI * blink / 1.6))));

        String lk = m.banner1 + "|" + m.banner2 + size;
        if (!lk.equals(lowerKey)) {
            lowerKey = lk;
            float fT = 2.3f * u, f1 = 1.35f * u, f2 = u;
            float h1 = f1 * 1.2f, h2 = f2 * 1.2f, bxh = 0.8f * u + h1 + h2;
            float tagWd = tw("GOAL", fT) + 0.9f * u, txtW = Math.max(tw(m.banner1, f1), tw(m.banner2, f2)) + 1.8f * u;
            lowerW = (int) Math.ceil(tagWd + txtW); lowerH = (int) Math.ceil(bxh);
            Bitmap b = Art.bitmap(lowerW, lowerH); Canvas c = new Canvas(b);
            rect(c, 0, 0, tagWd, bxh, RED); txt(c, "GOAL", fT, 0xffffffff, 0, bxh / 2, tagWd);
            rect(c, tagWd, 0, txtW, bxh, col("#0a1226", 0.94));
            txt(c, m.banner1, f1, 0xffffffff, tagWd + 0.9f * u, 0.4f * u + h1 / 2, 0);
            txt(c, m.banner2, f2, 0xccffffff, tagWd + 0.9f * u, bxh - 0.4f * u - h2 / 2, 0);
            texLower = replace(texLower, b);
        }
        lowerE += ((m.bannerOn ? 1 : 0) - lowerE) * (1 - Math.exp(-dt * 8));
        if (lowerE > 0.002) {
            // Narrow (portrait) screens: shrink to fit 94% of the width, as the mockup's max-width does.
            float k = Math.min(1, 0.94f * W / lowerW), lw = lowerW * k, lh = lowerH * k;
            float lx = 0.03f * W - 1.3f * lw * (float) (1 - lowerE), ly = 0.91f * H - lh;
            g.image(texLower, lx, ly, lx + lw, ly + lh, 1);
        }

        String ck = m.cardTitle + t0.def.name + t1.def.name + t0.score + "|" + t1.score + size;
        if (!ck.equals(cardKey)) {
            cardKey = ck;
            float fR = 1.3f * u, hh = 1.7f * u, rh = 0.8f * u + fR * 1.25f;
            String[] names = {t0.def.name.toUpperCase(), t1.def.name.toUpperCase()};
            float w = Math.max(15 * u, Math.max(tw(names[0], fR), tw(names[1], fR)) + 4.5f * u), ch = hh + 2 * rh;
            cardW = (int) Math.ceil(w); cardH = (int) Math.ceil(ch);
            Bitmap b = Art.bitmap(cardW, cardH); Canvas c = new Canvas(b);
            rect(c, 0, 0, w, ch, col("#0a1226", 0.95));
            rect(c, 0, 0, w, hh, col("#1c2a4f"));
            txt(c, m.cardTitle, u, 0xffffffff, 0, hh / 2, w);
            Match.Team[] ts = {t0, t1};
            for (int i = 0; i < 2; i++) {
                float my = hh + rh * (i + 0.5f);
                rect(c, 0.8f * u, my - 0.55f * u, 0.3f * u, 1.1f * u, col(ts[i].def.kit[0]));
                txt(c, names[i], fR, 0xffffffff, 1.7f * u, my, 0);
                String sc = String.valueOf(ts[i].score);
                txt(c, sc, fR, 0xffffffff, w - 0.8f * u - tw(sc, fR), my, 0);
            }
            texCard = replace(texCard, b);
        }
        cardE += ((m.cardOn ? 1 : 0) - cardE) * (1 - Math.exp(-dt * 7));
        if (cardE > 0.002) {
            float cx = (W - cardW) / 2, cy = (H - cardH) / 2 + (float) (1 - cardE) * 0.04f * cardH;
            g.image(texCard, cx, cy, cx + cardW, cy + cardH, (float) cardE);
        }

        if (!size.equals(wipeKey)) {
            wipeKey = size;
            float f = (float) clamp(0.07 * W / dp, 32, 110) * dp;
            text.setTextSize(f);
            wipeW = (int) Math.ceil(tw("R E P L A Y", f)); wipeH = (int) Math.ceil(f * 1.3f);
            Bitmap b = Art.bitmap(wipeW, wipeH);
            txt(new Canvas(b), "R E P L A Y", f, 0xffffffff, 0, wipeH / 2f, wipeW);
            texWipe = replace(texWipe, b);
        }
        if (m.wipes != wipes) { wipes = m.wipes; wipeT = 0; }
        if (wipeT < 0.6) {
            wipeT += dt;
            double tau = Math.min(wipeT / 0.6, 1);
            double k = tau < 0.4 ? -1 + sm(tau / 0.4) : tau < 0.6 ? 0 : sm((tau - 0.6) / 0.4);
            float cx = (float) (W / 2 + k * 1.92 * W), cy = H / 2, hw = 0.8f * W, hh = 0.6f * H, sk = 0.2126f;
            band(g, cx, cy, -hw, hw, hh, sk, col("#0a1226"));
            band(g, cx, cy, -hw, -hw + 0.12f * hw, hh, sk, RED);
            band(g, cx, cy, hw - 0.12f * hw, hw, hh, sk, RED);
            g.image(texWipe, cx - wipeW / 2f, cy - wipeH / 2f, cx + wipeW / 2f, cy + wipeH / 2f, 1);
        }
    }

    private static double sm(double v) { return v * v * (3 - 2 * v); }

    /** Sheared (skewX −12°) band from x = cx+a to cx+b. */
    private static void band(Gfx g, float cx, float cy, float a, float b, float hh, float sk, int color) {
        g.quadPx(g.white, new float[]{cx + a + sk * hh, cy - hh, cx + b + sk * hh, cy - hh, cx + a - sk * hh, cy + hh, cx + b - sk * hh, cy + hh},
            0, 0, 1, 1, color, 1);
    }

}
