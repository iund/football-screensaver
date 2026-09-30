//
//  HUD.swift
//  Football Screensaver
//
//  TV graphics: score bug, LIVE/REPLAY tag, GOAL lower third, half/full-time
//  card and the REPLAY wipe. Each is rebuilt only when its text changes
//  (at most once a second for the clock); per frame only opacity/position move.
//

import AppKit
import QuartzCore

final class HUD {
    let root = CALayer()
    var scale: CGFloat = 1 { didSet { reset() } }

    private let bug = CALayer(), tag = CALayer(), lower = CALayer(), card = CALayer(), wipe = CALayer()
    private var tagDot = CALayer()
    private var bugKey = "", tagKey = "", lowerKey = "", cardKey = "", wipeKey = ""
    private var lowerE = 0.0, lowerW = 0.0, cardE = 0.0, wipeT = 1.0, wipes = 0, blink = 0.0
    private let navy = cg("#0a1226", 0.92)

    init() {
        root.anchorPoint = .zero
        for l in [bug, tag, lower, card] { l.anchorPoint = .zero; root.addSublayer(l) }
        root.addSublayer(wipe)
        bug.shadowOpacity = 0.45; bug.shadowOffset = CGSize(width: 0, height: -3); bug.shadowRadius = 6
        lower.shadowOpacity = 0.5; lower.shadowOffset = CGSize(width: 0, height: -3); lower.shadowRadius = 8
        card.shadowOpacity = 0.6; card.shadowRadius = 14
    }

    func reset() { bugKey = ""; tagKey = ""; lowerKey = ""; cardKey = ""; wipeKey = "" }

    // MARK: - Building blocks

    private func box(_ parent: CALayer, _ x: Double, _ y: Double, _ w: Double, _ h: Double, _ col: CGColor) {
        let l = CALayer()
        l.frame = CGRect(x: x, y: y, width: w, height: h); l.backgroundColor = col
        parent.addSublayer(l)
    }

    private func attr(_ s: String, _ f: NSFont, _ col: NSColor, _ kern: Double) -> NSAttributedString {
        NSAttributedString(string: s, attributes: [.font: f, .foregroundColor: col, .kern: kern])
    }

    private func tw(_ s: String, _ f: NSFont, _ kern: Double = 0) -> Double { Double(attr(s, f, .white, kern).size().width) }

    /// Text at x (or centred in `centre` = (x, width)), vertically centred on midY.
    @discardableResult
    private func text(_ parent: CALayer, _ s: String, _ f: NSFont, _ col: NSColor, x: Double = 0, midY: Double,
                      centre: (Double, Double)? = nil, kern: Double = 0) -> Double {
        let a = attr(s, f, col, kern), sz = a.size(), w = Double(sz.width), h = Double(sz.height)
        let l = CATextLayer()
        l.string = a; l.contentsScale = scale
        l.frame = CGRect(x: centre.map { $0.0 + ($0.1 - w) / 2 } ?? x, y: midY - h / 2, width: ceil(w) + 2, height: ceil(h))
        parent.addSublayer(l)
        return w
    }

    // MARK: - Per frame

