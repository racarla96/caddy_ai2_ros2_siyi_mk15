package com.caddyai2.siyimk15teleop.protocol;

/**
 * Normalizes a raw joystick channel (~1000-2000, center 1500) to [-1, 1] with a
 * configurable deadzone around center, continuous at the deadzone edge (no jump:
 * output is exactly 0 at the edge and ramps linearly from there to +-1).
 */
public final class ChannelMapper {

    public static final int DEFAULT_CENTER = 1500;
    public static final int DEFAULT_HALF_RANGE = 500; // 1500 +-500 => [1000, 2000]
    public static final int DEFAULT_DEADZONE = 20;

    /** CH1 (array index 0): lateral / steering (Aileron, J1). */
    public static final int CHANNEL_INDEX_STEER = 0;
    /** CH3 (array index 2): throttle (J3). */
    public static final int CHANNEL_INDEX_THROTTLE = 2;

    private final int center;
    private final int halfRange;
    private final int deadzone;

    public ChannelMapper() {
        this(DEFAULT_CENTER, DEFAULT_HALF_RANGE, DEFAULT_DEADZONE);
    }

    public ChannelMapper(int center, int halfRange, int deadzone) {
        if (halfRange <= deadzone) {
            throw new IllegalArgumentException("halfRange must be > deadzone");
        }
        this.center = center;
        this.halfRange = halfRange;
        this.deadzone = deadzone;
    }

    /** Maps a raw channel value to [-1, 1], applying the deadzone around center. */
    public double normalize(int rawValue) {
        int delta = rawValue - center;
        int magnitude = Math.abs(delta);
        if (magnitude <= deadzone) {
            return 0.0;
        }
        double scaled = (magnitude - deadzone) / (double) (halfRange - deadzone);
        scaled = Math.max(0.0, Math.min(1.0, scaled));
        return delta < 0 ? -scaled : scaled;
    }

    public double normalizeSteer(int[] channels) {
        return normalize(channels[CHANNEL_INDEX_STEER]);
    }

    public double normalizeThrottle(int[] channels) {
        return normalize(channels[CHANNEL_INDEX_THROTTLE]);
    }
}
