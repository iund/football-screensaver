package com.iund.football;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The match simulation, a port of macos/Saver/Match.swift (itself a port of step() in mockup.html).
 * Pure Java, no Android types. Fixed 60 Hz steps.
 */
final class Match {
    static final double G = 9.81, TAU = Math.PI * 2;

    static double rnd() { return Math.random(); }
    static double R(double a, double b) { return a + rnd() * (b - a); }
    static int RI(int n) { return (int) (rnd() * n); }
    static double clamp(double v, double a, double b) { return v < a ? a : v > b ? b : v; }
    static double sgn(double v) { return v < 0 ? -1 : 1; }
    static double hyp(double a, double b) { return Math.sqrt(a * a + b * b); }

    static final class TeamDef {
        final String name, code; final String[] kit;
        TeamDef(String name, String code, String... kit) { this.name = name; this.code = code; this.kit = kit; }
    }

    /** Fictional clubs: shirt, shorts, socks. */
    static final TeamDef[] TEAMS = {
        new TeamDef("Northbridge", "NBR", "#c8102e", "#ffffff", "#c8102e"),
        new TeamDef("Castleford Athletic", "CAS", "#1d4ed8", "#1d4ed8", "#ffffff"),
        new TeamDef("Harbour City", "HBC", "#8fd3f4", "#ffffff", "#0b1f44"),
        new TeamDef("Redmoor Rovers", "RMR", "#f4f4f4", "#111111", "#f4f4f4"),
        new TeamDef("Ashvale United", "ASH", "#fcd116", "#111111", "#fcd116"),
        new TeamDef("Kingsport", "KSP", "#6d28d9", "#ffffff", "#6d28d9"),
        new TeamDef("Westmere Town", "WMT", "#7a1f3d", "#8fd3f4", "#7a1f3d"),
        new TeamDef("Stoneham", "STH", "#f97316", "#111111", "#f97316"),
    };
    static final String[][] GKKITS = {{"#a3e635", "#111111", "#a3e635"}, {"#ec4899", "#111111", "#ec4899"},
        {"#06b6d4", "#0b1f44", "#06b6d4"}, {"#222222", "#222222", "#222222"}, {"#facc15", "#facc15", "#facc15"}};
    static final String[] NAMES = {"Parker", "Okafor", "Lindqvist", "Moreau", "Silva", "Novak", "Brennan", "Haddad", "Kowalski",
        "Tanaka", "Reyes", "Fischer", "Adeyemi", "Costa", "Murphy", "Ivanov", "Duarte", "Walsh", "Kim", "Bauer", "Mensah", "Rossi",
        "Varga", "Holm", "Quinn", "Sato", "Nkemelu", "Petit", "Byrne", "Moreno", "Larsen", "Dvořák"};
    static final int[] NUMS = {1, 2, 5, 6, 3, 7, 8, 4, 11, 9, 10};
    /** 4-4-2 shape for a team attacking +x (metres; pitch 105×68, centre at 0,0). */
    static final double[][] FORM = {{-49, 0}, {-33, -22}, {-36, -8}, {-36, 8}, {-33, 22}, {-15, -23}, {-18, -7}, {-18, 7},
        {-15, 23}, {-3, -7}, {-5, 8}};

    static double cdist(String a, String b) {
        double d = 0;
        for (int i = 1; i < 7; i += 2) {
            double x = Integer.parseInt(a.substring(i, i + 2), 16) - Integer.parseInt(b.substring(i, i + 2), 16);
            d += x * x;
        }
        return Math.sqrt(d);
    }

    static final class Player {
        final int t, i, num, kit, look; final String name; final double wob = R(0, 99);
        double x, y, vx, vy, ph = R(0, TAU), face, stun, tx, ty, spd, decide; boolean tried;
        Player(int t, int i, double x, double y, double face, int num, String name, int kit, int look) {
            this.t = t; this.i = i; this.x = x; this.y = y; this.face = face; this.num = num; this.name = name; this.kit = kit; this.look = look;
        }
    }

