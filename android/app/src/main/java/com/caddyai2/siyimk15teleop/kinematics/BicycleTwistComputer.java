package com.caddyai2.siyimk15teleop.kinematics;

/**
 * Converts normalized steer/throttle in [-1, 1] into a bicycle-model
 * {@code geometry_msgs/Twist} (linear.x, angular.z), the inverse of the
 * formula used by {@code bicycle_cmd_relay} on the robot
 * (caddy_ai2_ros2_controllers): {@code angle = atan2(wheelbase * angular_z, linear_x)}.
 *
 * <p><b>Known limitation (not fixed here, see PROTOCOL.md / README):</b>
 * {@code atan2(y, x)} only returns a value in {@code (-pi/2, pi/2)} when
 * {@code x > 0}. So whenever {@code linear_x < 0} (reverse), {@code bicycle_cmd_relay}
 * cannot recover the steering angle we intended — it reconstructs an angle with
 * magnitude near {@code pi} (verified in {@code BicycleTwistComputerTest}), which
 * then saturates to {@code max_steer_angle} downstream, and with the *opposite*
 * sign of what was intended (e.g. steer left while reversing gets recovered as a
 * large *right* angle). This is a property of {@code bicycle_cmd_relay}'s own
 * {@code atan2}-based recovery, not something this class can fix on the
 * transmitting side — no choice of {@code angular_z} makes it exact for
 * {@code linear_x < 0}. Fixing it means changing {@code bicycle_cmd_relay} to use
 * {@code atan} (or an explicit sign-aware formula) instead of {@code atan2}.
 */
public final class BicycleTwistComputer {

    private static final double EPS = 1e-3;

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
        double linearX = clamp(throttleNorm, -1, 1) * maxSpeedMps;
        double steerAngle = clamp(steerNorm, -1, 1) * maxSteerAngleRad;

        double angularZ;
        if (Math.abs(linearX) > EPS) {
            angularZ = linearX * Math.tan(steerAngle) / wheelbaseMeters;
        } else {
            // Vehicle isn't moving: no yaw rate makes physical sense for a car-like
            // (non-turn-in-place) platform, regardless of steering wheel position.
            angularZ = 0.0;
        }
        return new Twist(linearX, angularZ);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
