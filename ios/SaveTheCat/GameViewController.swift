import UIKit

/// Runs the game loop, feeds it tilt and touch, and pauses it when the app
/// leaves the screen (the timer stops, like nobody being in front of the TV).
final class GameViewController: UIViewController {

    private let game = GameView()
    private let tilt = TiltController()
    private var link: CADisplayLink?
    private var last: CFTimeInterval = 0
    private var dragX: CGFloat?          // Simulator only: steer by dragging

    override func loadView() { view = game }

    override func viewDidLoad() {
        super.viewDidLoad()
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(resignActive),
                       name: UIApplication.willResignActiveNotification, object: nil)
        nc.addObserver(self, selector: #selector(becomeActive),
                       name: UIApplication.didBecomeActiveNotification, object: nil)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        startLoop()
    }

    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    // swipes at the edges are probably a child's hand, not "go home"
    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge { .all }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .landscape }

    // ---- loop ----

    private func startLoop() {
        guard link == nil else { return }
        tilt.start()
        last = 0
        let l = CADisplayLink(target: self, selector: #selector(frame(_:)))
        l.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: 60, preferred: 60)
        l.add(to: .main, forMode: .common)
        link = l
        UIApplication.shared.isIdleTimerDisabled = true   // like FLAG_KEEP_SCREEN_ON
    }

    private func stopLoop() {
        link?.invalidate()
        link = nil
        tilt.stop()
        UIApplication.shared.isIdleTimerDisabled = false
    }

    @objc private func frame(_ l: CADisplayLink) {
        let dt = last == 0 ? 0 : min(0.05, l.timestamp - last)
        last = l.timestamp
        if tilt.isAvailable {
            let o = view.window?.windowScene?.effectiveGeometry.interfaceOrientation ?? .landscapeRight
            game.tilt = tilt.position(for: o)
        } else if let x = dragX, view.bounds.width > 0 {
            game.tilt = x / view.bounds.width
        }
        game.tick(CGFloat(dt))
    }

    @objc private func resignActive() {
        game.pause()
        stopLoop()
    }

    @objc private func becomeActive() {
        if view.window != nil { startLoop() }
    }

    // ---- touch: tap to start; hold for a wide umbrella ("arms out" on the TV) ----

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        if !tilt.isAvailable, let t = touches.first { dragX = t.location(in: view).x }
        if game.isWaiting {
            tilt.recenter()
            game.play()
        } else {
            game.wide = true
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        if !tilt.isAvailable, let t = touches.first { dragX = t.location(in: view).x }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) { touchesDone(event) }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { touchesDone(event) }

    private func touchesDone(_ event: UIEvent?) {
        let stillDown = event?.allTouches?.contains { $0.phase != .ended && $0.phase != .cancelled } ?? false
        if !stillDown { game.wide = false }
    }
}
