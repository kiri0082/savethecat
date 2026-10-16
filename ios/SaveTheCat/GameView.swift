import UIKit
import CoreText

/// Keep the cat dry. A port of the Android TV `GameView`: same rules and the
/// same tuning, but there is one umbrella, steered by tilting the phone
/// (`tilt`, 0 = left edge .. 1 = right edge) and opened wide while a finger is
/// on the screen (`wide`), instead of camera tracking.
///
/// The world is `worldH` units tall and as wide as the screen's aspect allows,
/// so every pixel constant from the TV version (1080 tall) carries over.
/// Grayscale apart from the red umbrella and poppy, no sound - it's for kids.
final class GameView: UIView {

    // ---- tuning (same as the TV version unless noted) ----
    private enum K {
        static let chars = Array("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%&*?+=<>/{}[]~^")
        static let rainPerSec: CGFloat = 306      // per 1920 units of width
        static let meltSeconds: CGFloat = 10
        static let wetWindow: CGFloat = 0.25      // a hit keeps the cat "getting wet" this long
        static let poppyEvery: CGFloat = 10       // seconds, +/- 2 at random
        static let poppyPuddleS: CGFloat = 2      // a landed poppy melts this long
        static let dryAfter: CGFloat = 5          // seconds dry in a row before it recovers
        static let wetRate: CGFloat = 0.25        // melt progress per second of rain (40 s to melt)
        static let dryRate: CGFloat = 1.0         // wet seconds undone per second
        static let catSpeed: CGFloat = 0.075      // screen widths per second
        static let erraticRampS: CGFloat = 150    // play time to reach full erratic-ness
        static let umbHalf: CGFloat = 0.1375, umbHalfWide: CGFloat = 0.17   // screen widths
        static let meltedPause: CFTimeInterval = 5
        static let catBW: CGFloat = 120, catBH: CGFloat = 62
        static let shardLife: CGFloat = 2.2
        // One-Euro filter on the umbrella. The tilt sensor is far cleaner than
        // the webcam was, so it can follow much more tightly (TV: 0.9 / 0.004).
        static let minCutoff: CGFloat = 2.5       // Hz, at rest
        static let beta: CGFloat = 0.01           // cutoff gain per unit/s of speed
        static let dCutoff: CGFloat = 1.0         // Hz, for the speed estimate
        // rain glyph sizes, small/far to big/near (TV: 9, 11, 14, 16 - a phone needs them bigger)
        static let glyphSizes: [CGFloat] = [12, 15, 18, 22]
        static let glyphGrays: [UInt32] = [0x3c3c3c, 0x5e5e5e, 0x858585, 0xadadad]
    }

    // ---- colours ----
    private enum C {
        static let bg: UInt32 = 0x0e0e0e
        static let floor: UInt32 = 0x1b1b1b
        static let catFill: UInt32 = 0xb8b8b8, catLine: UInt32 = 0x5c5c5c, catDark: UInt32 = 0x2a2a2a
        static let umbFill: UInt32 = 0x9c2626, umbRib: UInt32 = 0x5a1010, handle: UInt32 = 0x9a9a9a
        static let poppy: UInt32 = 0xc8241f, poppyDark: UInt32 = 0x8a1210, poppyEye: UInt32 = 0x141414
        static let textMain: UInt32 = 0xbdbdbd, textDim: UInt32 = 0x5a5a5a
    }

    static let worldH: CGFloat = 900

    private enum State { case attract, playing, melted }

    private struct Drop {
        var glyph = 0, size = 0
        var poppy = false
        var x: CGFloat = 0, y: CGFloat = -40, vx: CGFloat = 0, vy: CGFloat = 0
        var age: CGFloat = 0, spin: CGFloat = 0
        var bounces = 0            // characters hop off the floor a couple of times
        var onUmb = false          // sliding along the umbrella
        var rel: CGFloat = 0       // position on it, -1..1 of its half width
    }
    private struct Spark { var x, y, vx, vy, age, life: CGFloat; var color: UInt32 }
    private struct Shard { var ch: String; var x, y, vx, vy, rot, vr, age, size: CGFloat }
    private struct Puddle { var x: CGFloat; var age: CGFloat }