    static final class Team {
        final TeamDef def; double dir; int score; final Player[] players;
        Team(TeamDef def, double dir, Player[] players) { this.def = def; this.dir = dir; this.players = players; }
    }

    enum Phase { PLAY, RESTART, GOAL, PAUSE, REPLAY }
    enum Kind { KICKOFF, THROW_IN, GOAL_KICK, CORNER }
    enum Out { GOAL, SAVE, WIDE }

    static final class Shot { final int t; final Out out; Shot(int t, Out out) { this.t = t; this.out = out; } }
    static final class Spot { final Player p; final double x, y; Spot(Player p, double x, double y) { this.p = p; this.x = x; this.y = y; } }

    static final class Restart {
        final Kind kind; final int team; final double x, y; final Player taker; double t, el; final List<Spot> spots = new ArrayList<>();
        Restart(Kind kind, int team, double x, double y, double t, Player taker) {
            this.kind = kind; this.team = team; this.x = x; this.y = y; this.t = t; this.taker = taker;
        }
    }

    static final class Ball {
        double x, y, z, vx, vy, vz, kickT; Player owner, kicker, recv; int last; Shot shot;
    }

    static final int REC = 480, STRIDE = 23 * 5 + 3;

    final Team[] teams; final Player[] all, actors; final Player ref; final Ball ball = new Ball();
    /** home, away, home keeper, away keeper, referee */
    final String[][] kits;
    double time, clock; int half = 1, added; Phase phase = Phase.PLAY;
    Restart rs;
    double gt, bt, celebX, celebY; int gteam; Player scorer;
    /** Set once full time is over; the renderer swaps in a new match. */
    boolean finished;
    // Broadcast cues read by the renderer.
    String banner1 = "", banner2 = "", cardTitle = ""; boolean bannerOn, cardOn, snapCam; int wipes;
    // Goal replay: ring buffer of the last 8 s.
    final float[] rec = new float[REC * STRIDE], live = new float[STRIDE];
    int recN, gi, rpEnd; double rpI; boolean rpWiped;

    Match(boolean first) {
        int a = RI(TEAMS.length), b;
        do b = RI(TEAMS.length); while (b == a || cdist(TEAMS[a].kit[0], TEAMS[b].kit[0]) < 170);
        TeamDef ta = TEAMS[a], tb = TEAMS[b];
        List<String[]> gks = new ArrayList<>();
        for (String[] k : GKKITS) if (cdist(ta.kit[0], k[0]) > 150 && cdist(tb.kit[0], k[0]) > 150) gks.add(k);
        boolean dark = cdist(ta.kit[0], "#111111") < 120 || cdist(tb.kit[0], "#111111") < 120;
        String[] refKit = dark ? new String[]{"#facc15", "#111111", "#111111"} : new String[]{"#111111", "#111111", "#111111"};
        kits = new String[][]{ta.kit, tb.kit, gks.size() > 0 ? gks.get(0) : GKKITS[3], gks.size() > 1 ? gks.get(1) : GKKITS[3], refKit};
        List<String> names = new ArrayList<>(Arrays.asList(NAMES));
        Collections.shuffle(names);
        teams = new Team[]{team(ta, 1, 0, names), team(tb, -1, 1, names)};
        all = new Player[22];
        System.arraycopy(teams[0].players, 0, all, 0, 11);
        System.arraycopy(teams[1].players, 0, all, 11, 11);
        ref = new Player(-1, -1, -6, -9, 1, 0, "", 4, RI(4));
        actors = Arrays.copyOf(all, 23);
        actors[22] = ref;
        added = 1 + RI(4);
        if (first) {
            time = 23 * 60 + RI(50); teams[0].score = 1;
            Player o = teams[0].players[7]; o.x = -4; o.y = 6; gain(o);
        } else restart(Kind.KICKOFF, 0, 0, 0);
    }

