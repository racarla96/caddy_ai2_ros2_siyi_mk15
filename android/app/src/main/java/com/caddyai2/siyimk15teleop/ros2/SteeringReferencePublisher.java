package com.caddyai2.siyimk15teleop.ros2;

import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import geometry_msgs.TwistStamped;
import us.ihmc.jros2.ROS2Node;
import us.ihmc.jros2.ROS2Publisher;
import us.ihmc.jros2.ROS2QoSProfile;
import us.ihmc.jros2.ROS2Topic;

/**
 * Owns the ROS 2 node/publisher and republishes the latest computed steering reference at a
 * fixed rate (independent of how often new joystick frames arrive), per the "stable heartbeat"
 * requirement.
 *
 * <p>Publishes directly to a {@code ros2_controllers} {@code steering_controllers_library}
 * controller's {@code <controller_name>/reference} topic (see the "Subscribers" section of
 * <a href="https://control.ros.org/rolling/doc/ros2_controllers/steering_controllers_library/doc/userdoc.html">
 * its userdoc</a> — used when the controller is not in chained mode) as
 * {@code geometry_msgs/TwistStamped}, <b>not</b> {@code bicycle_cmd_relay}'s plain {@code Twist}
 * (superseded 2026-09-14 — see README/PROTOCOL.md and {@link
 * com.caddyai2.siyimk15teleop.kinematics.BicycleTwistComputer}'s Javadoc for why). {@code
 * topicName} in {@code TeleopConfig} must be set to the real controller instance's reference
 * topic (e.g. {@code /bicycle_steering_controller/reference}), not a generic {@code cmd_vel}
 * name — there is no vendor-side relay renaming it anymore.
 *
 * <p><b>Clock caveat, not yet resolved</b>: the header {@code stamp} is this Android device's
 * own wall-clock time ({@link System#currentTimeMillis()}), not a ROS-time-synced clock. If the
 * target controller enforces a reference-message timeout by comparing this stamp against its own
 * node clock (common in {@code ros2_controllers}' chainable controllers), a clock skew between
 * this handset and the robot's clock could make every message look stale and get rejected even
 * though it arrived promptly. Not yet tested against the real robot controller's timeout
 * behavior — if commands get silently dropped, check for clock skew (e.g. via NTP/chrony on both
 * sides) before assuming a DDS/network problem.
 *
 * <p>This publisher does <b>not</b> implement the cross-network watchdog — by design that lives
 * on the robot side (it has to cover WiFi/DDS message loss, which this process cannot observe
 * about itself). It does, however, zero the command if the local serial link to the MK15
 * joystick hardware itself goes stale, which is a distinct failure mode (see {@link
 * #isLocalLinkStale()}).
 */
public final class SteeringReferencePublisher {

    private static final String TAG = "SteeringReferencePublisher";

    private final String topicName;
    private final String frameId;
    private final int domainId;
    private final int publishRateHz;
    private final long localStaleTimeoutMs;

    private ROS2Node node;
    private ROS2Publisher<TwistStamped> publisher;
    private Thread publishThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile double linearX = 0.0;
    private volatile double angularZ = 0.0;
    private final AtomicLong lastUpdateAtMs = new AtomicLong(0);

    public SteeringReferencePublisher(String topicName, String frameId, int domainId,
                                       int publishRateHz, long localStaleTimeoutMs) {
        this.topicName = topicName;
        this.frameId = frameId;
        this.domainId = domainId;
        this.publishRateHz = publishRateHz;
        this.localStaleTimeoutMs = localStaleTimeoutMs;
    }

    public synchronized void start() {
        if (running.get()) {
            return;
        }
        node = new ROS2Node("siyi_mk15_teleop", domainId);
        ROS2Topic<TwistStamped> topic =
                new ROS2Topic<>(topicName, TwistStamped.class).withQoS(ROS2QoSProfile.BEST_EFFORT);
        publisher = node.createPublisher(topic);
        running.set(true);
        publishThread = new Thread(this::publishLoop, "siyi-steering-ref-publish");
        publishThread.start();
        Log.i(TAG, "Publishing " + topicName + " at " + publishRateHz + " Hz, domain " + domainId);
    }

    public synchronized void stop() {
        running.set(false);
        if (publishThread != null) {
            publishThread.interrupt();
            publishThread = null;
        }
        if (node != null) {
            node.close();
            node = null;
        }
    }

    /** Called from the frame-decode thread with the freshly computed command. */
    public void updateCommand(double linearX, double angularZ) {
        this.linearX = linearX;
        this.angularZ = angularZ;
        this.lastUpdateAtMs.set(System.currentTimeMillis());
    }

    /** True once no channel frame has produced an update within the local timeout. */
    public boolean isLocalLinkStale() {
        long last = lastUpdateAtMs.get();
        return last == 0 || (System.currentTimeMillis() - last) > localStaleTimeoutMs;
    }

    private void publishLoop() {
        long periodMs = Math.max(1, 1000 / publishRateHz);
        TwistStamped msg = new TwistStamped();
        msg.getHeader().setFrameId(frameId);
        while (running.get()) {
            double vx = isLocalLinkStale() ? 0.0 : linearX;
            double wz = isLocalLinkStale() ? 0.0 : angularZ;

            long nowMs = System.currentTimeMillis();
            msg.getHeader().getStamp().setSec((int) (nowMs / 1000));
            msg.getHeader().getStamp().setNanosec((int) ((nowMs % 1000) * 1_000_000L));

            msg.getTwist().getLinear().setX(vx);
            msg.getTwist().getLinear().setY(0.0);
            msg.getTwist().getLinear().setZ(0.0);
            msg.getTwist().getAngular().setX(0.0);
            msg.getTwist().getAngular().setY(0.0);
            msg.getTwist().getAngular().setZ(wz);

            try {
                publisher.publish(msg);
            } catch (RuntimeException e) {
                Log.e(TAG, "publish() failed", e);
            }

            try {
                Thread.sleep(periodMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