    // ---- inputs ----
    var tilt: CGFloat = 0.5
    var wide = false

    // ---- world ----
    private var w: CGFloat = 0, h: CGFloat = 0
    private var groundY: CGFloat = 0, canopyY: CGFloat = 0
    private var state = State.attract
    private var stateAt: CFTimeInterval = 0
    private var playTime: CGFloat = 0, wet: CGFloat = 0, rainDebt: CGFloat = 0
    private var catLife: CGFloat = 0           // how long this cat has lived, counting only play time
    private var poppyIn = K.poppyEvery
    private var poppyGotIt = false             // this cat was lost to a poppy, not the rain
    private var exploded = false               // burst into characters, so no melt puddle
    private var lastHitAgo: CGFloat = 99

    private var drops: [Drop] = []
    private var sparks: [Spark] = []
    private var shards: [Shard] = []
    private var puddles: [Puddle] = []

    private var umbX: CGFloat = 0, umbTarget: CGFloat = 0, umbOpen: CGFloat = 0, umbSpeed: CGFloat = 0
    private var catX: CGFloat = 0, catDir: CGFloat = 1, catStopFor: CGFloat = 0, catPhase: CGFloat = 0, catAlpha: CGFloat = 0

    // ---- rain glyphs: one CTFontDrawGlyphs per size, like drawPosText on Android ----
    private let fonts: [CTFont]
    private var glyphs: [CGGlyph]
    private var glyphHalf: [CGFloat] = []
    private var glyphBuf: [[CGGlyph]] = Array(repeating: [], count: 4)
    private var posBuf: [[CGPoint]] = Array(repeating: [], count: 4)

    override init(frame: CGRect) {
        let fs = K.glyphSizes.map { UIFont.monospacedSystemFont(ofSize: $0, weight: .regular) as CTFont }
        let units = K.chars.map { UniChar(String($0).utf16.first!) }
        var gl = [CGGlyph](repeating: 0, count: units.count)
        _ = CTFontGetGlyphsForCharacters(fs[0], units, &gl, units.count)
        fonts = fs
        glyphs = gl
        super.init(frame: frame)
        for f in fonts {
            var g = glyphs[0]
            var adv = CGSize.zero
            _ = CTFontGetAdvancesForGlyphs(f, .horizontal, &g, &adv, 1)
            glyphHalf.append(adv.width / 2)   // monospace: centre each glyph
        }
        backgroundColor = .black
        isOpaque = true
        contentScaleFactor = min(2, UIScreen.main.scale)   // full-screen CPU redraw each frame; 2x is plenty
        isMultipleTouchEnabled = true
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard bounds.height > 0 else { return }
        let first = w == 0
        h = GameView.worldH
        w = bounds.width * h / bounds.height
        groundY = h - 70
        canopyY = h - 401   // hem ~189 units above the cat's ears
        if first {
            resetCat(w * 0.5)
            umbX = w * tilt; umbTarget = umbX
        }
    }

    // ---- control ----
    var isWaiting: Bool { state == .attract }
    var hasStarted: Bool { catLife > 0 }

    func play() { if state == .attract { setState(.playing) } }

    func pause() {
        if state == .playing { setState(.attract) }
        wide = false
    }

    func tick(_ dt: CGFloat) {
        guard w > 0 else { return }
        step(dt)
        setNeedsDisplay()
    }

    // =====================================================================
    // simulation
    // =====================================================================

