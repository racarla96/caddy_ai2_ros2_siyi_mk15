package com.caddyai2.siyimk15teleop.ros2;

import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import geometry_msgs.Twist;
import us.ihmc.jros2.ROS2Node;
import us.ihmc.jros2.ROS2Publisher;
import us.ihmc.jros2.ROS2QoSProfile;
import us.ihmc.jros2.ROS2Topic;

/**
 * Owns the ROS 2 node/publisher and republishes the latest computed Twist at a
 * fixed rate (independent of how often new joystick frames arrive), per the
 * "stable heartbeat" requirement.
 *
 * <p>This publisher does <b>not</b> implement the cross-network watchdog — by
 * design that lives on the robot side (it has to cover WiFi/DDS message loss,
 * which this process cannot observe about itself). It does, however, zero the
 * command if the local serial link to the MK15 joystick hardware itself goes
 * stale, which is a distinct failure mode (see {@link #onChannelFrameStale()}).
 */
public final class TwistCmdVelPublisher {

    private static final String TAG = "TwistCmdVelPublisher";

    private final String topicName;
    private final int domainId;
    private final int publishRateHz;
    private final long localStaleTimeoutMs;

    private ROS2Node node;
    private ROS2Publisher<Twist> publisher;
    private Thread publishThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile double linearX = 0.0;
    private volatile double angularZ = 0.0;
    private final AtomicLong lastUpdateAtMs = new AtomicLong(0);

    public TwistCmdVelPublisher(String topicName, int domainId, int publishRateHz, long localStaleTimeoutMs) {
        this.topicName = topicName;
        this.domainId = domainId;
        this.publishRateHz = publishRateHz;
        this.localStaleTimeoutMs = localStaleTimeoutMs;
    }

    public synchronized void start() {
        if (running.get()) {
            return;
        }
        node = new ROS2Node("siyi_mk15_teleop", domainId);
        ROS2Topic<Twist> topic = new ROS2Topic<>(topicName, Twist.class).withQoS(ROS2QoSProfile.BEST_EFFORT);
        publisher = node.createPublisher(topic);
        running.set(true);
        publishThread = new Thread(this::publishLoop, "siyi-cmd-vel-publish");
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
        Twist msg = new Twist();
        while (running.get()) {
            double vx = isLocalLinkStale() ? 0.0 : linearX;
            double wz = isLocalLinkStale() ? 0.0 : angularZ;

            msg.getLinear().setX(vx);
            msg.getLinear().setY(0.0);
            msg.getLinear().setZ(0.0);
            msg.getAngular().setX(0.0);
            msg.getAngular().setY(0.0);
            msg.getAngular().setZ(wz);

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
