//
//  Broadcast.swift
//  Football Screensaver
//
//  The whole picture as plain CALayers (no Metal, no per-frame
//  CGContext.draw — see docs/DESIGN.md): world planes carry a pre-rasterised
//  CGImage and a per-frame CATransform3D from the camera; players, ball and
//  shadows are sprite layers; goals are CAShapeLayers. Ports render(),
//  view() and css() in mockup.html.
//

import AppKit
import QuartzCore

struct V3 {
    var x, y, z: Double
    init(_ x: Double, _ y: Double, _ z: Double) { self.x = x; self.y = y; self.z = z }
    static func - (a: V3, b: V3) -> V3 { V3(a.x - b.x, a.y - b.y, a.z - b.z) }
    static func * (a: V3, s: Double) -> V3 { V3(a.x * s, a.y * s, a.z * s) }
    func dot(_ b: V3) -> Double { x * b.x + y * b.y + z * b.z }
    func cross(_ b: V3) -> V3 { V3(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x) }
    var unit: V3 { self * (1 / dot(self).squareRoot()) }
}

struct Basis {
    let C: V3, f: V3, r: V3, u: V3
    init(_ C: V3, _ T: V3) {
        self.C = C; f = (T - C).unit; r = f.cross(V3(0, 0, 1)).unit; u = r.cross(f)
    }
}

/// A textured world rectangle: texture pixel (u, v) (v down) sits at O + SU·u + SV·v.
final class Plane {
    enum Tag: Equatable { case main, end(Double), close(Double) }
    let layer = CALayer(), tag: Tag, O: V3, SU: V3, SV: V3, N: V3, h: Double, corners: [V3]
    var images: [CGImage] = [] { didSet { layer.contents = images.first } }

    init(_ w: Int, _ h: Int, _ O: V3, _ U: V3, _ V: V3, _ ppm: Double, _ tag: Tag = .main) {
        self.tag = tag; self.O = O; SU = U * (1 / ppm); SV = V * (1 / ppm); N = SU.cross(SV).unit; self.h = Double(h)
        let su = SU, sv = SV
        func at(_ a: Double, _ b: Double) -> V3 { V3(O.x + su.x * a + sv.x * b, O.y + su.y * a + sv.y * b, O.z + su.z * a + sv.z * b) }
        corners = [at(0, 0), at(Double(w), 0), at(0, Double(h)), at(Double(w), Double(h))]
        layer.anchorPoint = .zero
        layer.bounds = CGRect(x: 0, y: 0, width: w, height: h)
        layer.position = .zero
        layer.minificationFilter = .trilinear
    }

    func shown(replaySide s: Double) -> Bool {
        switch tag {
        case .main: return s == 0
        case .end(let e): return s == 0 || s == e
        case .close(let e): return s == e
        }
    }
}

final class Broadcast {
    let root = CALayer()
    var topInset: CGFloat = 0
    var backingScale: CGFloat = 1 { didSet { hud.scale = backingScale } }

    private var match = Match(first: true), pending: Match?, art: Art?, token = 0
    private var W = 0.0, H = 0.0, F0 = 1.0, F = 1.0, V = Basis(V3(0, -60, 25), V3(0, 5, 0))
    private var cam = (x: 0.0, y: 0.0, z: 1.0, ry: 0.0), acc = 0.0, boardT = 0.0, boardOff = 0
    private var planes: [Plane] = [], boards: [(Plane, Double, [CGImage])] = []
    private let world = CALayer(), actorsLayer = CALayer(), shadowsLayer = CALayer()
    private var sprites: [CALayer] = [], shadows: [CALayer] = []
    private let ballLayer = CALayer(), ballShadow = CALayer()
    private var goalNet: [CAShapeLayer] = [], goalFrame: [CAShapeLayer] = []
    private let hud = HUD()
    private(set) var framesDrawn = 0