    private static Team team(TeamDef d, double dir, int t, List<String> names) {
        Player[] ps = new Player[11];
        for (int i = 0; i < 11; i++)
            ps[i] = new Player(t, i, FORM[i][0] * dir, FORM[i][1], dir, NUMS[i], names.get(t * 11 + i), i > 0 ? t : 2 + t, RI(4));
        return new Team(d, dir, ps);
    }

    // ---- helpers

    double gx(int t) { return 52.5 * teams[t].dir; }
    int poss() { return phase == Phase.RESTART && rs != null ? rs.team : ball.owner != null ? ball.owner.t : ball.last; }
    double replaySide() { return phase == Phase.REPLAY ? sgn(celebX) : 0; }
    boolean crowdCheering() { return phase == Phase.GOAL || (phase == Phase.REPLAY && rpI >= gi); }

    static List<Player> near(List<Player> ps, double x, double y, int n) {
        List<Player> s = new ArrayList<>(ps);
        // Collections.sort + Comparator class: List.sort / Comparator.comparingDouble are API 24, this targets API 13.
        Collections.sort(s, new Comparator<Player>() {
            @Override public int compare(Player a, Player b) { return Double.compare(hyp(a.x - x, a.y - y), hyp(b.x - x, b.y - y)); }
        });
        return s.subList(0, Math.min(n, s.size()));
    }

    static <T> T pick(List<T> l) { return l.get(RI(l.size())); }

    void home(Player p) {
        double d = teams[p.t].dir, bx = ball.x * d; double[] f = FORM[p.i]; boolean on = poss() == p.t;
        if (p.i == 0) { p.tx = (-52.5 + clamp((bx + 52.5) * 0.12, 1, 12)) * d; p.ty = clamp(ball.y * 0.12, -3, 3); return; }
        double sh = on ? clamp(bx + 10, -10, 38) : clamp(bx * 0.6 - 2, -18, 25);
        double tx = clamp(f[0] + sh + Math.sin(clock * 0.3 + p.wob) * 1.5, -47, 47);
        double ty = f[1] * (on ? 1 : 0.78) + ball.y * 0.3 + Math.cos(clock * 0.23 + p.wob) * 1.5;
        p.tx = tx * d; p.ty = clamp(ty, -32, 32);
    }

    double[] predict() {
        Ball b = ball;
        if (b.z > 0.3 || b.vz > 0) {
            double t = (b.vz + Math.sqrt(b.vz * b.vz + 2 * G * b.z)) / G;
            return new double[]{b.x + b.vx * t, b.y + b.vy * t};
        }
        return new double[]{b.x + b.vx * 0.5, b.y + b.vy * 0.5};
    }

    // ---- targets

