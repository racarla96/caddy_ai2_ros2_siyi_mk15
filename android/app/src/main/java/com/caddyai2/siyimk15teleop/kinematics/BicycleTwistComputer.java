package com.caddyai2.siyimk15teleop.kinematics;

/**
 * Converts normalized steer/throttle in [-1, 1] into a {@code geometry_msgs/Twist}
 * (linear.x, angular.z), published directly to a {@code ros2_controllers}
 * {@code steering_controllers_library} controller's {@code <controller_name>/reference}
 * topic (see {@code SteeringReferencePublisher}), not {@code bicycle_cmd_relay} (superseded
 * 2026-09-14; see README/PROTOCOL.md).
 *
 * <p><b>steer and throttle are fully independent</b> (2026-09-14, explicit requirement —
 * three rounds of clarification with the user settled on this): {@code angular_z} is
 * proportional to the steer stick alone, {@code linear_x} to the throttle stick alone,
 * with <b>no</b> cross-multiplication between them. Earlier revisions of this class tried
 * to make {@code angular_z} exactly recoverable on the robot side via that controller's own
 * {@code phi = atan(angular_z * wheelbase_robot / linear_x)} formula ({@code
 * SteeringKinematics::convert_twist_to_steering_angle} in {@code ros2_controllers}), which
 * requires {@code angular_z} to scale with {@code linear_x} (a "creep" constant substituted
 * for zero throttle so the ratio stays defined). That made the on-screen {@code angular_z}
 * value tiny and easy to mistake for "not working" whenever the vehicle was stopped (the
 * creep was deliberately small, e.g. {@code 0.02 m/s * tan(steerAngle)} rounds to a couple
 * of hundredths of a rad/s) — confirmed live on hardware. The user's resolution: drop the
 * cross-multiplication entirely, at the cost of no longer trying to exactly satisfy that
 * controller's ratio-based angle recovery at zero throttle.
 */
public final class BicycleTwistComputer {

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
        double linearX = clamp(throttleNorm, -1, 1) * maxSpeedMps;
        double angularZ = clamp(steerNorm, -1, 1) * maxSteerAngleRad;
        return new Twist(linearX, angularZ);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