    init() {
        let bg = CAGradientLayer()
        bg.colors = [cg("#04060c"), cg("#0a0f1c"), cg("#0b1410")]
        bg.locations = [0, 0.45, 1]
        bg.startPoint = CGPoint(x: 0.5, y: 1); bg.endPoint = CGPoint(x: 0.5, y: 0)
        bg.name = "bg"
        root.addSublayer(bg)
        root.addSublayer(world)
        for l in [world, actorsLayer, shadowsLayer] { l.anchorPoint = .zero; l.position = .zero }

        // Order matters: layers are composited flat, back to front.
        let far = Plane(1200, 272, V3(-75, 40 + STAND * SC, STAND * SN), V3(1, 0, 0), V3(0, -SC, -SN), 8)
        let endL = Plane(640, 272, V3(-62 - STAND * SC, -40, STAND * SN), V3(0, 1, 0), V3(SC, 0, -SN), 8, .end(-1))
        let endR = Plane(640, 272, V3(62 + STAND * SC, 40, STAND * SN), V3(0, -1, 0), V3(-SC, 0, -SN), 8, .end(1))
        let pitch = Plane(Int((X1 - X0) * PPM), Int((Y1 - Y0) * PPM), V3(X0, Y1, 0), V3(1, 0, 0), V3(0, -1, 0), PPM)
        let close = [-1.0, 1.0].map { s -> Plane in
            let a = s > 0 ? 40.0 : -62.0
            return Plane(22 * 32, 68 * 32, V3(a, 40, 0), V3(1, 0, 0), V3(0, -1, 0), 32, .close(s))
        }
        planes = [far, endL, endR, pitch] + close
        for (wm, O, U, tag) in [(120.0, V3(-60, 37.5, 0.9), V3(1, 0, 0), Plane.Tag.main), (48, V3(-57, -24, 0.9), V3(0, 1, 0), Plane.Tag.end(-1)),
                                (48, V3(57, 24, 0.9), V3(0, -1, 0), Plane.Tag.end(1))] {
            let imgs = (0..<ADS.count).map { Art.board(wm, $0) }
            let p = Plane(imgs[0].width, imgs[0].height, O, U, V3(0, 0, -1), 24, tag)
            p.images = [imgs[0]]
            boards.append((p, wm, imgs)); planes.append(p)
        }
        planes.forEach { world.addSublayer($0.layer) }
        // Pitch images don't depend on the kits: build once, off the main thread.
        DispatchQueue.global(qos: .userInitiated).async {
            let main = Art.pitch(PPM, X0, X1, Y0, Y1), ends = [Art.pitch(32, -62, -40, -28, 40), Art.pitch(32, 40, 62, -28, 40)]
            DispatchQueue.main.async { pitch.images = [main]; close[0].images = [ends[0]]; close[1].images = [ends[1]] }
        }

        // Actors live outside `world`, never among the perspective-transformed planes, so nothing can sort them behind the pitch.
        shadowsLayer.name = "shadows"; actorsLayer.name = "actors"
        root.addSublayer(shadowsLayer)
        root.addSublayer(actorsLayer)
        for _ in 0..<23 {
            let s = CALayer(), a = CALayer()
            a.anchorPoint = CGPoint(x: 0.5, y: 2 / SH)
            shadowsLayer.addSublayer(s); actorsLayer.addSublayer(a)
            shadows.append(s); sprites.append(a)
        }
        shadowsLayer.addSublayer(ballShadow)
        actorsLayer.addSublayer(ballLayer)
        for _ in 0..<2 {
            let n = CAShapeLayer(), f = CAShapeLayer()
            n.strokeColor = CGColor(gray: 1, alpha: 0.32); f.strokeColor = CGColor(gray: 1, alpha: 1)
            for l in [n, f] { l.fillColor = nil; l.lineCap = .round; l.lineJoin = .round; actorsLayer.addSublayer(l) }
            goalNet.append(n); goalFrame.append(f)
        }
        let vig = CAGradientLayer()
        vig.type = .radial
        vig.colors = [CGColor(gray: 0, alpha: 0), CGColor(gray: 0, alpha: 0), CGColor(gray: 0, alpha: 0.38)]
        vig.locations = [0, 0.55, 1]
        vig.startPoint = CGPoint(x: 0.5, y: 0.55); vig.endPoint = CGPoint(x: 1, y: 1)
        vig.name = "vig"
        root.addSublayer(vig)
        root.addSublayer(hud.root)
        buildArt(for: match)
    }

    private func buildArt(for m: Match) {
        token += 1
        let t = token, kits = m.kits
        DispatchQueue.global(qos: .userInitiated).async {
            let a = Art(kits: kits)
            DispatchQueue.main.async { [weak self] in
                guard let self = self, t == self.token else { return }
                self.art = a; self.match = m; self.pending = nil
                self.planes[0].images = a.crowdFar
                self.planes[1].images = a.crowdEnd[0]
                self.planes[2].images = a.crowdEnd[1]
                self.hud.reset()
            }
        }
    }