    void assignPlay() {
        Ball b = ball; Player o = b.owner;
        for (Player p : all) { home(p); p.spd = 4.5; }
        if (o != null) {
            if (o.i == 0) { o.tx = o.x; o.ty = o.y; o.spd = 0; }
            else {
                double dir = teams[o.t].dir, dx = gx(o.t) - o.x, dy = -o.y * 0.5, n = hyp(dx, dy);
                dx /= n; dy /= n;
                for (Player q : teams[1 - o.t].players) {
                    double rx = q.x - o.x, ry = q.y - o.y, r = hyp(rx, ry);
                    if (r < 8) { double w = (8 - r) / 8 / (r + 0.01); dx -= rx * w * 1.3; dy -= ry * w * 2; }
                }
                if (dx * dir < 0.3) dx = 0.3 * dir;
                n = hyp(dx, dy); if (n == 0) n = 1;
                o.tx = clamp(o.x + dx / n * 6, -51, 51); o.ty = clamp(o.y + dy / n * 6, -32.5, 32.5); o.spd = 5.8;
            }
            List<Player> outfield = new ArrayList<>();
            for (Player q : teams[1 - o.t].players) if (q.i > 0) outfield.add(q);
            List<Player> pc = near(outfield, o.x, o.y, 2);
            Player pr = pc.get(0), cv = pc.get(1);
            pr.tx = o.x + o.vx * 0.3; pr.ty = o.y + o.vy * 0.3; pr.spd = 6.2;
            double vx = -gx(cv.t) - o.x, vy = -o.y, n = Math.max(hyp(vx, vy), 0.01);
            cv.tx = o.x + vx / n * 7; cv.ty = o.y + vy / n * 7; cv.spd = 6;
        } else {
            double[] pp = predict(); double px = pp[0], py = pp[1];
            for (int t = 0; t < 2; t++) {
                List<Player> ps = new ArrayList<>();
                for (Player p : teams[t].players)
                    if ((p.i > 0 || (Math.abs(px + gx(t)) < 16.5 && Math.abs(py) < 20)) && !(p == b.kicker && b.kickT > 0)) ps.add(p);
                if (!ps.isEmpty()) { Player c = near(ps, px, py, 1).get(0); c.tx = px; c.ty = py; c.spd = 7.5; }
            }
            if (b.recv != null) { b.recv.tx = px; b.recv.ty = py; b.recv.spd = 7.5; }
            if (b.shot != null) {
                Shot s = b.shot; Player k = teams[1 - s.t].players[0]; double gl = gx(s.t); boolean sv = s.out == Out.SAVE;
                double yc = b.vx != 0 ? b.y + b.vy * (gl - b.x) / b.vx : b.y;
                k.tx = gl - teams[s.t].dir * 0.6; k.ty = clamp(sv ? yc : yc * 0.55, -4, 4); k.spd = sv ? 10 : 4.5;
            }
        }
    }

    void assignRestart() {
        if (rs == null) return;
        for (Player p : all) { home(p); p.spd = 5; }
        if (rs.kind == Kind.KICKOFF) for (Player p : all) {
            double[] f = FORM[p.i]; double d = teams[p.t].dir;
            p.tx = (p.i > 8 && p.t != rs.team ? -10 : f[0] * 0.9) * d; p.ty = f[1];
            if (p.t == rs.team && p.i == 9) { p.tx = -0.3 * d; p.ty = 0.4; }
        }
        for (Spot s : rs.spots) { s.p.tx = s.x; s.p.ty = s.y; }
        Player k = rs.taker;
        k.tx = rs.x - sgn(rs.x) * (rs.kind == Kind.CORNER ? 0.5 : 0);
        k.ty = rs.y + (rs.kind == Kind.THROW_IN ? sgn(rs.y) * 0.4 : 0);
        k.spd = 6;
    }

    void assignGoal() {
        for (Player p : all) { home(p); p.spd = 2; }
        for (Player p : teams[gteam].players) if (p.i > 0) {
            p.tx = celebX + Math.sin(p.i * 2.4) * 2.5; p.ty = celebY + Math.cos(p.i * 2.4) * 2.5; p.spd = p == scorer ? 7 : 5.5;
        }
    }

    // ---- actions

    void kick(Player p, double tx, double ty, double vh, double tz, Player recv, Shot shot) {
        Ball b = ball; double dx = tx - b.x, dy = ty - b.y, d0 = hyp(dx, dy), d = d0 > 0 ? d0 : 1;
        b.owner = null; b.last = p.t; b.kicker = p; b.kickT = 0.35; b.recv = recv; b.shot = shot;
        for (Player q : all) q.tried = false;
        if (vh > 0) { double t = d / vh; b.vx = dx / t; b.vy = dy / t; b.vz = (tz - b.z) / t + G * t / 2; }
        else { double s = clamp(d * 0.6 + 6, 9, 26); b.vx = dx / d * s; b.vy = dy / d * s; b.vz = 0; b.z = 0; }
    }

    void gain(Player p) {
        Ball b = ball;
        b.owner = p; b.last = p.t; b.recv = null; b.shot = null; b.vz = 0;
        p.decide = p.i > 0 ? R(0.4, 1.6) : R(1.2, 2);
    }

