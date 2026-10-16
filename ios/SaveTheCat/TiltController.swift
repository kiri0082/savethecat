import CoreMotion
import UIKit

/// Turns the phone's left/right tilt into an umbrella position.
///
/// Uses the gravity vector from Core Motion (accelerometer + gyro fused, so
/// it's smooth and needs no permission prompt). Works whether the phone is
/// held flat or upright like a steering wheel: tipping the right side down
/// moves the umbrella right, like rolling a marble.
final class TiltController {

    /// Tilt this many degrees either way from level to reach a screen edge.
    static let rangeDeg: Double = 20
    /// How far off level "level" may be set when a game starts.
    static let maxNeutralDeg: Double = 15

    private let motion = CMMotionManager()
    private var neutral: Double = 0
    private var angleDeg: Double = 0

    /// False in the Simulator: there, drag a finger to steer instead.
    var isAvailable: Bool { motion.isDeviceMotionAvailable }

    func start() {
        guard isAvailable, !motion.isDeviceMotionActive else { return }
        motion.deviceMotionUpdateInterval = 1.0 / 60
        motion.startDeviceMotionUpdates()   // polled each frame, no handler needed
    }

    func stop() { motion.stopDeviceMotionUpdates() }

    /// 0 = left edge .. 1 = right edge.
    func position(for orientation: UIInterfaceOrientation) -> CGFloat {
        if let g = motion.deviceMotion?.gravity {
            // In landscape the screen's x axis is the device's y axis:
            // landscapeLeft has the top of the phone on the right.
            let towardRight = orientation == .landscapeRight ? -g.y : g.y
            angleDeg = asin(max(-1, min(1, towardRight))) * 180 / .pi
        }
        let t = 0.5 + (angleDeg - neutral) / (2 * Self.rangeDeg)
        return CGFloat(max(0, min(1, t)))
    }

    /// Treat the way the phone is held right now as level, so a child who
    /// holds it a little crooked still starts with the umbrella centred.
    func recenter() {
        neutral = max(-Self.maxNeutralDeg, min(Self.maxNeutralDeg, angleDeg))
    }
}
