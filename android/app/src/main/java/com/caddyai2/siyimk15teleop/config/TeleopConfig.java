package com.caddyai2.siyimk15teleop.config;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Vehicle/link parameters configurable from the app UI, persisted in
 * SharedPreferences. Drives the steer/throttle -&gt; steering reference
 * conversion (see {@code BicycleTwistComputer}) and the transport settings.
 *
 * <p><b>Driving profiles (2026-09-14)</b>: max steer angle and max speed are no longer single
 * values — the MK15's CH7 three-position switch ({@link
 * com.caddyai2.siyimk15teleop.protocol.ChannelMapper#CHANNEL_INDEX_PROFILE_SWITCH}) selects one
 * of 3 profiles live, synced (one switch controls both values together, per scenario — e.g. a
 * tighter/slower "maniobra" profile vs. a wider/faster "transporte" one). See
 * {@link #getProfile(int)} and {@code MainActivity}'s per-frame profile selection.
 */
public final class TeleopConfig {

    private static final String PREFS_NAME = "teleop_config";

    public static final int PROFILE_COUNT = 3;

    private static final String KEY_PROFILE_NAME_PREFIX = "profile_name_";
    private static final String KEY_PROFILE_MAX_STEER_DEG_PREFIX = "profile_max_steer_deg_";
    private static final String KEY_PROFILE_MAX_SPEED_MPS_PREFIX = "profile_max_speed_mps_";
    private static final String KEY_TOPIC_NAME = "topic_name";
    private static final String KEY_FRAME_ID = "frame_id";
    private static final String KEY_DOMAIN_ID = "domain_id";
    private static final String KEY_PUBLISH_RATE_HZ = "publish_rate_hz";
    private static final String KEY_LOCAL_STALE_TIMEOUT_MS = "local_stale_timeout_ms";

    // Defaults: profile 1 is the caddy_ai2_ros2_controllers historical single-profile config
    // (config/controllers.yaml's bicycle_to_ackermann_steering_adapter section); 0 and 2 are
    // starting points for a tighter/slower and a wider/faster scenario respectively — all three
    // fully editable per-vehicle in the UI, these are just sane starting points.
    public static final String[] DEFAULT_PROFILE_NAMES = {"Maniobra", "Normal", "Transporte"};
    public static final double[] DEFAULT_PROFILE_MAX_STEER_DEG = {22.9, 22.9, 15.0};
    public static final double[] DEFAULT_PROFILE_MAX_SPEED_MPS = {0.5, 1.5, 2.5};

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

    public static final class Profile {
        public final String name;
        public final double maxSteerAngleDeg;
        public final double maxSpeedMps;

        Profile(String name, double maxSteerAngleDeg, double maxSpeedMps) {
            this.name = name;
            this.maxSteerAngleDeg = maxSteerAngleDeg;
            this.maxSpeedMps = maxSpeedMps;
        }

        public double maxSteerAngleRad() {
            return Math.toRadians(maxSteerAngleDeg);
        }
    }

    private static void requireValidIndex(int index) {
        if (index < 0 || index >= PROFILE_COUNT) {
            throw new IllegalArgumentException("profile index must be in [0, " + (PROFILE_COUNT - 1) + "]");
        }
    }

    public Profile getProfile(int index) {
        requireValidIndex(index);
        String name = prefs.getString(KEY_PROFILE_NAME_PREFIX + index, DEFAULT_PROFILE_NAMES[index]);
        double maxSteerDeg = getDouble(KEY_PROFILE_MAX_STEER_DEG_PREFIX + index, DEFAULT_PROFILE_MAX_STEER_DEG[index]);
        double maxSpeedMps = getDouble(KEY_PROFILE_MAX_SPEED_MPS_PREFIX + index, DEFAULT_PROFILE_MAX_SPEED_MPS[index]);
        return new Profile(name, maxSteerDeg, maxSpeedMps);
    }

    public void setProfile(int index, String name, double maxSteerAngleDeg, double maxSpeedMps) {
        requireValidIndex(index);
        prefs.edit()
                .putString(KEY_PROFILE_NAME_PREFIX + index, name)
                .putLong(KEY_PROFILE_MAX_STEER_DEG_PREFIX + index, Double.doubleToLongBits(maxSteerAngleDeg))
                .putLong(KEY_PROFILE_MAX_SPEED_MPS_PREFIX + index, Double.doubleToLongBits(maxSpeedMps))
                .apply();
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
}
