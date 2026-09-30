//
//  Match.swift
//  Football Screensaver
//
//  The match simulation, a line-for-line port of step() and friends in
//  mockup.html. Pure Foundation: no AppKit, no drawing. Fixed 60 Hz steps.
//

import Foundation

let G = 9.81
let TAU = Double.pi * 2

@inline(__always) func rnd() -> Double { Double.random(in: 0..<1) }
@inline(__always) func R(_ a: Double, _ b: Double) -> Double { a + rnd() * (b - a) }
@inline(__always) func RI(_ n: Int) -> Int { Int.random(in: 0..<n) }
@inline(__always) func clamp(_ v: Double, _ a: Double, _ b: Double) -> Double { v < a ? a : v > b ? b : v }
@inline(__always) func sgn(_ v: Double) -> Double { v < 0 ? -1 : 1 }
@inline(__always) func hyp(_ a: Double, _ b: Double) -> Double { (a * a + b * b).squareRoot() }

struct TeamDef { let name: String; let code: String; let kit: [String] }

/// Fictional clubs: shirt, shorts, socks.
let TEAMS = [
    TeamDef(name: "Northbridge", code: "NBR", kit: ["#c8102e", "#ffffff", "#c8102e"]),
    TeamDef(name: "Castleford Athletic", code: "CAS", kit: ["#1d4ed8", "#1d4ed8", "#ffffff"]),
    TeamDef(name: "Harbour City", code: "HBC", kit: ["#8fd3f4", "#ffffff", "#0b1f44"]),
    TeamDef(name: "Redmoor Rovers", code: "RMR", kit: ["#f4f4f4", "#111111", "#f4f4f4"]),
    TeamDef(name: "Ashvale United", code: "ASH", kit: ["#fcd116", "#111111", "#fcd116"]),
    TeamDef(name: "Kingsport", code: "KSP", kit: ["#6d28d9", "#ffffff", "#6d28d9"]),
    TeamDef(name: "Westmere Town", code: "WMT", kit: ["#7a1f3d", "#8fd3f4", "#7a1f3d"]),
    TeamDef(name: "Stoneham", code: "STH", kit: ["#f97316", "#111111", "#f97316"]),
]
let GKKITS = [["#a3e635", "#111111", "#a3e635"], ["#ec4899", "#111111", "#ec4899"], ["#06b6d4", "#0b1f44", "#06b6d4"],
              ["#222222", "#222222", "#222222"], ["#facc15", "#facc15", "#facc15"]]
let NAMES = ["Parker", "Okafor", "Lindqvist", "Moreau", "Silva", "Novak", "Brennan", "Haddad", "Kowalski", "Tanaka", "Reyes",
             "Fischer", "Adeyemi", "Costa", "Murphy", "Ivanov", "Duarte", "Walsh", "Kim", "Bauer", "Mensah", "Rossi", "Varga",
             "Holm", "Quinn", "Sato", "Nkemelu", "Petit", "Byrne", "Moreno", "Larsen", "Dvořák"]
let NUMS = [1, 2, 5, 6, 3, 7, 8, 4, 11, 9, 10]
/// 4-4-2 shape for a team attacking +x (metres; pitch 105×68, centre at 0,0).
let FORM: [(Double, Double)] = [(-49, 0), (-33, -22), (-36, -8), (-36, 8), (-33, 22), (-15, -23), (-18, -7), (-18, 7),
                                (-15, 23), (-3, -7), (-5, 8)]

func hexRGB(_ h: String) -> [Double] {
    let s = Array(h.dropFirst())
    return stride(from: 0, to: 6, by: 2).map { Double(Int(String(s[$0...$0 + 1]), radix: 16) ?? 0) }
}
func cdist(_ a: String, _ b: String) -> Double {
    let x = hexRGB(a), y = hexRGB(b)
    return ((x[0] - y[0]) * (x[0] - y[0]) + (x[1] - y[1]) * (x[1] - y[1]) + (x[2] - y[2]) * (x[2] - y[2])).squareRoot()
}

