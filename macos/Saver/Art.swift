//
//  Art.swift
//  Football Screensaver
//
//  Every bitmap the broadcast shows, rasterised once (per match, on a
//  background queue) and then only ever handed to CALayer.contents — the
//  per-frame path never draws. Ports of pitchTex / crowdTex / boardTex /
//  figure in mockup.html.
//

import AppKit

let RUNF = 12, WALKF = 8, IDLE = RUNF + WALKF
/// Sprite box in figure units (feet at y = 46, head top ≈ 2.5); rendered at SS px per unit.
let SW = 24.0, SH = 48.0, SS = 3.0
let SKINS = ["#f1c7a5", "#c68a5d", "#7a4a2c"]
let LOOKS = [("#f1c7a5", "#2a1a10"), ("#f1c7a5", "#b88a4a"), ("#c68a5d", "#111111"), ("#7a4a2c", "#111111")]
let ADS = [("NORTHSTAR BANK", "#0b3d91", "#ffffff"), ("KICK COLA", "#d61f45", "#ffffff"), ("VOLTA ENERGY", "#111111", "#facc15"),
           ("ORBIT AIR", "#f4f4f4", "#0b3d91"), ("FIELDLINE", "#0f7a3a", "#ffffff"), ("PENSIVE TYRES", "#facc15", "#111111")]
let X0 = -62.0, X1 = 62.0, Y0 = -40.0, Y1 = 40.0, PPM = 14.0
let STAND = 34.0, SC = cos(35 * Double.pi / 180), SN = sin(35 * Double.pi / 180)

func cg(_ hex: String, _ a: Double = 1) -> CGColor {
    let c = hexRGB(hex)
    return CGColor(red: c[0] / 255, green: c[1] / 255, blue: c[2] / 255, alpha: a)
}
func shade(_ hex: String, _ f: Double) -> CGColor {
    let c = hexRGB(hex)
    return CGColor(red: c[0] * f / 255, green: c[1] * f / 255, blue: c[2] * f / 255, alpha: 1)
}
@inline(__always) func pt(_ x: Double, _ y: Double) -> CGPoint { CGPoint(x: x, y: y) }

