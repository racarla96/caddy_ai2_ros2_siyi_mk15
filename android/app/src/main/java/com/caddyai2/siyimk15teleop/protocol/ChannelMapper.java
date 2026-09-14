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
    /**
     * CH7 (array index 6): one of the MK15's three 3-position switches (see
     * SDK_COMMANDS.md's channel-mapping table, CH5-7). Used (2026-09-14) to select which
     * {@code TeleopConfig} steering profile (max steer angle + max speed, synced) is active —
     * see {@code MainActivity}.
     */
    public static final int CHANNEL_INDEX_PROFILE_SWITCH = 6;

    // Real captured resting values for a 3-position switch cluster on this handset (2026-09-14
    // on-device capture) fell around ~1050 (low) / ~1500 (mid) / ~1950 (high) — the same
    // +-450-from-center detent spacing as the documented stick range, just discrete. Bucketing
    // at the midpoints between those keeps this robust to the usual +-20-ish channel jitter.
    private static final int SWITCH_LOW_HIGH_THRESHOLD_LOW = 1300;
    private static final int SWITCH_LOW_HIGH_THRESHOLD_HIGH = 1700;

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

    /**
     * Buckets a raw 3-position switch channel into {@code 0} (low), {@code 1} (mid) or
     * {@code 2} (high). Unlike {@link #normalize}, this is a discrete classification, not a
     * continuous scale — there is no meaningful "in between" for a physical 3-way switch.
     */
    public static int threeWaySwitchPosition(int rawValue) {
        if (rawValue < SWITCH_LOW_HIGH_THRESHOLD_LOW) {
            return 0;
        }
        if (rawValue > SWITCH_LOW_HIGH_THRESHOLD_HIGH) {
            return 2;
        }
        return 1;
    }

    /** Convenience overload reading {@link #CHANNEL_INDEX_PROFILE_SWITCH} from a full frame. */
    public int profileSwitchPosition(int[] channels) {
        return threeWaySwitchPosition(channels[CHANNEL_INDEX_PROFILE_SWITCH]);
    }
}