final class Player {
    let t: Int, i: Int, num: Int, name: String, kit: Int, look: Int, wob = R(0, 99)
    var x: Double, y: Double, vx = 0.0, vy = 0.0, ph = R(0, TAU), face: Double, stun = 0.0
    var tx = 0.0, ty = 0.0, spd = 0.0, decide = 0.0, tried = false
    init(t: Int, i: Int, x: Double, y: Double, face: Double, num: Int, name: String, kit: Int, look: Int) {
        self.t = t; self.i = i; self.x = x; self.y = y; self.face = face; self.num = num; self.name = name
        self.kit = kit; self.look = look
    }
}

final class Team {
    let def: TeamDef; var dir: Double; var score = 0; let players: [Player]
    init(def: TeamDef, dir: Double, players: [Player]) { self.def = def; self.dir = dir; self.players = players }
}

enum Phase { case play, restart, goal, pause, replay }
enum RestartKind { case kickoff, throwIn, goalKick, corner }
enum ShotOut { case goal, save, wide }
struct Shot { let t: Int; let out: ShotOut }

final class Restart {
    let kind: RestartKind, team: Int, x: Double, y: Double, taker: Player
    var t: Double, el = 0.0, spots: [(Player, Double, Double)] = []
    init(kind: RestartKind, team: Int, x: Double, y: Double, t: Double, taker: Player) {
        self.kind = kind; self.team = team; self.x = x; self.y = y; self.t = t; self.taker = taker
    }
}

final class Ball {
    var x = 0.0, y = 0.0, z = 0.0, vx = 0.0, vy = 0.0, vz = 0.0
    var owner: Player?, last = 0, kicker: Player?, kickT = 0.0, shot: Shot?, recv: Player?
}

final class Match {
    static let REC = 480, STRIDE = 23 * 5 + 3

    let teams: [Team], all: [Player], ref: Player, actors: [Player], ball = Ball()
    /// home, away, home keeper, away keeper, referee
    let kits: [[String]]
    var time = 0.0, half = 1, added: Int, clock = 0.0, phase = Phase.play
    var rs: Restart?
    var gt = 0.0, gteam = 0, scorer: Player?, celeb = (0.0, 0.0), bt = 0.0
    /// Set once full time is over; the renderer swaps in a new match.
    var finished = false
    // Broadcast cues read by the renderer.
    var banner = ("", ""), bannerOn = false, cardTitle = "", cardOn = false, wipes = 0, snapCam = false
    // Goal replay: ring buffer of the last 8 s.
    var rec = [Float](repeating: 0, count: Match.REC * Match.STRIDE), recN = 0, gi = 0
    var live = [Float](repeating: 0, count: Match.STRIDE), rpI = 0.0, rpEnd = 0, rpWiped = false

    init(first: Bool) {
        let a = RI(TEAMS.count)
        var b: Int
        repeat { b = RI(TEAMS.count) } while b == a || cdist(TEAMS[a].kit[0], TEAMS[b].kit[0]) < 170
        let ta = TEAMS[a], tb = TEAMS[b], used = [ta.kit[0], tb.kit[0]]
        let gks = GKKITS.filter { k in used.allSatisfy { cdist($0, k[0]) > 150 } }
        let refKit = used.contains { cdist($0, "#111111") < 120 } ? ["#facc15", "#111111", "#111111"] : ["#111111", "#111111", "#111111"]
        kits = [ta.kit, tb.kit, gks.first ?? GKKITS[3], gks.count > 1 ? gks[1] : GKKITS[3], refKit]
        let names = NAMES.shuffled()
        func team(_ d: TeamDef, _ dir: Double, _ t: Int) -> Team {
            Team(def: d, dir: dir, players: FORM.enumerated().map { i, f in
                Player(t: t, i: i, x: f.0 * dir, y: f.1, face: dir, num: NUMS[i], name: names[t * 11 + i], kit: i > 0 ? t : 2 + t, look: RI(4))
            })
        }
        teams = [team(ta, 1, 0), team(tb, -1, 1)]
        all = teams[0].players + teams[1].players
        ref = Player(t: -1, i: -1, x: -6, y: -9, face: 1, num: 0, name: "", kit: 4, look: RI(4))
        actors = all + [ref]
        added = 1 + RI(4)
        if first {
            time = Double(23 * 60 + RI(50)); teams[0].score = 1
            let o = teams[0].players[7]; o.x = -4; o.y = 6; gain(o)
        } else {
            restart(.kickoff, 0, 0, 0)
        }
    }

