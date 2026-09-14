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

    /** Mirrors steering_controllers_library's own recovery (steering_kinematics.cpp). */
    private static double recoverAngle(double linearX, double angularZ) {
        return Math.atan(angularZ * WHEELBASE / linearX);
    }

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
    public void stoppedVehicleWithSteerUsesCreepToPreserveTheAngle() {
        // Unlike the old behavior (angularZ forced to 0 when stopped), a real steer command with
        // the throttle centered must still recover exactly on the robot side — steering_controllers_
        // library's atan(angularZ*wheelbase/linearX) is undefined at linearX==0, so this class now
        // publishes a small nonzero "creep" linearX instead of a literal zero.
        BicycleTwistComputer.Twist t = computer.compute(1.0, 0.0);
        assertTrue("expected a small nonzero creep, not exactly 0", t.linearX != 0.0);
        assertTrue("creep should be a small crawl, not real driving speed",
                Math.abs(t.linearX) < 0.1 * MAX_SPEED);
        assertEquals(MAX_STEER_RAD, recoverAngle(t.linearX, t.angularZ), 1e-9);
    }

    @Test
    public void stoppedVehicleWithNoSteerPublishesExactZero() {
        // Centered stick (steerNorm=0) needs no creep: linearX=0, angularZ=0 already recovers
        // phi=0 correctly on the robot side (0/0 -> NaN -> mapped to 0 there).
        BicycleTwistComputer.Twist t = computer.compute(0.0, 0.0);
        assertEquals(0.0, t.linearX, 1e-9);
        assertEquals(0.0, t.angularZ, 1e-9);
    }

    @Test
    public void forwardWithSteerRecoversExactAngleViaAtan() {
        BicycleTwistComputer.Twist t = computer.compute(0.5, 0.8);
        double intendedAngle = 0.5 * MAX_STEER_RAD;
        assertEquals(intendedAngle, recoverAngle(t.linearX, t.angularZ), 1e-9);
    }

    @Test
    public void reverseWithSteerAlsoRecoversExactAngleViaAtan() {
        // In contrast to bicycle_cmd_relay's old atan2-based recovery (which could not recover
        // the intended angle while reversing — see git history), steering_controllers_library's
        // atan-based recovery is sign-preserving: this round-trips exactly for vx < 0 too.
        BicycleTwistComputer.Twist t = computer.compute(0.5, -0.8);
        double intendedAngle = 0.5 * MAX_STEER_RAD;
        assertEquals(intendedAngle, recoverAngle(t.linearX, t.angularZ), 1e-9);
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
