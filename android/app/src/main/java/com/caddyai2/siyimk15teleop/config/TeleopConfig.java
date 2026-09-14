package com.caddyai2.siyimk15teleop.config;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Vehicle/link parameters configurable from the app UI, persisted in
 * SharedPreferences. These are the numbers the user asked to be able to set
 * without a rebuild: wheelbase, max steering angle and max speed drive the
 * steer/throttle -&gt; Twist conversion (see {@code BicycleTwistComputer}); the
 * rest are transport settings.
 */
public final class TeleopConfig {

    private static final String PREFS_NAME = "teleop_config";

    private static final String KEY_WHEELBASE_M = "wheelbase_m";
    private static final String KEY_MAX_STEER_DEG = "max_steer_deg";
    private static final String KEY_MAX_SPEED_MPS = "max_speed_mps";
    private static final String KEY_TOPIC_NAME = "topic_name";
    private static final String KEY_FRAME_ID = "frame_id";
    private static final String KEY_DOMAIN_ID = "domain_id";
    private static final String KEY_PUBLISH_RATE_HZ = "publish_rate_hz";
    private static final String KEY_LOCAL_STALE_TIMEOUT_MS = "local_stale_timeout_ms";

    // Defaults from caddy_ai2_ros2_controllers/config/controllers.yaml
    // (bicycle_to_ackermann_steering_adapter section) — override per-vehicle in the UI.
    public static final double DEFAULT_WHEELBASE_M = 1.65;
    public static final double DEFAULT_MAX_STEER_DEG = 22.9; // ~0.4 rad
    public static final double DEFAULT_MAX_SPEED_MPS = 1.5;
    // Published directly to a ros2_controllers steering_controllers_library controller's
    // reference topic (2026-09-14, superseded bicycle_cmd_relay — see README/PROTOCOL.md) —
    // this MUST match the actual controller instance name configured on the robot; the default
    // here is a placeholder, not a discovered value.
    public static final String DEFAULT_TOPIC_NAME = "/bicycle_steering_controller/reference";
    public static final String DEFAULT_FRAME_ID = "base_link";
    public static final int DEFAULT_DOMAIN_ID = 0;
    public static final int DEFAULT_PUBLISH_RATE_HZ = 50;
    public static final long DEFAULT_LOCAL_STALE_TIMEOUT_MS = 300;

    private final SharedPreferences prefs;

    public TeleopConfig(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public double getWheelbaseMeters() {
        return getDouble(KEY_WHEELBASE_M, DEFAULT_WHEELBASE_M);
    }

    public void setWheelbaseMeters(double value) {
        putDouble(KEY_WHEELBASE_M, value);
    }

    public double getMaxSteerAngleDeg() {
        return getDouble(KEY_MAX_STEER_DEG, DEFAULT_MAX_STEER_DEG);
    }

    public void setMaxSteerAngleDeg(double value) {
        putDouble(KEY_MAX_STEER_DEG, value);
    }

    public double getMaxSteerAngleRad() {
        return Math.toRadians(getMaxSteerAngleDeg());
    }

    public double getMaxSpeedMps() {
        return getDouble(KEY_MAX_SPEED_MPS, DEFAULT_MAX_SPEED_MPS);
    }

    public void setMaxSpeedMps(double value) {
        putDouble(KEY_MAX_SPEED_MPS, value);
    }

    public String getTopicName() {
        return prefs.getString(KEY_TOPIC_NAME, DEFAULT_TOPIC_NAME);
    }

    public void setTopicName(String value) {
        prefs.edit().putString(KEY_TOPIC_NAME, value).apply();
    }

    public String getFrameId() {
        return prefs.getString(KEY_FRAME_ID, DEFAULT_FRAME_ID);
    }

    public void setFrameId(String value) {
        prefs.edit().putString(KEY_FRAME_ID, value).apply();
    }

    public int getDomainId() {
        return prefs.getInt(KEY_DOMAIN_ID, DEFAULT_DOMAIN_ID);
    }

    public void setDomainId(int value) {
        prefs.edit().putInt(KEY_DOMAIN_ID, value).apply();
    }

    public int getPublishRateHz() {
        return prefs.getInt(KEY_PUBLISH_RATE_HZ, DEFAULT_PUBLISH_RATE_HZ);
    }

    public void setPublishRateHz(int value) {
        prefs.edit().putInt(KEY_PUBLISH_RATE_HZ, value).apply();
    }

    public long getLocalStaleTimeoutMs() {
        return prefs.getLong(KEY_LOCAL_STALE_TIMEOUT_MS, DEFAULT_LOCAL_STALE_TIMEOUT_MS);
    }

    public void setLocalStaleTimeoutMs(long value) {
        prefs.edit().putLong(KEY_LOCAL_STALE_TIMEOUT_MS, value).apply();
    }

    private double getDouble(String key, double defaultValue) {
        return Double.longBitsToDouble(prefs.getLong(key, Double.doubleToLongBits(defaultValue)));
    }

    private void putDouble(String key, double value) {
        prefs.edit().putLong(key, Double.doubleToLongBits(value)).apply();
    }
}