    // MARK: - Helpers

    func gx(_ t: Int) -> Double { 52.5 * teams[t].dir }
    func poss() -> Int { phase == .restart ? (rs?.team ?? ball.last) : ball.owner?.t ?? ball.last }
    func near(_ ps: [Player], _ x: Double, _ y: Double, _ n: Int = 1) -> [Player] {
        Array(ps.sorted { hyp($0.x - x, $0.y - y) < hyp($1.x - x, $1.y - y) }.prefix(n))
    }
    var replaySide: Double { phase == .replay ? sgn(celeb.0) : 0 }
    var crowdCheering: Bool { phase == .goal || (phase == .replay && rpI >= Double(gi)) }

    func homeSpot(_ p: Player) -> (Double, Double) {
        let d = teams[p.t].dir, f = FORM[p.i], bx = ball.x * d, on = poss() == p.t
        if p.i == 0 { return ((-52.5 + clamp((bx + 52.5) * 0.12, 1, 12)) * d, clamp(ball.y * 0.12, -3, 3)) }
        let sh = on ? clamp(bx + 10, -10, 38) : clamp(bx * 0.6 - 2, -18, 25)
        let tx = clamp(f.0 + sh + sin(clock * 0.3 + p.wob) * 1.5, -47, 47)
        let ty = f.1 * (on ? 1 : 0.78) + ball.y * 0.3 + cos(clock * 0.23 + p.wob) * 1.5
        return (tx * d, clamp(ty, -32, 32))
    }

    func predict() -> (Double, Double) {
        let b = ball
        if b.z > 0.3 || b.vz > 0 {
            let t = (b.vz + (b.vz * b.vz + 2 * G * b.z).squareRoot()) / G
            return (b.x + b.vx * t, b.y + b.vy * t)
        }
        return (b.x + b.vx * 0.5, b.y + b.vy * 0.5)
    }

    // MARK: - Targets

    func assignPlay() {
        let b = ball
        for p in all { (p.tx, p.ty) = homeSpot(p); p.spd = 4.5 }
        if let o = b.owner {
            if o.i == 0 {
                o.tx = o.x; o.ty = o.y; o.spd = 0
            } else {
                let dir = teams[o.t].dir
                var dx = gx(o.t) - o.x, dy = -o.y * 0.5, n = hyp(dx, dy)
                dx /= n; dy /= n
                for q in teams[1 - o.t].players {
                    let rx = q.x - o.x, ry = q.y - o.y, r = hyp(rx, ry)
                    if r < 8 { let w = (8 - r) / 8 / (r + 0.01); dx -= rx * w * 1.3; dy -= ry * w * 2 }
                }
                if dx * dir < 0.3 { dx = 0.3 * dir }
                n = hyp(dx, dy)
                if n == 0 { n = 1 }
                o.tx = clamp(o.x + dx / n * 6, -51, 51); o.ty = clamp(o.y + dy / n * 6, -32.5, 32.5); o.spd = 5.8
            }
            let pc = near(teams[1 - o.t].players.filter { $0.i > 0 }, o.x, o.y, 2), pr = pc[0], cv = pc[1]
            pr.tx = o.x + o.vx * 0.3; pr.ty = o.y + o.vy * 0.3; pr.spd = 6.2
            let vx = -gx(cv.t) - o.x, vy = -o.y, n = max(hyp(vx, vy), 0.01)
            cv.tx = o.x + vx / n * 7; cv.ty = o.y + vy / n * 7; cv.spd = 6
        } else {
            let (px, py) = predict()
            for t in 0..<2 {
                let ps = teams[t].players.filter { p in
                    (p.i > 0 || (abs(px + gx(t)) < 16.5 && abs(py) < 20)) && !(p === b.kicker && b.kickT > 0)
                }
                if let c = near(ps, px, py).first { c.tx = px; c.ty = py; c.spd = 7.5 }
            }
            if let r = b.recv { r.tx = px; r.ty = py; r.spd = 7.5 }
            if let s = b.shot {
                let k = teams[1 - s.t].players[0], gl = gx(s.t), sv = s.out == .save
                let yc = b.vx != 0 ? b.y + b.vy * (gl - b.x) / b.vx : b.y
                k.tx = gl - teams[s.t].dir * 0.6; k.ty = clamp(sv ? yc : yc * 0.55, -4, 4); k.spd = sv ? 10 : 4.5
            }
        }
    }