    func setSize(_ size: CGSize) {
        guard Double(size.width) != W || Double(size.height) != H else { return }
        W = Double(size.width); H = Double(size.height)
        F0 = max(W, H * 1.3) / (2 * tan(19 * Double.pi / 180))
        CATransaction.begin(); CATransaction.setDisableActions(true)
        root.frame = CGRect(origin: .zero, size: size)
        for l in root.sublayers ?? [] where l.name != nil { l.frame = root.bounds }
        world.bounds = root.bounds; hud.root.frame = root.bounds
        CATransaction.commit()
    }

    // MARK: - Per frame

    func tick(_ dt: Double) {
        guard art != nil, W >= 1, H >= 1 else { return }
        acc += dt
        while acc >= 1.0 / 60 {
            match.step(1.0 / 60); acc -= 1.0 / 60
            if match.finished && pending == nil { let m = Match(first: false); pending = m; buildArt(for: m) }
        }
        boardT += dt
        if boardT > 10 { boardT = 0; boardOff += 1; for (p, _, imgs) in boards { p.images = [imgs[boardOff % imgs.count]] } }
        CATransaction.begin(); CATransaction.setDisableActions(true)
        render(dt)
        hud.update(match, W: W, H: H, top: Double(topInset), dt: dt)
        CATransaction.commit()
        framesDrawn += 1
    }

    private func view() {
        let s = match.replaySide
        if s != 0 { V = Basis(V3(s * 31, -5, 2.4), V3(s * 53, cam.ry, 1)); F = F0 * 1.5; return }
        let C = V3(cam.x * 0.55, -60, 25), T = V3(cam.x, cam.y + 5 - 9 * clamp(H / W - 0.7, 0, 1), 0)
        // Keep every plane corner well in front of the lens: Core Animation misbehaves at w <= 0.
        func ok(_ b: Basis) -> Bool { planes.allSatisfy { p in !p.shown(replaySide: 0) || p.corners.allSatisfy { q in (q - C).dot(b.f) > 4 } } }
        var b = Basis(C, T), hi = 1.0
        for _ in 0..<12 where !ok(b) {
            hi /= 2
            b = Basis(C, V3(C.x + (T.x - C.x) * hi, T.y, 0))
        }
        V = b; F = F0 * cam.z
    }

    /// Screen position in points, y down, plus camera depth.
    private func proj(_ x: Double, _ y: Double, _ z: Double) -> (x: Double, y: Double, z: Double) {
        let d = V3(x - V.C.x, y - V.C.y, z - V.C.z), zc = d.dot(V.f)
        return (W / 2 + F * d.dot(V.r) / zc, H / 2 - F * d.dot(V.u) / zc, zc)
    }

    /// Maps a plane layer's local points (y up, anchor at its bottom-left) to the view (y up), perspective included.
    /// Output z is 0: any depth would let Core Animation sort the planes in front of the sprite layers.
    private func transform(_ p: Plane) -> CATransform3D {
        func col(_ v: V3, _ t: Double) -> [Double] {
            let xc = v.dot(V.r), yc = v.dot(V.u), zc = v.dot(V.f), X = F * xc + W / 2 * zc, Y = -F * yc + H / 2 * zc
            return [X, H * zc - Y, t, zc]
        }
        let cu = col(p.SU, 0), cv = col(p.SV, 0), cn = col(p.N, 0), co = col(p.O - V.C, 0), h = p.h
        return CATransform3D(m11: cu[0], m12: cu[1], m13: cu[2], m14: cu[3],
                             m21: -cv[0], m22: -cv[1], m23: -cv[2], m24: -cv[3],
                             m31: cn[0], m32: cn[1], m33: cn[2], m34: cn[3],
                             m41: co[0] + h * cv[0], m42: co[1] + h * cv[1], m43: co[2] + h * cv[2], m44: co[3] + h * cv[3])
    }