    private double space(Player q, Player[] opp) {
        double m = 99;
        for (Player e : opp) m = Math.min(m, hyp(e.x - q.x, e.y - q.y));
        return m;
    }

    private Player forwardMate(List<Player> mates, double d) {
        List<Player> f = new ArrayList<>();
        for (Player q : mates) if (q.x * d > -15) f.add(q);
        return pick(f.isEmpty() ? mates : f);
    }

    void decide(Player o) {
        double d = teams[o.t].dir, dG = hyp(gx(o.t) - o.x, o.y); Player[] opp = teams[1 - o.t].players;
        List<Player> mates = new ArrayList<>();
        for (Player q : teams[o.t].players) if (q != o && q.i > 0) mates.add(q);
        if (o.i == 0) { Player q = forwardMate(mates, d); kick(o, q.x, q.y, 22, 0, q, null); return; }
        if (dG < 28 && rnd() < (dG < 18 ? 0.8 : 0.4)) { shoot(o, dG); return; }
        boolean pressed = space(o, opp) < 2.5;
        Player best = null; double bs = -9;
        for (Player q : mates) {
            double dd = hyp(q.x - o.x, q.y - o.y);
            if (dd < 6 || dd > 42) continue;
            double s = (q.x - o.x) * d * 0.15 + Math.min(space(q, opp), 8) * 0.3 - Math.abs(dd - 16) * 0.04 + rnd() * 0.6;
            if (s > bs) { bs = s; best = q; }
        }
        if (best == null || (bs < 1.4 && !pressed)) { o.decide = R(0.4, 1); return; }
        Player q = best;
        double dd = hyp(q.x - o.x, q.y - o.y), lx = q.x + q.vx * dd / 15, ly = q.y + q.vy * dd / 15;
        boolean blocked = false;
        for (Player e : opp) {
            double t = clamp(((e.x - o.x) * (lx - o.x) + (e.y - o.y) * (ly - o.y)) / (dd * dd), 0, 1);
            if (hyp(o.x + (lx - o.x) * t - e.x, o.y + (ly - o.y) * t - e.y) < 1.8 && t > 0.1) blocked = true;
        }
        kick(o, lx, ly, dd > 28 || blocked ? clamp(dd * 0.35 + 10, 14, 24) : 0, 0, q, null);
    }

    void shoot(Player o, double dG) {
        double v = rnd(), pg = 0.05 + (28 - dG) * 0.008;
        Out out = v < pg ? Out.GOAL : v < pg + 0.42 ? Out.SAVE : Out.WIDE;
        double ty = R(-3.2, 3.2), tz = R(0.15, 2.2);
        if (out == Out.SAVE) { ty = R(-2.6, 2.6); tz = R(0.2, 1.8); }
        if (out == Out.WIDE) { if (rnd() < 0.6) ty = sgn(R(-1, 1)) * R(3.9, 7); else tz = R(2.6, 4); }
        kick(o, gx(o.t) + teams[o.t].dir * 0.5, ty, R(22, 29), tz, null, new Shot(o.t, out));
    }

    void restart(Kind kind, int team, double x, double y) {
        Ball b = ball; Player[] ps = teams[team].players;
        phase = Phase.RESTART;
        b.owner = null; b.x = x; b.y = y; b.z = 0; b.vx = 0; b.vy = 0; b.vz = 0; b.shot = null; b.recv = null;
        Player taker;
        if (kind == Kind.GOAL_KICK) taker = ps[0];
        else if (kind == Kind.KICKOFF) taker = ps[9];
        else { List<Player> o = new ArrayList<>(); for (Player p : ps) if (p.i > 0) o.add(p); taker = near(o, x, y, 1).get(0); }
        Restart r = new Restart(kind, team, x, y, kind == Kind.KICKOFF ? 1.5 : 1.4, taker);
        if (kind == Kind.CORNER) {
            double d = teams[team].dir;
            for (Player p : ps) if (p.i > 2 && p != taker) r.spots.add(new Spot(p, (52.5 - R(5, 14)) * d, R(-8, 8)));
            for (Player p : teams[1 - team].players) if (p.i > 0 && p.i < 9) r.spots.add(new Spot(p, (52.5 - R(2, 13)) * d, R(-9, 9)));
        }
        rs = r;
    }