    func assignRestart() {
        guard let rs = rs else { return }
        for p in all { (p.tx, p.ty) = homeSpot(p); p.spd = 5 }
        if rs.kind == .kickoff {
            for p in all {
                let f = FORM[p.i], d = teams[p.t].dir
                p.tx = (p.i > 8 && p.t != rs.team ? -10 : f.0 * 0.9) * d; p.ty = f.1
                if p.t == rs.team && p.i == 9 { p.tx = -0.3 * d; p.ty = 0.4 }
            }
        }
        for (p, x, y) in rs.spots { p.tx = x; p.ty = y }
        let k = rs.taker
        k.tx = rs.x - sgn(rs.x) * (rs.kind == .corner ? 0.5 : 0)
        k.ty = rs.y + (rs.kind == .throwIn ? sgn(rs.y) * 0.4 : 0)
        k.spd = 6
    }

    func assignGoal() {
        for p in all { (p.tx, p.ty) = homeSpot(p); p.spd = 2 }
        for p in teams[gteam].players where p.i > 0 {
            p.tx = celeb.0 + sin(Double(p.i) * 2.4) * 2.5; p.ty = celeb.1 + cos(Double(p.i) * 2.4) * 2.5
            p.spd = p === scorer ? 7 : 5.5
        }
    }

    // MARK: - Actions

    func kick(_ p: Player, _ tx: Double, _ ty: Double, vh: Double? = nil, tz: Double = 0, v: Double? = nil,
              recv: Player? = nil, shot: Shot? = nil) {
        let b = ball, dx = tx - b.x, dy = ty - b.y, d0 = hyp(dx, dy), d = d0 > 0 ? d0 : 1
        b.owner = nil; b.last = p.t; b.kicker = p; b.kickT = 0.35; b.recv = recv; b.shot = shot
        for q in all { q.tried = false }
        if let vh = vh {
            let t = d / vh
            b.vx = dx / t; b.vy = dy / t; b.vz = (tz - b.z) / t + G * t / 2
        } else {
            let s = v ?? clamp(d * 0.6 + 6, 9, 26)
            b.vx = dx / d * s; b.vy = dy / d * s; b.vz = 0; b.z = 0
        }
    }

    func gain(_ p: Player) {
        let b = ball
        b.owner = p; b.last = p.t; b.recv = nil; b.shot = nil; b.vz = 0
        p.decide = p.i > 0 ? R(0.4, 1.6) : R(1.2, 2)
    }

    func decide(_ o: Player) {
        let d = teams[o.t].dir, mates = teams[o.t].players.filter { $0 !== o && $0.i > 0 }, opp = teams[1 - o.t].players
        let dG = hyp(gx(o.t) - o.x, o.y)
        if o.i == 0 {
            let q = mates.filter { $0.x * d > -15 }.randomElement() ?? mates.randomElement()!
            kick(o, q.x, q.y, vh: 22, recv: q); return
        }
        if dG < 28 && rnd() < (dG < 18 ? 0.8 : 0.4) { shoot(o, dG); return }
        func space(_ q: Player) -> Double { opp.map { hyp($0.x - q.x, $0.y - q.y) }.min() ?? 99 }
        let pressed = space(o) < 2.5
        var best: Player?, bs = -9.0
        for q in mates {
            let dd = hyp(q.x - o.x, q.y - o.y)
            if dd < 6 || dd > 42 { continue }
            let s = (q.x - o.x) * d * 0.15 + min(space(q), 8) * 0.3 - abs(dd - 16) * 0.04 + rnd() * 0.6
            if s > bs { bs = s; best = q }
        }
        guard let q = best, bs >= 1.4 || pressed else { o.decide = R(0.4, 1); return }
        let dd = hyp(q.x - o.x, q.y - o.y), lx = q.x + q.vx * dd / 15, ly = q.y + q.vy * dd / 15
        let blocked = opp.contains { e in
            let t = clamp(((e.x - o.x) * (lx - o.x) + (e.y - o.y) * (ly - o.y)) / (dd * dd), 0, 1)
            return hyp(o.x + (lx - o.x) * t - e.x, o.y + (ly - o.y) * t - e.y) < 1.8 && t > 0.1
        }
        if dd > 28 || blocked { kick(o, lx, ly, vh: clamp(dd * 0.35 + 10, 14, 24), recv: q) } else { kick(o, lx, ly, recv: q) }
    }

