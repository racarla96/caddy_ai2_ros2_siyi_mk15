package com.caddyai2.siyimk15teleop.kinematics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BicycleTwistComputerTest {

    private static final double WHEELBASE = 1.65;
    private static final double MAX_STEER_RAD = Math.toRadians(22.9);
    private static final double MAX_SPEED = 1.5;

    private final BicycleTwistComputer computer =
            new BicycleTwistComputer(WHEELBASE, MAX_STEER_RAD, MAX_SPEED);

    @Test
    public void centeredInputIsZeroTwist() {
        BicycleTwistComputer.Twist t = computer.compute(0.0, 0.0);
        assertEquals(0.0, t.linearX, 1e-9);
        assertEquals(0.0, t.angularZ, 1e-9);
    }

    @Test
    public void fullThrottleNoSteerIsStraightLine() {
        BicycleTwistComputer.Twist t = computer.compute(0.0, 1.0);
        assertEquals(MAX_SPEED, t.linearX, 1e-9);
        assertEquals(0.0, t.angularZ, 1e-9);
    }

    @Test
    public void stoppedVehicleNeverProducesYawRate() {
        BicycleTwistComputer.Twist t = computer.compute(1.0, 0.0);
        assertEquals(0.0, t.linearX, 1e-9);
        assertEquals(0.0, t.angularZ, 1e-9);
    }

    @Test
    public void forwardWithSteerRoundTripsThroughBicycleCmdRelayFormula() {
        // Mirrors bicycle_cmd_relay's own inverse: angle = atan2(wheelbase*wz, vx).
        // For vx > 0 this must recover exactly the steering angle we intended.
        BicycleTwistComputer.Twist t = computer.compute(0.5, 0.8);
        double vx = t.linearX;
        double wz = t.angularZ;
        double recoveredAngle = Math.atan2(WHEELBASE * wz, vx);
        double intendedAngle = 0.5 * MAX_STEER_RAD;
        assertEquals(intendedAngle, recoveredAngle, 1e-9);
    }

    @Test
    public void reverseWithSteerDoesNotRoundTripCleanly() {
        // Known limitation documented on BicycleTwistComputer: atan2(y, x) can only
        // land in (-pi/2, pi/2) when x > 0, so bicycle_cmd_relay cannot recover the
        // steering angle while vx < 0 — it comes back with the OPPOSITE sign and a
        // magnitude near pi (which then saturates to max_steer_angle downstream).
        // This test pins down (rather than silently hides) that behavior so a
        // future fix to bicycle_cmd_relay has a regression test to satisfy.
        BicycleTwistComputer.Twist t = computer.compute(0.5, -0.8);
        double vx = t.linearX;
        double wz = t.angularZ;
        double recoveredAngle = Math.atan2(WHEELBASE * wz, vx);
        double intendedAngle = 0.5 * MAX_STEER_RAD;

        assertEquals(-Math.signum(intendedAngle), Math.signum(recoveredAngle), 1e-9);
        assertTrue(Math.abs(recoveredAngle) > Math.PI / 2);
    }

    @Test
    public void inputIsClampedBeyondUnitRange() {
        BicycleTwistComputer.Twist t = computer.compute(2.0, -2.0);
        assertEquals(-MAX_SPEED, t.linearX, 1e-9);
        double expectedAngularZ = -MAX_SPEED * Math.tan(MAX_STEER_RAD) / WHEELBASE;
        assertEquals(expectedAngularZ, t.angularZ, 1e-9);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveWheelbase() {
        new BicycleTwistComputer(0.0, MAX_STEER_RAD, MAX_SPEED);
    }
}