    private func render(_ dt: Double) {
        guard let art = art else { return }
        let M = match, b = M.ball, rp = M.phase == .replay
        var focus: (Double, Double, Double) = (b.x, b.y, b.vx)
        if M.phase == .pause { focus = (0, 0, 0) }
        if M.phase == .goal, let sc = M.scorer { focus = (sc.x, sc.y, sc.vx) }
        let tx = clamp(focus.0 + focus.2 * 0.6, -41, 41), ty = clamp(focus.1 * 0.55, -16, 16), k = 1 - exp(-dt * 1.8)
        if M.snapCam { M.snapCam = false; cam.ry = clamp(b.y * 0.3, -2, 2) }
        cam.ry += (clamp(b.y * 0.3, -2, 2) - cam.ry) * (1 - exp(-dt * 2))
        if !rp { cam.x += (tx - cam.x) * k; cam.y += (ty - cam.y) * k * 0.6 }
        let zt = rp ? cam.z : M.phase == .pause ? 0.8 : (b.z > 3 || hyp(b.vx, b.vy) > 16) ? 0.9 : M.phase == .goal ? 1.12 : 1
        cam.z += (zt - cam.z) * (1 - exp(-dt * 0.9))
        view()

        let side = M.replaySide, cheer = M.crowdCheering && Int(M.clock * 4) & 1 == 1
        for p in planes {
            let on = p.shown(replaySide: side)
            p.layer.isHidden = !on
            guard on else { continue }
            p.layer.transform = transform(p)
            if p.images.count > 1 { p.layer.contents = p.images[cheer ? 1 : 0] }
        }

        // Players, referee, ball, goals: sorted by zPosition inside actorsLayer.
        for (i, p) in M.actors.enumerated() {
            let a = sprites[i], s = shadows[i], pf = proj(p.x, p.y, 0)
            guard pf.z > 1 else { a.isHidden = true; s.isHidden = true; continue }
            a.isHidden = false; s.isHidden = false
            let ph = proj(p.x, p.y, 1.85), sc = (pf.y - ph.y) / 43, sp = hyp(p.vx, p.vy)
            let sv = p.vx * V.r.x + p.vy * V.r.y
            if sp > 0.6 && abs(sv) > 0.3 { p.face = sgn(sv) } else if sp <= 0.6 { p.face = sgn((b.x - p.x) * V.r.x + (b.y - p.y) * V.r.y) }
            let cyc = { (n: Int) -> Int in ((Int(p.ph / TAU * Double(n)) % n) + n) % n }
            let fr = sp < 0.5 ? IDLE : sp < 2.4 ? RUNF + cyc(WALKF) : cyc(RUNF)
            a.contents = art.sprites[p.kit][p.look][fr]
            a.bounds = CGRect(x: 0, y: 0, width: SW * sc, height: SH * sc)
            a.position = CGPoint(x: pf.x, y: H - pf.y)
            a.transform = p.face < 0 ? CATransform3DMakeScale(-1, 1, 1) : CATransform3DIdentity
            a.zPosition = -pf.z / 1000
            s.contents = art.shadow
            s.bounds = CGRect(x: 0, y: 0, width: 36 * sc, height: 5 * sc)
            s.position = CGPoint(x: pf.x, y: H - pf.y)
        }
        let bp = proj(b.x, b.y, b.z + 0.11), bs = proj(b.x, b.y, 0)
        ballLayer.isHidden = bp.z <= 1; ballShadow.isHidden = bp.z <= 1
        if bp.z > 1 {
            let r = max(1.3, F * 0.11 / bp.z)
            ballLayer.contents = art.ball
            ballLayer.bounds = CGRect(x: 0, y: 0, width: 2 * r, height: 2 * r)
            ballLayer.position = CGPoint(x: bp.x, y: H - bp.y)
            ballLayer.zPosition = -(bp.z - 0.3) / 1000
            ballShadow.contents = art.ballShadow
            ballShadow.bounds = CGRect(x: 0, y: 0, width: 2.2 * r, height: 0.8 * r)
            ballShadow.position = CGPoint(x: bs.x, y: H - bs.y)
            ballShadow.opacity = Float(0.35 / (1 + b.z * 0.4))
        }
        for (gi, s) in [-1.0, 1.0].enumerated() { drawGoal(gi, s) }
    }