    void takeRestart() {
        Restart r = rs; Player k = r.taker; Ball b = ball; double d = teams[r.team].dir;
        List<Player> mates = new ArrayList<>();
        for (Player q : teams[r.team].players) if (q != k && q.i > 0) mates.add(q);
        phase = Phase.PLAY;
        switch (r.kind) {
            case KICKOFF: { Player q = teams[r.team].players[6 + RI(2)]; kick(k, q.x, q.y, 0, 0, q, null); break; }
            case THROW_IN: {
                Player q = pick(near(mates, k.x, k.y, 3));
                b.x = k.x; b.y = sgn(r.y) * 33.8; b.z = 2.1; kick(k, q.x, q.y, 11, 0, q, null); break;
            }
            case GOAL_KICK: { Player q = forwardMate(mates, d); kick(k, q.x, q.y, 22, 0, q, null); break; }
            case CORNER: {
                List<Player> own = new ArrayList<>();
                for (Spot s : r.spots) if (s.p.t == r.team) own.add(s.p);
                Player q = pick(own); kick(k, q.x, q.y, 17, 0, q, null); break;
            }
        }
    }

    void outOfPlay() {
        Ball b = ball; double s = sgn(b.x);
        if (Math.abs(b.y) > 34.1) { restart(Kind.THROW_IN, 1 - b.last, clamp(b.x, -52, 52), sgn(b.y) * 34); return; }
        if (Math.abs(b.x) <= 52.6) return;
        if (Math.abs(b.y) < 3.66 && b.z < 2.44) { goal(teams[0].dir == s ? 0 : 1, s); return; }
        int def = teams[0].dir == s ? 1 : 0; boolean saved = b.shot != null && b.shot.out == Out.SAVE;
        if (b.last == def || saved) restart(Kind.CORNER, 1 - def, s * 52.5, sgn(b.y) * 34);
        else restart(Kind.GOAL_KICK, def, s * 47, sgn(b.y) * 5);
    }

    void goal(int t, double s) {
        Ball b = ball; Player k = b.kicker != null ? b.kicker : teams[t].players[9];
        teams[t].score++; phase = Phase.GOAL; gt = 3.5; gi = recN; gteam = t; scorer = k; celebX = s * 46; celebY = sgn(b.y) * 27; b.shot = null;
        boolean og = k.t != t; int minute = (int) (time / 60) + 1, hEnd = half * 45;
        String when = minute > hEnd ? hEnd + "+" + (minute - hEnd) : String.valueOf(minute);
        banner1 = (og ? "" : "#" + k.num + " ") + k.name.toUpperCase() + (og ? " (OG)" : "") + "  " + when + "'";
        banner2 = teams[0].def.name.toUpperCase() + " " + teams[0].score + " – " + teams[1].score + " " + teams[1].def.name.toUpperCase();
        bannerOn = true;
    }

    void brk() {
        phase = Phase.PAUSE; bt = 9; ball.owner = null;
        cardTitle = half == 1 ? "HALF-TIME" : "FULL-TIME"; cardOn = true;
    }

    void endBreak() {
        cardOn = false;
        if (half == 2) { finished = true; return; }
        half = 2; time = 45 * 60; added = 2 + RI(4);
        for (Team t : teams) t.dir *= -1;
        restart(Kind.KICKOFF, 1, 0, 0);
    }

    // ---- replay

    private void snap(float[] buf, int at) {
        int k = at;
        for (Player p : actors) { buf[k++] = (float) p.x; buf[k++] = (float) p.y; buf[k++] = (float) p.vx; buf[k++] = (float) p.vy; buf[k++] = (float) p.ph; }
        buf[k++] = (float) ball.x; buf[k++] = (float) ball.y; buf[k] = (float) ball.z;
    }

