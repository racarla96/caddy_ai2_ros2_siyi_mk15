package com.caddyai2.siyimk15teleop.kinematics;

/**
 * Converts normalized steer/throttle in [-1, 1] into a bicycle-model
 * {@code geometry_msgs/Twist} (linear.x, angular.z) intended for
 * {@code ros2_controllers}' {@code steering_controllers_library} — published directly to
 * a steering controller's {@code <controller_name>/reference} topic (see
 * {@code SteeringReferencePublisher}), not {@code bicycle_cmd_relay} (superseded
 * 2026-09-14; see README/PROTOCOL.md).
 *
 * <p>That controller recovers the steering joint angle as ({@code SteeringKinematics::
 * convert_twist_to_steering_angle} in {@code ros2_controllers}, {@code steering_kinematics.cpp}):
 * <pre>
 *   phi = atan(angular_z * wheelbase_robot / linear_x)
 * </pre>
 * using the robot's <b>own</b> configured wheelbase — a value this app has no way to know is
 * correct for certain, and (2026-09-14, agreed with the user) has deliberately stopped trying
 * to replicate exactly. Instead this class publishes {@code angular_z} proportional to the
 * joystick's steer fraction directly, without a wheelbase term:
 * <pre>
 *   angular_z = linear_x * tan(steerNorm * maxSteerAngleRad)
 * </pre>
 * This does <b>not</b> generally recover exactly {@code steerNorm * maxSteerAngleRad} on the
 * robot side (it does only if the robot's configured wheelbase happens to be exactly 1) — what
 * it guarantees is a monotonic, proportional response from center to full lock, which is what
 * was actually wanted here; the robot's own controller still clamps to its own configured
 * {@code max_steering_angle} regardless. See {@code BicycleTwistComputerTest} for the exact
 * shape of what's (and isn't) preserved.
 *
 * <p>Two consequences of targeting this controller's {@code atan}-based (not {@code atan2}-based)
 * recovery still apply and motivate the rest of this class's design:
 * <ol>
 *   <li>{@code atan} is odd and sign-preserving for negative {@code linear_x}, unlike
 *       {@code atan2} — so, in contrast to {@code bicycle_cmd_relay}'s own inverse formula
 *       (which used {@code atan2} and could not recover the intended direction while reversing),
 *       this target's proportionality holds for reverse too, as long as {@code linear_x} isn't
 *       exactly zero.</li>
 *   <li>At {@code linear_x == 0} exactly, the ratio the robot computes is {@code angular_z / 0}
 *       — undefined (full lock or straight-ahead depending on whether {@code angular_z} is also
 *       zero), not the intended direction. Since {@code linear_x} and {@code angular_z} are the
 *       same two fields the controller uses for both the steering-angle ratio <i>and</i> the
 *       traction wheel speed, there is no way to say "point the wheel, don't move" through this
 *       message alone. <b>This class's fix</b>: whenever a real steer angle is commanded with
 *       the throttle centered, substitute a small constant "creep" reference speed
 *       ({@link #CREEP_MPS}) for {@code linear_x} — just enough to keep the ratio well-defined,
 *       small enough to be a negligible real crawl rather than a meaningful move. Agreed with
 *       the user 2026-09-14 as a deliberate trade-off: positioning the steering joint while
 *       "stopped" costs a small, constant, real forward creep.</li>
 * </ol>
 */
public final class BicycleTwistComputer {

    private static final double EPS = 1e-3;

    // Small enough to be an imperceptible real crawl on a ~1.5 m/s vehicle, large enough to keep
    // the phi = atan(angularZ*wheelbase/linearX) ratio well away from a literal 0/0 or x/0. Not
    // yet tuned against real hardware feel — revisit (e.g. make configurable in TeleopConfig) if
    // this proves too fast/slow once tested with the actual robot.
    private static final double CREEP_MPS = 0.02;

    private final double maxSteerAngleRad;
    private final double maxSpeedMps;

    public BicycleTwistComputer(double maxSteerAngleRad, double maxSpeedMps) {
        if (maxSteerAngleRad <= 0) {
            throw new IllegalArgumentException("maxSteerAngleRad must be > 0");
        }
        if (maxSpeedMps <= 0) {
            throw new IllegalArgumentException("maxSpeedMps must be > 0");
        }
        this.maxSteerAngleRad = maxSteerAngleRad;
        this.maxSpeedMps = maxSpeedMps;
    }

    public static final class Twist {
        public final double linearX;
        public final double angularZ;

        Twist(double linearX, double angularZ) {
            this.linearX = linearX;
            this.angularZ = angularZ;
        }
    }

    /**
     * @param steerNorm    in [-1, 1], positive = left (REP-103 convention, matches
     *                     bicycle_to_ackermann_steering_adapter's sign choice)
     * @param throttleNorm in [-1, 1], positive = forward
     */
    public Twist compute(double steerNorm, double throttleNorm) {
        double steerAngle = clamp(steerNorm, -1, 1) * maxSteerAngleRad;
        double linearX = clamp(throttleNorm, -1, 1) * maxSpeedMps;

        // Only fudge linearX away from a real zero when there's an actual angle to preserve —
        // if steerAngle is also ~0, publishing an honest linearX=0 alongside angularZ=0 already
        // recovers phi=0 correctly on the robot side (0/0 -> NaN -> mapped to 0 there), so no
        // creep is needed (and none is added) when the stick is simply centered.
        boolean needsCreepToPreserveAngle = Math.abs(linearX) <= EPS && Math.abs(steerAngle) > EPS;
        double linearXPublished = needsCreepToPreserveAngle ? CREEP_MPS : linearX;
        double angularZ = linearXPublished * Math.tan(steerAngle);

        return new Twist(linearXPublished, angularZ);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
