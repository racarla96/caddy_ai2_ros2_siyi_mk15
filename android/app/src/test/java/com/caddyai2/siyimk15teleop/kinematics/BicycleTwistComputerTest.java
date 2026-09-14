package com.caddyai2.siyimk15teleop.kinematics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
    public void steerAloneProducesAngularZWithThrottleAtZero() {
        // The explicit requirement (2026-09-14, three rounds of clarification): angular.z must
        // reflect the steer stick regardless of throttle -- no cross-multiplication, no "creep"
        // needed, no dependency on linearX at all.
        BicycleTwistComputer.Twist t = computer.compute(1.0, 0.0);
        assertEquals(0.0, t.linearX, 1e-9);
        assertEquals(MAX_STEER_RAD, t.angularZ, 1e-9);
    }

    @Test
    public void throttleAloneProducesLinearXWithSteerAtZero() {
        BicycleTwistComputer.Twist t = computer.compute(0.0, -0.6);
        assertEquals(-0.6 * MAX_SPEED, t.linearX, 1e-9);
        assertEquals(0.0, t.angularZ, 1e-9);
    }

    @Test
    public void steerAndThrottleAreFullyIndependent() {
        // Changing throttle must not change angularZ, and vice versa.
        BicycleTwistComputer.Twist a = computer.compute(0.5, 0.0);
        BicycleTwistComputer.Twist b = computer.compute(0.5, 1.0);
        BicycleTwistComputer.Twist c = computer.compute(0.5, -1.0);
        assertEquals(a.angularZ, b.angularZ, 1e-9);
        assertEquals(a.angularZ, c.angularZ, 1e-9);
        assertEquals(0.5 * MAX_STEER_RAD, a.angularZ, 1e-9);
    }

    @Test
    public void inputIsClampedBeyondUnitRange() {
        BicycleTwistComputer.Twist t = computer.compute(2.0, -2.0);
        assertEquals(-MAX_SPEED, t.linearX, 1e-9);
        assertEquals(MAX_STEER_RAD, t.angularZ, 1e-9);
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