    func shoot(_ o: Player, _ dG: Double) {
        let v = rnd(), pg = 0.05 + (28 - dG) * 0.008
        let out: ShotOut = v < pg ? .goal : v < pg + 0.42 ? .save : .wide
        var ty = R(-3.2, 3.2), tz = R(0.15, 2.2)
        if out == .save { ty = R(-2.6, 2.6); tz = R(0.2, 1.8) }
        if out == .wide { if rnd() < 0.6 { ty = sgn(R(-1, 1)) * R(3.9, 7) } else { tz = R(2.6, 4) } }
        kick(o, gx(o.t) + teams[o.t].dir * 0.5, ty, vh: R(22, 29), tz: tz, shot: Shot(t: o.t, out: out))
    }

    func restart(_ kind: RestartKind, _ team: Int, _ x: Double, _ y: Double) {
        let b = ball, ps = teams[team].players
        phase = .restart
        b.owner = nil; b.x = x; b.y = y; b.z = 0; b.vx = 0; b.vy = 0; b.vz = 0; b.shot = nil; b.recv = nil
        let taker = kind == .goalKick ? ps[0] : kind == .kickoff ? ps[9] : near(ps.filter { $0.i > 0 }, x, y)[0]
        let r = Restart(kind: kind, team: team, x: x, y: y, t: kind == .kickoff ? 1.5 : 1.4, taker: taker)
        if kind == .corner {
            let d = teams[team].dir
            r.spots = ps.filter { $0.i > 2 && $0 !== taker }.map { ($0, (52.5 - R(5, 14)) * d, R(-8, 8)) }
                + teams[1 - team].players.filter { $0.i > 0 && $0.i < 9 }.map { ($0, (52.5 - R(2, 13)) * d, R(-9, 9)) }
        }
        rs = r
    }

    func takeRestart() {
        guard let rs = rs else { return }
        let k = rs.taker, b = ball, d = teams[rs.team].dir, mates = teams[rs.team].players.filter { $0 !== k && $0.i > 0 }
        phase = .play
        switch rs.kind {
        case .kickoff:
            let q = teams[rs.team].players[6 + RI(2)]; kick(k, q.x, q.y, recv: q)
        case .throwIn:
            let q = near(mates, k.x, k.y, 3).randomElement()!
            b.x = k.x; b.y = sgn(rs.y) * 33.8; b.z = 2.1; kick(k, q.x, q.y, vh: 11, recv: q)
        case .goalKick:
            let q = mates.filter { $0.x * d > -15 }.randomElement() ?? mates.randomElement()!
            kick(k, q.x, q.y, vh: 22, recv: q)
        case .corner:
            let q = rs.spots.filter { $0.0.t == rs.team }.randomElement()!.0
            kick(k, q.x, q.y, vh: 17, recv: q)
        }
    }

    func outOfPlay() {
        let b = ball, s = sgn(b.x)
        if abs(b.y) > 34.1 { restart(.throwIn, 1 - b.last, clamp(b.x, -52, 52), sgn(b.y) * 34); return }
        if abs(b.x) <= 52.6 { return }
        if abs(b.y) < 3.66 && b.z < 2.44 { goal(teams[0].dir == s ? 0 : 1, s); return }
        let def = teams[0].dir == s ? 1 : 0, saved = b.shot?.out == .save
        if b.last == def || saved { restart(.corner, 1 - def, s * 52.5, sgn(b.y) * 34) } else { restart(.goalKick, def, s * 47, sgn(b.y) * 5) }
    }

    func goal(_ t: Int, _ s: Double) {
        let b = ball, k = b.kicker ?? teams[t].players[9]
        teams[t].score += 1; phase = .goal; gt = 3.5; gi = recN; gteam = t; scorer = k; celeb = (s * 46, sgn(b.y) * 27); b.shot = nil
        let og = k.t != t, minute = Int(time / 60) + 1, hEnd = half * 45
        let when = minute > hEnd ? "\(hEnd)+\(minute - hEnd)" : "\(minute)"
        banner = ("\(og ? "" : "#\(k.num) ")\(k.name.uppercased())\(og ? " (OG)" : "")  \(when)'",
                  "\(teams[0].def.name.uppercased()) \(teams[0].score) – \(teams[1].score) \(teams[1].def.name.uppercased())")
        bannerOn = true
    }