/// A bitmap; `flipped` gives a y-down drawing space (canvas-style, row 0 at the top).
func bitmap(_ w: Int, _ h: Int, flipped: Bool, _ draw: (CGContext) -> Void) -> CGImage {
    let ctx = CGContext(data: nil, width: max(w, 1), height: max(h, 1), bitsPerComponent: 8, bytesPerRow: 0,
                        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    if flipped { ctx.translateBy(x: 0, y: CGFloat(h)); ctx.scaleBy(x: 1, y: -1) }
    draw(ctx)
    return ctx.makeImage()!
}

func condensed(_ size: Double, _ weight: NSFont.Weight) -> NSFont {
    NSFont.systemFont(ofSize: size, weight: weight, width: .condensed)
}

struct Art {
    let sprites: [[[CGImage]]]  // [kit][look][frame]
    let crowdFar: [CGImage], crowdEnd: [[CGImage]]  // [calm, cheering]; end: [left, right]
    let shadow: CGImage, ball: CGImage, ballShadow: CGImage

    init(kits: [[String]]) {
        sprites = kits.map { kit in
            LOOKS.map { look in
                (0...IDLE).map { f in
                    bitmap(Int(SW * SS), Int(SH * SS), flipped: true) { c in c.scaleBy(x: SS, y: SS); Art.figure(c, kit, look.0, look.1, f) }
                }
            }
        }
        let k2 = [kits[0][0], kits[1][0]]
        crowdFar = [Art.crowd(150, STAND, k2, false), Art.crowd(150, STAND, k2, true)]
        crowdEnd = (0..<2).map { _ in [Art.crowd(80, STAND, k2, false), Art.crowd(80, STAND, k2, true)] }
        shadow = bitmap(144, 20, flipped: true) { c in
            c.setFillColor(CGColor(gray: 0, alpha: 0.1))
            for o in [-1.0, 1.0] { c.fillEllipse(in: CGRect(x: 72 + o * 36 - 36, y: 10 - 5.6, width: 72, height: 11.2)) }
            c.setFillColor(CGColor(gray: 0, alpha: 0.28))
            c.fillEllipse(in: CGRect(x: 72 - 28, y: 10 - 8, width: 56, height: 16))
        }
        ball = bitmap(32, 32, flipped: true) { c in
            let g = CGGradient(colorsSpace: nil, colors: [cg("#ffffff"), cg("#b9bec6")] as CFArray, locations: [0, 1])!
            c.addEllipse(in: CGRect(x: 0, y: 0, width: 32, height: 32)); c.clip()
            c.drawRadialGradient(g, startCenter: pt(11, 11), startRadius: 1.6, endCenter: pt(16, 16), endRadius: 16, options: .drawsAfterEndLocation)
        }
        ballShadow = bitmap(32, 12, flipped: false) { c in
            c.setFillColor(CGColor(gray: 0, alpha: 1)); c.fillEllipse(in: CGRect(x: 0, y: 0, width: 32, height: 12))
        }
    }

    // MARK: - Pitch, crowd, boards

    /// World rect x0...x1 × y0...y1 at ppm px/m: the whole pitch, or a sharper crop of one goal end for the replay camera.
    static func pitch(_ ppm: Double, _ x0: Double, _ x1: Double, _ y0: Double, _ y1: Double) -> CGImage {
        bitmap(Int((x1 - x0) * ppm), Int((y1 - y0) * ppm), flipped: false) { c in
            c.scaleBy(x: ppm, y: ppm); c.translateBy(x: -x0, y: -y0)
            for i in -2..<22 {
                c.setFillColor(cg(i & 1 != 0 ? "#3d8a34" : "#47983d"))
                c.fill(CGRect(x: -52.5 + Double(i) * 5.25, y: Y0, width: 5.25, height: Y1 - Y0))
            }
            c.setFillColor(CGColor(gray: 0, alpha: 0.13))
            c.fill([CGRect(x: X0, y: Y0, width: X1 - X0, height: -34 - Y0), CGRect(x: X0, y: 34, width: X1 - X0, height: Y1 - 34),
                    CGRect(x: X0, y: -34, width: -52.5 - X0, height: 68), CGRect(x: 52.5, y: -34, width: X1 - 52.5, height: 68)])
            let dark = CGColor(gray: 0, alpha: 0.07), light = CGColor(gray: 1, alpha: 0.05)
            for _ in 0..<Int((x1 - x0) * (y1 - y0) * 6) {
                c.setFillColor(rnd() < 0.5 ? dark : light)
                c.fill(CGRect(x: R(x0, x1), y: R(y0, y1), width: 0.12, height: 0.12))
            }
            c.setStrokeColor(CGColor(gray: 1, alpha: 0.93)); c.setLineWidth(0.12); c.setFillColor(CGColor(gray: 1, alpha: 1))
            func circ(_ x: Double, _ y: Double, _ r: Double, _ a: Double, _ b: Double) {
                c.beginPath(); c.addArc(center: pt(x, y), radius: r, startAngle: a, endAngle: b, clockwise: false); c.strokePath()
            }
            func spot(_ x: Double, _ y: Double) { c.fillEllipse(in: CGRect(x: x - 0.15, y: y - 0.15, width: 0.3, height: 0.3)) }
            c.stroke(CGRect(x: -52.5, y: -34, width: 105, height: 68))
            c.strokeLineSegments(between: [pt(0, -34), pt(0, 34)])
            circ(0, 0, 9.15, 0, TAU); spot(0, 0)
            let a = acos(5.5 / 9.15)
            for s in [-1.0, 1.0] {
                c.stroke(CGRect(x: s > 0 ? 36 : -52.5, y: -20.16, width: 16.5, height: 40.32))
                c.stroke(CGRect(x: s > 0 ? 47 : -52.5, y: -9.16, width: 5.5, height: 18.32))
                spot(s * 41.5, 0)
                if s > 0 { circ(41.5, 0, 9.15, Double.pi - a, Double.pi + a) } else { circ(-41.5, 0, 9.15, -a, a) }
                for t in [-1.0, 1.0] { let a0 = atan2(-t, -s); circ(s * 52.5, t * 34, 1, a0 - Double.pi / 4, a0 + Double.pi / 4) }
            }
        }
    }

    static func crowd(_ wm: Double, _ hm: Double, _ kits: [String], _ jump: Bool) -> CGImage {
        let P = 8.0, W = Int(wm * P), H = Int(hm * P), rh = 0.85
        let cols = ["#d9d9d9", "#3b3f4a", "#23395d", "#555555", "#c9b27c", "#2d2d2d", "#6b2020", "#1f3b2a"]
        return bitmap(W, H, flipped: true) { c in
            var r = 0
            while Double(r) * rh < hm {
                let y = Double(r) * rh * P
                c.setFillColor(cg(r & 1 == 1 ? "#1b1e27" : "#16181f")); c.fill(CGRect(x: 0, y: y, width: Double(W), height: rh * P))
                var s = 0
                while Double(s) * 0.55 < wm {
                    defer { s += 1 }
                    if rnd() < 0.09 { continue }
                    let px = (Double(s) * 0.55 + R(-0.08, 0.08)) * P, up = jump && rnd() < 0.6 ? 0.18 * P : 0, v = rnd()
                    c.setFillColor(cg(v < 0.3 ? kits[0] : v < 0.45 ? kits[1] : cols.randomElement()!))
                    c.fill(CGRect(x: px - 0.2 * P, y: y + 0.36 * P - up, width: 0.4 * P, height: 0.45 * P))
                    c.setFillColor(cg(SKINS.randomElement()!))
                    c.fillEllipse(in: CGRect(x: px - 0.12 * P, y: y + 0.14 * P - up, width: 0.24 * P, height: 0.24 * P))
                    if up > 0 && rnd() < 0.5 { c.fill(CGRect(x: px - 0.28 * P, y: y + 0.02 * P - up, width: 0.06 * P, height: 0.3 * P)) }
                }
                r += 1
            }
            let g = CGGradient(colorsSpace: nil, colors: [CGColor(gray: 0, alpha: 0.7), CGColor(gray: 0, alpha: 0.15), CGColor(gray: 0, alpha: 0)] as CFArray,
                               locations: [0, 0.35, 1])!
            c.drawLinearGradient(g, start: pt(0, 0), end: pt(0, Double(H)), options: [])
            c.setFillColor(cg("#04060c", 0.25)); c.fill(CGRect(x: 0, y: 0, width: W, height: H))
        }
    }

    /// LED board of `wm` metres, panels rotated by `off`. Draws text, so build on the main thread.
    static func board(_ wm: Double, _ off: Int) -> CGImage {
        let P = 24.0, W = Int(wm * P), H = Int(0.9 * P)
        return bitmap(W, H, flipped: true) { c in
            NSGraphicsContext.saveGraphicsState()
            NSGraphicsContext.current = NSGraphicsContext(cgContext: c, flipped: true)
            let font = condensed(0.62 * P, .bold)
            var i = 0
            while Double(i) * 8 < wm {
                let ad = ADS[(i + off) % ADS.count]
                c.setFillColor(cg(ad.1)); c.fill(CGRect(x: Double(i) * 8 * P, y: 0, width: 8 * P, height: Double(H)))
                let s = NSAttributedString(string: ad.0, attributes: [.font: font, .foregroundColor: NSColor(cgColor: cg(ad.2))!])
                let sw = Double(s.size().width), sh = Double(s.size().height)
                s.draw(at: NSPoint(x: (Double(i) * 8 + 4) * P - sw / 2, y: (Double(H) - sh) / 2))
                i += 1
            }
            c.setFillColor(CGColor(gray: 0, alpha: 0.35)); c.fill(CGRect(x: 0, y: H - 2, width: W, height: 2))
            NSGraphicsContext.restoreGraphicsState()
        }
    }

    // MARK: - Players

    /// One leg's (thigh, knee flex) in radians (+ = forward) at cycle position u, 0 = foot strike.
    static func gait(_ u: Double, _ run: Bool) -> (Double, Double) {
        let ST = run ? 0.38 : 0.6, T0 = run ? 0.45 : 0.32, T1 = run ? -0.55 : -0.3
        if u < ST { let s = u / ST; return (T0 + (T1 - T0) * s, (run ? 0.3 : 0.08) + (run ? 0.28 : 0.1) * sin(s * Double.pi)) }
        let s = (u - ST) / (1 - ST), sm = (1 - cos(Double.pi * s)) / 2
        return (T1 + (T0 - T1) * sm + (run ? 0.4 : 0.08) * pow(sin(Double.pi * s), 2),
                (run ? 0.3 : 0.08) + (run ? 1.55 : 0.9) * pow(sin(Double.pi * pow(s, 0.75)), 1.4))
    }

    /// Side-view footballer facing +x, in a y-down context scaled to figure units.
    static func figure(_ c: CGContext, _ k: [String], _ skin: String, _ hair: String, _ fr: Int) {
        let run = fr < RUNF, walk = fr >= RUNF && fr < IDLE, L = 8.6
        let u = run ? Double(fr) / Double(RUNF) : Double(fr - RUNF) / Double(WALKF)
        typealias Leg = (back: Bool, ps: Double, knee: (Double, Double), foot: (Double, Double))
        let legs: [Leg] = [true, false].map { back in
            let (th, kn) = run || walk ? gait((u + (back ? 0.5 : 0)).truncatingRemainder(dividingBy: 1), run) : (back ? -0.1 : 0.1, 0.12)
            let ps = th - kn, knee = (L * sin(th), L * cos(th))
            return (back, ps, knee, (knee.0 + L * sin(ps), knee.1 + L * cos(ps)))
        }
        // Lowest foot on the ground (stance knee flex gives the bob), plus a short flight phase between running strides.
        let u2 = (u * 2).truncatingRemainder(dividingBy: 1)
        let hy = 46 - max(legs[0].foot.1, legs[1].foot.1) - (run && u2 > 0.76 ? 1.1 * sin((u2 - 0.76) / 0.24 * Double.pi) : 0)
        c.setLineCap(.round)
        c.translateBy(x: 12, y: hy)
        func seg(_ a: (Double, Double), _ b: (Double, Double), _ col: CGColor, _ w: Double) {
            c.setStrokeColor(col); c.setLineWidth(w)
            c.strokeLineSegments(between: [pt(a.0, a.1), pt(b.0, b.1)])
        }
        func leg(_ l: Leg) {
            let f = l.back ? 0.72 : 1.0, grounded = l.foot.1 + hy > 45.6
            let al = grounded ? 0 : clamp(-l.ps * 0.9, -0.3, 1), d = (cos(al), sin(al))
            seg((0, 0), l.knee, shade(skin, f), 3.4)
            seg(l.knee, l.foot, shade(k[2], f), 2.9)
            seg((l.foot.0 - 0.8 * d.0, l.foot.1 - 0.8 * d.1), (l.foot.0 + 2.4 * d.0, l.foot.1 + 2.4 * d.1), cg(l.back ? "#000000" : "#161616"), 2.3)
        }
        func arm(_ back: Bool) {
            let sg = back ? -1.0 : 1.0
            let a = run ? -0.6 * cos(u * TAU) * sg + 0.1 : walk ? -0.3 * cos(u * TAU) * sg : 0.08
            let el = (5.5 * sin(a), -16 + 5.5 * cos(a)), be = a + (run ? 1.45 : walk ? 0.5 : 0.15)
            let hd = (el.0 + 5 * sin(be), el.1 + 5 * cos(be)), f = back ? 0.72 : 1.0
            seg((0, -16), el, shade(skin, f), 2.4)
            seg(el, hd, shade(skin, f), 2.2)
            seg((0, -16), (2.6 * sin(a), -16 + 2.6 * cos(a)), shade(k[0], f), 3)
        }
        let lean = run ? 0.14 : walk ? 0.03 : 0
        c.saveGState(); c.rotate(by: lean); arm(true); c.restoreGState()
        legs.forEach(leg)
        c.rotate(by: lean)
        c.setFillColor(cg(k[1])); c.fill(CGRect(x: -4, y: -4.5, width: 8, height: 6))
        c.setFillColor(cg(k[0]))
        c.addPath(CGPath(roundedRect: CGRect(x: -4.5, y: -18.5, width: 9, height: 15), cornerWidth: 2.5, cornerHeight: 2.5, transform: nil))
        c.fillPath()
        c.setFillColor(CGColor(gray: 0, alpha: 0.18)); c.fill(CGRect(x: -4.5, y: -18.5, width: 2, height: 15))
        arm(false)
        c.setFillColor(cg(skin)); c.fillEllipse(in: CGRect(x: 0.5 - 3.6, y: -22.3 - 3.6, width: 7.2, height: 7.2))
        c.setFillColor(cg(hair))
        c.beginPath(); c.addArc(center: pt(0.3, -22.9), radius: 3.7, startAngle: Double.pi * 0.95, endAngle: Double.pi * 2.1, clockwise: false)
        c.fillPath()
    }
}