    private func step(_ dt: CGFloat) {
        let now = CACurrentMediaTime()
        if state == .melted, now - stateAt > K.meltedPause {
            wet = 0; playTime = 0; catLife = 0; poppyGotIt = false; exploded = false
            resetCat(Bool.random() ? -80 : w + 80)
            setState(.playing)
        }
        if state == .playing { playTime += dt; catLife += dt }

        stepUmbrella(dt)
        if state != .melted { stepCat(dt) }

        // rain: steady
        rainDebt += K.rainPerSec * (w / 1920) * dt
        while rainDebt >= 1 { rainDebt -= 1; drops.append(newDrop(poppy: false)) }
        poppyIn -= dt
        if poppyIn <= 0 {
            poppyIn = K.poppyEvery - 2 + rnd() * 4
            drops.append(newDrop(poppy: true))
        }

        lastHitAgo += dt
        // swap-remove: ~700 drops, hundreds finishing a second
        var i = 0
        while i < drops.count {
            if stepDrop(&drops[i], dt) {
                drops.swapAt(i, drops.count - 1)
                drops.removeLast()
            } else {
                i += 1
            }
        }

        // landed poppies melt for poppyPuddleS; a cat stepping in one explodes
        var p = puddles.count - 1
        while p >= 0 {
            puddles[p].age += dt
            if puddles[p].age > K.poppyPuddleS {
                puddles.remove(at: p)
            } else if state == .playing,
                      abs(catX - puddles[p].x) < K.catBW * 0.45 + poppyRadius(puddles[p].age) {
                puddles.remove(at: p)
                explodeCat("cat stepped in a poppy")
            }
            p -= 1
        }

        for j in shards.indices {
            shards[j].age += dt
            shards[j].x += shards[j].vx * dt
            shards[j].y += shards[j].vy * dt
            shards[j].vy += 700 * dt
            shards[j].rot += shards[j].vr * dt
            if shards[j].y > groundY + 18 {
                shards[j].y = groundY + 18; shards[j].vy *= -0.3; shards[j].vx *= 0.6
            }
        }
        shards.removeAll { $0.age > K.shardLife }

        for j in sparks.indices {
            sparks[j].age += dt
            sparks[j].x += sparks[j].vx * dt
            sparks[j].y += sparks[j].vy * dt
            sparks[j].vy += 900 * dt
        }
        sparks.removeAll { $0.age > $0.life }

        if state == .playing && lastHitAgo >= K.dryAfter && wet > 0 {
            wet = max(0, wet - K.dryRate * dt)
        }
        if state == .playing && lastHitAgo < K.wetWindow {
            wet += K.wetRate * dt
            if wet >= K.meltSeconds {
                wet = K.meltSeconds
                for _ in 0..<40 { splash(catX, groundY - 30, 0x9a9a9a) }
                setState(.melted)
            }
        }
    }

    /// The cat bursts into characters that fly out and rain back down.
    private func explodeCat(_ why: String) {
        guard state == .playing else { return }
        let squash = 1 - 0.55 * melt()
        let bodyTop = groundY - (K.catBH + 26) * squash
        for _ in 0..<60 {
            let a = (200 + rnd() * 140) * .pi / 180   // mostly upward fan
            let sp = 300 + rnd() * 500
            shards.append(Shard(
                ch: String(K.chars.randomElement()!),
                x: catX + (rnd() - 0.5) * K.catBW,
                y: bodyTop + rnd() * (groundY - bodyTop),
                vx: cos(a) * sp, vy: sin(a) * sp,
                rot: 0, vr: (rnd() - 0.5) * 720, age: 0,
                size: 18 + CGFloat(Int.random(in: 0..<16))))
        }
        wet = K.meltSeconds
        poppyGotIt = true
        exploded = true
        setState(.melted)
    }

    private func poppyRadius(_ age: CGFloat) -> CGFloat {
        52 * (1 - 0.6 * age / K.poppyPuddleS)   // 2x: easier for the cat to blunder into
    }

    private func setState(_ s: State) {
        state = s
        stateAt = CACurrentMediaTime()
    }