    private void load(float[] a, int ia, float[] c, int ic, double t) {
        int k = 0; double[] v = new double[STRIDE];
        for (; k < STRIDE; k++) v[k] = a[ia + k] + (c[ic + k] - a[ia + k]) * t;
        k = 0;
        for (Player p : actors) { p.x = v[k++]; p.y = v[k++]; p.vx = v[k++]; p.vy = v[k++]; p.ph = v[k++]; }
        ball.x = v[k++]; ball.y = v[k++]; ball.z = v[k];
    }

    void startReplay() {
        int st = Math.max(recN - REC + 1, gi - 180), o = (st % REC) * STRIDE;
        snap(live, 0);
        rpI = st; rpEnd = Math.min(recN - 1, gi + 42); rpWiped = false; phase = Phase.REPLAY; bannerOn = false;
        load(rec, o, rec, o, 0); ball.vx = 0; ball.vy = 0; snapCam = true;
    }

    void replayStep(double dt) {
        time += dt; clock += dt; rpI += dt * 60 * 0.4;
        if (!rpWiped && rpI >= rpEnd - 7) { rpWiped = true; wipes++; }
        if (rpI >= rpEnd) { load(live, 0, live, 0, 0); restart(Kind.KICKOFF, 1 - gteam, 0, 0); return; }
        int i = (int) rpI;
        load(rec, (i % REC) * STRIDE, rec, ((i + 1) % REC) * STRIDE, rpI - i);
    }

    // ---- step

