package com.caddyai2.siyimk15teleop.kinematics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BicycleTwistComputerTest {

    private static final double MAX_STEER_RAD = Math.toRadians(22.9);
    private static final double MAX_SPEED = 1.5;

    private final BicycleTwistComputer computer =
            new BicycleTwistComputer(MAX_STEER_RAD, MAX_SPEED);

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
    public void stoppedVehicleWithSteerUsesCreepToPreserveTheRatio() {
        // Unlike the old wheelbase-based behavior (angularZ forced to 0 when stopped), a real
        // steer command with the throttle centered must still publish a nonzero, well-defined
        // angularZ/linearX ratio — never a literal 0/0.
        BicycleTwistComputer.Twist t = computer.compute(1.0, 0.0);
        assertTrue("expected a small nonzero creep, not exactly 0", t.linearX != 0.0);
        assertTrue("creep should be a small crawl, not real driving speed",
                Math.abs(t.linearX) < 0.1 * MAX_SPEED);
        assertEquals(Math.tan(MAX_STEER_RAD), t.angularZ / t.linearX, 1e-9);
    }

    @Test
    public void stoppedVehicleWithNoSteerPublishesExactZero() {
        // Centered stick (steerNorm=0) needs no creep: linearX=0, angularZ=0.
        BicycleTwistComputer.Twist t = computer.compute(0.0, 0.0);
        assertEquals(0.0, t.linearX, 1e-9);
        assertEquals(0.0, t.angularZ, 1e-9);
    }

    @Test
    public void angularZIsProportionalToSteerFractionRegardlessOfWheelbase() {
        // No wheelbase term anymore: angularZ = linearX * tan(steerNorm * maxSteerAngleRad).
        BicycleTwistComputer.Twist t = computer.compute(0.5, 0.8);
        double expectedLinearX = 0.8 * MAX_SPEED;
        double expectedAngularZ = expectedLinearX * Math.tan(0.5 * MAX_STEER_RAD);
        assertEquals(expectedLinearX, t.linearX, 1e-9);
        assertEquals(expectedAngularZ, t.angularZ, 1e-9);
    }

    @Test
    public void reverseWithSteerKeepsTheSameSteerRatio() {
        // atan is odd/sign-preserving for linearX < 0, unlike bicycle_cmd_relay's old
        // atan2-based recovery (which flipped sign while reversing) — the ratio angularZ/linearX
        // stays tan(steerAngle) regardless of the sign of linearX.
        BicycleTwistComputer.Twist t = computer.compute(0.5, -0.8);
        assertEquals(Math.tan(0.5 * MAX_STEER_RAD), t.angularZ / t.linearX, 1e-9);
    }

    @Test
    public void inputIsClampedBeyondUnitRange() {
        BicycleTwistComputer.Twist t = computer.compute(2.0, -2.0);
        assertEquals(-MAX_SPEED, t.linearX, 1e-9);
        double expectedAngularZ = -MAX_SPEED * Math.tan(MAX_STEER_RAD);
        assertEquals(expectedAngularZ, t.angularZ, 1e-9);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveMaxSteerAngle() {
        new BicycleTwistComputer(0.0, MAX_SPEED);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveMaxSpeed() {
        new BicycleTwistComputer(MAX_STEER_RAD, 0.0);
    }
}