    private func splash(_ x: CGFloat, _ y: CGFloat, _ color: UInt32) {
        sparks.append(Spark(x: x, y: y,
                            vx: (rnd() - 0.5) * 260, vy: -120 - rnd() * 220,
                            age: 0, life: 0.25 + rnd() * 0.3, color: color))
    }

    private func newDrop(poppy: Bool) -> Drop {
        var d = Drop()
        d.glyph = Int.random(in: 0..<glyphs.count)
        d.size = Int.random(in: 0..<4)
        d.poppy = poppy
        d.x = rnd() * w
        d.spin = rnd() * 360
        // a poppy falls slower and sways so kids see it coming
        d.vy = poppy ? 150 : 230 + CGFloat(d.size) * 55 + rnd() * 60
        if poppy { d.x = w * (0.1 + 0.8 * rnd()) }
        return d
    }

    /// One falling character: falls, or slides along the umbrella's canopy
    /// toward the nearer edge and drops off it. Returns true when finished.
    private func stepDrop(_ d: inout Drop, _ dt: CGFloat) -> Bool {
        if d.onUmb {
            let half = umbHalfWidth()
            let s: CGFloat = d.rel < 0 ? -1 : 1
            d.rel += s * (300 / half) * dt   // slide outward
            if abs(d.rel) >= 1 {
                d.x = umbX + s * half
                d.y = canopyY
                d.vx = s * 70
                d.vy = 90
                d.onUmb = false
                return false
            }
            d.x = umbX + d.rel * half
            d.y = umbSurfaceY(d.rel) - 4
            return false
        }
        d.age += dt
        if d.poppy {
            d.x += (d.vx + sin(d.age * 2.2) * 60) * dt
            d.y += d.vy * dt
            d.spin += 40 * dt
        } else {
            d.x += d.vx * dt
            d.y += d.vy * dt
            d.vy += (d.bounces > 0 ? 1600 : 60) * dt   // real gravity once it has bounced
        }
        // land on the umbrella? Drops just shed off an edge (vx != 0) mustn't re-land on it.
        let half = umbHalfWidth(), dx = d.x - umbX
        if abs(dx) < half * (d.vx == 0 ? 0.98 : 0.9) {
            let r = dx / half
            if d.y >= umbSurfaceY(r) - 4 && d.y <= canopyY + 10 {
                d.onUmb = true
                d.rel = r == 0 ? (Bool.random() ? 0.01 : -0.01) : r
                return false
            }
        }
        // hit the cat? Bounced characters are just floor splash: they don't wet it.
        if state != .melted && d.bounces == 0 && catHit(d.x, d.y) {
            if d.poppy {
                for _ in 0..<12 { splash(d.x, d.y, C.poppy) }
                if state == .playing { explodeCat("poppy hit the cat") }
                return true
            }
            lastHitAgo = 0
            splash(d.x, d.y, 0x8c8c8c)
            splash(d.x, d.y, 0x8c8c8c)
            return true
        }
        if d.y >= groundY + 20 {
            if d.poppy {
                puddles.append(Puddle(x: d.x, age: 0))
                for _ in 0..<6 { splash(d.x, groundY + 16, C.poppy) }
                return true
            }
            splash(d.x, groundY + 20, 0x4a4a4a)
            if d.bounces < 2 {
                d.bounces += 1
                d.y = groundY + 20
                d.vy = -abs(d.vy) * (d.bounces == 1 ? 0.32 : 0.2) * (0.7 + 0.6 * rnd())
                d.vx = (rnd() - 0.5) * 180
                splash(d.x, groundY + 20, 0x5e5e5e)
                return false
            }
            return true
        }
        return d.x < -60 || d.x > w + 60
    }

    // ---- umbrella: a One-Euro filter on x, heavy smoothing when still, light when moving ----

    private func lowpass(_ cutoff: CGFloat, _ dt: CGFloat) -> CGFloat {
        let tau = 1 / (2 * .pi * cutoff)
        return 1 / (1 + tau / dt)
    }