    func update(_ m: Match, W: Double, H: Double, top: Double, dt: Double) {
        let u = clamp(W * 0.0145, 13, 24), bh = 1.9 * u, pad = 0.55 * u, topY = H - top - max(0.035 * H, 12) - bh
        let fB = condensed(u, .bold), fD = NSFont.monospacedDigitSystemFont(ofSize: u, weight: .semibold)
        let t0 = m.teams[0], t1 = m.teams[1], s = Int(m.time), over = m.time >= Double(m.half * 45 * 60)
        let clock = String(format: "%02d:%02d", s / 60, s % 60), size = "\(W)x\(H)x\(top)"

        let bk = [t0.def.code, t1.def.code, "\(t0.score)", "\(t1.score)", clock, "\(m.added)", "\(over)", size].joined(separator: "|")
        if bk != bugKey {
            bugKey = bk; bug.sublayers = nil
            var x = 0.0, w = 0.0
            let c0 = tw(t0.def.code, fB), c1 = tw(t1.def.code, fB), score = "\(t0.score) – \(t1.score)"
            w = pad + 0.68 * u + c0 + pad
            box(bug, x, 0, w, bh, navy); box(bug, x + pad, bh / 2 - 0.575 * u, 0.28 * u, 1.15 * u, cg(t0.def.kit[0]))
            text(bug, t0.def.code, fB, .white, x: x + pad + 0.68 * u, midY: bh / 2); x += w
            w = max(2.9 * u, tw(score, fB) + 2 * pad)
            box(bug, x, 0, w, bh, cg("#f1f3f8")); text(bug, score, fB, NSColor(cgColor: cg("#0a1226"))!, midY: bh / 2, centre: (x, w)); x += w
            w = pad + c1 + 0.68 * u + pad
            box(bug, x, 0, w, bh, navy); text(bug, t1.def.code, fB, .white, x: x + pad, midY: bh / 2)
            box(bug, x + pad + c1 + 0.4 * u, bh / 2 - 0.575 * u, 0.28 * u, 1.15 * u, cg(t1.def.kit[0])); x += w
            w = max(3 * u, tw(clock, fD) + 2 * pad)
            box(bug, x, 0, w, bh, cg("#1c2a4f")); text(bug, clock, fD, .white, midY: bh / 2, centre: (x, w)); x += w
            if over {
                let a = "+\(m.added)"
                w = tw(a, fB) + 2 * pad
                box(bug, x, 0, w, bh, cg("#d61f45")); text(bug, a, fB, .white, midY: bh / 2, centre: (x, w)); x += w
            }
            bug.frame = CGRect(x: 0.03 * W, y: topY, width: x, height: bh)
        }

        let rp = m.phase == .replay, tk = "\(rp)|\(size)"
        if tk != tagKey {
            tagKey = tk; tag.sublayers = nil
            let label = rp ? "REPLAY" : "LIVE", w = 0.6 * u + 0.55 * u + 0.4 * u + tw(label, fB) + 0.6 * u
            box(tag, 0, 0, w, bh, rp ? cg("#d61f45") : cg("#0a1226", 0.75))
            tagDot = CALayer()
            tagDot.frame = CGRect(x: 0.6 * u, y: bh / 2 - 0.275 * u, width: 0.55 * u, height: 0.55 * u)
            tagDot.cornerRadius = 0.275 * u; tagDot.backgroundColor = rp ? cg("#ffffff") : cg("#e11d48")
            tag.addSublayer(tagDot)
            text(tag, label, fB, .white, x: 1.55 * u, midY: bh / 2)
            tag.frame = CGRect(x: 0.97 * W - w, y: topY, width: w, height: bh)
        }
        blink += dt
        tagDot.opacity = rp ? 1 : Float(0.25 + 0.75 * abs(cos(Double.pi * blink / 1.6)))

        let lk = "\(m.banner.0)|\(m.banner.1)|\(size)"
        if lk != lowerKey {
            lowerKey = lk; lower.sublayers = nil
            let fT = condensed(2.3 * u, .bold), f1 = condensed(1.35 * u, .bold), f2 = condensed(u, .semibold)
            let h1 = Double(attr("A", f1, .white, 0).size().height), h2 = Double(attr("A", f2, .white, 0).size().height)
            let bxh = 0.8 * u + h1 + h2, tagW = tw("GOAL", fT) + 0.9 * u, txtW = max(tw(m.banner.0, f1), tw(m.banner.1, f2)) + 1.8 * u
            box(lower, 0, 0, tagW, bxh, cg("#d61f45")); text(lower, "GOAL", fT, .white, midY: bxh / 2, centre: (0, tagW))
            box(lower, tagW, 0, txtW, bxh, cg("#0a1226", 0.94))
            text(lower, m.banner.0, f1, .white, x: tagW + 0.9 * u, midY: bxh - 0.4 * u - h1 / 2)
            text(lower, m.banner.1, f2, NSColor(white: 1, alpha: 0.8), x: tagW + 0.9 * u, midY: 0.4 * u + h2 / 2)
            lowerW = tagW + txtW
            lower.frame = CGRect(x: 0, y: 0.09 * H, width: lowerW, height: bxh)
        }
        lowerE += ((m.bannerOn ? 1 : 0) - lowerE) * (1 - exp(-dt * 8))
        lower.isHidden = lowerE < 0.002
        lower.position = CGPoint(x: 0.03 * W - 1.3 * lowerW * (1 - lowerE), y: 0.09 * H)

        let ck = "\(m.cardTitle)|\(t0.def.name)|\(t1.def.name)|\(t0.score)|\(t1.score)|\(size)"
        if ck != cardKey {
            cardKey = ck; card.sublayers = nil
            let fH = condensed(u, .bold), fR = condensed(1.3 * u, .semibold), fS = NSFont.monospacedDigitSystemFont(ofSize: 1.3 * u, weight: .bold)
            let hh = 1.7 * u, rh = 0.8 * u + 1.3 * u * 1.25
            let names = [t0.def.name.uppercased(), t1.def.name.uppercased()]
            let w = max(15 * u, names.map { tw($0, fR) }.max()! + 4.5 * u), ch = hh + 2 * rh
            box(card, 0, 0, w, ch, cg("#0a1226", 0.95))
            box(card, 0, ch - hh, w, hh, cg("#1c2a4f"))
            text(card, m.cardTitle, fH, .white, midY: ch - hh / 2, centre: (0, w), kern: 0.12 * u)
            for (i, t) in [t0, t1].enumerated() {
                let my = ch - hh - rh * (Double(i) + 0.5)
                box(card, 0.8 * u, my - 0.55 * u, 0.3 * u, 1.1 * u, cg(t.def.kit[0]))
                text(card, names[i], fR, .white, x: 1.7 * u, midY: my)
                let sw = tw("\(t.score)", fS)
                text(card, "\(t.score)", fS, .white, x: w - 0.8 * u - sw, midY: my)
            }
            card.frame = CGRect(x: (W - w) / 2, y: (H - ch) / 2, width: w, height: ch)
        }
        cardE += ((m.cardOn ? 1 : 0) - cardE) * (1 - exp(-dt * 7))
        card.opacity = Float(cardE)
        card.isHidden = cardE < 0.002
        let chh = Double(card.bounds.height)
        card.position = CGPoint(x: card.position.x, y: (H - chh) / 2 - (1 - cardE) * 0.04 * chh)

        let wk = size
        if wk != wipeKey {
            wipeKey = wk; wipe.sublayers = nil
            let ww = 1.6 * W, wh = 1.2 * H
            wipe.bounds = CGRect(x: 0, y: 0, width: ww, height: wh)
            box(wipe, 0, 0, ww, wh, cg("#0a1226"))
            box(wipe, 0, 0, 0.06 * ww, wh, cg("#d61f45")); box(wipe, 0.94 * ww, 0, 0.06 * ww, wh, cg("#d61f45"))
            let f = condensed(clamp(0.07 * W, 32, 110), .bold)
            text(wipe, "REPLAY", f, .white, midY: wh / 2, centre: (0, ww), kern: 0.25 * clamp(0.07 * W, 32, 110))
            wipe.transform = CATransform3DMakeAffineTransform(CGAffineTransform(a: 1, b: 0, c: 0.2126, d: 1, tx: 0, ty: 0))
        }
        if m.wipes != wipes { wipes = m.wipes; wipeT = 0 }
        wipe.isHidden = wipeT >= 0.6
        if wipeT < 0.6 {
            wipeT += dt
            let tau = min(wipeT / 0.6, 1), sm = { (v: Double) in v * v * (3 - 2 * v) }
            let k = tau < 0.4 ? -1 + sm(tau / 0.4) : tau < 0.6 ? 0 : sm((tau - 0.6) / 0.4)
            wipe.position = CGPoint(x: W / 2 + k * 1.92 * W, y: H / 2)
        }
    }
}