    func brk() {
        phase = .pause; bt = 9; ball.owner = nil
        cardTitle = half == 1 ? "HALF-TIME" : "FULL-TIME"; cardOn = true
    }

    func endBreak() {
        cardOn = false
        if half == 2 { finished = true; return }
        half = 2; time = 45 * 60; added = 2 + RI(4)
        for t in teams { t.dir *= -1 }
        restart(.kickoff, 1, 0, 0)
    }

    // MARK: - Replay

    private func snap(_ buf: inout [Float], _ at: Int) {
        var k = at
        for p in actors { for v in [p.x, p.y, p.vx, p.vy, p.ph] { buf[k] = Float(v); k += 1 } }
        buf[k] = Float(ball.x); buf[k + 1] = Float(ball.y); buf[k + 2] = Float(ball.z)
    }

    private func load(_ a: [Float], _ ia: Int, _ c: [Float], _ ic: Int, _ t: Double) {
        var k = 0
        func l() -> Double { let v = Double(a[ia + k]) + Double(c[ic + k] - a[ia + k]) * t; k += 1; return v }
        for p in actors { p.x = l(); p.y = l(); p.vx = l(); p.vy = l(); p.ph = l() }
        ball.x = l(); ball.y = l(); ball.z = l()
    }

    func startReplay() {
        let st = max(recN - Match.REC + 1, gi - 180), o = (st % Match.REC) * Match.STRIDE
        snap(&live, 0)
        rpI = Double(st); rpEnd = min(recN - 1, gi + 42); rpWiped = false; phase = .replay; bannerOn = false
        load(rec, o, rec, o, 0); ball.vx = 0; ball.vy = 0; snapCam = true
    }

    func replayStep(_ dt: Double) {
        time += dt; clock += dt; rpI += dt * 60 * 0.4
        if !rpWiped && rpI >= Double(rpEnd) - 7 { rpWiped = true; wipes += 1 }
        if rpI >= Double(rpEnd) { load(live, 0, live, 0, 0); restart(.kickoff, 1 - gteam, 0, 0); return }
        let i = Int(rpI)
        load(rec, (i % Match.REC) * Match.STRIDE, rec, ((i + 1) % Match.REC) * Match.STRIDE, rpI - Double(i))
    }

    // MARK: - Step