    private func stepUmbrella(_ dt: CGFloat) {
        umbTarget = min(w, max(0, tilt * w))
        if dt > 0 {
            let raw = (umbTarget - umbX) / dt
            umbSpeed += (raw - umbSpeed) * lowpass(K.dCutoff, dt)
            umbX += (umbTarget - umbX) * lowpass(K.minCutoff + K.beta * abs(umbSpeed), dt)
        }
        umbOpen += ((wide ? 1 : 0) - umbOpen) * min(1, dt * 7)
    }

    private func umbHalfWidth() -> CGFloat { w * (K.umbHalf + (K.umbHalfWide - K.umbHalf) * umbOpen) }

    /// canopy top at rel -1..1; a flat-ish dome
    private func umbSurfaceY(_ rel: CGFloat) -> CGFloat {
        let dome = umbHalfWidth() * 0.32
        return canopyY - dome * sqrt(max(0, 1 - rel * rel))
    }

    // ---- the cat: one speed, sudden stops and turns, more of both over time ----

    private func resetCat(_ startX: CGFloat) {
        catX = startX
        catDir = startX < w / 2 ? 1 : -1
        catStopFor = 0
        catAlpha = 0
    }

    private func stepCat(_ dt: CGFloat) {
        let erratic = min(1, playTime / K.erraticRampS)
        catAlpha += (1 - catAlpha) * min(1, dt * 2)
        if catStopFor > 0 {
            catStopFor -= dt
        } else {
            catX += catDir * K.catSpeed * w * dt
            catPhase += dt * 9
            let pTurn = (0.12 + 0.45 * erratic) * dt
            let pStop = (0.10 + 0.30 * erratic) * dt
            if rnd() < pTurn { catDir = -catDir }
            if rnd() < pStop { catStopFor = 0.4 + rnd() * (1.4 - 0.6 * erratic) }
        }
        let margin: CGFloat = 110
        if catX < margin { catDir = 1; if catX < margin - 40 { catX += K.catSpeed * w * dt } }
        if catX > w - margin { catDir = -1; if catX > w - margin + 40 { catX -= K.catSpeed * w * dt } }
    }

    private func melt() -> CGFloat { wet / K.meltSeconds }

    private func catHit(_ px: CGFloat, _ py: CGFloat) -> Bool {
        let top = groundY - (K.catBH + 46) * (1 - 0.55 * melt())
        return py >= top && py <= groundY && px >= catX - K.catBW * 0.62 && px <= catX + K.catBW * 0.62
    }

    // =====================================================================
    // drawing
    // =====================================================================

    override func draw(_ rect: CGRect) {
        guard w > 0, let c = UIGraphicsGetCurrentContext() else { return }
        let k = bounds.height / h
        c.scaleBy(x: k, y: k)
        c.setLineCap(.round)
        c.setLineJoin(.round)

        setFill(c, C.bg)
        c.fill(CGRect(x: 0, y: 0, width: w, height: h))
        drawRain(c)
        for d in drops where d.poppy { drawPoppy(c, d.x, d.y, d.spin) }
        setFill(c, C.floor)
        c.fill(CGRect(x: 0, y: groundY + 22, width: w, height: h - groundY - 22))
        drawPuddle(c)
        drawPoppyPuddles(c)
        if state != .melted { drawCat(c) }
        drawShards(c)
        drawUmbrella(c)
        for s in sparks {
            setFill(c, s.color, 1 - s.age / s.life)
            circle(c, s.x, s.y, 3)
        }
        drawHUD(c)
    }

