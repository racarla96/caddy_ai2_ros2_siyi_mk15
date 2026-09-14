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
 *   phi = atan(angular_z * wheelbase / linear_x)
 * </pre>
 * i.e. {@code std::atan}, <b>not</b> {@code atan2}. Two consequences drive this class's design:
 * <ol>
 *   <li>{@code atan} is odd and sign-preserving for negative {@code linear_x}, unlike
 *       {@code atan2} — so, in contrast to {@code bicycle_cmd_relay}'s own inverse formula
 *       (which used {@code atan2} and could not recover the intended angle while reversing,
 *       see {@code BicycleTwistComputerTest}'s round-trip tests), this target recovers the
 *       exact intended {@code steerAngle} for <b>any</b> nonzero {@code linear_x}, forward or
 *       reverse — as long as {@code linear_x} isn't exactly zero.</li>
 *   <li>At {@code linear_x == 0} exactly, the ratio is {@code angular_z / 0} — {@code ±infinity}
 *       (full steering lock, not the intended angle) if {@code angular_z != 0}, or {@code 0/0}
 *       ({@code NaN}, which that function maps to {@code 0} — straight-ahead, also not the
 *       intended angle) if {@code angular_z == 0} too. Neither reflects a steer command made
 *       while stationary. Since {@code linear_x} and {@code angular_z} are the same two fields
 *       the controller uses for both the steering-angle ratio <i>and</i> the traction wheel
 *       speed, there is no way to say "point the wheel, don't move" through this message alone.
 *       <b>This class's fix</b>: whenever a real steer angle is commanded with the throttle
 *       centered, substitute a small constant "creep" reference speed ({@link #CREEP_MPS}) for
 *       {@code linear_x} — just enough to keep the ratio well-defined so {@code phi} recovers
 *       the exact intended angle, small enough to be a negligible real crawl rather than a
 *       meaningful move. This is a deliberate trade-off (found and agreed with the user
 *       2026-09-14): positioning the steering joint accurately while "stopped" costs a small,
 *       constant, real forward creep — there is no way to get an exact angle recovery from this
 *       controller's reference interface without it.</li>
 * </ol>
 */
public final class BicycleTwistComputer {

    private static final double EPS = 1e-3;

    // Small enough to be an imperceptible real crawl on a ~1.5 m/s vehicle, large enough to keep
    // the phi = atan(angularZ*wheelbase/linearX) ratio well away from a literal 0/0 or x/0. Not
    // yet tuned against real hardware feel — revisit (e.g. make configurable in TeleopConfig) if
    // this proves too fast/slow once tested with the actual robot.
    private static final double CREEP_MPS = 0.02;

    private final double wheelbaseMeters;
    private final double maxSteerAngleRad;
    private final double maxSpeedMps;

    public BicycleTwistComputer(double wheelbaseMeters, double maxSteerAngleRad, double maxSpeedMps) {
        if (wheelbaseMeters <= 0) {
            throw new IllegalArgumentException("wheelbaseMeters must be > 0");
        }
        if (maxSteerAngleRad <= 0) {
            throw new IllegalArgumentException("maxSteerAngleRad must be > 0");
        }
        if (maxSpeedMps <= 0) {
            throw new IllegalArgumentException("maxSpeedMps must be > 0");
        }
        this.wheelbaseMeters = wheelbaseMeters;
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
        double angularZ = linearXPublished * Math.tan(steerAngle) / wheelbaseMeters;

        return new Twist(linearXPublished, angularZ);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
