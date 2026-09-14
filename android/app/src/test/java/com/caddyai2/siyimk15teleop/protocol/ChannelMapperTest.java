package com.caddyai2.siyimk15teleop.protocol;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ChannelMapperTest {

    private final ChannelMapper mapper = new ChannelMapper(); // center=1500, halfRange=500, deadzone=20

    @Test
    public void centerIsZero() {
        assertEquals(0.0, mapper.normalize(1500), 1e-9);
    }

    @Test
    public void withinDeadzoneIsZero() {
        assertEquals(0.0, mapper.normalize(1500 + 20), 1e-9);
        assertEquals(0.0, mapper.normalize(1500 - 20), 1e-9);
        assertEquals(0.0, mapper.normalize(1510), 1e-9);
    }

    @Test
    public void extremesMapToPlusMinusOne() {
        assertEquals(1.0, mapper.normalize(2000), 1e-9);
        assertEquals(-1.0, mapper.normalize(1000), 1e-9);
    }

    @Test
    public void continuousAtDeadzoneEdge() {
        // Just past the deadzone edge should be close to 0, not jump to a large value.
        double justPast = mapper.normalize(1521);
        assertEquals(0.0, justPast, 0.01);
    }

    @Test
    public void outOfRangeInputIsClamped() {
        assertEquals(1.0, mapper.normalize(2500), 1e-9);
        assertEquals(-1.0, mapper.normalize(500), 1e-9);
    }

    @Test
    public void ch2CrosstalkExampleStaysWithinSmallMagnitudeIfTreatedAsSteer() {
        // From the empirical notes: CH2 showed 1500-1651 crosstalk when moving CH1.
        // Demonstrates why a deadzone matters — without one, 1651 would read as a
        // real ~+0.3 command instead of being recognized as mechanical crosstalk.
        // (CH2 itself is never fed into the mapper in production; this just documents
        // the reasoning with a concrete number from the field notes.)
        double normalized = mapper.normalize(1651);
        assertEquals((1651 - 1500 - 20) / (double) (500 - 20), normalized, 1e-9);
    }

    @Test
    public void indicesMatchConfirmedMapping() {
        assertEquals(0, ChannelMapper.CHANNEL_INDEX_STEER);
        assertEquals(2, ChannelMapper.CHANNEL_INDEX_THROTTLE);
        assertEquals(6, ChannelMapper.CHANNEL_INDEX_PROFILE_SWITCH);
    }

    @Test
    public void threeWaySwitchBucketsRealCapturedDetentValues() {
        // ~1050/1500/1950 are the real resting values seen on this handset's switch cluster
        // (2026-09-14 on-device capture) — not just the theoretical 1000/1500/2000.
        assertEquals(0, ChannelMapper.threeWaySwitchPosition(1050));
        assertEquals(1, ChannelMapper.threeWaySwitchPosition(1500));
        assertEquals(2, ChannelMapper.threeWaySwitchPosition(1950));
    }

    @Test
    public void threeWaySwitchIsRobustToJitterNearDetents() {
        assertEquals(0, ChannelMapper.threeWaySwitchPosition(1000));
        assertEquals(0, ChannelMapper.threeWaySwitchPosition(1299));
        assertEquals(1, ChannelMapper.threeWaySwitchPosition(1300));
        assertEquals(1, ChannelMapper.threeWaySwitchPosition(1700));
        assertEquals(2, ChannelMapper.threeWaySwitchPosition(1701));
        assertEquals(2, ChannelMapper.threeWaySwitchPosition(2000));
    }

    @Test
    public void profileSwitchPositionReadsChannelIndex6() {
        int[] channels = new int[16];
        channels[6] = 1950;
        assertEquals(2, mapper.profileSwitchPosition(channels));
    }
}