    private func drawRain(_ c: CGContext) {
        for k in 0..<4 {
            glyphBuf[k].removeAll(keepingCapacity: true)
            posBuf[k].removeAll(keepingCapacity: true)
        }
        for d in drops where !d.poppy {
            glyphBuf[d.size].append(glyphs[d.glyph])
            posBuf[d.size].append(CGPoint(x: d.x - glyphHalf[d.size], y: -d.y))
        }
        // glyphs draw y-up: flip, and negate y in the positions
        c.saveGState()
        c.scaleBy(x: 1, y: -1)
        c.textMatrix = .identity
        for k in 0..<4 where !glyphBuf[k].isEmpty {
            setFill(c, K.glyphGrays[k])
            CTFontDrawGlyphs(fonts[k], glyphBuf[k], posBuf[k], glyphBuf[k].count, c)
        }
        c.restoreGState()
    }

    private func drawUmbrella(_ c: CGContext) {
        let half = umbHalfWidth(), top = umbSurfaceY(0), x = umbX
        // handle: shaft from the canopy down, J hook at the bottom
        setStroke(c, C.handle)
        c.setLineWidth(7)
        line(c, x, top, x, canopyY + 133)
        let hook = CGMutablePath()
        hook.addArc(center: .zero, radius: 1, startAngle: 0, endAngle: .pi, clockwise: false,
                    transform: CGAffineTransform(translationX: x - 14, y: canopyY + 133).scaledBy(x: 14, y: 26))
        c.addPath(hook)
        c.strokePath()
        // canopy: dome plus scalloped hem
        let p = CGMutablePath()
        p.move(to: CGPoint(x: x - half, y: canopyY))
        for i in -20...20 {
            let r = CGFloat(i) / 20
            p.addLine(to: CGPoint(x: x + r * half, y: umbSurfaceY(r)))
        }
        let scallops = umbOpen > 0.5 ? 6 : 4
        let sw = 2 * half / CGFloat(scallops)
        for i in stride(from: scallops - 1, through: 0, by: -1) {
            let x0 = x - half + CGFloat(i + 1) * sw, x1 = x - half + CGFloat(i) * sw
            p.addQuadCurve(to: CGPoint(x: x1, y: canopyY), control: CGPoint(x: (x0 + x1) / 2, y: canopyY - sw * 0.28))
        }
        p.closeSubpath()
        setFill(c, C.umbFill)
        c.addPath(p)
        c.fillPath()
        setStroke(c, C.umbRib)
        c.setLineWidth(3)
        for i in 1..<scallops {
            let rx = -1 + 2 * CGFloat(i) / CGFloat(scallops)
            line(c, x, top, x + rx * half, canopyY - 2)
        }
        c.addPath(p)
        c.strokePath()
        // tip
        c.setLineWidth(5)
        line(c, x, top, x, top - 18)
    }