    private func drawGoal(_ gi: Int, _ s: Double) {
        let n = goalNet[gi], f = goalFrame[gi], d = proj(s * 53, 0, 0).z
        n.isHidden = d <= 1; f.isHidden = d <= 1
        guard d > 1 else { return }
        let x0 = s * 52.5, x1 = s * 54.5
        func path(_ lines: [[(Double, Double, Double)]]) -> CGPath {
            let p = CGMutablePath()
            for l in lines {
                let q = l.map { proj($0.0, $0.1, $0.2) }
                if q.contains(where: { $0.z <= 0.5 }) { continue }
                p.addLines(between: q.map { CGPoint(x: $0.x, y: H - $0.y) })
            }
            return p
        }
        var net: [[(Double, Double, Double)]] = []
        var y = -3.66
        while y <= 3.67 { net.append([(x0, y, 2.44), (x1, y, 1.9), (x1, y, 0)]); y += 0.61 }
        var z = 0.0
        while z <= 2 {
            net.append([(x0, -3.66, min(2.44, z * 1.22)), (x1, -3.66, z), (x1, 3.66, z), (x0, 3.66, min(2.44, z * 1.22))]); z += 0.4
        }
        n.path = path(net); n.lineWidth = max(0.6, F * 0.025 / d); n.zPosition = -d / 1000
        f.path = path([[(x0, -3.66, 0), (x0, -3.66, 2.44), (x0, 3.66, 2.44), (x0, 3.66, 0)]]); f.lineWidth = max(0.6, F * 0.12 / d); f.zPosition = -d / 1000
    }

    // MARK: - Still (cacheDisplay / wallpaper snapshot)

    /// A CPU approximation of the current frame: flat-shaded stands and pitch stripes, lines, sprites.
    func still(width: Int, height: Int) -> CGImage? {
        guard W >= 1, H >= 1, let art = art else { return nil }
        view()
        let k = Double(width) / W, M = match
        return bitmap(width, height, flipped: true) { c in
            c.scaleBy(x: k, y: k)
            c.setFillColor(cg("#070a12")); c.fill(CGRect(x: 0, y: 0, width: W, height: H))
            func poly(_ pts: [(Double, Double, Double)], _ col: CGColor) {
                let q = pts.map { proj($0.0, $0.1, $0.2) }
                guard q.allSatisfy({ $0.z > 0.5 }) else { return }
                c.setFillColor(col); c.addLines(between: q.map { pt($0.x, $0.y) }); c.closePath(); c.fillPath()
            }
            for p in planes where p.shown(replaySide: M.replaySide) && p.images.count > 1 {
                let q = p.corners
                poly([(q[0].x, q[0].y, q[0].z), (q[1].x, q[1].y, q[1].z), (q[3].x, q[3].y, q[3].z), (q[2].x, q[2].y, q[2].z)], cg("#2a2e3a"))
            }
            for i in -2..<22 {
                let a = max(X0, -52.5 + Double(i) * 5.25), b = min(X1, a + 5.25)
                if a < b { poly([(a, Y0, 0), (b, Y0, 0), (b, Y1, 0), (a, Y1, 0)], cg(i & 1 != 0 ? "#3d8a34" : "#47983d")) }
            }
            c.setStrokeColor(CGColor(gray: 1, alpha: 0.9)); c.setLineWidth(1.2)
            func line(_ pts: [(Double, Double)]) {
                let q = pts.map { proj($0.0, $0.1, 0) }
                guard q.allSatisfy({ $0.z > 0.5 }) else { return }
                c.addLines(between: q.map { pt($0.x, $0.y) }); c.strokePath()
            }
            line([(-52.5, -34), (52.5, -34), (52.5, 34), (-52.5, 34), (-52.5, -34)])
            line([(0, -34), (0, 34)])
            line((0...48).map { (9.15 * cos(Double($0) / 48 * TAU), 9.15 * sin(Double($0) / 48 * TAU)) })
            for s in [-1.0, 1.0] {
                line([(s * 52.5, -20.16), (s * 36, -20.16), (s * 36, 20.16), (s * 52.5, 20.16)])
                line([(s * 52.5, -9.16), (s * 47, -9.16), (s * 47, 9.16), (s * 52.5, 9.16)])
            }
            for p in M.actors.sorted(by: { proj($0.x, $0.y, 0).z > proj($1.x, $1.y, 0).z }) {
                let pf = proj(p.x, p.y, 0)
                guard pf.z > 1 else { continue }
                let sc = (pf.y - proj(p.x, p.y, 1.85).y) / 43, img = art.sprites[p.kit][p.look][IDLE]
                c.saveGState()
                c.translateBy(x: pf.x, y: pf.y + 2 * sc); c.scaleBy(x: 1, y: -1)
                c.draw(img, in: CGRect(x: -SW * sc / 2, y: 0, width: SW * sc, height: SH * sc))
                c.restoreGState()
            }
        }
    }
}