    func step(_ dt: Double) {
        if phase == .replay { replayStep(dt); return }
        snap(&rec, (recN % Match.REC) * Match.STRIDE); recN += 1
        let b = ball, o = b.owner
        clock += dt
        if phase != .pause { time += dt }
        if (phase == .play || phase == .restart) && time >= Double(half * 45 + added) * 60 { brk() }
        b.kickT -= dt
        switch phase {
        case .play: assignPlay()
        case .restart: assignRestart()
        case .goal: assignGoal()
        default: for p in all { (p.tx, p.ty) = homeSpot(p); p.spd = 1.5 }
        }
        ref.tx = b.x * 0.85 - sgn(b.x == 0 ? 1 : b.x) * 4; ref.ty = clamp(b.y * 0.5 - 10, -30, 30); ref.spd = 5
        for p in actors {
            let dx = p.tx - p.x, dy = p.ty - p.y, d = hyp(dx, dy)
            let w = min(p.spd * (p.stun > 0 ? 0.3 : 1), d * 1.5), a = min(1, dt * (p.spd > 8 ? 9 : 5))
            p.vx += ((d > 0.01 ? dx / d * w : 0) - p.vx) * a
            p.vy += ((d > 0.01 ? dy / d * w : 0) - p.vy) * a
            p.stun -= dt
        }
        for i in 0..<all.count {
            for j in (i + 1)..<all.count {
                let p = all[i], q = all[j], dx = q.x - p.x, dy = q.y - p.y, d = hyp(dx, dy)
                if d < 0.9 && d > 1e-3 && p !== o && q !== o {
                    let k = (0.9 - d) / d * 0.5
                    p.x -= dx * k; p.y -= dy * k; q.x += dx * k; q.y += dy * k
                }
            }
        }
        for p in actors {
            p.x += p.vx * dt; p.y += p.vy * dt
            let sp = hyp(p.vx, p.vy)
            if sp > 0.5 { p.ph += dt * (sp < 2.4 ? 2.8 + sp * 1.3 : 4 + sp * 0.9) }
        }
        // ball
        if phase == .restart, let rs = rs {
            let k = rs.taker, at = hyp(k.x - k.tx, k.y - k.ty) < 0.8
            rs.el += dt
            if at { rs.t -= dt; if rs.kind == .throwIn { b.x = k.x; b.y = k.y; b.z = 2.1 } }
            let settled = rs.kind != .kickoff || rs.el > 8 || all.allSatisfy { hyp($0.x - $0.tx, $0.y - $0.ty) < 2 }
            if at && rs.t <= 0 && settled { takeRestart() }
        } else if let o = o, phase == .play {
            let sp = hyp(o.vx, o.vy), ux = sp > 0.3 ? o.vx / sp : o.face, uy = sp > 0.3 ? o.vy / sp : 0
            let off = 0.55 + 0.25 * sin(clock * 9)
            b.x = o.x + ux * off; b.y = o.y + uy * off; b.z = o.i > 0 ? 0 : 1; b.vx = o.vx; b.vy = o.vy
        } else {
            b.owner = nil
            let px = b.x
            if b.z > 0 || b.vz > 0 {
                b.vz -= G * dt; b.z += b.vz * dt; b.x += b.vx * dt; b.y += b.vy * dt
                if b.z <= 0 {
                    b.z = 0
                    if b.vz < -2 { b.vz *= -0.45; b.vx *= 0.75; b.vy *= 0.75 } else { b.vz = 0 }
                }
            } else {
                let k = exp(-0.6 * dt)
                b.vx *= k; b.vy *= k
                if hyp(b.vx, b.vy) < 0.3 { b.vx = 0; b.vy = 0 }
                b.x += b.vx * dt; b.y += b.vy * dt
            }
            if phase == .goal && abs(b.x) > 54.3 && abs(px) <= 54.3 + 1e-6 { b.x = sgn(b.x) * 54.3; b.vx *= -0.15; b.vy *= 0.5 }
            if phase == .goal && abs(b.x) > 52.5 { b.z = min(b.z, 2.3); b.y = clamp(b.y, -3.5, 3.5) }
        }
        if phase == .play {
            if b.owner == nil && b.z < 2.6 {
                var best: Player?, bd = 1e9
                for p in all {
                    if (p === b.kicker && b.kickT > 0) || p.stun > 0 || b.z > (p.i > 0 ? 2 : 2.6) { continue }
                    if let s = b.shot, !(p.i == 0 && p.t != s.t && s.out == .save) { continue }
                    let d = hyp(p.x - b.x, p.y - b.y)
                    if d < (p.i > 0 ? 1 : 1.7) && d < bd { best = p; bd = d }
                }
                if let p = best {
                    var take = true
                    if p.t != b.last && p !== b.recv && b.shot == nil {
                        if p.tried { take = false } else { p.tried = true; if rnd() < 0.45 { take = false } }
                    }
                    if take { gain(p) }
                }
            }
            if let o = b.owner, o.i > 0 {
                for q in teams[1 - o.t].players where q.stun <= 0 && hyp(q.x - o.x, q.y - o.y) < 1.1 && rnd() < dt * 0.6 {
                    o.stun = 0.8
                    if rnd() < 0.35 {
                        b.owner = nil; b.last = q.t; b.kicker = q; b.kickT = 0.3; b.vx = R(-5, 5); b.vy = R(-5, 5)
                    } else { gain(q) }
                    break
                }
            }
            if let o = b.owner { o.decide -= dt; if o.decide <= 0 { decide(o) } }
            if phase == .play { outOfPlay() }
        } else if phase == .goal {
            let g0 = gt
            gt -= dt
            if g0 > 0.3 && gt <= 0.3 { wipes += 1 }
            if gt <= 0 { startReplay() }
        } else if phase == .pause {
            bt -= dt
            if bt <= 0 { endBreak() }
        }
    }
}