    private func drawCat(_ c: CGContext) {
        let m = melt()
        let squash = 1 - 0.55 * m                       // melts downward
        let a = catAlpha * (1 - 0.65 * m)
        let bw = K.catBW * (1 + 0.25 * m), bh = K.catBH * squash
        let cx = catX, base = groundY, d = catDir
        let walking = catStopFor <= 0

        setStroke(c, C.catLine, a)
        c.setLineWidth(5)
        // legs
        let legH = 26 * squash
        for i in 0..<4 {
            let lx = cx - bw * 0.36 + CGFloat(i) * bw * 0.24
            let swing = walking ? sin(catPhase + CGFloat(i % 2) * .pi) * 9 * squash : 0
            line(c, lx, base - legH, lx + swing, base)
        }
        // tail: a curve up and back
        let tx = cx - d * bw * 0.48, ty = base - legH - bh * 0.6
        c.move(to: CGPoint(x: tx, y: ty))
        c.addQuadCurve(to: CGPoint(x: tx - d * 38, y: ty - 58 * squash),
                       control: CGPoint(x: tx - d * 46, y: ty - 10 * squash))
        c.setLineWidth(8)
        c.strokePath()
        // body
        let body = CGRect(x: cx - bw / 2, y: base - legH - bh, width: bw, height: bh + 6)
        setFill(c, C.catFill, a)
        c.fillEllipse(in: body)
        c.setLineWidth(3)
        c.strokeEllipse(in: body)
        // head with ears
        let hr = 30 * (1 - 0.4 * m)
        let hx = cx + d * bw * 0.46, hy = base - legH - bh - hr * 0.35
        let ears = CGMutablePath()
        ears.move(to: CGPoint(x: hx - hr * 0.85, y: hy - hr * 0.35))
        ears.addLine(to: CGPoint(x: hx - hr * 0.55, y: hy - hr * 1.45))
        ears.addLine(to: CGPoint(x: hx - hr * 0.05, y: hy - hr * 0.75))
        ears.move(to: CGPoint(x: hx + hr * 0.85, y: hy - hr * 0.35))
        ears.addLine(to: CGPoint(x: hx + hr * 0.55, y: hy - hr * 1.45))
        ears.addLine(to: CGPoint(x: hx + hr * 0.05, y: hy - hr * 0.75))
        c.addPath(ears); c.fillPath()
        c.addPath(ears); c.strokePath()
        let head = CGRect(x: hx - hr, y: hy - hr, width: 2 * hr, height: 2 * hr)
        c.fillEllipse(in: head)
        c.strokeEllipse(in: head)
        // eyes (look the way it walks) + nose
        setFill(c, C.catDark, a)
        circle(c, hx + d * hr * 0.15 - hr * 0.3, hy - hr * 0.1, 4)
        circle(c, hx + d * hr * 0.15 + hr * 0.3, hy - hr * 0.1, 4)
        circle(c, hx + d * hr * 0.35, hy + hr * 0.25, 3)
        // drips while melting
        if m > 0.05 {
            setFill(c, C.catFill, a)
            let n = Int(m * 6)
            for i in 0..<n {
                let dx = cx - bw * 0.4 + CGFloat((i * 37) % Int(bw))
                let len = 8 + CGFloat((i * 13) % 14) * m
                c.addPath(CGPath(roundedRect: CGRect(x: dx - 4, y: base - legH, width: 8, height: len),
                                 cornerWidth: 4, cornerHeight: 4, transform: nil))
                c.fillPath()
            }
        }
    }

    /// five overlapping red petals round a dark centre, ~40 units across
    private func drawPoppy(_ c: CGContext, _ x: CGFloat, _ y: CGFloat, _ spin: CGFloat) {
        c.saveGState()
        c.translateBy(x: x, y: y)
        c.rotate(by: spin * .pi / 180)
        for i in 0..<5 {
            let a = CGFloat(i) * 72 * .pi / 180
            setFill(c, i % 2 == 0 ? C.poppy : C.poppyDark)
            circle(c, cos(a) * 10, sin(a) * 10, 12)
        }
        setFill(c, C.poppy); circle(c, 0, 0, 9)
        setFill(c, C.poppyEye); circle(c, 0, 0, 5)
        c.restoreGState()
    }

    private func drawPoppyPuddles(_ c: CGContext) {
        for p in puddles {
            let k = p.age / K.poppyPuddleS, r = poppyRadius(p.age)
            setFill(c, C.poppy, 0.9 * (1 - k * 0.7))
            c.fillEllipse(in: CGRect(x: p.x - r * 1.4, y: groundY + 10, width: r * 2.8, height: r * 0.8))
            setFill(c, C.poppyDark, 0.9 * (1 - k * 0.7))
            circle(c, p.x, groundY + 10 + r * 0.4, r * 0.3)
        }
    }

    private func drawShards(_ c: CGContext) {
        for sh in shards {
            let font = UIFont.systemFont(ofSize: sh.size, weight: .bold)
            let attrs: [NSAttributedString.Key: Any] = [
                .font: font,
                .foregroundColor: UIColor(white: 0.72, alpha: max(0, 1 - sh.age / K.shardLife)),
            ]
            let s = sh.ch as NSString
            let sz = s.size(withAttributes: attrs)
            c.saveGState()
            c.translateBy(x: sh.x, y: sh.y)
            c.rotate(by: sh.rot * .pi / 180)
            s.draw(at: CGPoint(x: -sz.width / 2, y: -font.ascender), withAttributes: attrs)
            c.restoreGState()
        }
    }