    void step(double dt) {
        if (phase == Phase.REPLAY) { replayStep(dt); return; }
        snap(rec, (recN % REC) * STRIDE); recN++;
        Ball b = ball; Player o = b.owner;
        clock += dt;
        if (phase != Phase.PAUSE) time += dt;
        if ((phase == Phase.PLAY || phase == Phase.RESTART) && time >= (half * 45 + added) * 60) brk();
        b.kickT -= dt;
        switch (phase) {
            case PLAY: assignPlay(); break;
            case RESTART: assignRestart(); break;
            case GOAL: assignGoal(); break;
            default: for (Player p : all) { home(p); p.spd = 1.5; }
        }
        ref.tx = b.x * 0.85 - sgn(b.x == 0 ? 1 : b.x) * 4; ref.ty = clamp(b.y * 0.5 - 10, -30, 30); ref.spd = 5;
        for (Player p : actors) {
            double dx = p.tx - p.x, dy = p.ty - p.y, d = hyp(dx, dy);
            double w = Math.min(p.spd * (p.stun > 0 ? 0.3 : 1), d * 1.5), a = Math.min(1, dt * (p.spd > 8 ? 9 : 5));
            p.vx += ((d > 0.01 ? dx / d * w : 0) - p.vx) * a;
            p.vy += ((d > 0.01 ? dy / d * w : 0) - p.vy) * a;
            p.stun -= dt;
        }
        for (int i = 0; i < all.length; i++) for (int j = i + 1; j < all.length; j++) {
            Player p = all[i], q = all[j]; double dx = q.x - p.x, dy = q.y - p.y, d = hyp(dx, dy);
            if (d < 0.9 && d > 1e-3 && p != o && q != o) {
                double k = (0.9 - d) / d * 0.5;
                p.x -= dx * k; p.y -= dy * k; q.x += dx * k; q.y += dy * k;
            }
        }
        for (Player p : actors) {
            p.x += p.vx * dt; p.y += p.vy * dt;
            double sp = hyp(p.vx, p.vy);
            if (sp > 0.5) p.ph += dt * (sp < 2.4 ? 2.8 + sp * 1.3 : 4 + sp * 0.9);
        }
        // ball
        if (phase == Phase.RESTART && rs != null) {
            Restart r = rs; Player k = r.taker; boolean at = hyp(k.x - k.tx, k.y - k.ty) < 0.8;
            r.el += dt;
            if (at) { r.t -= dt; if (r.kind == Kind.THROW_IN) { b.x = k.x; b.y = k.y; b.z = 2.1; } }
            boolean settled = r.kind != Kind.KICKOFF || r.el > 8;
            if (!settled) { settled = true; for (Player p : all) if (hyp(p.x - p.tx, p.y - p.ty) >= 2) { settled = false; break; } }
            if (at && r.t <= 0 && settled) takeRestart();
        } else if (o != null && phase == Phase.PLAY) {
            double sp = hyp(o.vx, o.vy), ux = sp > 0.3 ? o.vx / sp : o.face, uy = sp > 0.3 ? o.vy / sp : 0;
            double off = 0.55 + 0.25 * Math.sin(clock * 9);
            b.x = o.x + ux * off; b.y = o.y + uy * off; b.z = o.i > 0 ? 0 : 1; b.vx = o.vx; b.vy = o.vy;
        } else {
            b.owner = null;
            double px = b.x;
            if (b.z > 0 || b.vz > 0) {
                b.vz -= G * dt; b.z += b.vz * dt; b.x += b.vx * dt; b.y += b.vy * dt;
                if (b.z <= 0) { b.z = 0; if (b.vz < -2) { b.vz *= -0.45; b.vx *= 0.75; b.vy *= 0.75; } else b.vz = 0; }
            } else {
                double k = Math.exp(-0.6 * dt);
                b.vx *= k; b.vy *= k;
                if (hyp(b.vx, b.vy) < 0.3) { b.vx = 0; b.vy = 0; }
                b.x += b.vx * dt; b.y += b.vy * dt;
            }
            if (phase == Phase.GOAL && Math.abs(b.x) > 54.3 && Math.abs(px) <= 54.3 + 1e-6) { b.x = sgn(b.x) * 54.3; b.vx *= -0.15; b.vy *= 0.5; }
            if (phase == Phase.GOAL && Math.abs(b.x) > 52.5) { b.z = Math.min(b.z, 2.3); b.y = clamp(b.y, -3.5, 3.5); }
        }
        if (phase == Phase.PLAY) {
            if (b.owner == null && b.z < 2.6) {
                Player best = null; double bd = 1e9;
                for (Player p : all) {
                    if ((p == b.kicker && b.kickT > 0) || p.stun > 0 || b.z > (p.i > 0 ? 2 : 2.6)) continue;
                    if (b.shot != null && !(p.i == 0 && p.t != b.shot.t && b.shot.out == Out.SAVE)) continue;
                    double d = hyp(p.x - b.x, p.y - b.y);
                    if (d < (p.i > 0 ? 1 : 1.7) && d < bd) { best = p; bd = d; }
                }
                if (best != null) {
                    boolean take = true;
                    if (best.t != b.last && best != b.recv && b.shot == null) {
                        if (best.tried) take = false; else { best.tried = true; if (rnd() < 0.45) take = false; }
                    }
                    if (take) gain(best);
                }
            }
            Player ow = b.owner;
            if (ow != null && ow.i > 0) for (Player q : teams[1 - ow.t].players) {
                if (q.stun <= 0 && hyp(q.x - ow.x, q.y - ow.y) < 1.1 && rnd() < dt * 0.6) {
                    ow.stun = 0.8;
                    if (rnd() < 0.35) { b.owner = null; b.last = q.t; b.kicker = q; b.kickT = 0.3; b.vx = R(-5, 5); b.vy = R(-5, 5); }
                    else gain(q);
                    break;
                }
            }
            if (b.owner != null) { b.owner.decide -= dt; if (b.owner.decide <= 0) decide(b.owner); }
            if (phase == Phase.PLAY) outOfPlay();
        } else if (phase == Phase.GOAL) {
            double g0 = gt; gt -= dt;
            if (g0 > 0.3 && gt <= 0.3) wipes++;
            if (gt <= 0) startReplay();
        } else if (phase == Phase.PAUSE) {
            bt -= dt;
            if (bt <= 0) endBreak();
        }
    }
}