    private func drawPuddle(_ c: CGContext) {
        if exploded { return }
        let m = state == .melted ? 1 : melt()
        if m <= 0.02 { return }
        let pw = K.catBW * (0.4 + 1.4 * m)
        setFill(c, 0x7a7a7a, (60 + 120 * m) / 255)
        c.fillEllipse(in: CGRect(x: catX - pw / 2, y: groundY + 6, width: pw, height: 14 + 10 * m))
    }

    private func drawHUD(_ c: CGContext) {
        let cx = w / 2
        // how long this cat has lived; frozen while it's melted or paused
        text(clock(catLife), 64, state == .playing ? C.textMain : C.textDim, cx, 92)
        switch state {
        case .attract:
            text("TILT THE PHONE TO MOVE THE UMBRELLA", 48, C.textMain, cx, h * 0.3)
            text(hasStarted ? "TAP TO CARRY ON" : "TAP TO START", 30, C.textDim, cx, h * 0.3 + 56)
        case .melted:
            text(poppyGotIt ? "OH NO, A POPPY!" : "OH NO, THE CAT MELTED!", 52, C.textMain, cx, h * 0.3)
            text("IT LASTED \(clock(catLife))  \u{00b7}  A NEW CAT IS ON ITS WAY...", 30, C.textDim, cx, h * 0.3 + 56)
        case .playing:
            if playTime < 12 {
                text("HOLD THE SCREEN FOR A BIGGER UMBRELLA", 30, C.textDim, cx, 150)
            }
        }
    }

    // ---- helpers ----

    private func text(_ s: String, _ size: CGFloat, _ color: UInt32, _ x: CGFloat, _ baseline: CGFloat) {
        let font = UIFont.monospacedDigitSystemFont(ofSize: size, weight: .bold)
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: uiColor(color), .kern: size * 0.08]
        let ns = s as NSString
        let sz = ns.size(withAttributes: attrs)
        ns.draw(at: CGPoint(x: x - sz.width / 2, y: baseline - font.ascender), withAttributes: attrs)
    }

    private func clock(_ s: CGFloat) -> String {
        let t = Int(s)
        return String(format: "%d:%02d", t / 60, t % 60)
    }

    private func rnd() -> CGFloat { CGFloat.random(in: 0..<1) }

    private func rgb(_ hex: UInt32) -> (CGFloat, CGFloat, CGFloat) {
        (CGFloat((hex >> 16) & 0xff) / 255, CGFloat((hex >> 8) & 0xff) / 255, CGFloat(hex & 0xff) / 255)
    }

    private func uiColor(_ hex: UInt32) -> UIColor {
        let (r, g, b) = rgb(hex)
        return UIColor(red: r, green: g, blue: b, alpha: 1)
    }

    private func setFill(_ c: CGContext, _ hex: UInt32, _ a: CGFloat = 1) {
        let (r, g, b) = rgb(hex)
        c.setFillColor(red: r, green: g, blue: b, alpha: a)
    }

    private func setStroke(_ c: CGContext, _ hex: UInt32, _ a: CGFloat = 1) {
        let (r, g, b) = rgb(hex)
        c.setStrokeColor(red: r, green: g, blue: b, alpha: a)
    }

    private func circle(_ c: CGContext, _ x: CGFloat, _ y: CGFloat, _ r: CGFloat) {
        c.fillEllipse(in: CGRect(x: x - r, y: y - r, width: 2 * r, height: 2 * r))
    }

    private func line(_ c: CGContext, _ x0: CGFloat, _ y0: CGFloat, _ x1: CGFloat, _ y1: CGFloat) {
        c.move(to: CGPoint(x: x0, y: y0))
        c.addLine(to: CGPoint(x: x1, y: y1))
        c.strokePath()
    }
}
